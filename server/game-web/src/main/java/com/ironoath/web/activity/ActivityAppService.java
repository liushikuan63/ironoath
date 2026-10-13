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
 * <p>常规活动先同次保存资格与冻结待发计划，再按金币、材料与原赛季积分稳定回执接受。
 * 读取和重试恢复计划；没有计划的历史已领标记不推测补发。
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
    private final com.ironoath.core.player.PlayerRepository players;

    public ActivityAppService(ActivityAnchors anchors, RewardService rewardService, RewardNames names,
                              IdempotencyStore idempotency, PlayerLock playerLock,
                              TimeService timeService, ConfigRegistry configs,
                              com.ironoath.web.battlepass.BattlePassService battlePass, SignInRewards signIn,
                              ActivityLedger ledger, com.ironoath.core.player.PlayerRepository players) {
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
        this.players = players;
    }

    /** 活动列表。顺序 = 表序；含 EXPIRED 的行（轮到下一轮之前它看得见、领不了）。 */
    public ActivityListResp list(String playerId) {
        long now = timeService.serverNow();
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
            ActivityLedger.Book book = load(playerId, now, ActivityLedger.Mode.READ);
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
                () -> load(playerId, now, ActivityLedger.Mode.READ).progress().claimableCount(now, anchors.playerAnchorMs(playerId)) + signInCount(signIn.list(playerId, now)));
    }

    /** 领取一次活动奖励。requestId 幂等；同窗口重复领取被拒且不发货。 */
    public ActivityClaimResp claim(String playerId, ActivityClaimReq req) {
        if (req == null || req.activityId() == null || req.activityId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "activityId 不得为空");
        }
        long now = timeService.serverNow();
        acquire(playerId, req.requestId(), now);
        try { return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
            if (signIn.contains(req.activityId())) return signIn.claim(playerId, req.activityId(), timeService.serverNow());
            ActivityLedger.Book book = load(playerId, now, ActivityLedger.Mode.CLAIM);
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
                        detailOf(req.activityId(), block, book,now));
            }
            ActivityInstance instance = book.instance(req.activityId());
            if (campaign(instance)) {
                var facts = new java.util.LinkedHashSet<>(progress.entry(req.activityId()).facts());
                facts.add("activity-window:" + instance.startAt());
                facts.add("activity-goal:" + instance.def().goalValue());
                var result = rewardService.grant(playerId, instance.rewards(), new RewardContext(
                        "free_gold", req.activityId(), campaignKey(instance), true, facts));
                progress.claim(req.activityId(), now, playerAnchor);
                ledger.save(book);
                LOG.info("主题活动金币已接受 playerId={} activityId={} 窗口={} 业务收据={}",
                        playerId,req.activityId(),instance.startAt(),campaignKey(instance));
                return new ActivityClaimResp(true, views(result.granted()),
                        stateOf(progress.phaseOf(req.activityId(), now, playerAnchor)), null);
            }
            for (var reward : instance.rewards()) {
                if(reward.type()==com.ironoath.core.reward.RewardType.ITEM
                        && configs.get(com.ironoath.config.cfg.ItemCfg.class,reward.id()).type()==com.ironoath.config.cfg.ItemCfg.Type.EQUIP)
                    throw new IllegalArgumentException("活动稳定发奖只接受普通堆叠道具");
            }
            var plan = new ActivityClaimPlan(ActivityClaimPlan.keyOf(instance),instance,
                    battlePass.activitySeasonId(),now,battlePass.activityTierSnapshot());
            progress.claim(req.activityId(), now, playerAnchor);
            var pending = new ArrayList<>(book.pendingClaims());pending.add(plan);
            var accepted = new ActivityLedger.Book(progress,book.instances(),List.copyOf(pending));
            // This single state write accepts the qualification and its frozen delivery plan together.
            ledger.save(accepted);
            List<ActivityReward> granted = deliver(plan,playerId);
            ledger.save(new ActivityLedger.Book(progress,book.instances(),book.pendingClaims()));
            LOG.info("活动计划发放完成 playerId={} key={} 奖励={}",playerId,plan.businessKey(),granted);
            return new ActivityClaimResp(true,granted,stateOf(progress.phaseOf(req.activityId(),now,playerAnchor)),null);
        });
        } catch (RuntimeException error) {
            idempotency.release("activity:"+playerId+':'+req.requestId());
            throw error;
        }
    }

    private List<ActivityReward> deliver(ActivityClaimPlan plan,String playerId) {
        List<ActivityReward> granted=new ArrayList<>();
        String activityId=plan.instance().def().activityId();
        var facts=java.util.Set.of("activity-window:"+plan.instance().startAt());
        var gold=plan.instance().rewards().stream().filter(r -> r.type()==com.ironoath.core.reward.RewardType.RESOURCE).toList();
        var items=plan.instance().rewards().stream().filter(r -> r.type()==com.ironoath.core.reward.RewardType.ITEM).toList();
        if (!gold.isEmpty()) granted.addAll(views(rewardService.grant(playerId,gold,
                new RewardContext("free_gold",activityId,plan.businessKey()+":gold",true,facts)).granted()));
        if (!items.isEmpty()) granted.addAll(views(rewardService.grant(playerId,items,
                new RewardContext("free_item",activityId,plan.businessKey()+":items",true,facts)).granted()));
        if (plan.instance().battlePassPoints()>0) battlePass.addActivityPointsOnce(playerId,plan.seasonId(),
                plan.businessKey(),plan.instance().battlePassPoints());
        if (plan.instance().battlePassPoints()>0) battlePass.recoverActivityTierRewards(playerId,plan.seasonId(),plan.battlePassTiers());
        return List.copyOf(granted);
    }

    private static boolean campaign(ActivityInstance instance) {
        return instance.def().requiresFact() && instance.battlePassPoints()==0 && instance.rewards().size()==1
                && instance.rewards().get(0).type()==com.ironoath.core.reward.RewardType.RESOURCE
                && "GOLD".equals(instance.rewards().get(0).id());
    }
    private static String campaignKey(ActivityInstance instance) {
        return "activity_gold:"+instance.def().activityId()+":"+instance.startAt();
    }
    /** 任一读取、红点或重试恢复钱包已接受的资格，不依赖HTTP requestId。 */
    private ActivityLedger.Book load(String playerId,long now,ActivityLedger.Mode mode) {
        rewardService.recoverGoldClaims(playerId);
        rewardService.recoverItemClaims(playerId);
        ActivityLedger.Book book=ledger.load(playerId,now,mode);
        if (!book.pendingClaims().isEmpty()) {
            for (var plan : book.pendingClaims()) deliver(plan,playerId);
            var completed = new ActivityLedger.Book(book.progress(),book.instances(),List.of());
            ledger.save(completed);
            // After old delivery is accepted, choose the current window using normal ledger rules.
            book=ledger.load(playerId,now,mode);
        }
        var owner=players.findByPlayerId(playerId).orElse(null);
        if (owner==null) return book;
        boolean changed=false;
        for (var instance:book.instances()) {
            if (!campaign(instance)) continue;
            var receipt=owner.goldClaims().get(campaignKey(instance));
            if (receipt==null || !"free_gold".equals(receipt.source())
                    || !instance.def().activityId().equals(receipt.sourceRef())) continue;
            long goal=receipt.facts().stream().filter(f -> f.startsWith("activity-goal:"))
                    .mapToLong(f -> Long.parseLong(f.substring("activity-goal:".length()))).findFirst().orElseThrow();
            if (!receipt.facts().contains("activity-window:"+instance.startAt()))
                throw new IllegalStateException("活动金币收据窗口不一致，拒绝猜测资格");
            changed|=book.progress().restoreClaimedFromGoldReceipt(instance.def().activityId(),instance.startAt(),goal,receipt.facts());
        }
        if (changed) ledger.save(book);
        return book;
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
                now<span.startAt() ? "尚未开始："+windowTime(span.startAt())+"，截止："+windowTime(span.endAt())+"（北京时间）。"
                        : def.retired() ? "旧登录活动已停止累计，本轮已达标奖励仍可在原截止前领取。" : null, false, false,span.startAt());
    }

    private static String windowTime(long at) {
        return java.time.Instant.ofEpochMilli(at).atZone(com.ironoath.common.time.DayKey.CALENDAR_ZONE)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private static int signInCount(List<ActivityView> views) {
        return (int) views.stream().filter(v -> v.category() == com.ironoath.web.dto.generated.ActivityCategory.SIGN_IN
                && v.state() == ActivityState.CLAIMABLE && !v.claimed() && !Boolean.TRUE.equals(v.automatic())).count();
    }

    /** 领取被拒的原因写进 detail：三种原因共用一个错误码（B17 §二），detail 是玩家唯一能看到的差别。 */
    private String detailOf(String activityId, ActivityProgress.ClaimBlock block, ActivityLedger.Book book,long now) {
        ActivityProgress progress = book.progress();
        String name = book.instance(activityId).name();
        String label = name == null || name.isBlank() ? "该活动" : "“" + name + "”";
        if (block==ActivityProgress.ClaimBlock.EXPIRED && now<book.instance(activityId).startAt())
            return label+"尚未开始，请按活动列表的开放时间参与。";
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
