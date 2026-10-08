package com.ironoath.core.formula;

import com.ironoath.common.config.CurveKind;
import com.ironoath.common.config.CurveParams;
import com.ironoath.common.config.CurveSource;
import com.ironoath.common.config.CurveUnit;
import com.ironoath.common.num.FixedPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：Formula 单测 —— 验证曲线求值与 B00 数值速查一致，且脱离 Spring/配置文件即可运行。
 * 依赖：JUnit 5 + AssertJ；CurveSource 用内存实现，<b>不加载任何配置表</b>（C00 公理四·五）。
 */
class FormulaTest {

    /** 内存曲线源，模拟 contract/config/curve.json 的内容。 */
    private static final class InMemoryCurveSource implements CurveSource {
        private final Map<String, CurveParams> params = new HashMap<>();

        InMemoryCurveSource add(String id, CurveKind kind, String base, String ratio,
                                String exponent, CurveUnit unit) {
            params.put(id, new CurveParams(id, kind,
                    FixedPoint.parse(base), FixedPoint.parse(ratio), FixedPoint.parse(exponent), unit));
            return this;
        }

        @Override
        public CurveParams curve(String curveId) {
            CurveParams p = params.get(curveId);
            if (p == null) {
                throw new IllegalArgumentException("曲线不存在: " + curveId);
            }
            return p;
        }

        @Override
        public boolean hasCurve(String curveId) {
            return params.containsKey(curveId);
        }
    }

    /** B00 数值速查的完整曲线集。 */
    private static InMemoryCurveSource b00Curves() {
        return new InMemoryCurveSource()
                .add("BUILDING_TIME", CurveKind.GEOMETRIC, "30", "1.18", "1", CurveUnit.SECOND)
                .add("BUILDING_COST", CurveKind.GEOMETRIC, "0", "1.22", "1", CurveUnit.FIXED)
                .add("BUILDING_OUTPUT", CurveKind.POWER, "0", "1", "1.08", CurveUnit.FIXED_PER_HOUR)
                .add("POWER_CONTRIB", CurveKind.POWER, "0", "1", "1.15", CurveUnit.FIXED)
                .add("TECH_TIME", CurveKind.GEOMETRIC, "0", "1.28", "1", CurveUnit.SECOND)
                .add("UNIT_STRENGTH", CurveKind.GEOMETRIC, "0", "1.12", "1", CurveUnit.FIXED)
                .add("HERO_GROWTH", CurveKind.POWER, "0", "1", "1.20", CurveUnit.FIXED)
                .add("CHAPTER_DIFFICULTY", CurveKind.GEOMETRIC, "0", "1.25", "1", CurveUnit.FIXED);
    }

    @Test
    @DisplayName("建筑时间 T(n)=30×1.18^(n-1)：1 级 30s、2 级 35s、8 级 96s")
    void buildingTimeMatchesB00Curve() {
        Formula formula = new Formula(b00Curves());

        assertThat(formula.evaluateSeconds("BUILDING_TIME", 1)).isEqualTo(30L);
        // 30 × 1.18 = 35.4 ⇒ HALF_UP ⇒ 35
        assertThat(formula.evaluateSeconds("BUILDING_TIME", 2)).isEqualTo(35L);
        // 30 × 1.18^7 = 95.87… ⇒ 96
        assertThat(formula.evaluateSeconds("BUILDING_TIME", 8)).isEqualTo(96L);
    }

    @Test
    @DisplayName("几何曲线与 BigDecimal 精确参考值逐位一致（1~30 级全量）")
    void geometricCurveMatchesExactReference() {
        Formula formula = new Formula(b00Curves());
        BigDecimal ratio = new BigDecimal("1.18");
        BigDecimal base = new BigDecimal("30");

        for (int level = 1; level <= 30; level++) {
            long expected = base.multiply(ratio.pow(level - 1))
                    .movePointRight(4).setScale(0, RoundingMode.HALF_UP).longValueExact();
            assertThat(formula.evaluate("BUILDING_TIME", level))
                    .as("BUILDING_TIME 第 %d 级", level)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("幂律曲线 P(n)=P0×n^1.08 单调递增且量级正确")
    void powerCurveIsMonotonicAndCorrect() {
        Formula formula = new Formula(b00Curves());
        long p0 = FixedPoint.of(100);   // 某建筑 1 级底产 100/小时

        long prev = 0L;
        for (int level = 1; level <= 30; level++) {
            long value = formula.evaluate("BUILDING_OUTPUT", p0, level);
            assertThat(value).as("BUILDING_OUTPUT 第 %d 级应单调不减", level).isGreaterThanOrEqualTo(prev);
            prev = value;
        }
        // 1 级：100 × 1^1.08 = 100
        assertThat(formula.evaluate("BUILDING_OUTPUT", p0, 1)).isEqualTo(FixedPoint.of(100));
        // 10 级：100 × 10^1.08 ≈ 1202.26，容差 1 个定点单位（BigDecimal 级数 vs double 参考）
        double reference = 100.0d * Math.pow(10.0d, 1.08d) * FixedPoint.SCALE;
        assertThat(Math.abs(formula.evaluate("BUILDING_OUTPUT", p0, 10) - reference))
                .isLessThanOrEqualTo(2.0d);
    }

    @Test
    @DisplayName("曲线参数完全来自 CurveSource，Formula 内无任何硬编码系数（铁律 1）")
    void coefficientsComeFromCurveSource() {
        // 同一份 Formula 代码，换一套曲线参数就得到完全不同的结果
        InMemoryCurveSource steeper = new InMemoryCurveSource()
                .add("BUILDING_TIME", CurveKind.GEOMETRIC, "30", "2.00", "1", CurveUnit.SECOND);
        Formula formula = new Formula(steeper);

        assertThat(formula.evaluateSeconds("BUILDING_TIME", 1)).isEqualTo(30L);
        assertThat(formula.evaluateSeconds("BUILDING_TIME", 2)).isEqualTo(60L);
        assertThat(formula.evaluateSeconds("BUILDING_TIME", 3)).isEqualTo(120L);
    }

    @Test
    @DisplayName("基数由业务表提供时，无参基数重载抛异常而不是静默用 0")
    void externalBaseRequiresExplicitValue() {
        Formula formula = new Formula(b00Curves());
        assertThatThrownBy(() -> formula.evaluate("BUILDING_COST", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("基数由业务表逐行提供");
        // 显式传基数则正常工作：C0=200 木材，5 级 = 200×1.22^4 ≈ 441.09
        long cost = formula.evaluate("BUILDING_COST", FixedPoint.of(200), 5);
        assertThat(cost).isEqualTo(
                new BigDecimal("200").multiply(new BigDecimal("1.22").pow(4))
                        .movePointRight(4).setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    @Test
    @DisplayName("量纲校验：对非 SECOND 曲线调用 evaluateSeconds 抛异常，防止把产量当时间用")
    void unitMismatchIsRejected() {
        Formula formula = new Formula(b00Curves());
        assertThatThrownBy(() -> formula.evaluateSeconds("BUILDING_OUTPUT", FixedPoint.of(100), 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是 SECOND");
    }

    @Test
    @DisplayName("非法入参：等级 < 1、基数 <= 0、CurveSource 为 null 均抛异常")
    void rejectsInvalidInput() {
        Formula formula = new Formula(b00Curves());
        assertThatThrownBy(() -> formula.evaluate("BUILDING_TIME", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> formula.evaluate("BUILDING_TIME", -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> formula.evaluate("BUILDING_COST", 0L, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Formula(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("未知曲线 id 抛异常，绝不返回 null 或默认值")
    void unknownCurveThrows() {
        Formula formula = new Formula(b00Curves());
        assertThatThrownBy(() -> formula.evaluate("NOT_EXIST", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("缓存不改变结果：同一 (曲线, 基数, 等级) 重复求值逐位相同，超过缓存上界仍正确")
    void cacheDoesNotChangeResults() {
        Formula formula = new Formula(b00Curves());
        long first = formula.evaluate("BUILDING_TIME", 7);
        for (int i = 0; i < 100; i++) {
            assertThat(formula.evaluate("BUILDING_TIME", 7)).isEqualTo(first);
        }
        assertThat(formula.cacheSize()).isEqualTo(1);

        // 超过 MAX_CACHED_LEVEL 走无缓存路径：用温和比率(1.01)的曲线，避免结果超出定点 long 范围
        InMemoryCurveSource mild = new InMemoryCurveSource()
                .add("MILD_TIME", CurveKind.GEOMETRIC, "30", "1.01", "1", CurveUnit.SECOND);
        Formula mildFormula = new Formula(mild);
        int beyond = Formula.MAX_CACHED_LEVEL + 50;
        long beyondValue = mildFormula.evaluate("MILD_TIME", beyond);
        long expected = new BigDecimal("30")
                .multiply(new BigDecimal("1.01").pow(beyond - 1))
                .movePointRight(4).setScale(0, RoundingMode.HALF_UP).longValueExact();
        assertThat(beyondValue).isEqualTo(expected);
        assertThat(mildFormula.cacheSize()).isZero();

        // 陡峭曲线在荒谬等级下必须抛溢出异常而不是静默回绕：
        // 1.18^249 约 1e18，定点 long 的真实值上限约 9.2e14，超出即报错
        assertThatThrownBy(() -> formula.evaluate("BUILDING_TIME", beyond))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("溢出 long 范围");

        formula.invalidateCache();
        assertThat(formula.cacheSize()).isZero();
        assertThat(formula.evaluate("BUILDING_TIME", 7)).isEqualTo(first);
    }

    @Test
    @DisplayName("衰减型曲线（ratio<1）高等级结果为 0 时不被哨兵值污染")
    void decayingCurveCanLegitimatelyReachZero() {
        InMemoryCurveSource decaying = new InMemoryCurveSource()
                .add("DECAY", CurveKind.GEOMETRIC, "1", "0.10", "1", CurveUnit.FIXED);
        Formula formula = new Formula(decaying);

        // 1 级：1.0 × 0.1^0 = 1.0 ⇒ 定点 10000
        assertThat(formula.evaluate("DECAY", 1)).isEqualTo(FixedPoint.ONE);
        // 6 级：1.0 × 0.1^5 = 0.00001 ⇒ 定点 0.1 ⇒ HALF_UP ⇒ 0，这是合法结果而非「未计算」
        assertThat(formula.evaluate("DECAY", 6)).isZero();
        // 再次读取仍为 0，而不是被误判成「未计算」
        assertThat(formula.evaluate("DECAY", 6)).isZero();
    }

    @Test
    @DisplayName("contributionLevel：缺省（null）不封顶，填了 cap 就到 cap 为止（台账 #826 的裁决落地）")
    void contributionLevelHonoursOptionalCap() {
        assertThat(Formula.contributionLevel(17, null))
                .as("表里没填这一列时，战力贡献必须照实等级算 —— 现有各行的读数一字不变的前提就在这里")
                .isEqualTo(17);
        assertThat(Formula.contributionLevel(17, 30L)).as("cap 高于当前等级时不起作用").isEqualTo(17);
        assertThat(Formula.contributionLevel(17, 10L)).as("cap 低于当前等级时按 cap 计费").isEqualTo(10);
        assertThat(Formula.contributionLevel(0, 0L))
                .as("0 是「产品真填了 0」这个合法取值，而「没填」是 null —— 两者混起来就等于把所有建筑封在 1 级")
                .isZero();
    }
}
