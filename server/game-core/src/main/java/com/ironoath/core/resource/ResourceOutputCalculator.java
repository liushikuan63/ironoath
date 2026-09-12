package com.ironoath.core.resource;

import com.ironoath.common.num.FixedPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * 职责：资源产出明细计算 —— B04 §2 的「产出明细面板」，验收 5 要求各项之和与实际产出误差为 0。
 * 依赖：game-common 的 FixedPoint（纯 Java，零框架、零配置依赖）。
 *
 * <p><b>为什么这个面板值得单独做一个类</b>：B04 说它是「转化关键 UI」——
 * 玩家看懂「我为什么产这么点」之后，才会去升级对应建筑或买对应礼包。
 * 但它的技术难点不在展示，在于<b>分解值必须精确等于总量</b>：
 * 一旦面板显示 600+420+120+60=1200 而实际产出是 1198，玩家就会认为游戏在骗他，
 * 这个面板的转化作用会立刻反转成信任破坏。
 *
 * <p><b>保证「误差为 0」的手法不是测试，而是让总量由分解值算出来</b>：
 * {@link Breakdown} 的构造器强制校验 Σ 各行 == totalPerHour，不满足直接抛异常。
 * 于是「面板与实际产出不一致」在类型层面就不可能发生 ——
 * 依赖测试来守这个不变量是脆弱的，因为测试只覆盖它想到的那些组合。
 *
 * <p>百分比加成的取整口径：每个加成各自按 HALF_UP 取整后再相加，
 * <b>总量是取整后各项之和</b>，而不是先加百分比再取整。
 * 这两种算法会差 1~2 单位，而只有前者能让面板逐行相加恰好等于总量。
 */
public final class ResourceOutputCalculator {

    private ResourceOutputCalculator() {
    }

    /**
     * 明细中的一行。
     *
     * @param source      来源标签，直接展示给玩家，如「农田 Lv8」「科技：耕作术」
     * @param amount      该行贡献的每小时产量（整数，已取整）
     * @param isPercent   是否是百分比加成行（决定客户端显示成「+120 (+10%)」还是「+600」）
     * @param percentFixed 百分比值（定点），仅 isPercent=true 时有意义
     */
    public record Line(String source, long amount, boolean isPercent, long percentFixed) {

        public Line {
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("明细行的 source 不得为空");
            }
            if (amount < 0L) {
                throw new IllegalArgumentException("明细行的产量不得为负：source=" + source
                        + ", amount=" + amount);
            }
            if (isPercent && percentFixed < 0L) {
                throw new IllegalArgumentException("百分比不得为负：source=" + source);
            }
            if (!isPercent) {
                percentFixed = 0L;
            }
        }

        /** 基础产出行（建筑、buff 等固定量）。 */
        public static Line flat(String source, long amount) {
            return new Line(source, amount, false, 0L);
        }

        /** 百分比加成行。amount 由 {@link ResourceOutputCalculator} 依据基数算出。 */
        public static Line percent(String source, long amount, long percentFixed) {
            return new Line(source, amount, true, percentFixed);
        }
    }

    /**
     * 一份完整的产出明细。
     *
     * <p>构造时强制校验「各行之和 == 总量」，这是验收 5 的类型级保证。
     */
    public record Breakdown(List<Line> lines, long totalPerHour) {

        public Breakdown {
            lines = List.copyOf(lines);
            long sum = 0L;
            for (Line line : lines) {
                sum += line.amount();
            }
            if (sum != totalPerHour) {
                throw new IllegalStateException("产出明细各行之和不等于总量：Σ=" + sum
                        + ", totalPerHour=" + totalPerHour
                        + "。这个不变量一旦破坏，玩家看到的面板就会与实际产出对不上，"
                        + "「转化关键 UI」会立刻变成信任破坏点。");
            }
            if (totalPerHour < 0L) {
                throw new IllegalArgumentException("总产量不得为负：" + totalPerHour);
            }
        }

        /** 基础产量小计（不含百分比加成行）。 */
        public long baseSubtotal() {
            long sum = 0L;
            for (Line line : lines) {
                if (!line.isPercent()) {
                    sum += line.amount();
                }
            }
            return sum;
        }

        /** 百分比加成小计。 */
        public long bonusSubtotal() {
            return totalPerHour - baseSubtotal();
        }
    }

    /**
     * 计算产出明细。
     *
     * @param baseLines          基础产出行（每个产出建筑一行，amount 由调用方用 Formula 按曲线算好）
     * @param techPercentFixed   科技加成合计（定点），来源 tech 表的 AGRICULTURE 学派
     * @param alliancePercentFixed 联盟加成合计（定点），来源 alliance_tech（B10 落地前传 0）
     * @param buffPercentFixed   道具/活动 buff 加成（定点），来源 item 表（无 buff 时传 0）
     */
    public static Breakdown compute(List<Line> baseLines,
                                    long techPercentFixed,
                                    long alliancePercentFixed,
                                    long buffPercentFixed) {
        if (baseLines == null) {
            throw new IllegalArgumentException("baseLines 不得为 null（无产出建筑请传空列表）");
        }
        requireNonNegative(techPercentFixed, "techPercentFixed");
        requireNonNegative(alliancePercentFixed, "alliancePercentFixed");
        requireNonNegative(buffPercentFixed, "buffPercentFixed");

        long base = 0L;
        for (Line line : baseLines) {
            if (line.isPercent()) {
                throw new IllegalArgumentException("baseLines 里不应包含百分比行：" + line.source()
                        + "。百分比加成由本方法统一计算，混进来会导致重复计入。");
            }
            base += line.amount();
        }

        List<Line> lines = new ArrayList<>(baseLines);
        // 三个加成行始终出现（即使为 0）：面板结构稳定，玩家不会因为「今天少了一行」而困惑。
        // B04 的示例里「道具 buff ---」就是零值也展示的写法。
        lines.add(Line.percent("科技加成", percentOf(base, techPercentFixed), techPercentFixed));
        lines.add(Line.percent("联盟加成", percentOf(base, alliancePercentFixed), alliancePercentFixed));
        lines.add(Line.percent("道具 buff", percentOf(base, buffPercentFixed), buffPercentFixed));

        long total = base
                + percentOf(base, techPercentFixed)
                + percentOf(base, alliancePercentFixed)
                + percentOf(base, buffPercentFixed);
        return new Breakdown(lines, total);
    }

    /** 百分比取整：基数 × 百分比，HALF_UP。每个加成独立取整，总量是取整后之和。 */
    private static long percentOf(long base, long percentFixed) {
        if (base <= 0L || percentFixed <= 0L) {
            return 0L;
        }
        return FixedPoint.round(FixedPoint.mul(FixedPoint.of(base), percentFixed));
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0L) {
            throw new IllegalArgumentException(field + " 不得为负：" + value);
        }
    }
}
