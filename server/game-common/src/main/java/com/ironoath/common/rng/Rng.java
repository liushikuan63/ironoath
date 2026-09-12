package com.ironoath.common.rng;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 职责：带 seed 的可复现伪随机数发生器（mulberry32 算法的 Java 实现）。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p>铁律 4「随机可复现」：全项目所有随机必须走本类，禁止 {@code Math.random()} 与 {@code java.util.Random}。
 * 战报只存 seed + 输入，服务端凭本类可 100% 复算。客户端有同名同算法的 TS 实现，
 * 但仅用于表现层，不参与任何结算（跨语言一致性策略第 1 条）。
 *
 * <p>结算安全性：本类对外提供的区间随机（{@link #nextFixed}、{@link #range}、{@link #chance}）
 * 全程 long 运算，不经过 double，满足铁律 5。{@link #next()} 返回 double，
 * 仅用于表现层与非结算用途，禁止把它直接乘进伤害或资源公式。
 *
 * <p>线程模型：非线程安全。每个随机域（每场战斗、每次抽卡）持有独立实例，不跨线程共享。
 */
public final class Rng {

    /** mulberry32 每轮递增量。 */
    private static final int INCREMENT = 0x6D2B79F5;

    /** 2^32，用于把无符号 32 位整数映射到 [0,1)。 */
    private static final double UINT32_SPACE = 4294967296.0d;

    /** 32 位雪崩混淆常量（splitmix32 finalizer），用于 seed 与 fork 的初始化。 */
    private static final int MIX_C1 = 0x7feb352d;
    private static final int MIX_C2 = 0x846ca68b;

    /** 内部状态。所有随机都由此推进，同 seed 同调用序列 ⇒ 逐位相同的输出。 */
    private int state;

    private Rng(int state) {
        this.state = state;
    }

    /**
     * 由 seed 创建随机流。相同 seed 必然产生相同序列（B01 验收 3）。
     *
     * @param seed 任意 long，内部折叠为 32 位并做雪崩混淆，避免相邻 seed 产生相似序列
     */
    public static Rng of(long seed) {
        return new Rng(mix((int) (seed ^ (seed >>> 32))));
    }

    /**
     * 派生子随机流 —— 关键方法（B01 验收 4）。
     *
     * <p>战斗内有多个随机点（暴击、技能触发、伤害浮动）。若共用一个流，改动任一处随机调用顺序
     * 都会导致历史战报无法复现。用 fork 为每个随机点隔离出独立子流：
     * 子流的推进不影响父流，父流的推进也不影响已 fork 出的子流。
     *
     * @param salt 区分不同随机点的盐值，建议用稳定的枚举序号或回合号，不要用递变的计数器
     */
    public Rng fork(long salt) {
        int saltBits = mix((int) (salt ^ (salt >>> 32)));
        return new Rng(mix(state ^ saltBits));
    }

    /**
     * 返回 [0, 1) 区间的均匀随机 double。
     *
     * <p><b>禁止用于结算</b>：double 参与战斗或资源运算违反铁律 5。
     * 结算请用 {@link #nextFixed(long, long)} 或 {@link #chance(long)}。
     */
    public double next() {
        return Integer.toUnsignedLong(nextBits()) / UINT32_SPACE;
    }

    /**
     * 闭区间 [a, b] 的均匀随机整数。用于配置表给出的区间（如伤害浮动 0.95~1.05 的定点表示）。
     *
     * @throws IllegalArgumentException 当 b &lt; a，或区间跨度溢出 long
     */
    public long range(long a, long b) {
        if (b < a) {
            throw new IllegalArgumentException("range 要求 a <= b，实际 a=" + a + ", b=" + b);
        }
        long span = b - a;
        if (span < 0) {
            throw new IllegalArgumentException("range 区间跨度溢出 long：a=" + a + ", b=" + b);
        }
        return a + nextBounded(span + 1);
    }

    /**
     * 半开区间 [a, b) 的均匀随机整数。用于索引、取模等习惯半开的场景。
     *
     * @throws IllegalArgumentException 当 b &lt;= a
     */
    public long nextInt(long a, long b) {
        if (b <= a) {
            throw new IllegalArgumentException("nextInt 要求 a < b，实际 a=" + a + ", b=" + b);
        }
        return a + nextBounded(b - a);
    }

    /**
     * 定点数闭区间随机 [minFixed, maxFixed]，全程 long，<b>结算专用</b>。
     *
     * <p>典型用法：战斗损失浮动 0.95~1.05，传入 {@code FixedPoint.parse("0.95")} 与
     * {@code FixedPoint.parse("1.05")}。
     */
    public long nextFixed(long minFixed, long maxFixed) {
        return range(minFixed, maxFixed);
    }

    /**
     * 概率判定。probabilityFixed 为放大 10000 倍的概率，例如 1500 表示 15%。
     *
     * @param probabilityFixed 定点概率，合法范围 [0, 10000]；0 恒 false，10000 恒 true
     * @throws IllegalArgumentException 当概率越界
     */
    public boolean chance(long probabilityFixed) {
        if (probabilityFixed <= 0L) {
            if (probabilityFixed < 0L) {
                throw new IllegalArgumentException("chance 概率不得为负：" + probabilityFixed);
            }
            return false;
        }
        if (probabilityFixed >= com.ironoath.common.num.FixedPoint.SCALE) {
            if (probabilityFixed > com.ironoath.common.num.FixedPoint.SCALE) {
                throw new IllegalArgumentException(
                        "chance 概率不得超过定点 1.0（" + com.ironoath.common.num.FixedPoint.SCALE
                                + "），实际=" + probabilityFixed);
            }
            return true;
        }
        // nextBounded(SCALE) 产生 [0, 9999] 均匀整数，与概率阈值直接比较，无 double 参与
        return nextBounded(com.ironoath.common.num.FixedPoint.SCALE) < probabilityFixed;
    }

    /** 返回一个均匀分布的 64 位随机整数（可为负）。 */
    public long nextLong() {
        return (((long) nextBits()) << 32) ^ Integer.toUnsignedLong(nextBits());
    }

    /**
     * 从列表中随机取一个元素。
     *
     * @throws IllegalArgumentException 当列表为空
     */
    public <T> T pick(List<T> arr) {
        Objects.requireNonNull(arr, "pick 的入参列表不得为 null");
        if (arr.isEmpty()) {
            throw new IllegalArgumentException("pick 的入参列表不得为空");
        }
        return arr.get((int) nextBounded(arr.size()));
    }

    /**
     * 随机打乱，<b>返回新列表</b>，不修改入参（Fisher-Yates）。
     *
     * <p>不修改入参是刻意的：调用方常把配置表的不可变列表直接传进来。
     */
    public <T> List<T> shuffle(List<T> arr) {
        Objects.requireNonNull(arr, "shuffle 的入参列表不得为 null");
        List<T> copy = new ArrayList<>(arr);
        for (int i = copy.size() - 1; i > 0; i--) {
            int j = (int) nextBounded(i + 1);
            T tmp = copy.get(i);
            copy.set(i, copy.get(j));
            copy.set(j, tmp);
        }
        return copy;
    }

    /**
     * mulberry32 单轮推进，返回 32 位原始输出。
     *
     * <p>与客户端 TS 实现逐位对应：
     * <pre>
     *   a = a + 0x6D2B79F5 | 0;
     *   var t = Math.imul(a ^ a &gt;&gt;&gt; 15, 1 | a);
     *   t = t + Math.imul(t ^ t &gt;&gt;&gt; 7, 61 | t) ^ t;
     *   return (t ^ t &gt;&gt;&gt; 14) &gt;&gt;&gt; 0;
     * </pre>
     * Java 的 int 加法与乘法溢出行为等同于 JS 的 {@code |0} 与 {@code Math.imul}。
     */
    private int nextBits() {
        state = state + INCREMENT;
        int t = (state ^ (state >>> 15)) * (state | 1);
        t = (t + ((t ^ (t >>> 7)) * (t | 61))) ^ t;
        return t ^ (t >>> 14);
    }

    /**
     * [0, bound) 均匀整数，无偏。
     *
     * <p>用拒绝采样而非 {@code nextLong() % bound}：取模在 bound 不整除 2^63 时会让小值概率偏高，
     * 长期累积会扭曲掉落率与暴击率。
     */
    private long nextBounded(long bound) {
        if (bound <= 0L) {
            throw new IllegalArgumentException("bound 必须为正数，实际=" + bound);
        }
        if (bound == 1L) {
            return 0L;
        }
        long limit = Long.MAX_VALUE - (Long.MAX_VALUE % bound);
        long r;
        do {
            r = nextLong() & Long.MAX_VALUE;
        } while (r >= limit);
        return r % bound;
    }

    /** 32 位雪崩混淆，保证相邻 seed / salt 产生完全不同的初始状态。 */
    private static int mix(int x) {
        x ^= (x >>> 16);
        x *= MIX_C1;
        x ^= (x >>> 15);
        x *= MIX_C2;
        x ^= (x >>> 16);
        return x;
    }

    @Override
    public String toString() {
        // 不输出 state 明文：日志里泄漏 state 等于泄漏后续全部随机序列，可被用于预测抽卡结果
        return "Rng(hash=" + Integer.toHexString(mix(state)) + ")";
    }
}
