package com.ironoath.core.power;

import com.ironoath.common.num.FixedPoint;

/**
 * 职责：战力计算与峰值记忆（B08 §1）。双端共用的口径 —— 客户端只显示，判定一律在服务端。
 * 依赖：game-common 的 FixedPoint（纯 Java，零框架、零配置依赖）。
 *
 * <p><b>为什么必须有「两个战力」</b>：
 * <ul>
 *   <li>{@code displayPower} = 建筑 + 部队 + 武将 + 科技 + 装备，用于排行榜与联盟展示。
 *       玩家关心的是「我总共练了多少」。</li>
 *   <li>{@code matchPower} = 只算<b>当前部队 + 上阵主将</b>，用于 PVP 圈层校验与 Bot 校准。
 *       因为能打出去的只有这些 —— 建筑与科技不会跟着出征。</li>
 * </ul>
 * 用 displayPower 做圈层校验会立刻被套利：把兵全部卸掉、把武将全部下阵，
 * displayPower 仍然很高（建筑还在），于是他顶着「高战力」的标签却谁都打不过、
 * 也没人打得动他（因为守方按他的高 displayPower 判定区间）—— 整个圈层失效。
 *
 * <p><b>峰值记忆是为了堵「卸兵压分刷小号」</b>：
 * <pre>
 *   matchPower = max(当前匹配战力, 历史峰值 × 0.8)
 *   历史峰值每日衰减 2%
 * </pre>
 * 卸兵之后 matchPower 不会立刻掉下去，所以压分没有收益；
 * 而峰值每日衰减 2%，所以「真的转玩法了」（例如从打架转种田）不会被永久钉在高位。
 * 0.8 而不是 1.0 是给正常波动留的余量：打完一场大仗兵力损失，
 * 峰值按 100% 记会让玩家在半小时内被判定成「比你实际强得多」，搜不到合适对手。
 *
 * <p><b>UI 必须能点开看明细</b>：玩家对「我为什么是这个战力」极度敏感，
 * 所以 {@link PowerBreakdown} 逐项返回，不是只给一个总数。
 */
public final class PowerCalculator {

    /**
     * 战力明细。逐项来自各自的系统，本类只负责汇总 ——
     * 它不重新计算建筑战力或武将战力，那些由 B03 的 POWER_CONTRIB 曲线与 B06 的 HeroCalculator 给出。
     */
    public record PowerBreakdown(long building, long troops, long heroes, long tech, long equipment) {

        public PowerBreakdown {
            requireNonNegative(building, "building");
            requireNonNegative(troops, "troops");
            requireNonNegative(heroes, "heroes");
            requireNonNegative(tech, "tech");
            requireNonNegative(equipment, "equipment");
        }

        public long total() {
            return building + troops + heroes + tech + equipment;
        }

        private static void requireNonNegative(long value, String field) {
            if (value < 0L) {
                throw new IllegalArgumentException(field + " 战力不得为负：" + value);
            }
        }
    }

    /**
     * 计算战力的输入快照。
     *
     * @param breakdown          五项明细
     * @param currentMatchPower  当前匹配战力（当前部队 + 上阵主将）。<b>不是 breakdown.total()</b>
     * @param storedPeakPower    存档里的历史峰值（未经衰减）
     * @param daysSincePeakTouch 距上次刷新峰值过了多少天，用于每日 2% 衰减（惰性结算口径）
     */
    public record Snapshot(PowerBreakdown breakdown,
                           long currentMatchPower,
                           long storedPeakPower,
                           int daysSincePeakTouch) {

        public Snapshot {
            if (breakdown == null) {
                throw new IllegalArgumentException("breakdown 不得为 null");
            }
            if (currentMatchPower < 0L) {
                throw new IllegalArgumentException("当前匹配战力不得为负：" + currentMatchPower);
            }
            if (storedPeakPower < 0L) {
                throw new IllegalArgumentException("历史峰值不得为负：" + storedPeakPower);
            }
            if (daysSincePeakTouch < 0) {
                throw new IllegalArgumentException("天数不得为负（时钟回拨应按 0 天处理）："
                        + daysSincePeakTouch);
            }
        }
    }

    /**
     * 峰值记忆规则。
     *
     * @param memoryRatioFixed  matchPower 至少取「峰值 × 本比率」（PEAK_POWER_MEMORY_RATIO = 0.8）
     * @param decayPerDayFixed  峰值每日衰减比例（PEAK_POWER_DAILY_DECAY = 0.02）
     */
    public record Rules(long memoryRatioFixed, long decayPerDayFixed) {

        public Rules {
            if (memoryRatioFixed <= 0L || memoryRatioFixed > FixedPoint.SCALE) {
                throw new IllegalArgumentException("记忆比率必须落在 (0, 1.0] 的定点区间，实际="
                        + memoryRatioFixed + "。0 等于没有峰值记忆，卸兵压分立刻可用");
            }
            if (decayPerDayFixed < 0L || decayPerDayFixed >= FixedPoint.SCALE) {
                throw new IllegalArgumentException("每日衰减必须落在 [0, 1.0) 的定点区间，实际="
                        + decayPerDayFixed + "。衰减 100% 等于峰值当天清零，记忆同样失效");
            }
        }
    }

    /**
     * 战力计算结果。
     *
     * @param displayPower 展示战力（排行榜、联盟展示）
     * @param matchPower   匹配战力（PVP 圈层校验、Bot 校准）
     * @param peakPower    <b>应当写回存档的新峰值</b>（已衰减并与当前值取大）
     * @param breakdown    明细，UI 必须能展开
     * @param peakRaised   本次是否抬高了峰值（用于埋点：压分行为的反向信号）
     * @param peakDecayed  峰值是否因衰减而下降
     * @param peakFromCurrent 新峰值是否来自「当前匹配战力」而不是「衰减后的旧峰值」。
     *                     调用方据此决定衰减锚点：来自当前值就重置为 now，
     *                     来自旧峰值就只能按<b>整天</b>推进锚点以保留余数 ——
     *                     直接把锚点设成 now 的话，一天上线两次的玩家永远算不满一天，峰值永不衰减
     */
    public record Result(long displayPower,
                         long matchPower,
                         long peakPower,
                         PowerBreakdown breakdown,
                         boolean peakRaised,
                         boolean peakDecayed,
                         boolean peakFromCurrent) {

        public Result {
            if (displayPower < 0L || matchPower < 0L || peakPower < 0L) {
                throw new IllegalArgumentException("战力不得为负：display=" + displayPower
                        + ", match=" + matchPower + ", peak=" + peakPower);
            }
            if (breakdown == null) {
                throw new IllegalArgumentException("breakdown 不得为 null");
            }
            if (breakdown.total() != displayPower) {
                // 明细之和必须等于展示战力：玩家点开明细逐项相加却对不上总数，
                // 是「这游戏在骗我」最直接的证据，比任何数值不平衡都伤信任
                throw new IllegalArgumentException("明细之和(" + breakdown.total()
                        + ")必须等于 displayPower(" + displayPower + ")");
            }
        }
    }

    private PowerCalculator() {
    }

    /** 计算战力与应当写回的新峰值。 */
    public static Result compute(Snapshot snapshot, Rules rules) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot 不得为 null");
        }
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        long display = snapshot.breakdown().total();
        long decayedPeak = decayPeak(snapshot.storedPeakPower(), snapshot.daysSincePeakTouch(), rules);
        long peak = Math.max(snapshot.currentMatchPower(), decayedPeak);
        long floor = FixedPoint.round(FixedPoint.mul(FixedPoint.of(peak), rules.memoryRatioFixed()));
        long match = Math.max(snapshot.currentMatchPower(), floor);
        return new Result(display, match, peak, snapshot.breakdown(),
                peak > snapshot.storedPeakPower(),
                decayedPeak < snapshot.storedPeakPower(),
                snapshot.currentMatchPower() >= decayedPeak);
    }

    /** 峰值每日衰减（惰性结算：按天数补算，不跑定时器）。 */
    public static long decayPeak(long storedPeak, int days, Rules rules) {
        if (storedPeak == 0L || days == 0) {
            return storedPeak;
        }
        long retained = FixedPoint.sub(FixedPoint.ONE, rules.decayPerDayFixed());
        long current = storedPeak;
        for (int i = 0; i < days; i++) {
            current = FixedPoint.truncate(FixedPoint.mul(FixedPoint.of(current), retained));
            if (current == 0L) {
                return 0L;
            }
        }
        return current;
    }
}
