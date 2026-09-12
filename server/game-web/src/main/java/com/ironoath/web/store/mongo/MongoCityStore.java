package com.ironoath.web.store.mongo;

import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;
import com.mongodb.client.result.UpdateResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Optional;

/**
 * 职责：城建仓储的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、{@link CityDocument}。
 *
 * <p><b>语义必须与 {@code InMemoryCityStore} 一致，而且这件事现在是被测试而不是被注释的</b>：
 * 四条契约（读返回副本、保存推进版本、过期版本被拒且不留半个写入、并发插入只有一个赢家）
 * 由 {@code CityStoreContractTest} 与 {@code MongoCityStoreContractTest} 跑同一份断言。
 * 之前这句话只写在 {@code DEVELOPMENT.md} §四 里。
 *
 * <p>两处实现细节：
 * <ul>
 *   <li>写入用「按 version 条件 update」而不是 replace —— 只读出一个字段（{@code versionOf}）
 *       也要付一次整档传输的代价，条件更新则天然把并发挡在数据库里；</li>
 *   <li>版本的唯一权威是文档里的 {@code version} 字段，不是 {@code @Version} 注解：
 *       端口签名 {@code save(playerId, state, expectedVersion) -> long} 要求<b>调用方显式传版本</b>，
 *       交给 Spring Data 的 {@code @Version} 自动比对会让"重读后重试"这条应用层逻辑拿不到它需要的信息。</li>
 * </ul>
 */
public final class MongoCityStore implements CityRepository {

    private final MongoTemplate mongo;

    public MongoCityStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<CityState> findByPlayerId(String playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        CityDocument doc = mongo.findById(playerId, CityDocument.class, CityDocument.COLLECTION);
        // toDomain() 每次新建整棵对象树，所以这里返回的必然不是库里的活对象
        return Optional.ofNullable(doc).map(CityDocument::toDomain);
    }

    @Override
    public boolean insertIfAbsent(String playerId, CityState state) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (state == null) {
            throw new IllegalArgumentException("城建存档不得为 null");
        }
        try {
            // 一次插入而不是"先查再插"：playerId 是 _id，唯一性由主键索引保证
            mongo.insert(CityDocument.fromDomain(playerId, 0L, state), CityDocument.COLLECTION);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public long save(String playerId, CityState state, long expectedVersion) {
        if (state == null) {
            throw new IllegalArgumentException("城建存档不得为 null");
        }
        CityDocument doc = CityDocument.fromDomain(playerId, expectedVersion, state);
        Query query = Query.query(Criteria.where("_id").is(playerId).and("version").is(expectedVersion));
        Update update = new Update()
                .set("buildings", doc.buildings())
                .set("extraQueues", doc.extraQueues())
                .inc("version", 1L);
        UpdateResult result = mongo.updateFirst(query, update, CityDocument.class, CityDocument.COLLECTION);
        if (result.getMatchedCount() == 0L) {
            boolean exists = mongo.exists(Query.query(Criteria.where("_id").is(playerId)),
                    CityDocument.class, CityDocument.COLLECTION);
            if (!exists) {
                throw new IllegalStateException("城建存档不存在，无法更新：playerId=" + playerId);
            }
            throw new IllegalStateException("乐观锁冲突：playerId=" + playerId
                    + "，提交版本=" + expectedVersion + "。请重读存档后重试。");
        }
        return expectedVersion + 1L;
    }

    @Override
    public long versionOf(String playerId) {
        CityDocument doc = mongo.findById(playerId, CityDocument.class, CityDocument.COLLECTION);
        if (doc == null) {
            throw new IllegalStateException("城建存档不存在：playerId=" + playerId);
        }
        return doc.version();
    }
}
