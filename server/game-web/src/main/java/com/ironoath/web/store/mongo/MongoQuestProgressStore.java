package com.ironoath.web.store.mongo;

import java.util.Optional;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import com.ironoath.web.quest.QuestProgressStore;
import com.ironoath.web.store.memory.InMemoryQuestProgressStore;

/**
 * 职责：任务进度的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、{@link QuestProgressDocument}。
 *
 * <p><b>这一档防的是「进度丢一次就永久少一格」</b>：累加型目标无法从当前状态反推
 * （「累计训练 20 个兵」的兵可能已经战死），所以进度只能在事件发生那一刻记下来 ——
 * 内存版重启即空，玩家看到的是「我明明做了，任务没动」。
 *
 * <p>整档读改写通过独立任务账本锁串行，包含事件、领取和会刷新落盘的读取。
 * 全局行军可以在异主人的玩家/国家锁域发布事件，不能依赖调用方已持主人锁。
 * 具体末端锁键及不得反取外层业务锁的契约见{@link QuestProgressStore}。
 */
public final class MongoQuestProgressStore implements QuestProgressStore {

    private final MongoTemplate mongo;

    public MongoQuestProgressStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<State> load(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return Optional.empty();
        }
        QuestProgressDocument doc = mongo.findById(playerId, QuestProgressDocument.class,
                QuestProgressDocument.COLLECTION);
        return doc == null ? Optional.empty() : Optional.of(doc.toState());
    }

    @Override
    public void save(String playerId, State state) {
        QuestProgressStore.requireConsistentKey(playerId, state);
        mongo.save(QuestProgressDocument.of(state), QuestProgressDocument.COLLECTION);
    }

    @Override
    public void clear() {
        mongo.remove(new Query(), QuestProgressDocument.COLLECTION);
    }
}
