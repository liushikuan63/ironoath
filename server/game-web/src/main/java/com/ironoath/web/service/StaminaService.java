package com.ironoath.web.service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.time.DayKey;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.limit.DailyCounter;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.web.dto.generated.StaminaBuyReq;
import com.ironoath.web.dto.generated.StaminaBuyResp;
import com.ironoath.web.dto.generated.StaminaResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 职责：体力（B09 §5）—— 惰性恢复的读取、金币购买、每日次数上限。
 * 依赖：game-config（global / resource）、game-core 的端口（Wallet / PlayerLock / DailyCounter / 幂等）、
 * {@link CityAppService}（加锁 + 惰性结算）。
 *
 * <p><b>体力是 resource 表里的一行（kind=STAMINA），不是一套独立系统</b>。
 * B09 §5 的四条要求在资源模型里分别对应现成的机制：
 * <ul>
 *   <li>「每 X 分钟恢复 1 点」⇒ {@code basePerHour}（10 点/小时 = 每 6 分钟 1 点）</li>
 *   <li>「溢出不累积超上限」⇒ 结算与发放都按 {@code cap} 截断</li>
 *   <li>「上限随等级提升」⇒ {@link ResourceRateService} 按主城等级算容量</li>
 *   <li>「不要用定时任务给全体玩家重置体力」⇒ 惰性结算，登录时按时间差补</li>
 * </ul>
 * 另写一套体力系统的代价不是代码量，而是<b>两套结算口径</b>：
 * 资源的零头结转规则一旦改动（本轮就刚改过一次），体力那份不会跟着改，
 * 而体力恰恰是最依赖零头结转的 —— 每 6 分钟 1 点，玩家每 5 分钟上线一次，
 * 零头不结转就意味着体力永远恢复不了。
 *
 * <p><b>购买走「次数 → 扣金币 → 发体力」的顺序，且任何一步失败都要退还前一步</b>。
 * 反过来（先扣金币再占次数）在次数用尽时会让玩家的金币白花；
 * 而次数已占但金币不足时，必须把次数退回去，否则玩家会因为一次余额不足白白损失一次购买机会
 * —— 与广告加速的日限次是同一条纪律。
 */
@Service
public class StaminaService {

    /** 体力在 resource 表里的行 id。 */
    public static final String RESOURCE_ID = ResourceIds.STAMINA;

    /** 金币在 resource 表里的行 id。 */
    public static final String GOLD_ID = ResourceIds.GOLD;

    /** 每日购买次数的计数域。与广告加速分域，两者互不占用。 */
    private static final String SCOPE_BUY = "stamina_buy";

    private static final long LOCK_TIMEOUT_MS = 3000L;
    private static final long MILLIS_PER_HOUR = 3_600_000L;

    private static final Logger LOG = LoggerFactory.getLogger(StaminaService.class);

    private final ConfigRegistry configs;
    private final CityAppService cityAppService;
    private final RewardPorts.Wallet wallet;
    private final PlayerLock playerLock;
    private final IdempotencyStore idempotency;
    private final DailyCounter dailyCounter;
    private final TimeService timeService;

    public StaminaService(ConfigRegistry configs, CityAppService cityAppService,
                          RewardPorts.Wallet wallet, PlayerLock playerLock,
                          IdempotencyStore idempotency, DailyCounter dailyCounter,
                          TimeService timeService) {
        this.configs = configs;
        this.cityAppService = cityAppService;
        this.wallet = wallet;
        this.playerLock = playerLock;
        this.idempotency = idempotency;
        this.dailyCounter = dailyCounter;
        this.timeService = timeService;
    }

    // ---------- 读取 ----------

    /**
     * 体力面板。
     *
     * <p>走 {@link CityAppService#withSettledCity}：一来结算恢复量，
     * 二来刷新容量（容量随主城等级变，只有产率服务会把它写回存档）。
     */
    public StaminaResp view(String playerId) {
        requirePlayer(playerId);
        return cityAppService.withSettledCity(playerId, snap -> {
            PlayerResourceState state = snap.player().resource(RESOURCE_ID);
            long perHour = state.perHour();
            Long nextPointAt = null;
            if (state.current() < state.cap() && perHour > 0L) {
                // lastSettle 是「产量已入账到的时刻」（不足 1 点的时间零头结转在它之后），
                // 所以下一点恢复的时刻就是它加上一个恢复周期
                nextPointAt = state.lastSettle() + MILLIS_PER_HOUR / perHour;
            }
            String dayKey = DayKey.of(snap.now());
            int bought = (int) dailyCounter.used(SCOPE_BUY, playerId, dayKey);
            long limit = configs.longParam("STAMINA_BUY_DAILY_LIMIT");
            // 达到上限时单价下发 0，客户端据此把按钮置灰而不是隐藏：
            // 体力是付费点，让玩家看见「明天还能买」比让它消失更有价值
            long cost = bought >= limit ? 0L : nextCostGold(bought);
            return new StaminaResp(state.current(), state.cap(), perHour, nextPointAt,
                    bought, cost, snap.now());
        });
    }

    // ---------- 购买 ----------

    /** 用金币买体力（B09 §5 的付费点；直购礼包属 B15）。 */
    public StaminaBuyResp buy(String playerId, StaminaBuyReq req) {
        requirePlayer(playerId);
        if (req == null || req.requestId() == null || req.requestId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "requestId 不得为空");
        }
        if (req.times() != null && req.times() < 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "times 必须 >= 1，实际=" + req.times());
        }
        long now = timeService.serverNow();
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(req.requestId(), now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + req.requestId());
        }
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> doBuy(playerId, req, now));
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    private StaminaBuyResp doBuy(String playerId, StaminaBuyReq req, long now) {
        String dayKey = DayKey.of(now);
        long limit = configs.longParam("STAMINA_BUY_DAILY_LIMIT");
        long bought = dailyCounter.used(SCOPE_BUY, playerId, dayKey);
        long remaining = limit - bought;
        if (remaining <= 0L) {
            // 明确提示「明日重置」，绝不静默失败（B09 禁止项 + 验收 3）
            throw new BizException(ErrorCode.RATE_LIMITED,
                    "今日体力购买次数已用完（" + limit + " 次），明日 " + dayKey + " 之后重置");
        }
        int times = req.times() == null ? 1 : (int) Math.min(req.times(), remaining);

        // 一、先占次数。此时还没扣任何钱，失败不需要退款
        for (int i = 0; i < times; i++) {
            if (!dailyCounter.tryConsume(SCOPE_BUY, playerId, dayKey, limit)) {
                for (int j = 0; j < i; j++) {
                    dailyCounter.refund(SCOPE_BUY, playerId, dayKey);
                }
                throw new BizException(ErrorCode.RATE_LIMITED,
                        "今日体力购买次数已用完（" + limit + " 次），明日 " + dayKey + " 之后重置");
            }
        }

        // 二、扣金币。单价按当日已购次数递增，所以多次购买要逐次累加
        long totalCost = 0L;
        for (int i = 0; i < times; i++) {
            totalCost += nextCostGold((int) (bought + i));
        }
        try {
            if (wallet.deduct(playerId, GOLD_ID, totalCost, now) == 0L) {
                throw new BizException(ErrorCode.RESOURCE_NOT_ENOUGH,
                        "金币不足：需要 " + totalCost + "，当前 "
                                + wallet.available(playerId, GOLD_ID, now));
            }
        } catch (RuntimeException e) {
            // 金币没扣成就必须把次数退回去，否则玩家因为一次余额不足白白损失购买机会
            for (int i = 0; i < times; i++) {
                dailyCounter.refund(SCOPE_BUY, playerId, dayKey);
            }
            throw e;
        }

        // 三、发体力。grant 自己会按容量截断，超出部分永久损失（B09 §5：溢出不累积超上限）
        long perAmount = configs.longParam("STAMINA_BUY_AMOUNT");
        long granted = 0L;
        for (int i = 0; i < times; i++) {
            granted += wallet.grant(playerId, RESOURCE_ID, perAmount, now);
        }
        long requested = perAmount * times;
        if (granted < requested) {
            // 照实告知少了多少，而不是静默吞掉：金币已经扣了，
            // 玩家有权知道自己买的东西有一部分溢出了（客户端应当在购买前用 cap 提示）
            LOG.info("体力购买溢出 playerId={} 请求={} 实到={} 金币={} 次数={}",
                    playerId, requested, granted, totalCost, times);
        }
        StaminaResp view = view(playerId);
        return new StaminaBuyResp(view, granted, totalCost, (int) (bought + times));
    }

    /**
     * 当日第 {@code alreadyBought + 1} 次购买的单价 = 基准 × 增长率^已购次数，向下取整。
     *
     * <p>递增而不是恒价：恒价的话重氪玩家可以一天买满几百次，
     * 把 PVE 内容在一天内刷空 —— 而关卡内容的消耗速度是节奏设计的一部分，
     * 不该由钱包深度决定。
     */
    public long nextCostGold(int alreadyBought) {
        if (alreadyBought < 0) {
            throw new IllegalArgumentException("已购次数不得为负：" + alreadyBought);
        }
        long base = FixedPoint.of(configs.longParam("STAMINA_BUY_COST_BASE"));
        long growth = configs.fixedParam("STAMINA_BUY_COST_GROWTH");
        return FixedPoint.truncate(FixedPoint.mul(base, FixedPoint.powInt(growth, alreadyBought)));
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
    }
}
