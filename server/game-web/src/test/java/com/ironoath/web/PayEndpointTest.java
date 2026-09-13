package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.pay.PayOrder;
import com.ironoath.web.dto.generated.CreateOrderReq;
import com.ironoath.web.dto.generated.OrderStatus;
import com.ironoath.web.dto.generated.OrderStatusResp;
import com.ironoath.web.dto.generated.PayCallbackReq;
import com.ironoath.web.dto.generated.PayRetryReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PayAppService;
import com.ironoath.web.service.PlayerInitService;

/**
 * 职责：B15 支付域的端到端验证 —— 价格表同源、回调幂等（验收 2）、补单队列（验收 3）、验签、订单归属。
 * 依赖：Spring Boot Test + MockMvc；test profile（内存订单登记簿 + 本地验签实现）。
 *
 * <p><b>本类最重要的一条是「重复回调 3 次只发货一次」</b>（B15 验收 2）。
 * 微信在收不到及时响应时会重试，所以重复回调是常态而不是异常；
 * 而回调不幂等的后果是同一笔钱发三份货 —— 那是能直接亏穿一个服的经济漏洞，
 * 且它在功能测试里完全看不出来（手工点一次支付只会收到一次回调）。
 *
 * <p><b>发货本身在本 profile 下永远失败</b>（{@code PayBeansConfig} 的默认实现什么都不发，
 * 把订单推进补单队列），所以这里的断言落在<b>尝试次数</b>而不是「发出了什么」上：
 * 「只发货一次」的可观测形式就是 {@code fulfillAttempts == 1}。
 * 这不是绕过验证 —— 恰恰相反，发货失败正是补单队列（验收 3）需要的那种输入。
 *
 * <p>过期那一组测试没有走 HTTP：<b>没有任何端点能把服务端时钟拨快 25 小时</b>，
 * 而「未支付订单超时作废」这条规则只有跨过时间才看得见，所以它们直接构造服务实例并注入可控时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PayEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final long MINUTE = 60_000L;

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private ConfigRegistry configs;
    @Autowired private PayOrder.Registry orders;
    @Autowired private PayAppService.SignatureVerifier verifier;
    @Autowired private PayAppService.ProductFulfiller fulfiller;
    @Autowired private PlayerLock playerLock;
    @Autowired private IdempotencyStore idempotency;
    @Autowired private Environment environment;

    // ---------- 价格表 ----------

    @Test
    @DisplayName("价格表由服务端下发且与配置表同源：客户端内置价格的话，调价必须发版")
    void pricesComeFromTheConfigTable() throws Exception {
        JsonNode data = okData(perform(get("/pay/prices")));

        assertThat(data.get("region").asText()).isNotBlank();
        JsonNode products = data.get("products");
        assertThat(products.size()).as("三类主力商品").isEqualTo(3);
        for (JsonNode product : products) {
            String id = product.get("productId").asText();
            long expected = switch (id) {
                case "monthly_card" -> configs.longParam("PRODUCT_MONTHLY_CARD_CENTS");
                case "growth_fund" -> configs.longParam("PRODUCT_GROWTH_FUND_CENTS");
                case "first_charge" -> configs.longParam("PRODUCT_FIRST_CHARGE_CENTS");
                default -> throw new AssertionError("未预期的商品 id: " + id);
            };
            assertThat(product.get("cents").asLong())
                    .as("%s 的价格必须来自 global 表（单位是分，不是元）", id).isEqualTo(expected);
            assertThat(product.get("currency").asText()).isNotBlank();
            JsonNode original = product.get("originalCents");
            assertThat(original == null || original.isNull())
                    .as("没有折扣时不得下发划线价：显示一个等于现价的划线价是价格欺诈的常见形态。"
                            + "序列化可能直接省略 null 字段，所以「缺失」与「JSON null」都算合格")
                    .isTrue();
        }
    }

    // ---------- 下单 ----------

    @Test
    @DisplayName("下单：金额由服务端按 productId 查表，客户端传什么都改不了它")
    void orderAmountIsDecidedByTheServer() throws Exception {
        String playerId = newPlayer();
        JsonNode data = okData(postJson("/pay/order", playerId,
                new CreateOrderReq(newRequestId(), "first_charge", 1)));

        String orderId = data.get("orderId").asText();
        assertThat(orderId).isNotBlank();
        assertThat(data.get("payParams").get("offerId").asText())
                .as("offerId 来自部署参数，不是配置表也不是客户端").isEqualTo("test-offer-id");
        assertThat(data.get("payParams").get("buyQuantity").asText())
                .as("金额以分为单位透传").isEqualTo(
                        String.valueOf(configs.longParam("PRODUCT_FIRST_CHARGE_CENTS")));

        PayOrder order = orders.get(orderId);
        assertThat(order).isNotNull();
        assertThat(order.line().unitPriceCents())
                .isEqualTo(configs.longParam("PRODUCT_FIRST_CHARGE_CENTS"));
        assertThat(order.status()).isEqualTo(PayOrder.Status.PENDING);
    }

    @Test
    @DisplayName("未知商品被拒：客户端不能靠自造 productId 让服务端去查一个不存在的价格")
    void unknownProductIsRejected() throws Exception {
        JsonNode root = postJson("/pay/order", newPlayer(),
                new CreateOrderReq(newRequestId(), "one_yuan_monthly_card", 1));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.PAY_PRODUCT_OFFLINE.code());
    }

    // ---------- 验收 2：回调幂等 ----------

    @Test
    @DisplayName("验收2：同一订单重复回调 3 次，只发货一次")
    void duplicateCallbacksDeliverOnlyOnce() throws Exception {
        String playerId = newPlayer();
        String orderId = okData(postJson("/pay/order", playerId,
                new CreateOrderReq(newRequestId(), "monthly_card", 1))).get("orderId").asText();

        for (int i = 0; i < 3; i++) {
            JsonNode data = okData(postJson("/pay/callback", null,
                    new PayCallbackReq(orderId, "txn-" + i, "sign-" + i, true)));
            assertThat(data.get("status").asText()).as("第 %d 次回调后订单都是 SUCCESS", i + 1)
                    .isEqualTo("SUCCESS");
        }

        PayOrder order = orders.get(orderId);
        assertThat(order.callbackCount()).as("三次回调都被记录下来（可审计）").isEqualTo(3);
        assertThat(order.fulfillAttempts())
                .as("验收 2 的本体：只发货一次。不幂等的后果是同一笔钱发三份货，"
                        + "那是能直接亏穿一个服的经济漏洞，而手工点一次支付永远只收到一次回调，"
                        + "所以这条漏洞在功能测试里完全看不出来")
                .isEqualTo(1);
        // 领域状态是 PAID_UNFULFILLED（钱收了、货没发出去，因为默认发货实现什么都不发），
        // 而协议状态收窄成 SUCCESS + retryQueued=true —— 对玩家而言钱确实付了，
        // 把它显示成 FAILED 会让他再付一次或发起一笔不该发生的退款
        assertThat(order.status()).isEqualTo(PayOrder.Status.PAID_UNFULFILLED);
    }

    @Test
    @DisplayName("验收3：发货失败时订单进补单队列，状态查询如实告知「钱收了、货在处理中」")
    void failedFulfillmentIsQueuedForRetryAndVisible() throws Exception {
        String playerId = newPlayer();
        String orderId = okData(postJson("/pay/order", playerId,
                new CreateOrderReq(newRequestId(), "growth_fund", 1))).get("orderId").asText();
        okData(postJson("/pay/callback", null, new PayCallbackReq(orderId, "txn-1", "sign-1", true)));

        JsonNode status = okData(perform(get("/pay/order?orderId=" + orderId).header(PLAYER_HEADER, playerId)));
        assertThat(status.get("status").asText()).as("钱已经收了，订单不能是 FAILED").isEqualTo("SUCCESS");
        assertThat(status.get("retryQueued").asBoolean())
                .as("发货失败必须可见：客户端据此显示「处理中」并给出客服入口，而不是显示失败")
                .isTrue();
        assertThat(status.get("rewards").size()).as("没发出去就没有奖励可列").isZero();

        assertThat(orders.retryQueue(10)).extracting(PayOrder::orderId).contains(orderId);
    }

    @Test
    @DisplayName("补单可以重复触发：每次都仍然发不出去（默认实现不发货），但订单不会因此丢失或重复发货")
    void retryKeepsTheOrderAliveWithoutDoubleDelivering() throws Exception {
        String playerId = newPlayer();
        String orderId = okData(postJson("/pay/order", playerId,
                new CreateOrderReq(newRequestId(), "monthly_card", 1))).get("orderId").asText();
        okData(postJson("/pay/callback", null, new PayCallbackReq(orderId, "txn-1", "sign-1", true)));
        int attemptsAfterCallback = orders.get(orderId).fulfillAttempts();

        okData(postJson("/pay/retry", playerId, new PayRetryReq(newRequestId(), orderId)));

        PayOrder order = orders.get(orderId);
        assertThat(order.fulfillAttempts()).as("补单又试了一次").isEqualTo(attemptsAfterCallback + 1);
        assertThat(order.status()).as("补单不改变已付款这个事实：仍然是一笔待发货的负债")
                .isEqualTo(PayOrder.Status.PAID_UNFULFILLED);
    }

    // ---------- 安全边界 ----------

    @Test
    @DisplayName("验签失败必须拒绝：不验签的回调端点等于任何人 POST 一下就能给自己发货")
    void callbackWithoutSignatureIsRejected() throws Exception {
        String playerId = newPlayer();
        String orderId = okData(postJson("/pay/order", playerId,
                new CreateOrderReq(newRequestId(), "first_charge", 1))).get("orderId").asText();

        JsonNode root = postJson("/pay/callback", null, new PayCallbackReq(orderId, "txn-x", " ", true));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.PAY_SIGN_INVALID.code());
        assertThat(orders.get(orderId).status())
                .as("被拒的回调不得改变订单状态").isEqualTo(PayOrder.Status.PENDING);
    }

    @Test
    @DisplayName("未知订单号的回调被拒并留痕：可能是伪造回调，也可能是我们先丢了订单，两种都不能发货")
    void callbackForUnknownOrderIsRejected() throws Exception {
        JsonNode root = postJson("/pay/callback", null,
                new PayCallbackReq("order_nobody_" + UUID.randomUUID(), "txn-1", "sign-1", true));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.PAY_ORDER_DUPLICATE.code());
    }

    @Test
    @DisplayName("别人的订单一律回「不存在」：回「不属于你」等于给了一个探测订单号的接口")
    void otherPlayersOrderIsInvisible() throws Exception {
        String owner = newPlayer();
        String stranger = newPlayer();
        String orderId = okData(postJson("/pay/order", owner,
                new CreateOrderReq(newRequestId(), "first_charge", 1))).get("orderId").asText();

        JsonNode root = perform(get("/pay/order?orderId=" + orderId).header(PLAYER_HEADER, stranger));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.PAY_ORDER_DUPLICATE.code());
    }

    // ---------- PAY_ORDER_TTL_HOURS：未支付订单过期 ----------

    /**
     * 时间可控的服务实例。
     *
     * <p><b>刻意不去改上下文里那份 {@link TimeService}</b>：它是全服唯一时间基准，
     * 把它的时钟拨快 25 小时，同一个上下文里后面每一个测试都会在「未来」里跑
     * （资源产出、日切、赛季阶段全都跟着跳），那种串扰排查起来比这条功能本身还贵。
     * 所以另建一个被测实例，只让它读可控时钟 —— 与 SeasonSettlementTest 同一做法。
     */
    private PayAppService payWithClock(java.util.concurrent.atomic.AtomicLong clock) {
        return new PayAppService(orders, verifier, fulfiller, configs, playerLock, idempotency,
                new TimeService(clock::get), environment,
                // 2026-09-11 由另一条工作线补：构造器多了这两个注入物，而本用例测的是订单 TTL。
                // 值与生产装配同源（PayBeansConfig），不是为测试编的一套数
                new com.ironoath.core.pay.PopupThrottle(new com.ironoath.core.pay.PopupThrottle.Rules(
                        configs.longParam("PAY_FIRST_PURCHASE_QUIET_HOURS") * 3_600_000L,
                        (int) configs.longParam("PAY_POPUP_PER_GIFT_DAILY_MAX"),
                        configs.longParam("PAY_POPUP_GLOBAL_COOLDOWN_MINUTES") * 60_000L)),
                // UNKNOWN 就是生产默认 bean：年龄未知 ⇒ 限额不拦，本用例的下单路径不会碰到 throttle
                com.ironoath.web.pay.MinorPaymentPolicy.UNKNOWN);
    }

    @Test
    @DisplayName("PAY_ORDER_TTL_HOURS：超时未付的订单在查单时作废，协议状态是 FAILED 而不是永远 PENDING")
    void unpaidOrderExpiresWhenPolled() {
        long base = 1_700_000_000_000L;
        long ttlMs = configs.longParam("PAY_ORDER_TTL_HOURS") * 3_600_000L;
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(base);
        PayAppService pay = payWithClock(clock);
        String playerId = newPlayer();
        String orderId = pay.createOrder(playerId,
                new CreateOrderReq("req-" + UUID.randomUUID(), "monthly_card", 1)).orderId();
        assertThat(pay.status(playerId, orderId).status()).isEqualTo(OrderStatus.PENDING);

        clock.set(base + ttlMs + MINUTE);
        assertThat(pay.status(playerId, orderId).status())
                .as("轮询这个端点的人就是在等一个答复，未付的单子必须给出结论")
                .isEqualTo(OrderStatus.FAILED);
        PayOrder order = orders.get(orderId);
        assertThat(order.status()).isEqualTo(PayOrder.Status.CANCELLED);
        assertThat(order.needsRetry()).as("没有支付发生就不该进补单队列").isFalse();
        assertThat(order.paidAt()).as("作废不是支付：paidAt 必须留 0，否则对账会把它读成一笔收入")
                .isZero();
    }

    /**
     * 这条是本次接线存在的理由：过期关掉的是生产侧的账，而一条验签通过、带交易号的成功回调
     * 是渠道确认扣款的凭据。因为「我们以为它过期了」就不发货，玩家看到的就是付了钱什么都没拿到，
     * 而那正是 B15 §2 补单队列那段注释里点名要避免的东西。
     */
    @Test
    @DisplayName("过期之后迟到一条验签通过的成功回调：仍然确认收款并发货，且仍然只发一次")
    void lateCallbackAfterExpiryStillDelivers() {
        long base = 1_700_000_000_000L;
        long ttlMs = configs.longParam("PAY_ORDER_TTL_HOURS") * 3_600_000L;
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(base);
        PayAppService pay = payWithClock(clock);
        String playerId = newPlayer();
        String orderId = pay.createOrder(playerId,
                new CreateOrderReq("req-" + UUID.randomUUID(), "growth_fund", 1)).orderId();

        clock.set(base + ttlMs + MINUTE);
        pay.status(playerId, orderId);
        assertThat(orders.get(orderId).status()).isEqualTo(PayOrder.Status.CANCELLED);

        OrderStatusResp late = pay.callback(new PayCallbackReq(orderId, "txn-late", "sign-1", true));
        assertThat(late.status()).as("钱是实的：对玩家而言这是「已购买」，显示 FAILED 会引发一笔不该发生的退款")
                .isEqualTo(OrderStatus.SUCCESS);
        assertThat(late.retryQueued()).as("本 profile 下发货实现什么都不发 ⇒ 仍然是补单队列里的负债")
                .isTrue();
        assertThat(orders.get(orderId).fulfillAttempts()).as("迟到的回调同样要试一次发货").isEqualTo(1);

        pay.callback(new PayCallbackReq(orderId, "txn-late", "sign-1", true));
        pay.callback(new PayCallbackReq(orderId, "txn-new", "sign-2", true));
        assertThat(orders.get(orderId).fulfillAttempts())
                .as("重开只允许一次：确认收款之后的重复回调再发一次货，是能直接套现的漏洞").isEqualTo(1);
        assertThat(orders.get(orderId).transactionId()).isEqualTo("txn-late");
    }

    @Test
    @DisplayName("下单时顺带清扫：已付款的订单不会被扫掉，未支付的才会")
    void createOrderSweepsZombiesButKeepsDebts() {
        long base = 1_700_000_000_000L;
        long ttlMs = configs.longParam("PAY_ORDER_TTL_HOURS") * 3_600_000L;
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(base);
        PayAppService pay = payWithClock(clock);
        String playerId = newPlayer();
        String stale = pay.createOrder(playerId,
                new CreateOrderReq("req-" + UUID.randomUUID(), "first_charge", 1)).orderId();
        String paid = pay.createOrder(playerId,
                new CreateOrderReq("req-" + UUID.randomUUID(), "first_charge", 1)).orderId();
        pay.callback(new PayCallbackReq(paid, "txn-paid", "sign-1", true));

        clock.set(base + ttlMs + MINUTE);
        String fresh = pay.createOrder(playerId,
                new CreateOrderReq("req-" + UUID.randomUUID(), "first_charge", 1)).orderId();

        assertThat(orders.get(stale).status()).as("下单是唯一让订单表变大的入口，不在这里扫表就只增不减")
                .isEqualTo(PayOrder.Status.CANCELLED);
        assertThat(orders.get(paid).status()).as("已确认收款是一笔负债，不在清扫范围内")
                .isEqualTo(PayOrder.Status.PAID_UNFULFILLED);
        assertThat(orders.get(fresh).status()).isEqualTo(PayOrder.Status.PENDING);
    }

    // ---------- 夹具 ----------

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "支付测试", 1_700_000_000_000L, ""))
                .playerId();
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private JsonNode postJson(String url, String playerId, Object req) throws Exception {
        MockHttpServletRequestBuilder builder = post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(req));
        if (playerId != null) {
            builder = builder.header(PLAYER_HEADER, playerId);
        }
        return perform(builder);
    }

    private JsonNode perform(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        // MockMvc 默认按 ISO-8859-1 解码响应体，中文提示会变乱码
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode okData(JsonNode root) {
        assertThat(root.get("code").asInt())
                .as("业务码必须为 0，实际响应=%s", root).isZero();
        return root.get("data");
    }
}
