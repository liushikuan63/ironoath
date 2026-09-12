package com.ironoath.core.hero;

import com.ironoath.common.num.FixedPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
// List.of 不接受 null，而「副将位空着」正是用 null 表示的，所以这些用例必须用 Arrays.asList
import java.util.Arrays;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：武将养成与队伍加成的数值验证（B06 验收 5 / 6 / 7 / 9，以及乘区隔离）。
 * 依赖：纯 JUnit，不需要容器、不需要配置表（铁律 2）。
 *
 * <p><b>期望值用 BigDecimal 独立算一遍，不复用实现的定点运算链</b>：
 * 验收 6 要的是「属性 = 配置表公式手算值，误差 0」。
 * 如果期望值也调用 FixedPoint 的那几个方法，测试就只是在断言「实现等于它自己」，
 * 一旦定点链里某一步取整口径错了，测试会跟着一起错。
 * 独立算一遍才能在实现与公式分叉时真的失败。
 */
class HeroSystemTest {

    /** 逐项取自 contract/config/global.json 与 curve.json，与生产配置一致。 */
    private static HeroRules rules() {
        return new HeroRules(
                FixedPoint.parse("0.02"),     // HERO_LEVEL_STEP
                FixedPoint.parse("0.10"),     // HERO_STAR_STEP
                FixedPoint.parse("0.08"),     // HERO_AWAKEN_STEP
                5,                            // HERO_STAR_MAX
                10,                           // HERO_SKILL_MAX_LEVEL
                10L,                          // HERO_ATTR_PER_PERCENT
                FixedPoint.parse("0.50"),     // HERO_SUB_BONUS_RATIO
                FixedPoint.parse("2.00"),     // HERO_ZONE_CAP
                FixedPoint.parse("0.08"),     // HERO_BOND_BONUS
                5L,                           // TROOP_PER_COMMAND
                3,                            // LINEUP_HERO_COUNT
                3,                            // LINEUP_PRESET_COUNT
                FixedPoint.parse("20"),       // curve HERO_LEVEL_EXP base
                FixedPoint.parse("1.08"),     // curve HERO_LEVEL_EXP ratio
                FixedPoint.parse("1.20"));    // curve HERO_GROWTH exponent
    }

    /** hero_ssr_01 裴惊澜：might 100 / command 95 / wisdom 90，growthRate 1.20。 */
    private static final HeroAttrs SSR_01 = HeroAttrs.of(100L, 95L, 90L);
    private static final long SSR_01_GROWTH = FixedPoint.parse("1.20");

    /** hero_sr_01 卫无咎：might 70 / command 65 / wisdom 60，growthRate 1.10。 */
    private static final HeroAttrs SR_01 = HeroAttrs.of(70L, 65L, 60L);
    private static final long SR_01_GROWTH = FixedPoint.parse("1.10");

    /** 独立的十进制手算：base × growthRate × (等级因子 × 星级因子 × 觉醒因子)。 */
    private static long handCalc(long base, String growthRate, int level, int star, int awaken) {
        BigDecimal levelFactor = BigDecimal.ONE.add(
                new BigDecimal(level - 1).multiply(new BigDecimal("0.02")));
        BigDecimal starFactor = BigDecimal.ONE.add(
                new BigDecimal(star - 1).multiply(new BigDecimal("0.10")));
        BigDecimal awakenFactor = BigDecimal.ONE.add(
                new BigDecimal(awaken).multiply(new BigDecimal("0.08")));
        return BigDecimal.valueOf(base)
                .multiply(new BigDecimal(growthRate))
                .multiply(levelFactor).multiply(starFactor).multiply(awakenFactor)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    // ---------- 验收 6：满级满星属性 = 手算值 ----------

    @Test
    @DisplayName("验收6：满级满星满觉醒的三维属性逐维等于十进制手算值（误差 0）")
    void maxedAttributesMatchHandCalculation() {
        HeroAttrs attrs = HeroCalculator.finalAttrs(SSR_01, SSR_01_GROWTH,
                100, 5, 3, HeroAttrs.zero(), rules());

        assertThat(attrs.might()).as("武力 = 100 × 1.20 × 2.98 × 1.40 × 1.24")
                .isEqualTo(handCalc(100L, "1.20", 100, 5, 3));
        assertThat(attrs.command()).as("统率 = 95 × 1.20 × 2.98 × 1.40 × 1.24")
                .isEqualTo(handCalc(95L, "1.20", 100, 5, 3));
        assertThat(attrs.wisdom()).as("智力 = 90 × 1.20 × 2.98 × 1.40 × 1.24")
                .isEqualTo(handCalc(90L, "1.20", 100, 5, 3));
    }

    @Test
    @DisplayName("验收6：1 级 1 星 0 觉醒的裸装属性 = 基础 × 成长率（三个养成因子此时都是 1）")
    void freshHeroAttributesAreBaseTimesGrowthRate() {
        HeroAttrs attrs = HeroCalculator.finalAttrs(SSR_01, SSR_01_GROWTH,
                1, 1, 0, HeroAttrs.zero(), rules());
        assertThat(attrs.might()).isEqualTo(handCalc(100L, "1.20", 1, 1, 0));
        assertThat(attrs.might()).as("100 × 1.20 = 120").isEqualTo(120L);
        assertThat(attrs.command()).isEqualTo(114L);
        assertThat(attrs.wisdom()).isEqualTo(108L);
    }

    @Test
    @DisplayName("装备固定值加在养成结果之外，不被等级/星级/觉醒放大")
    void equipFlatIsNotAmplifiedByGrowth() {
        HeroAttrs equip = HeroAttrs.of(45L, 50L, 0L);
        HeroAttrs lv1 = HeroCalculator.finalAttrs(SSR_01, SSR_01_GROWTH, 1, 1, 0, equip, rules());
        HeroAttrs lv100 = HeroCalculator.finalAttrs(SSR_01, SSR_01_GROWTH, 100, 5, 3, equip, rules());
        HeroAttrs lv1Bare = HeroCalculator.finalAttrs(SSR_01, SSR_01_GROWTH, 1, 1, 0, HeroAttrs.zero(), rules());
        HeroAttrs lv100Bare = HeroCalculator.finalAttrs(SSR_01, SSR_01_GROWTH, 100, 5, 3, HeroAttrs.zero(), rules());

        // 装备的贡献在两个等级下必须完全相同 —— 若被养成因子放大，
        // 同一件装备在满级武将身上的价值会是 1 级的 5 倍，玩家会把好装备全堆在主力身上，
        // 装备投放退化成一次性
        assertThat(lv1.might() - lv1Bare.might()).isEqualTo(45L);
        assertThat(lv100.might() - lv100Bare.might()).isEqualTo(45L);
        assertThat(lv1.command() - lv1Bare.command()).isEqualTo(50L);
        assertThat(lv100.command() - lv100Bare.command()).isEqualTo(50L);
    }

    // ---------- 验收 7：稀有度比值与养成进度无关 ----------

    @Test
    @DisplayName("验收7：SSR/SR 的属性比值恒为 1.5944，在 1 级与满级都成立，且落在 [1.5,1.7]")
    void rarityRatioIsInvariantAcrossGrowth() {
        // 裸装比值 = (Σ三维 × 成长率) 之比 = (285×1.20)/(195×1.10) = 342/214.5
        BigDecimal expected = new BigDecimal("342").divide(new BigDecimal("214.5"), 6, RoundingMode.HALF_UP);
        assertThat(expected.doubleValue()).as("B02 已把 1.5944 这个数写进 hero 表的 designNote")
                .isBetween(1.5943d, 1.5945d);

        for (int[] growth : new int[][]{{1, 1, 0}, {50, 3, 1}, {100, 5, 3}}) {
            int level = growth[0];
            int star = growth[1];
            int awaken = growth[2];
            long ssr = HeroCalculator.finalAttrs(SSR_01, SSR_01_GROWTH, level, star, awaken,
                    HeroAttrs.zero(), rules()).total();
            long sr = HeroCalculator.finalAttrs(SR_01, SR_01_GROWTH, level, star, awaken,
                    HeroAttrs.zero(), rules()).total();
            double ratio = (double) ssr / sr;
            assertThat(ratio)
                    .as("Lv%d ★%d 觉醒%d 时 SSR/SR 必须仍在 [1.5,1.7]（B06 验收 7）", level, star, awaken)
                    .isBetween(1.5d, 1.7d);
            // 容差 ±0.006 而不是「完全相等」：属性是逐维取整的整数，
            // 1 级时 SR 的统率 65×1.10=71.5 会取整成 72，比值因此是 1.5907 而非理论的 1.5944。
            // 这是取整噪声而不是模型漂移，且随属性变大迅速消失（满级时误差 < 0.001）。
            // 真正要守的是「漂移不随养成进度系统性放大」，所以下面再断言满级时更接近理论值。
            assertThat(ratio)
                    .as("比值不得随养成进度系统性漂移，否则「差距只有 1.6 倍」这条生态红线只在某个等级成立")
                    .isBetween(expected.doubleValue() - 0.006d, expected.doubleValue() + 0.006d);
        }
        // 满级时属性基数大，取整噪声被摊薄，比值应当几乎精确等于理论值
        long ssrMax = HeroCalculator.finalAttrs(SSR_01, SSR_01_GROWTH, 100, 5, 3,
                HeroAttrs.zero(), rules()).total();
        long srMax = HeroCalculator.finalAttrs(SR_01, SR_01_GROWTH, 100, 5, 3,
                HeroAttrs.zero(), rules()).total();
        assertThat((double) ssrMax / srMax).isBetween(
                expected.doubleValue() - 0.002d, expected.doubleValue() + 0.002d);
    }

    @Test
    @DisplayName("禁止项：同等投入下 SSR/R 不得超过 2 倍（生态红线）")
    void ssrToRRatioNeverExceedsTwo() {
        // hero_r_01 罗青禾 58/55/52 = 165，growthRate 1.09，awakenMax 1
        HeroAttrs r01 = HeroAttrs.of(58L, 55L, 52L);
        // 同等投入比较：等级/星级/觉醒全部对齐。
        // 这是 B02 在 hero.json designNote 里已经裁定的口径 —— 稀有度的体感差距
        // 由「技能强度（SSR 主技能期望约为 R 的 4.7 倍）」与「觉醒上限（SSR 3 / R 1）」承担，
        // 这两项都不进入满级战力比的计算。若在这里让 SSR 觉醒 3 阶、R 觉醒 1 阶，
        // 比值会到 2.185，那量的是「投入上限之差」而不是「稀有度之差」。
        long ssr = HeroCalculator.finalAttrs(SSR_01, SSR_01_GROWTH, 100, 5, 1,
                HeroAttrs.zero(), rules()).total();
        long r = HeroCalculator.finalAttrs(r01, FixedPoint.parse("1.09"), 100, 5, 1,
                HeroAttrs.zero(), rules()).total();
        assertThat((double) ssr / r).as("同等投入下 SSR/R 必须 <= 2.0").isLessThanOrEqualTo(2.0d);
        assertThat((double) ssr / r).as("B02 实测值是 1.9016，改动养成参数后必须复核")
                .isBetween(1.85d, 1.95d);

        // 把「各练满」的实际差距也量出来记录在案：SSR 觉醒 3 阶 vs R 觉醒 1 阶。
        // 这个数字大于 2.0，是刻意接受的结果而不是疏漏 —— 但它必须可见，
        // 否则以后有人把觉醒阶差再拉大时，没有任何东西会提醒他生态红线正在被侵蚀。
        long ssrMaxed = HeroCalculator.finalAttrs(SSR_01, SSR_01_GROWTH, 100, 5, 3,
                HeroAttrs.zero(), rules()).total();
        double maxedRatio = (double) ssrMaxed / r;
        assertThat(maxedRatio)
                .as("各自练到上限时的 SSR/R 差距（含觉醒阶差）不得继续扩大，当前口径上限 2.3")
                .isLessThanOrEqualTo(2.3d);
    }

    @Test
    @DisplayName("等级/星级/觉醒因子与稀有度无关：成长率同为 1.0 时，属性之比恰为三维之比")
    void growthFactorsAreRarityIndependent() {
        HeroAttrs a = HeroCalculator.finalAttrs(HeroAttrs.of(200L, 0L, 0L), FixedPoint.ONE,
                100, 5, 3, HeroAttrs.zero(), rules());
        HeroAttrs b = HeroCalculator.finalAttrs(HeroAttrs.of(100L, 0L, 0L), FixedPoint.ONE,
                100, 5, 3, HeroAttrs.zero(), rules());
        // 不能用「恰好 2 倍」：两个值各自独立取整（1035 与 517），
        // 比值最多能偏离 1/517 ≈ 0.002。带宽按「较小值的 1 个单位」来定，取 ±0.01 留足余量。
        // 要验的是「成长因子不含稀有度」，不是整除关系 —— 若因子真的含稀有度，
        // 偏离会是百分之几十的量级，而不是千分之几。
        assertThat((double) a.might() / b.might())
                .as("成长因子若含稀有度，这个比值就会显著偏离三维之比 2.0")
                .isBetween(1.99d, 2.01d);
    }

    // ---------- 战力 ----------

    @Test
    @DisplayName("战力走幂律曲线且随每条养成线单调上升，装备固定值直接相加")
    void powerGrowsWithEveryLine() {
        long bare = HeroCalculator.power(SSR_01, SSR_01_GROWTH, 1, 1, 0, HeroAttrs.zero(), rules());
        long leveled = HeroCalculator.power(SSR_01, SSR_01_GROWTH, 100, 1, 0, HeroAttrs.zero(), rules());
        long starred = HeroCalculator.power(SSR_01, SSR_01_GROWTH, 100, 5, 0, HeroAttrs.zero(), rules());
        long awakened = HeroCalculator.power(SSR_01, SSR_01_GROWTH, 100, 5, 3, HeroAttrs.zero(), rules());
        long equipped = HeroCalculator.power(SSR_01, SSR_01_GROWTH, 100, 5, 3,
                HeroAttrs.of(45L, 50L, 30L), rules());

        assertThat(bare).as("1 级裸装战力 = Σ三维 × 成长率 = 285 × 1.20 = 342").isEqualTo(342L);
        assertThat(leveled).isGreaterThan(bare);
        assertThat(starred).isGreaterThan(leveled);
        assertThat(awakened).isGreaterThan(starred);
        assertThat(equipped - awakened).as("装备固定值直接加进战力，不被放大").isEqualTo(125L);
        // 幂律曲线在满级应当远高于线性属性曲线：战力是给玩家「我变强了很多」的体感数字
        assertThat(leveled).as("100 级战力 ≈ 342 × 100^1.20").isGreaterThan(bare * 200L);
    }

    // ---------- 队伍加成与乘区隔离 ----------

    @Test
    @DisplayName("主将属性全额计入，副将按 HERO_SUB_BONUS_RATIO 折半计入")
    void mainCountsFullAndSubsCountHalf() {
        HeroAttrs main = HeroAttrs.of(120L, 114L, 108L);
        HeroAttrs sub = HeroAttrs.of(80L, 70L, 60L);
        HeroCalculator.TeamBonus bonus = HeroCalculator.teamBonus(
                main, List.of(sub, sub), 0L, 0L, 0L, 0, rules());

        // 主将武力 120 ⇒ +12%（1200 定点）；副将各 80 ⇒ 各 +8%，折半后各 +4%（400）
        assertThat(bonus.atkFixed()).isEqualTo(1200L + 400L + 400L);
        assertThat(bonus.defFixed()).isEqualTo(1140L + 350L + 350L);
        assertThat(bonus.skillFixed()).isEqualTo(1080L + 300L + 300L);
        // 统帅值同样按主将全额、副将折半
        assertThat(bonus.commandValue()).isEqualTo(114L + 35L + 35L);
        assertThat(bonus.capped()).isFalse();
    }

    @Test
    @DisplayName("验收9：激活一条缘分带来的加成恰好等于 HERO_BOND_BONUS，误差 0")
    void bondBonusMatchesConfigExactly() {
        HeroAttrs main = HeroAttrs.of(120L, 114L, 108L);
        long bondFixed = FixedPoint.parse("0.08");

        HeroCalculator.TeamBonus none = HeroCalculator.teamBonus(main, List.of(), 0L, 0L, 0L, 0, rules());
        HeroCalculator.TeamBonus one = HeroCalculator.teamBonus(main, List.of(), 0L, 0L, 0L, 1, rules());
        HeroCalculator.TeamBonus two = HeroCalculator.teamBonus(main, List.of(), 0L, 0L, 0L, 2, rules());

        assertThat(one.atkFixed() - none.atkFixed()).isEqualTo(bondFixed);
        assertThat(one.defFixed() - none.defFixed()).isEqualTo(bondFixed);
        assertThat(two.atkFixed() - one.atkFixed()).as("第二条缘分同样值 8%").isEqualTo(bondFixed);
        assertThat(one.breakdown()).anySatisfy(b -> {
            assertThat(b.zone()).isEqualTo(HeroCalculator.Zone.BOND);
            assertThat(b.valueFixed()).isEqualTo(bondFixed);
        });
    }

    @Test
    @DisplayName("验收5：下阵之后加成归零，没有任何残留")
    void unequippingLeavesNoResidue() {
        HeroAttrs main = HeroAttrs.of(120L, 114L, 108L);
        HeroAttrs sub = HeroAttrs.of(80L, 70L, 60L);
        HeroCalculator.TeamBonus formed = HeroCalculator.teamBonus(
                main, List.of(sub, sub), 1500L, 600L, 0L, 1, rules());
        assertThat(formed.atkFixed()).isPositive();
        assertThat(formed.commandValue()).isPositive();

        // 下阵 = 主将与副将全部传 null。编队是整体替换而不是增量修改，
        // 所以「移除」不是一条独立路径，也就没有漏掉清理的可能
        HeroCalculator.TeamBonus empty = HeroCalculator.teamBonus(
                null, Arrays.asList(null, null), 0L, 0L, 0L, 0, rules());
        assertThat(empty.atkFixed()).isZero();
        assertThat(empty.defFixed()).isZero();
        assertThat(empty.skillFixed()).isZero();
        assertThat(empty.equipSetAtkFixed()).isZero();
        assertThat(empty.commandValue()).isZero();
        assertThat(HeroCalculator.troopCap(empty.commandValue(), rules()))
                .as("统帅值为 0 时带兵上限必须是 0，不能留下旧值").isZero();
        assertThat(empty.breakdown()).as("没有武将就不该有任何明细行").isEmpty();
    }

    @Test
    @DisplayName("乘区隔离：装备套装的百分比单独返回，不混进武将乘区（B06 禁止项）")
    void equipSetStaysInItsOwnZone() {
        HeroAttrs main = HeroAttrs.of(120L, 114L, 108L);
        long setAtk = FixedPoint.parse("0.15");
        long setDef = FixedPoint.parse("0.06");

        HeroCalculator.TeamBonus without = HeroCalculator.teamBonus(main, List.of(), 0L, 0L, 0L, 0, rules());
        HeroCalculator.TeamBonus with = HeroCalculator.teamBonus(main, List.of(), setAtk, setDef, 0L, 0, rules());

        assertThat(with.atkFixed()).as("武将乘区不得被装备污染").isEqualTo(without.atkFixed());
        assertThat(with.defFixed()).isEqualTo(without.defFixed());
        assertThat(with.equipSetAtkFixed()).isEqualTo(setAtk);
        assertThat(with.equipSetDefFixed()).isEqualTo(setDef);
        assertThat(with.equipSetSkillFixed()).as("没传技能侧套装加成就必须是 0").isZero();
        assertThat(with.breakdown()).filteredOn(b -> b.zone() == HeroCalculator.Zone.EQUIP_SET)
                .as("明细里必须能看出装备套装落在哪个乘区，攻击/防御/技能三个方向都单列").hasSize(3);
    }

    @Test
    @DisplayName("乘区上限：堆到超过 HERO_ZONE_CAP 时被截断，且 capped=true 让 UI 能提示玩家")
    void zoneCapTruncatesAndIsReported() {
        // 单名主将武力 4000 点 ⇒ +400%，远超 200% 上限
        HeroAttrs huge = HeroAttrs.of(4000L, 4000L, 0L);
        HeroCalculator.TeamBonus bonus = HeroCalculator.teamBonus(huge, List.of(), 0L, 0L, 0L, 0, rules());

        assertThat(bonus.atkFixed()).isEqualTo(FixedPoint.parse("2.00"));
        assertThat(bonus.defFixed()).isEqualTo(FixedPoint.parse("2.00"));
        assertThat(bonus.capped()).as("触顶必须显式告知，否则玩家会以为继续养成还有收益").isTrue();
        assertThat(bonus.breakdown()).anySatisfy(b ->
                assertThat(b.source()).contains("触发乘区上限"));
    }

    @Test
    @DisplayName("带兵上限 = 统帅值 × TROOP_PER_COMMAND（B06 验收 8 的数值基础）")
    void troopCapFollowsCommandValue() {
        assertThat(HeroCalculator.troopCap(0L, rules())).isZero();
        assertThat(HeroCalculator.troopCap(184L, rules())).isEqualTo(920L);
        assertThat(HeroCalculator.troopCap(1000L, rules())).isEqualTo(5000L);
        assertThatThrownBy(() -> HeroCalculator.troopCap(-1L, rules()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 经验曲线 ----------

    @Test
    @DisplayName("升级经验按 20 × 1.08^(n-1) 递增，满级返回 0 而不是抛异常")
    void expCurveGrowsAndStopsAtMaxLevel() {
        HeroRules rules = rules();
        assertThat(rules.expToNext(1, 100)).as("1→2 级 = 20").isEqualTo(20L);
        assertThat(rules.expToNext(2, 100)).as("2→3 级 = 20×1.08 = 21.6 → 22").isEqualTo(22L);
        assertThat(rules.expToNext(100, 100)).as("满级返回 0，客户端据此显示「已满级」").isZero();
        assertThat(rules.expToNext(99, 100)).isPositive();
        // 曲线必须严格递增，否则会出现「越往后越便宜」的刷级漏洞
        long previous = 0L;
        for (int level = 1; level < 100; level++) {
            long need = rules.expToNext(level, 100);
            assertThat(need).as("%d→%d 级的经验必须不少于上一档", level, level + 1)
                    .isGreaterThanOrEqualTo(previous);
            previous = need;
        }
    }

    // ---------- 聚合根行为 ----------

    @Test
    @DisplayName("投喂经验会连续升级，多余的经验留在条里而不是凭空消失")
    void feedingExpLevelsUpContinuously() {
        HeroInstance hero = new HeroInstance("hero_ssr_01");
        // 1→2 需 20，2→3 需 22，合计 42。投 50 应升到 3 级并剩 8
        int gained = hero.feedExp(50L, 100, rules());
        assertThat(gained).isEqualTo(2);
        assertThat(hero.level()).isEqualTo(3);
        assertThat(hero.exp()).as("50 - 20 - 22 = 8，剩余经验必须留着").isEqualTo(8L);
    }

    @Test
    @DisplayName("满级后继续投喂不会越级，经验保留（满级不是永久状态，赛季可能开放更高上限）")
    void feedExpStopsAtMaxLevelButKeepsExp() {
        HeroInstance hero = new HeroInstance("hero_ssr_01");
        hero.feedExp(10_000L, 3, rules());
        assertThat(hero.level()).isEqualTo(3);
        assertThat(hero.exp()).as("满级后多出来的经验必须保留，丢掉等于没收玩家已付费的东西")
                .isPositive();
        int gained = hero.feedExp(5_000L, 3, rules());
        assertThat(gained).isZero();
    }

    @Test
    @DisplayName("星级/觉醒/技能都有上限，越界写入在聚合内就被拒绝而不是靠调用方自觉")
    void growthLinesEnforceTheirCaps() {
        HeroInstance hero = new HeroInstance("hero_ssr_01");
        for (int i = 1; i < 5; i++) {
            hero.starUp(rules());
        }
        assertThat(hero.star()).isEqualTo(5);
        assertThatThrownBy(() -> hero.starUp(rules()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("星级上限");

        for (int i = 0; i < 3; i++) {
            hero.awakenUp(3);
        }
        assertThatThrownBy(() -> hero.awakenUp(3))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("觉醒上限");

        for (int i = 1; i < 10; i++) {
            hero.skillUp(true, rules());
        }
        assertThat(hero.mainSkillLevel()).isEqualTo(10);
        assertThatThrownBy(() -> hero.skillUp(true, rules()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("主技能已达上限");
    }

    @Test
    @DisplayName("穿装备返回被替换下来的那一件，卸下返回原装备且槽位真的空了")
    void equipSwapsAndUnequips() {
        HeroInstance hero = new HeroInstance("hero_ssr_01");
        assertThat(hero.equip(EquipSlot.WEAPON, "eq_pojun_blade")).isNull();
        assertThat(hero.equipOf(EquipSlot.WEAPON)).isEqualTo("eq_pojun_blade");
        assertThat(hero.equips()).hasSize(1);

        assertThat(hero.equip(EquipSlot.WEAPON, "eq_iron_sword"))
                .as("换装必须把旧的那件交回给调用方放回背包，否则装备凭空消失")
                .isEqualTo("eq_pojun_blade");

        assertThat(hero.equip(EquipSlot.WEAPON, null)).isEqualTo("eq_iron_sword");
        assertThat(hero.equipOf(EquipSlot.WEAPON)).isNull();
        assertThat(hero.equips()).as("卸下后槽位必须真的空掉，不能留一个 value 为 null 的条目")
                .isEmpty();
    }

    @Test
    @DisplayName("上阵校验：未拥有的武将、同队重复、副将超编都拒绝")
    void lineupValidationRejectsCheating() {
        HeroRoster roster = new HeroRoster();
        roster.obtain("hero_ssr_01");
        roster.obtain("hero_ssr_03");

        assertThatThrownBy(() -> roster.setLineup(0, "hero_ssr_02", List.of(), rules()))
                .isInstanceOf(IllegalArgumentException.class)
                .as("客户端伪造一个没抽到的 heroId 就能白拿加成，必须服务端拦")
                .hasMessageContaining("尚未拥有");
        assertThatThrownBy(() -> roster.setLineup(0, "hero_ssr_01",
                List.of("hero_ssr_01", "hero_ssr_03"), rules()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("两次");
        assertThatThrownBy(() -> roster.setLineup(0, "hero_ssr_01",
                List.of("hero_ssr_03", "hero_ssr_03", "hero_ssr_03"), rules()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("副将数量");
        assertThatThrownBy(() -> roster.setLineup(9, "hero_ssr_01", List.of(), rules()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("presetIndex");

        roster.setLineup(0, "hero_ssr_01", Arrays.asList("hero_ssr_03", null), rules());
        assertThat(roster.lineup(0, rules()).members()).containsExactly("hero_ssr_01", "hero_ssr_03");
    }

    @Test
    @DisplayName("缘分成对才算一条，且双向不会被数成两条")
    void bondsAreCountedOncePerPair() {
        Map<String, String> bondOf = Map.of(
                "hero_ssr_01", "hero_ssr_03",
                "hero_ssr_03", "hero_ssr_01",
                "hero_sr_02", "hero_ssr_02");
        HeroRoster roster = new HeroRoster();
        roster.obtain("hero_ssr_01");
        roster.obtain("hero_ssr_03");
        roster.obtain("hero_sr_02");

        assertThat(roster.activeBonds(new Lineup(0, "hero_ssr_01", Arrays.asList("hero_ssr_03", null)), bondOf))
                .as("裴惊澜与燕孤鸿互为缘分，同队只算一条")
                .isEqualTo(1);
        assertThat(roster.activeBonds(new Lineup(0, "hero_ssr_03", Arrays.asList("hero_ssr_01", null)), bondOf))
                .as("换主副位置不影响判定").isEqualTo(1);
        assertThat(roster.activeBonds(new Lineup(0, "hero_ssr_01", List.of()), bondOf))
                .as("缘分对象不在队里就不激活").isZero();
        assertThat(roster.activeBonds(new Lineup(0, "hero_sr_02", Arrays.asList("hero_ssr_01", null)), bondOf))
                .as("沈砚秋的对象是苏妄之，队里没有就不激活").isZero();
    }

    @Test
    @DisplayName("预设数量恒等于 LINEUP_PRESET_COUNT，首次访问自动补建空队")
    void presetsAreAutoCreated() {
        HeroRoster roster = new HeroRoster();
        assertThat(roster.lineups(rules())).hasSize(3);
        assertThat(roster.lineup(2, rules()).isFormed()).isFalse();
        assertThatThrownBy(() -> roster.lineup(3, rules()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("重复获得同一名武将时 obtain 返回 false，调用方据此转碎片")
    void duplicateHeroIsDetected() {
        HeroRoster roster = new HeroRoster();
        assertThat(roster.obtain("hero_ssr_01")).isTrue();
        assertThat(roster.obtain("hero_ssr_01")).as("第二次必须是 false，否则会覆盖掉已有养成进度")
                .isFalse();
        assertThat(roster.hero("hero_ssr_01").level()).isEqualTo(1);
        assertThatThrownBy(() -> roster.hero("hero_ssr_02"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("尚未拥有");
    }
}
