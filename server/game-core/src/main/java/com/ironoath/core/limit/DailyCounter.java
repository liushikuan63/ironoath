package com.ironoath.core.limit;

/**
 * 职责：按日计数端口 —— 用于「广告加速每日 N 次」这类日限次校验（B03 §3）。
 * 依赖：无（纯接口）。
 *
 * <p>为什么抽成端口：日限次的存储位置随部署形态变化 —— 单实例可以用进程内 Map，
 * 多实例必须放 Redis（否则每个实例各算各的，玩家能刷到 N × 实例数 次）。
 * 玩法逻辑只依赖本接口，换存储不需要改业务代码。
 *
 * <p>{@code dayKey} 由调用方按服务端时区算出（如 {@code 20260906}），
 * 端口本身不理解日期语义 —— 这样跨天重置由 key 变化自然实现，不需要任何定时清理任务
 * （B00 陷阱 2：禁止用定时器扫表）。
 *
 * <p><b>既然端口不理解日期，这个参数就是一个「周期标签」而不是字面上的日</b>：
 * 日限次传 {@code DayKey.of(now)}，周限次传 {@code WeekKey.of(now)}（商店限购两种都有，
 * 见 {@code ShopAppService}），永久额度传一个固定串。两种共用同一个计数器，
 * 因为「按周期标签计数 + 到点换键」这件事只有一份实现，而日与周都必须按同一个 UTC+8 口径切
 * （{@code WeekKey} 与 {@code DayKey} 共用同一个 {@code CALENDAR_ZONE} 常量就是这个道理）。
 *
 * <p><b>这条泛化换来的唯一约束在实现方</b>：存储的保留时长必须<b>不短于</b>最长的周期。
 * 键里已经带了周期标签，所以留久了只会晚点回收；留短了则会在周期内先过期，
 * 表现是「限购提前刷新」—— 那就是多发，而多发在商店与付费道具上是能直接刷出资产的。
 */
public interface DailyCounter {

    /**
     * 尝试占用一次配额。
     *
     * <p>实现必须<b>原子</b>：并发的两次调用不能都拿到同一个名额，
     * 否则「每日 5 次」在并发下会变成 10 次。用 Redis 的 INCR + 首次设置过期，
     * 或进程内的 compute，都不要用「先 GET 再 SET」。
     *
     * @param scope   计数域，如 {@code ad_speedup}
     * @param ownerId 归属者，通常是 playerId
     * @param dayKey  日期键，如 {@code 20260906}
     * @param limit   当日上限
     * @return true 表示占用成功；false 表示当日已达上限
     */
    boolean tryConsume(String scope, String ownerId, String dayKey, long limit);

    /** 查询当日已用次数，用于给客户端显示「今日剩余 N 次」。 */
    long used(String scope, String ownerId, String dayKey);

    /**
     * 退还一次配额。
     *
     * <p>只在「占用成功但业务失败且无副作用」时调用，否则玩家会因为一次失败白白损失当日次数。
     */
    void refund(String scope, String ownerId, String dayKey);
}
