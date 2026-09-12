package com.ironoath.core.gacha;

/**
 * 职责：玩家在某个卡池上的抽卡进度（保底计数 + 终身次数）。
 * 依赖：无（纯数据）。
 *
 * <p><b>保底计数必须持久化，且必须按「玩家 × 卡池」分开记</b>：
 * <ul>
 *   <li>不持久化 ⇒ 玩家断线重连就能把保底计数清零重来，
 *       而「累计 80 抽必出 SSR」是写进公示文案的承诺，清零等于公示不实（合规红线）。</li>
 *   <li>不按卡池分开 ⇒ 在标准池攒的 79 抽进度会被限定池继承，
 *       玩家会在限定池第一抽就拿到 SSR，限定池的付费深度直接归零。</li>
 * </ul>
 * gacha 表的公示文案明写「保底计数跨单次抽取持续累计，仅在获得对应稀有度后重置，
 * 且不因赛季或版本更新而清零」，本类就是那句话的载体。
 *
 * @param playerId        玩家 id。<b>必须带在状态里</b>：仓储按 (playerId, poolId) 复合键存储，
 *                        少了它就无法确定归属，而写错键的后果是「一个玩家攒的 79 抽保底进度
 *                        被记到另一个账号上」—— 那比抛异常严重得多，因为它不会报错
 * @param poolId          卡池 id
 * @param ssrCounter      距上次出 SSR 已累计多少抽
 * @param srCounter       距上次出 SR 及以上已累计多少抽
 * @param nonUpStreak  连续多少次「命中 UP 档位但不是 UP 武将」（UP 大保底用）
 * @param lifetimeDraws   该池的终身累计抽取次数，用于 gacha.lifetimeLimit（新手池限抽 1 次）
 */
public record GachaState(String playerId,
                         String poolId,
                         long ssrCounter,
                         long srCounter,
                         long nonUpStreak,
                         long lifetimeDraws) {

    public GachaState {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (poolId == null || poolId.isBlank()) {
            throw new IllegalArgumentException("poolId 不得为空");
        }
        requireNonNegative(ssrCounter, "ssrCounter");
        requireNonNegative(srCounter, "srCounter");
        requireNonNegative(nonUpStreak, "nonUpStreak");
        requireNonNegative(lifetimeDraws, "lifetimeDraws");
    }

    public static GachaState fresh(String playerId, String poolId) {
        return new GachaState(playerId, poolId, 0L, 0L, 0L, 0L);
    }

    /** 抽卡后的新状态。counters 来自 {@link GachaEngine.Batch}。 */
    public GachaState after(GachaEngine.Counters counters, int draws) {
        if (counters == null) {
            throw new IllegalArgumentException("counters 不得为 null");
        }
        if (draws < 0) {
            throw new IllegalArgumentException("抽取次数不得为负：" + draws);
        }
        return new GachaState(playerId, poolId, counters.ssr(), counters.sr(),
                counters.nonUpStreak(), lifetimeDraws + draws);
    }

    public GachaEngine.Counters counters() {
        return new GachaEngine.Counters(ssrCounter, srCounter, nonUpStreak);
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0L) {
            throw new IllegalArgumentException(field + " 不得为负：" + value);
        }
    }
}
