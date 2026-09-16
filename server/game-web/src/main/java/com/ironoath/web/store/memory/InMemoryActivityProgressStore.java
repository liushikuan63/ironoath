package com.ironoath.web.store.memory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.ironoath.web.activity.ActivityProgressStore;

/**
 * 职责：活动进度的内存实现（dev / test）。
 * 依赖：无（一张 ConcurrentHashMap）。
 *
 * <p>重启即空 —— 活动进度是丢了就补不回来的那种数据（见 {@link ActivityProgressStore} 的类注释），
 * 所以生产必须用 mongo 模式（见 {@code ActivityBeansConfig} 的告警）。
 */
public final class InMemoryActivityProgressStore implements ActivityProgressStore {

    private final Map<String, State> byPlayer = new ConcurrentHashMap<>();

    @Override
    public Optional<State> load(String playerId) {
        return playerId == null ? Optional.empty() : Optional.ofNullable(byPlayer.get(playerId));
    }

    @Override
    public void save(String playerId, State state) {
        ActivityProgressStore.requireConsistentKey(playerId, state);
        byPlayer.put(playerId, state);
    }

    @Override
    public void clear() {
        byPlayer.clear();
    }
}
