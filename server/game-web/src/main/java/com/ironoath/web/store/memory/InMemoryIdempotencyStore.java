package com.ironoath.web.store.memory;

import com.ironoath.core.idempotency.IdempotencyStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 职责：幂等存储的内存实现 —— 供 dev 与单测使用。
 * 依赖：game-core 的 IdempotencyStore 端口。
 *
 * <p>与 Mongo 实现语义一致：{@link #tryAcquire} 原子占用，过期后可重新占用。
 * 过期判定用调用方传入的 {@code nowMs}，不读系统时钟 —— 这样单测能直接把时间推进到过期之后，
 * 不需要真的 sleep 24 小时。
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    /** requestId → 过期时间戳（毫秒）。 */
    private final Map<String, Long> expireAtByRequestId = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String requestId, long nowMs, long ttlMs) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId 不得为空");
        }
        if (ttlMs <= 0L) {
            throw new IllegalArgumentException("ttlMs 必须为正数，实际=" + ttlMs);
        }
        long expireAt = nowMs + ttlMs;
        // compute 是原子的：把「检查是否过期」与「占用」合并成一次操作，避免并发下两个请求都判定为可占用
        boolean[] acquired = new boolean[1];
        expireAtByRequestId.compute(requestId, (key, existing) -> {
            if (existing == null || existing <= nowMs) {
                acquired[0] = true;
                return expireAt;
            }
            acquired[0] = false;
            return existing;
        });
        return acquired[0];
    }

    @Override
    public void release(String requestId) {
        if (requestId != null) {
            expireAtByRequestId.remove(requestId);
        }
    }

    /** 测试辅助：清掉所有已过期的键，模拟 Mongo TTL 索引的后台回收。 */
    public int purgeExpired(long nowMs) {
        int before = expireAtByRequestId.size();
        expireAtByRequestId.entrySet().removeIf(e -> e.getValue() <= nowMs);
        return before - expireAtByRequestId.size();
    }

    public int size() {
        return expireAtByRequestId.size();
    }
}
