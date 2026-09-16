package com.ironoath.web.activity;

import java.util.List;
import java.util.Optional;

import com.ironoath.core.activity.ActivityProgress;

/**
 * 职责：活动进度的持久化端口（B17 §一）。
 * 依赖：game-core 的进度条目。
 *
 * <p>与其它端口同一套约定：端口在 {@code web/activity}，实现放 {@code store/memory} 与 {@code store/mongo}。
 *
 * <p><b>为什么和任务进度一样必须落库</b>：活动进度是累加型与连续型两种都有的派生状态 ——
 * 「这轮打了 30 只怪」与「连了 5 天」都无法从当前状态反推（怪已经死了、日子已经过去了）。
 * 唯一的记录时机就是事件发生的那一刻，所以丢一次就是永久少一格，而玩家看到的是「我明明做了，进度没动」。
 *
 * <p><b>为什么不带 dayKey / weekKey 两个跨期键</b>（任务端口有）：活动的跨期是<b>窗口</b>，
 * 而窗口的起点就写在每个条目上（{@code Entry.windowStart}）—— 两个键能表达的东西更少，
 * 存进来只会多一处要维护的真相。这是本端口与 {@code QuestProgressStore} 唯一的结构差异。
 *
 * <p><b>没有版本号，因为不需要</b>：所有推进事件都是玩家自己的动作（登录、击杀、捐献、参战…），
 * 同一个玩家的写请求由他自己的玩家锁串行化，事件又是同步派发的。将来若出现跨玩家事件
 * （例如"队友的击杀算我也完成"），必须补乐观锁而不是继续假定串行。
 */
public interface ActivityProgressStore {

    /** 玩家的进度快照；没有记录返回 empty（新号第一次打开活动页）。 */
    Optional<State> load(String playerId);

    /** 覆盖保存。调用方保证同玩家的写是串行的（见类注释）。 */
    void save(String playerId, State state);

    /** 测试辅助：清空。 */
    void clear();

    /** 落库的形状：条目 + 那份开服时刻（诊断用）。 */
    record State(String playerId, List<ActivityProgress.Entry> entries, long serverOpenMs) {
        public State {
            if (playerId == null || playerId.isBlank()) {
                throw new IllegalArgumentException("playerId 不得为空");
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
