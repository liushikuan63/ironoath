package com.ironoath.web.store.mongo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.ironoath.web.reward.RewardCompensationStore;

/**
 * 职责：补偿台账的 MongoDB 实现（生产）。
 * 依赖：{@link MongoTemplate}、{@link RewardCompensationDocument}。
 *
 * <p><b>{@link #resolve} 是条件更新，不是「读一眼再写回去」</b>：
 * {@code updateFirst(_id 命中 且 entry.resolvedAt 为空, set 三个字段)} 由 Mongo 在单文档级别保证原子，
 * {@code modifiedCount == 1} 才是这一次处理权的赢家。两个人（客服与值班）同时点「已处理」时
 * 必然一个赢一个输，与节点数无关 —— 内存版那把 {@code synchronized} 替补不了这一半，
 * 因为多实例各有一把进程内锁。
 *
 * <p><b>读整份文档、不做字段投影</b>：收口清单 #61 的教训 —— 投影 {@code include("state.version")}
 * 时 Spring Data 仍要实例化整个嵌套 record，而嵌套 record 里的原始类型字段拿不到值就直接抛
 * {@code MappingInstantiationException}，把真实原因盖成一条看不懂的报错。
 * 这张表每次最多几百条，整份读不贵。
 */
public final class MongoRewardCompensationStore implements RewardCompensationStore {

    private final MongoTemplate mongo;

    public MongoRewardCompensationStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void save(Entry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("补偿记录不得为 null");
        }
        try {
            mongo.insert(RewardCompensationDocument.of(entry), RewardCompensationDocument.COLLECTION);
        } catch (DuplicateKeyException e) {
            // 幂等：同一条 compensationId 已在册就不覆盖。把已处理的那条盖回未处理，
            // 等于让一次重投悄悄抹掉别人的处理记录（内存版 putIfAbsent 同一个语义）
        }
    }

    @Override
    public List<Entry> pending(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<RewardCompensationDocument> docs = mongo.find(
                Query.query(pendingFilter())
                        .with(Sort.by(Sort.Order.asc(RewardCompensationDocument.FIELD_CREATED_AT),
                                Sort.Order.asc("_id")))
                        .limit(limit),
                RewardCompensationDocument.class, RewardCompensationDocument.COLLECTION);
        List<Entry> out = new ArrayList<>(docs.size());
        docs.forEach(d -> out.add(d.toEntry()));
        return out;
    }

    @Override
    public int countPending() {
        return (int) mongo.count(Query.query(pendingFilter()), RewardCompensationDocument.COLLECTION);
    }

    /** 待处理的定义只写这一处：{@link #pending} 与 {@link #countPending} 必须同文。 */
    private static Criteria pendingFilter() {
        return Criteria.where(RewardCompensationDocument.FIELD_RESOLVED_AT).is(null);
    }

    @Override
    public Optional<Entry> findById(String compensationId) {
        if (compensationId == null || compensationId.isBlank()) {
            return Optional.empty();
        }
        RewardCompensationDocument doc = mongo.findById(compensationId,
                RewardCompensationDocument.class, RewardCompensationDocument.COLLECTION);
        return Optional.ofNullable(doc).map(RewardCompensationDocument::toEntry);
    }

    @Override
    public boolean resolve(String compensationId, String resolvedBy, String resolution, long nowMillis) {
        if (compensationId == null || compensationId.isBlank()) {
            return false;
        }
        if (resolvedBy == null || resolvedBy.isBlank()) {
            // 两侧同一句话（等价测试比对文本）：写进库的空处理人会让这条记录读回来时
            // 撞上 Entry 的构造校验，从此谁也查不到它 —— 那就不是"没处理"，而是"处理完就消失"
            throw new IllegalArgumentException("resolve 必须写明处理人：「谁把这笔债销掉的」与「谁欠的」同样重要");
        }
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(compensationId)
                        .and(RewardCompensationDocument.FIELD_RESOLVED_AT).is(null)),
                new Update()
                        .set(RewardCompensationDocument.FIELD_RESOLVED_AT, nowMillis)
                        .set("entry.resolvedBy", resolvedBy)
                        .set("entry.resolution", resolution == null ? "" : resolution),
                RewardCompensationDocument.COLLECTION).getModifiedCount() == 1L;
    }

    @Override
    public int count() {
        return (int) mongo.count(new Query(), RewardCompensationDocument.COLLECTION);
    }

    @Override
    public void clear() {
        mongo.remove(new Query(), RewardCompensationDocument.COLLECTION);
    }
}
