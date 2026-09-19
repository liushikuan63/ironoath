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
 *   <li><b>先标记后发奖</b>（与任务/结算同一条取舍）：失败方向选"标记了但没发出去"
 *       —— 那笔有补偿队列与客服，反过来会让玩家重领。</li>
 * </ol>
 */
@Service
public class BattlePassService {

    private static final Logger LOG = LoggerFactory.getLogger(BattlePassService.class);

    /** 玩家锁等待上限（与任务领取同一个量级：一次领取只碰一个存档）。 */
    private static final long LOCK_TIMEOUT_MS = 5_000L;

    private final BattlePassStore store;
    private final BattlePassRules rules;
    private final RewardService rewardService;
    private final RewardNames rewardNames;
    private final PlayerLock playerLock;
    private final TimeService timeService;
    private final ConfigRegistry configs;
    /** 赛季结束时把没领的档位奖励发进邮箱（B24 验收 2 的后半句：不静默作废）。 */
    private final StoreMailbox mailbox;

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
    }

    /** 本赛季战令全貌（20 档 + 三个结论位）。 */
    public BattlePassStatusResp status(String playerId) {
        String seasonId = rules.seasonId();
        return statusOf(seasonId, store.load(seasonId, playerId), timeService.serverNow());
    }

    /**
     * 加积分。**只由任务与活动的领取路径调用**（各自在发奖成功之后调一次）。
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

    /** 领一档的某一条线。 */
    public BattlePassClaimResp claim(String playerId, BattlePassClaimReq req) {
        if (req == null || req.track() == null || req.tier() == 0L) {
            throw new BizException(ErrorCode.PARAM_INVALID, "tier 与 track 都不得为空");
        }
        long tier = req.tier();
        BattlePassTrack track = req.track();
        BattlePassCfg row = rules.requireTier(tier);
        long now = timeService.serverNow();
        String seasonId = rules.seasonId();
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
            BattlePassStore.Progress before = store.load(seasonId, playerId);
            if (before.points() < row.requiredPoints()) {
                throw new BizException(ErrorCode.BATTLE_PASS_TIER_LOCKED,
                        "第 " + tier + " 档需要 " + row.requiredPoints() + " 分，当前 " + before.points() + " 分");
            }
            if (track == BattlePassTrack.PAID && !before.paidUnlocked()) {
                throw new BizException(ErrorCode.BATTLE_PASS_PAID_LOCKED, "第 " + tier + " 档的付费线还没解锁");
            }
            if (before.claimed((int) tier, track)) {
                throw new BizException(ErrorCode.BATTLE_PASS_ALREADY_CLAIMED,
                        "第 " + tier + " 档的" + (track == BattlePassTrack.PAID ? "付费" : "免费") + "线已经领过");
            }
            // 先标记再发奖：两个并发请求串在同一把玩家锁上，第二个看到的已经是标记过的进度
            BattlePassStore.Progress after = store.update(seasonId, playerId,
                    cur -> cur.withClaimed((int) tier, track));
            RewardItem reward = rewardItemOf(row, track);
            var result = rewardService.grant(playerId, List.of(reward),
                    RewardContext.toMail("battlePass", row.id(),
                            row.id() + ":" + track.name() + ":" + req.requestId()));
            if (result.hasCompensation()) {
                LOG.error("【战令奖励未入账已进补偿队列】playerId={} 档位={} 线={} compensationId={}",
                        playerId, tier, track, result.compensationId());
            }
            return new BattlePassClaimResp(track, tier, rewardViewOf(row, track),
                    statusOf(seasonId, after, now));
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
        if (seasonId == null || seasonId.isBlank()) {
            return 0;
        }
        List<BattlePassCfg> tiers = rules.tiers();
        int sent = 0;
        for (String playerId : store.playerIdsOf(seasonId)) {
            BattlePassStore.Progress progress = store.load(seasonId, playerId);
            for (BattlePassTrack track : BattlePassTrack.values()) {
                List<RewardItem> pending = new ArrayList<>();
                List<Integer> pendingTiers = new ArrayList<>();
                for (BattlePassCfg row : tiers) {
                    if (progress.points() < row.requiredPoints()) {
                        continue;   // 没达成的不补发：不能靠"补发"绕过积分
                    }
                    if (track == BattlePassTrack.PAID && !progress.paidUnlocked()) {
                        continue;   // 没买的不补发：那是没买过的东西
                    }
                    if (progress.claimed((int) row.tier(), track)) {
                        continue;   // 已经领过的不再发
                    }
                    pending.add(rewardItemOf(row, track));
                    pendingTiers.add((int) row.tier());
                }
                if (pending.isEmpty()) {
                    continue;
                }
                BattlePassStore.Progress after = store.update(seasonId, playerId, cur -> {
                    BattlePassStore.Progress next = cur;
                    for (Integer tier : pendingTiers) {
                        next = next.withClaimed(tier, track);
                    }
                    return next;
                });
                boolean marked = pendingTiers.stream()
                        .allMatch(tier -> after.claimed(tier, track));
                if (!marked) {
                    LOG.error("补发标记没落库，本档跳过（宁可这一档不发，也不能发两次）playerId={} 赛季={} 轨道={}",
                            playerId, seasonId, track);
                    continue;
                }
                mailbox.sendGrant(playerId, "BATTLE_PASS", "赛季战令奖励补发", pending,
                        "赛季结束时有 " + pending.size() + " 档"
                                + (track == BattlePassTrack.PAID ? "付费" : "免费") + "奖励还没领，已按档补发：",
                        // overflowToMail=false：这批东西**已经**在邮件里了，没有第二处可落
                        new RewardContext("battlePass", seasonId + ":" + track.name(),
                                "seasonEnd:" + seasonId + ":" + playerId, false));
                sent++;
            }
        }
        LOG.info("战令赛季末补发完成 赛季={} 发出邮件={} 封（覆盖 {} 名参与者）",
                seasonId, sent, store.playerIdsOf(seasonId).size());
        return sent;
    }

    // ---------- 组装 ----------

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
        return new BattlePassStatusResp(seasonId, progress.points(), progress.paidUnlocked(),
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
