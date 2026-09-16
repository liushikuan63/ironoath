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
import com.ironoath.web.activity.ActivityProgressStore.State;
import com.ironoath.web.dto.generated.ActivityClaimReq;
import com.ironoath.web.dto.generated.ActivityClaimResp;
import com.ironoath.web.dto.generated.ActivityListResp;
import com.ironoath.web.dto.generated.ActivityReward;
import com.ironoath.web.dto.generated.ActivityState;
import com.ironoath.web.dto.generated.ActivityView;
import com.ironoath.web.reward.RewardNames;

/**
 * 职责：活动面板与领取（B17 §一、§二的实现侧）。
 * 依赖：{@link ActivityProgressStore}（两份实现语义一致）、{@link ActivityRulesAssembler}（表）、
 *       {@link ActivityAnchors}（两块锚）、发放器（奖励不得绕过它）、幂等、玩家锁、
 *       {@link RewardNames}（奖励显示名与背包/邮件同源）。
 *
 * <p><b>读取路径先 {@code syncOnRead}，事件路径先 {@code rollover}</b>：两者的差别只有一条 ——
 * 读取时留住"上一轮有进展但没领"的行（它会被读成 EXPIRED），事件到达时把它推进到新一轮。
 * 为什么必须这样切见 {@code ActivityProgress.syncOnRead} 的注释（那是 B17 文档内部张力的一处解法）。
 *
 * <p><b>领取顺序「先落状态再发奖」</b>：与 {@code QuestAppService} 完全一致 —— 失败方向选
 * 「标记了已领但没发出去」（有补偿队列与客服入口可追），反过来（发了没标记）玩家会重复领。
 *
 * <p><b>红点与列表同一条判定</b>：{@code claimableCount} 调的是核心的 {@code blockOf}，
 * 这里不另算一遍"什么算可领"。
 */
@Service
public class ActivityAppService {

    private static final Logger LOG = LoggerFactory.getLogger(ActivityAppService.class);
    private static final long LOCK_TIMEOUT_MS = 5_000L;

    private final ActivityProgressStore store;
    private final ActivityRulesAssembler assembler;
    private final ActivityAnchors anchors;
    private final RewardService rewardService;
    private final RewardNames names;
    private final IdempotencyStore idempotency;
    private final PlayerLock playerLock;
    private final TimeService timeService;
    private final ConfigRegistry configs;

    public ActivityAppService(ActivityProgressStore store, ActivityRulesAssembler assembler,
                              ActivityAnchors anchors, RewardService rewardService, RewardNames names,
                              IdempotencyStore idempotency, PlayerLock playerLock,
                              TimeService timeService, ConfigRegistry configs) {
        this.store = store;
        this.assembler = assembler;
        this.anchors = anchors;
        this.rewardService = rewardService;
        this.names = names;
        this.idempotency = idempotency;
        this.playerLock = playerLock;
        this.timeService = timeService;
        this.configs = configs;
    }

    /** 活动列表。顺序 = 表序；含 EXPIRED 的行（轮到下一轮之前它看得见、领不了）。 */
    public ActivityListResp list(String playerId) {
        long now = timeService.serverNow();
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
            ActivityProgress progress = loaded(playerId);
            // 读取路径的惰性同步：没有待展示记录的行直接进入新一轮（有进展没领的行留住）
            if (progress.syncOnRead(now, anchors.playerAnchorMs(playerId)) > 0) {
                save(playerId, progress);
            }
            long playerAnchor = anchors.playerAnchorMs(playerId);
            List<ActivityView> views = new ArrayList<>();
            for (ActivityProgress.Def def : progress.defs()) {
                views.add(viewOf(progress, def, now, playerAnchor));
            }
            return new ActivityListResp(List.copyOf(views), now,
                    progress.claimableCount(now, playerAnchor));
        });
    }

    /** 有可领奖的活动数 —— 红点叶 {@code activity/claimable} 的输入（与列表同一判定）。 */
    public int claimableCount(String playerId) {
        long now = timeService.serverNow();
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS,
                () -> loaded(playerId).claimableCount(now, anchors.playerAnchorMs(playerId)));
    }

    /** 领取一次活动奖励。requestId 幂等；同窗口重复领取被拒且不发货。 */
    public ActivityClaimResp claim(String playerId, ActivityClaimReq req) {
        if (req == null || req.activityId() == null || req.activityId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "activityId 不得为空");
        }
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
            ActivityProgress progress = loaded(playerId);
            long playerAnchor = anchors.playerAnchorMs(playerId);
            ActivityProgress.ClaimBlock block;
            try {
                block = progress.blockOf(req.activityId(), now, playerAnchor);
            } catch (IllegalArgumentException e) {
                // 活动表的行不在了（改表或客户端缓存过期）：与「现在领不了」分开报
                throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND, e.getMessage());
            }
            if (block.blocked()) {
                throw new BizException(ErrorCode.ACTIVITY_NOT_CLAIMABLE,
                        detailOf(req.activityId(), block, progress, now, playerAnchor));
            }
            progress.claim(req.activityId(), now, playerAnchor);
            // 先落状态再发奖：失败方向见类注释
            save(playerId, progress);
            List<RewardItem> rewards = assembler.rewardsByActivity()
                    .getOrDefault(req.activityId(), List.of());
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
            LOG.info("活动奖励已领取 playerId={} activityId={} 奖励={}", playerId, req.activityId(), granted);
            return new ActivityClaimResp(true, granted,
                    stateOf(progress.phaseOf(req.activityId(), now, playerAnchor)));
        });
    }

    /** 名单上那一行此刻的视图（读与领共用，字段口径只有一处）。 */
    private ActivityView viewOf(ActivityProgress progress, ActivityProgress.Def def, long now,
                                long playerAnchor) {
        ActivityProgress.Entry entry = progress.entry(def.activityId());
        ActivityWindow.Span span = progress.windowOf(def.activityId(), now, playerAnchor);
        return new ActivityView(def.activityId(), assembler.namesById().get(def.activityId()),
                stateOf(progress.phaseOf(def.activityId(), now, playerAnchor)),
                entry.value(), def.goalValue(), span.endAt(), entry.claimed());
    }

    /** 领取被拒的原因写进 detail：三种原因共用一个错误码（B17 §二），detail 是玩家唯一能看到的差别。 */
    private static String detailOf(String activityId, ActivityProgress.ClaimBlock block,
                                   ActivityProgress progress, long now, long playerAnchor) {
        if (block == ActivityProgress.ClaimBlock.NOT_REACHED) {
            ActivityProgress.Entry entry = progress.entry(activityId);
            return "活动 " + activityId + " 还没达标：" + entry.value() + "/" + progressGoal(progress, activityId);
        }
        return switch (block) {
            case EXPIRED -> "活动 " + activityId + " 的上一轮已经结束，本轮还没开始：记录留在列表里，但奖领不了";
            case ALREADY_CLAIMED -> "活动 " + activityId + " 这一轮的奖励已经领过了";
            case NOT_REACHED, NONE -> "活动 " + activityId + " 当前可领（这条不该出现在拒绝路径里）";
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

    /** 载入或新建账本。**新建不放库**：从没打开过活动页的玩家不该因为一次读取就留下存档。 */
    private ActivityProgress loaded(String playerId) {
        State stored = store.load(playerId).orElse(null);
        return stored == null
                ? ActivityProgress.open(playerId, assembler.activities(), anchors.serverOpenMs(playerId))
                : ActivityProgress.restore(playerId, assembler.activities(), stored.entries(),
                        stored.serverOpenMs());
    }

    private void save(String playerId, ActivityProgress progress) {
        store.save(playerId, new State(playerId, progress.entries(), progress.serverOpenMs()));
    }

    /** 幂等键：与 {@code QuestAppService} 同一条（缺键回 1003，重复回 1002，TTL 读配置表）。 */
    private void acquire(String requestId, long now) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "领取活动奖励必须带 requestId");
        }
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED,
                    "requestId=" + requestId + " 已经用过了：同一次领取重投只会拿到同一份结果");
        }
    }
}
