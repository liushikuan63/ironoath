package com.ironoath.core.pay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：支付订单生命周期 —— 下单、回调幂等、发货失败进补单队列（B15 §2，验收 1/2/3）。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p><b>金额一律用「分」的 long</b>（禁止项：不要用 double 表示金额）。
 * 0.1 + 0.2 != 0.3 这件事在支付上是致命的：一次对账差一分钱，
 * 财务就无法证明账是平的，而「无法证明账是平的」在监管口径上等同于账不平。
 * 用 BigDecimal 也可以，但 long 分更快且没有精度陷阱 —— 本项目的定点数纪律（×10000）
 * 已经是同一套思路，金额不必再引入第二种数值类型。
 *
 * <p><b>回调幂等用 orderId 做唯一键</b>（验收 2、禁止项写了两遍）。
 * 微信的回调会在超时后重试，同一个订单收到 3 次回调是常态而不是异常；
 * 没有幂等的话就是发三次货，而那是可以直接套现的漏洞。
 *
 * <p><b>回调里不做重逻辑</b>（禁止项：快速落库后异步发货，避免微信回调超时重试）。
 * 所以本类把「确认支付」与「发放奖励」拆成两步：
 * {@link #confirmCallback} 只做状态迁移与落库，{@link #fulfill} 才发货，
 * 而发货失败进补单队列（验收 3）而不是让回调失败 ——
 * 回调失败会触发微信重试，重试又会撞上幂等键，于是订单永远停在「已支付未发货」。
 *
 * <p><b>四条支付路径都有明确终态</b>（验收 1）：
 * 断网 → PENDING（可由客户端主动查询或等回调）；
 * 支付取消 → CANCELLED（不进补单队列，因为根本没有支付发生）；
 * 下单了一直没付 → 超过 PAY_ORDER_TTL_HOURS 由读路径惰性作废，同样转 CANCELLED；
 * 支付成功但发货失败 → PAID + 补单队列（钱收了就必须发货，这是负债）。
 *
 * <p><b>「已作废」不等于「永远不收货」</b>：本类判重复回调的闸门是
 * <b>这笔订单有没有确认过收款</b>，不是「当前状态是不是 PENDING」。
 * 因为 pay.schema.json 写明 orderId 是幂等唯一键的理由正是
 * 「取消后重新支付会产生新的 transactionId，而那是同一笔订单」—— 所以
 * 「一条验签通过、带交易号的成功回调打到一单已作废的订单上」是<b>正常业务</b>，不是攻击。
 * 两种错法的后果也不对称：错在重开，最多是重复发货，而那已经被闸门挡住；
 * 错在不收，是「玩家付了钱什么都没拿到」，追不回来。
 * 所以过期只关生产侧的账（不再等这笔钱、订单表不被僵尸撑大），不关渠道侧的账。
 */
public final class PayOrder {

    /** 订单状态。 */
    public enum Status {
        /** 已下单，等待支付结果 */
        PENDING,
        /** 支付成功且已发货。唯一的良好终态 */
        SUCCESS,
        /** 支付成功但发货失败，已进补单队列。<b>这是一笔负债</b>：钱收了货没发 */
        PAID_UNFULFILLED,
        /**
         * 没有支付发生，订单关闭：玩家取消、或超过 PAY_ORDER_TTL_HOURS 未付而作废。
         * 不进补单队列 —— 没有支付就没有发货义务。
         * <b>但它不是不可逆的终态</b>：一条验签通过的成功回调可以把它重开（见类注释）。
         */
        CANCELLED,
        /** 支付平台明确拒绝（签名错误、订单不存在等） */
        FAILED
    }

    /**
     * "钱已经收到"的两个状态 —— <b>唯一的一份定义</b>。
     *
     * <p>领域侧的 {@link #paymentConfirmed()} 与存储侧按状态过滤的查询都必须用它：
     * 月度付费限额、负债总额这类查询如果在 Java 侧另抄一遍状态名，
     * 将来加一个"部分退款"之类的状态时，一侧改了另一侧没改，
     * 症状不是报错而是"玩家这个月的额度算少了" —— 那是要拿账单对质的数。
     */
    public static final java.util.EnumSet<Status> CONFIRMED_STATUSES =
            java.util.EnumSet.of(Status.SUCCESS, Status.PAID_UNFULFILLED);

    /** 给存储层查询用的状态名列表（Mongo 存的是 {@code name()}）。 */
    public static final List<String> CONFIRMED_STATUS_NAMES =
            List.copyOf(CONFIRMED_STATUSES).stream().map(Enum::name).toList();

    /**
     * @param productId       商品 id（shop 表的行 id）
     * @param count           购买数量
     * @param unitPriceCents  单价（分）。**来自服务端下发的价格表**，不写死在客户端（§2）
     * @param createdAt       下单时刻
     */
    public record Line(String productId, int count, long unitPriceCents, long createdAt) {
        public Line {
            if (productId == null || productId.isBlank()) {
                throw new IllegalArgumentException("productId 不得为空");
            }
            if (count < 1) {
                throw new IllegalArgumentException("购买数量必须 >= 1，实际=" + count);
            }
            if (unitPriceCents < 0) {
                throw new IllegalArgumentException("单价不得为负，实际=" + unitPriceCents);
            }
        }

        /** 总价（分）。用 long 乘法，不用 double。 */
        public long totalCents() {
            long total = unitPriceCents * count;
            if (count != 0 && total / count != unitPriceCents) {
                throw new ArithmeticException("总价溢出：单价 " + unitPriceCents + " × 数量 " + count
                        + "。溢出后金额会变成负数或一个小得离谱的值，"
                        + "而支付平台会照那个值扣款 —— 这是可以直接套现的漏洞");
            }
            return total;
        }
    }

    /**
     * 一次回调的结果。
     *
     * @param reopened 本单先因未付而关闭、这次回调把它重开。<b>调用方必须把它记下来</b>：
     *                 偶发是正常业务（取消后重付、弱网下迟到的回调），大量出现则说明
     *                 PAY_ORDER_TTL_HOURS 设得比支付时长还短。核心层不记日志，所以只能靠这个标志位传出去
     */
    public record CallbackOutcome(boolean firstTime, Status statusAfter, String reason, boolean reopened) {
    }

    /** 一次发货的结果。 */
    public record FulfillOutcome(boolean delivered, boolean queuedForRetry, String reason) {
    }

    private final String orderId;
    private final String playerId;
    private final Line line;
    private Status status;
    private String transactionId;
    /** 回调次数。埋点用：一个订单被回调几十次说明微信侧在超时重试，那是发货太慢的信号 */
    private int callbackCount;
    /** 发货尝试次数。补单队列按它决定还要不要重试 */
    private int fulfillAttempts;
    private long paidAt;
    private long fulfilledAt;
    private String failureReason;

    private PayOrder(String orderId, String playerId, Line line) {
        this.orderId = orderId;
        this.playerId = playerId;
        this.line = line;
        this.status = Status.PENDING;
    }

    /** 下单。 */
    public static PayOrder create(String orderId, String playerId, Line line) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId 不得为空：它是回调幂等的唯一键");
        }
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (line == null) {
            throw new IllegalArgumentException("line 不得为 null");
        }
        return new PayOrder(orderId, playerId, line);
    }

    /**
     * 支付回调（验收 2：同一订单重复回调只发货一次）。
     *
     * <p><b>幂等靠状态机而不是靠一张去重表</b>：闸门是「这笔订单确认过收款没有」，
     * 而「已确认收款」恰好就是 PAID_UNFULFILLED 与 SUCCESS 两个状态 —— 没有任何迁移能把它们
     * 退回未确认，所以这两个状态本身就是确认过的记录，不需要第二份真相。
     * 用去重表的话，表与订单状态会各自漂移；用「状态是不是 PENDING」做闸门的话，
     * 会把先作废后付款的单子静默丢掉（见类注释）。
     *
     * @param transactionId 支付平台的交易号。落库但不参与幂等判定 ——
     *                      微信重试时可能带不同的 transactionId，用它做键就挡不住重复
     * @param success       支付平台是否确认收款成功
     */
    public CallbackOutcome confirmCallback(String transactionId, boolean success, long now) {
        callbackCount++;
        if (paymentConfirmed()) {
            // 重复回调：照实返回「不是第一次」，不改动任何状态。
            // 抛错会让微信继续重试（它把非 2xx 当成失败），而重复回调本身不是错误
            return new CallbackOutcome(false, status, "订单已处于 " + status + "，本次回调被忽略", false);
        }
        if (!success) {
            // 只有还在等的订单需要被告知「没收到钱」。已经作废的订单本来就按没收钱处理，
            // 把它改成 FAILED 会让取消与渠道拒付两种关闭原因在账上混成一谈
            if (status != Status.PENDING) {
                return new CallbackOutcome(false, status, "订单已处于 " + status + "，本次回调被忽略", false);
            }
            status = Status.FAILED;
            failureReason = "支付平台未确认收款";
            return new CallbackOutcome(true, status, failureReason, false);
        }
        if (transactionId == null || transactionId.isBlank()) {
            // 收款成功却没有交易号：这是签名或数据被篡改的信号，宁可停在 PENDING 也不发货。
            // 停在 PENDING 的后果是玩家付了钱没拿到货，但那可以靠补单与客服解决；
            // 发货的后果是无法追回。已作废的订单同样不重开：没有交易号就没有可追讨的凭据
            String why = "回调缺少 transactionId，拒绝发货";
            if (status == Status.PENDING) {
                failureReason = why;
            }
            return new CallbackOutcome(false, status, why, false);
        }
        // 走到这里：渠道确认扣款 + 有交易号。作废过的订单照样重开 —— 钱是实的
        boolean reopened = status != Status.PENDING;
        this.transactionId = transactionId;
        this.paidAt = now;
        this.failureReason = null;
        // 先落「已支付未发货」，再由 fulfill 发货：回调里不做重逻辑（禁止项），
        // 否则微信回调超时会重试，重试又撞上幂等键，订单永远停在已支付未发货
        status = Status.PAID_UNFULFILLED;
        return new CallbackOutcome(true, status,
                reopened ? "订单关闭后又收到确认收款回调，已重新确认收款" : "支付已确认，等待发货",
                reopened);
    }

    /**
     * 这笔订单是否已确认过收款 —— 回调幂等的唯一判据。
     *
     * <p>刻意不新增一个布尔字段：PAID_UNFULFILLED 与 SUCCESS 只能由「确认收款」进入，
     * 也进不去别的状态，所以状态本身就带了这份信息。再加一个字段就是两份真相，
     * 而支付域最怕的正是这两份真相在某次改动后开始漂移。
     */
    public boolean paymentConfirmed() {
        return CONFIRMED_STATUSES.contains(status);
    }

    /**
     * 玩家取消支付（验收 1 的第二条路径）。
     *
     * <p>取消不进补单队列：没有支付发生就没有发货义务。
     * 把取消也塞进补单队列的话，队列里会全是永远不会成功的单子，
     * 而运维看到的就是「补单队列一直在涨」。
     */
    public CallbackOutcome cancel(long now) {
        if (status != Status.PENDING) {
            return new CallbackOutcome(false, status, "订单已处于 " + status + "，不能取消", false);
        }
        status = Status.CANCELLED;
        // 不写 paidAt：它是「支付完成时刻」，而取消根本没有支付。
        // 把关闭时刻塞进这个字段，对账时 paidAt != 0 就会看成「钱进来了」，
        // 而本类判幂等的依据正是「有没有一次真实的收款确认」
        failureReason = "玩家取消支付";
        return new CallbackOutcome(true, status, failureReason, false);
    }

    /**
     * 未支付订单超时作废（global.PAY_ORDER_TTL_HOURS，B15 §2）。
     *
     * <p><b>过期是转 CANCELLED，不是删除记录</b>：删掉之后迟到的回调会在订单表里查不到这一单，
     * 而那条路径记的是「伪造回调，需人工核查」的 ERROR —— 一次完全正常的弱网迟到回调
     * 会被伪装成一次攻击，然后没人再去追那笔钱。
     *
     * <p>由读路径惰性调用（与战报清理、行军到期同一套纪律，B00 陷阱 2：服务端不跑定时器）。
     *
     * @param ttlMillis 未支付订单的存活时长；<= 0 表示永不过期 —— 把 TTL 配成 0
     *                  的实际效果是「刚下的单在下一次任意请求里就作废」，那是配置写错而不是产品意图，
     *                  所以不照字面执行
     * @return firstTime=true 表示本次真的把这一单关掉了
     */
    public CallbackOutcome expireIfUnpaid(long ttlMillis, long now) {
        if (status != Status.PENDING) {
            return new CallbackOutcome(false, status,
                    paymentConfirmed() ? "已确认收款的订单不会因为超时而作废" : "订单已处于 " + status, false);
        }
        if (ttlMillis <= 0L || now - line.createdAt() < ttlMillis) {
            return new CallbackOutcome(false, status, "未到过期时间", false);
        }
        status = Status.CANCELLED;
        failureReason = "超过 PAY_ORDER_TTL_HOURS 未支付，订单作废";
        return new CallbackOutcome(true, status, failureReason, false);
    }

    /**
     * 发货（验收 3：发货失败进补单队列，重试后成功发货）。
     *
     * @param deliver 实际发货动作。<b>由调用方注入</b>（走 B04 的 RewardGrantor），
     *                本类不知道奖励系统长什么样 —— 那是分层要求，也让本类可以在单测里
     *                模拟「第一次失败、第二次成功」
     * @return 发货结果。失败时订单留在 PAID_UNFULFILLED，由补单队列重试
     */
    public FulfillOutcome fulfill(java.util.function.Supplier<Boolean> deliver, int maxAttempts, long now) {
        if (status == Status.SUCCESS) {
            return new FulfillOutcome(false, false, "已发货，重复调用被忽略");
        }
        if (status != Status.PAID_UNFULFILLED) {
            return new FulfillOutcome(false, false, "订单状态 " + status + " 不允许发货：只有已支付未发货的订单能发");
        }
        if (deliver == null) {
            throw new IllegalArgumentException("deliver 不得为 null");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 必须 >= 1，实际=" + maxAttempts);
        }
        fulfillAttempts++;
        boolean ok;
        try {
            ok = Boolean.TRUE.equals(deliver.get());
        } catch (RuntimeException e) {
            ok = false;
            failureReason = "发货抛异常：" + e.getMessage();
        }
        if (ok) {
            status = Status.SUCCESS;
            fulfilledAt = now;
            failureReason = null;
            return new FulfillOutcome(true, false, "发货成功");
        }
        boolean retry = fulfillAttempts < maxAttempts;
        if (failureReason == null) {
            failureReason = "发货返回失败";
        }
        // 失败也留在 PAID_UNFULFILLED：钱已经收了，这笔负债不能因为发货失败就消失。
        // 把它改成 FAILED 会让对账时看不出「这笔钱到底收没收到」
        return new FulfillOutcome(false, retry,
                retry ? "发货失败，已进补单队列（第 " + fulfillAttempts + " 次）"
                        : "发货失败且已达最大重试次数（" + maxAttempts + "），需人工介入");
    }

    /** 是否需要补单重试。 */
    public boolean needsRetry() {
        return status == Status.PAID_UNFULFILLED;
    }

    // ---------- 只读访问 ----------

    public String orderId() {
        return orderId;
    }

    public String playerId() {
        return playerId;
    }

    public Line line() {
        return line;
    }

    public Status status() {
        return status;
    }

    public String transactionId() {
        return transactionId;
    }

    public int callbackCount() {
        return callbackCount;
    }

    public int fulfillAttempts() {
        return fulfillAttempts;
    }

    public long paidAt() {
        return paidAt;
    }

    public long fulfilledAt() {
        return fulfilledAt;
    }

    public String failureReason() {
        return failureReason;
    }

    /**
     * 一笔订单的完整状态。
     *
     * <p><b>为什么需要它</b>：内存登记簿把订单对象本身存着，{@code get()} 返回的就是那个实例，
     * 所以"回调改完状态、不写回登记簿"在内存版上看不出来 —— 而换成任何真存储（Mongo/Redis）后，
     * 读出来的是副本，不显式写回就是<b>整笔丢失：钱收了、订单还停在 PENDING、补单队列里查不到</b>。
     * 收口清单 #22 记的那三处就地改写就是这么写的。有了快照，端口可以要求实现"必须写回"，
     * 并且这件事能被测试（见 {@code PayOrderStoreEquivalenceTest}）。
     *
     * @param callbackCount   回调次数：埋点用，一个订单被回调几十次说明渠道在超时重试
     * @param fulfillAttempts 发货尝试次数，补单队列据此决定是否还要重试
     */
    public record Snapshot(String orderId, String playerId, String productId, int count,
                           long unitPriceCents, long createdAt, Status status, String transactionId,
                           int callbackCount, int fulfillAttempts, long paidAt, long fulfilledAt,
                           String failureReason) {
    }

    public Snapshot snapshot() {
        return new Snapshot(orderId, playerId, line.productId(), line.count(),
                line.unitPriceCents(), line.createdAt(), status, transactionId, callbackCount,
                fulfillAttempts, paidAt, fulfilledAt, failureReason);
    }

    /**
     * 由快照重建订单。
     *
     * <p>走私有构造器而不是 {@link #create}：<code>create</code> 会把状态钉在 PENDING，
     * 而重建必须能还原任意状态（包括 PAID_UNFULFILLED 这笔没清掉的负债）。
     */
    public static PayOrder fromSnapshot(Snapshot s) {
        if (s == null) {
            throw new IllegalArgumentException("快照不得为 null：没有快照就没有重建");
        }
        if (s.orderId() == null || s.orderId().isBlank()) {
            throw new IllegalArgumentException("orderId 不得为空：它是回调幂等的唯一键");
        }
        PayOrder order = new PayOrder(s.orderId(), s.playerId(),
                new Line(s.productId(), s.count(), s.unitPriceCents(), s.createdAt()));
        order.status = s.status() == null ? Status.PENDING : s.status();
        order.transactionId = s.transactionId();
        order.callbackCount = s.callbackCount();
        order.fulfillAttempts = s.fulfillAttempts();
        order.paidAt = s.paidAt();
        order.fulfilledAt = s.fulfilledAt();
        order.failureReason = s.failureReason();
        return order;
    }

    /**
     * 补单队列：从一批订单里挑出需要重试的。
     *
     * <p><b>分页而不是全量</b>（与 B14 结算同一条纪律）：补单队列在支付平台故障时会瞬间涨到
     * 几千条，一次全量载入并逐条发货会让补单任务本身超时。
     *
     * @param orders 全部订单
     * @param limit  本次最多处理几条
     */
    public static List<PayOrder> retryQueue(List<PayOrder> orders, int limit) {
        if (orders == null) {
            throw new IllegalArgumentException("orders 不得为 null");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit 必须 >= 1，实际=" + limit);
        }
        List<PayOrder> out = new ArrayList<>();
        for (PayOrder order : orders) {
            if (order.needsRetry()) {
                out.add(order);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 简单的内存订单表 —— {@link PayOrderStore} 的内存实现，服务于单测与本地开发
     * （与其它 {@code InMemory*Store} 同一身份）。生产用 MongoDB（orderId 唯一索引）。
     */
    public static final class Registry implements PayOrderStore {
        private final Map<String, PayOrder> orders = new LinkedHashMap<>();

        public synchronized PayOrder put(PayOrder order) {
            if (order == null) {
                // 与 MongoPayOrderStore.insert 同一条：原先这里 order.orderId() 直接 NPE，
                // 而换到 Mongo 抛的是带原因的 IllegalArgumentException —— 同一个非法调用两种炸法，
                // 调用方按哪一侧写断言，到另一侧就会吃到意料之外的异常类型
                throw new IllegalArgumentException("订单不得为 null");
            }
            PayOrder previous = orders.putIfAbsent(order.orderId(), order);
            if (previous != null) {
                throw new IllegalStateException("订单号重复：" + order.orderId()
                        + "。orderId 是回调幂等的唯一键，重复就等于两笔支付共用一个幂等键");
            }
            return order;
        }

        @Override
        public void insert(PayOrder order) {
            put(order);
        }

        /**
         * 内存版里 this map 存的就是调用方改过的那个实例，所以这里只做存在性检查。
         *
         * <p>但调用方<b>必须照样调它</b>：这条写回在内存版是免费的，在真存储版是全部。
         * 少调一次的结果不是报错而是"那笔已付款订单从未被记录"，而唯一能发现它的地方是
         * 跨实现等价测试（{@code PayOrderStoreEquivalenceTest} 会在 Mongo 侧重新读一遍并比对）。
         */
        @Override
        public synchronized void save(PayOrder order) {
            if (order == null) {
                throw new IllegalArgumentException("待保存的订单不得为 null");
            }
            if (!orders.containsKey(order.orderId())) {
                throw new IllegalStateException("订单不存在，无法更新：orderId=" + order.orderId()
                        + "。先 insert 再 save —— 静默插进去会让重复幂等键绕过唯一性检查");
            }
        }

        @Override
        public synchronized PayOrder get(String orderId) {
            return orders.get(orderId);
        }

        public synchronized List<PayOrder> all() {
            return Collections.unmodifiableList(new ArrayList<>(orders.values()));
        }

        public synchronized List<PayOrder> retryQueue(int limit) {
            return PayOrder.retryQueue(all(), limit);
        }

        /**
         * 把超时未支付的订单作废，返回关掉的条数。
         *
         * <p>惰性调用（下单、查单、补单队列三个入口都会触发），不跑定时器（B00 陷阱 2）。
         * 与战报清理 {@code purgeExpired} 同一形状：<b>只改状态不删记录</b>，
         * 因为迟到的回调仍然要能在表里找到这一单。
         */
        public synchronized int expireUnpaid(long ttlMillis, long now) {
            int expired = 0;
            for (PayOrder order : orders.values()) {
                if (order.expireIfUnpaid(ttlMillis, now).firstTime()) {
                    expired++;
                }
            }
            return expired;
        }

        /** 未发货的负债总额（分）。对账用：这个数必须最终归零。 */
        public synchronized long unfulfilledCents() {
            long total = 0L;
            for (PayOrder order : orders.values()) {
                if (order.needsRetry()) {
                    total += order.line().totalCents();
                }
            }
            return total;
        }

        /**
         * 月度限额用的"本月已花"。按 {@code paidAt} 而不是 {@code createdAt} 算 ——
         * 月末 23:59 下单、月初 00:01 付掉的这笔，钱是下个月花的，占用下个月的额度。
         */
        @Override
        public synchronized long paidCentsSince(String playerId, long sinceMillis) {
            if (playerId == null || playerId.isBlank()) {
                throw new IllegalArgumentException("playerId 不得为空");
            }
            long total = 0L;
            for (PayOrder order : orders.values()) {
                if (playerId.equals(order.playerId()) && order.paymentConfirmed()
                        && order.paidAt() >= sinceMillis) {
                    total += order.line().totalCents();
                }
            }
            return total;
        }

        public synchronized void clear() {
            orders.clear();
        }
    }
}
