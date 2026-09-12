package com.ironoath.web.lock;

import com.ironoath.core.lock.PlayerLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 职责：玩家锁的<b>多实例</b>实现 —— Redisson 分布式锁（B00 陷阱 3 要求的方案）。
 * 依赖：Redisson、game-core 的 PlayerLock 端口。
 *
 * <p>这是生产环境唯一正确的实现。{@link InJvmPlayerLock} 只能防住同进程内的并发，
 * 水平扩容到两个实例后，同一玩家的两个请求会落到不同进程、各自拿到自己的 JVM 锁，
 * 互斥直接失效 —— 并发扣资源的漏洞原样复现。
 *
 * <p>锁粒度是 playerId：同一玩家的请求串行，不同玩家完全并行。
 * 锁全局会让整个服务端串行化；锁太细（如按建筑）挡不住跨建筑的资源竞争，
 * 因为升级 A 建筑和升级 B 建筑扣的是同一个资源池。
 *
 * <p>看门狗：不传 leaseTime，让 Redisson 的看门狗自动续期（默认 30 秒，每 10 秒续一次）。
 * 显式传 leaseTime 会关闭看门狗，一旦业务执行超过租期，锁会在业务还在跑的时候被别人拿走，
 * 两个请求同时改同一份存档 —— 这比不加锁更危险，因为它看起来是加了锁的。
 */
public final class RedissonPlayerLock implements PlayerLock {

    private static final Logger LOG = LoggerFactory.getLogger(RedissonPlayerLock.class);

    /** 锁键前缀。带前缀便于在 Redis 里按模式排查与清理。 */
    private static final String KEY_PREFIX = "ironoath:lock:player:";

    private final RedissonClient redisson;

    public RedissonPlayerLock(RedissonClient redisson) {
        if (redisson == null) {
            throw new IllegalArgumentException("RedissonClient 不得为 null");
        }
        this.redisson = redisson;
    }

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
        RLock lock = redisson.getLock(KEY_PREFIX + playerId);
        boolean acquired;
        try {
            // 不传 leaseTime ⇒ 启用看门狗自动续期，见类注释
            acquired = lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockTimeoutException(playerId, timeoutMs);
        }
        if (!acquired) {
            LOG.warn("获取玩家分布式锁超时 playerId={} timeoutMs={}（该玩家有请求正在处理，客户端应重试）",
                    playerId, timeoutMs);
            throw new LockTimeoutException(playerId, timeoutMs);
        }
        try {
            return action.get();
        } finally {
            // 只有当前线程仍持有锁时才解锁：租期意外失效后锁可能已属于别人，
            // 此时解锁会释放别人的锁，导致第三方请求也进来
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            } else {
                LOG.error("玩家锁在业务执行期间丢失（看门狗续期失败或 Redis 抖动）playerId={}，"
                        + "本次写入可能与其它请求冲突，请检查乐观锁是否兜住", playerId);
            }
        }
    }
}
