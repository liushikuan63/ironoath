package com.ironoath.web.mail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;
import com.ironoath.web.dto.generated.MailClaimAllReq;
import com.ironoath.web.dto.generated.MailClaimAllResp;
import com.ironoath.web.dto.generated.MailClaimFailure;
import com.ironoath.web.dto.generated.MailKind;
import com.ironoath.web.dto.generated.MailListResp;
import com.ironoath.web.dto.generated.MailReadReq;
import com.ironoath.web.dto.generated.MailReadResp;
import com.ironoath.web.dto.generated.MailReward;
import com.ironoath.web.dto.generated.MailView;
import com.ironoath.web.dto.generated.OpsMailSendReq;
import com.ironoath.web.dto.generated.OpsMailSendResp;
import com.ironoath.web.mail.MailStore.MailRecord;
import com.ironoath.web.mail.MailStore.RewardLine;
import com.ironoath.web.reward.RewardNames;

/**
 * 职责：邮件面板（B12 §2）—— 列表、一键领取、标已读、以及运营/客服补发的写入口。
 * 依赖：{@link MailStore}（两份实现语义一致）、发放器（奖励不得绕过它）、幂等、玩家锁、
 *       {@link RewardNames}（附件显示名与任务/背包同源）。
 *
 * <p><b>一键领取不带 mailId 列表</b>（B12 禁止项「不要让一键领邮件发 N 次请求」的实现侧含义）：
 * 「哪几封可领」是服务端状态，客户端只该说「全领」。带列表的版本会把判断搬到客户端，
 * 于是客户端缓存的列表与服务端的真实状态之间的窗口期就是重复领取的入口。
 *
 * <p><b>领取顺序是「先占格再发货，发货失败就退回」</b>：
 * {@link MailStore#claim} 赢下这一封之后才发奖；发不出去（背包满到溢出策略拒绝、子系统抛错）
 * 就用 {@link MailStore#releaseClaim} 把格子退回未领，邮件于是<b>留在列表里</b>并带着原因回给玩家
 * （验收 5）。反过来先发货再占格的话，两个并发请求会都看到「没领过」而各发一遍 ——
 * 那与 {@code QuestAppService} 记下的「先落状态再发奖」是同一条取舍，
 * 只是这里多了一步退回，因为邮件的失败必须看得见而任务的失败不该。
 *
 * <p><b>溢出会再生成一封邮件</b>：附件发放时若背包/仓库满了，走的是 B04 的溢出转邮件
 * （{@code RewardContext.toMail}）。它<b>不会在本次请求里循环</b> ——
 * 本方法遍历的是进入时的那份快照，新邮件不在其中。跨请求的表现是「旧的一封变成新的一封」，
 * 而那是设计：玩家的奖励不会因为仓库满而消失，只会一直在邮箱里。
 */
@Service
public class MailAppService {

    private static final Logger LOG = LoggerFactory.getLogger(MailAppService.class);
    private static final long LOCK_TIMEOUT_MS = 5_000L;
    /** 一封邮件能带的附件条数上限。补发不是无限的，而一条无上限的附件列表会变成最大的那个响应。 */
    private static final int MAX_ATTACHMENTS = 32;
    private static final int MAX_TEXT_CHARS = 2_000;

    private final MailStore store;
    private final TimeService timeService;
    private final ConfigRegistry configs;
    private final RewardService rewardService;
    private final IdempotencyStore idempotency;
    private final PlayerLock playerLock;
    private final RewardNames names;

    public MailAppService(MailStore store, TimeService timeService, ConfigRegistry configs,
                          RewardService rewardService, IdempotencyStore idempotency,
                          PlayerLock playerLock, RewardNames names) {
        this.store = store;
        this.timeService = timeService;
        this.configs = configs;
        this.rewardService = rewardService;
        this.idempotency = idempotency;
        this.playerLock = playerLock;
        this.names = names;
    }

    // ---------- 读 ----------

    /**
     * 邮件列表。<b>读的时候顺手清过期</b>（B12 禁止项：不跑定时器），
     * 与战报 {@code purgeExpired}、埋点保留期同一形状。
     */
    public MailListResp list(String playerId) {
        long now = timeService.serverNow();
        int purged = store.purgeExpired(now);
        if (purged > 0) {
            LOG.info("邮件按保留期清理 {} 封 playerId={} 保留天数={}（来源 global.MAIL_RETENTION_DAYS）",
                    purged, playerId, retentionDays());
        }
        List<MailRecord> records = store.listOf(playerId, now);
        List<MailView> views = new ArrayList<>(records.size());
        int unread = 0;
        int settled = 0;
        for (MailRecord record : records) {
            boolean claimed = record.claimedAt() != null || !record.hasAttachment();
            if (record.readAt() == null) {
                unread++;
            }
            if (claimed) {
                settled++;
            }
            views.add(new MailView(record.mailId(), MailKind.valueOf(record.kind()), record.title(),
                    record.text(), rewardViews(record.rewards()), claimed, record.readAt() != null,
                    record.createdAt(), record.expireAt(), record.sourceRef()));
        }
        return new MailListResp(List.copyOf(views), unread, settled);
    }

    /** 红点判据：有没有<b>没读过</b>的邮件（含没附件的公告 —— 未读就是未读，别把两件事合并）。 */
    public boolean hasUnread(String playerId) {
        long now = timeService.serverNow();
        for (MailRecord record : store.listOf(playerId, now)) {
            if (record.readAt() == null) {
                return true;
            }
        }
        return false;
    }

    // ---------- 写 ----------

    /** 一键领取全部可领附件。只发一次请求（验收 5），失败的邮件保留并带原因。 */
    public MailClaimAllResp claimAll(String playerId, MailClaimAllReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                List<MailRecord> snapshot = store.listOf(playerId, timeService.serverNow());
                int claimed = 0;
                Map<String, Long> grantedByLine = new LinkedHashMap<>();
                Map<String, String> nameOfLine = new LinkedHashMap<>();
                List<MailClaimFailure> failed = new ArrayList<>();
                for (MailRecord mail : snapshot) {
                    if (!mail.claimable(timeService.serverNow())) {
                        continue;
                    }
                    if (!store.claim(playerId, mail.mailId(), timeService.serverNow())) {
                        // 另一次点击已经把它领走了：这不算失败，也不该出现在 failed 里
                        LOG.info("邮件已被另一次领取占走 playerId={} mailId={}", playerId, mail.mailId());
                        continue;
                    }
                    try {
                        for (RewardLine line : mail.rewards()) {
                            RewardItem item = new RewardItem(RewardType.valueOf(line.type()), line.id(),
                                    line.count());
                            var result = rewardService.grant(playerId, List.of(item),
                                    RewardContext.toMail("mail", mail.mailId(),
                                            mail.mailId() + ":" + req.requestId()));
                            if (result.hasCompensation()) {
                                throw new IllegalStateException("发放进入补偿队列 compensationId="
                                        + result.compensationId());
                            }
                            for (RewardItem got : result.granted()) {
                                String key = got.type().name() + ':' + got.id();
                                grantedByLine.merge(key, got.count(), Long::sum);
                                // 只在第一次见到这条时记名字：LinkedHashMap 的插入序就是明细的展示序
                                nameOfLine.putIfAbsent(key, names.nameOf(got));
                            }
                        }
                        claimed++;
                    } catch (RuntimeException e) {
                        store.releaseClaim(playerId, mail.mailId());
                        String reason = reasonOf(e);
                        failed.add(new MailClaimFailure(mail.mailId(), reason));
                        LOG.warn("邮件附件未能发放，已退回未领 playerId={} mailId={} 附件={} 原因={}",
                                playerId, mail.mailId(), mail.rewards(), reason, e);
                    }
                }
                List<MailReward> rewards = new ArrayList<>(nameOfLine.size());
                for (Map.Entry<String, String> entry : nameOfLine.entrySet()) {
                    int split = entry.getKey().indexOf(':');
                    rewards.add(new MailReward(entry.getKey().substring(0, split),
                            entry.getKey().substring(split + 1),
                            grantedByLine.getOrDefault(entry.getKey(), 0L), entry.getValue()));
                }
                LOG.info("一键领取邮件 playerId={} 领到 {} 封、失败 {} 封", playerId, claimed, failed.size());
                return new MailClaimAllResp(claimed, List.copyOf(rewards), List.copyOf(failed));
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /** 标一封为已读，顺带回新的未读封数（省一次重拉）。 */
    public MailReadResp markRead(String playerId, MailReadReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            String mailId = req.mailId();
            if (mailId == null || mailId.isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "mailId 不得为空");
            }
            if (!store.markRead(playerId, mailId, now)) {
                throw new BizException(ErrorCode.MAIL_NOT_FOUND, "mailId=" + mailId);
            }
            int unread = 0;
            for (MailRecord record : store.listOf(playerId, now)) {
                if (record.readAt() == null) {
                    unread++;
                }
            }
            return new MailReadResp(mailId, unread);
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 运营/客服补发一封邮件。**唯一一条能凭空给玩家发奖励的通路**，所以它同时受三道闸门：
     * 运维令牌（控制器侧）、幂等键（这里）、审计日志（WARN 级，带操作者）。
     */
    public OpsMailSendResp sendByOps(OpsMailSendReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            if (req.playerId() == null || req.playerId().isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "playerId 不得为空");
            }
            if (req.rewards() != null && req.rewards().size() > MAX_ATTACHMENTS) {
                throw new BizException(ErrorCode.PARAM_INVALID, "一封邮件最多带 " + MAX_ATTACHMENTS + " 条附件");
            }
            List<RewardLine> lines = new ArrayList<>();
            for (MailReward reward : req.rewards() == null ? List.<MailReward>of() : req.rewards()) {
                // 类型与 id 现在就校验：错了不该等到玩家点领取那一刻才发现（那时已经发不出去）
                RewardType type = typeOf(reward.type());
                if (reward.id() == null || reward.id().isBlank()) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "附件 id 不得为空，type=" + type);
                }
                if (reward.count() <= 0) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "附件数量必须为正，实际=" + reward.count());
                }
                lines.add(new RewardLine(type.name(), reward.id(), reward.count(), names.nameOf(
                        new RewardItem(type, reward.id(), reward.count()))));
            }
            MailRecord mail = new MailRecord(newId(), req.playerId(), MailKind.SYSTEM.name(),
                    requireText(req.title(), "title", 120), requireText(req.text(), "text", MAX_TEXT_CHARS),
                    lines, "ops:" + requireText(req.actor(), "actor", 64), now,
                    now + retentionDays() * 86_400_000L, null, null);
            store.save(mail);
            LOG.warn("运营补发邮件 playerId={} mailId={} 附件={} 操作者={}（幂等键 {}）",
                    req.playerId(), mail.mailId(), lines.size(), req.actor(), req.requestId());
            return new OpsMailSendResp(mail.mailId(), mail.expireAt(), lines.size());
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    // ---------- 内部 ----------

    private static String newId() {
        return "mail_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private int retentionDays() {
        return (int) configs.longParam("MAIL_RETENTION_DAYS");
    }

    private static RewardType typeOf(String value) {
        try {
            return RewardType.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "附件类型 " + value + " 不是已知的奖励类型（可选："
                    + java.util.Arrays.toString(RewardType.values()) + "）");
        }
    }

    private static String requireText(String value, String field, int maxChars) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, field + " 不得为空");
        }
        if (value.length() > maxChars) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    field + " 最长 " + maxChars + " 字符，实际=" + value.length());
        }
        return value;
    }

    private static String reasonOf(RuntimeException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : "：" + message);
    }

    private void acquire(String requestId, long now) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "写邮件必须带 requestId");
        }
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + requestId);
        }
    }

    private static List<MailReward> rewardViews(List<RewardLine> lines) {
        List<MailReward> out = new ArrayList<>(lines.size());
        for (RewardLine line : lines) {
            out.add(new MailReward(line.type(), line.id(), line.count(), line.name()));
        }
        return List.copyOf(out);
    }
}
