package com.ironoath.web.limit;

import com.ironoath.core.limit.DailyCounter;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.time.Duration;

/**
 * 职责：日计数器的<b>多实例</b>实现 —— Redisson 原子计数（生产用）。
 * 依赖：Redisson、game-core 的 DailyCounter 端口。
 *
 * <p>原子性靠 Redis 的 INCR：并发的两次调用会拿到不同的递增值，
 * 因此「先 INCR 再判断是否超限」是安全的 —— 超限就把刚加的那一次减回去。
 * 这比「先 GET 判断再 INCR」正确，后者在并发下会让多个请求同时通过检查。
 *
 * <p><b>过期时间必须不短于最长的计周期</b>：本实现现在同时服务两种周期标签 ——
 * 日口径（{@code DayKey}，如广告加速每日次数）与周口径（{@code WeekKey}，商店的 WEEKLY 限购）。
 * 键里已经带了周期标签，所以跨期时自然会换键，留久一点只是晚点回收；
 * <b>而留短了是会多东西的</b>：2 天的 TTL 落在周键上，等于每个周期内会提前刷新一次限购，
 * 表现是「本周只能换 1 次的道具一周能换好几次」—— 那是直接刷出资产。
 * 取 8 天 = 一个周周期 + 同样的时钟偏差余量。
 *
 * <p>反过来记一条已知边界：{@code refreshType=NONE} 的商品行是<b>永久</b>额度
 * （周期标签是一个固定串），而 Redis 这边它同样按 8 天回收 —— 也就是在
 * {@code ironoath.lock=redisson} 的多实例部署下，永久额度目前会 8 天重置一次。
 * 今天这不影响任何一行商品（全表唯一的 NONE 行是 SEASON_COIN 计价，而那一页尚未开放兑换，
 * 见 {@code ShopAppService} 的 CURRENCY_OPEN 判定），但要把 NONE 用于真实商品时，
 * 必须先把这类键改成不过期或按更长的保留期存放。
 */
public final class RedissonDailyCounter implements DailyCounter {

    private static final String KEY_PREFIX = "ironoath:daily:";
    private static final Duration TTL = Duration.ofDays(8);

    private final RedissonClient redisson;

    public RedissonDailyCounter(RedissonClient redisson) {
        if (redisson == null) {
            throw new IllegalArgumentException("RedissonClient 不得为 null");
        }
        this.redisson = redisson;
    }

    private static String key(String scope, String ownerId, String dayKey) {
        return KEY_PREFIX + scope + ":" + ownerId + ":" + dayKey;
    }

    @Override
    public boolean tryConsume(String scope, String ownerId, String dayKey, long limit) {
        validate(scope, ownerId, dayKey, limit);
        RAtomicLong counter = redisson.getAtomicLong(key(scope, ownerId, dayKey));
        long after = counter.incrementAndGet();
        if (after == 1L) {
            // 只有创建者设置 TTL：并发下多个请求都可能看到 after==1 之外的值，
            // 但只会有一个拿到 1，因此不会重复设置或漏设置
            counter.expire(TTL);
        }
        if (after > limit) {
            // 超限：把刚占用的名额退回去，否则计数会一直虚高，
            // 导致「今日已用次数」显示得比实际多
            counter.decrementAndGet();
            return false;
        }
        return true;
    }

    @Override
    public long used(String scope, String ownerId, String dayKey) {
        return redisson.getAtomicLong(key(scope, ownerId, dayKey)).get();
    }

    @Override
    public void refund(String scope, String ownerId, String dayKey) {
        RAtomicLong counter = redisson.getAtomicLong(key(scope, ownerId, dayKey));
        // 不减到负数：负计数会让玩家靠反复退款刷出额外配额。
        // RAtomicLong 没有 updateAndGet，用 CAS 循环实现同样的「读取-判断-写入」原子语义
        while (true) {
            long current = counter.get();
            if (current <= 0L) {
                return;
            }
            if (counter.compareAndSet(current, current - 1L)) {
                return;
            }
        }
    }

    private static void validate(String scope, String ownerId, String dayKey, long limit) {
        if (scope == null || scope.isBlank() || ownerId == null || ownerId.isBlank()
                || dayKey == null || dayKey.isBlank()) {
            throw new IllegalArgumentException("scope / ownerId / dayKey 都不得为空");
        }
        if (limit <= 0L) {
            throw new IllegalArgumentException("limit 必须为正数，实际=" + limit);
        }
    }
}
