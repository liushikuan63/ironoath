package com.ironoath.web.store.mongo;

import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.mongodb.client.result.UpdateResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 职责：行军的 MongoDB 实现（生产存储，B07 §2 明写"持久化在 MongoDB"）。
 * 依赖：Spring Data MongoDB、{@link MarchDocument}。
 *
 * <p><b>这一类存不存得下来，是 B07 验收 1 的全部内容</b>：玩家杀进程重进后，所有队伍必须按
 * 真实剩余时间继续走。内存实现下重启等于把半路上的兵清空 —— 兵力"在路上"这件事一旦丢失，
 * 连"该退多少给谁"都无从计算，而集合那边（B10 集结）还记着他们在外。
 *
 * <p>排序口径与内存实现逐条对齐（{@code MarchStoreEquivalenceTest#orderingIsIdenticalAcrossImplementations} 两套实现比同一份结果）：
 * 一律按 {@code startAt} 升序、同刻再按 {@code id}，因为 {@code ConcurrentHashMap} 与
 * Mongo 的自然顺序都不确定，而客户端要按出征顺序展示列表。
 */
public final class MongoMarchStore implements MarchRepository {

    private final MongoTemplate mongo;
    private final int chunkSize;

    public MongoMarchStore(MongoTemplate mongo, int chunkSize) {
        if (chunkSize <= 0 || (chunkSize & (chunkSize - 1)) != 0) {
            throw new IllegalArgumentException("chunkSize 必须是正的 2 的幂（chunk 键用位移算），实际="
                    + chunkSize);
        }
        this.mongo = mongo;
        this.chunkSize = chunkSize;
    }

    @Override
    public Optional<March> findById(String marchId) {
        if (marchId == null) {
            return Optional.empty();
        }
        MarchDocument doc = mongo.findById(marchId, MarchDocument.class, MarchDocument.COLLECTION);
        return Optional.ofNullable(doc).map(MarchDocument::toDomain);
    }

    @Override
    public List<March> findByPlayerId(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        return toDomain(orderedByStart(Query.query(Criteria.where("playerId").is(playerId))));
    }

    @Override
    public List<March> findByChunkKeys(List<String> chunkKeys) {
        if (chunkKeys == null || chunkKeys.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> wanted = new LinkedHashSet<>(chunkKeys);
        Query query = orderedByStart(new Query().addCriteria(new Criteria().orOperator(
                Criteria.where("fromChunkKey").in(wanted),
                Criteria.where("toChunkKey").in(wanted))));
        return toDomain(query);
    }

    @Override
    public boolean insertIfAbsent(March march) {
        if (march == null) {
            throw new IllegalArgumentException("march 不得为 null");
        }
        try {
            mongo.insert(MarchDocument.fromDomain(march, 0L, chunkSize), MarchDocument.COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public long save(March march, long expectedVersion) {
        if (march == null) {
            throw new IllegalArgumentException("march 不得为 null");
        }
        MarchDocument doc = MarchDocument.fromDomain(march, expectedVersion, chunkSize);
        Query query = Query.query(Criteria.where("_id").is(march.id()).and("version").is(expectedVersion));
        // 整份 state 与三个索引键一起换：行军的起点/终点在返程时会变（returnFrom 另说），
        // 只换 state 会让 chunk 键停留在出发时的值上，viewport 增量下发就再也命中不了它
        Update update = new Update()
                .set("state", doc.state())
                .set("playerId", doc.playerId())
                .set("fromChunkKey", doc.fromChunkKey())
                .set("toChunkKey", doc.toChunkKey())
                .inc("version", 1L);
        UpdateResult result = mongo.updateFirst(query, update, MarchDocument.class, MarchDocument.COLLECTION);
        if (result.getMatchedCount() == 0L) {
            boolean exists = mongo.exists(Query.query(Criteria.where("_id").is(march.id())),
                    MarchDocument.class, MarchDocument.COLLECTION);
            if (!exists) {
                throw new IllegalStateException("行军不存在，无法更新：marchId=" + march.id());
            }
            throw new IllegalStateException("乐观锁冲突：marchId=" + march.id()
                    + "，提交版本=" + expectedVersion + "。请重读行军后重试。");
        }
        return expectedVersion + 1L;
    }

    @Override
    public long versionOf(String marchId) {
        MarchDocument doc = mongo.findById(marchId, MarchDocument.class, MarchDocument.COLLECTION);
        if (doc == null) {
            throw new IllegalStateException("行军不存在：marchId=" + marchId);
        }
        return doc.version();
    }

    @Override
    public void delete(String marchId) {
        if (marchId == null || marchId.isBlank()) {
            throw new IllegalArgumentException("marchId 不得为空");
        }
        mongo.remove(Query.query(Criteria.where("_id").is(marchId)),
                MarchDocument.class, MarchDocument.COLLECTION);
        // 刻意不检查"有没有删到"：内存版是静默的，而调用方删一条已经不在的行军是正常路径
        // （返程到家后由调用方 delete，可能别的路径先删过）。在这里抛异常会让同一份代码
        // 在 dev 全绿、在 prod 随机报错 —— #34 那套契约存在的全部理由就是不让这种事发生。
    }

    @Override
    public long activeCountOf(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        // 凡是还在库里的都算在外：到家后由调用方 delete（与内存实现同一口径，两处必须一致，
        // 否则 MARCH_MAX_CONCURRENT 在 dev 与 prod 上会给出不同的上限）
        return mongo.count(Query.query(Criteria.where("playerId").is(playerId)),
                MarchDocument.class, MarchDocument.COLLECTION);
    }

    private static Query orderedByStart(Query query) {
        return query.with(Sort.by(Sort.Order.asc("state.startAt"), Sort.Order.asc("_id")));
    }

    private List<March> toDomain(Query query) {
        List<MarchDocument> docs = mongo.find(query, MarchDocument.class, MarchDocument.COLLECTION);
        List<March> out = new ArrayList<>(docs.size());
        for (MarchDocument doc : docs) {
            out.add(doc.toDomain());
        }
        return out;
    }
}
