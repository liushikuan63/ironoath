package com.ironoath.web.store.mongo;

import org.springframework.data.annotation.Id;

import java.util.Date;

/**
 * 职责：requestId 幂等键的 MongoDB 文档。
 * 依赖：Spring Data 注解。
 *
 * <p>{@code _id} 直接用 requestId —— 这样「占用」就是一次插入，
 * MongoDB 的主键唯一性天然提供了原子性，不需要额外的分布式锁。
 *
 * <p>{@code expireAt} 上建 TTL 索引，让 MongoDB 自动回收过期键，
 * 避免幂等表无限膨胀（24 小时窗口 × 全服请求量，不清理会很快撑爆）。
 *
 * @param requestId 幂等键，同时作为 _id
 * @param expireAt  过期时刻，TTL 索引依据
 * @param createdAt 占用时刻，用于排查「某请求是什么时候被处理的」
 */
public record RequestIdDocument(
        @Id String requestId,
        Date expireAt,
        Date createdAt) {

    /** 集合名。 */
    public static final String COLLECTION = "request_id";
}
