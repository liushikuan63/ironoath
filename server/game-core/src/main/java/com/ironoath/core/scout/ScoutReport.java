package com.ironoath.core.scout;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.rng.Rng;
import com.ironoath.core.world.Coord;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：侦查报告与情报误差（B07 §3、验收 8）。
 * 依赖：game-common 的 Rng 与 FixedPoint（纯 Java，零框架）。
 *
 * <p><b>情报必须有误差，而且误差随等级差放大</b>。这不是拟真装饰，是生态机制：
 * 越级挑战时你连对面有多少兵都看不清，所以要么集结（B08 给的组团出路），要么放弃。
 * 若误差恒定，越级挑战就只是「数值不够」，而数值可以靠养成慢慢补 ——
 * 那会鼓励单人硬堆而不是社交，直接违反 C00 公理一。
 *
 * <p><b>误差是 seeded 的</b>：同一份报告重复读取永远得到同一组数字。
 * 这很关键 —— 如果每次打开战报数字都在变，玩家会认为界面有 bug，
 * 而「情报不可靠」与「界面在乱跳」是两件完全不同的事，后者只会摧毁信任。
 *
 * <p><b>报告是快照，不是实时视图</b>：存下的是「侦查那一刻看到的东西（含误差）」，
 * 展示时必须标注情报时间，超时置灰（B07 验收 9）。
 */
public final class ScoutReport {

    /**
     * 误差幅度（定点）。
     *
     * @param levelDiff      侦查方与目标的等级差（绝对值）
     * @param baseFixed      同级时的误差幅度，来源 global.SCOUT_ERROR_BASE（0.05）
     * @param perLevelFixed  每级增加的幅度，来源 global.SCOUT_ERROR_PER_LEVEL（0.0175）
     * @param maxFixed       封顶，来源 global.SCOUT_ERROR_MAX（0.40）
     * @return 误差幅度（定点），实际取值在 [-幅度, +幅度] 内均匀分布
     */
    public static long errorFixed(int levelDiff, long baseFixed, long perLevelFixed, long maxFixed) {
        if (levelDiff < 0) {
            throw new IllegalArgumentException("等级差不得为负（应传绝对值）：" + levelDiff);
        }
        if (baseFixed < 0L || perLevelFixed < 0L || maxFixed < 0L) {
            throw new IllegalArgumentException("误差参数不得为负：base=" + baseFixed
                    + ", perLevel=" + perLevelFixed + ", max=" + maxFixed);
        }
        if (baseFixed > maxFixed) {
            throw new IllegalArgumentException("基础误差(" + baseFixed + ")不得大于封顶(" + maxFixed
                    + ")：那样同级侦查的误差就已经超过上限，封顶形同虚设");
        }
        long grown = baseFixed + FixedPoint.mul(FixedPoint.of(levelDiff), perLevelFixed);
        return Math.min(maxFixed, grown);
    }

    /**
     * 一份侦查报告。
     *
     * @param reportId      报告 id
     * @param scoutPlayerId 侦查方
     * @param target        目标坐标
     * @param targetId      目标实体 id（玩家城 id / 野怪 id）；null 表示空地
     * @param targetLevel   目标等级（用于算等级差，也是报告里唯一<b>无误差</b>的字段 ——
     *                      等级从外观就能看出来，给它加误差只会让玩家觉得系统在耍他）
     * @param createdAt     生成时刻
     * @param expiresAt     过期时刻 = createdAt + SCOUT_REPORT_TTL_SECONDS
     * @param observed      观测到的数值（已加误差），键为指标名
     * @param errorFixed    本报告使用的误差幅度，随报告一起下发。
     *                      <b>必须下发</b>：UI 要显示「±12%」，
     *                      否则玩家会把带误差的数字当成精确值来做决策，
     *                      那是比没有情报更糟的结果
     * @param seed          误差用的种子，随报告存下来以便复现（客服核查「这份情报为什么这么离谱」）
     */
    public record Report(String reportId,
                         String scoutPlayerId,
                         Coord target,
                         String targetId,
                         int targetLevel,
                         long createdAt,
                         long expiresAt,
                         Map<String, Long> observed,
                         long errorFixed,
                         long seed) {

        public Report {
            if (reportId == null || reportId.isBlank()) {
                throw new IllegalArgumentException("reportId 不得为空");
            }
            if (scoutPlayerId == null || scoutPlayerId.isBlank()) {
                throw new IllegalArgumentException("scoutPlayerId 不得为空");
            }
            if (target == null) {
                throw new IllegalArgumentException("target 不得为 null");
            }
            if (targetLevel < 0) {
                throw new IllegalArgumentException("targetLevel 不得为负：" + targetLevel);
            }
            if (createdAt <= 0L) {
                throw new IllegalArgumentException("createdAt 必须为正的服务端时间戳：" + createdAt);
            }
            if (expiresAt <= createdAt) {
                throw new IllegalArgumentException("过期时刻必须晚于生成时刻：expiresAt=" + expiresAt
                        + ", createdAt=" + createdAt + "。有效期为 0 的情报等于没有情报");
            }
            if (errorFixed < 0L || errorFixed > FixedPoint.SCALE) {
                throw new IllegalArgumentException("误差幅度必须落在 [0, 1.0] 的定点区间，实际=" + errorFixed);
            }
            observed = observed == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(observed));
        }

        /** 是否已过期。过期后 UI 必须置灰且不可用于决策（B07 验收 9）。 */
        public boolean expired(long now) {
            return now >= expiresAt;
        }

        /** 剩余有效毫秒；已过期返回 0，<b>绝不返回负数</b>。 */
        public long remainingMs(long now) {
            return Math.max(0L, expiresAt - now);
        }
    }

    private ScoutReport() {
    }

    /**
     * 生成一份带误差的报告。
     *
     * @param truth       真实数值（指标名 → 真实值）
     * @param errorFixed  误差幅度（定点），由 {@link #errorFixed} 算出
     * @param seed        误差种子。<b>必须由服务端生成</b>，不能来自请求字段 ——
     *                    否则玩家可以枚举种子直到刷出一份「看起来敌方很弱」的情报，
     *                    那等于取消了误差（与开箱种子同一条纪律）
     */
    public static Map<String, Long> distort(Map<String, Long> truth, long errorFixed, long seed) {
        if (truth == null) {
            throw new IllegalArgumentException("truth 不得为 null（没有指标请传空 Map）");
        }
        if (errorFixed < 0L || errorFixed > FixedPoint.SCALE) {
            throw new IllegalArgumentException("误差幅度必须落在 [0, 1.0]，实际=" + errorFixed);
        }
        Map<String, Long> out = new LinkedHashMap<>();
        int index = 0;
        // 按传入顺序遍历并为每个指标 fork 一个子流：
        // 各指标的误差互相独立，且将来增删指标不会平移其它指标的误差
        for (Map.Entry<String, Long> entry : truth.entrySet()) {
            long actual = entry.getValue() == null ? 0L : entry.getValue();
            Rng rng = Rng.of(seed).fork(index++);
            if (actual == 0L || errorFixed == 0L) {
                out.put(entry.getKey(), actual);
                continue;
            }
            // 在 [-errorFixed, +errorFixed] 内均匀取一个偏移
            long delta = rng.range(-errorFixed, errorFixed);
            long distorted = actual + FixedPoint.round(FixedPoint.mul(FixedPoint.of(actual), delta));
            // 观测值不得为负，也不得因为误差而凭空变成 0：
            // 一支确实存在的军队被侦查成「0 兵」会让玩家以为目标是空的，那是致命误导
            out.put(entry.getKey(), Math.max(1L, distorted));
        }
        return out;
    }

    /** 报告里应当包含哪些指标。集中在一处，避免各处随手加键导致 UI 读不到。 */
    public static final List<String> STANDARD_METRICS = List.of(
            "power", "totalUnits", "infantry", "cavalry", "archer", "siege",
            "wood", "stone", "iron", "grain", "gold");
}
