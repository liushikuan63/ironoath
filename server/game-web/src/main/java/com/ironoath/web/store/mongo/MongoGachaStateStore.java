package com.ironoath.web.store.mongo;

import com.ironoath.core.gacha.GachaState;
import com.ironoath.core.gacha.GachaStateRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.Optional;

/**
 * 职责：抽卡保底进度的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、{@link GachaStateDocument}。
 *
 * <p><b>这一类状态不持久化的代价是合规问题，不是丢数据问题</b>：{@code gacha} 表的公示文案写着
 * 「保底计数不因赛季或版本更新而清零」，内存实现一重启就归零，玩家攒了 59 抽没出货、
 * 维护一过变回 0 —— 公示与事实不符，就是公示不实。所以 {@code HeroBeansConfig} 里那句
 * WARN 一直是响的。
 *
 * <p>没有乐观锁，与端口一致（抽卡整段在玩家锁内，计数字段单调递增或按规则清零）。
 * 落库用 {@code upsert}：首次抽某池时没有文档，第二次起是覆盖同一桶键。
 */
public final class MongoGachaStateStore implements GachaStateRepository {

    private final MongoTemplate mongo;

    public MongoGachaStateStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<GachaState> find(String playerId, String poolId) {
        if (playerId == null || poolId == null) {
            return Optional.empty();
        }
        GachaStateDocument doc = mongo.findById(
                GachaStateDocument.keyOf(playerId, poolId),
                GachaStateDocument.class, GachaStateDocument.COLLECTION);
        return Optional.ofNullable(doc).map(GachaStateDocument::toDomain);
    }

    @Override
    public void save(GachaState state) {
        if (state == null) {
            throw new IllegalArgumentException("保底进度不得为 null");
        }
        GachaStateDocument doc = GachaStateDocument.fromDomain(state);
        mongo.save(doc, GachaStateDocument.COLLECTION);
    }

    /** 测试与运维用：某个池的桶键是否存在（不返回内容，避免把整份进度读进内存）。 */
    public boolean exists(String playerId, String poolId) {
        return mongo.exists(Query.query(Criteria.where("_id")
                .is(GachaStateDocument.keyOf(playerId, poolId))), GachaStateDocument.COLLECTION);
    }
}
