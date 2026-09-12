package com.ironoath.web.store.memory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.ironoath.web.quest.QuestProgressStore;

/**
 * 职责：任务进度的内存实现（dev / test）。
 * 依赖：无（一张 ConcurrentHashMap）。
 *
 * <p>重启即空 —— 而任务进度是<b>丢了就补不回来</b>的那种数据（累加型目标无法从当前状态反推，
 * 见 {@link QuestProgressStore} 的类注释）。所以生产必须用 mongo 模式（见 {@code QuestBeansConfig} 的告警）。
 */
public final class InMemoryQuestProgressStore implements QuestProgressStore {

    private final Map<String, State> byPlayer = new ConcurrentHashMap<>();

    @Override
    public Optional<State> load(String playerId) {
        return playerId == null ? Optional.empty() : Optional.ofNullable(byPlayer.get(playerId));
    }

    @Override
    public void save(String playerId, State state) {
        QuestProgressStore.requireConsistentKey(playerId, state);
        byPlayer.put(playerId, state);
    }

    @Override
    public void clear() {
        byPlayer.clear();
    }
}
