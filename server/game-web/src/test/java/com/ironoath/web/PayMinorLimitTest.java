package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.MonthKey;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.pay.PayOrder;
import com.ironoath.core.pay.PopupThrottle;
import com.ironoath.web.dto.generated.CreateOrderReq;
import com.ironoath.web.pay.MinorPaymentPolicy;
import com.ironoath.web.service.PayAppService;

/**
 * 未成年付费限额的**接线**（B15 §3、上线检查清单 §二 4）。
 *
 * <p>判定函数与月度账本各自都有单测，这个类只回答另一件事：
 * **下单那条路上到底有没有人问一句**。它用自己的订单表，不碰上下文里那份共享登记簿 ——
 * 否则这里塞进去的「已付款订单」会变成别的支付用例里看不懂的余额。
 *
 * <p>金额一律从 {@code global} 现读，不写死：限额与商品价都是会被调的数，
 * 测试写死之后一旦表改了就会红在测试上而不是红在产品上。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("B15 未成年付费限额：下单处真的会去问一句")
class PayMinorLimitTest {

    /** 与 PayEndpointTest 同一个固定时钟基准（2023-11-14），月界由它现算。 */
    private static final long BASE = 1_700_000_000_000L;

    @Autowired private ConfigRegistry configs;
    @Autowired private PayAppService.SignatureVerifier verifier;
    @Autowired private PayAppService.ProductFulfiller fulfiller;
    @Autowired private PlayerLock playerLock;
    @Autowired private IdempotencyStore idempotency;
    @Autowired private Environment environment;
    @Autowired private com.ironoath.core.player.PlayerRepository players;

    private long priceOf(String productId) {
        return switch (productId) {
            case "monthly_card" -> configs.longParam("PRODUCT_MONTHLY_CARD_CENTS");
            case "growth_fund" -> configs.longParam("PRODUCT_GROWTH_FUND_CENTS");
            default -> configs.longParam("PRODUCT_FIRST_CHARGE_CENTS");
        };
    }

    private PayAppService payWith(PayOrder.Registry orders, MinorPaymentPolicy policy) {
        AtomicLong clock = new AtomicLong(BASE);
        return new PayAppService(orders, verifier, fulfiller, configs, playerLock, idempotency,
                new TimeService(clock::get), environment, throttle(), policy,
                new com.ironoath.web.pay.PaidProducts(configs), players);
    }

    /** 频控那三条口径与生产装配同源，不为测试另编一套数。 */
    private PopupThrottle throttle() {
        return new PopupThrottle(new PopupThrottle.Rules(
                configs.longParam("PAY_FIRST_PURCHASE_QUIET_HOURS") * 3_600_000L,
                (int) configs.longParam("PAY_POPUP_PER_GIFT_DAILY_MAX"),
                configs.longParam("PAY_POPUP_GLOBAL_COOLDOWN_MINUTES") * 60_000L));
    }

    /** 造一笔「这个月已经付掉这么多钱」的既成事实。 */
    private void seedPaid(PayOrder.Registry orders, String playerId, long cents,
                         PayOrder.Status status, long paidAt) {
        String orderId = "order_seed_" + UUID.randomUUID();
        orders.insert(PayOrder.fromSnapshot(new PayOrder.Snapshot(orderId, playerId,
                "growth_fund", 1, cents, paidAt - 1000L, status,
                status == PayOrder.Status.SUCCESS ? "txn_" + orderId : null,
                1, 1, paidAt, status == PayOrder.Status.SUCCESS ? paidAt : 0L,
                null, null, null)));
    }

    private static MinorPaymentPolicy alwaysMinor() {
        return playerId -> Boolean.TRUE;
    }

    /** 本月内、还差 {@code gapCents} 就到月度上限的那一刻已花满。 */
    private long almostSpent(long gapCents) {
        return configs.longParam("MINOR_PAY_MONTHLY_LIMIT_CENTS") - gapCents;
    }

    private static long insideThisMonth() {
        return MonthKey.startMillis(BASE) + 60_000L;
    }

    @Test
    @DisplayName("未成年买超过单笔上限的商品：拒绝，且不在订单表里留下一笔 PENDING")
    void minorSingleCapRejectsBeforeInsert() {
        PayOrder.Registry orders = new PayOrder.Registry();
        long price = priceOf("growth_fund");
        assertThat(price).as("前置：这件商品确实贵过单笔上限，否则这条用例什么都没测")
                .isGreaterThan(configs.longParam("MINOR_PAY_SINGLE_LIMIT_CENTS"));

        String playerId = "p-minor-" + UUID.randomUUID();
        assertThatThrownBy(() -> payWith(orders, alwaysMinor()).createOrder(playerId,
                new CreateOrderReq("req-" + UUID.randomUUID(), "growth_fund", 1, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PAY_MINOR_LIMIT);

        assertThat(orders.all()).as("拦下了还留一笔 PENDING，对账就会把一次被拒的支付当真实交易")
                .isEmpty();
    }

    @Test
    @DisplayName("成年与年龄未知都不拦：没接实名不能把全服付费锁死，也不能顺手放过已确认的未成年账号")
    void adultAndUnknownAreBothAllowed() {
        PayOrder.Registry orders = new PayOrder.Registry();
        for (MinorPaymentPolicy policy : new MinorPaymentPolicy[] {
                playerId -> Boolean.FALSE, MinorPaymentPolicy.UNKNOWN }) {
            String playerId = "p-" + UUID.randomUUID();
            String orderId = payWith(orders, policy).createOrder(playerId,
                    new CreateOrderReq("req-" + UUID.randomUUID(), "growth_fund", 1, null)).orderId();
            assertThat(orders.get(orderId)).as("这一路应当正常建档（策略=%s）", policy).isNotNull();
        }
    }

    @Test
    @DisplayName("本月快花完时，小额订单也拒：走的必须是月度那条分支，不是单笔")
    void minorMonthlyCapRejectsSmallOrder() {
        PayOrder.Registry orders = new PayOrder.Registry();
        String playerId = "p-minor-" + UUID.randomUUID();
        seedPaid(orders, playerId, almostSpent(200L), PayOrder.Status.SUCCESS, insideThisMonth());

        long small = priceOf("first_charge");
        assertThat(small).as("前置：这笔小额在单笔上限之内，所以拒它的只可能是月度额度")
                .isLessThanOrEqualTo(configs.longParam("MINOR_PAY_SINGLE_LIMIT_CENTS"));

        PayAppService pay = payWith(orders, alwaysMinor());
        assertThatThrownBy(() -> pay.createOrder(playerId,
                new CreateOrderReq("req-" + UUID.randomUUID(), "first_charge", 1, "hero_sr_01")))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException b = (BizException) e;
                    assertThat(b.errorCode()).isEqualTo(ErrorCode.PAY_MINOR_LIMIT);
                    assertThat(b.detail())
                            .as("文案要说清是本月超额，否则玩家会去换更小的档位，而换多小都没用")
                            .contains("本月剩余额度").doesNotContain("单笔上限");
                });
    }

    @Test
    @DisplayName("上个月的付款不占本月额度：月界是自然月，不是「往前 30 天」")
    void lastMonthSpendDoesNotEatThisMonthQuota() {
        PayOrder.Registry orders = new PayOrder.Registry();
        String playerId = "p-minor-" + UUID.randomUUID();
        seedPaid(orders, playerId,
                configs.longParam("MINOR_PAY_MONTHLY_LIMIT_CENTS") + 10_000L,
                PayOrder.Status.SUCCESS, MonthKey.startMillis(BASE) - 1000L);

        PayAppService pay = payWith(orders, alwaysMinor());
        String orderId = pay.createOrder(playerId,
                new CreateOrderReq("req-" + UUID.randomUUID(), "first_charge", 1, "hero_sr_01")).orderId();
        assertThat(orders.get(orderId))
                .as("跨月刷新是限额文案里承诺过的（等下月额度刷新），做不到就是骗人").isNotNull();
    }

    @Test
    @DisplayName("只有确认收款的单算进额度：作废与被拒的单都不占额")
    void unconfirmedOrdersDoNotCount() {
        PayOrder.Registry orders = new PayOrder.Registry();
        String playerId = "p-minor-" + UUID.randomUUID();
        long monthly = configs.longParam("MINOR_PAY_MONTHLY_LIMIT_CENTS");
        seedPaid(orders, playerId, monthly + 5_000L, PayOrder.Status.CANCELLED, 0L);
        seedPaid(orders, playerId, monthly + 5_000L, PayOrder.Status.FAILED, insideThisMonth());

        PayAppService pay = payWith(orders, alwaysMinor());
        String orderId = pay.createOrder(playerId,
                new CreateOrderReq("req-" + UUID.randomUUID(), "first_charge", 1, "hero_sr_01")).orderId();
        assertThat(orders.get(orderId))
                .as("玩家点了几次没付成功就把自己的额度用光了，那是「没付的钱也占额」那种事故").isNotNull();
    }

    @Test
    @DisplayName("额度是每个账号一份：别人的已花不能算到我头上")
    void quotaIsPerPlayer() {
        PayOrder.Registry orders = new PayOrder.Registry();
        seedPaid(orders, "p-other-" + UUID.randomUUID(),
                configs.longParam("MINOR_PAY_MONTHLY_LIMIT_CENTS") + 9_000L,
                PayOrder.Status.SUCCESS, insideThisMonth());

        PayAppService pay = payWith(orders, alwaysMinor());
        String orderId = pay.createOrder("p-minor-" + UUID.randomUUID(),
                new CreateOrderReq("req-" + UUID.randomUUID(), "first_charge", 1, "hero_sr_01")).orderId();
        assertThat(orders.get(orderId)).isNotNull();
    }
}
