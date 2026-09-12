package com.ironoath.core.march;

import com.ironoath.common.num.FixedPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 职责：行军的全部数值计算（B07 §2）—— 时长、队伍速度、负载上限、采集量。
 * 依赖：game-common 的 FixedPoint（纯 Java，零框架、零配置依赖）。
 *
 * <p><b>队伍速度取最慢兵种</b>，不是取平均也不是取最快：
 * 一支混合队伍必须按最慢的那个走，否则「带一个攻城器（speed 2）和 999 个轻骑兵（speed 10）」
 * 会按接近 10 的速度行军，攻城器的「慢」这个身份就被抹掉了 ——
 * 而慢正是它作为攻城专用兵种的代价（B05 的兵种平衡里，攻城器的强度是用速度换来的）。
 *
 * <p><b>全程整数运算</b>（B00 铁律：禁止 float/double 参与结算）。
 * 时长公式 {@code 距离 × 每格秒数 ÷ 速度 ÷ (1 + 加速)} 的除法顺序是刻意的：
 * 先乘后除，最后才落到秒，中间不取整 —— 否则一段 7 格的短程行军会因为
 * 每格各取整一次而累计出好几秒的误差，而客户端的插值动画会把这个误差放大成可见的抖动。
 */
public final class MarchCalculator {

    private MarchCalculator() {
    }

    /**
     * 队伍速度 = 所有随行兵种里最慢的那个。
     *
     * @param units  队伍构成，unitId（含阶级）→ 数量。数量 &gt; 0 的才算随行
     * @param speeds unitId → 速度，来自 unit 表的 speed 列。<b>必须覆盖 units 里出现的每个 unitId</b>，
     *               缺一个就抛异常而不是当成「无限快」—— 后者会让漏配的兵种变成瞬移外挂
     */
    public static int teamSpeed(Map<String, Long> units, Map<String, Integer> speeds) {
        if (units == null || units.isEmpty()) {
            throw new IllegalArgumentException("units 不得为空：不派兵就没有队伍速度可言");
        }
        if (speeds == null) {
            throw new IllegalArgumentException("speeds 不得为 null");
        }
        int slowest = Integer.MAX_VALUE;
        boolean any = false;
        for (String kind : sortedKeys(units)) {
            Long count = units.get(kind);
            if (count == null || count <= 0L) {
                continue;
            }
            Integer speed = speeds.get(kind);
            if (speed == null) {
                throw new IllegalArgumentException("缺少兵种 " + kind + " 的速度配置。"
                        + "漏配不能当成「无限快」，那会让这个兵种变成瞬移外挂");
            }
            if (speed < 1) {
                throw new IllegalArgumentException("兵种 " + kind + " 的速度必须 >= 1，实际=" + speed
                        + "。速度为 0 会让行军时长变成除零");
            }
            slowest = Math.min(slowest, speed);
            any = true;
        }
        if (!any) {
            throw new IllegalArgumentException("units 里所有兵种的数量都是 0，等同于没有派兵");
        }
        return slowest;
    }

    /**
     * 负载上限 = Σ(兵数 × 该兵种的 load)。
     *
     * <p>这就是「带多少兵去采集」的运力取舍：攻城器 load 40 全兵种最高，
     * 但 speed 2 也最慢，所以运输队必然是慢的 —— 这个矛盾是设计出来的，不是缺陷。
     */
    public static long loadCap(Map<String, Long> units, Map<String, Long> loads) {
        if (units == null || units.isEmpty()) {
            throw new IllegalArgumentException("units 不得为空");
        }
        if (loads == null) {
            throw new IllegalArgumentException("loads 不得为 null");
        }
        long cap = 0L;
        for (String kind : sortedKeys(units)) {
            Long count = units.get(kind);
            if (count == null || count <= 0L) {
                continue;
            }
            Long perUnit = loads.get(kind);
            if (perUnit == null) {
                throw new IllegalArgumentException("缺少兵种 " + kind + " 的负载配置");
            }
            if (perUnit < 0L) {
                throw new IllegalArgumentException("兵种 " + kind + " 的单位负载不得为负：" + perUnit);
            }
            cap += count * perUnit;
        }
        return cap;
    }

    /**
     * 稳定的遍历顺序（按 unitId 字典序）。
     *
     * <p>速度与负载的计算结果与顺序无关，但<b>抛出的异常信息</b>与顺序有关：
     * 漏配了两个兵种时，先报哪一个决定了排查的人先看到什么。
     * 调用方传进来的可能是 HashMap，迭代顺序在不同 JVM 上不同，
     * 于是同一个配置错误会在两台机器上报出不同的兵种 —— 那会让人以为是两个 bug。
     */
    private static List<String> sortedKeys(Map<String, Long> units) {
        List<String> keys = new ArrayList<>(units.keySet());
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    /**
     * 行军时长（秒）= 距离 × 每格秒数 ÷ 队伍速度 ÷ (1 + 加速)。
     *
     * @param distance            曼哈顿距离（格）
     * @param teamSpeed           队伍速度（最慢兵种）
     * @param secondsPerTileFixed 每格秒数（定点），来源 global.MARCH_SECONDS_PER_TILE / SCOUT_SECONDS_PER_TILE
     * @param speedBonusFixed     各类加速之和（定点），来源科技 / 道具 / 联盟（B10/B12 接线）
     * @return 时长秒数，至少 1 秒（0 秒会让「到达」与「出发」同一时刻，延迟队列无法排序）
     */
    public static long durationSeconds(long distance, int teamSpeed,
                                       long secondsPerTileFixed, long speedBonusFixed) {
        if (distance < 0L) {
            throw new IllegalArgumentException("距离不得为负：" + distance);
        }
        if (teamSpeed < 1) {
            throw new IllegalArgumentException("队伍速度必须 >= 1，实际=" + teamSpeed);
        }
        if (secondsPerTileFixed < 0L) {
            throw new IllegalArgumentException("每格秒数不得为负：" + secondsPerTileFixed);
        }
        if (speedBonusFixed < 0L) {
            throw new IllegalArgumentException("加速不得为负：" + speedBonusFixed);
        }
        if (distance == 0L) {
            // 原地行军（例如驻扎在自家城门口）：给 1 秒而不是 0 秒，
            // 0 秒会让 arriveAt == startAt，延迟队列里它与「立刻到期」无法区分
            return 1L;
        }
        long numerator = distance * secondsPerTileFixed;
        if (numerator / distance != secondsPerTileFixed) {
            throw new IllegalArgumentException("行军时长计算溢出 long：distance=" + distance
                    + ", secondsPerTileFixed=" + secondsPerTileFixed);
        }
        // 先除速度（定点），再除 (1 + 加速)，最后一次性落到秒
        long perSpeed = FixedPoint.div(numerator, FixedPoint.of(teamSpeed));
        long withBonus = FixedPoint.div(perSpeed, FixedPoint.ONE + speedBonusFixed);
        long seconds = FixedPoint.round(withBonus);
        return Math.max(1L, seconds);
    }

    /**
     * 采集量：采满一次负载固定需要 {@code fillSeconds} 秒，与负载大小无关。
     *
     * <p><b>为什么按「采满所需时间」定而不是按「每秒采多少」定</b>：
     * 按速率定的话负载越大的队伍采得越久，玩家会把所有兵都换成攻城器（load 40，全兵种最高）
     * 去当运输队，兵种身份被采集效率抹平。按采满时间定，负载只影响「一次能带多少回家」，
     * 不影响「要待多久」—— 于是「带多少兵去采」是纯粹的运力取舍，不污染战斗编成。
     *
     * @param loadCap     负载上限
     * @param elapsedMs   已采集的毫秒数
     * @param fillSeconds 采满一次负载所需的秒数，来源 global.GATHER_FILL_SECONDS
     * @return 已采集量，封顶在 loadCap
     */
    public static long gathered(long loadCap, long elapsedMs, long fillSeconds) {
        if (loadCap < 0L) {
            throw new IllegalArgumentException("负载上限不得为负：" + loadCap);
        }
        if (elapsedMs < 0L) {
            throw new IllegalArgumentException("已采集时长不得为负：" + elapsedMs);
        }
        if (fillSeconds < 1L) {
            throw new IllegalArgumentException("采满时长必须为正，否则瞬间采满，实际=" + fillSeconds);
        }
        if (loadCap == 0L || elapsedMs == 0L) {
            return 0L;
        }
        long fillMs = fillSeconds * 1000L;
        if (elapsedMs >= fillMs) {
            return loadCap;
        }
        // 先乘后除，避免 elapsedMs/fillMs 先整除成 0
        long amount = loadCap * elapsedMs / fillMs;
        return Math.min(loadCap, Math.max(0L, amount));
    }

    /**
     * 召回的返程时长（毫秒）= 已行军消耗的时间（B07 §2、验收 7）。
     *
     * <p><b>用时间而不是格数</b>：格数插值会丢小数（走了 3.7 格记成 3 格），
     * 而时间是精确的，返程时长 = 已消耗时长 ⇒ 位置连续，
     * 不会出现「召回之后反而往前走了一格」这种玩家一眼就能看出的错位。
     *
     * <p>去程中被召回 ⇒ 返程 = 已走的时间；已到达（驻扎/采集）后被召回 ⇒ 返程 = 完整去程时长。
     */
    public static long recallMillis(March march, long now) {
        if (march == null) {
            throw new IllegalArgumentException("march 不得为 null");
        }
        long fullTrip = march.arriveAt() - march.startAt();
        if (march.status() == March.Status.MARCHING) {
            long elapsed = Math.max(0L, Math.min(now, march.arriveAt()) - march.startAt());
            return elapsed;
        }
        return Math.max(0L, fullTrip);
    }
}
