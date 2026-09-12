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
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.pay.PayOrder;
import com.ironoath.web.dto.generated.CreateOrderReq;
import com.ironoath.web.dto.generated.CreateOrderResp;
import com.ironoath.web.dto.generated.OrderStatus;
import com.ironoath.web.dto.generated.OrderStatusResp;
import com.ironoath.web.dto.generated.PayCallbackReq;
import com.ironoath.web.dto.generated.PayParams;
import com.ironoath.web.dto.generated.PayRetryReq;
import com.ironoath.web.dto.generated.PayRewardItem;
import com.ironoath.web.dto.generated.PricesResp;
import com.ironoath.web.dto.generated.ProductPrice;

/**
 * 职责：B15 商业化域应用服务 —— 价格表、下单、支付回调、补单、订单状态。
 * 依赖：{@link com.ironoath.core.pay.PayOrderStore}（订单存储，内存与 Mongo 两个实现）、
 * {@link SignatureVerifier}、{@link ProductFulfiller}、配置表。
 *
 * <p><b>本类负责的是「订单的机械正确性」，不负责「商品发了什么」</b>：
 * 后者由 {@link ProductFulfiller} 承担，而 B15 §一 的三类主力商品
 * （特权卡每日领取、成长基金分批返还、首充双倍）各自是一套系统，不是一次奖励发放。
 * 所以当前默认实现<b>什么都不发</b>并把订单推进补单队列 ——
 * 这是刻意的失败方向：钱收到了、货没发出去，订单留在补单队列里并且状态可查（{@code retryQueued=true}），
 * 客服能看见、玩家能看到「发货处理中」。反过来（先假装发货成功）会让一笔钱在账面上消失。
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
     */
    public interface ProductFulfiller {

        /**
         * @return 实际发出的奖励；返回空列表或 false 都表示没发出去
         */
        Result deliver(String playerId, PayOrder.Line line);

        /** 发货结果。 */
        record Result(boolean delivered, List<PayRewardItem> rewards, String reason) {
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

    public PayAppService(com.ironoath.core.pay.PayOrderStore orders, SignatureVerifier verifier,
                         ProductFulfiller fulfiller,
                         ConfigRegistry configs, PlayerLock playerLock, IdempotencyStore idempotency,
                         TimeService timeService, org.springframework.core.env.Environment environment,
                         com.ironoath.core.pay.PopupThrottle throttle,
                         com.ironoath.web.pay.MinorPaymentPolicy minorPolicy) {
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
     * <p><b>商品清单与价格的映射写在这里而不是配置表的一行里</b>：
     * 价格是配置（PRODUCT_*_CENTS），但「哪个 productId 对应哪个价格参数」是代码结构 ——
     * 新增一个商品本来就要为它写发货逻辑，所以它不可能只靠改配置表上线。
     */
    public PricesResp prices() {
        List<ProductPrice> products = new ArrayList<>();
        products.add(new ProductPrice("monthly_card", configs.longParam("PRODUCT_MONTHLY_CARD_CENTS"),
                currency(), null));
        products.add(new ProductPrice("growth_fund", configs.longParam("PRODUCT_GROWTH_FUND_CENTS"),
                currency(), null));
        products.add(new ProductPrice("first_charge", configs.longParam("PRODUCT_FIRST_CHARGE_CENTS"),
                currency(), null));
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
                long cents = priceOf(req.productId());
                String orderId = "order_" + playerId + "_" + now + "_"
                        + Long.toHexString(req.requestId().hashCode());
                PayOrder.Line line = new PayOrder.Line(req.productId(), Math.max(1, req.count()), cents, now);
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
                LOG.info("下单 orderId={} playerId={} 商品={} 份数={} 金额={}分",
                        orderId, playerId, line.productId(), line.count(), line.totalCents());
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
        List<PayRewardItem> rewards = deliver(order, now);
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
                List<PayRewardItem> rewards = deliverWithRetry(order, maxAttempts, now);
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

    private List<PayRewardItem> deliver(PayOrder order, long now) {
        int maxAttempts = (int) configs.longParam("PAY_FULFILL_MAX_ATTEMPTS");
        return deliverWithRetry(order, maxAttempts, now);
    }

    private List<PayRewardItem> deliverWithRetry(PayOrder order, int maxAttempts, long now) {
        final List<PayRewardItem> delivered = new ArrayList<>();
        PayOrder.FulfillOutcome outcome = order.fulfill(() -> {
            ProductFulfiller.Result result = fulfiller.deliver(order.playerId(), order.line());
            if (result.delivered()) {
                delivered.addAll(result.rewards());
                return true;
            }
            LOG.warn("发货失败 orderId={} playerId={} 商品={} 原因={}：订单进补单队列",
                    order.orderId(), order.playerId(), order.line().productId(), result.reason());
            return false;
        }, maxAttempts, now);
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

    private OrderStatusResp statusOf(PayOrder order, List<PayRewardItem> rewards) {
        return new OrderStatusResp(toStatus(order.status()), List.copyOf(rewards), order.needsRetry());
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

    /** 商品单价（分）。查不到就是下架或未定义 —— 两种情况都不允许下单。 */
    private long priceOf(String productId) {
        if (isBlank(productId)) {
            throw new BizException(ErrorCode.PAY_PRODUCT_OFFLINE, "productId 不得为空");
        }
        String param = switch (productId) {
            case "monthly_card" -> "PRODUCT_MONTHLY_CARD_CENTS";
            case "growth_fund" -> "PRODUCT_GROWTH_FUND_CENTS";
            case "first_charge" -> "PRODUCT_FIRST_CHARGE_CENTS";
            default -> null;
        };
        if (param == null || !configs.hasParam(param)) {
            throw new BizException(ErrorCode.PAY_PRODUCT_OFFLINE, "商品已下架或不存在: " + productId);
        }
        return configs.longParam(param);
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
