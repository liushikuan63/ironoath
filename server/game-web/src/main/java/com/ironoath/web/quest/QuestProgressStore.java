package com.ironoath.web.quest;

import java.util.List;
import java.util.Optional;

import com.ironoath.core.quest.QuestProgress;

/**
 * 职责：任务进度的持久化端口（B12 §1）。
 * 依赖：game-core 的进度条目。
 *
 * <p>与其它端口同一套约定：端口在 {@code web/quest}，实现放 {@code store/memory} 与 {@code store/mongo}。
 *
 * <p><b>为什么进度必须落库而不能"读的时候重算"</b>：累加型目标的进度<b>无法从当前状态反推</b>
 * （「累计训练 20 个兵」里的兵可能已经战死、被派出去、或用于治疗）。
 * 唯一的记录时机就是事件发生的那一刻 —— 所以丢一次进度就是永久少一格，
 * 而玩家看到的是「我明明做了，任务没动」。这也是 {@code GameEventBus} 的类注释里那条
 * 「漏掉一个事件，进度就永久少一格」的持久化面。
 *
 * <p><b>没有版本号，因为不需要</b>：所有 13 种目标事件都是<b>玩家自己的动作</b>产生的
 * （升级、训练、击杀、采集、帮助、参战…），而同一个玩家的写请求由他自己的玩家锁串行化，
 * 事件又是同步派发的 —— 所以同一条进度不会有两个线程同时改。
 * 这一条前提写在这里：将来若出现「别人帮我完成了目标」这类跨玩家事件，
 * 就必须补乐观锁，而不是继续假定串行。
 */
public interface QuestProgressStore {

    /** 玩家的进度快照；没有记录返回 empty（新号第一次打开任务面板）。 */
    Optional<State> load(String playerId);

    /** 覆盖保存。调用方保证同玩家的写是串行的（见类注释）。 */
    void save(String playerId, State state);

    /** 测试辅助：清空。 */
    void clear();

    /**
     * 落库的形状：进度条目 + 两个跨期键。
     *
     * <p><b>两个键必须与条目一起存</b>：只存条目的话，重启后无法判断「这些每日进度是不是今天的」——
     * 昨天的 3/3 会被当成今天的，玩家一上线就看到一个白送的完成态。
     */
    record State(String playerId, List<QuestProgress.Entry> entries, String dayKey, String weekKey) {
        public State {
            if (playerId == null || playerId.isBlank()) {
                throw new IllegalArgumentException("playerId 不得为空");
            }
            if (dayKey == null || dayKey.isBlank() || weekKey == null || weekKey.isBlank()) {
                throw new IllegalArgumentException("dayKey 与 weekKey 都不得为空：跨期清零靠它们");
            }
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** 写侧共用的键校验：两个实现都要拒，否则会在存储里留下永远读不到的行（等价性的一条）。 */
    static void requireConsistentKey(String playerId, State state) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (state == null) {
            throw new IllegalArgumentException("state 不得为 null");
        }
        if (!playerId.equals(state.playerId())) {
            throw new IllegalArgumentException("playerId 与 state.playerId 不一致："
                    + playerId + " vs " + state.playerId());
        }
    }
}
