package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.pay.PayOrder;
import com.ironoath.core.pay.PayOrderStore;
import com.ironoath.web.store.mongo.MongoPayOrderStore;

/**
 * 职责：支付订单存储在<b>内存登记簿与 Mongo 上必须给出同一个结果</b>，重点是
 * "已收款未发货" 这笔负债（{@link PayOrder.Status#PAID_UNFULFILLED}）真的落得住。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）；连不上时明确报"跳过即未验证"。
 *
 * <p><b>这份测试存在的唯一理由</b>：内存版 {@code get()} 返回的就是表里那个实例，
 * 所以"改完状态不写回"在内存版<b>永远测不出来</b> —— 而换到任何真存储上，
 * 少那次写回的结果不是报错，是"钱收了、订单还停在 PENDING、补单队列里查不到"。
 * 收口清单 #22 记的三处就地改写就是这个形状。所以本类的读全部走 {@code store.get(...)}
 * 重新读一遍，绝不复用改过的那个对象；
 * 而 {@link #missingWriteBackIsInvisibleOnMemoryButVisibleOnMongo} 故意把两边的差异
 * <b>断言出来</b>，用来证明这条测试确实会红 —— 一份两边都恒绿的等价测试等于没测。
 */
class PayOrderStoreEquivalenceTest {

    private static final long T0 = 1_800_000_000_000L;
    private static final long TTL = 24L * 3_600_000L;
    private static TestMongo db;

    @BeforeAll
    static void connect() {
        db = TestMongo.tryOpen();
    }

    /** Mongo 库整个类共享、内存版每条用例新建 —— 不清理会把上一条用例的订单算进负债总额。 */
    @BeforeEach
    void clearOrders() {
        if (db != null) {
            newMongoStore().deleteAll();
        }
    }

    @AfterAll
    static void release() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    // ---------- 负债 ----------

    @Test
    @DisplayName("确认收款后重新读一遍：两套实现都必须给出 PAID_UNFULFILLED 与交易号")
    void paidButUndeliveredLiabilitySurvivesTheWriteBack() {
        for (PayOrderStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insert(order("o-debt", "P-debt", "pack_monthly", 600L));

            PayOrder read = store.get("o-debt");
            assertThat(read.confirmCallback("txn-debt", true, T0 + 1_000L).firstTime())
                    .as("%s 首次成功回调要确认收款", label).isTrue();
            store.save(read);

            // 关键：重新读，而不是复用 read。内存版返回同一实例，Mongo 版必须靠这一次写回
            PayOrder reread = store.get("o-debt");
            assertThat(reread.status()).as("%s 已收款未发货的状态必须落库", label)
                    .isEqualTo(PayOrder.Status.PAID_UNFULFILLED);
            assertThat(reread.transactionId()).as("%s 交易号是对账与追讨的唯一凭据", label)
                    .isEqualTo("txn-debt");
            assertThat(reread.paidAt()).as("%s 付款时刻", label).isEqualTo(T0 + 1_000L);
            assertThat(describe(reread)).as("%s 整笔负债逐字段一致", label)
                    .isEqualTo(describeMemoryReference());
            assertThat(store.retryQueue(10)).as("%s 负债必须出现在补单队列里", label)
                    .extracting(PayOrder::orderId).containsExactly("o-debt");
            assertThat(store.unfulfilledCents()).as("%s 对账口径：600 分欠着", label).isEqualTo(600L);
        }
    }

    /**
     * 这条是"能失败"的证明：漏掉 {@code save} 时内存版<b>照样绿</b>（get 返回的就是改过的实例），
     * Mongo 版必须停在 PENDING。两边行为不同是本测试要写下来的事实，不是要修的 bug ——
     * 修的是调用方：谁不写回，谁就在生产上丢掉一笔已收款的订单。
     */
    @Test
    @DisplayName("漏掉写回：内存版看不出来、Mongo 版立刻看不对（所以必须重新读一遍才算验证）")
    void missingWriteBackIsInvisibleOnMemoryButVisibleOnMongo() {
        PayOrderStore memory = new PayOrder.Registry();
        memory.insert(order("o-no-save", "P-no-save", "pack_monthly", 600L));
        memory.get("o-no-save").confirmCallback("txn-ghost", true, T0 + 1_000L);
        assertThat(memory.get("o-no-save").status())
                .as("内存版的假绿：没有 save，读出来却已经是已收款").isEqualTo(PayOrder.Status.PAID_UNFULFILLED);

        requireMongo();
        PayOrderStore mongo = newMongoStore();
        mongo.insert(order("o-no-save", "P-no-save", "pack_monthly", 600L));
        mongo.get("o-no-save").confirmCallback("txn-ghost", true, T0 + 1_000L);
        assertThat(mongo.get("o-no-save").status())
                .as("同一份代码换到真存储上：不写回就是钱收了而订单从没变过").isEqualTo(PayOrder.Status.PENDING);
        assertThat(mongo.retryQueue(10)).as("补单队列也查不到这笔负债").isEmpty();
        assertThat(mongo.unfulfilledCents()).as("对账账面凭空少了 600 分").isZero();
    }

    @Test
    @DisplayName("发货成功后重新读一遍：SUCCESS、fulfilledAt、尝试次数两套实现一致")
    void deliveredOrderComesBackAsSuccessOnBothStores() {
        for (PayOrderStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insert(order("o-ship", "P-ship", "pack_monthly", 600L));
            PayOrder paid = store.get("o-ship");
            paid.confirmCallback("txn-ship", true, T0 + 1_000L);
            store.save(paid);

            PayOrder shipping = store.get("o-ship");
            assertThat(shipping.fulfill(() -> true, 3, T0 + 2_000L).delivered())
                    .as("%s 首次发货要成功", label).isTrue();
            store.save(shipping);

            PayOrder reread = store.get("o-ship");
            assertThat(reread.status()).as("%s 发货终态", label).isEqualTo(PayOrder.Status.SUCCESS);
            assertThat(reread.fulfilledAt()).as("%s 发货时刻", label).isEqualTo(T0 + 2_000L);
            assertThat(reread.fulfillAttempts()).as("%s 尝试次数（补单据此决定还要不要试）", label)
                    .isEqualTo(1);
            assertThat(store.retryQueue(10)).as("%s 已发货的不该再在补单队列里", label).isEmpty();
            assertThat(store.unfulfilledCents()).as("%s 负债已清", label).isZero();
            assertThat(store.unfulfilledOrderCount()).as("%s 总额归零时笔数也要归零，否则两个判据已经分叉",
                    label).isZero();
        }
    }

    @Test
    @DisplayName("发货失败三次：两套实现给出的尝试次数、原因、负债总额完全一致")
    void failedFulfillmentKeepsTheSameFailureReasonOnBothStores() {
        String fromMemory = null;
        String fromMongo = null;
        for (int i = 0; i < bothStores().size(); i++) {
            PayOrderStore store = bothStores().get(i);
            store.insert(order("o-fail", "P-fail", "fund_growth", 3000L));
            PayOrder paid = store.get("o-fail");
            paid.confirmCallback("txn-fail", true, T0 + 1_000L);
            store.save(paid);
            for (int attempt = 0; attempt < 3; attempt++) {
                PayOrder order = store.get("o-fail");
                order.fulfill(() -> false, 3, T0 + 2_000L + attempt);
                store.save(order);
            }
            String described = describe(store.get("o-fail")) + "|queue="
                    + store.retryQueue(10).size() + "|cents=" + store.unfulfilledCents();
            if (i == 0) {
                fromMemory = described;
            } else {
                fromMongo = described;
            }
        }
        assertThat(fromMongo).as("Mongo 侧必须与内存侧逐字段一致").isEqualTo(fromMemory);
        assertThat(fromMemory).contains("PAID_UNFULFILLED").contains("发货返回失败");
    }

    // ---------- 幂等键 ----------

    @Test
    @DisplayName("订单号重复：两套实现都在 insert 就拒绝，文案同一句")
    void duplicateOrderIdIsRejectedByBothStores() {
        for (PayOrderStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insert(order("o-dup", "P-dup", "pack_monthly", 600L));
            assertThatThrownBy(() -> store.insert(order("o-dup", "P-other", "pack_monthly", 600L)))
                    .as("%s 同号重复登记等于两笔支付共用一个幂等键", label)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("订单号重复");
            assertThat(store.get("o-dup").playerId()).as("%s 被拒的那笔不许覆盖原单", label)
                    .isEqualTo("P-dup");
        }
    }

    @Test
    @DisplayName("save 一个不存在的订单号：两套实现都抛，不静默插入")
    void saveOfUnknownOrderIsRejectedByBothStores() {
        for (PayOrderStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            assertThatThrownBy(() -> store.save(order("o-ghost", "P-ghost", "pack_monthly", 600L)))
                    .as("%s 静默插入会让 insert 的唯一性检查变成摆设", label)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("订单不存在，无法更新");
            assertThat(store.get("o-ghost")).as("%s 抛错之后表里也不该多出这一单", label).isNull();
        }
    }

    // ---------- 过期 ----------

    @Test
    @DisplayName("超时作废只关从未确认收款的单，且作废是转 CANCELLED 而不是删记录")
    void expiryClosesOnlyNeverConfirmedOrdersAndNeverDeletes() {
        for (PayOrderStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insert(order("o-zombie", "P-zombie", "pack_monthly", 600L));
            store.insert(order("o-fresh", "P-fresh", "pack_monthly", 600L, T0 + TTL - 60_000L));
            store.insert(order("o-paid", "P-paid", "fund_growth", 3000L));
            PayOrder paid = store.get("o-paid");
            paid.confirmCallback("txn-paid", true, T0 + 1_000L);
            store.save(paid);

            int closed = store.expireUnpaid(TTL, T0 + TTL + 1L);

            assertThat(closed).as("%s 只该关掉那一单真僵尸", label).isEqualTo(1);
            assertThat(store.get("o-zombie").status()).as("%s 作废转 CANCELLED", label)
                    .isEqualTo(PayOrder.Status.CANCELLED);
            assertThat(store.get("o-zombie").paidAt()).as("%s 作废没有付款，paidAt 必须还是 0", label).isZero();
            assertThat(store.get("o-fresh").status()).as("%s 没到期的不许动", label)
                    .isEqualTo(PayOrder.Status.PENDING);
            assertThat(store.get("o-paid").status()).as("%s 已收款的是负债，不在作废范围内", label)
                    .isEqualTo(PayOrder.Status.PAID_UNFULFILLED);
            assertThat(store.get("o-zombie")).as("%s 作废不是删除：删了迟到回调会变成「订单不存在」", label)
                    .isNotNull();
            assertThat(store.unfulfilledCents()).as("%s 负债不受作废影响", label).isEqualTo(3000L);
            assertThat(store.unfulfilledOrderCount()).as("%s 三单里只有 o-paid 是负债：作废单与未付单都不该算进来，"
                    + "这条钉住笔数与总额用的是同一个判据", label).isEqualTo(1L);
            assertThat(store.expireUnpaid(TTL, T0 + TTL + 1L)).as("%s 重复清扫必须是 0", label).isZero();
        }
    }

    @Test
    @DisplayName("作废后迟到的成功回调重开订单，并且只发一次货")
    void lateCallbackAfterExpiryReopensAndStillDeliversExactlyOnce() {
        for (PayOrderStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insert(order("o-late", "P-late", "pack_monthly", 600L));
            assertThat(store.expireUnpaid(TTL, T0 + TTL + 1L)).as("%s 先把这单作废", label).isEqualTo(1);

            PayOrder reopened = store.get("o-late");
            PayOrder.CallbackOutcome outcome = reopened.confirmCallback("txn-late", true, T0 + TTL + 2_000L);
            assertThat(outcome.firstTime()).as("%s 迟到的成功回调必须被接受", label).isTrue();
            assertThat(outcome.reopened()).as("%s 重开要报出来（WARN 的依据）", label).isTrue();
            store.save(reopened);

            assertThat(store.get("o-late").status()).as("%s 重开后是负债态而不是直接成功", label)
                    .isEqualTo(PayOrder.Status.PAID_UNFULFILLED);

            int delivered = 0;
            for (int attempt = 0; attempt < 3; attempt++) {
                PayOrder order = store.get("o-late");
                // 重复回调打到已在发货流程里的单：幂等闸门必须挡住，且不改状态
                order.confirmCallback("txn-late-2", true, T0 + TTL + 3_000L + attempt);
                if (order.fulfill(() -> true, 3, T0 + TTL + 3_000L + attempt).delivered()) {
                    delivered++;
                }
                store.save(order);
            }
            assertThat(delivered).as("%s 三次回调只许发一次货（多次发货可直接套现）", label).isEqualTo(1);
            assertThat(store.get("o-late").callbackCount()).as("%s 回调次数要如实累计", label).isEqualTo(4);
            assertThat(store.retryQueue(10)).as("%s 已发货，补单队列不该再有它", label).isEmpty();
        }
    }

    // ---------- 逐字段 ----------

    @Test
    @DisplayName("每一个字段都要原样回来：漏一个的代价是钱收了而订单记不下")
    void everyFieldSurvivesTheRoundTrip() {
        for (PayOrderStore store : bothStores()) {
            store.insert(order("o-all", "P-all", "skin_legendary", 1999L, 5_000L, 3));
            PayOrder order = store.get("o-all");
            order.confirmCallback("txn-3", false, T0 + 10L);   // 失败回调：callbackCount 也要累计
            order.fulfill(() -> false, 7, T0 + 20L);            // 状态不允许发货，但尝试次数不动
            store.save(order);

            PayOrder reread = store.get("o-all");
            assertThat(reread.snapshot().orderId()).isEqualTo("o-all");
            assertThat(reread.snapshot().playerId()).isEqualTo("P-all");
            assertThat(reread.snapshot().productId()).isEqualTo("skin_legendary");
            assertThat(reread.snapshot().count()).as("数量：漏了就是发一份货收三份钱").isEqualTo(3);
            assertThat(reread.snapshot().unitPriceCents()).isEqualTo(1999L);
            assertThat(reread.snapshot().createdAt()).isEqualTo(5_000L);
            assertThat(reread.snapshot().callbackCount()).as("回调次数是渠道超时重试的信号").isEqualTo(1);
            assertThat(reread.snapshot().status()).isEqualTo(PayOrder.Status.FAILED);
            assertThat(reread.snapshot().failureReason()).isEqualTo("支付平台未确认收款");
            assertThat(describe(reread)).as("逐字段串起来比：任何一侧少一个字段都会在这里红")
                    .isEqualTo("o-all|P-all|skin_legendary|3|1999|5000|FAILED|txn=null|cb=1|fa=0"
                            + "|paid=0|ful=0|reason=支付平台未确认收款");
        }
    }

    @Test
    @DisplayName("补单队列按 limit 分页：两套实现都不得一次全量返回")
    void retryQueueIsBoundedByLimitOnBothStores() {
        for (PayOrderStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            for (int i = 0; i < 5; i++) {
                String id = "o-q" + i;
                store.insert(order(id, "P-q", "pack_monthly", 600L));
                PayOrder order = store.get(id);
                order.confirmCallback("txn-q" + i, true, T0 + i);
                store.save(order);
            }
            assertThat(store.retryQueue(2)).as("%s 一次取 2 条就该只有 2 条", label).hasSize(2);
            assertThat(store.retryQueue(99)).as("%s 只要 5 条负债，队列不该虚高", label).hasSize(5);
            assertThat(store.unfulfilledCents()).as("%s 5 × 600", label).isEqualTo(3000L);
        }
    }

    /**
     * 索引由生产的 {@code MongoIndexes.ensure} 建（{@link TestMongo} 跑的是同一份，不是抄件）。
     * 缺它的后果不是报错而是慢：每一次下单都会触发一次超时清扫，而支付表是唯一
     * "只作废不删除"、只会变长的表 —— 没有索引就是每个下单请求整表扫一遍。
     */
    @Test
    @DisplayName("支付表必须带 (state.status, state.createdAt) 索引：三条查询都靠它")
    void payOrderHasTheStatusAndCreatedAtCompoundIndex() {
        requireMongo();
        List<String> shapes = new ArrayList<>();
        db.template().getCollection(com.ironoath.web.store.mongo.PayOrderDocument.COLLECTION)
                .listIndexes().forEach(info -> shapes.add(info.get("key").toString()));
        assertThat(shapes).as("现有索引：" + shapes)
                .anySatisfy(shape -> assertThat(shape).contains("state.status")
                        .contains("state.createdAt"));
    }

    @Test
    @DisplayName("Mongo 必须真的可达：否则「负债落得住」这句话今天没有被验证过")
    void mongoMustBeReachableOrTheClaimIsUnverified() {
        Assumptions.assumeTrue(db != null,
                "跳过即未验证：支付订单的内存/Mongo 等价性没有被检查。"
                        + "补跑方式：起一个本地 MongoDB，或 -Dironoath.test.mongo.uri=... 指向一台");
    }

    /**
     * 同一个非法调用在两侧必须是同一种失败。内存登记簿原先 {@code insert(null)} 直接 NPE
     * （{@code order.orderId()} 炸在 LinkedHashMap 之前），而 Mongo 版抛的是带原因的
     * IllegalArgumentException —— 断言写在哪一侧，换另一侧就会吃到意外的异常类型。
     */
    @Test
    @DisplayName("null 订单：两侧同一个异常同一句话；null 订单号读成「查不到」而不是抛")
    void nullOrderIsRejectedIdenticallyOnBothStores() {
        for (PayOrderStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            assertThatThrownBy(() -> store.insert(null))
                    .as("%s 建档 null 必须被拒", label).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.save(null))
                    .as("%s 写回 null 同样", label).isInstanceOf(IllegalArgumentException.class);
            assertThat(store.get(null)).as("%s null 订单号读成 null 而不是抛", label).isNull();
            assertThatThrownBy(() -> store.retryQueue(0))
                    .as("%s limit 必须 >= 1（两侧同一条，否则补单任务会静默拿到全表）", label)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        List<String> messages = new ArrayList<>();
        bothStores().forEach(store -> messages.add(failureOf(store)));
        assertThat(messages.get(1)).as("两侧异常类型与文案必须逐字相同").isEqualTo(messages.get(0));
    }

    private static String failureOf(PayOrderStore store) {
        try {
            store.insert(null);
            return "<没有抛异常>";
        } catch (RuntimeException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    // ---------- 夹具 ----------

    /** 内存版每条用例新建实例，Mongo 版共用同一个库。 */
    private List<PayOrderStore> bothStores() {
        requireMongo();
        return List.of(new PayOrder.Registry(), newMongoStore());
    }

    private static MongoPayOrderStore newMongoStore() {
        return new MongoPayOrderStore(db.template());
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 见「跳过即未验证」那条");
    }

    private static PayOrder order(String orderId, String playerId, String productId, long unitCents) {
        return order(orderId, playerId, productId, unitCents, T0, 1);
    }

    private static PayOrder order(String orderId, String playerId, String productId, long unitCents,
                                  long createdAt) {
        return order(orderId, playerId, productId, unitCents, createdAt, 1);
    }

    @Test
    @DisplayName("本月已花只算「本月内且确认收款」的那笔：跨月与未付款都不占额（两套实现同一个数）")
    void monthlySpendCountsOnlyConfirmedPaymentsInsideTheWindow() {
        long monthStart = com.ironoath.common.time.MonthKey.startMillis(T0);
        String playerId = "P-spend";
        for (PayOrderStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insert(confirmed("o-in-month", playerId, 600L, monthStart + 60_000L));
            store.insert(confirmed("o-last-month", playerId, 1_200L, monthStart - 60_000L));
            store.insert(order("o-unpaid", playerId, "pack_monthly", 900L, T0, 1));

            assertThat(store.paidCentsSince(playerId, monthStart))
                    .as("%s：上月的 1200 分不该占本月额度，没付的 900 分更不该", label)
                    .isEqualTo(600L);
            assertThat(store.paidCentsSince(playerId, 0L))
                    .as("%s：把窗口放到 epoch 起点就应该看到两笔已付款", label)
                    .isEqualTo(1_800L);
            assertThat(store.paidCentsSince("P-nobody", monthStart))
                    .as("%s：额度是每个账号一份，别人的钱不算我的", label).isZero();
        }
    }

    /** 一笔「渠道已确认收款」的既成事实。直接按快照造，否则要先把领域方法绕一圈才能把 paidAt 放到月初之前。 */
    private static PayOrder confirmed(String orderId, String playerId, long cents, long paidAt) {
        return PayOrder.fromSnapshot(new PayOrder.Snapshot(orderId, playerId, "pack_monthly", 1,
                cents, paidAt - 1_000L, PayOrder.Status.SUCCESS, "txn-" + orderId,
                1, 1, paidAt, paidAt, null, null, null));
    }

    private static PayOrder order(String orderId, String playerId, String productId, long unitCents,
                                  long createdAt, int count) {
        return PayOrder.create(orderId, playerId,
                new PayOrder.Line(productId, count, unitCents, createdAt, null));
    }

    /** 逐字段描述：新增字段时必须在这里出现，否则"快照少带一个字段"就查不出来。 */
    private static String describe(PayOrder order) {
        PayOrder.Snapshot s = order.snapshot();
        return s.orderId() + "|" + s.playerId() + "|" + s.productId() + "|" + s.count()
                + "|" + s.unitPriceCents() + "|" + s.createdAt() + "|" + s.status()
                + "|txn=" + s.transactionId() + "|cb=" + s.callbackCount() + "|fa=" + s.fulfillAttempts()
                + "|paid=" + s.paidAt() + "|ful=" + s.fulfilledAt() + "|reason=" + s.failureReason();
    }

    /** 手写的一份期望值：只从内存侧比对的话，两边一起漏同一个字段照样绿。 */
    private static String describeMemoryReference() {
        return "o-debt|P-debt|pack_monthly|1|600|" + T0 + "|PAID_UNFULFILLED|txn=txn-debt|cb=1|fa=0"
                + "|paid=" + (T0 + 1_000L) + "|ful=0|reason=null";
    }
}
