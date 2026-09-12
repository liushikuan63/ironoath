package com.ironoath.core.march;

import java.util.List;

/**
 * 职责：行军到期队列端口 —— <b>B07 头号红线的落地方式</b>。
 * 依赖：无。
 *
 * <p><b>为什么是「拉」而不是「推」</b>：接口只有一个 {@link #dueBefore}，没有任何回调注册。
 * 这是刻意的 —— 一旦接口上出现「注册一个到期回调」，实现方最自然的做法就是
 * 给每支行军开一个 {@code Timer} / {@code ScheduledExecutorService}，
 * 1000 支行军 = 1000 个定时器，必崩（B07 禁止项原文）。
 * 把接口设计成「问我要 now 之前到期的 id」，实现方就只有两条路：
 * <ol>
 *   <li>内存/Redis ZSET 按到期时刻排序，被问到时扫出前面的若干个（dev/test 与 B07 明写的方案）</li>
 *   <li>Redisson {@code RDelayedQueue}：它内部只有<b>一个</b>队列与一个消费线程，
 *       不是每支行军一个定时器，所以不违反红线</li>
 * </ol>
 *
 * <p><b>到期由请求驱动，不由定时器驱动</b>：与产出结算同一套纪律
 * （B03 验收 9：服务端无任何常驻定时器）。生产环境可以加<b>一个</b>专用轮询线程
 * 定期调用 {@code dueBefore} —— 那是「全服一个轮询器」，
 * 与禁止项针对的「每支行军一个定时器」差三个数量级，不冲突。
 */
public interface MarchDueQueue {

    /**
     * 登记一个到期事件。重复登记同一 marchId 会覆盖旧的到期时刻。
     *
     * @param dueAtMillis 到期时刻（服务端毫秒时间戳）
     */
    void schedule(String marchId, long dueAtMillis);

    /**
     * 改期（加速、召回都会改到达时刻）。
     *
     * <p>必须是「改期」而不是「取消 + 重新登记」：后者在两步之间有一个窗口，
     * 此时如果正好有一次到期扫描，这支行军就会被漏掉（B07 验收 2：无漏触发）。
     */
    void reschedule(String marchId, long dueAtMillis);

    /** 撤销（行军到家、被歼灭后调用）。撤销不存在的 id 是合法的（幂等）。 */
    void cancel(String marchId);

    /**
     * 取出到期时刻 &lt;= now 的 marchId，按到期时刻升序。
     *
     * <p><b>本方法不删除记录</b>：删除由调用方在处理完之后显式做（{@link #cancel}）。
     * 如果取的时候就删，处理途中失败会让这支行军永远没人再处理 ——
     * 玩家看到一支停在半路、既不前进也不到家的队伍，而日志里没有任何线索。
     *
     * @param limit 单次最多返回多少个。1000 支同时到期时不能一次全处理完，
     *              否则一次请求的耗时会拖垮整个线程池；分批处理让每次请求的耗时可预测
     */
    List<String> dueBefore(long nowMillis, int limit);

    /** 队列中的事件总数，用于监控与单测。 */
    int size();
}
