package com.ironoath.web.levelreward;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 职责：等级奖励「哪些等级已经领过」的账本端口（收口清单 #829 裁决②：入账只发生在玩家点「领取」那一刻）。
 * 依赖：无（纯端口，实现放 {@code store/memory} 与 {@code store/mongo}，与任务进度同一套约定）。
 *
 * <p><b>为什么账本必须落库而不能「读的时候重算」</b>：已领是历史事实，当前存档里没有任何一位能反推它 ——
 * 资源会被花掉、金币会变动，「他领过 12 级」这件事只存在于这一次领取里。
 * 所以丢一条记录的症状不是「少显示一行」，而是<b>同一级能再领一遍</b>（玩家白拿一份，而幂等键是新的）。
 *
 * <p><b>没有版本号，因为不需要</b>：与 {@code QuestProgressStore} 同一条前提 —— 领取是玩家自己的动作，
 * 同一玩家的写请求由他自己的玩家锁串行化。将来若出现「别人替他领」这类跨玩家入口，就必须补乐观锁，
 * 而不是继续假定串行。
 */
public interface LevelRewardClaimStore {

    /** 玩家的领取账本；没有记录返回 empty（新号一个都没领过）。 */
    Optional<State> load(String playerId);

    /** 覆盖保存。调用方保证同玩家的写是串行的（见类注释）。 */
    void save(String playerId, State state);

    /** 测试辅助：清空。 */
    void clear();

    /**
     * 落库的形状：已领的等级列表。
     *
     * <p><b>存等级而不是行 id</b>：{@code level_reward} 表的行 id 形如 {@code lr_lv31}，它本身就是等级的拼写，
     * 存它等于把「表的主键怎么起的名」写进玩家存档 —— 改一次命名，全部老存档一起失效。
     *
     * <p><b>有序去重的 List 而不是 Set</b>：与 {@code PlayerPaid.fundClaimedTiers} 同一个理由 ——
     * 集合从存储回来的顺序由实现决定，而这里没有第二位能解释「为什么这一级排在前面」。
     */
    record State(String playerId, List<Long> claimedLevels) {

        public State {
            if (playerId == null || playerId.isBlank()) {
                throw new IllegalArgumentException("playerId 不得为空");
            }
            if (claimedLevels == null) {
                claimedLevels = List.of();
            } else {
                Set<Long> distinct = new LinkedHashSet<>(claimedLevels);
                for (Long level : distinct) {
                    if (level == null || level < 1L) {
                        throw new IllegalArgumentException("已领等级必须 >= 1，实际=" + level);
                    }
                }
                // 去重后按等级升序：读路径要按行序渲染，而「同一份存档两次读出来顺序不同」
                // 会变成玩家看到的列表在跳
                claimedLevels = distinct.stream().sorted().toList();
            }
        }

        public static State empty(String playerId) {
            return new State(playerId, List.of());
        }

        public boolean claimed(long level) {
            return claimedLevels.contains(level);
        }

        /** 记一级已领。调用方必须先用 {@link #claimed} 挡过重复（重复领各有各的业务码）。 */
        public State withClaimed(long level) {
            if (level < 1L) {
                throw new IllegalArgumentException("已领等级必须 >= 1，实际=" + level);
            }
            return new State(playerId, java.util.stream.Stream
                    .concat(claimedLevels.stream(), java.util.stream.Stream.of(level)).toList());
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
