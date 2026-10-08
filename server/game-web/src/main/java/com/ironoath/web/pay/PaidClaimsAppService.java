package com.ironoath.web.pay;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.DayKey;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigException;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.PayProductCfg;
import com.ironoath.config.cfg.ProductRewardCfg;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.pay.PaidEntitlements;
import com.ironoath.core.player.PlayerPaid;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardService;
import com.ironoath.web.dto.generated.CardClaimReq;
import com.ironoath.web.dto.generated.CardClaimResp;
import com.ironoath.web.dto.generated.CardStatusResp;
import com.ironoath.web.dto.generated.FundClaimReq;
import com.ironoath.web.dto.generated.FundClaimResp;
import com.ironoath.web.dto.generated.FundStatusResp;
import com.ironoath.web.dto.generated.FundTier;

/**
 * 职责：月卡日包与成长基金档位的<b>领取</b>与<b>状态查询</b>（B19 §一.1a / §一.1b）。
 * 依赖：玩家存档、玩家锁、幂等存储、{@link PaidProducts}（两张付费表）、{@link RewardService}。
 *
 * <p><b>为什么不塞进 {@code PayAppService}</b>：那个类管的是"订单的机械正确性"（下单 / 回调 / 补单），
 * 本类管的是"买完之后每天与每档的领取"。两件事的时钟粒度都不一样 —— 一个是订单生命周期，
 * 一个是自然日与主城等级。混在一起之后，"改补单逻辑"与"改日包规则"会变成同一处改动。
 *
 * <p><b>领取的失败方向与任务领奖相反，这是刻意的</b>：{@code QuestAppService} 选的是
 * "先标记已领、再发奖"（宁可漏发，因为任务奖励是免费的，重复领是白送）。
 * 这里选的是<strong>先发奖、后结清</strong>：日包与基金档位是<strong>已经付过钱</strong>的东西，
 * "标记了却没发出去"对玩家就是"我花钱买的第 12 天凭空消失"，而它不会在任何地方留下痕迹；
 * 反过来"发了却没标记"最坏是重复领一天，那在账本与日志里都是看得见的，也能由客服追回。
 * 收口清单 #22 那条"失败方向要选可追查的一侧"在这里指的正是这个方向。
 */
@Service
public class PaidClaimsAppService {

    private static final Logger LOG = LoggerFactory.getLogger(PaidClaimsAppService.class);
    private static final long LOCK_TIMEOUT_MS = 3000L;

    private final PlayerRepository players;
    private final PlayerLock playerLock;
    private final IdempotencyStore idempotency;
    private final TimeService timeService;
    private final ConfigRegistry configs;
    private final RewardService rewardService;
    private final PaidProducts catalog;

    public PaidClaimsAppService(PlayerRepository players, PlayerLock playerLock,
                                IdempotencyStore idempotency, TimeService timeService,
                                ConfigRegistry configs, RewardService rewardService,
                                PaidProducts catalog) {
        this.players = players;
        this.playerLock = playerLock;
        this.idempotency = idempotency;
        this.timeService = timeService;
        this.configs = configs;
        this.rewardService = rewardService;
        this.catalog = catalog;
    }

    // ---------- 月卡 ----------

    /** 月卡状态：是否在有效期、今天领过没有、现在点会领几天、当前生效的两项权益。 */
    public CardStatusResp cardStatus(String playerId) {
        long now = timeService.serverNow();
        PlayerPaid paid = require(playerId).paid();
        var entitlements = catalog.entitlements(paid, now);
        return new CardStatusResp(paid.cardActive(now), paid.cardExpireAt(),
                paid.cardClaimedToday(now), PaidEntitlements.claimableDays(paid, now),
                entitlements.adFree(), entitlements.bonusQueues(), now);
    }

    /**
     * 领月卡日包（可能一次补发多天，见 {@link PaidEntitlements}）。
     *
     * <p>日包内容全部走 {@link RewardService}：金币到顶的部分照旧转邮件，
     * 本类不自己碰钱包 —— 那是 B04 定下的分工，付费场景没有理由另开一条通路。
     */
    public CardClaimResp claimCard(String playerId, CardClaimReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                PlayerSave save = require(playerId);
                var verdict = PaidEntitlements.claimCard(save.paid(), now);
                switch (verdict.status()) {
                    case NOT_ACTIVE -> throw new BizException(ErrorCode.PAY_CARD_INACTIVE,
                            "月卡不在有效期内（到期时刻=" + save.paid().cardExpireAt()
                                    + "，当前=" + now + "），本次没有可领的日包");
                    case SETTLED_TODAY -> throw new BizException(ErrorCode.PAY_ALREADY_CLAIMED,
                            "今天（" + DayKey.of(now) + "）的日包已经领过了，明日零点（UTC+8）之后再领");
                    default -> {
                    }
                }
                long days = verdict.days();
                PayProductCfg monthly = catalog.requireKind(PayProductCfg.Kind.MONTHLY_CARD);
                List<RewardItem> plan = catalog.toDomainRewards(
                        catalog.alwaysRows(monthly.id()), days);
                var result = rewardService.grant(playerId, plan,
                        new RewardContext("pay", monthly.id(), monthly.id() + ":" + req.requestId(),
                                true));
                // 发完才结清游标：见类注释的失败方向。
                // <b>必须重读存档</b>：上面那次发放改的就是同一份 PlayerSave（金币入账走
                // PlayerWallet），版本号已经被它推前进过一次，拿发放前缓存的那份再写就是
                // 乐观锁冲突 —— 症状是玩家点了领取却报系统错误（PlayerWallet 的类注释点名过这件事）
                PlayerSave afterGrant = require(playerId);
                afterGrant.setPaid(afterGrant.paid().withCardSettledThrough(
                        DayKey.startOfDayPlusDays(now, 0)));
                players.save(afterGrant);
                LOG.info("月卡日包发放 playerId={} 补发={} 天 奖励={} 项 到期={}",
                        playerId, days, result.granted().size(), afterGrant.paid().cardExpireAt());
                return new CardClaimResp(days, PaidProducts.toProtocol(result.granted()),
                        afterGrant.paid().cardExpireAt(), now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    // ---------- 成长基金 ----------

    /** 基金六档的当前状态（没买过也照常返回档位表，客户端要能显示"买了之后有什么"）。 */
    public FundStatusResp fundStatus(String playerId) {
        long now = timeService.serverNow();
        PlayerSave save = require(playerId);
        PlayerPaid paid = save.paid();
        int level = save.cityLevel();
        PayProductCfg fund = catalog.requireKind(PayProductCfg.Kind.GROWTH_FUND);
        List<FundTier> tiers = new ArrayList<>();
        for (ProductRewardCfg row : catalog.fundTiers(fund.id())) {
            boolean claimed = paid.fundTierClaimed(row.id());
            long required = row.requireMainLevel();
            tiers.add(new FundTier(row.id(), (int) required, row.count(),
                    claimed, paid.fundPurchased() && level >= required && !claimed));
        }
        return new FundStatusResp(paid.fundPurchased(), List.copyOf(tiers), level, now);
    }

    /** 领一档基金。一次只领一档，档位 id 原样回传（见协议里 FundTier.tierId 的说明）。 */
    public FundClaimResp claimFund(String playerId, FundClaimReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                if (isBlank(req.tierId())) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "tierId 不得为空");
                }
                PlayerSave save = require(playerId);
                PlayerPaid paid = save.paid();
                ProductRewardCfg row = tierRowOf(req.tierId());
                if (!paid.fundPurchased()) {
                    // 这三句都会进 Result.detail，而客户端展示的就是 detail ?? msg ⇒ 不写 row.id()（台账 #821）；
                    // 玩家认的是「主城几级那一档」，不是配置表行名。
                    throw new BizException(ErrorCode.PAY_NOT_ENTITLED,
                            "成长基金尚未购买（或购买的那笔订单还没发货），这一档无从领取");
                }
                if (paid.fundTierClaimed(row.id())) {
                    throw new BizException(ErrorCode.PAY_ALREADY_CLAIMED,
                            "主城 " + row.requireMainLevel() + " 级那一档已经领过了，"
                                    + "基金档位是一次性的");
                }
                if (save.cityLevel() < row.requireMainLevel()) {
                    throw new BizException(ErrorCode.PAY_TIER_LOCKED,
                            "这一档要主城 " + row.requireMainLevel()
                                    + " 级才解锁（你当前主城 " + save.cityLevel() + " 级）");
                }
                var result = rewardService.grant(playerId,
                        catalog.toDomainRewards(List.of(row), 1L),
                        new RewardContext("pay", row.id(), row.id() + ":" + req.requestId(), true));
                // 与日包那条同一个理由：发放已经把这份存档的版本推前进过，标记必须写在重读的那份上
                PlayerSave afterGrant = require(playerId);
                afterGrant.setPaid(afterGrant.paid().withFundTierClaimed(row.id()));
                players.save(afterGrant);
                LOG.info("基金档位发放 playerId={} 档位={} 主城={} 级 奖励={} 项",
                        playerId, row.id(), afterGrant.cityLevel(), result.granted().size());
                return new FundClaimResp(row.id(), PaidProducts.toProtocol(result.granted()), now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    // ---------- 内部 ----------

    /** 档位行必须真的是基金档位：requireMainLevel 为空的商品行（日包、首充金币）不能从这条路领。 */
    private ProductRewardCfg tierRowOf(String tierId) {
        ProductRewardCfg row;
        try {
            row = configs.get(ProductRewardCfg.class, tierId);
        } catch (ConfigException e) {
            throw new BizException(ErrorCode.PAY_NOT_ENTITLED,
                    "product_reward 里没有档位 " + tierId + "：客户端手里的是旧表，重新拉一次档位");
        }
        if (row.requireMainLevel() == null) {
            throw new BizException(ErrorCode.PAY_NOT_ENTITLED,
                    "行 " + tierId + " 没有等级门槛，不是基金档位（基金档位靠 requireMainLevel 分批解锁）");
        }
        return row;
    }

    private PlayerSave require(String playerId) {
        return players.findByPlayerId(playerId)
                .orElseThrow(() -> new IllegalStateException("玩家存档不存在: " + playerId));
    }

    private void acquire(String requestId, long now) {
        if (isBlank(requestId)) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "付费权益领取必须带 requestId");
        }
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + requestId);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
