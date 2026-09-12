package com.ironoath.core.power;

import com.ironoath.common.num.FixedPoint;

/**
 * 职责：战力圈层校验器（B08 §2）。在「发起攻击 / 搜索目标 / 发起集结」三个入口统一调用。
 * 依赖：game-common 的 FixedPoint（纯 Java，零框架、零配置依赖）。
 *
 * <p><b>这是全项目最容易被做错的一处，做错的方向也最一致</b>：把它实现成「禁止强者打弱者」
 * 或「给弱者自动补偿」。两种做法都会同时摧毁强者的付费动力与弱者的社交动力 ——
 * 前者让练度与付费的回报无法兑现（「我练了个寂寞」），
 * 后者让玩家失去抱团的理由（独狼也能活，社交系统就废了）。
 *
 * <p><b>正确的做法是一条规则的对称性</b>：
 * <pre>
 *   合法攻击区间：target.matchPower ∈ [ self × lower , self × upper ]
 * </pre>
 * 从弱者 A(100) 看，打不到 B(300)，因为 300 &gt; 100×2 —— 弱者不被碾压；
 * 从强者 B(300) 看，也打不到 A(100)，因为 100 &lt; 300×0.5 —— 同一条规则双向闭合。
 * <b>不需要额外写「禁止虐菜」</b>，而区间<b>内部</b>（300 打 150）强者必须能打、能拿满收益，
 * 因为被打是社交的起点：玩家被打了才会去叫人，才会加入联盟，才会产生恩怨。
 *
 * <p><b>√N 破圈是弱者的出路</b>（B08 §3）：
 * <pre>
 *   upper = 2.0 × √N      lower = 0.5 / √N
 *   N=1  → [0.50x, 2.0x]     N=4  → [0.25x, 4.0x]
 *   N=16 → [0.13x, 8.0x]     N=25 → [0.10x, 10.0x]
 * </pre>
 * 用 √N 而不是 N：集结总战力本就随人数叠加，若门槛也线性放宽，高战集结会无限膨胀。
 * √N 让「人多的优势」真实存在但有边际递减，逼联盟在「堆人数」和「练战力」之间取舍。
 * <b>下限必须同步放宽</b>，否则 16 人集结反而打不了比自己弱很多的目标，不合逻辑。
 *
 * <p><b>√N 必须用 {@link FixedPoint#sqrt}</b>，禁止 {@code Math.sqrt(double)}：
 * 边界值（战力恰好等于 upper 倍）的判定会因浮点精度在不同 JVM 上分歧 1 个定点单位，
 * 而那 1 个单位正好决定一次攻击被允许还是被拒绝。
 */
public final class PowerBandGuard {

    /** 拒绝原因。<b>只有两种</b>，而且两种都是「提示」而不是「惩罚」。 */
    public enum RejectReason {
        /** 通过。 */
        NONE,
        /** 目标战力高于上限：对方太强。 */
        TARGET_TOO_STRONG,
        /** 目标战力低于下限：对方太弱。 */
        TARGET_TOO_WEAK
    }

    /**
     * 圈层规则。全部来自 match_rule / global 表（铁律 1：不硬编码）。
     *
     * @param lowerRatioFixed 单人下限倍率（定点），来源 PVP_POWER_MIN_RATIO = 0.5
     * @param upperRatioFixed 单人上限倍率（定点），来源 PVP_POWER_MAX_RATIO = 2.0
     * @param rallyBandSqrt   集结是否按 √N 放宽。留这个开关是为了能一键回退到「线性 N」做对比实验 ——
     *                        但生产必须是 true，线性放宽会让高战集结无限膨胀（B08 禁止项）
     */
    public record Rules(long lowerRatioFixed, long upperRatioFixed, boolean rallyBandSqrt) {

        public Rules {
            if (lowerRatioFixed <= 0L || lowerRatioFixed > FixedPoint.SCALE) {
                throw new IllegalArgumentException("下限倍率必须落在 (0, 1.0] 的定点区间，实际="
                        + lowerRatioFixed);
            }
            if (upperRatioFixed < FixedPoint.SCALE) {
                throw new IllegalArgumentException("上限倍率必须 >= 1.0，否则谁都打不了任何人，实际="
                        + upperRatioFixed);
            }
            if (upperRatioFixed <= lowerRatioFixed) {
                throw new IllegalArgumentException("上限倍率必须大于下限倍率，否则区间为空："
                        + "lower=" + lowerRatioFixed + ", upper=" + upperRatioFixed);
            }
        }
    }

    /**
     * 一次校验的结果。
     *
     * @param allowed   是否允许
     * @param reason    拒绝原因；通过时为 {@link RejectReason#NONE}
     * @param lowerFixed 实际生效的下限倍率（含 √N 放宽）
     * @param upperFixed 实际生效的上限倍率
     * @param lowerBound 下限对应的目标战力绝对值
     * @param upperBound 上限对应的目标战力绝对值
     * @param message   给玩家的文案。<b>拒绝时必须非空</b>（B08：绝不静默失败），
     *                  而且必须是「提示」而不是「惩罚」的口吻
     */
    public record BandCheckResult(boolean allowed,
                                  RejectReason reason,
                                  long lowerFixed,
                                  long upperFixed,
                                  long lowerBound,
                                  long upperBound,
                                  String message) {

        public BandCheckResult {
            if (reason == null) {
                throw new IllegalArgumentException("reason 不得为 null，通过时请用 RejectReason.NONE");
            }
            if (allowed != (reason == RejectReason.NONE)) {
                throw new IllegalArgumentException("allowed 与 reason 必须一致：allowed=" + allowed
                        + ", reason=" + reason);
            }
            if (!allowed && (message == null || message.isBlank())) {
                throw new IllegalArgumentException("拒绝时必须给出文案，绝不静默失败（B08 §2）");
            }
            if (lowerBound < 0L || upperBound < 0L) {
                throw new IllegalArgumentException("区间端点不得为负：lower=" + lowerBound
                        + ", upper=" + upperBound);
            }
            if (upperBound < lowerBound) {
                throw new IllegalArgumentException("区间上限不得小于下限：lower=" + lowerBound
                        + ", upper=" + upperBound);
            }
        }

        /** 区间宽度（上限 - 下限），供埋点与调试。 */
        public long bandWidth() {
            return upperBound - lowerBound;
        }
    }

    /** 目标太强时的文案（B08 §2 的表格原文）。 */
    public static final String MESSAGE_TOO_STRONG = "对方实力远超于你，无法发起进攻";
    /** 目标太弱时的文案（B08 §2 的表格原文）。注意这是提示不是惩罚。 */
    public static final String MESSAGE_TOO_WEAK = "对方实力远弱于你，无需出手";

    /**
     * 一名攻方在给定集结人数下实际生效的区间。
     *
     * <p><b>与目标无关</b>：区间只由攻方匹配战力与 √N 决定，所以可以单独取出来下发。
     * 目标搜索的响应就带了 bandLower / bandUpper —— 让玩家看见「我能打的范围」，
     * 而不是只看见一堆被筛过的目标却不知道为什么（B08 §1：玩家对判定口径极度敏感）。
     *
     * @param lowerFixed 下限倍率（含 √N 放宽）
     * @param upperFixed 上限倍率（含 √N 放宽）
     * @param lowerBound 下限对应的目标战力绝对值
     * @param upperBound 上限对应的目标战力绝对值
     */
    public record Band(long lowerFixed, long upperFixed, long lowerBound, long upperBound) {

        public Band {
            if (lowerFixed < 0L || upperFixed < 0L || lowerBound < 0L || upperBound < 0L) {
                throw new IllegalArgumentException("区间各值不得为负：lowerFixed=" + lowerFixed
                        + ", upperFixed=" + upperFixed + ", lowerBound=" + lowerBound
                        + ", upperBound=" + upperBound);
            }
            if (upperBound < lowerBound) {
                throw new IllegalArgumentException("区间上限不得小于下限：lower=" + lowerBound
                        + ", upper=" + upperBound);
            }
        }

        /** 区间宽度，供埋点与调试。 */
        public long width() {
            return upperBound - lowerBound;
        }

        /** 目标战力是否落在闭区间内。 */
        public boolean contains(long targetMatchPower) {
            return targetMatchPower >= lowerBound && targetMatchPower <= upperBound;
        }
    }

    private PowerBandGuard() {
    }

    /**
     * 算出攻方当前生效的区间（不做判定）。
     *
     * <p>{@link #check} 复用本方法，这样「搜索时下发的区间」与「攻击时判定的区间」
     * 必然同源 —— 两处各算一遍 √N 是最典型的口径分叉，
     * 表现是玩家按列表里的区间挑了目标、点下去却被拒。
     *
     * @return 攻方匹配战力为 0 时返回全 0 的空区间（没有兵就没有可打的范围）
     */
    public static Band bandOf(long attackerMatchPower, int rallySize, Rules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        if (attackerMatchPower < 0L) {
            throw new IllegalArgumentException("战力不得为负：" + attackerMatchPower);
        }
        if (rallySize < 1) {
            throw new IllegalArgumentException("集结人数必须 >= 1（单人攻击传 1），实际=" + rallySize);
        }
        if (attackerMatchPower == 0L) {
            return new Band(0L, 0L, 0L, 0L);
        }
        long sqrtN = rules.rallyBandSqrt()
                ? FixedPoint.sqrt(FixedPoint.of(rallySize))
                : FixedPoint.of(rallySize);
        if (sqrtN <= 0L) {
            throw new IllegalStateException("√N 算出了非正数：rallySize=" + rallySize
                    + ", rallyBandSqrt=" + rules.rallyBandSqrt());
        }
        // upper = upperRatio × √N ；lower = lowerRatio / √N
        long upperFixed = FixedPoint.mul(rules.upperRatioFixed(), sqrtN);
        // √N 很大时 lowerRatio/√N 可能被整除成 0，那意味着「任何弱目标都能打」。
        // 这不是 bug 而是数学结果：下限为 0 表示不设下限，区间端点仍然合法
        long lowerFixed = FixedPoint.div(rules.lowerRatioFixed(), sqrtN);
        long lowerBound = FixedPoint.round(FixedPoint.mul(FixedPoint.of(attackerMatchPower), lowerFixed));
        long upperBound = FixedPoint.round(FixedPoint.mul(FixedPoint.of(attackerMatchPower), upperFixed));
        return new Band(lowerFixed, upperFixed, lowerBound, upperBound);
    }

    /**
     * 校验一次攻击/搜索/集结是否落在合法战力区间内。
     *
     * @param attackerMatchPower 攻方的<b>匹配战力</b>（不是展示战力 —— 展示战力含建筑与科技，
     *                           而能打出去的只有当前部队与上阵主将，见 {@link PowerCalculator}）
     * @param targetMatchPower   守方的匹配战力
     * @param rallySize          集结参与人数，单人 = 1
     */
    public static BandCheckResult check(long attackerMatchPower, long targetMatchPower,
                                        int rallySize, Rules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        if (targetMatchPower < 0L) {
            throw new IllegalArgumentException("战力不得为负：target=" + targetMatchPower);
        }
        if (attackerMatchPower == 0L) {
            // 战力为 0 意味着没有可战斗的部队。区间会是 [0, 0]，
            // 任何目标都「超出上限」，给出的文案会是「对方太强」—— 那是误导，
            // 真实原因是自己没兵，必须单独说清楚
            return new BandCheckResult(false, RejectReason.TARGET_TOO_STRONG,
                    0L, 0L, 0L, 0L,
                    "你没有可出征的部队，先去训练士兵（战力为 0 时任何目标都打不了）");
        }
        Band band = bandOf(attackerMatchPower, rallySize, rules);

        // 区间是闭区间：B08 验收 1 要求 0.5x 与 2.0x 都算通过，0.3x 与 2.1x 都算拒绝
        if (targetMatchPower > band.upperBound()) {
            return new BandCheckResult(false, RejectReason.TARGET_TOO_STRONG,
                    band.lowerFixed(), band.upperFixed(), band.lowerBound(), band.upperBound(),
                    MESSAGE_TOO_STRONG);
        }
        if (targetMatchPower < band.lowerBound()) {
            return new BandCheckResult(false, RejectReason.TARGET_TOO_WEAK,
                    band.lowerFixed(), band.upperFixed(), band.lowerBound(), band.upperBound(),
                    MESSAGE_TOO_WEAK);
        }
        return new BandCheckResult(true, RejectReason.NONE,
                band.lowerFixed(), band.upperFixed(), band.lowerBound(), band.upperBound(), null);
    }

    /**
     * 双向闭合校验（B08 验收 2）。
     *
     * <p>单独提供这个方法是因为「A 打不到 B」与「B 打不到 A」是<b>两次独立调用</b>，
     * 只测一次很容易漏掉另一半 —— 而只闭合一半的规则等于没有规则：
     * 若只有弱者打不到强者、强者却能打弱者，那就是「禁止虐菜」的反面
     * （允许虐菜），生态同样会崩。
     *
     * <p><b>只有测试调用它</b>：生产里每一次判定都只发生在"某一个人打某一个人"这一个方向上
     * （发起攻击、搜索目标、集结各问各的），没有一处天生需要同时算两个方向。
     * 所以这不是"接了一半"的闸门，而是验收 2 的闭合性检查器 —— 别删，也别以为线上在做双向校验。
     *
     * @return true 表示双方互相都无法选中对方
     */
    public static boolean isMutuallyExcluded(long powerA, long powerB, Rules rules) {
        BandCheckResult aToB = check(powerA, powerB, 1, rules);
        BandCheckResult bToA = check(powerB, powerA, 1, rules);
        return !aToB.allowed() && !bToA.allowed();
    }
}
