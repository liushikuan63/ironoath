package com.ironoath.core.pay;

import java.util.List;

/**
 * 职责：支付订单的存储端口 —— 内存登记簿与真存储（Mongo/Redis）共用同一套约定。
 * 依赖：无（纯接口）。
 *
 * <p><b>为什么要在这个端口上把"写回"写成显式动作</b>：内存实现把订单对象本身存在表里，
 * {@link #get} 返回的就是那个实例，所以"回调里改完状态不写回"在内存版上<b>看不出来</b>；
 * 换成任何真存储后读出来的是副本，不写回就是整笔丢失 —— 而丢的正好是
 * "钱已经收了、货还没发" 那笔负债（{@link PayOrder.Status#PAID_UNFULFILLED}）。
 * 收口清单 #22 记的三处就地改写就是这个形状。
 *
 * <p>因此本端口的契约是：<b>任何状态迁移之后必须调用 {@link #save}</b>。
 * 这条契约不是靠注释维持的，{@code PayOrderStoreEquivalenceTest} 会把同一组操作分别跑在
 * 内存与 Mongo 上，再各自<b>重新读一遍</b>比对 —— 少一次 save，Mongo 侧就会红。
 *
 * <p>幂等键仍然是 {@code orderId}：{@link #insert} 撞到已有订单号必须失败，
 * 因为"两笔支付共用一个幂等键"等于没有幂等（验收 2 要防的就是重复发货）。
 */
public interface PayOrderStore {

    /**
     * 按订单号取订单。
     *
     * @return 不存在时返回 null（调用方要把它当成"订单不存在"，见 {@code PayAppService#callback}
     *         —— 未知订单号既不能发货，又必须留痕，因为也可能是我们自己丢了订单）
     */
    PayOrder get(String orderId);

    /**
     * 登记一笔新订单。
     *
     * @throws IllegalStateException 订单号已存在。这不是异常情况：orderId 是回调幂等的唯一键，
     *                               同号重复登记意味着两笔支付共用一个幂等键
     */
    void insert(PayOrder order);

    /**
     * 写回一笔订单（新建之外的每次状态迁移都要调）。
     *
     * <p>实现必须是<b>整单替换</b>而不是"更新变化的字段"：调用方持有的可能就是它自己改过的那个对象
     * （内存实现），字段级 diff 在这里既做不到也没有意义。
     */
    void save(PayOrder order);

    /** 补单队列：按需要重试的状态取前 {@code limit} 条（分页，禁止全量）。 */
    List<PayOrder> retryQueue(int limit);

    /** 未发货的负债总额（分）。对账用：这个数必须最终归零。 */
    long unfulfilledCents();

    /**
     * 未发货的订单<b>笔数</b>。与总额一起看才分得清「一笔大的」和「一堆小的」这两种成因 ——
     * 只回总额，运维就只能猜。
     *
     * <p><b>刻意不复用 {@code retryQueue(limit).size()}</b>：那个队列受 limit 截断，
     * 拿它当总数等于让运维以为列出来的就是全部。
     *
     * <p>与 {@link #unfulfilledCents()} 是两次独立扫描，所以两者之间如果恰好有一单被补发，
     * 读数会短暂地不自洽。这是一个给人看的读数，不是一本要平的账 —— 真要平账的人看的是订单本身。
     */
    long unfulfilledOrderCount();

    /**
     * 某个玩家自 {@code sinceMillis}（含）以来<b>真正付掉</b>的钱（分）。
     * 未成年月度付费限额用它算"本月已花"，月界由 {@code MonthKey.startMillis} 给。
     *
     * <p>只算 {@link PayOrder#paymentConfirmed()} 为真的单：限额管的是"钱花出去了"，
     * 下了单没付以及被渠道拒绝的那些都不该占用额度 —— 算进去会表现为
     * "玩家只是点了几次支付没付钱，就把自己这个月锁死了"。
     *
     * <p><b>刻意不再建一份"月度账本"</b>：订单表本身就是"这个人付过多少钱"的唯一事实，
     * 另起一个累加器就会在退款、订单重开（CANCELLED 可被成功回调重开）、跨月刷新这三处
     * 各自漏算一次，而少算的后果是"多花了一遍钱" —— 那正是要被审核抓的。
     */
    long paidCentsSince(String playerId, long sinceMillis);

    /**
     * 把超过 {@code ttlMillis} 仍未支付的订单作废。
     *
     * <p>只能关"从未确认过收款"的订单；已付款未发货的是负债，不在作废范围内。
     * 另外作废<b>不是删除</b> —— 删掉之后迟到的成功回调会在 {@code get} 上变成"订单不存在"，
     * 那条路径记的是「疑似伪造回调」的 ERROR，等于把一次正常的弱网迟到伪装成攻击。
     *
     * @return 本次真的关掉的单数（0 表示没有可作废的，日志据此决定要不要出声）
     */
    int expireUnpaid(long ttlMillis, long now);
}
