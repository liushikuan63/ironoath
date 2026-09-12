package com.ironoath.core.lock;

/**
 * 职责：玩家级互斥锁端口 —— B00 陷阱 3「并发扣资源禁止先查后改」的抽象侧。
 * 依赖：无（纯接口）。
 *
 * <p>为什么需要它：升级建筑要「读存档 → 校验 → 扣资源 → 写存档」四步，
 * 两个并发请求同时读到同一份存档就会双扣或双升级。B03 验收 2 要求
 * 「并发 10 个同一建筑升级请求，只有 1 个成功」，靠的就是这把锁加上乐观锁双保险。
 *
 * <p>两套实现：
 * <ul>
 *   <li>单实例（dev / 单测）：JVM 内按 key 分段的可重入锁</li>
 *   <li>多实例（prod）：Redisson 分布式锁，key = playerId</li>
 * </ul>
 * <b>JVM 内锁在多实例部署下无效</b> —— 它只能防住同进程内的并发。
 * 生产必须用 Redisson 实现，否则水平扩容后并发扣资源的漏洞会重新出现。
 */
public interface PlayerLock {

    /**
     * 在锁内执行一段逻辑。
     *
     * @param playerId 锁粒度为玩家级：同一玩家的请求串行，不同玩家完全并行。
     *                 锁全局会让整个服务端串行化，锁太细（如按建筑）则挡不住跨建筑的资源竞争
     * @param timeoutMs 获取锁的超时。超时抛异常而不是无限等待 ——
     *                  无限等待会让一个卡住的请求把整条线程池拖死
     * @param action    锁内执行的业务逻辑
     * @return action 的返回值
     * @throws LockTimeoutException 在 timeoutMs 内未拿到锁
     */
    <T> T runLocked(String playerId, long timeoutMs, java.util.function.Supplier<T> action);

    /** 获取锁超时。客户端应重试；这不是服务端故障，而是该玩家有请求正在处理。 */
    class LockTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public LockTimeoutException(String playerId, long timeoutMs) {
            super("获取玩家锁超时：playerId=" + playerId + ", timeoutMs=" + timeoutMs);
        }
    }
}
