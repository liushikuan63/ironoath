package com.ironoath.core.idempotency;

/**
 * 职责：请求幂等存储端口 —— 保证同一 {@code requestId} 只被处理一次。
 * 依赖：无（纯接口）。
 *
 * <p>B00 Java 五大技术陷阱第 3 条：并发扣资源必须「分布式锁 + 事务 + requestId 幂等」。
 * 客户端断网重连会重放离线队列里的请求（B01 NetModule 能力），没有幂等就会重复扣资源、
 * 重复造兵、重复领奖。这是所有写接口的共同前置，因此抽成端口放在 game-core。
 *
 * <p>TTL 来自 contract/config/global.json 的 {@code REQUEST_ID_TTL_SECONDS}，
 * 必须大于客户端离线队列的最长滞留时间。
 */
public interface IdempotencyStore {

    /**
     * 尝试占用一个 requestId。
     *
     * <p>实现必须保证<b>原子性</b>：并发调用同一个 requestId 时只有一个返回 true。
     * 用 MongoDB 唯一索引或 Redis SETNX 实现，禁止「先查后写」。
     *
     * @param requestId 客户端生成的幂等键
     * @param nowMs     服务端当前时间戳，由调用方传入（不在实现内部读时钟，便于单测控制时间）
     * @param ttlMs     去重窗口，毫秒
     * @return true 表示首次占用成功，可以继续处理；false 表示重复请求，应直接返回上次结果或拒绝
     */
    boolean tryAcquire(String requestId, long nowMs, long ttlMs);

    /**
     * 释放占用。
     *
     * <p>只在「请求处理失败且不产生副作用」时调用，让客户端可以立刻重试。
     * 处理成功后<b>不要</b>释放 —— 那等于把幂等窗口关掉了。
     */
    void release(String requestId);
}
