package com.ironoath.common.num;

import com.ironoath.common.rng.Rng;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：FixedPoint 单测 —— 覆盖 B01 验收 5（万次定点乘除与 BigDecimal 误差为 0）与曲线求值精度。
 * 依赖：JUnit 5 + AssertJ，无 Spring、无容器。
 *
 * <p>参考实现全部用 BigDecimal/BigInteger 单次舍入得出，<b>不用 double 当基准</b>：
 * 拿 double 校验定点数等于用一个不准的尺子量另一把尺子。
 */
class FixedPointTest {

    private static final int VERIFICATION_ROUNDS = 10000;
    private static final BigDecimal SCALE_BD = BigDecimal.valueOf(FixedPoint.SCALE);

    /** 参考实现：a×b/10000，单次 HALF_UP 舍入。 */
    private static long referenceMul(long a, long b) {
        return BigDecimal.valueOf(a).multiply(BigDecimal.valueOf(b))
                .divide(SCALE_BD, 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** 参考实现：a×10000/b，单次 HALF_UP 舍入。 */
    private static long referenceDiv(long a, long b) {
        return BigDecimal.valueOf(a).multiply(SCALE_BD)
                .divide(BigDecimal.valueOf(b), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    @Test
    @DisplayName("验收5：10000 次定点乘法，与 BigDecimal 参考实现误差为 0")
    void multiplicationMatchesBigDecimalExactly() {
        Rng rng = Rng.of(20260906L);
        for (int i = 0; i < VERIFICATION_ROUNDS; i++) {
            long a = rng.range(-80_000_000L, 80_000_000L);
            long b = rng.range(-80_000_000L, 80_000_000L);
            assertThat(FixedPoint.mul(a, b))
                    .as("mul(%d, %d) 第 %d 轮", a, b, i)
                    .isEqualTo(referenceMul(a, b));
        }
    }

    @Test
    @DisplayName("验收5：10000 次定点除法，与 BigDecimal 参考实现误差为 0")
    void divisionMatchesBigDecimalExactly() {
        Rng rng = Rng.of(5150L);
        int done = 0;
        while (done < VERIFICATION_ROUNDS) {
            long a = rng.range(-80_000_000L, 80_000_000L);
            long b = rng.range(-80_000_000L, 80_000_000L);
            if (b == 0L) {
                continue;
            }
            assertThat(FixedPoint.div(a, b))
                    .as("div(%d, %d) 第 %d 轮", a, b, done)
                    .isEqualTo(referenceDiv(a, b));
            done++;
        }
    }

    @Test
    @DisplayName("中间积溢出 long 时自动走 BigInteger，结果仍与参考实现一致（不静默截断）")
    void multiplicationOverflowFallsBackToBigInteger() {
        // 1e10 × 1e10 = 1e20 已超出 long（9.22e18），但除以 SCALE 后 = 1e16 仍在范围内
        long a = 10_000_000_000L;
        long b = 10_000_000_000L;
        assertThat(a).isGreaterThan(0L);
        assertThat(FixedPoint.mul(a, b)).isEqualTo(referenceMul(a, b));
        assertThat(FixedPoint.mul(a, b)).isEqualTo(10_000_000_000_000_000L);

        // Long.MAX_VALUE/2 × 1.0：中间积溢出，结果等于原值
        long half = Long.MAX_VALUE / 2;
        assertThat(FixedPoint.mul(half, FixedPoint.ONE)).isEqualTo(half);
    }

    @Test
    @DisplayName("真实结果本身超出 long 范围时抛异常，绝不静默回绕成负数")
    void resultOverflowThrowsInsteadOfWrapping() {
        // Long.MAX_VALUE/2 × 3.0 的真实结果约 1.38e19，超出 long 上界
        assertThatThrownBy(() -> FixedPoint.mul(Long.MAX_VALUE / 2, FixedPoint.of(3)))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("out of long range");
    }

    @Test
    @DisplayName("HALF_UP 舍入在正负两侧对称：恰好 .5 向远离零方向进位")
    void halfUpRoundingIsSymmetric() {
        // 乘法：0.0003 × 0.5 = 0.00015 ⇒ 定点 1.5 ⇒ HALF_UP ⇒ 2
        assertThat(FixedPoint.mul(3L, 5000L)).isEqualTo(2L);
        assertThat(FixedPoint.mul(-3L, 5000L)).isEqualTo(-2L);
        // 0.0002 × 0.5 = 0.0001 ⇒ 定点 1.0，无需舍入
        assertThat(FixedPoint.mul(2L, 5000L)).isEqualTo(1L);

        // 除法：1/3 = 0.33333… ⇒ 3333；2/3 = 0.66667 ⇒ 6667
        assertThat(FixedPoint.div(FixedPoint.ONE, FixedPoint.of(3))).isEqualTo(3333L);
        assertThat(FixedPoint.div(FixedPoint.of(2), FixedPoint.of(3))).isEqualTo(6667L);
        assertThat(FixedPoint.div(-FixedPoint.ONE, FixedPoint.of(3))).isEqualTo(-3333L);
        assertThat(FixedPoint.div(-FixedPoint.of(2), FixedPoint.of(3))).isEqualTo(-6667L);

        // 恰好 .5 的边界：0.0001 / 2.0 = 0.00005 ⇒ 定点 0.5 ⇒ HALF_UP ⇒ 1
        assertThat(FixedPoint.div(1L, FixedPoint.of(2))).isEqualTo(1L);
        assertThat(FixedPoint.div(-1L, FixedPoint.of(2))).isEqualTo(-1L);
    }

    @Test
    @DisplayName("parse 与 toBigDecimal 互为逆运算，且拒绝超过 4 位小数")
    void parseAndFormatRoundTrip() {
        assertThat(FixedPoint.parse("1.18")).isEqualTo(11800L);
        assertThat(FixedPoint.parse("-0.95")).isEqualTo(-9500L);
        assertThat(FixedPoint.parse("0")).isEqualTo(0L);
        assertThat(FixedPoint.parse(" 2.0 ")).isEqualTo(20000L);
        assertThat(FixedPoint.toBigDecimal(11800L)).isEqualByComparingTo("1.18");
        assertThat(FixedPoint.format(11800L)).isEqualTo("1.18");

        // 超过 4 位小数无法定点精确表示，必须报错而不是静默截断
        assertThatThrownBy(() -> FixedPoint.parse("1.00001"))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> FixedPoint.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FixedPoint.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FixedPoint.parse("abc"))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    @DisplayName("of / round / truncate 语义正确")
    void wholeNumberConversions() {
        assertThat(FixedPoint.of(3)).isEqualTo(30000L);
        assertThat(FixedPoint.round(15000L)).isEqualTo(2L);
        assertThat(FixedPoint.round(14999L)).isEqualTo(1L);
        assertThat(FixedPoint.round(-15000L)).isEqualTo(-2L);
        assertThat(FixedPoint.truncate(19999L)).isEqualTo(1L);
        assertThat(FixedPoint.truncate(-19999L)).isEqualTo(-1L);
    }

    @Test
    @DisplayName("整数次幂精确：1.18^7 与 BigDecimal.pow 逐位一致（建筑时间曲线的基础）")
    void integerPowerIsExact() {
        BigDecimal reference = new BigDecimal("1.18").pow(7)
                .movePointRight(4).setScale(0, RoundingMode.HALF_UP);
        assertThat(FixedPoint.powInt(FixedPoint.parse("1.18"), 7))
                .isEqualTo(reference.longValueExact());

        assertThat(FixedPoint.powInt(FixedPoint.ONE, 0)).isEqualTo(FixedPoint.ONE);
        assertThat(FixedPoint.powInt(FixedPoint.parse("1.22"), 0)).isEqualTo(FixedPoint.ONE);
        assertThat(FixedPoint.powInt(FixedPoint.parse("1.28"), 19))
                .isEqualTo(new BigDecimal("1.28").pow(19).movePointRight(4)
                        .setScale(0, RoundingMode.HALF_UP).longValueExact());
        assertThatThrownBy(() -> FixedPoint.powInt(FixedPoint.ONE, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("分数次幂正确：2.0^1.08 与 double 参考值相差在 2 个定点单位内（仅用于校验量级，不作为真值）")
    void fractionalPowerIsCloseToReference() {
        long actual = FixedPoint.pow(FixedPoint.of(2), FixedPoint.parse("1.08"));
        double reference = Math.pow(2.0d, 1.08d) * FixedPoint.SCALE;
        assertThat(Math.abs(actual - reference)).isLessThanOrEqualTo(2.0d);
    }

    @Test
    @DisplayName("分数次幂可复现且单调：n^1.08 随 n 递增，两次调用逐位相同")
    void fractionalPowerIsDeterministicAndMonotonic() {
        long exp = FixedPoint.parse("1.08");
        long prev = 0L;
        for (int n = 1; n <= 40; n++) {
            long v = FixedPoint.pow(FixedPoint.of(n), exp);
            assertThat(v).as("n=%d 的曲线值应单调不减", n).isGreaterThanOrEqualTo(prev);
            assertThat(FixedPoint.pow(FixedPoint.of(n), exp)).isEqualTo(v);
            prev = v;
        }
    }

    @Test
    @DisplayName("pow 对非正底数抛异常；指数 0 与 1 走快捷路径")
    void powGuardsAndShortcuts() {
        assertThatThrownBy(() -> FixedPoint.pow(0L, FixedPoint.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FixedPoint.pow(-FixedPoint.ONE, FixedPoint.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FixedPoint.pow(FixedPoint.of(7), 0L)).isEqualTo(FixedPoint.ONE);
        assertThat(FixedPoint.pow(FixedPoint.of(7), FixedPoint.ONE)).isEqualTo(FixedPoint.of(7));
        // 定点整数指数（2.0）应走精确整数幂路径，与 powInt 结果一致
        assertThat(FixedPoint.pow(FixedPoint.of(3), FixedPoint.of(2)))
                .isEqualTo(FixedPoint.powInt(FixedPoint.of(3), 2));
    }

    @Test
    @DisplayName("percent 用于加成计算：2000 的 15% = 300")
    void percentAppliesBonus() {
        assertThat(FixedPoint.percent(2000L, FixedPoint.parse("0.15"))).isEqualTo(300L);
        assertThat(FixedPoint.percent(FixedPoint.of(1000), FixedPoint.parse("1.25")))
                .isEqualTo(FixedPoint.of(1250));
    }

    @Test
    @DisplayName("clamp 夹取边界，非法区间抛异常")
    void clampBounds() {
        assertThat(FixedPoint.clamp(150L, 100L, 200L)).isEqualTo(150L);
        assertThat(FixedPoint.clamp(50L, 100L, 200L)).isEqualTo(100L);
        assertThat(FixedPoint.clamp(500L, 100L, 200L)).isEqualTo(200L);
        assertThatThrownBy(() -> FixedPoint.clamp(150L, 200L, 100L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("除零抛 ArithmeticException，不返回 NaN 或 0")
    void divisionByZeroThrows() {
        assertThatThrownBy(() -> FixedPoint.div(FixedPoint.ONE, 0L))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("除零");
    }

    @Test
    @DisplayName("add/sub/negate 溢出时抛异常而非静默回绕")
    void additiveOverflowIsDetected() {
        assertThat(FixedPoint.add(FixedPoint.ONE, FixedPoint.ONE)).isEqualTo(FixedPoint.of(2));
        assertThat(FixedPoint.sub(FixedPoint.ONE, FixedPoint.ONE)).isEqualTo(FixedPoint.ZERO);
        assertThat(FixedPoint.negate(FixedPoint.ONE)).isEqualTo(-FixedPoint.ONE);
        assertThatThrownBy(() -> FixedPoint.add(Long.MAX_VALUE, FixedPoint.ONE))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> FixedPoint.negate(Long.MIN_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("战斗链路冒烟：有效攻击 × 克制系数 × 损失浮动，全程 long 无 double")
    void battleChainStaysInFixedPoint() {
        long unitAtk = FixedPoint.of(120);          // 单位攻击 120
        long count = FixedPoint.of(500);            // 兵数 500
        long counterBonus = FixedPoint.ONE + FixedPoint.parse("0.25");   // 克制 +25%
        long lossJitter = FixedPoint.parse("1.05"); // 损失浮动上界

        long totalAtk = FixedPoint.mul(FixedPoint.mul(count, unitAtk), counterBonus);
        long jittered = FixedPoint.mul(totalAtk, lossJitter);

        assertThat(totalAtk).isEqualTo(FixedPoint.of(75000));
        assertThat(jittered).isEqualTo(FixedPoint.of(78750));
    }
}
