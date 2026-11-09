package com.ironoath.web.battlepass;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.BattlePassCfg;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;
import com.ironoath.web.dto.generated.BattlePassClaimReq;
import com.ironoath.web.dto.generated.BattlePassClaimResp;
import com.ironoath.web.dto.generated.BattlePassRewardType;
import com.ironoath.web.dto.generated.BattlePassRewardView;
import com.ironoath.web.dto.generated.BattlePassStatusResp;
import com.ironoath.web.dto.generated.BattlePassTierView;
import com.ironoath.web.dto.generated.BattlePassTrack;
import com.ironoath.web.reward.RewardNames;
import com.ironoath.web.reward.StoreMailbox;
import com.ironoath.web.service.ServerCalendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 职责：赛季战令的推进与领取（B24 块②）。
 * 依赖：{@link BattlePassStore}（进度）、{@link BattlePassRules}（表）、{@link RewardService}（发奖）。
 *
 * <p><b>三条口径</b>：
 * <ol>
 *   <li><b>积分只来自任务与活动的领取</b>（{@link #addPoints} 只被那两处调用），
 *       战令没有自己的任务体系 —— 这是裁决② 的原话；</li>
 *   <li><b>两条线各领各的</b>：付费线未解锁只拦付费那一次，免费线照领（「买了战令」解锁的是第二行，
 *       不是把第一行锁起来）；</li>
 *   <li><b>资格与冻结投递计划同次保存</b>：外部资产或邮件确认前保留 pending，
 *       重建服务按原计划和稳定收据继续，资格标记不能单独成为漏发终态。</li>
 * </ol>
 */
@Service
public class BattlePassService {

    private static final Logger LOG = LoggerFactory.getLogger(BattlePassService.class);

    /** 玩家锁等待上限（与任务领取同一个量级：一次领取只碰一个存档）。 */
    private static final long LOCK_TIMEOUT_MS = 5_000L;
    /** 战令在 pay_product 表里的行 id（那一行的 name 列就是战令的显示名）。 */
    private static final String BATTLE_PASS_PRODUCT_ID = "battle_pass";

    private final BattlePassStore store;
    private final BattlePassRules rules;
    private final RewardService rewardService;
    private final RewardNames rewardNames;
    private final PlayerLock playerLock;
    private final TimeService timeService;
    private final ConfigRegistry configs;
    /** 赛季结束时把没领的档位奖励发进邮箱（B24 验收 2 的后半句：不静默作废）。 */
    private final StoreMailbox mailbox;
    private final BattlePassClaimRecovery delivery;

    public BattlePassService(BattlePassStore store, BattlePassRules rules, RewardService rewardService,
                             RewardNames rewardNames, PlayerLock playerLock, TimeService timeService,
                             ConfigRegistry configs, StoreMailbox mailbox) {
        this.store = store;
        this.rules = rules;
        this.rewardService = rewardService;
        this.rewardNames = rewardNames;
        this.playerLock = playerLock;
        this.timeService = timeService;
        this.configs = configs;
        this.mailbox = mailbox;
        this.delivery = new BattlePassClaimRecovery(store,configs,rewardService,rewardNames,mailbox);
    }

    /** 本赛季战令全貌（20 档 + 三个结论位）。 */
    public BattlePassStatusResp status(String playerId) {
        return playerLock.runLocked(playerId,LOCK_TIMEOUT_MS,()->{
            String seasonId=rules.seasonId();delivery.recoverAll(playerId,seasonId);
            return statusOf(seasonId,store.load(seasonId,playerId),timeService.serverNow());
        });
    }

    /**
     * 普通任务领取加积分；活动使用addActivityPointsOnce的持久窗口回执。
     *
     * <p>调用方已经过一次幂等闸（{@code requestId}），所以这里不需要第二个幂等键 ——
     * 重放请求在那两处就被挡掉了，走不到这里。这也正是"不新开一套任务体系"的好处：
     * 幂等只在一个地方成立。
     */
    public long addPoints(String playerId, long points, String reason) {
        if (points <= 0L) {
            return 0L;
        }
        String seasonId = rules.seasonId();
        BattlePassStore.Progress after = store.update(seasonId, playerId, cur -> cur.withPoints(points));
        LOG.info("战令积分 +{} playerId={} 来源={} 当前={}", points, playerId, reason, after.points());
        return after.points();
    }

    /** 活动计划冻结领取时赛季，恢复不向后续赛季转移积分。 */
    public String activitySeasonId() { return rules.seasonId(); }

    public List<BattlePassCfg> activityTierSnapshot() { return List.copyOf(rules.tiers()); }

    /** 迟恢复的原季积分必须兑现原档位；资格和计划先同次保存，再投递既有稳定邮件。 */
    public void recoverActivityTierRewards(String playerId,String seasonId,List<BattlePassCfg> frozenTiers) {
        recoverLateTierRewards(playerId, seasonId, frozenTiers, "活动");
    }
    public void recoverQuestTierRewards(String playerId,String seasonId,List<BattlePassCfg> frozenTiers) {
        recoverLateTierRewards(playerId, seasonId, frozenTiers, "任务");
    }
    private void recoverLateTierRewards(String playerId,String seasonId,List<BattlePassCfg> frozenTiers,String sourceName) {
        if(seasonId.equals(activitySeasonId()))return;
        if(frozenTiers==null||frozenTiers.isEmpty())throw new IllegalStateException("活动缺少原季战令档位快照，拒绝按当前表猜测补发");
        playerLock.runLocked(playerId,LOCK_TIMEOUT_MS,()->{
            delivery.recoverSeason(playerId,seasonId);delivery.reconcileLegacy(playerId,seasonId,frozenTiers);
            for(var row:frozenTiers)for(var track:BattlePassTrack.values()) {
                var before=store.load(seasonId,playerId);
                if(before.points()<row.requiredPoints()||track==BattlePassTrack.PAID&&!before.paidUnlocked()||before.claimed((int)row.tier(),track))continue;
                var tier=delivery.freeze(row,track,false);long now=timeService.serverNow(),expires=delivery.expireAt(now);
                String key=BattlePassDeliveryPlan.keyOf(seasonId,track,BattlePassDeliveryPlan.Kind.LATE_MAIL,List.of(tier));
                var oldWindow=before.tierMailWindows().get(key);if(oldWindow!=null){now=oldWindow.createdAt();expires=oldWindow.expireAt();}
                var plan=BattlePassClaimRecovery.plan(seasonId,track,BattlePassDeliveryPlan.Kind.LATE_MAIL,List.of(tier),now,expires,sourceName);
                store.update(seasonId,playerId,p->{
                    if(p.claimed(tier.tier(),track)||p.points()<tier.requiredPoints()||track==BattlePassTrack.PAID&&!p.paidUnlocked())return p;
                    return p.withDeliveryPlan(plan);
                });
                delivery.recoverSeason(playerId,seasonId);
            }
            return null;
        });
    }

    public long addActivityPointsOnce(String playerId,String seasonId,String businessKey,long points) {
        if(seasonId==null||seasonId.isBlank()||businessKey==null||!businessKey.startsWith("activity_claim:"))
            throw new IllegalArgumentException("活动积分需要原赛季与活动窗口业务键");
        if(points<=0)return 0;
        var accepted=store.update(seasonId,playerId,p -> p.withPointsOnce(businessKey,points));
        LOG.info("活动战令积分已接受 playerId={} season={} key={} 当前={}",playerId,seasonId,businessKey,accepted.points());
        return accepted.points();
    }

    /** Ordinary task plans pin original season and use the same atomic per-season point receipts. */
    public long addQuestPointsOnce(String playerId, String seasonId, String businessKey, long points) {
        if (seasonId == null || seasonId.isBlank() || businessKey == null || !businessKey.startsWith("quest_claim:"))
            throw new IllegalArgumentException("任务积分需要原赛季与周期业务键");
        if (points <= 0) return 0;
        return store.update(seasonId, playerId, p -> p.withPointsOnce(businessKey, points)).points();
    }

    /** 领一档的某一条线。 */
    public BattlePassClaimResp claim(String playerId,BattlePassClaimReq req) {
        if(req==null||req.track()==null||req.tier()==0L)throw new BizException(ErrorCode.PARAM_INVALID,"tier 与 track 都不得为空");
        var row=rules.requireTier(req.tier());var track=req.track();String seasonId=rules.seasonId();
        return playerLock.runLocked(playerId,LOCK_TIMEOUT_MS,()->{
            delivery.recoverAll(playerId,seasonId);delivery.reconcileLegacy(playerId,seasonId,List.of(row));
            var tier=delivery.freeze(row,track,true);long now=timeService.serverNow();
            var plan=BattlePassClaimRecovery.plan(seasonId,track,BattlePassDeliveryPlan.Kind.DIRECT,List.of(tier),now,delivery.expireAt(now),"");
            var accepted=store.update(seasonId,playerId,p->{
                if(p.pendingPlans().containsKey(plan.key()))return p;
                if(p.points()<row.requiredPoints())throw new BizException(ErrorCode.BATTLE_PASS_TIER_LOCKED,"第 "+req.tier()+" 档需要 "+row.requiredPoints()+" 分，当前 "+p.points()+" 分");
                if(track==BattlePassTrack.PAID&&!p.paidUnlocked())throw new BizException(ErrorCode.BATTLE_PASS_PAID_LOCKED,"第 "+req.tier()+" 档的付费线还没解锁");
                if(p.claimed(tier.tier(),track))throw new BizException(ErrorCode.BATTLE_PASS_ALREADY_CLAIMED,"第 "+req.tier()+" 档的"+(track==BattlePassTrack.PAID?"付费":"免费")+"线已经领过");
                return p.withDeliveryPlan(plan);
            });
            var actual=accepted.pendingPlans().get(plan.key());delivery.recoverSeason(playerId,seasonId);
            var promised=actual.tiers().get(0);
            var view=new com.ironoath.web.dto.generated.BattlePassRewardView(com.ironoath.web.dto.generated.BattlePassRewardType.valueOf(promised.reward().type().name()),promised.reward().id(),promised.name(),promised.reward().count());
            return new BattlePassClaimResp(track,req.tier(),view,statusOf(seasonId,store.load(seasonId,playerId),now));
        });
    }

    /** 本赛季付费线解锁了没有（下单前的第二道检查用，第一道在读状态时）。 */
    public boolean paidUnlocked(String playerId) {
        return store.load(rules.seasonId(), playerId).paidUnlocked();
    }

    /**
     * 赛季结束时把**已达成但未领**的档位奖励按档发进邮箱。返回发出去多少封。
     *
     * <p><b>为什么按战令存储枚举玩家而不是按榜单</b>：榜单只有前 N 名，而"打过战令但没上榜"的人
     * 恰恰最容易留下没领的档位。{@code playerIdsOf} 是这一季参与者的唯一可枚举出口。
     *
     * <p><b>幂等靠把档位标成已领</b>（不是靠结算的 requestId：重跑会换一个键，那样每次重跑都会再发一遍）：
     * 补发之后这些档位就是已领状态 —— 那是事实，奖励确实发出去了。
     *
     * <p><b>发的是邮件而不是直接入账</b>：赛季结束那一刻玩家可能已经很久不上线，直接塞进背包会有
     * "背包满而静默丢失"的风险，而邮件有保留期与领取记录，符合裁决② 的「不静默作废」。
     */
    public int sweepSeasonToMail(String seasonId) {
        if(seasonId==null||seasonId.isBlank())return 0;
        List<BattlePassCfg> tiers=rules.tiers();int sent=0;
        for(String playerId:store.playerIdsOf(seasonId))sent+=playerLock.runLocked(playerId,LOCK_TIMEOUT_MS,()->{
            int delivered=delivery.recoverSeason(playerId,seasonId);delivery.reconcileLegacy(playerId,seasonId,tiers);
            for(var track:BattlePassTrack.values()) {
                var before=store.load(seasonId,playerId);var candidates=new ArrayList<BattlePassDeliveryPlan.Tier>();
                for(var row:tiers)if(before.points()>=row.requiredPoints()&&!before.claimed((int)row.tier(),track)&&(track!=BattlePassTrack.PAID||before.paidUnlocked()))
                    candidates.add(delivery.freeze(row,track,false));
                if(candidates.isEmpty())continue;
                long now=timeService.serverNow(),expires=delivery.expireAt(now);
                store.update(seasonId,playerId,p->{
                    var won=candidates.stream().filter(t->p.points()>=t.requiredPoints()&&!p.claimed(t.tier(),track)&&(track!=BattlePassTrack.PAID||p.paidUnlocked())).toList();
                    return won.isEmpty()?p:p.withDeliveryPlan(BattlePassClaimRecovery.plan(seasonId,track,BattlePassDeliveryPlan.Kind.SEASON_MAIL,won,now,expires,""));
                });
                delivered+=delivery.recoverSeason(playerId,seasonId);
            }
            return delivered;
        });
        return sent;
    }

    // ---------- 组装 ----------

    /**
     * 战令的显示名（{@code pay_product} 表 {@code battle_pass} 行的 name 列）。
     *
     * <p><b>为什么由服务端给</b>：客户端没有配置表加载器（{@code pay_product} 不进包）
     * ⇒ 拿不到表；自己写一份就是**抄表** —— 表里改了客户端不跟着改，
     * 屏上的商品名与服务端卖的那个商品对不上。而商品名正是玩家在购买弹窗里要读的那句话。
     *
     * <p><b>读不到配置行时回 null 而不是编一个</b>：缺配置是运营事故，
     * 编一个名字会让屏上看不出「这个商品没配」。
     */
    private String battlePassName() {
        try {
            return configs.get(com.ironoath.config.cfg.PayProductCfg.class, BATTLE_PASS_PRODUCT_ID).name();
        } catch (com.ironoath.config.ConfigException e) {
            return null;
        }
    }

    private BattlePassStatusResp statusOf(String seasonId, BattlePassStore.Progress progress, long now) {
        List<BattlePassTierView> tiers = new ArrayList<>();
        for (BattlePassCfg row : rules.tiers()) {
            tiers.add(new BattlePassTierView(row.tier(), row.requiredPoints(),
                    progress.points() >= row.requiredPoints(),
                    progress.claimed((int) row.tier(), BattlePassTrack.FREE),
                    progress.claimed((int) row.tier(), BattlePassTrack.PAID),
                    rewardViewOf(row, BattlePassTrack.FREE), rewardViewOf(row, BattlePassTrack.PAID)));
        }
        long start = ServerCalendar.seasonStartOrZero(configs);
        long seasonEndAt = start == 0L ? 0L : start + rules.seasonTotalDays() * 86_400_000L;
        return new BattlePassStatusResp(battlePassName(), seasonId, progress.points(), progress.paidUnlocked(),
                seasonEndAt, tiers, now);
    }

    private BattlePassRewardView rewardViewOf(BattlePassCfg row, BattlePassTrack track) {
        RewardItem item = rewardItemOf(row, track);
        return new BattlePassRewardView(BattlePassRewardType.valueOf(item.type().name()),
                item.id(), rewardNames.nameOf(item), item.count());
    }

    /** 这一档这一条线给的是资源还是道具。表里两列各是一个枚举类型（生成器按列名出枚举），所以两边分开判。 */
    private static boolean isResource(BattlePassCfg row, boolean paid) {
        return paid ? row.paidRewardType() == BattlePassCfg.PaidRewardType.RESOURCE
                : row.freeRewardType() == BattlePassCfg.FreeRewardType.RESOURCE;
    }

    private RewardItem rewardItemOf(BattlePassCfg row, BattlePassTrack track) {
        boolean paid = track == BattlePassTrack.PAID;
        boolean resource = isResource(row, paid);
        RewardType rewardType = resource ? RewardType.RESOURCE : RewardType.ITEM;
        return new RewardItem(rewardType, paid ? row.paidRewardId() : row.freeRewardId(),
                paid ? row.paidRewardCount() : row.freeRewardCount());
    }
}
