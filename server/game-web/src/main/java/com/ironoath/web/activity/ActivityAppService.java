package com.ironoath.web.activity;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.activity.ActivityProgress;
import com.ironoath.core.activity.ActivityWindow;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardService;
import com.ironoath.web.dto.generated.ActivityClaimReq;
import com.ironoath.web.dto.generated.ActivityClaimResp;
import com.ironoath.web.dto.generated.ActivityListResp;
import com.ironoath.web.dto.generated.ActivityReward;
import com.ironoath.web.dto.generated.ActivityState;
import com.ironoath.web.dto.generated.ActivityView;
import com.ironoath.web.reward.RewardNames;

/**
 * 职责：活动面板与领取（B17 §一、§二的实现侧）。
 * 依赖：{@link ActivityLedger}（规则、窗口及奖励快照）、{@link ActivityProgressStore}（两份实现语义一致）、
 *       {@link ActivityAnchors}（两块锚）、发放器（奖励不得绕过它）、幂等、玩家锁、
 *       {@link RewardNames}（奖励显示名与背包/邮件同源）。
 *
 * <p><b>读取与事件由 {@code ActivityLedger} 选择实例</b>：两者的差别只有一条 ——
 * 读取时留住"上一轮有进展但没领"的行（它会被读成 EXPIRED），事件到达时把它推进到新一轮。
 * 为什么必须这样切见 {@code ActivityProgress.syncOnRead} 的注释（那是 B17 文档内部张力的一处解法）。
 *
 * <p><b>旧混合活动领取保持「先落状态再发奖」</b>：失败方向选
 * 「标记了已领但没发出去」（有补偿队列与客服入口可追），反过来（发了没标记）玩家会重复领。
 *
 * <p><b>红点与列表同一条判定</b>：{@code claimableCount} 调的是核心的 {@code blockOf}，
 * 这里不另算一遍"什么算可领"。
 */
@Service
public class ActivityAppService {

    private static final Logger LOG = LoggerFactory.getLogger(ActivityAppService.class);
    private static final long LOCK_TIMEOUT_MS = 5_000L;

    private final ActivityAnchors anchors;
    private final RewardService rewardService;
    private final RewardNames names;
    private final IdempotencyStore idempotency;
    private final PlayerLock playerLock;
    private final TimeService timeService;
    private final ConfigRegistry configs;
    /** 战令积分（B24 S-d-c）。与任务那条同源：分值挂在既有活动上，不新增任务体系。 */
    private final com.ironoath.web.battlepass.BattlePassService battlePass;
    private final SignInRewards signIn;
    private final ActivityLedger ledger;

    public ActivityAppService(ActivityAnchors anchors, RewardService rewardService, RewardNames names,
                              IdempotencyStore idempotency, PlayerLock playerLock,
                              TimeService timeService, ConfigRegistry configs,
                              com.ironoath.web.battlepass.BattlePassService battlePass, SignInRewards signIn,
                              ActivityLedger ledger) {
        this.anchors = anchors;
        this.rewardService = rewardService;
        this.names = names;
        this.idempotency = idempotency;
        this.playerLock = playerLock;
        this.timeService = timeService;
        this.configs = configs;
        this.battlePass = battlePass;
        this.signIn = signIn;
        this.ledger = ledger;
    }

    /** 活动列表。顺序 = 表序；含 EXPIRED 的行（轮到下一轮之前它看得见、领不了）。 */
    public ActivityListResp list(String playerId) {
        long now = timeService.serverNow();
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
            rewardService.recoverGoldClaims(playerId);
            ActivityLedger.Book book = ledger.load(playerId, now, ActivityLedger.Mode.READ);
            ActivityProgress progress = book.progress();
            // 读取路径的惰性同步：没有待展示记录的行直接进入新一轮（有进展没领的行留住）
            if (progress.syncOnRead(now, anchors.playerAnchorMs(playerId)) > 0) {
                ledger.save(book);
            }
            long playerAnchor = anchors.playerAnchorMs(playerId);
            List<ActivityView> views = new ArrayList<>();
            for (ActivityProgress.Def def : progress.defs()) {
                if (def.retired() && progress.blockOf(def.activityId(), now, playerAnchor).blocked()) continue;
                views.add(viewOf(book, def, now, playerAnchor));
            }
            views.addAll(signIn.list(playerId, now));
            return new ActivityListResp(List.copyOf(views), now,
                    progress.claimableCount(now, playerAnchor) + signInCount(views));
        });
    }

    /** 有可领奖的活动数 —— 红点叶 {@code activity/claimable} 的输入（与列表同一判定）。 */
    public int claimableCount(String playerId) {
        long now = timeService.serverNow();
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS,
                () -> ledger.load(playerId, now, ActivityLedger.Mode.READ).progress().claimableCount(now, anchors.playerAnchorMs(playerId)) + signInCount(signIn.list(playerId, now)));
    }

    /** 领取一次活动奖励。requestId 幂等；同窗口重复领取被拒且不发货。 */
    public ActivityClaimResp claim(String playerId, ActivityClaimReq req) {
        if (req == null || req.activityId() == null || req.activityId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "activityId 不得为空");
        }
        long now = timeService.serverNow();
        acquire(playerId, req.requestId(), now);
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
            if (signIn.contains(req.activityId())) return signIn.claim(playerId, req.activityId(), timeService.serverNow());
            ActivityLedger.Book book = ledger.load(playerId, now, ActivityLedger.Mode.CLAIM);
            ActivityProgress progress = book.progress();
            long playerAnchor = anchors.playerAnchorMs(playerId);
            ActivityProgress.ClaimBlock block;
            try {
                block = progress.blockOf(req.activityId(), now, playerAnchor);
            } catch (IllegalArgumentException e) {
                // 活动表的行不在了（改表或客户端缓存过期）：与「现在领不了」分开报
                throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND, "这个活动已结束或已更新，请刷新活动列表后再试。");
            }
            if (block.blocked()) {
                throw new BizException(ErrorCode.ACTIVITY_NOT_CLAIMABLE,
                        detailOf(req.activityId(), block, book));
            }
            progress.claim(req.activityId(), now, playerAnchor);
            // 先落状态再发奖：失败方向见类注释
            ledger.save(book);
            List<RewardItem> rewards = book.instance(req.activityId()).rewards();
            List<ActivityReward> granted = List.of();
            if (!rewards.isEmpty()) {
                var result = rewardService.grant(playerId, rewards,
                        RewardContext.toMail("activity", req.activityId(),
                                req.activityId() + ":" + req.requestId()));
                // 写的是**入账量**，不是申请量（与 QuestClaimResp.rewards / MailClaimAllResp.rewards 同一条）：
                // 背包装不下的那些走邮件兜底，不在这里假装发过
                granted = views(result.granted());
                if (result.hasCompensation()) {
                    LOG.error("【活动奖励未入账已进补偿队列】playerId={} activityId={} 奖励={} compensationId={}",
                            playerId, req.activityId(), rewards, result.compensationId());
                }
            }
            // 战令积分：只在领取成功这一下加（重放请求在 acquire(requestId) 那一步就被挡掉了）
            long points = book.instance(req.activityId()).battlePassPoints();
            if (points > 0L) {
                battlePass.addPoints(playerId, points, "activity:" + req.activityId());
            }
            LOG.info("活动奖励已领取 playerId={} activityId={} 奖励={} 战令积分+{}", playerId, req.activityId(), granted, points);
            return new ActivityClaimResp(true, granted,
                    stateOf(progress.phaseOf(req.activityId(), now, playerAnchor)), null);
        });
    }

    /** 名单上那一行此刻的视图（读与领共用，字段口径只有一处）。 */
    private ActivityView viewOf(ActivityLedger.Book book, ActivityProgress.Def def, long now,
                                long playerAnchor) {
        ActivityProgress progress = book.progress();
        ActivityProgress.Entry entry = progress.entry(def.activityId());
        ActivityWindow.Span span = progress.windowOf(def.activityId(), now, playerAnchor);
        return new ActivityView(def.activityId(), book.instance(def.activityId()).name(),
                stateOf(progress.phaseOf(def.activityId(), now, playerAnchor)),
                entry.value(), def.goalValue(), span.endAt(), entry.claimed(),
                com.ironoath.web.dto.generated.ActivityCategory.EVENT,
                views(book.instance(def.activityId()).rewards()),
                def.retired() ? "旧登录活动已停止累计，本轮已达标奖励仍可在原截止前领取。" : null, false, false);
    }

    private static int signInCount(List<ActivityView> views) {
        return (int) views.stream().filter(v -> v.category() == com.ironoath.web.dto.generated.ActivityCategory.SIGN_IN
                && v.state() == ActivityState.CLAIMABLE && !v.claimed() && !Boolean.TRUE.equals(v.automatic())).count();
    }

    /** 领取被拒的原因写进 detail：三种原因共用一个错误码（B17 §二），detail 是玩家唯一能看到的差别。 */
    private String detailOf(String activityId, ActivityProgress.ClaimBlock block, ActivityLedger.Book book) {
        ActivityProgress progress = book.progress();
        String name = book.instance(activityId).name();
        String label = name == null || name.isBlank() ? "该活动" : "“" + name + "”";
        if (block == ActivityProgress.ClaimBlock.NOT_REACHED) {
            ActivityProgress.Entry entry = progress.entry(activityId);
            return label + "还没达标：" + entry.value() + "/" + progressGoal(progress, activityId);
        }
        return switch (block) {
            case EXPIRED -> label + "的上一轮已经结束，本轮还没开始：记录留在列表里，但奖领不了";
            case ALREADY_CLAIMED -> label + "这一轮的奖励已经领过了";
            case NOT_REACHED, NONE -> "请刷新活动状态后再尝试领取。";
        };
    }

    private static long progressGoal(ActivityProgress progress, String activityId) {
        for (ActivityProgress.Def def : progress.defs()) {
            if (def.activityId().equals(activityId)) {
                return def.goalValue();
            }
        }
        return 0L;
    }

    private static ActivityState stateOf(ActivityProgress.Phase phase) {
        return switch (phase) {
            case RUNNING -> ActivityState.RUNNING;
            case CLAIMABLE -> ActivityState.CLAIMABLE;
            case EXPIRED -> ActivityState.EXPIRED;
        };
    }

    private List<ActivityReward> views(List<RewardItem> items) {
        List<ActivityReward> out = new ArrayList<>(items.size());
        for (RewardItem item : items) {
            out.add(new ActivityReward(item.type().name(), item.id(), item.count(), names.nameOf(item)));
        }
        return List.copyOf(out);
    }

    /** 幂等键：与 {@code QuestAppService} 同一条（缺键回 1003，重复回 1002，TTL 读配置表）。 */
    private void acquire(String playerId, String requestId, long now) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "领取活动奖励必须带 requestId");
        }
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire("activity:" + playerId + ':' + requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED,
                    "这次领取请求已处理，请刷新列表查看奖励到账情况。");
        }
    }
}
