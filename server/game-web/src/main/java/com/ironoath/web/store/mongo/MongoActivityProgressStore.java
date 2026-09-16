package com.ironoath.web.store.mongo;

import java.util.Optional;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import com.ironoath.web.activity.ActivityProgressStore;

/**
 * 职责：活动进度的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、{@link ActivityProgressDocument}。
 *
 * <p><b>这一档防的是「连续签到与活动进度丢一次就永久少一格」</b>：两类进度都无法从当前状态反推
 * （见 {@link ActivityProgressStore} 的类注释），所以只能在事件发生那一刻记下来。
 *
 * <p>并发：没有任何版本号，前提是「同玩家的进度只有一个线程在改」（事件在玩家自己的锁内同步派发，
 * 且所有推进事件都来自玩家自己的动作）。这条前提写在端口上。
 */
public final class MongoActivityProgressStore implements ActivityProgressStore {

    private final MongoTemplate mongo;

    public MongoActivityProgressStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<State> load(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return Optional.empty();
        }
        ActivityProgressDocument doc = mongo.findById(playerId, ActivityProgressDocument.class,
                ActivityProgressDocument.COLLECTION);
        return doc == null ? Optional.empty() : Optional.of(doc.toState());
    }

    @Override
    public void save(String playerId, State state) {
        ActivityProgressStore.requireConsistentKey(playerId, state);
        mongo.save(ActivityProgressDocument.of(state), ActivityProgressDocument.COLLECTION);
    }

    @Override
    public void clear() {
        mongo.remove(new Query(), ActivityProgressDocument.COLLECTION);
    }
}
