package com.ironoath.web.store.memory;

import com.ironoath.core.gacha.GachaState;
import com.ironoath.core.gacha.GachaStateRepository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 职责：抽卡保底进度的内存实现。
 * 依赖：game-core 的端口与状态记录。
 *
 * <p><b>键是 (playerId, poolId) 的复合键</b>：保底按卡池分开记，
 * 在标准池攒的 79 抽进度不能被限定池继承，否则限定池第一抽就出 SSR，
 * 限定池的付费深度直接归零（gacha 表的公示文案承诺的正是「按池累计」）。
 *
 * <p>{@link GachaState} 是不可变 record，所以读写都不需要拷贝。
 *
 * <p><b>重启即丢，而保底进度是不能丢的</b>：gacha 表的公示文案承诺
 * 「保底计数不因赛季或版本更新而清零」。所以生产环境必须用 MongoDB 版（B16），
 * 本类只服务 dev/test。这一点在装配处用 WARN 日志显式提示。
 */
public final class InMemoryGachaStateStore implements GachaStateRepository {

    private final Map<String, GachaState> byKey = new ConcurrentHashMap<>();

    @Override
    public Optional<GachaState> find(String playerId, String poolId) {
        requireText(playerId, "playerId");
        requireText(poolId, "poolId");
        return Optional.ofNullable(byKey.get(key(playerId, poolId)));
    }

    @Override
    public void save(GachaState state) {
        if (state == null) {
            throw new IllegalArgumentException("state 不得为 null");
        }
        byKey.put(key(state.playerId(), state.poolId()), state);
    }

    public void clear() {
        byKey.clear();
    }

    public int size() {
        return byKey.size();
    }

    private static String key(String playerId, String poolId) {
        return playerId + "@" + poolId;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为空");
        }
    }
}
