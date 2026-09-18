package com.ironoath.web.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.PayProductCfg;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.pay.PayOrder;
import com.ironoath.core.player.PlayerPaid;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.pay.PaidProducts;
import com.ironoath.web.dto.generated.CreateOrderReq;
import com.ironoath.web.dto.generated.CreateOrderResp;
import com.ironoath.web.dto.generated.DebtOrderView;
import com.ironoath.web.dto.generated.OrderStatus;
import com.ironoath.web.dto.generated.OrderStatusResp;
import com.ironoath.web.dto.generated.PayCallbackReq;
import com.ironoath.web.dto.generated.PayDebtResp;
import com.ironoath.web.dto.generated.PayParams;
import com.ironoath.web.dto.generated.PayRetryReq;
import com.ironoath.web.dto.generated.PayRewardItem;
import com.ironoath.web.dto.generated.PricesResp;
import com.ironoath.web.dto.generated.ProductPrice;
import com.ironoath.config.cfg.GiftCfg;
import com.ironoath.config.cfg.PayProductCfg;
import com.ironoath.core.player.PlayerGiftPopup;
import com.ironoath.core.player.PlayerSave;

/**
 * 职责：B15 商业化域应用服务 —— 价格表、下单、支付回调、补单、订单状态。
 * 依赖：{@link com.ironoath.core.pay.PayOrderStore}（订单存储，内存与 Mongo 两个实现）、
 * {@link SignatureVerifier}、{@link ProductFulfiller}、配置表。
 *
 * <p><b>本类负责的是「订单的机械正确性」，不负责「商品发了什么」</b>：
 * 后者由 {@link ProductFulfiller} 承担 —— 真实现是
 * {@link com.ironoath.web.pay.ProductFulfilment}（B19 的三类商品：月卡延期、基金登记、首充即发）。
 * 分工没有变：本类不知道奖励系统长什么样，只知道"发出去了没有"。
 * 失败方向也和 B15 立这条时一样：发不出去就留在补单队列里可查（{@code retryQueued=true}），
 * 绝不假装成功 —— 钱收到了、货没发出去是可追查的，而"账面消失的一笔钱"追不回来。
 *
 * <p>本类还管两件事的<b>下单侧</b>：选将必须合法（不替玩家挑），一次性商品不许买第二次
 * （{@link com.ironoath.web.pay.PaidProducts#alreadyOwned}）。<b>买完之后每天/每档的领取</b>
 * 在 {@link com.ironoath.web.pay.PaidClaimsAppService} —— 那边的时钟粒度是自然日与主城等级，
 * 不是订单生命周期。
 *
 * <p><b>四条不可让步的口径</b>（B15 禁止项）：
 * <ol>
 *   <li><b>回调幂等</b>：同一订单重复回调只发货一次。微信收不到及时响应就会重试，
 *       所以重复回调是常态而不是异常（验收 2 要求重复 3 次只发一次）</li>
 *   <li><b>回调里不做重逻辑</b>：验签 → 改状态 → 落库，然后才发货。
 *       回调有时限，超时会微信判失败并重试，于是「发货慢」会变成「重复发货的诱因」</li>
 *   <li><b>价格永远由服务端按 productId 查表</b>：客户端传价格等于把定价权交出去，
 *       改一下请求体就能一分钱买月卡</li>
 *   <li><b>金额全程用分（long）</b>：浮点聚合的症状是流水尾数出现 0.01 级漂移，
 *       而对账时那一分钱会让人查一整天</li>
 * </ol>
 */
@Service
public class PayAppService {

    private static final Logger LOG = LoggerFactory.getLogger(PayAppService.class);
    private static final long LOCK_TIMEOUT_MS = 3000L;
    /**
     * 负债明细一次最多带几笔。
     *
     * <p>写成常量而不是配置参数：这是「一条只读运维查询最多捞多少行」的服务端自我保护，
     * 不是策划会去调的游戏数值 —— 放进 global 表就是给一个不该有第二个家的东西发户口。
     * 50 笔足够看出成因（一笔大的还是一堆小的），再多的明细该去导出而不是看接口。
     */
    private static final int DEBT_LIST_MAX = 50;

    /**
     * 回调验签。<b>这是一个显式的接缝</b>：真实的米大师验签需要商户密钥，
     * 而密钥属于部署凭据（环境变量，不进版本库），本地无法验证。
     *
     * <p>做成端口而不是在 service 里写 {@code if (dev)} 分支的理由是：
     * 分支版本在正式环境里只要有一个配置项没设对就会静默退化成「不验签」，
     * 而不验签的回调端点等于任何人 POST 一下就能给自己发货。
     * 端口版本则要求正式环境<b>必须</b>提供一个实现，缺了就起不来。
     */
    public interface SignatureVerifier {

        /**
         * @return true 表示验签通过
         */
        boolean verify(PayCallbackReq callback, PayOrder order);

        /** 这个实现能否用于正式环境。启动日志会把它打出来，避免沙箱实现被带上生产。 */
        default boolean productionReady() {
            return true;
        }
    }

    /**
     * 发货。<b>返回 false 表示这次没发出去</b>，订单会进补单队列（B15 §2）。
     *
     * <p>实现方必须自己保证幂等：补单会重复调用同一个订单的发货，
     * 而 {@code PayOrder} 只在「订单状态」层面保证不重复，
     * 它管不到实现方有没有把同一份奖励发两遍。
     *
     * <p>真实现是 {@link com.ironoath.web.pay.ProductFulfilment}（B19）；
     * B15 那个"什么都不发"的桩已经删掉了。
     */
    public interface ProductFulfiller {

        /**
         * @param orderId 本笔订单号。<b>幂等键就是它</b>（B19 §一.1 统一纪律第一条）：
         *                实现方要靠它挡住"同一笔订单发两遍"，而 productId 挡不住合法的月卡续期
         * @param line    订单商品行（含玩家下单时挑的武将）
         * @param now     服务端当前时刻。延期类权益必须由它起算，实现方自己读墙上时钟
         *                就等于绕开铁律 5，也让用例无法把时间钉在日界上
         * @return 实际发出的奖励；返回空列表或 false 都表示没发出去
         */
        Result deliver(String playerId, String orderId, PayOrder.Line line, long now);

        /**
         * 发货结果。
         *
         * <p>{@code rewards} 用的是领域类型 {@code RewardItem} 而不是协议 DTO：实现方回答的是
         * "我发了什么"，协议形状是 {@code OrderStatusResp} 出口那一次转换的事。两头各转一次，
         * 就会出现"订单里存的"与"下发给客户端的"不是同一份。
         */
        record Result(boolean delivered, List<com.ironoath.core.reward.RewardItem> rewards,
                      String reason) {
            public Result {
                rewards = rewards == null ? List.of() : List.copyOf(rewards);
            }

            public static Result failure(String reason) {
                return new Result(false, List.of(), reason);
            }
        }
    }

    /**
     * 订单存储。类型是端口 {@link com.ironoath.core.pay.PayOrderStore} 而不是内存登记簿本身：
     * 于是"每次状态迁移都必须写回"是本类的义务，不是某个实现的红利 ——
     * 内存实现里 {@code get} 返回的就是活对象、不写回也看得见，换到任何真存储就会整笔丢，
     * 而丢的恰好是"钱收了货没发"那笔负债。
     */
    private final com.ironoath.core.pay.PayOrderStore orders;
    private final SignatureVerifier verifier;
    private final ProductFulfiller fulfiller;
    private final ConfigRegistry configs;
    private final PlayerLock playerLock;
    private final IdempotencyStore idempotency;
    private final TimeService timeService;
    /**
     * 部署参数（凭据类）。<b>刻意不走配置表</b>：contract/config 会进版本库，
     * 而 offerId 与商户密钥同属一类凭据，写进版本库等于把它交给任何能看到仓库的人。
     * 它们来自 Spring Environment（application-*.yml + IRONOATH_* 环境变量），
     * 与 B16 上线清单 §八 1「生产配置全部来自环境变量」是同一条纪律。
     */
    private final org.springframework.core.env.Environment environment;
    private final com.ironoath.core.pay.PopupThrottle throttle;
    private final com.ironoath.web.pay.MinorPaymentPolicy minorPolicy;
    /** 两张付费表（商品结构 + 发货内容）唯一的读处。价格也从这里取，不再有一份 switch 副本。 */
    private final com.ironoath.web.pay.PaidProducts catalog;
    /** 判"这一档本账号是否已经用掉"要读权益位（下单处拦，别等玩家付了第二笔钱再在发货处拦）。 */
    private final com.ironoath.core.player.PlayerRepository players;

    public PayAppService(com.ironoath.core.pay.PayOrderStore orders, SignatureVerifier verifier,
                         ProductFulfiller fulfiller,
                         ConfigRegistry configs, PlayerLock playerLock, IdempotencyStore idempotency,
                         TimeService timeService, org.springframework.core.env.Environment environment,
                         com.ironoath.core.pay.PopupThrottle throttle,
                         com.ironoath.web.pay.MinorPaymentPolicy minorPolicy,
                         com.ironoath.web.pay.PaidProducts catalog,
                         com.ironoath.core.player.PlayerRepository players) {
        this.orders = orders;
        this.verifier = verifier;
        this.fulfiller = fulfiller;
        this.configs = configs;
        this.playerLock = playerLock;
        this.idempotency = idempotency;
        this.timeService = timeService;
        this.environment = environment;
        this.throttle = throttle;
        this.minorPolicy = minorPolicy;
        this.catalog = catalog;
        this.players = players;
        if (!verifier.productionReady()) {
            LOG.warn("支付验签使用的是非正式实现：任何回调都会被判定为可信。"
                    + "上线前必须替换成米大师验签（B15 红线：无绕支付、回调必须验签）");
        }
        if (minorPolicy == com.ironoath.web.pay.MinorPaymentPolicy.UNKNOWN) {
            LOG.warn("未成年付费限额当前一律不生效：没有实名认证，拿不到年龄。"
                    + "判定函数与月度账本都已接好（下单处每次都会问一次），"
                    + "换上真实 MinorPaymentPolicy 实现即生效 —— 这条 WARN 是合规项的检查点，"
                    + "上线前必须能看到它消失（B15 §3、上线检查清单 §二 4）");
        }
    }

    // ---------- 价格表 ----------

    /**
     * 在售商品与价格（B15 §2：价格读服务端下发的价格表）。
     *
     * <p><b>商品清单来自 {@code pay_product} 表，价格来自表里指名的 global 参数</b>
     * （{@code priceCentsParam} 是指针不是副本）。B19 之前这里是一份硬编码的
     * productId→参数名 switch，与表里那一列是同一个映射的两个家 —— 加一个商品要改两处，
     * 漏改的那一处表现为"表里下架了、价格表还在下发"。
     */
    public PricesResp prices() {
        String currency = currency();
        List<ProductPrice> products = new ArrayList<>();
        for (PaidProducts.PriceEntry entry : catalog.onSale()) {
            products.add(new ProductPrice(entry.productId(), entry.cents(), currency, null));
        }
        return new PricesResp(List.copyOf(products), region());
    }

    // ---------- 下单 ----------

    /** 下单：生成订单号与调起支付所需的参数。<b>价格由服务端查表，客户端传的一律忽略</b>。 */
    public CreateOrderResp createOrder(String playerId, CreateOrderReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                // 先扫过期再造新单：下单是唯一会让订单表变大的入口，
                // 不在这里扫，「下了单没付」的记录就只增不减（PAY_ORDER_TTL_HOURS 的 why 就是这条）
                expireUnpaidOrders(now);
                PayProductCfg product = requireBuyable(playerId, req);
                // 礼包的两道闸在**下单时**判（工单 6.1 S3-iii）：拦在发货就晚了 —— 玩家已经付过钱，
                // 而"已付不退"是一条绝对纪律（§四 禁止项）。
                requireGiftSellable(playerId, product, now);
                long cents = catalog.priceCents(product);
                String orderId = "order_" + playerId + "_" + now + "_"
                        + Long.toHexString(req.requestId().hashCode());
                PayOrder.Line line = new PayOrder.Line(product.id(), Math.max(1, req.count()),
                        cents, now, req.heroChoice());
                PayOrder existing = orders.get(orderId);
                if (existing != null) {
                    // 幂等：同一个 requestId 重放时返回同一个订单，而不是造出第二笔。
                    // 造两笔的后果是玩家只付其中一笔的钱，另一笔永久停在 PENDING，
                    // 最后在补单队列里变成一条谁也说不清的记录
                    return new CreateOrderResp(orderId, payParams(line));
                }
                PayOrder order = PayOrder.create(orderId, playerId, line);
                requireWithinMinorLimit(playerId, line.totalCents(), now);
                orders.insert(order);
                recordGiftPurchaseIfAny(playerId, product, now);
                LOG.info("下单 orderId={} playerId={} 商品={} 份数={} 金额={}分 选将={}",
                        orderId, playerId, line.productId(), line.count(), line.totalCents(),
                        line.heroChoice());
                return new CreateOrderResp(orderId, payParams(line));
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 未成年付费限额（B15 §3、验收 4）。**在 {@code orders.insert} 之前判** ——
     * 拦下了还留一条 PENDING 订单的话，对账与"僵尸单清理"都会把它当真实交易看，
     * 而它从头到尾没被允许发生过。
     *
     * <p>三态里只有"不知道"和"成年"是不拦，两者都必须能被查出区别：
     * 前者每次记 WARN（合规项没生效不是一条安静的默认值），后者什么都不记。
     *
     * <p>{@code PAY_MINOR_LIMIT} 的 {@code msg} 是给玩家看的那句话（prod 不下发 {@code detail}，
     * 把可执行的提示写进 msg 才能让玩家知道下一步做什么）；具体金额进 {@code detail} 供排查与客服。
     */
    /**
     * 礼包的两道下单闸（S3-iii）：**报价没过期** 且 **今天没买满**。
     *
     * <p><b>为什么读的是"弹窗那一位"而不是订单表</b>：一天的事实写在本来就要读的这一位上，
     * 比给订单表新开一条按 productId 的索引便宜，也少一次跨集合查询（工单里的优先项）。
     *
     * <p><b>为什么在 orders.insert 之前判</b>：与 {@link #requireWithinMinorLimit} 同一条理由 ——
     * 拦下了却留一条 PENDING 订单，对账与僵尸单清理都会把它当真实交易看。
     */
    private void requireGiftSellable(String playerId, PayProductCfg product, long now) {
        if (product.kind() != PayProductCfg.Kind.GIFT) {
            return;
        }
        GiftCfg gift = giftOf(product);
        if (gift == null) {
            // GIFT 档却不在 gift 表里 = 这一期没有在推它：按"暂不可购买"回，而不是编一个礼包
            throw new BizException(ErrorCode.PAY_PRODUCT_OFFLINE,
                    "这一档礼包当前不在推送列表里：productId=" + product.id());
        }
        PlayerGiftPopup state = giftStateOf(playerId);
        long triggerAt = state.triggeredAtOf(gift.trigger().name());
        long ttlMillis = gift.offerTtlMinutes() * 60_000L;
        if (triggerAt <= 0L || now - triggerAt >= ttlMillis) {
            throw new BizException(ErrorCode.PAY_GIFT_OFFER_EXPIRED,
                    "触发时刻=" + triggerAt + " 有效期(分)=" + gift.offerTtlMinutes() + " 现在=" + now);
        }
        long boughtToday = state.purchasedTodayOf(gift.id(), com.ironoath.common.time.DayKey.of(now));
        if (boughtToday >= gift.limitCount()) {
            throw new BizException(ErrorCode.PAY_GIFT_DAILY_LIMIT,
                    "今日已购=" + boughtToday + " 上限=" + gift.limitCount() + " 礼包=" + gift.id());
        }
    }

    private GiftCfg giftOf(PayProductCfg product) {
        for (GiftCfg row : configs.all(GiftCfg.class)) {
            if (row.productId().equals(product.id())) {
                return row;
            }
        }
        return null;
    }

    private PlayerGiftPopup giftStateOf(String playerId) {
        return players.findByPlayerId(playerId)
                .orElseThrow(() -> new BizException(ErrorCode.PLAYER_NOT_FOUND, "玩家不存在：" + playerId))
                .giftPopup();
    }

    /**
     * 下单成功后记一次购买（**记在下单这一刻，不记在发货**）。
     *
     * <p>不记的后果很具体：玩家可以在同一分钟内连下两单、都付款成功 ——
     * 限购在下一单的下单时才发现已经买过，而钱已经收了。记在下单侧的代价是
     * "下单没付"也会占掉当日额度（订单 TTL 2 小时），这一侧是保守的、可解释的。
     */
    private void recordGiftPurchaseIfAny(String playerId, PayProductCfg product, long now) {
        if (product.kind() != PayProductCfg.Kind.GIFT) {
            return;
        }
        GiftCfg gift = giftOf(product);
        if (gift == null) {
            return;
        }
        PlayerSave save = players.findByPlayerId(playerId)
                .orElseThrow(() -> new BizException(ErrorCode.PLAYER_NOT_FOUND, "玩家不存在：" + playerId));
        save.setGiftPopup(save.giftPopup().withPurchased(gift.id(), com.ironoath.common.time.DayKey.of(now)));
        players.save(save);
    }

    private void requireWithinMinorLimit(String playerId, long amountCents, long now) {
        Boolean minor = minorPolicy.minorFlagOf(playerId);
        if (minor == null) {
            LOG.warn("未成年付费限额未生效：账号 {} 拿不到年龄（实名认证未接入），本次 {}分 按成年人放行。"
                    + "B15 §3 是提审必查项，换掉 MinorPaymentPolicy 即生效", playerId, amountCents);
            return;
        }
        if (!minor) {
            return;
        }
        long single = configs.longParam("MINOR_PAY_SINGLE_LIMIT_CENTS");
        long monthly = configs.longParam("MINOR_PAY_MONTHLY_LIMIT_CENTS");
        long spent = orders.paidCentsSince(playerId, com.ironoath.common.time.MonthKey.startMillis(now));
        var verdict = throttle.minorPayNotice(single, monthly, spent, amountCents);
        if (verdict.withinLimit()) {
            if (verdict.notice() != null) {
                LOG.info("未成年付费额度提示 playerId={} 本月已花={}分 剩余={}分",
                        playerId, spent, verdict.remainingCents());
            }
            return;
        }
        LOG.warn("拒绝未成年付费 playerId={} 本次={}分 本月已花={}分 单笔上限={}分 本月上限={}分",
                playerId, amountCents, spent, single, monthly);
        throw new BizException(ErrorCode.PAY_MINOR_LIMIT,
                verdict.notice() + "（本月已花 " + spent + "分，剩 " + verdict.remainingCents() + "分）");
    }

    // ---------- 回调（渠道服务器调用，不校验玩家身份） ----------

    /**
     * 支付回调。<b>验签 → 改状态 → 落库，然后才发货</b>。
     *
     * <p>重复回调只发货一次（验收 2）：{@code PayOrder.confirmCallback} 用订单状态做幂等，
     * 第二次及以后的回调返回 {@code firstTime=false}，本方法据此跳过发货。
     */
    public OrderStatusResp callback(PayCallbackReq req) {
        if (req == null || isBlank(req.orderId())) {
            throw new BizException(ErrorCode.PAY_ORDER_DUPLICATE, "回调缺少 orderId");
        }
        long now = timeService.serverNow();
        PayOrder order = orders.get(req.orderId());
        if (order == null) {
            // 不存在的订单号：可能是伪造的回调，也可能是我们先丢了订单。
            // 两种情况都不能发货，但都要留下痕迹
            LOG.error("收到未知订单的支付回调 orderId={} transactionId={}：不发货，需人工核查",
                    req.orderId(), req.transactionId());
            throw new BizException(ErrorCode.PAY_ORDER_DUPLICATE, "订单不存在: " + req.orderId());
        }
        if (!verifier.verify(req, order)) {
            LOG.error("支付回调验签失败 orderId={} playerId={}：已拒绝，这可能是伪造回调",
                    req.orderId(), order.playerId());
            throw new BizException(ErrorCode.PAY_SIGN_INVALID, "签名校验失败");
        }
        PayOrder.CallbackOutcome outcome = order.confirmCallback(req.transactionId(),
                req.success() == null || req.success(), now);
        // 写回是义务，不是可选项：confirmCallback 会改状态、交易号与 paidAt，也会让 callbackCount
        // 前进（连"重复回调被忽略"这条路径都要记次数，那是渠道在超时重试的唯一信号）。
        // 内存登记簿里 get() 返回的就是活对象，漏掉这一次 save 也看不出问题；
        // 换到任何真存储就是整笔丢失，而丢的可能是"钱收了货没发"那笔负债。
        // （收口清单 #22 记的三处就地改写，至此三处都改成了显式写回。）
        orders.save(order);
        if (outcome.reopened()) {
            // 必须吵：这一单我们本来已经按「没收到钱」关闭了，是渠道说钱到了。
            // 偶发是正常业务（取消后重付、弱网下迟到的回调），但成片出现说明
            // PAY_ORDER_TTL_HOURS 短于一笔支付实际可能延迟多久 —— 那玩家就该看到付了钱没货
            LOG.warn("已作废的订单收到确认收款回调，重新发货 orderId={} playerId={} 第{}次回调（{}）",
                    req.orderId(), order.playerId(), order.callbackCount(), outcome.reason());
        }
        if (!outcome.firstTime()) {
            LOG.info("重复回调，已忽略 orderId={} 第{}次回调 状态={}（{}）",
                    req.orderId(), order.callbackCount(), outcome.statusAfter(), outcome.reason());
            return statusOf(order, List.of());
        }
        var rewards = deliver(order, now);
        return statusOf(order, rewards);
    }

    // ---------- 补单 ----------

    /**
     * 手工触发一次补单（客服入口用）。
     *
     * <p><b>补单必须存在</b>：支付成功但发货失败时，钱已经收了。
     * 没有补单队列的话，这类订单会停在「已付款、未发货」，
     * 而玩家的体验是「我付了钱什么都没拿到」—— 那是退款投诉与差评的主要来源。
     */
    public OrderStatusResp retry(String playerId, PayRetryReq req) {
        long now = timeService.serverNow();
        acquire(req == null ? null : req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                PayOrder order = requireOwnOrder(playerId, req.orderId());
                if (!order.needsRetry()) {
                    throw new BizException(ErrorCode.PAY_ORDER_DUPLICATE,
                            "订单 " + req.orderId() + " 不在补单队列里（状态=" + order.status() + "）");
                }
                int maxAttempts = (int) configs.longParam("PAY_FULFILL_MAX_ATTEMPTS");
                var rewards = deliverWithRetry(order, maxAttempts, now);
                return statusOf(order, rewards);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /** 订单状态查询。<b>客户端在支付返回后必须轮询它</b>，不能凭客户端回调直接显示已到账。 */
    public OrderStatusResp status(String playerId, String orderId) {
        // 未付的单子必须在这里变成 FAILED：轮询这个端点的人就是等着一个答复的人，
        // 让「下了单没付」永远显示 PENDING，客户端就只能显示一个转圈的进度条
        expireUnpaidOrders(timeService.serverNow());
        PayOrder order = requireOwnOrder(playerId, orderId);
        return statusOf(order, List.of());
    }

    /** 补单队列（运维/客服用）。 */
    public List<PayOrder> retryQueue(int limit) {
        return orders.retryQueue(limit);
    }

    /**
     * 负债读数（只读，供 {@code GET /ops/pay/debt} 用）：钱收了、货没发出去的那批订单。
     *
     * <p><b>这个方法存在的理由是把两个零调用点变成有调用点</b>：{@code unfulfilledCents()} 与
     * {@link #retryQueue(int)} 此前只有测试在读，而 #27 那条 ERROR 日志写的是「必须有人跟进」——
     * 日志喊了但没人能查账，等于没有账。
     *
     * @param limit 明细最多带几笔。<b>受 {@link #DEBT_LIST_MAX} 夹住</b>：这是一条只读但挂在
     *              不需要玩家身份的 /ops/ 前缀下的路径，把 limit 原样透传给存储层等于任何人都能
     *              要求服务端把整张订单表捞一遍。总额与笔数不受 limit 影响 ——
     *              所以「没列全」这件事在响应里是看得见的（{@code listed < unfulfilledOrders}）。
     */
    public PayDebtResp debt(int limit) {
        int capped = Math.max(1, Math.min(limit, DEBT_LIST_MAX));
        List<PayOrder> queue = orders.retryQueue(capped);
        List<DebtOrderView> views = new ArrayList<>(queue.size());
        for (PayOrder order : queue) {
            views.add(new DebtOrderView(order.orderId(), order.playerId(), order.line().totalCents(),
                    order.paidAt(), order.fulfillAttempts(), order.failureReason()));
        }
        return new PayDebtResp(orders.unfulfilledCents(), orders.unfulfilledOrderCount(),
                views.size(), views);
    }

    // ---------- 内部 ----------

    /**
     * 未支付订单超时作废（global.PAY_ORDER_TTL_HOURS）。惰性调用，不跑定时器（B00 陷阱 2）。
     *
     * <p><b>为什么是全局扫而不是只扫本人</b>：这条清扫存在的目的是给订单表封顶，
     * 只清自己那几单的表仍然会无限长大。跨玩家只是把「按时间应有的状态迁移」补上，
     * 不读也不写任何玩家的业务数据，与战报清理 {@code purgeExpired} 同一口径。
     *
     * <p><b>作废不等于删除，更不影响发货</b>：已确认收款的订单（PAID_UNFULFILLED / SUCCESS）
     * 永远不在作废范围内 —— 那是一笔负债，不是一条僵尸记录。
     * 而作废之后仍然允许一条验签通过的成功回调把它重开并发货，
     * 所以这个方法的调用时机不会让玩家付了钱拿不到货（见 {@link #callback}）。
     */
    private void expireUnpaidOrders(long now) {
        long ttlHours = configs.longParam("PAY_ORDER_TTL_HOURS");
        int expired = orders.expireUnpaid(ttlHours * 3_600_000L, now);
        if (expired > 0) {
            LOG.info("未支付订单超时作废 数量={} 时长={}小时 截止={}", expired, ttlHours, now);
        }
    }

    private List<com.ironoath.core.reward.RewardItem> deliver(PayOrder order, long now) {
        int maxAttempts = (int) configs.longParam("PAY_FULFILL_MAX_ATTEMPTS");
        return deliverWithRetry(order, maxAttempts, now);
    }

    private List<com.ironoath.core.reward.RewardItem> deliverWithRetry(PayOrder order, int maxAttempts,
                                                                       long now) {
        // 发货清单已经记在订单上 ⇒ 这一单发过了，回放而不是再发一遍。
        // 挡住的是"渠道重发回调 + 我们已发货但状态没落回去"这一段：状态机在这里管不到发奖次数
        if (!order.rewards().isEmpty()) {
            LOG.info("订单已有发货清单，回放不再重复发货 orderId={} playerId={} 项数={}",
                    order.orderId(), order.playerId(), order.rewards().size());
            return PaidProducts.fromProtocolRows(order.rewards());
        }
        final List<com.ironoath.core.reward.RewardItem> delivered = new ArrayList<>();
        PayOrder.FulfillOutcome outcome = order.fulfill(() -> {
            // 玩家锁：发货要写玩家存档（权益位、钱包、背包），而回调路径上没有别的锁。
            // 拿不到锁时 LockTimeoutException 会一路冒到 PayOrder.fulfill 的捕获里，
            // 结果是"这次没发出去、留在补单队列"—— 正确的方向：宁可晚发，不可并发覆盖
            ProductFulfiller.Result result = playerLock.runLocked(order.playerId(), LOCK_TIMEOUT_MS,
                    () -> fulfiller.deliver(order.playerId(), order.orderId(), order.line(), now));
            if (result.delivered()) {
                delivered.addAll(result.rewards());
                return true;
            }
            LOG.warn("发货失败 orderId={} playerId={} 商品={} 原因={}：订单进补单队列",
                    order.orderId(), order.playerId(), order.line().productId(), result.reason());
            return false;
        }, maxAttempts, now);
        if (outcome.delivered()) {
            // 记下这一单发了什么：查单端点据此回放，客服据此对账（B19 §一.1 最后一条纪律）
            order.recordRewards(PaidProducts.toOrderRows(delivered));
        }
        // fulfill 改的是 status / fulfilledAt / fulfillAttempts / failureReason，无论成功还是失败
        // 都必须写回：成功不落会被重复发货，失败不落这笔负债就从补单队列里消失（玩家付了钱没拿到货，
        // 而系统里没有任何一处记得它）
        orders.save(order);
        if (outcome.queuedForRetry()) {
            LOG.error("订单已付款但发货失败，进入补单队列 orderId={} playerId={} 金额={}分 原因={}。"
                            + "这笔钱已经收了，必须有人跟进（客服入口见 B15 §三）",
                    order.orderId(), order.playerId(), order.line().totalCents(), outcome.reason());
        }
        return List.copyOf(delivered);
    }

    /**
     * 组装订单状态响应。
     *
     * <p>{@code justDelivered} 为空时回落到订单上记着的发货清单：查单（{@link #status}）与
     * 重复回调走的都是这条路 —— 玩家轮询到的「我拿到了什么」必须和他第一次看到的一致。
     */
    private OrderStatusResp statusOf(PayOrder order, List<com.ironoath.core.reward.RewardItem> justDelivered) {
        List<PayRewardItem> rewards = justDelivered.isEmpty()
                ? PaidProducts.toProtocol(PaidProducts.fromProtocolRows(order.rewards()))
                : PaidProducts.toProtocol(justDelivered);
        return new OrderStatusResp(toStatus(order.status()), rewards, order.needsRetry());
    }

    /**
     * 领域的五个状态 → 协议的三个状态。
     *
     * <p><b>这是一处真实的口径收窄，不是偷懒</b>：{@code PayOrder.Status} 有
     * PENDING / SUCCESS / PAID_UNFULFILLED / CANCELLED / FAILED 五个，
     * 而 B15 §二 的协议只给了三个。收窄的规则是「客户端需要据此做什么」：
     * <ul>
     *   <li>{@code PAID_UNFULFILLED} → SUCCESS + {@code retryQueued=true}。
     *       钱已经收了，所以对玩家而言这是「已购买」；货还没到这件事由 retryQueued 表达，
     *       客户端据此显示「发货处理中」并给出客服入口。<b>绝不能映射成 FAILED</b> ——
     *       那会让玩家以为自己没付成功而再付一次，或者发起一笔本不该发生的退款</li>
     *   <li>{@code CANCELLED} → FAILED。玩家取消与支付失败对客户端是同一件事：不要发货、可以重新下单</li>
     * </ul>
     *
     * <p><b>default 分支必须抛</b>：领域层将来新增一个状态时，
     * 静默映射到某个既有取值会让客户端做出错误的动作（例如把一个退款中的订单显示成已购买），
     * 而当场抛出的错误信息会直接点名是哪个状态没跟上。
     */
    private static OrderStatus toStatus(PayOrder.Status status) {
        return switch (status) {
            case PENDING -> OrderStatus.PENDING;
            case SUCCESS, PAID_UNFULFILLED -> OrderStatus.SUCCESS;
            case CANCELLED, FAILED -> OrderStatus.FAILED;
        };
    }

    private PayOrder requireOwnOrder(String playerId, String orderId) {
        if (isBlank(orderId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "orderId 不得为空");
        }
        PayOrder order = orders.get(orderId);
        // 别人的订单一律回「不存在」，与战报、行军同一条口径：
        // 回「不属于你」等于给了一个探测订单号的接口，而订单号里含玩家 id 与时间戳
        if (order == null || !order.playerId().equals(playerId)) {
            throw new BizException(ErrorCode.PAY_ORDER_DUPLICATE, "订单不存在: " + orderId);
        }
        return order;
    }

    /**
     * 下单前的三道判定：<b>商品存在 → 选将合法 → 本账号还有资格买它</b>，全在
     * {@code orders.insert} 之前完成（与 {@link #requireWithinMinorLimit} 同一条理由：
     * 拦下了还留一条 PENDING 订单，对账与僵尸单清理都会把它当真实交易看）。
     *
     * <p><b>选将的两个方向都要判</b>：该挑没挑 ⇒ 发货时只能替玩家挑一个（不允许）；
     * 不该挑却带了值 ⇒ 说明客户端拿的是旧表或别的商品，静默忽略会让这个错一路带到发货。
     */
    private PayProductCfg requireBuyable(String playerId, CreateOrderReq req) {
        PayProductCfg product = catalog.require(req.productId());
        List<String> candidates = catalog.heroChoices(product);
        boolean picked = req.heroChoice() != null && !req.heroChoice().isBlank();
        if (candidates.isEmpty()) {
            if (picked) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "商品 " + product.id() + " 的发货内容里没有可挑的武将，不该带 heroChoice="
                                + req.heroChoice());
            }
        } else if (!picked) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "商品 " + product.id() + " 需要从候选里挑一名武将（heroChoice），候选=" + candidates
                            + "。不替玩家默认挑：那一选不是他做的，事后只会变成一张退款工单");
        } else if (!candidates.contains(req.heroChoice())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "heroChoice=" + req.heroChoice() + " 不在商品 " + product.id()
                            + " 的候选里，候选=" + candidates);
        }
        // 读不到存档就当"本账号没有已购记录"：没有存档就没有可依据的权益位，
        // 而这条判定的目的（别收第二次钱）由发货处的第二道同样挡得住 ——
        // 更要紧的是，发货时会因为"存档不存在"直接失败进补单队列，那才是这道题的响亮答案
        PlayerPaid paid = players.findByPlayerId(playerId).map(PlayerSave::paid).orElse(null);
        String alreadyOwned = catalog.alreadyOwned(paid, product);
        if (alreadyOwned != null) {
            throw new BizException(ErrorCode.PAY_NOT_ENTITLED,
                    "商品 " + product.id() + "：" + alreadyOwned);
        }
        return product;
    }

    /**
     * 调起米大师所需的参数。
     *
     * <p><b>env 尤其不能由客户端写死</b>：一次忘了改的提交就会让正式包指向沙箱，
     * 而沙箱支付在正式环境是收不到钱的 —— 玩家付了钱、渠道说成功、我们账上没有这笔。
     * 所以它由服务端按部署环境下发。
     *
     * <p><b>offerId 缺失时不静默用空串</b>：空串会让客户端调起一个必然失败的支付，
     * 玩家看到的是「点了没反应」。当场报错虽然难看，但它指向的是配置缺失这个真问题。
     */
    private PayParams payParams(PayOrder.Line line) {
        String offerId = environment.getProperty("ironoath.pay.offer-id", "");
        if (offerId.isBlank()) {
            throw new BizException(ErrorCode.PAY_PRODUCT_OFFLINE,
                    "支付未配置：缺少部署参数 ironoath.pay.offer-id（米大师应用 id）。"
                            + "这是部署凭据，只能来自环境变量，不在配置表里");
        }
        return new PayParams(
                environment.getProperty("ironoath.pay.mode", "game"),
                offerId,
                String.valueOf(line.totalCents()),
                environment.getProperty("ironoath.pay.env", "1"),
                currency(),
                null);
    }

    private String currency() {
        return configs.hasParam("PAY_CURRENCY") ? configs.stringParam("PAY_CURRENCY") : "CNY";
    }

    private String region() {
        return configs.hasParam("PAY_REGION") ? configs.stringParam("PAY_REGION") : "CN";
    }

    private void acquire(String requestId, long now) {
        if (isBlank(requestId)) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "支付域的写操作必须带 requestId");
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
