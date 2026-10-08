package com.ironoath.web.store.mongo;

import java.util.Optional;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import com.ironoath.web.levelreward.LevelRewardClaimStore;

/**
 * 职责：等级奖励领取账本的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、{@link LevelRewardClaimDocument}。
 *
 * <p><b>这一档防的是「领过的等级随进程消失，于是同一级能再领一遍」</b>：已领是历史事实，
 * 存档上没有任何一位能反推它（资源早被花掉了），所以内存版重启即空 = 白送一份奖励。
 *
 * <p>并发：没有版本号，前提是「同玩家的领取只有一个线程在改」（写在端口注释里，与任务进度同一条）。
 */
public final class MongoLevelRewardClaimStore implements LevelRewardClaimStore {

    private final MongoTemplate mongo;

    public MongoLevelRewardClaimStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<State> load(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return Optional.empty();
        }
        LevelRewardClaimDocument doc = mongo.findById(playerId, LevelRewardClaimDocument.class,
                LevelRewardClaimDocument.COLLECTION);
        return doc == null ? Optional.empty() : Optional.of(doc.toState());
    }

    @Override
    public void save(String playerId, State state) {
        LevelRewardClaimStore.requireConsistentKey(playerId, state);
        mongo.save(LevelRewardClaimDocument.of(state), LevelRewardClaimDocument.COLLECTION);
    }

    @Override
    public void clear() {
        mongo.remove(new Query(), LevelRewardClaimDocument.COLLECTION);
    }
}
