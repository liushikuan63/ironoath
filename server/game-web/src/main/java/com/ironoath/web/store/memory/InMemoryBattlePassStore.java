package com.ironoath.web.store.memory;

import com.ironoath.web.battlepass.BattlePassStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * 职责：战令进度的内存实现 —— dev / 单测零依赖启动。
 * 依赖：{@link BattlePassStore} 端口。
 *
 * <p><b>它带着一个生产上不可接受的性质</b>：重启即清空。而战令进度是"这一季打过的分"
 * 与"哪些档领过"的唯一凭据 —— 丢了之后玩家会在重启后的第一次打开时看到进度归零，
 * 而**已经领过的档位会变成可领**（重复发一遍奖励）。所以 {@code ironoath.storage=mongo}
 * 时换 {@code MongoBattlePassStore}，两侧跑同一份 {@code BattlePassStoreEquivalenceTest}。
 *
 * <p>{@link #update} 用 {@code ConcurrentHashMap#compute} 保证"读-改-写"是原子的：
 * 两个并发的领取请求必须在同一把锁下走完"校验没领过 → 标记已领"，
 * 否则同一档会发两份（这正是端口注释里那条取舍要挡的东西）。
 */
public class InMemoryBattlePassStore implements BattlePassStore {

    /** {@code seasonId:playerId} → 这一季这个人的进度。键格式与 Mongo 文档的 {@code _id} 同一条拼法。 */
    private final Map<String, Progress> byKey = new ConcurrentHashMap<>();

    static String keyOf(String seasonId, String playerId) {
        return seasonId + ":" + playerId;
    }

    /** 清空。只给测试用（内存实现换实例等于重启，但等价测试要在同一个实例上反复跑）。 */
    public void clear() {
        byKey.clear();
    }

    @Override
    public Progress load(String seasonId, String playerId) {
        if (seasonId == null || playerId == null) {
            return Progress.empty();
        }
        return byKey.getOrDefault(keyOf(seasonId, playerId), Progress.empty());
    }

    @Override
    public java.util.List<String> playerIdsOf(String seasonId) {
        if (seasonId == null) {
            return java.util.List.of();
        }
        String prefix = seasonId + ":";
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String key : byKey.keySet()) {
            if (key.startsWith(prefix)) {
                out.add(key.substring(prefix.length()));
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    @Override
    public Progress update(String seasonId, String playerId, UnaryOperator<Progress> change) {
        if (seasonId == null || seasonId.isBlank() || playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("战令进度的键不得为空：seasonId=" + seasonId + " playerId=" + playerId);
        }
        return byKey.compute(keyOf(seasonId, playerId), (key, current) -> {
            Progress base = current == null ? Progress.empty() : current;
            Progress next = change.apply(base);
            return next == null ? base : next;
        });
    }
}
