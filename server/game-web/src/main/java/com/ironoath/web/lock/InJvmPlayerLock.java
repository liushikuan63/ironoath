package com.ironoath.web.lock;

import com.ironoath.core.lock.PlayerLock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 职责：玩家锁的<b>单实例</b>实现 —— JVM 内按 playerId 分段的可重入锁。
 * 依赖：game-core 的 PlayerLock 端口。
 *
 * <p>⚠️ <b>多实例部署下本实现无效</b>：它只能防住同进程内的并发。
 * 生产环境必须换成 Redisson 分布式锁（{@code ironoath.lock=redisson}），
 * 否则水平扩容后「并发扣资源」的漏洞会原样复现。
 * 这里保留内存实现是为了 dev 零依赖启动与单测可控 —— 与 InMemoryPlayerStore 同一个理由。
 *
 * <p>锁对象按 playerId 缓存在 ConcurrentHashMap 里且<b>不清理</b>：
 * 玩家数量级是百万，每个 ReentrantLock 约 48 字节，全量常驻约 50MB，可接受；
 * 而做清理就要引入引用计数或弱引用，一旦清掉正在使用的锁，两个请求会拿到不同的锁对象，
 * 互斥直接失效 —— 那是比内存占用严重得多的问题。
 */
public final class InJvmPlayerLock implements PlayerLock {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T runLocked(String playerId, long timeoutMs, Supplier<T> action) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (timeoutMs <= 0L) {
            throw new IllegalArgumentException("timeoutMs 必须为正数，实际=" + timeoutMs);
        }
        if (action == null) {
            throw new IllegalArgumentException("action 不得为 null");
        }
        // computeIfAbsent 保证同一 playerId 永远拿到同一个锁对象
        ReentrantLock lock = locks.computeIfAbsent(playerId, k -> new ReentrantLock());
        boolean acquired;
        try {
            acquired = lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockTimeoutException(playerId, timeoutMs);
        }
        if (!acquired) {
            throw new LockTimeoutException(playerId, timeoutMs);
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** 当前持有的锁对象数量，用于监控与单测。 */
    public int lockCount() {
        return locks.size();
    }
}
