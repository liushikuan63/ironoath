package com.ironoath.web.store.memory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.ironoath.web.levelreward.LevelRewardClaimStore;

/**
 * 职责：等级奖励领取账本的内存实现（dev / test）。
 * 依赖：无（一张 ConcurrentHashMap）。
 *
 * <p>重启即空 —— 而「哪些等级领过了」是<b>丢了就能再领一遍</b>的那种数据（当前存档反推不出来，
 * 见 {@link LevelRewardClaimStore} 的类注释），所以生产必须用 mongo 模式（见 {@code LevelRewardBeansConfig}）。
 */
public final class InMemoryLevelRewardClaimStore implements LevelRewardClaimStore {

    private final Map<String, State> byPlayer = new ConcurrentHashMap<>();

    @Override
    public Optional<State> load(String playerId) {
        return playerId == null ? Optional.empty() : Optional.ofNullable(byPlayer.get(playerId));
    }

    @Override
    public void save(String playerId, State state) {
        LevelRewardClaimStore.requireConsistentKey(playerId, state);
        byPlayer.put(playerId, state);
    }

    @Override
    public void clear() {
        byPlayer.clear();
    }
}
