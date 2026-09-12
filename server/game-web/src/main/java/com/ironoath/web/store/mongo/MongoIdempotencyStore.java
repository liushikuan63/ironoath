package com.ironoath.web.store.mongo;

import com.ironoath.core.idempotency.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.Date;

/**
 * 职责：幂等存储的 MongoDB 实现 —— 靠 {@code _id} 唯一性实现原子占用。
 * 依赖：Spring Data MongoDB、game-core 的 IdempotencyStore 端口。
 *
 * <p>为什么不用「先查后写」：两个并发请求会同时查到「不存在」，然后都认为自己拿到了幂等键，
 * 结果资源被扣两次。B00 陷阱 3 明确要求 requestId 幂等，实现必须是原子的。
 *
 * <p>TTL 索引每 60 秒才回收一次，所以过期键可能仍在表里。
 * {@link #tryAcquire} 在插入冲突后会显式检查 expireAt，过期就删掉重试一次，
 * 保证「客户端隔了一天重发同一 requestId」不会被误判为重复请求。
 */
public final class MongoIdempotencyStore implements IdempotencyStore {

    private static final Logger LOG = LoggerFactory.getLogger(MongoIdempotencyStore.class);

    private final MongoTemplate mongo;

    public MongoIdempotencyStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public boolean tryAcquire(String requestId, long nowMs, long ttlMs) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId 不得为空");
        }
        if (ttlMs <= 0L) {
            throw new IllegalArgumentException("ttlMs 必须为正数，实际=" + ttlMs);
        }
        RequestIdDocument doc = new RequestIdDocument(
                requestId, new Date(nowMs + ttlMs), new Date(nowMs));

        if (insertQuietly(doc)) {
            return true;
        }
        // 冲突：可能是真的重复请求，也可能是 TTL 还没来得及回收的过期键
        RequestIdDocument existing = mongo.findById(requestId, RequestIdDocument.class,
                RequestIdDocument.COLLECTION);
        if (existing != null && existing.expireAt() != null && existing.expireAt().getTime() <= nowMs) {
            LOG.info("幂等键已过期，回收后重新占用 requestId={} expireAt={} now={}",
                    requestId, existing.expireAt().getTime(), nowMs);
            mongo.remove(Query.query(Criteria.where("_id").is(requestId)),
                    RequestIdDocument.COLLECTION);
            return insertQuietly(doc);
        }
        LOG.info("重复请求，已拒绝 requestId={}", requestId);
        return false;
    }

    @Override
    public void release(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        mongo.remove(Query.query(Criteria.where("_id").is(requestId)), RequestIdDocument.COLLECTION);
    }

    /** 插入并吞掉主键冲突，返回是否插入成功。 */
    private boolean insertQuietly(RequestIdDocument doc) {
        try {
            mongo.insert(doc, RequestIdDocument.COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
