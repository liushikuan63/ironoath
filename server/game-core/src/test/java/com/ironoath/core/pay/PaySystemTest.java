package com.ironoath.core.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：B15 支付与合规核心的单测 —— 验收 2（回调幂等）、3（补单队列）、
 * 4（未成年友好提示）、6（首次付费静默期）、7（弹窗频控）。
 * 依赖：JUnit 5 + AssertJ + game-core 的 pay 包（纯 Java，零框架）。
 *
 * <p><b>本批次含合规红线，所以测试的口径比一般批次更严</b>：
 * 支付幂等漏一次就是可以直接套现的漏洞，未成年提示写成硬拦截就是监管处罚，
 * 而这两类问题在功能测试里都不会失败 —— 支付重复回调时货发了三份，功能上是「成功」的；
 * 未成年被硬拦截时付费确实没发生，功能上也是「成功」的。
 * 所以必须逐条钉住。
 */
class PaySystemTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    // ---------- 夹具 ----------

    private static PayOrder.Line line(long unitCents, int count) {
        return new PayOrder.Line("gift_monthly_card", count, unitCents, 0L, null);
    }

    private static PayOrder newOrder(String orderId, long unitCents, int count) {
        return PayOrder.create(orderId, "p1", line(unitCents, count));
    }

    /** 指定下单时刻的夹具：过期判定读的就是 {@code Line.createdAt}，默认夹具写死 0 只能测「必然已过期」。 */
    private static PayOrder orderCreatedAt(String orderId, long createdAt) {
        return PayOrder.create(orderId, "p1",
                new PayOrder.Line("gift_monthly_card", 1, 3000L, createdAt, null));
    }

    private static PopupThrottle throttle() {
        return new PopupThrottle(new PopupThrottle.Rules(24 * HOUR, 3, 10 * MINUTE));
    }

    // ---------- 金额单位（禁止项：不要用 double 表示金额） ----------

    @Test
    @DisplayName("金额一律用「分」的 long：0.1+0.2 这类浮点误差在支付上意味着账永远对不平")
    void amountsAreLongCents() {
        PayOrder.Line item = line(3000L, 2);
        assertThat(item.totalCents()).isEqualTo(6000L);
        assertThat(PopupThrottle.formatCents(3000L)).isEqualTo("30");
        assertThat(PopupThrottle.formatCents(1999L)).as("19.99 元，不是 19.990000000000002").isEqualTo("19.99");
        assertThat(PopupThrottle.formatCents(600L)).isEqualTo("6");
        assertThat(PopupThrottle.formatCents(605L)).as("余分小于 10 要补零").isEqualTo("6.05");
        assertThat(PopupThrottle.formatCents(0L)).isEqualTo("0");
    }

    @Test
    @DisplayName("总价溢出要在计算时就炸：溢出后金额变成负数，支付平台会照那个值扣款")
    void totalCentsDetectsOverflow() {
        PayOrder.Line huge = new PayOrder.Line("x", Integer.MAX_VALUE, Long.MAX_VALUE, 0L, null);
        assertThatThrownBy(huge::totalCents)
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("溢出");
    }

    @Test
    @DisplayName("下单参数校验：数量至少 1，单价不得为负，订单号是幂等键所以不得为空")
    void orderInputsAreValidated() {
        assertThatThrownBy(() -> new PayOrder.Line("x", 0, 100, 0L, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(">= 1");
        assertThatThrownBy(() -> new PayOrder.Line("x", 1, -100, 0L, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为负");
        assertThatThrownBy(() -> new PayOrder.Line(" ", 1, 100, 0L, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("productId");
        assertThatThrownBy(() -> PayOrder.create("", "p1", line(100, 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("幂等");
    }

    // ---------- 验收 2：回调幂等 ----------

    @Test
    @DisplayName("验收2：同一订单重复回调 3 次，只有第一次算数，发货也只发生一次")
    void callbackIsIdempotent() {
        PayOrder order = newOrder("o1", 3000L, 1);
        assertThat(order.status()).isEqualTo(PayOrder.Status.PENDING);

        PayOrder.CallbackOutcome first = order.confirmCallback("wx_txn_1", true, 1000L);
        assertThat(first.firstTime()).isTrue();
        assertThat(first.statusAfter()).isEqualTo(PayOrder.Status.PAID_UNFULFILLED);

        AtomicInteger deliveries = new AtomicInteger();
        order.fulfill(() -> {
            deliveries.incrementAndGet();
            return true;
        }, 3, 2000L);
        assertThat(order.status()).isEqualTo(PayOrder.Status.SUCCESS);

        for (int i = 0; i < 2; i++) {
            PayOrder.CallbackOutcome replay = order.confirmCallback("wx_txn_" + (i + 2), true, 3000L + i);
            assertThat(replay.firstTime()).as("第 %d 次重复回调", i + 2).isFalse();
            assertThat(replay.reason()).contains("被忽略");
            // 重复回调不得把已成功的订单打回未发货，否则补单队列会再发一次货
            PayOrder.FulfillOutcome retry = order.fulfill(() -> {
                deliveries.incrementAndGet();
                return true;
            }, 3, 4000L);
            assertThat(retry.delivered()).isFalse();
            assertThat(retry.reason()).contains("重复调用被忽略");
        }
        assertThat(deliveries.get()).as("发过 3 次回调，货只发一次").isEqualTo(1);
        assertThat(order.callbackCount()).isEqualTo(3);
        assertThat(order.transactionId()).as("首次回调的交易号被保留").isEqualTo("wx_txn_1");
    }

    @Test
    @DisplayName("回调带不同的 transactionId 也算重复：幂等键是 orderId，不是交易号")
    void idempotencyKeysOnOrderIdNotTransactionId() {
        PayOrder order = newOrder("o1", 100L, 1);
        assertThat(order.confirmCallback("txn_A", true, 1000L).firstTime()).isTrue();
        assertThat(order.confirmCallback("txn_B", true, 2000L).firstTime())
                .as("微信重试时可能带不同的 transactionId，用它做键就挡不住重复").isFalse();
    }

    @Test
    @DisplayName("收款成功却没有交易号：宁可停在 PENDING 也不发货（发了就追不回）")
    void missingTransactionIdRefusesFulfillment() {
        PayOrder order = newOrder("o1", 100L, 1);
        PayOrder.CallbackOutcome outcome = order.confirmCallback(null, true, 1000L);
        assertThat(outcome.firstTime()).isFalse();
        assertThat(order.status()).isEqualTo(PayOrder.Status.PENDING);
        assertThat(outcome.reason()).contains("transactionId");
        PayOrder.FulfillOutcome blocked = order.fulfill(() -> true, 3, 2000L);
        assertThat(blocked.delivered()).as("PENDING 状态不允许发货").isFalse();
        assertThat(blocked.queuedForRetry()).as("没收到钱就不是负债，不该进补单队列").isFalse();
    }

    @Test
    @DisplayName("验收1 路径二：玩家取消支付 → CANCELLED，且不进补单队列（没有支付就没有发货义务）")
    void cancelledOrderNeverEntersRetryQueue() {
        PayOrder order = newOrder("o1", 3000L, 1);
        assertThat(order.cancel(1000L).firstTime()).isTrue();
        assertThat(order.status()).isEqualTo(PayOrder.Status.CANCELLED);
        assertThat(order.needsRetry()).as("取消不进补单队列，否则队列里全是永远不会成功的单子").isFalse();
        assertThat(order.cancel(2000L).firstTime()).isFalse();
        assertThat(order.paidAt()).as("取消没有支付：paidAt 必须留 0，否则对账会把关闭时刻读成「钱进来了」")
                .isZero();
        assertThat(order.confirmCallback("txn", false, 3000L).firstTime())
                .as("渠道说没收到钱 —— 已作废的订单本来就在按没收钱处理，不用改成 FAILED").isFalse();
        assertThat(order.status()).as("取消与渠道拒付是两种关闭原因，混成一谈就没法回答「到底关了多少单是因为什么」")
                .isEqualTo(PayOrder.Status.CANCELLED);
    }

    /**
     * 这条测试以前断的是反面的规则（「已取消的订单不能被后续回调复活」）。
     * 改过来的理由是 pay.schema.json 自己写明了 orderId 为什么是幂等唯一键 ——
     * 「取消后重新支付会产生新的 transactionId，而那是同一笔订单」。
     * 也就是说「取消后重付」是设计里的常态，而按老规则走的结果是：
     * 渠道确认扣款、验签通过、我们回一句「已取消」然后不发 —— 玩家的体验是钱没了货没到。
     */
    @Test
    @DisplayName("取消之后重新支付：同 orderId、新 transactionId 的成功回调必须重开发货，且仍然只发一次")
    void paidAfterCancelStillDeliversOnce() {
        PayOrder order = newOrder("o1", 3000L, 1);
        order.cancel(1000L);

        PayOrder.CallbackOutcome late = order.confirmCallback("txn_second", true, 2000L);
        assertThat(late.firstTime()).as("钱是实的：不发货的后果追不回来").isTrue();
        assertThat(late.reopened()).as("这条路径必须能被调用方记成日志：偶发是正常业务，成片说明 TTL 太短").isTrue();
        assertThat(order.status()).isEqualTo(PayOrder.Status.PAID_UNFULFILLED);
        assertThat(order.paidAt()).isEqualTo(2000L);
        assertThat(order.transactionId()).isEqualTo("txn_second");
        assertThat(order.failureReason()).as("重开后不能还留着「玩家取消支付」这条已经作废的原因").isNull();

        assertThat(order.fulfill(() -> true, 3, 3000L).delivered()).isTrue();
        // 重开之后幂等闸门照常生效
        assertThat(order.confirmCallback("txn_third", true, 4000L).firstTime())
                .as("重开只针对「还没确认过收款」的订单，确认过的照旧算重复").isFalse();
        assertThat(order.fulfill(() -> true, 3, 5000L).delivered())
                .as("已发货不能因为再来一次回调就再发一次，那是可以直接套现的漏洞").isFalse();
    }

    @Test
    @DisplayName("已作废的订单不会被一条没有交易号的成功回调重开：没有凭据就不发货")
    void closedOrderIsNotReopenedWithoutTransactionId() {
        PayOrder order = newOrder("o1", 3000L, 1);
        order.cancel(1000L);
        assertThat(order.confirmCallback("  ", true, 2000L).firstTime()).isFalse();
        assertThat(order.status()).isEqualTo(PayOrder.Status.CANCELLED);
        assertThat(order.failureReason()).as("没有交易号的回调不该把关闭原因覆盖成别的").isEqualTo("玩家取消支付");
    }

    // ---------- PAY_ORDER_TTL_HOURS：未支付订单过期 ----------

    @Test
    @DisplayName("PAY_ORDER_TTL_HOURS：超过时长未支付的订单转 CANCELLED，未到点的一动不动")
    void unpaidOrderExpiresAfterTtl() {
        long now = 1000L + 25 * HOUR;
        PayOrder fresh = orderCreatedAt("o1", now - HOUR);
        assertThat(fresh.expireIfUnpaid(24 * HOUR, now).firstTime())
                .as("没到点就动它，玩家会在支付中途看到订单失效").isFalse();
        assertThat(fresh.status()).isEqualTo(PayOrder.Status.PENDING);

        PayOrder stale = orderCreatedAt("o2", 1000L);
        assertThat(stale.expireIfUnpaid(24 * HOUR, now).firstTime()).isTrue();
        assertThat(stale.status()).isEqualTo(PayOrder.Status.CANCELLED);
        assertThat(stale.needsRetry()).as("没收到钱就不是负债").isFalse();
        assertThat(stale.failureReason()).contains("PAY_ORDER_TTL_HOURS");
        assertThat(stale.expireIfUnpaid(24 * HOUR, now + HOUR).firstTime())
                .as("第二次扫不能报「又关了一单」，否则日志里的作废数会翻倍").isFalse();

        assertThat(orderCreatedAt("o3", 0L).expireIfUnpaid(0L, now).firstTime())
                .as("TTL 配成 0 的语义是「不过期」—— 照字面执行会变成「刚下的单在下一次请求里就作废」").isFalse();
    }

    /** 过期最容易做错的一处：把作废实现成「删除」或「不可逆」，都会在弱网迟到回调上变成收钱不发货。 */
    @Test
    @DisplayName("过期之后迟到的成功回调仍然要发货：过期关的是生产侧的账，不是渠道侧的")
    void lateCallbackAfterExpiryStillDelivers() {
        long now = 1000L + 25 * HOUR;
        PayOrder order = orderCreatedAt("o1", 1000L);
        assertThat(order.expireIfUnpaid(24 * HOUR, now).firstTime()).isTrue();

        PayOrder.CallbackOutcome late = order.confirmCallback("txn_late", true, now + MINUTE);
        assertThat(late.firstTime()).as("验签通过 + 有交易号 = 钱已经收了").isTrue();
        assertThat(late.reopened()).isTrue();
        assertThat(order.fulfill(() -> true, 3, now + 2 * MINUTE).delivered()).isTrue();
        assertThat(order.status()).isEqualTo(PayOrder.Status.SUCCESS);
    }

    @Test
    @DisplayName("已确认收款的订单不会因为超时而作废：那是一笔负债，不是僵尸记录")
    void debtNeverExpires() {
        PayOrder order = orderCreatedAt("o1", 1000L);
        order.confirmCallback("txn", true, 1000L + MINUTE);
        order.fulfill(() -> false, 1, 1000L + 2 * MINUTE);   // 发货失败且已达上限 → 留在队列里等人工
        assertThat(order.needsRetry()).isTrue();

        PayOrder.CallbackOutcome outcome = order.expireIfUnpaid(HOUR, 1000L + 30 * DAY);
        assertThat(outcome.firstTime()).isFalse();
        assertThat(outcome.reason()).contains("已确认收款");
        assertThat(order.status())
                .as("对账时这笔钱仍然挂着，状态必须停在 PAID_UNFULFILLED 直到人工处理完")
                .isEqualTo(PayOrder.Status.PAID_UNFULFILLED);
    }

    @Test
    @DisplayName("验收1 路径三：支付平台拒绝 → FAILED，同样不进补单队列")
    void failedPaymentDoesNotEnterRetryQueue() {
        PayOrder order = newOrder("o1", 3000L, 1);
        PayOrder.CallbackOutcome outcome = order.confirmCallback("txn", false, 1000L);
        assertThat(outcome.firstTime()).isTrue();
        assertThat(order.status()).isEqualTo(PayOrder.Status.FAILED);
        assertThat(order.needsRetry()).isFalse();
    }

    // ---------- 验收 3：补单队列 ----------

    @Test
    @DisplayName("验收3：发货失败留在 PAID_UNFULFILLED 并进补单队列，重试后成功发货")
    void failedFulfillmentIsRetriedUntilSuccess() {
        PayOrder order = newOrder("o1", 3000L, 1);
        order.confirmCallback("txn", true, 1000L);

        AtomicInteger attempts = new AtomicInteger();
        // 前两次失败、第三次成功
        PayOrder.FulfillOutcome first = order.fulfill(() -> attempts.incrementAndGet() >= 3, 5, 2000L);
        assertThat(first.delivered()).isFalse();
        assertThat(first.queuedForRetry()).as("未达最大重试次数 ⇒ 进补单队列").isTrue();
        assertThat(order.status())
                .as("钱已收了，这笔负债不能因为发货失败就消失（改成 FAILED 会让对账看不出钱到底收没收到）")
                .isEqualTo(PayOrder.Status.PAID_UNFULFILLED);
        assertThat(order.needsRetry()).isTrue();

        PayOrder.FulfillOutcome second = order.fulfill(() -> attempts.incrementAndGet() >= 3, 5, 3000L);
        assertThat(second.delivered()).isFalse();
        assertThat(second.queuedForRetry()).isTrue();

        PayOrder.FulfillOutcome third = order.fulfill(() -> attempts.incrementAndGet() >= 3, 5, 4000L);
        assertThat(third.delivered()).as("第 3 次成功").isTrue();
        assertThat(order.status()).isEqualTo(PayOrder.Status.SUCCESS);
        assertThat(order.needsRetry()).isFalse();
        assertThat(order.fulfillAttempts()).isEqualTo(3);
        assertThat(order.fulfilledAt()).isEqualTo(4000L);
        assertThat(order.failureReason()).as("成功后清掉失败原因").isNull();
    }

    @Test
    @DisplayName("达到最大重试次数后不再进队列，文案要求人工介入")
    void retryGivesUpAfterMaxAttempts() {
        PayOrder order = newOrder("o1", 3000L, 1);
        order.confirmCallback("txn", true, 1000L);
        PayOrder.FulfillOutcome outcome = order.fulfill(() -> false, 1, 2000L);
        assertThat(outcome.delivered()).isFalse();
        assertThat(outcome.queuedForRetry()).isFalse();
        assertThat(outcome.reason()).contains("人工介入");
        assertThat(order.needsRetry()).as("仍然是负债，不能因为放弃重试就消失").isTrue();
    }

    @Test
    @DisplayName("发货抛异常按失败处理，不让异常穿透到支付回调线程")
    void fulfillmentExceptionIsContained() {
        PayOrder order = newOrder("o1", 3000L, 1);
        order.confirmCallback("txn", true, 1000L);
        PayOrder.FulfillOutcome outcome = order.fulfill(() -> {
            throw new IllegalStateException("奖励发放器炸了");
        }, 3, 2000L);
        assertThat(outcome.delivered()).isFalse();
        assertThat(outcome.queuedForRetry()).isTrue();
        assertThat(order.failureReason()).contains("奖励发放器炸了");
    }

    @Test
    @DisplayName("补单队列分页取（禁止全表扫描：支付平台故障时队列会瞬间涨到几千条）")
    void retryQueueIsPaginated() {
        List<PayOrder> orders = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            PayOrder order = newOrder("o" + i, 100L, 1);
            order.confirmCallback("txn" + i, true, i);
            if (i % 2 == 0) {
                // 奇数号发货成功，偶数号留在队列里
                order.fulfill(() -> false, 1, i);
            } else {
                order.fulfill(() -> true, 1, i);
            }
            orders.add(order);
        }
        assertThat(PayOrder.retryQueue(orders, 100)).hasSize(25);
        assertThat(PayOrder.retryQueue(orders, 10)).hasSize(10);
        assertThat(PayOrder.retryQueue(orders, 1)).hasSize(1);
        assertThatThrownBy(() -> PayOrder.retryQueue(orders, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(">= 1");
    }

    @Test
    @DisplayName("订单表：订单号重复直接拒绝（两笔支付共用一个幂等键等于没有幂等），未发货负债可对账")
    void registryRejectsDuplicateOrderIds() {
        PayOrder.Registry registry = new PayOrder.Registry();
        registry.put(newOrder("o1", 3000L, 1));
        assertThatThrownBy(() -> registry.put(newOrder("o1", 6000L, 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("订单号重复");

        PayOrder order = registry.get("o1");
        order.confirmCallback("txn", true, 1000L);
        assertThat(registry.unfulfilledCents()).as("已收款未发货 = 一笔负债").isEqualTo(3000L);
        assertThat(registry.retryQueue(10)).hasSize(1);

        order.fulfill(() -> true, 3, 2000L);
        assertThat(registry.unfulfilledCents()).as("发货后负债归零").isZero();
        assertThat(registry.retryQueue(10)).isEmpty();
        registry.clear();
        assertThat(registry.all()).isEmpty();
    }

    @Test
    @DisplayName("订单表的过期清扫：只关未支付且已到点的，返回关掉的条数，负债一条都不动")
    void registrySweepClosesOnlyUnpaidOrders() {
        long now = 1000L + 25 * HOUR;
        PayOrder.Registry registry = new PayOrder.Registry();
        registry.put(orderCreatedAt("paid", 0L));
        registry.put(orderCreatedAt("stale", 0L));
        registry.put(orderCreatedAt("fresh", now - HOUR));
        registry.get("paid").confirmCallback("txn", true, 1000L);

        assertThat(registry.expireUnpaid(24 * HOUR, now)).isEqualTo(1);
        assertThat(registry.get("paid").status()).as("已确认收款 = 负债，不在清扫范围内")
                .isEqualTo(PayOrder.Status.PAID_UNFULFILLED);
        assertThat(registry.get("fresh").status()).as("未到点的不动").isEqualTo(PayOrder.Status.PENDING);
        assertThat(registry.get("stale").status()).isEqualTo(PayOrder.Status.CANCELLED);
        assertThat(registry.unfulfilledCents()).as("清扫不能把负债扫没：这一分钱还是要发的")
                .isEqualTo(3000L);
        assertThat(registry.expireUnpaid(24 * HOUR, now)).as("同一时刻再扫没有新的可关：已作废的不得重复计数")
                .isZero();
    }

    // ---------- 验收 6/7：弹窗频控 ----------

    @Test
    @DisplayName("验收6：首次付费后 24 小时内不弹任何付费弹窗（对所有礼包生效）")
    void firstPurchaseStartsQuietPeriod() {
        // 静默期不再由本类自己记：首充时刻的真值住在 PlayerPaid.firstChargedAt，回灌时接过来用一次
        PopupThrottle throttle = PopupThrottle.forPlayer(
                new PopupThrottle.Rules(24 * HOUR, 3, 10 * MINUTE), "p1",
                com.ironoath.core.player.PlayerGiftPopup.empty(), 1000L);

        PopupThrottle.Verdict verdict = throttle.shouldShow("p1", "gift_a", false, 1000L + HOUR);
        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).contains("首次付费");
        assertThat(verdict.retryAfterMillis()).as("要告诉调用方多久后再试").isPositive();

        assertThat(throttle.shouldShow("p1", "gift_b", false, 1000L + 25 * HOUR).allowed())
                .as("静默期对每个礼包都生效，24 小时之后才放行").isTrue();
}

    @Test
    @DisplayName("静默期只属于首充的那个人：共享实例不得拿甲的首充压制乙（回灌后 owner 才生效）")
    void quietPeriodOnlyAppliesToTheOwnerOfThatFirstPay() {
        // 共享实例上拿甲的首充压制乙是错的：静默期必须问"这一位是不是这个玩家的"
        PopupThrottle shared = new PopupThrottle(new PopupThrottle.Rules(24 * HOUR, 3, 10 * MINUTE));
        assertThat(shared.shouldShow("p9", "gift_a", false, 1000L).allowed())
                .as("没有回灌任何首充时刻时不该凭空压制").isTrue();

        PopupThrottle mine = PopupThrottle.forPlayer(
                new PopupThrottle.Rules(24 * HOUR, 3, 10 * MINUTE), "p1",
                com.ironoath.core.player.PlayerGiftPopup.empty(), 1000L);
        assertThat(mine.shouldShow("p2", "gift_a", false, 1000L + HOUR).allowed())
                .as("别人的首充不该压在 p2 头上").isTrue();
}

    @Test
    @DisplayName("验收7：同一礼包 24 小时最多 3 次，第 4 次被压制并给出可重试时刻")
    void sameGiftIsLimitedToThreePerDay() {
        PopupThrottle throttle = throttle();
        for (int i = 0; i < 3; i++) {
            long now = 1000L + i * (11 * MINUTE);   // 每次间隔 11 分钟，绕过全局冷却
            assertThat(throttle.shouldShow("p1", "gift_a", false, now).allowed())
                    .as("第 %d 次应当允许", i + 1).isTrue();
            throttle.recordShown("p1", "gift_a", now);
        }
        PopupThrottle.Verdict fourth = throttle.shouldShow("p1", "gift_a", false, 1000L + 40 * MINUTE);
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.reason()).contains("24 小时");
        assertThat(fourth.retryAfterMillis()).isPositive();

        // 别的礼包不受影响
        assertThat(throttle.shouldShow("p1", "gift_b", false, 1000L + 60 * MINUTE).allowed())
                .as("全局冷却已过，另一个礼包可以弹").isTrue();
    }

    @Test
    @DisplayName("验收7：全局冷却 10 分钟 —— 五个不同礼包各弹一次也不能在五分钟内连着出现")
    void globalCooldownAppliesAcrossGifts() {
        PopupThrottle throttle = throttle();
        assertThat(throttle.shouldShow("p1", "gift_a", false, 1000L).allowed()).isTrue();
        throttle.recordShown("p1", "gift_a", 1000L);

        // 换一个礼包，每条按礼包的规则都没违反，但全局冷却必须挡住
        PopupThrottle.Verdict blocked = throttle.shouldShow("p1", "gift_b", false, 1000L + 5 * MINUTE);
        assertThat(blocked.allowed()).as("全局冷却是最容易被漏掉的一条").isFalse();
        assertThat(blocked.reason()).contains("全局");
        assertThat(blocked.retryAfterMillis()).isEqualTo(5 * MINUTE);

        assertThat(throttle.shouldShow("p1", "gift_b", false, 1000L + 10 * MINUTE + 1).allowed())
                .as("冷却过后放行").isTrue();
    }

    @Test
    @DisplayName("判定与记账分开：shouldShow 是纯查询，问十次不吃一次配额")
    void shouldShowDoesNotConsumeQuota() {
        PopupThrottle throttle = throttle();
        for (int i = 0; i < 10; i++) {
            assertThat(throttle.shouldShow("p1", "gift_a", false, 1000L).allowed()).isTrue();
        }
        // 十次查询之后仍然能弹 3 次
        for (int i = 0; i < 3; i++) {
            long now = 1000L + i * (11 * MINUTE);
            assertThat(throttle.shouldShow("p1", "gift_a", false, now).allowed()).isTrue();
            throttle.recordShown("p1", "gift_a", now);
        }
        assertThat(throttle.shouldShow("p1", "gift_a", false, 1000L + 40 * MINUTE).allowed()).isFalse();
    }

    @Test
    @DisplayName("B11 §七 红线：Bot 不得出现在任何付费弹窗场景，且 viewerIsBot 是必填参数")
    void botsNeverSeePaymentPopups() {
        PopupThrottle throttle = throttle();
        PopupThrottle.Verdict verdict = throttle.shouldShow("bot_1", "gift_a", true, 1000L);
        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).contains("Bot").contains("合规红线");
        assertThat(verdict.retryAfterMillis()).as("这不是时间问题，重试也没用").isZero();
        // 同一个人作为真人时是允许的，说明判定确实看的是 viewerIsBot 而不是 playerId
        assertThat(throttle.shouldShow("bot_1", "gift_a", false, 1000L).allowed()).isTrue();
    }

    @Test
    @DisplayName("频控规则构造期校验：每礼包上限为 0 等于礼包永远不会被弹出")
    void throttleRulesAreValidated() {
        assertThatThrownBy(() -> new PopupThrottle.Rules(HOUR, 0, MINUTE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("perGiftDailyMax");
        assertThatThrownBy(() -> new PopupThrottle.Rules(-1, 3, MINUTE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("静默期");
        assertThatThrownBy(() -> new PopupThrottle.Rules(HOUR, 3, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("全局冷却");
    }

    // ---------- 验收 4：未成年友好提示 ----------

    @Test
    @DisplayName("验收4：额度充足且没花过时不打扰；花过一部分时提示剩余额度")
    void minorWithinLimitGetsNoOrSoftNotice() {
        PopupThrottle throttle = throttle();
        PopupThrottle.MinorVerdict fresh = throttle.minorPayNotice(5000L, 20000L, 0L, 600L);
        assertThat(fresh.withinLimit()).isTrue();
        assertThat(fresh.notice()).as("额度充足且没花过 ⇒ 不打扰正常付费").isNull();
        assertThat(fresh.remainingCents()).isEqualTo(20000L);

        PopupThrottle.MinorVerdict partial = throttle.minorPayNotice(5000L, 20000L, 15000L, 600L);
        assertThat(partial.withinLimit()).isTrue();
        assertThat(partial.notice()).contains("剩余").contains("50");
        assertThat(partial.remainingCents()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("单笔上限独立生效：月度额度还很多，也不能一次扣掉超过单笔上限的钱")
    void singleCapIsEnforcedOnItsOwn() {
        // 判别性：加这个参数之前，9800 分（98 元）在"本月一分没花"的账号上是直接放行的，
        // 而 B15 §3 要求单次与月度两条限额同时成立 —— 只判一条等于少判一条。
        PopupThrottle.MinorVerdict over = throttle().minorPayNotice(5000L, 20000L, 0L, 9800L);
        assertThat(over.withinLimit())
                .as("月度还剩 200 元，但这一笔 98 元超过单笔上限 50 元").isFalse();
        assertThat(over.remainingCents()).as("月度额度本身没被打穿，剩余照实给").isEqualTo(20000L);
        assertThat(over.notice())
                .contains("单笔上限").contains("50")
                .as("不许误报成「本月超额」——那会让玩家等到下月，而真正的原因是档位太大")
                .doesNotContain("本月剩余额度");
    }

    @Test
    @DisplayName("禁止项：未成年超限是提示而不是硬拦截 —— 必须同时给出「还剩多少」与「怎么办」")
    void minorOverLimitGetsFriendlyNoticeNotHardBlock() {
        PopupThrottle throttle = throttle();
        PopupThrottle.MinorVerdict over = throttle.minorPayNotice(5000L, 20000L, 19000L, 3000L);
        assertThat(over.withinLimit()).isFalse();
        assertThat(over.remainingCents()).as("只说「不行」而不说「还剩多少」就是硬拦截").isEqualTo(1000L);
        assertThat(over.notice())
                .contains("30")            // 本次需要多少
                .contains("10")            // 还剩多少
                .contains("更小的档位")     // 出路之一
                .contains("客服");         // 出路之二
        assertThat(over.notice()).doesNotContain("禁止").doesNotContain("不允许");
    }

    @Test
    @DisplayName("限额为 0（通常未满 8 岁）也给明确文案，而不是抛错让客户端只能显示通用错误")
    void zeroLimitStillExplainsItself() {
        PopupThrottle.MinorVerdict verdict = throttle().minorPayNotice(0L, 0L, 0L, 600L);
        assertThat(verdict.withinLimit()).isFalse();
        assertThat(verdict.remainingCents()).isZero();
        assertThat(verdict.notice()).contains("未成年人保护").contains("客服");
    }

    @Test
    @DisplayName("金额不得为负：负数额度会让「还剩多少」变成一个 meaningless 的正数")
    void minorAmountsAreValidated() {
        PopupThrottle throttle = throttle();
        assertThatThrownBy(() -> throttle.minorPayNotice(-1L, 100L, 0L, 100L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为负");
        assertThatThrownBy(() -> throttle.minorPayNotice(100L, -1L, 0L, 100L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为负");
        assertThatThrownBy(() -> throttle.minorPayNotice(100L, 100L, -1L, 100L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为负");
        assertThatThrownBy(() -> throttle.minorPayNotice(100L, 100L, 0L, -1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为负");
    }

    @Test
    @DisplayName("清理玩家状态：退号后频控记录与首次付费时刻都不该留着")
    void forgetClearsPlayerState() {
        PopupThrottle throttle = PopupThrottle.forPlayer(
                new PopupThrottle.Rules(24 * HOUR, 3, 10 * MINUTE), "p1",
                com.ironoath.core.player.PlayerGiftPopup.empty(), 0L);
        throttle.recordShown("p1", "gift_a", 1000L);
        throttle.forget("p1");
        assertThat(throttle.shouldShow("p1", "gift_a", false, 1000L + 11 * MINUTE).allowed())
                .as("清理后全局冷却也不再生效（间隔要大于 10 分钟冷却）").isTrue();
        // 首充时刻现在住 PlayerPaid（持久真值），forget 不再也不该动它
}
}
