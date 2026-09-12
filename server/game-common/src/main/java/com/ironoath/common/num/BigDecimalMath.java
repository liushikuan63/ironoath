package com.ironoath.common.num;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 职责：BigDecimal 超越函数（exp / ln / pow / sqrt），供定点数曲线计算使用。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p><b>为什么不用 {@code Math.pow}</b>：铁律 5 禁止 float/double 参与结算，而 {@code Math.pow}
 * 的 JLS 精度约束只到 1 ulp，不同 JVM / 不同 CPU 可能给出不同的最后一位，
 * 会让「服务端算的产量」与「复算校验」出现分歧。本类全程 BigDecimal 泰勒级数，
 * 结果只取决于算法与精度上下文，跨平台逐位一致，也因此可以直接移植成客户端 TS 的同名实现。
 *
 * <p>性能：单次 pow 约百微秒量级。<b>不得放在每回合战斗循环内</b>；
 * 曲线求值由 {@code game-core} 的 Formula 预计算并缓存整张等级表。
 */
public final class BigDecimalMath {

    /** 内部运算精度。40 位有效数字，足够让最终 4 位小数的定点结果稳定。 */
    static final MathContext MC = new MathContext(40, RoundingMode.HALF_EVEN);

    /** 级数收敛阈值。小于该值即停止累加。 */
    private static final BigDecimal TOLERANCE = new BigDecimal("1E-45");

    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal HALF = new BigDecimal("0.5");
    /** ln 归约时希望 |m-1| 小于该值，使 atanh 级数快速收敛。 */
    private static final BigDecimal NEAR_ONE = new BigDecimal("0.1");

    /** 自然常数 e，由 expSmall(1) 算出而非硬编码数字，避免手抄常数出错。 */
    private static final BigDecimal E = expSmall(BigDecimal.ONE);

    /** ln 2，由 atanh 级数在 x=2 处直接算出（y=1/3，收敛比 1/9）。 */
    private static final BigDecimal LN2 = lnByAtanh(TWO);

    private BigDecimalMath() {
    }

    /**
     * e 的 x 次方。
     *
     * @param x 指数，任意有限值
     */
    public static BigDecimal exp(BigDecimal x) {
        BigDecimal integerPart = new BigDecimal(x.toBigInteger());
        BigDecimal frac = x.subtract(integerPart);
        return expSmall(frac).multiply(expInteger(integerPart.intValueExact()), MC);
    }

    /**
     * 自然对数。
     *
     * @param x 真数，必须为正
     * @throws ArithmeticException 当 x &lt;= 0
     */
    public static BigDecimal ln(BigDecimal x) {
        if (x.signum() <= 0) {
            throw new ArithmeticException("ln 的参数必须为正数，实际=" + x);
        }
        // 第一步：二进制归约 x = m * 2^k，把 m 压到 [0.5, 2)
        int k = 0;
        BigDecimal m = x;
        while (m.compareTo(TWO) >= 0) {
            m = m.divide(TWO, MC);
            k++;
        }
        while (m.compareTo(HALF) < 0) {
            m = m.multiply(TWO, MC);
            k--;
        }
        // 第二步：反复开平方把 m 拉到 1 附近，atanh 级数收敛速度与 |m-1| 成正比
        int sqrtCount = 0;
        while (m.subtract(BigDecimal.ONE).abs().compareTo(NEAR_ONE) > 0) {
            m = m.sqrt(MC);
            sqrtCount++;
        }
        BigDecimal lnM = lnByAtanh(m);
        if (sqrtCount > 0) {
            // ln(m^(1/2^s)) = ln(m)/2^s ⇒ 反推 ln(m) = ln(m^(1/2^s)) * 2^s
            lnM = lnM.multiply(TWO.pow(sqrtCount, MC), MC);
        }
        return lnM.add(LN2.multiply(BigDecimal.valueOf(k), MC), MC);
    }

    /**
     * 任意实数次幂：base^exponent = exp(exponent * ln(base))。
     *
     * @param base     底数，必须为正
     * @param exponent 指数，可为分数（如 1.08）
     */
    public static BigDecimal pow(BigDecimal base, BigDecimal exponent) {
        if (base.signum() <= 0) {
            throw new ArithmeticException("pow 的底数必须为正数，实际=" + base);
        }
        if (exponent.signum() == 0) {
            return BigDecimal.ONE;
        }
        if (exponent.signum() > 0 && exponent.stripTrailingZeros().scale() <= 0) {
            // 整数指数走快速幂，比 exp/ln 更精确也更快
            return powInteger(base, exponent.intValueExact());
        }
        return exp(exponent.multiply(ln(base), MC));
    }

    /** 小数部分指数，|frac| &lt; 1，泰勒级数收敛极快。 */
    private static BigDecimal expSmall(BigDecimal x) {
        BigDecimal sum = BigDecimal.ONE;
        BigDecimal term = BigDecimal.ONE;
        for (int k = 1; k < 300; k++) {
            term = term.multiply(x, MC).divide(BigDecimal.valueOf(k), MC);
            sum = sum.add(term, MC);
            if (term.abs().compareTo(TOLERANCE) < 0) {
                break;
            }
        }
        return sum;
    }

    /** e 的整数次方，快速幂。 */
    private static BigDecimal expInteger(int n) {
        if (n == 0) {
            return BigDecimal.ONE;
        }
        if (n < 0) {
            return BigDecimal.ONE.divide(expInteger(-n), MC);
        }
        BigDecimal result = BigDecimal.ONE;
        BigDecimal base = E;
        int k = n;
        while (k > 0) {
            if ((k & 1) == 1) {
                result = result.multiply(base, MC);
            }
            base = base.multiply(base, MC);
            k >>= 1;
        }
        return result;
    }

    /** 正整数次幂，快速幂。 */
    private static BigDecimal powInteger(BigDecimal base, int n) {
        if (n == 0) {
            return BigDecimal.ONE;
        }
        if (n < 0) {
            return BigDecimal.ONE.divide(powInteger(base, -n), MC);
        }
        BigDecimal result = BigDecimal.ONE;
        BigDecimal b = base;
        int k = n;
        while (k > 0) {
            if ((k & 1) == 1) {
                result = result.multiply(b, MC);
            }
            b = b.multiply(b, MC);
            k >>= 1;
        }
        return result;
    }

    /**
     * ln x = 2·atanh((x-1)/(x+1)) = 2·Σ y^(2n+1)/(2n+1)，y = (x-1)/(x+1)。
     *
     * <p>要求 x 在 1 附近才收敛得快，调用方需先做归约与开平方预处理。
     */
    private static BigDecimal lnByAtanh(BigDecimal x) {
        BigDecimal y = x.subtract(BigDecimal.ONE).divide(x.add(BigDecimal.ONE), MC);
        BigDecimal y2 = y.multiply(y, MC);
        BigDecimal term = y;
        BigDecimal sum = BigDecimal.ZERO;
        for (int n = 0; n < 2000; n++) {
            BigDecimal part = term.divide(BigDecimal.valueOf(2L * n + 1), MC);
            sum = sum.add(part, MC);
            if (part.abs().compareTo(TOLERANCE) < 0) {
                break;
            }
            term = term.multiply(y2, MC);
        }
        return sum.multiply(TWO, MC);
    }
}
