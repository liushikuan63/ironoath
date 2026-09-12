package com.ironoath.web.store.mongo;

import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.hero.HeroRoster;
import com.mongodb.client.result.UpdateResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Optional;

/**
 * 职责：武将存档的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、{@link HeroDocument}。
 *
 * <p>四条版本化契约（读返回副本、保存推进版本、过期版本被拒且不留半个写入、并发插入只有一个赢家）
 * 由 {@code HeroStoreContractTest}（内存）与 {@code MongoHeroStoreContractTest}（真实 Mongo）
 * 跑同一份断言，另加一条逐字段落库往返（养成字段比建筑更容易只写一半：等级、经验、星级、觉醒、
 * 两个技能等级、装备位、编队预设）。
 *
 * <p>写入用「按 version 条件的 update」而不是整档 replace，理由同 {@link MongoCityStore}。
 */
public final class MongoHeroStore implements HeroRepository {

    private final MongoTemplate mongo;

    public MongoHeroStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<HeroRoster> findByPlayerId(String playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        HeroDocument doc = mongo.findById(playerId, HeroDocument.class, HeroDocument.COLLECTION);
        return Optional.ofNullable(doc).map(HeroDocument::toDomain);
    }

    @Override
    public boolean insertIfAbsent(String playerId, HeroRoster roster) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (roster == null) {
            throw new IllegalArgumentException("武将存档不得为 null");
        }
        try {
            mongo.insert(HeroDocument.fromDomain(playerId, 0L, roster), HeroDocument.COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public long save(String playerId, HeroRoster roster, long expectedVersion) {
        if (roster == null) {
            throw new IllegalArgumentException("武将存档不得为 null");
        }
        HeroDocument doc = HeroDocument.fromDomain(playerId, expectedVersion, roster);
        Query query = Query.query(Criteria.where("_id").is(playerId).and("version").is(expectedVersion));
        Update update = new Update()
                .set("heroes", doc.heroes())
                .set("lineups", doc.lineups())
                .inc("version", 1L);
        UpdateResult result = mongo.updateFirst(query, update, HeroDocument.class, HeroDocument.COLLECTION);
        if (result.getMatchedCount() == 0L) {
            boolean exists = mongo.exists(Query.query(Criteria.where("_id").is(playerId)),
                    HeroDocument.class, HeroDocument.COLLECTION);
            if (!exists) {
                throw new IllegalStateException("武将存档不存在，无法更新：playerId=" + playerId);
            }
            throw new IllegalStateException("乐观锁冲突：playerId=" + playerId
                    + "，提交版本=" + expectedVersion + "。请重读武将存档后重试。");
        }
        return expectedVersion + 1L;
    }

    @Override
    public long versionOf(String playerId) {
        HeroDocument doc = mongo.findById(playerId, HeroDocument.class, HeroDocument.COLLECTION);
        if (doc == null) {
            throw new IllegalStateException("武将存档不存在：playerId=" + playerId);
        }
        return doc.version();
    }
}
