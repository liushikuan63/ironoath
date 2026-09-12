package com.ironoath.common.num;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * 职责：定点数工具 —— 全项目战斗与资源结算的唯一数值载体。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p>约定（B00 跨语言一致性策略第 5 条 / 铁律 5）：所有小数统一放大 10000 倍存成 {@code long}。
 * 例如 1.18 存为 11800，0.95 存为 9500。<b>禁止 float/double 参与战斗与资源结算</b>。
 *
 * <p>舍入策略：所有乘除一律 HALF_UP（四舍五入），与 BigDecimal 参考实现逐位一致（B01 验收 5）。
 * 混用 HALF_UP 与 HALF_EVEN 会让双端复算出现 1 单位差，进而导致战报无法复现。
 *
 * <p>溢出策略：中间积超出 long 时自动降级到 BigInteger 精确计算，<b>不静默截断</b>。
 * 战斗里兵数 × 攻击 × 多重乘区极易越过 long 边界，静默溢出会产出负数伤害这种无法排查的脏数据。
 */
public final class FixedPoint {

    /** 定点放大倍数。数值来源：contract/config/global.json 的 FIXED_POINT_SCALE。 */
    public static final long SCALE = 10000L;

    /** 定点 0。 */
    public static final long ZERO = 0L;

    /** 定点 1.0。 */
    public static final long ONE = SCALE;

    private static final BigDecimal SCALE_DECIMAL = BigDecimal.valueOf(SCALE);

    /** movePointLeft/Right 的位数，等于 SCALE 的十进制位数（10000 ⇒ 4）。 */
    private static final int SCALE_DIGITS = 4;

    private FixedPoint() {
    }

    // ---------- 构造与转换 ----------

    /** 整数转定点：3 ⇒ 30000。 */
    public static long of(long whole) {
        return safeMultiply(whole, SCALE);
    }

    /**
     * 十进制字符串转定点：{@code "1.18"} ⇒ 11800。
     *
     * <p>配置表中的小数字段一律用字符串书写（见 contract/README.md），
     * 就是为了在源头避免 JSON number 的 double 二进制误差。
     *
     * <p>严格模式：小数位数超过 4 位时<b>抛异常而非静默舍入</b>。定点数只能精确表示 4 位小数，
     * 配置表里写出 {@code "1.00001"} 属于数据错误，必须在启动期被校验器抓出来。
     *
     * @throws ArithmeticException        当小数位数超过 4 位，或结果溢出 long
     * @throws IllegalArgumentException   当入参为空
     * @throws NumberFormatException      当入参不是合法十进制数
     */
    public static long parse(String decimal) {
        if (decimal == null || decimal.isBlank()) {
            throw new IllegalArgumentException("定点数解析入参不得为空");
        }
        BigDecimal value = new BigDecimal(decimal.trim());
        if (value.stripTrailingZeros().scale() > SCALE_DIGITS) {
            throw new ArithmeticException(
                    "小数位数超过定点精度（" + SCALE_DIGITS + " 位），无法精确表示：" + decimal);
        }
        return fromBigDecimal(value);
    }

    /** BigDecimal（真实值，非定点）转定点，HALF_UP 舍入到 4 位小数。 */
    public static long fromBigDecimal(BigDecimal realValue) {
        BigDecimal scaled = realValue.movePointRight(SCALE_DIGITS).setScale(0, RoundingMode.HALF_UP);
        try {
            return scaled.longValueExact();
        } catch (ArithmeticException e) {
            throw new ArithmeticException("定点数溢出 long 范围：" + summarize(scaled));
        }
    }

    /**
     * 大数的量级摘要。
     *
     * <p>高等级曲线求值溢出时，真实值可能有上千位数字。把完整数值塞进异常信息会刷爆日志，
     * 而对排查毫无帮助 —— 只需要知道「量级是多少、有多少位」就能判断是哪条曲线跑飞了。
     */
    private static String summarize(BigDecimal value) {
        BigDecimal abs = value.abs();
        String digits = abs.toBigInteger().toString();
        String head = digits.length() <= 12 ? digits : digits.substring(0, 12) + "…";
        return value.signum() < 0 ? "-" + head + "（共 " + digits.length() + " 位整数）"
                : head + "（共 " + digits.length() + " 位整数）";
    }

    /** 定点转 BigDecimal 真实值：11800 ⇒ 1.18（精确，无舍入）。 */
    public static BigDecimal toBigDecimal(long fixed) {
        return BigDecimal.valueOf(fixed).movePointLeft(SCALE_DIGITS);
    }

    /**
     * 定点转 long 整数，HALF_UP 舍入：15000 ⇒ 2，14999 ⇒ 1。
     * 资源与兵数最终落地为整数时使用。
     */
    public static long round(long fixed) {
        return divideHalfUp(fixed, SCALE);
    }

    /**
     * 定点转 long 整数，向零截断：19999 ⇒ 1，-19999 ⇒ -1。
     * 用于「不足 1 个不给」的场景（如按小时产量折算不足 1 小时的部分）。
     */
    public static long truncate(long fixed) {
        return fixed / SCALE;
    }

    /**
     * 定点的可读字符串，用于日志与战报，不参与运算。
     * 去掉尾随零：11800 ⇒ "1.18" 而非 "1.1800"。
     */
    public static String format(long fixed) {
        return toBigDecimal(fixed).stripTrailingZeros().toPlainString();
    }

    // ---------- 四则运算 ----------

    public static long add(long a, long b) {
        return safeAdd(a, b);
    }

    public static long sub(long a, long b) {
        return safeSubtract(a, b);
    }

    public static long negate(long a) {
        if (a == Long.MIN_VALUE) {
            throw new ArithmeticException("定点数取负溢出：Long.MIN_VALUE");
        }
        return -a;
    }

    public static long abs(long a) {
        return a < 0 ? negate(a) : a;
    }

    /**
     * 定点乘法：{@code mul(11800, 20000)} ⇒ 23600（1.18 × 2.0 = 2.36）。
     * 结果 HALF_UP 舍入到 4 位小数。
     *
     * <p>中间积溢出 long 时整条运算转入 BigInteger 域，只有<b>最终结果</b>超出 long 才抛异常。
     * 这点很关键：战斗里「兵数 × 单位攻击」的中间量轻易就能越过 9.2e18，
     * 但除以 SCALE 之后完全在范围内，如果在这里就放弃会得到大量假溢出。
     */
    public static long mul(long a, long b) {
        try {
            return divideHalfUp(Math.multiplyExact(a, b), SCALE);
        } catch (ArithmeticException intermediateOverflow) {
            return divideHalfUpBig(
                    BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)),
                    BigInteger.valueOf(SCALE));
        }
    }

    /**
     * 定点除法：{@code div(23600, 11800)} ⇒ 20000（2.36 ÷ 1.18 = 2.0）。
     * 结果 HALF_UP 舍入到 4 位小数。
     *
     * @throws ArithmeticException 当除数为 0，或最终结果超出 long 范围
     */
    public static long div(long a, long b) {
        if (b == 0L) {
            throw new ArithmeticException("定点数除零：被除数=" + format(a));
        }
        try {
            return divideHalfUp(Math.multiplyExact(a, SCALE), b);
        } catch (ArithmeticException intermediateOverflow) {
            return divideHalfUpBig(
                    BigInteger.valueOf(a).multiply(BigInteger.valueOf(SCALE)),
                    BigInteger.valueOf(b));
        }
    }

    /**
     * 按百分比取值：{@code percent(2000, 1500)} ⇒ 300（2000 的 15% = 300）。
     * 用于各类加成（复仇 +15%、克制 +25%）。
     */
    public static long percent(long value, long percentFixed) {
        return mul(value, percentFixed);
    }

    // ---------- 幂运算（曲线求值专用） ----------

    /**
     * 整数次幂，<b>精确无舍入中间损失</b>：{@code powInt(11800, 7)} = 1.18^7 的定点值。
     *
     * <p>用于几何型成长曲线 T(n) = T0 × ratio^(n-1)。中间过程用 BigDecimal 精确幂，
     * 只在最后落地定点时舍入一次，避免逐次定点相乘累积 49 次舍入误差。
     *
     * @param baseFixed 底数（定点）
     * @param exponent  非负整数指数
     */
    public static long powInt(long baseFixed, int exponent) {
        if (exponent < 0) {
            throw new IllegalArgumentException("powInt 的指数不得为负：" + exponent);
        }
        if (exponent == 0) {
            return ONE;
        }
        return fromBigDecimal(toBigDecimal(baseFixed).pow(exponent));
    }

    /**
     * 实数次幂：{@code pow(20000, 10800)} = 2.0^1.08 的定点值。
     *
     * <p>用于幂律型成长曲线 P(n) = P0 × n^1.08。走 {@link BigDecimalMath} 的 exp/ln 级数，
     * 不用 {@code Math.pow}，理由见 BigDecimalMath 类注释（跨平台逐位一致）。
     *
     * @param baseFixed     底数（定点），必须为正
     * @param exponentFixed 指数（定点），可为分数
     */
    public static long pow(long baseFixed, long exponentFixed) {
        if (baseFixed <= 0L) {
            throw new IllegalArgumentException("pow 的底数必须为正定点数，实际=" + format(baseFixed));
        }
        if (exponentFixed == ZERO) {
            return ONE;
        }
        if (exponentFixed == ONE) {
            return baseFixed;
        }
        // 指数为整数时走精确幂路径
        if (exponentFixed % SCALE == 0L) {
            long exp = exponentFixed / SCALE;
            if (exp >= 0 && exp <= Integer.MAX_VALUE) {
                return powInt(baseFixed, (int) exp);
            }
        }
        BigDecimal result = BigDecimalMath.pow(toBigDecimal(baseFixed), toBigDecimal(exponentFixed));
        return fromBigDecimal(result);
    }

    /**
     * 几何型成长曲线：{@code base × ratio^exponent}，全程 BigDecimal 精确运算，<b>只在最后舍入一次</b>。
     *
     * <p>为什么不用 {@code mul(base, powInt(ratio, exponent))} 组合：powInt 会先把 ratio^exponent
     * 落地成 4 位小数的定点值，再与基数相乘。基数较大时这一步的舍入误差会被放大 ——
     * 实测 {@code 200 × 1.22^4} 用组合写法得到 443.0600，精确值是 443.0669，差 69 个定点单位。
     * 建筑消耗、行军时间这类数字差 69 单位，双端复算就会对不上。
     *
     * @param baseFixed  基数（定点），如建筑 1 级消耗 C0
     * @param ratioFixed 比率（定点），如 1.22 ⇒ 12200
     * @param exponent   非负整数指数，如 n-1
     */
    public static long geometric(long baseFixed, long ratioFixed, int exponent) {
        if (exponent < 0) {
            throw new IllegalArgumentException("geometric 的指数不得为负：" + exponent);
        }
        if (ratioFixed < 0L) {
            throw new IllegalArgumentException(
                    "geometric 的比率不得为负，实际=" + format(ratioFixed));
        }
        BigDecimal result = toBigDecimal(baseFixed).multiply(toBigDecimal(ratioFixed).pow(exponent));
        return fromBigDecimal(result);
    }

    /**
     * 幂律型成长曲线：{@code base × level^exponent}，指数可为分数（如 1.08）。
     *
     * <p>同样只在最后舍入一次，避免中间落地造成的精度损失。
     * 分数指数走 {@link BigDecimalMath} 的 exp/ln 级数，跨平台逐位一致（不用 {@code Math.pow}）。
     *
     * @param baseFixed     基数（定点），如建筑 1 级产量 P0
     * @param level         等级，必须为正整数
     * @param exponentFixed 指数（定点），如 1.08 ⇒ 10800
     */
    public static long powerLaw(long baseFixed, long level, long exponentFixed) {
        if (level <= 0L) {
            throw new IllegalArgumentException("powerLaw 的等级必须为正整数，实际=" + level);
        }
        if (baseFixed <= 0L) {
            throw new IllegalArgumentException(
                    "powerLaw 的基数必须为正定点数，实际=" + format(baseFixed));
        }
        BigDecimal factor = BigDecimalMath.pow(BigDecimal.valueOf(level), toBigDecimal(exponentFixed));
        return fromBigDecimal(toBigDecimal(baseFixed).multiply(factor));
    }

    // ---------- 开方 ----------

    /**
     * 定点开方：返回 {@code floor(sqrt(fixed / SCALE)) × SCALE}。
     *
     * <p><b>为什么必须有这个方法</b>：B08 的集结破圈要算 {@code upper = 2.0 × √N}，
     * 而 B08 明写「用 FixedPoint.sqrt() 计算，禁止 {@code Math.sqrt(double)}，
     * 避免边界值判定出现精度分歧」。所谓边界判定就是「战力恰好等于 2.0×√N 倍时算不算通过」——
     * √2 用 double 算是 1.4142135623730951，乘回定点后是 14142.135… 再取整，
     * 同一个 N 在不同 JVM / 不同优化等级下可能差 1 个定点单位，
     * 而那 1 个单位正好决定一次攻击被允许还是被拒绝。
     *
     * <p><b>向下取整是刻意的</b>：对圈层校验而言，向下取整让区间只会略窄、绝不会略宽，
     * 也就是「宁可拒绝一个本该允许的目标，也不放过一个本该拒绝的目标」。
     * 反方向的偏差是漏洞（能打超出圈层的人），而那正是 B08 要防的战力崩坏。
     *
     * @param fixed 被开方的定点值，不得为负
     */
    public static long sqrt(long fixed) {
        if (fixed < 0L) {
            throw new IllegalArgumentException("不能对负数开方：" + fixed);
        }
        if (fixed == 0L) {
            return 0L;
        }
        // fixed × SCALE 可能溢出 long，用 BigInteger 兜底（与 mul/div 同一套做法）
        java.math.BigInteger scaled = java.math.BigInteger.valueOf(fixed)
                .multiply(java.math.BigInteger.valueOf(SCALE));
        java.math.BigInteger root = isqrt(scaled);
        if (root.bitLength() >= 63) {
            throw new ArithmeticException("开方结果溢出 long：fixed=" + fixed);
        }
        return root.longValue();
    }

    /**
     * 整数平方根（floor），牛顿迭代。
     *
     * <p>初值取 {@code 1 << (bitLength/2)} 而不是 {@code n/2}：后者对大数要迭代几十次，
     * 前者只要个位数次。终止条件是 {@code y >= x} —— 牛顿迭代在整数域会在两个值之间振荡，
     * 等「相等」会死循环。
     */
    private static java.math.BigInteger isqrt(java.math.BigInteger n) {
        java.math.BigInteger x = java.math.BigInteger.ONE.shiftLeft((n.bitLength() + 1) / 2);
        while (true) {
            java.math.BigInteger y = x.add(n.divide(x)).shiftRight(1);
            if (y.compareTo(x) >= 0) {
                return x;
            }
            x = y;
        }
    }

    // ---------- 比较 ----------

    public static int compare(long a, long b) {
        return Long.compare(a, b);
    }

    public static long min(long a, long b) {
        return Math.min(a, b);
    }

    public static long max(long a, long b) {
        return Math.max(a, b);
    }

    /** 把结果夹在 [lo, hi] 闭区间内。lo &gt; hi 时抛异常，不静默交换。 */
    public static long clamp(long value, long lo, long hi) {
        if (lo > hi) {
            throw new IllegalArgumentException(
                    "clamp 区间非法：lo=" + format(lo) + " > hi=" + format(hi));
        }
        return value < lo ? lo : (value > hi ? hi : value);
    }

    // ---------- 内部：溢出安全的 long 运算 ----------

    private static long safeMultiply(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException overflow) {
            return BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).longValueExact();
        }
    }

    private static long safeAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            return BigInteger.valueOf(a).add(BigInteger.valueOf(b)).longValueExact();
        }
    }

    private static long safeSubtract(long a, long b) {
        try {
            return Math.subtractExact(a, b);
        } catch (ArithmeticException overflow) {
            return BigInteger.valueOf(a).subtract(BigInteger.valueOf(b)).longValueExact();
        }
    }

    /**
     * HALF_UP 整数除法（保留符号语义：正负都按绝对值四舍五入）。
     *
     * <p>比较 {@code absR >= absDen - absR} 而非 {@code absR * 2 >= absDen}，是为了避免 absR*2 溢出。
     */
    private static long divideHalfUp(long numerator, long denominator) {
        if (denominator == 0L) {
            throw new ArithmeticException("定点数除零");
        }
        if (denominator == Long.MIN_VALUE || numerator == Long.MIN_VALUE) {
            return divideHalfUpBig(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
        }
        long q = numerator / denominator;
        long r = numerator % denominator;
        if (r == 0L) {
            return q;
        }
        long absR = Math.abs(r);
        long absDen = Math.abs(denominator);
        if (absR >= absDen - absR) {
            q += ((r > 0) == (denominator > 0)) ? 1L : -1L;
        }
        return q;
    }

    private static long divideHalfUpBig(BigInteger numerator, BigInteger denominator) {
        BigInteger[] dr = numerator.divideAndRemainder(denominator);
        BigInteger q = dr[0];
        BigInteger r = dr[1];
        if (r.signum() == 0) {
            return q.longValueExact();
        }
        BigInteger absR = r.abs();
        BigInteger absDen = denominator.abs();
        if (absR.compareTo(absDen.subtract(absR)) >= 0) {
            boolean sameSign = (r.signum() > 0) == (denominator.signum() > 0);
            q = sameSign ? q.add(BigInteger.ONE) : q.subtract(BigInteger.ONE);
        }
        return q.longValueExact();
    }

    /** 供测试断言使用：暴露与 BigDecimal 参考实现的对照值。 */
    static BigDecimal referenceMul(long a, long b) {
        return toBigDecimal(a).multiply(toBigDecimal(b)).setScale(SCALE_DIGITS, RoundingMode.HALF_UP);
    }

    /** 供测试断言使用：暴露与 BigDecimal 参考实现的对照值。 */
    static BigDecimal referenceDiv(long a, long b) {
        return toBigDecimal(a).divide(toBigDecimal(b), SCALE_DIGITS, RoundingMode.HALF_UP);
    }

    static BigDecimal scaleDecimal() {
        return SCALE_DECIMAL;
    }
}
