package com.ironoath.core.power;

import com.ironoath.common.num.FixedPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：B08 战力圈层、峰值记忆与暴虐值的验证 —— 验收 1~8。
 * 依赖：纯 JUnit，不需要容器、不需要配置表（铁律 2）。
 *
 * <p><b>本批次的验收标准比其他批次更需要测试守着</b>：B08 标了「★生态地基」，
 * 而它的失败模式不是崩溃而是「游戏变得没人玩」——
 * 区间放宽一点、暴虐阈值调低一点、加一个「弱者补偿」，都不会让任何测试变红，
 * 但会在几个月后表现为强者流失或弱者退游。所以这里的断言大量写成
 * 「必须等于配置值」「必须双向闭合」「必须不存在某条路径」，
 * 而不是「看起来差不多就行」。
 */
class PowerSystemTest {

    /** 与 global.json 一致：区间 [0.5x, 2.0x]，集结按 √N 放宽。 */
    private static PowerBandGuard.Rules bandRules() {
        return new PowerBandGuard.Rules(
                FixedPoint.parse("0.5"), FixedPoint.parse("2.0"), true);
    }

    private static PowerCalculator.Rules powerRules() {
        return new PowerCalculator.Rules(FixedPoint.parse("0.8"), FixedPoint.parse("0.02"));
    }

    private static Tyranny.Rules tyrannyRules() {
        return new Tyranny.Rules(
                FixedPoint.parse("1.5"),   // TYRANNY_THRESHOLD
                100L,                      // TYRANNY_PER_UNIT
                2L,                        // TYRANNY_CRUSH_MULTIPLIER
                FixedPoint.parse("0.20"),  // BRUTALITY_DAILY_DECAY
                100L, 300L, 600L);         // 三档阈值
    }

    // ---------- 验收 1：区间正确性 ----------

    @Test
    @DisplayName("验收1：0.3x 拒绝、0.5x 通过、1.0x 通过、1.9x 通过、2.0x 通过、2.1x 拒绝（闭区间）")
    void bandBoundariesAreInclusive() {
        long self = 1000L;
        // 0.3x：低于下限 500 ⇒ 太弱
        assertRejected(self, 300L, PowerBandGuard.RejectReason.TARGET_TOO_WEAK);
        // 0.5x：正好等于下限 ⇒ 通过（闭区间）
        assertAllowed(self, 500L);
        assertAllowed(self, 1000L);
        assertAllowed(self, 1900L);
        // 2.0x：正好等于上限 ⇒ 通过
        assertAllowed(self, 2000L);
        // 2.1x：超出上限 ⇒ 太强
        assertRejected(self, 2100L, PowerBandGuard.RejectReason.TARGET_TOO_STRONG);

        // 下限之外一格也必须拒绝，证明判定不是「大概在这个范围」
        assertRejected(self, 499L, PowerBandGuard.RejectReason.TARGET_TOO_WEAK);
        assertRejected(self, 2001L, PowerBandGuard.RejectReason.TARGET_TOO_STRONG);
    }

    @Test
    @DisplayName("拒绝必须带文案，而且两种文案都是「提示」不是「惩罚」")
    void rejectionAlwaysCarriesAMessage() {
        PowerBandGuard.BandCheckResult tooStrong = PowerBandGuard.check(1000L, 3000L, 1, bandRules());
        assertThat(tooStrong.message()).isEqualTo("对方实力远超于你，无法发起进攻");
        PowerBandGuard.BandCheckResult tooWeak = PowerBandGuard.check(1000L, 100L, 1, bandRules());
        assertThat(tooWeak.message()).isEqualTo("对方实力远弱于你，无需出手");
        // 「无需出手」而不是「不允许攻击弱者」：前者是提示，后者听起来像惩罚，
        // 而 B08 明写这条是提示不是惩罚
        assertThat(tooWeak.message()).doesNotContain("禁止").doesNotContain("不允许");
        assertThat(tooStrong.allowed()).isFalse();
        assertThat(PowerBandGuard.check(1000L, 1000L, 1, bandRules()).message())
                .as("通过时不需要文案").isNull();
    }

    @Test
    @DisplayName("战力为 0 时给出「你没有可出征的部队」而不是误导性的「对方太强」")
    void zeroPowerGetsAnHonestMessage() {
        PowerBandGuard.BandCheckResult result = PowerBandGuard.check(0L, 500L, 1, bandRules());
        assertThat(result.allowed()).isFalse();
        assertThat(result.message()).contains("没有可出征的部队");
        assertThat(result.message()).doesNotContain("对方实力远超于你");
    }

    // ---------- 验收 2：双向闭合 ----------

    @Test
    @DisplayName("验收2：A(100) 与 B(300) 互相都无法选中对方 —— 同一条规则双向闭合，不需要额外的「禁止虐菜」")
    void bandIsMutuallyExclusive() {
        // 300 > 100×2 ⇒ A 打不到 B
        assertThat(PowerBandGuard.check(100L, 300L, 1, bandRules()).allowed()).isFalse();
        // 100 < 300×0.5 ⇒ B 也打不到 A
        assertThat(PowerBandGuard.check(300L, 100L, 1, bandRules()).allowed()).isFalse();
        assertThat(PowerBandGuard.isMutuallyExcluded(100L, 300L, bandRules())).isTrue();

        // 区间内则是双向可打：300 打 150 与 150 打 300 都合法
        assertThat(PowerBandGuard.isMutuallyExcluded(150L, 300L, bandRules()))
                .as("150 与 300 在彼此的区间内（300 ≤ 150×2 且 150 ≥ 300×0.5），必须双向可打")
                .isFalse();
        assertThat(PowerBandGuard.check(300L, 150L, 1, bandRules()).allowed())
                .as("强者打区间内的弱者必须被允许，且收益不打折 —— 这是 B08 最重要的禁止项")
                .isTrue();
        assertThat(PowerBandGuard.check(150L, 300L, 1, bandRules()).allowed()).isTrue();
    }

    @Test
    @DisplayName("临界比值 2.0 恰好双向可打，2.01 就双向闭合（区间宽度是设计出来的，不是凑出来的）")
    void mutualExclusionBoundaryIsExactlyTheRatio() {
        assertThat(PowerBandGuard.isMutuallyExcluded(100L, 200L, bandRules()))
                .as("恰好 2.0 倍时双方都在彼此区间内").isFalse();
        assertThat(PowerBandGuard.isMutuallyExcluded(100L, 201L, bandRules()))
                .as("超过 2.0 倍立刻双向闭合").isTrue();
    }

    // ---------- 验收 3：峰值记忆 ----------

    @Test
    @DisplayName("验收3：卸掉全部兵后 matchPower 仍是峰值的 80%，2.1x 对手依然被拒绝")
    void peakMemoryDefeatsPowerDumping() {
        PowerCalculator.Snapshot before = new PowerCalculator.Snapshot(
                new PowerCalculator.PowerBreakdown(5000L, 4000L, 3000L, 1000L, 500L),
                4000L,      // 当前匹配战力 = 部队 + 上阵主将
                4000L,      // 峰值
                0);
        PowerCalculator.Result healthy = PowerCalculator.compute(before, powerRules());
        assertThat(healthy.displayPower()).isEqualTo(13500L);
        assertThat(healthy.matchPower()).isEqualTo(4000L);

        // 卸兵压分：部队清零、武将下阵 ⇒ 当前匹配战力掉到 0，但展示战力仍然很高
        PowerCalculator.Snapshot dumped = new PowerCalculator.Snapshot(
                new PowerCalculator.PowerBreakdown(5000L, 0L, 0L, 1000L, 500L),
                0L, 4000L, 0);
        PowerCalculator.Result after = PowerCalculator.compute(dumped, powerRules());
        assertThat(after.matchPower())
                .as("卸兵之后匹配战力必须仍等于峰值 × 0.8，否则压分就能骗到弱对手")
                .isEqualTo(3200L);
        assertThat(after.displayPower()).as("展示战力不受卸兵影响（建筑还在）").isEqualTo(6500L);

        // 压分之后，2.1x 的对手依然被拒绝（按 matchPower 判定，不是 displayPower）
        assertThat(PowerBandGuard.check(after.matchPower(), 3200L * 21 / 10, 1, bandRules()).allowed())
                .as("2.1x 必须被拒绝").isFalse();
        assertThat(PowerBandGuard.check(after.matchPower(), 3200L * 2, 1, bandRules()).allowed())
                .as("2.0x 必须仍然可打").isTrue();
    }

    @Test
    @DisplayName("峰值每日衰减 2%：10000 → 9800 → 9604，且衰减后当前战力能重新成为峰值")
    void peakDecaysDaily() {
        assertThat(PowerCalculator.decayPeak(10_000L, 1, powerRules())).isEqualTo(9_800L);
        assertThat(PowerCalculator.decayPeak(10_000L, 2, powerRules())).isEqualTo(9_604L);
        assertThat(PowerCalculator.decayPeak(10_000L, 0, powerRules())).isEqualTo(10_000L);

        // 衰减 30 天之后，一个转玩法的玩家不该再被钉在高位
        long after30 = PowerCalculator.decayPeak(10_000L, 30, powerRules());
        assertThat(after30).as("30 天 ≈ ×0.98^30 ≈ 0.545").isBetween(5400L, 550L + 5000L);

        // 当前战力高于衰减后的峰值时，峰值必须被抬回去
        PowerCalculator.Result raised = PowerCalculator.compute(
                new PowerCalculator.Snapshot(
                        new PowerCalculator.PowerBreakdown(0L, 9000L, 0L, 0L, 0L),
                        9000L, 10_000L, 30), powerRules());
        assertThat(raised.peakPower()).isEqualTo(9000L);
        assertThat(raised.peakRaised()).isFalse();
        assertThat(raised.peakDecayed()).isTrue();
        assertThat(raised.matchPower()).isEqualTo(9000L);
    }

    @Test
    @DisplayName("明细之和必须等于展示战力，否则玩家点开明细逐项相加对不上总数")
    void breakdownSumsToDisplayPower() {
        PowerCalculator.PowerBreakdown breakdown =
                new PowerCalculator.PowerBreakdown(5000L, 4000L, 3000L, 1000L, 500L);
        assertThat(breakdown.total()).isEqualTo(13500L);
        assertThatThrownBy(() -> new PowerCalculator.Result(
                999L, 100L, 100L, breakdown, false, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须等于 displayPower");
    }

    // ---------- 验收 4 / 5：集结破圈 ----------

    @Test
    @DisplayName("验收4：单人打不过 4x 目标，4 人集结可以；16 人集结可打 8x")
    void rallyBreaksTheBandBySqrtN() {
        long self = 1000L;
        // 单人：4x = 4000 > 2000 上限 ⇒ 拒绝
        assertRejected(self, 4000L, PowerBandGuard.RejectReason.TARGET_TOO_STRONG);
        // 4 人：upper = 2.0 × √4 = 4.0 ⇒ 上限 4000，恰好可打
        assertThat(PowerBandGuard.check(self, 4000L, 4, bandRules()).allowed())
                .as("4 人集结的上限是 4.0x").isTrue();
        assertThat(PowerBandGuard.check(self, 4001L, 4, bandRules()).allowed()).isFalse();
        // 16 人：upper = 2.0 × √16 = 8.0 ⇒ 上限 8000
        assertThat(PowerBandGuard.check(self, 8000L, 16, bandRules()).allowed())
                .as("16 人集结的上限是 8.0x（B08 设计意图里那条循环的落点）").isTrue();
        assertThat(PowerBandGuard.check(self, 8001L, 16, bandRules()).allowed()).isFalse();
        // 25 人：upper = 10.0
        assertThat(PowerBandGuard.check(self, 10_000L, 25, bandRules()).allowed()).isTrue();
    }

    @Test
    @DisplayName("验收5：集结下限同步放宽，16 人集结可以打 0.13x 目标")
    void rallyAlsoLowersTheFloor() {
        long self = 1000L;
        // 单人下限 0.5 ⇒ 打不了 130
        assertRejected(self, 130L, PowerBandGuard.RejectReason.TARGET_TOO_WEAK);
        // 16 人：lower = 0.5 / √16 = 0.125 ⇒ 下限 125，130 可打
        PowerBandGuard.BandCheckResult result = PowerBandGuard.check(self, 130L, 16, bandRules());
        assertThat(result.allowed())
                .as("否则 16 人集结反而打不了比自己弱很多的目标，不合逻辑").isTrue();
        assertThat(result.lowerBound()).isEqualTo(125L);
        assertThat(PowerBandGuard.check(self, 124L, 16, bandRules()).allowed()).isFalse();
    }

    @Test
    @DisplayName("禁止项：集结门槛必须用 √N 而不是 N —— 线性放宽会让高战集结无限膨胀")
    void rallyBandMustUseSqrtNotLinear() {
        long self = 1000L;
        PowerBandGuard.Rules sqrt = new PowerBandGuard.Rules(
                FixedPoint.parse("0.5"), FixedPoint.parse("2.0"), true);
        PowerBandGuard.Rules linear = new PowerBandGuard.Rules(
                FixedPoint.parse("0.5"), FixedPoint.parse("2.0"), false);

        // 20 人（联盟集结上限）：√20 ≈ 4.47 ⇒ 上限约 8944；线性则是 2.0×20 = 40x ⇒ 40000
        assertThat(PowerBandGuard.check(self, 8944L, 20, sqrt).allowed()).isTrue();
        assertThat(PowerBandGuard.check(self, 9000L, 20, sqrt).allowed())
                .as("√N 下 20 人集结的上限约 8.94x").isFalse();
        assertThat(PowerBandGuard.check(self, 40_000L, 20, linear).allowed())
                .as("线性放宽下 20 人能打 40x —— 这就是禁止项要防的无限膨胀").isTrue();
        // 所以生产必须用 √N；这个断言把两者的差距量化出来，
        // 以后谁想改成线性，会先看到「上限从 8.94x 变成 40x」这个数字
        assertThat(PowerBandGuard.check(self, 40_000L, 20, sqrt).allowed()).isFalse();
    }

    @Test
    @DisplayName("√N 用定点开方，边界值不因浮点精度分歧（FixedPoint.sqrt 的存在理由）")
    void sqrtIsExactAtBoundaries() {
        assertThat(FixedPoint.sqrt(FixedPoint.of(4))).isEqualTo(FixedPoint.of(2));
        assertThat(FixedPoint.sqrt(FixedPoint.of(16))).isEqualTo(FixedPoint.of(4));
        assertThat(FixedPoint.sqrt(FixedPoint.of(25))).isEqualTo(FixedPoint.of(5));
        assertThat(FixedPoint.sqrt(FixedPoint.of(1))).isEqualTo(FixedPoint.of(1));
        assertThat(FixedPoint.sqrt(0L)).isZero();
        // √2 不是精确值：向下取整 ⇒ 14142（而不是 14143），这保证区间只会略窄不会略宽
        assertThat(FixedPoint.sqrt(FixedPoint.of(2))).isEqualTo(14142L);
        assertThat(FixedPoint.sqrt(FixedPoint.of(20))).isEqualTo(44721L);
        assertThatThrownBy(() -> FixedPoint.sqrt(-1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("负数");
    }

    @Test
    @DisplayName("非法入参在构造期或调用点就拒绝")
    void rejectsInvalidInput() {
        // 上下限相等且都为 1.0：这是唯一能触发「上限必须大于下限」的合法输入组合，
        // 因为 lower 必须 <= 1.0 而 upper 必须 >= 1.0
        assertThatThrownBy(() -> new PowerBandGuard.Rules(FixedPoint.ONE, FixedPoint.ONE, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("上限倍率必须大于下限");
        assertThatThrownBy(() -> new PowerBandGuard.Rules(FixedPoint.parse("2.0"), FixedPoint.parse("3.0"), true))
                .isInstanceOf(IllegalArgumentException.class)
                .as("下限倍率本身不得超过 1.0，否则「打比自己弱的」也被禁止")
                .hasMessageContaining("下限倍率");
        assertThatThrownBy(() -> new PowerBandGuard.Rules(0L, FixedPoint.parse("2.0"), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("下限倍率");
        assertThatThrownBy(() -> PowerBandGuard.check(100L, 100L, 0, bandRules()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("集结人数必须 >= 1");
        assertThatThrownBy(() -> PowerBandGuard.check(-1L, 100L, 1, bandRules()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为负");
        assertThatThrownBy(() -> new PowerCalculator.Rules(0L, FixedPoint.parse("0.02")))
                .isInstanceOf(IllegalArgumentException.class)
                .as("记忆比率为 0 等于没有峰值记忆，卸兵压分立刻可用")
                .hasMessageContaining("记忆比率");
        assertThatThrownBy(() -> new PowerCalculator.Rules(FixedPoint.parse("0.8"), FixedPoint.SCALE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("每日衰减");
    }

    // ---------- 验收 6 / 7 / 8：暴虐值 ----------

    @Test
    @DisplayName("验收6：R 超过阈值才累积，击溃翻倍；R=1.2（区间内的正常对抗）不累积")
    void tyrannyAccumulatesOnlyAboveThreshold() {
        Tyranny.Rules rules = tyrannyRules();
        // R = 1.2：区间内的正常对抗，一分暴虐都不该记
        assertThat(Tyranny.accumulate(1200L, 1000L, false, rules))
                .as("R=1.2 < 阈值 1.5 ⇒ 不累积。若从 1.0 就开始记，"
                        + "区间内一半的合法战斗都会被记暴虐，等于变相禁止强者打弱者").isZero();
        assertThat(Tyranny.accumulate(1500L, 1000L, false, rules))
                .as("R 恰好等于阈值也不累积（严格大于才开始）").isZero();
        // 闸门在 threshold(1.5)，基数在势均力敌(1.0)：R=2.0 ⇒ (2.0 - 1.0) × 100 = 100，击溃翻倍 = 200。
        // 这正是 B08 验收 6 的字面要求，也是「赢一场两倍战力的碾压刚好上强横档」的口径
        assertThat(Tyranny.accumulate(2000L, 1000L, false, rules)).isEqualTo(100L);
        assertThat(Tyranny.accumulate(2000L, 1000L, true, rules)).isEqualTo(200L);
        // R = 3.0：(3.0 - 1.0) × 100 = 200
        assertThat(Tyranny.accumulate(3000L, 1000L, false, rules)).isEqualTo(200L);
        // 刚过闸门的最小增量：R=1.6 ⇒ 60。不连续（0 → 60）是验收 6 的口径带来的必然结果
        assertThat(Tyranny.accumulate(1600L, 1000L, false, rules)).isEqualTo(60L);
        assertThatThrownBy(() -> Tyranny.accumulate(1000L, 0L, false, rules))
                .isInstanceOf(IllegalArgumentException.class)
                .as("0 战力目标会让 R 变成无穷大，必须拒绝而不是记一个天文数字")
                .hasMessageContaining("战力为 0");
    }

    @Test
    @DisplayName("验收7：每日衰减 20%，100 → 80 → 64")
    void tyrannyDecaysTwentyPercentPerDay() {
        Tyranny.Rules rules = tyrannyRules();
        assertThat(Tyranny.decay(100L, 1, rules)).isEqualTo(80L);
        assertThat(Tyranny.decay(100L, 2, rules)).isEqualTo(64L);
        assertThat(Tyranny.decay(100L, 3, rules)).isEqualTo(51L);
        assertThat(Tyranny.decay(100L, 0, rules)).as("0 天不衰减").isEqualTo(100L);
        assertThat(Tyranny.decay(0L, 30, rules)).isZero();
        // 长期不上线应当衰减到 0，否则「公敌」会变成摘不掉的标签
        assertThat(Tyranny.decay(100L, 60, rules)).as("60 天衰减后应归零").isZero();
    }

    @Test
    @DisplayName("三档阈值与效果：100 强横（坐标可见）、300 暴虐（围剿令）、600 公敌（全服广播）")
    void tyrannyLevelsAndTheirEffects() {
        Tyranny.Rules rules = tyrannyRules();
        assertThat(Tyranny.levelOf(0L, rules)).isEqualTo(Tyranny.Level.COMMONER);
        assertThat(Tyranny.levelOf(99L, rules)).isEqualTo(Tyranny.Level.COMMONER);
        assertThat(Tyranny.levelOf(100L, rules)).isEqualTo(Tyranny.Level.TYRANT);
        assertThat(Tyranny.levelOf(299L, rules)).isEqualTo(Tyranny.Level.TYRANT);
        assertThat(Tyranny.levelOf(300L, rules)).isEqualTo(Tyranny.Level.BRUTE);
        assertThat(Tyranny.levelOf(599L, rules)).isEqualTo(Tyranny.Level.BRUTE);
        assertThat(Tyranny.levelOf(600L, rules)).isEqualTo(Tyranny.Level.PUBLIC_ENEMY);
        assertThat(Tyranny.levelOf(99_999L, rules)).isEqualTo(Tyranny.Level.PUBLIC_ENEMY);

        // 效果逐级叠加，不是互斥的
        assertThat(Tyranny.exposesCoordinate(Tyranny.Level.COMMONER)).isFalse();
        assertThat(Tyranny.exposesCoordinate(Tyranny.Level.TYRANT))
                .as("强横档起坐标全图可见：看不见人在哪，一切反击都无从谈起").isTrue();
        assertThat(Tyranny.triggersCrusade(Tyranny.Level.TYRANT)).isFalse();
        assertThat(Tyranny.triggersCrusade(Tyranny.Level.BRUTE)).isTrue();
        assertThat(Tyranny.triggersCrusade(Tyranny.Level.PUBLIC_ENEMY)).isTrue();
        assertThat(Tyranny.triggersBroadcast(Tyranny.Level.BRUTE)).isFalse();
        assertThat(Tyranny.triggersBroadcast(Tyranny.Level.PUBLIC_ENEMY)).isTrue();
    }

    @Test
    @DisplayName("验收8：同一对玩家互打不计；单日对同一目标只计一次（防刷掉套利链的第一环）")
    void tyrannyAntiFarming() {
        // 互打不计：pairKey 必须无序，否则 A→B 与 B→A 各算「首次」，判定就失效了
        assertThat(Tyranny.pairKey("A", "B")).isEqualTo(Tyranny.pairKey("B", "A"));
        assertThat(Tyranny.shouldCount(false, false)).as("首次且当天未记 ⇒ 计入").isTrue();
        assertThat(Tyranny.shouldCount(true, false)).as("这一对互打过 ⇒ 不计").isFalse();
        assertThat(Tyranny.shouldCount(false, true)).as("今天已对该目标记过 ⇒ 不计").isFalse();
        assertThat(Tyranny.shouldCount(true, true)).isFalse();

        assertThat(Tyranny.dailyTargetKey("A", "B", "20260907"))
                .isNotEqualTo(Tyranny.dailyTargetKey("A", "B", "20260908"));
        assertThat(Tyranny.dailyTargetKey("A", "B", "20260907"))
                .as("方向必须区分：A 打 B 与 B 打 A 是两次不同的行为")
                .isNotEqualTo(Tyranny.dailyTargetKey("B", "A", "20260907"));
        assertThatThrownBy(() -> Tyranny.pairKey("", "B"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("暴虐阈值必须 >= 1.0：低于 1.0 就等于把区间内的正常对抗记成暴虐")
    void tyrannyThresholdCannotGoBelowOne() {
        assertThatThrownBy(() -> new Tyranny.Rules(FixedPoint.parse("0.9"), 100L, 2L,
                FixedPoint.parse("0.20"), 100L, 300L, 600L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("暴虐阈值必须 >= 1.0");
        assertThatThrownBy(() -> new Tyranny.Rules(FixedPoint.parse("1.5"), 100L, 2L,
                FixedPoint.parse("0.20"), 300L, 100L, 600L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("严格递增");
        assertThatThrownBy(() -> new Tyranny.Rules(FixedPoint.parse("1.5"), 100L, 2L,
                FixedPoint.SCALE, 100L, 300L, 600L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("当天清零");
    }

    // ---------- 辅助 ----------

    private static void assertAllowed(long self, long target) {
        PowerBandGuard.BandCheckResult result = PowerBandGuard.check(self, target, 1, bandRules());
        assertThat(result.allowed())
                .as("self=%d target=%d 应当被允许（区间 [%d, %d]，原因 %s）",
                        self, target, result.lowerBound(), result.upperBound(), result.reason())
                .isTrue();
        assertThat(result.reason()).isEqualTo(PowerBandGuard.RejectReason.NONE);
    }

    private static void assertRejected(long self, long target, PowerBandGuard.RejectReason expected) {
        PowerBandGuard.BandCheckResult result = PowerBandGuard.check(self, target, 1, bandRules());
        assertThat(result.allowed())
                .as("self=%d target=%d 应当被拒绝（区间 [%d, %d]）",
                        self, target, result.lowerBound(), result.upperBound())
                .isFalse();
        assertThat(result.reason()).isEqualTo(expected);
        assertThat(result.message()).as("拒绝必须有文案").isNotBlank();
    }
}
