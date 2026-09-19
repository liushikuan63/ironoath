package com.ironoath.web.store.mongo;

import com.ironoath.web.battlepass.BattlePassStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 职责：战令进度的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、{@link BattlePassDocument}。
 *
 * <p><b>为什么 {@link #update} 是重试循环而不是一把锁</b>：端口约定 {@code change} 是纯函数，
 * 于是"读 → 改 → 带版本号写回"三件事可以在撞版本时整段重来。并发守卫是
 * {@code _id + version} 两个条件同时成立 —— 只要另一个请求在这之间改过，
 * 这一次 {@code updateFirst} 的 modifiedCount 就是 0，本次改动<b>当作没发生</b>并重读。
 *
 * <p><b>方向上的取舍</b>：宁可整段重试（多算一次纯函数），也不能把"读到的旧版本 + 新改动"
 * 覆盖写回去 —— 那会丢掉另一个请求刚加上的积分或刚标记的领取，而后者是**玩家可重复领取**的入口。
 */
public final class MongoBattlePassStore implements BattlePassStore {

    /**
     * 重试上限。**64 而不是 8**：8 线程压测实测能连续撞满 8 次（每次都是读到新版本又被改掉），
     * 而每一次重试本身都是一次廉价的读+守卫写 —— 上限设小了，正常的高并发会被误报成有长时间持锁的路径。
     * 真正的病态（有人持锁不放、同一文档被反复改）仍会在 64 次里暴露。
     */
    private static final int MAX_ATTEMPTS = 64;

    private final MongoTemplate mongo;

    public MongoBattlePassStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** 清空集合。只给测试与人工排障用 —— 生产没有任何路径会调它。 */
    public void clear() {
        mongo.remove(new Query(), BattlePassDocument.COLLECTION);
    }

    @Override
    public List<String> playerIdsOf(String seasonId) {
        if (seasonId == null) {
            return List.of();
        }
        // 只投影 playerId 一列：补发只关心"这一季有谁"，把整份进度读回来是白读
        Query query = Query.query(Criteria.where("seasonId").is(seasonId));
        query.fields().include("playerId");
        List<String> out = new java.util.ArrayList<>();
        for (BattlePassDocument doc : mongo.find(query, BattlePassDocument.class,
                BattlePassDocument.COLLECTION)) {
            out.add(doc.playerId());
        }
        java.util.Collections.sort(out);
        return out;
    }

    @Override
    public Progress load(String seasonId, String playerId) {
        if (seasonId == null || playerId == null) {
            return Progress.empty();
        }
        BattlePassDocument doc = mongo.findById(BattlePassDocument.keyOf(seasonId, playerId),
                BattlePassDocument.class, BattlePassDocument.COLLECTION);
        return doc == null ? Progress.empty() : doc.progress();
    }

    @Override
    public Progress update(String seasonId, String playerId, UnaryOperator<Progress> change) {
        if (seasonId == null || seasonId.isBlank() || playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("战令进度的键不得为空：seasonId=" + seasonId + " playerId=" + playerId);
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            BattlePassDocument current = mongo.findById(BattlePassDocument.keyOf(seasonId, playerId),
                    BattlePassDocument.class, BattlePassDocument.COLLECTION);
            Progress base = current == null ? Progress.empty() : current.progress();
            Progress next = change.apply(base);
            if (next == null || next.equals(base)) {
                return base;
            }
            if (current == null) {
                try {
                    mongo.insert(BattlePassDocument.of(seasonId, playerId, next),
                            BattlePassDocument.COLLECTION);
                    return next;
                } catch (DuplicateKeyException e) {
                    continue;   // 并发建了同一条：重读一次再改
                }
            }
            Query guard = Query.query(Criteria.where("_id").is(current.id())
                    .and("version").is(current.version()));
            Update update = new Update()
                    .set("points", next.points())
                    .set("paidUnlocked", next.paidUnlocked())
                    .set("claimed", List.copyOf(next.claimed()))
                    .set("version", current.version() + 1L);
            if (mongo.updateFirst(guard, update, BattlePassDocument.COLLECTION).getModifiedCount() == 1L) {
                return next;
            }
        }
        throw new IllegalStateException("战令进度连续 " + MAX_ATTEMPTS + " 次被并发改动挤掉："
                + "seasonId=" + seasonId + " playerId=" + playerId
                + "。每次重试都读到新版本却又被改掉，说明有一段长时间持锁的写入路径，"
                + "而不是简单的并发领取");
    }
}
