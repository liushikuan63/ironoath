package com.ironoath.web.limit;

import com.ironoath.core.limit.DailyCounter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 职责：日计数器的<b>单实例</b>实现 —— 进程内 ConcurrentHashMap。
 * 依赖：game-core 的 DailyCounter 端口。
 *
 * <p>⚠️ 多实例部署下无效：每个实例各算各的，玩家能刷到 N × 实例数 次。
 * 生产必须用 {@link RedissonDailyCounter}。保留本实现是为了 dev 零依赖启动与单测可控。
 *
 * <p>不做过期清理：键名里含 dayKey，跨天后旧键自然不再被访问。
 * 常驻内存的量级是「活跃玩家数 × 计数域数」，按 10 万日活 × 5 个域 × 约 80 字节 ≈ 40MB，可接受。
 * 刻意不加定时清理任务 —— B00 陷阱 2 禁止用定时器做业务，
 * 而且清理任务本身会在每天零点造成一次全表扫描的尖峰。
 */
public final class InMemoryDailyCounter implements DailyCounter {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    private static String key(String scope, String ownerId, String dayKey) {
        return scope + "|" + ownerId + "|" + dayKey;
    }

    @Override
    public boolean tryConsume(String scope, String ownerId, String dayKey, long limit) {
        validate(scope, ownerId, dayKey, limit);
        AtomicLong counter = counters.computeIfAbsent(key(scope, ownerId, dayKey), k -> new AtomicLong(0L));
        // CAS 循环保证原子：并发的两次调用不会都拿到同一个名额
        while (true) {
            long current = counter.get();
            if (current >= limit) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1L)) {
                return true;
            }
        }
    }

    @Override
    public long used(String scope, String ownerId, String dayKey) {
        AtomicLong counter = counters.get(key(scope, ownerId, dayKey));
        return counter == null ? 0L : counter.get();
    }

    @Override
    public void refund(String scope, String ownerId, String dayKey) {
        AtomicLong counter = counters.get(key(scope, ownerId, dayKey));
        if (counter == null) {
            return;
        }
        // updateAndGet 而不是 decrementAndGet：不能减到负数，否则玩家能靠反复退款刷出额外配额
        counter.updateAndGet(v -> v <= 0L ? 0L : v - 1L);
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

    /** 测试辅助。 */
    public void clear() {
        counters.clear();
    }
}
