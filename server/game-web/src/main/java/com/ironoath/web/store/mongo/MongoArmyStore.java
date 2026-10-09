package com.ironoath.web.store.mongo;

import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.mongodb.client.result.UpdateResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Optional;

/**
 * 职责：军队存档的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、{@link ArmyDocument}。
 *
 * <p>四条版本化契约由 {@code ArmyStoreContractTest}（内存）与 {@code MongoArmyStoreContractTest}
 * （真实 Mongo）跑同一份断言保证；后者另有一条逐字段落库往返，专门盯训练队列与治疗进度 ——
 * 这两处的字段丢了都不会让"兵力总数"变化，于是没有任何测试会红。
 */
public final class MongoArmyStore implements ArmyRepository {

    private final MongoTemplate mongo;

    public MongoArmyStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<ArmyState> findByPlayerId(String playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        ArmyDocument doc = mongo.findById(playerId, ArmyDocument.class, ArmyDocument.COLLECTION);
        return Optional.ofNullable(doc).map(ArmyDocument::toDomain);
    }

    @Override
    public boolean insertIfAbsent(String playerId, ArmyState army) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (army == null) {
            throw new IllegalArgumentException("军队存档不得为 null");
        }
        try {
            mongo.insert(ArmyDocument.fromDomain(playerId, 0L, army), ArmyDocument.COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public long save(String playerId, ArmyState army, long expectedVersion) {
        if (army == null) {
            throw new IllegalArgumentException("军队存档不得为 null");
        }
        army.requireRepositoryVersion(expectedVersion);
        ArmyDocument doc = ArmyDocument.fromDomain(playerId, expectedVersion, army);
        Query query = Query.query(Criteria.where("_id").is(playerId).and("version").is(expectedVersion));
        // **每加一个 ArmyDocument 字段都必须在这里补一行**：这是 $set 白名单而不是整份替换，
        // 漏一个既不会报错也不会让任何用例变红（内存实现照样全绿），只会在 Mongo 上静默丢档。
        // B25 的自动续训策略就这么丢过一次：开关打开、下次读回来是关的，像坏了一样。
        // 兜底是 MongoArmyStoreContractTest#autoTrainPolicySurvivesTheRoundTrip 那条逐字段往返。
        Update update = new Update()
                .set("troops", doc.troops())
                .set("queue", doc.queue())
                .set("wounded", doc.wounded())
                .set("treatFinishAt", doc.treatFinishAt())
                .set("treatTotalSeconds", doc.treatTotalSeconds())
                .set("treatOriginalSeconds", doc.treatOriginalSeconds())
                .set("treatCost", doc.treatCost())
                .set("extraSlots", doc.extraSlots())
                .set("autoTrain", doc.autoTrain())
                .set("rallyRefunds", doc.rallyRefunds())
                .inc("version", 1L);
        UpdateResult result = mongo.updateFirst(query, update, ArmyDocument.class, ArmyDocument.COLLECTION);
        if (result.getMatchedCount() == 0L) {
            boolean exists = mongo.exists(Query.query(Criteria.where("_id").is(playerId)),
                    ArmyDocument.class, ArmyDocument.COLLECTION);
            if (!exists) {
                throw new IllegalStateException("军队存档不存在，无法更新：playerId=" + playerId);
            }
            throw new IllegalStateException("乐观锁冲突：playerId=" + playerId
                    + "，提交版本=" + expectedVersion + "。请重读军队存档后重试。");
        }
        army.bindRepositoryVersion(expectedVersion + 1L);
        return expectedVersion + 1L;
    }

    @Override
    public long versionOf(String playerId) {
        ArmyDocument doc = mongo.findById(playerId, ArmyDocument.class, ArmyDocument.COLLECTION);
        if (doc == null) {
            throw new IllegalStateException("军队存档不存在：playerId=" + playerId);
        }
        return doc.version();
    }
}
