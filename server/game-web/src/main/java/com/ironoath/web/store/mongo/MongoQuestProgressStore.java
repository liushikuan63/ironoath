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
 * <p>并发：没有任何版本号，前提是「同玩家的进度只有一个线程在改」（事件在玩家自己的锁内同步派发，
 * 且所有目标事件都来自玩家自己的动作）。这条前提写在端口上，将来出现跨玩家事件时要补锁。
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
