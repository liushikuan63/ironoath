package com.ironoath.web.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.DayKey;
import com.ironoath.common.time.TimeService;
import com.ironoath.common.time.WeekKey;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ShopCfg;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.limit.DailyCounter;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.reward.GrantResult;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;
import com.ironoath.core.social.Alliance;
import com.ironoath.core.social.Squad;
import com.ironoath.web.dto.generated.ShopBuyReq;
import com.ironoath.web.dto.generated.ShopBuyResp;
import com.ironoath.web.dto.generated.ShopCurrency;
import com.ironoath.web.dto.generated.ShopListResp;
import com.ironoath.web.dto.generated.ShopRefresh;
import com.ironoath.web.dto.generated.ShopRowView;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：商店兑换（B02 商店表 + B10 验收 8「商店兑换正确扣减」）—— 货架查询与购买，覆盖金币 /
 * 联盟贡献值 / 小队币三种货币。
 * 依赖：配置表、{@link RewardPorts.Wallet}、{@link SocialStore}、{@link DailyCounter}、
 * {@link RewardService}。
 *
 * <p><b>本类存在的另一半理由是「把一条商品行变成一次真实变动」</b>：此前 {@code shop.json} 的
 * 13 行里有 10 行连协议都没有（生成出来的 {@code ShopBuyReq} 服务端零引用），
 * 而 #21 上架的那张闭城死守令因此只是账面上的一行 —— 玩家看不见、也买不了。
 *
 * <p><b>四条不可让步的口径</b>：
 * <ol>
 *   <li><b>价格与限购只由服务端按 {@code rowId} 查表</b>：客户端传价格等于把定价权交出去。
 *       按行 id 而不是按道具 id 定位也是同一条纪律 —— 同一件道具可以在表里有多行不同价格
 *       （{@code item_res_wood_10k} 同时出现在金币行与联盟行），按 itemId 找会拿错价格。</li>
 *   <li><b>先扣钱、后发货</b>（{@code Alliance#spendContribution} 的注释里记了为什么与捐献相反）。
 *       反过来做的话扣款失败就是白送一件，而白送在商店里是可以被脚本刷的。</li>
 *   <li><b>发货失败不能静默</b>：{@link RewardService} 的语义是尽力发放，背包放不下的部分会转邮件
 *       （所以不亏），逐条出错会进补偿队列并返回 compensationId。真的整个抛出来时（实现故障）
 *       本类<b>不退钱</b>而是把该发的东西记进补偿队列 —— 与支付域「钱收了货没发 = 负债，
 *       必须留下可查凭据」同一条口径。退钱更坏：退款本身可能又失败，那时连「该退多少」都没记录了。</li>
 *   <li><b>限购按周期标签计数</b>：DAILY 用 {@code DayKey}、WEEKLY 用 {@code WeekKey}（两者共用
 *       同一个 UTC+8 口径）、NONE 是永久额度。全项目只有 {@code DailyCounter} 这一个计数器 ——
 *       再造一个「周计数器」就会有两份「什么叫本周」，而 #25 已经演示过那种漂移的代价。</li>
 * </ol>
 */
@Service
public class ShopAppService {

    private static final Logger LOG = LoggerFactory.getLogger(ShopAppService.class);
    private static final long LOCK_TIMEOUT_MS = 3000L;
    /** 永久额度（{@code refreshType=NONE}）的周期标签：固定串，跨期不会换键。 */
    private static final String LIFETIME_PERIOD = "lifetime";
    /**
     * 尚未开放兑换的页签。SEASON_COIN 的账本只进不出（{@code SeasonLedgerStore} 没有扣减入口），
     * 它的用途口径本身还没裁决（收口清单 #6b），唯一那一行的货品效果也没有定义数值（#19）——
     * 三件事都指向「先别开」。<b>但它仍然是货架的一部分</b>：列表照给、每行 purchasable=false，
     * 因为「还没开」与「不存在」对玩家是两句话。
     */
    private static final List<ShopCurrency> CLOSED_CURRENCIES = List.of(ShopCurrency.SEASON_COIN);
    private static final String CLOSED_NOTICE = "赛季币的用途尚未定案（等收口清单 #6b 裁决），本页暂不能兑换";

    private final ConfigRegistry configs;
    private final RewardPorts.Wallet wallet;
    private final RewardService rewardService;
    private final RewardPorts.Compensation compensation;
    private final DailyCounter limits;
    private final SocialStore social;
    private final PlayerRepository players;
    private final PlayerLock playerLock;
    private final IdempotencyStore idempotency;
    private final TimeService timeService;

    public ShopAppService(ConfigRegistry configs, RewardPorts.Wallet wallet, RewardService rewardService,
                          RewardPorts.Compensation compensation, DailyCounter limits,
                          SocialStore social, PlayerRepository players, PlayerLock playerLock,
                          IdempotencyStore idempotency, TimeService timeService) {
        this.configs = configs;
        this.wallet = wallet;
        this.rewardService = rewardService;
        this.compensation = compensation;
        this.limits = limits;
        this.social = social;
        this.players = players;
        this.playerLock = playerLock;
        this.idempotency = idempotency;
        this.timeService = timeService;
    }

    /** 不能买的原因，连同该回哪个错误码。<b>两者必须一起算</b>：只返回字符串的话调用方得靠前缀猜错误码。 */
    private record Block(ErrorCode code, String reason) {
    }

    // ---------- 货架 ----------

    /**
     * 某个货币页签的货架。
     *
     * <p><b>不按等级过滤</b>：等级不够的商品应当显示成「主城 5 级解锁」而不是消失 ——
     * 让玩家知道有这个东西，正是解锁类门槛存在的意义。
     *
     * <p>{@code purchasable} 判定的是「此刻买<b>一个</b>行不行」。买多个时的总价由扣款那一步兜
     * （{@code charge} 里比对的是 {@code price × count} 的真实总额），所以列表不必关心 count。
     */
    public ShopListResp list(String playerId, ShopCurrency currency) {
        long now = timeService.serverNow();
        boolean open = !CLOSED_CURRENCIES.contains(currency);
        Long balance = open ? Long.valueOf(balanceOf(playerId, currency, now)) : null;
        int cityLevel = cityLevelOf(playerId);

        List<ShopRowView> rows = new ArrayList<>();
        for (ShopCfg row : rowsOf(currency)) {
            long used = limits.used(scopeOf(row), playerId, periodOf(row, now));
            Block block = open ? blockOf(playerId, row, used, cityLevel, balance.longValue())
                    : new Block(ErrorCode.SHOP_CURRENCY_CLOSED, CLOSED_NOTICE);
            rows.add(new ShopRowView(row.id(), row.itemId(), row.name(), currency,
                    row.price(), refreshOf(row), (int) row.limitCount(), (int) used,
                    (int) Math.max(0L, row.limitCount() - used), (int) row.requireMainLevel(),
                    block == null, block == null ? null : block.reason()));
        }
        return new ShopListResp(currency, open, open ? null : CLOSED_NOTICE, List.copyOf(rows), balance, now);
    }

    // ---------- 兑换 ----------

    /**
     * 兑换一单。<b>占额度 → 扣钱 → 发货</b>。
     *
     * <p>顺序上刻意<b>先占限购额度</b>再扣钱：额度是「一人一周一次」这类稀缺配额的唯一闸门，
     * 若先扣钱再发现额度已满，退款路径本身就是又一次失败机会（体力购买的
     * {@code StaminaService} 走的是同一顺序）。占到的额度在后续任何失败里都退回去。
     */
    public ShopBuyResp buy(String playerId, ShopBuyReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                if (req == null || req.count() < 1) {
                    // 不得为 0 或负数：负数会让「limitCount - count」变成买得越多剩得越多
                    throw new BizException(ErrorCode.PARAM_INVALID, "count 必须 >= 1，实际="
                            + (req == null ? null : req.count()));
                }
                ShopCfg row = requireRow(req);
                String period = periodOf(row, now);
                Block block = blockOf(playerId, row, limits.used(scopeOf(row), playerId, period),
                        cityLevelOf(playerId), balanceOf(playerId, req.currency(), now));
                if (block != null) {
                    // 与列表用同一个判定函数：两处各写一遍的话，
                    // 「列表说能买、点下去报错」迟早会出现，而那是最伤信任的一种表现
                    throw new BizException(block.code(), block.reason());
                }

                int consumed = consumeSlots(row, playerId, period, req.count());
                long spent = row.price() * req.count();
                try {
                    charge(playerId, row, spent, now);
                } catch (RuntimeException e) {
                    releaseSlots(row, playerId, period, consumed);
                    throw e;
                }
                deliver(playerId, row, req, spent);

                long used = limits.used(scopeOf(row), playerId, period);
                long balance = balanceOf(playerId, req.currency(), now);
                LOG.info("商店兑换 playerId={} 行={} 道具={} 个数={} 币种={} 花费={} 余额={} 本周期已用={}/{}",
                        playerId, row.id(), row.itemId(), req.count(), row.priceCurrency(),
                        spent, balance, used, row.limitCount());
                return new ShopBuyResp(row.id(), row.itemId(), req.count(), req.currency(), spent,
                        balance, (int) used, (int) Math.max(0L, row.limitCount() - used), now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    // ---------- 内部：判定 ----------

    /**
     * 这一行此刻不能买的原因；可买时返回 null。
     *
     * <p>顺序是刻意的：先讲<b>资格</b>（页签、等级、在不在联盟/小队），再讲<b>配额</b>，最后才讲<b>钱</b>。
     * 反过来会让一个没加入联盟的人每次都看到「贡献值不足」，而他真正该做的事是先去加入。
     */
    private Block blockOf(String playerId, ShopCfg row, long used, int cityLevel, long balance) {
        if (row.requireMainLevel() > 0 && cityLevel < row.requireMainLevel()) {
            return new Block(ErrorCode.CITY_MAIN_LEVEL_LOW,
                    "主城 " + row.requireMainLevel() + " 级解锁，当前 " + cityLevel + " 级");
        }
        if (row.priceCurrency() == ShopCfg.PriceCurrency.ALLIANCE_COIN
                && social.allianceOf(playerId).isEmpty()) {
            return new Block(ErrorCode.ALLIANCE_NOT_FOUND, "需要先加入联盟才能用贡献值兑换");
        }
        if (row.priceCurrency() == ShopCfg.PriceCurrency.SQUAD_COIN) {
            Optional<Squad> squad = social.squadOf(playerId);
            if (squad.isEmpty()) {
                return new Block(ErrorCode.SQUAD_NOT_FOUND, "需要先加入小队才能用小队币兑换");
            }
            if (!squad.get().shopUnlocked()) {
                // B10 §小队等级：「等级决定人数上限与商店货品」。只有成员身份不够 ——
                // 1 级小队根本没有商店，此时的正确提示是「小队等级不足」而不是「你不是队员」
                return new Block(ErrorCode.SQUAD_LOCKED,
                        "小队商店尚未解锁（小队等级不足，当前 " + squad.get().level() + " 级）");
            }
        }
        if (used >= row.limitCount()) {
            return new Block(ErrorCode.SHOP_LIMIT_REACHED,
                    limitWord(row) + "限购 " + row.limitCount() + " 个，已用完");
        }
        if (balance < row.price()) {
            return new Block(lackCode(row),
                    currencyName(row) + "不足：需要 " + row.price() + "，当前 " + balance);
        }
        return null;
    }

    private static ErrorCode lackCode(ShopCfg row) {
        return switch (row.priceCurrency()) {
            case GOLD -> ErrorCode.RESOURCE_NOT_ENOUGH;
            case ALLIANCE_COIN -> ErrorCode.ALLIANCE_CONTRIBUTION_LACK;
            case SQUAD_COIN -> ErrorCode.SOCIAL_SQUAD_COIN_LACK;
            case SEASON_COIN -> ErrorCode.SHOP_CURRENCY_CLOSED;
        };
    }

    /** 限购的周期说法。<b>必须与 {@link #periodOf} 用的是同一个枚举</b>，否则文案与实际刷新口径会漂移。 */
    private static String limitWord(ShopCfg row) {
        return switch (row.refreshType()) {
            case DAILY -> "今日";
            case WEEKLY -> "本周";
            case NONE -> "永久";
        };
    }

    private static String currencyName(ShopCfg row) {
        return switch (row.priceCurrency()) {
            case GOLD -> "金币";
            case ALLIANCE_COIN -> "贡献值";
            case SQUAD_COIN -> "小队币";
            // 该页签不可购买（CLOSED_CURRENCIES）。走到这里说明 CLOSED 名单漏了它 —— 宁可文案别扭，
            // 也不要在这里悄悄卖出一个没有账本的货币
            case SEASON_COIN -> "赛季币（该页签尚未开放）";
        };
    }

    private BizException closedCurrency(ShopCurrency currency) {
        return new BizException(ErrorCode.SHOP_CURRENCY_CLOSED,
                currency + " 页当前不开放兑换：" + CLOSED_NOTICE);
    }

    // ---------- 内部：账本 ----------

    private long balanceOf(String playerId, ShopCurrency currency, long now) {
        return switch (currency) {
            // available() 内部会先做惰性结算但不落库，所以读路径上不必额外结算
            case GOLD -> wallet.available(playerId, ResourceIds.GOLD, now);
            case ALLIANCE_COIN -> social.allianceOf(playerId)
                    .map(alliance -> alliance.contributionOf(playerId)).orElse(0L);
            case SQUAD_COIN -> social.squadOf(playerId)
                    .map(squad -> squad.squadCoinOf(playerId)).orElse(0L);
            // 列表在 open=false 时不会调到这里（余额回 null），购买在 requireRow 处已拒绝。
            // 返回 0 而不是抛，是为了让「页签没开」这一件事只由 CLOSED_CURRENCIES 一处表达
            case SEASON_COIN -> 0L;
        };
    }

    /** 扣款。<b>三种货币都要求「全有或全无」</b>：部分扣款会让玩家花了一部分钱什么也没拿到。 */
    private void charge(String playerId, ShopCfg row, long spent, long now) {
        switch (row.priceCurrency()) {
            case GOLD -> {
                if (wallet.deduct(playerId, ResourceIds.GOLD, spent, now) == 0L) {
                    throw new BizException(ErrorCode.RESOURCE_NOT_ENOUGH,
                            "金币不足：需要 " + spent + "，当前 "
                                    + wallet.available(playerId, ResourceIds.GOLD, now));
                }
            }
            case ALLIANCE_COIN -> {
                Alliance alliance = social.allianceOf(playerId)
                        .orElseThrow(() -> new BizException(ErrorCode.ALLIANCE_NOT_FOUND,
                                "兑换贡献值商品必须在联盟里"));
                try {
                    alliance.spendContribution(playerId, spent);
                } catch (IllegalStateException e) {
                    throw new BizException(ErrorCode.ALLIANCE_CONTRIBUTION_LACK, e.getMessage());
                }
                social.saveAlliance(alliance);
            }
            case SQUAD_COIN -> {
                Squad squad = social.squadOf(playerId)
                        .orElseThrow(() -> new BizException(ErrorCode.SQUAD_NOT_FOUND,
                                "兑换小队币商品必须在小队里"));
                try {
                    squad.spendSquadCoin(playerId, spent);
                } catch (IllegalStateException e) {
                    throw new BizException(ErrorCode.SOCIAL_SQUAD_COIN_LACK, e.getMessage());
                }
                social.saveSquad(squad);
            }
            case SEASON_COIN -> throw closedCurrency(ShopCurrency.SEASON_COIN);
        }
    }

    /**
     * 发货。见类注释第 3 条：<b>失败不退钱，而是把该发的记进补偿队列</b>。
     */
    private void deliver(String playerId, ShopCfg row, ShopBuyReq req, long spent) {
        List<RewardItem> goods = List.of(new RewardItem(RewardType.ITEM, row.itemId(), req.count()));
        RewardContext ctx = RewardContext.toMail("shop", row.id(), req.requestId());
        try {
            GrantResult result = rewardService.grant(playerId, goods, ctx);
            if (result.hasCompensation()) {
                LOG.error("商店已扣款但部分奖励进了补偿队列 playerId={} 行={} 花费={} 补偿单={}：必须有人跟进",
                        playerId, row.id(), spent, result.compensationId());
            }
            if (result.hasOverflow()) {
                LOG.info("商店奖励溢出转邮件 playerId={} 行={} 溢出={} 邮件={}",
                        playerId, row.id(), result.overflow(), result.mailId());
            }
        } catch (RuntimeException e) {
            String compensationId = compensation.record(playerId, goods, ctx, e);
            LOG.error("商店已扣款但发货抛异常 playerId={} 行={} 道具={} 个数={} 花费={} 补偿单={}：{}"
                            + "（不退钱：差额由补偿队列追平，退款只会多一次失败机会）",
                    playerId, row.id(), row.itemId(), req.count(), spent, compensationId,
                    String.valueOf(e.getMessage()));
        }
    }

    // ---------- 内部：表与计数 ----------

    private List<ShopCfg> rowsOf(ShopCurrency currency) {
        List<ShopCfg> out = new ArrayList<>();
        for (ShopCfg row : configs.all(ShopCfg.class)) {
            if (row.priceCurrency() == ShopCfg.PriceCurrency.valueOf(currency.name())) {
                out.add(row);
            }
        }
        return out;
    }

    private ShopCfg requireRow(ShopBuyReq req) {
        if (req.rowId() == null || req.rowId().isBlank()) {
            throw new BizException(ErrorCode.SHOP_ROW_NOT_FOUND, "rowId 不得为空");
        }
        if (CLOSED_CURRENCIES.contains(req.currency())) {
            throw closedCurrency(req.currency());
        }
        Optional<ShopCfg> found = configs.all(ShopCfg.class).stream()
                .filter(candidate -> candidate.id().equals(req.rowId()))
                .findFirst();
        ShopCfg row = found.orElseThrow(() -> new BizException(ErrorCode.SHOP_ROW_NOT_FOUND,
                "rowId=" + req.rowId() + "（多半是货架过期了，重新拉一次 GET /shop/list）"));
        // 客户端声明的币种必须与行里的币种一致 —— 不一致说明它拿的是过期货架。
        // 默默按行里的币种扣钱是最坏的做法：玩家以为花小队币，实际被扣了金币
        if (row.priceCurrency() != ShopCfg.PriceCurrency.valueOf(req.currency().name())) {
            throw new BizException(ErrorCode.SHOP_ROW_NOT_FOUND,
                    "行 " + req.rowId() + " 的计价是 " + row.priceCurrency()
                            + "，与请求声明的 " + req.currency() + " 不符：请重新拉取货架");
        }
        return row;
    }

    private static ShopRefresh refreshOf(ShopCfg row) {
        return ShopRefresh.valueOf(row.refreshType().name());
    }

    private static String scopeOf(ShopCfg row) {
        return "shop:" + row.id();
    }

    /**
     * 周期标签。DAILY/WEEKLY/NONE 三种都走同一个计数器，区别只在标签怎么算 ——
     * 计数器本身不理解日期（见 {@code DailyCounter} 的类注释），所以「什么叫本周」在全项目只有一份。
     */
    private static String periodOf(ShopCfg row, long now) {
        return switch (row.refreshType()) {
            case DAILY -> DayKey.of(now);
            case WEEKLY -> WeekKey.of(now);
            case NONE -> LIFETIME_PERIOD;
        };
    }

    /** 逐个占额度，返回实际占到的个数（供失败时退回）。 */
    private int consumeSlots(ShopCfg row, String playerId, String period, int count) {
        int consumed = 0;
        for (int i = 0; i < count; i++) {
            if (!limits.tryConsume(scopeOf(row), playerId, period, row.limitCount())) {
                releaseSlots(row, playerId, period, consumed);
                throw new BizException(ErrorCode.SHOP_LIMIT_REACHED,
                        limitWord(row) + "限购 " + row.limitCount() + " 个，本次第 " + (consumed + 1) + " 个被拒");
            }
            consumed++;
        }
        return consumed;
    }

    private void releaseSlots(ShopCfg row, String playerId, String period, int consumed) {
        for (int i = 0; i < consumed; i++) {
            limits.refund(scopeOf(row), playerId, period);
        }
    }

    private int cityLevelOf(String playerId) {
        return players.findByPlayerId(playerId)
                .orElseThrow(() -> new BizException(ErrorCode.PLAYER_NOT_FOUND, playerId))
                .cityLevel();
    }

    private void acquire(String requestId, long now) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "商店兑换必须带 requestId：它会扣钱并发货");
        }
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + requestId);
        }
    }
}
