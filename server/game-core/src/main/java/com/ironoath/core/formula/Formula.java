package com.ironoath.core.formula;

import com.ironoath.common.config.CurveParams;
import com.ironoath.common.config.CurveSource;
import com.ironoath.common.config.CurveUnit;
import com.ironoath.common.num.FixedPoint;

import java.util.HashMap;
import java.util.Map;

/**
 * 职责：成长曲线求值 —— 全项目唯一的曲线计算入口（C00 公理三：所有曲线走 Formula）。
 * 依赖：game-common 的 CurveSource / CurveParams / FixedPoint（纯 Java，零框架）。
 *
 * <p>跨语言一致性策略第 4 条：曲线以「基数 + 系数 + 指数」表达，双端只读配置套同一条简单公式。
 * 客户端 {@code core/Formula.ts} 是本类的镜像实现，但<b>只用于表现层预测</b>，
 * 任何进入结算的数值一律由服务端算完下发（铁律 3）。
 *
 * <p>两条公式（与 contract/config/curve.json 的 kind 对应）：
 * <pre>
 *   GEOMETRIC: value(n) = base × ratio^(n-1)     建筑时间/消耗、科技时间、兵种强度、关卡难度
 *   POWER:     value(n) = base × n^exponent       建筑产出、战力贡献、武将成长
 * </pre>
 *
 * <p>全程定点 long 运算，无 double（铁律 5）。分数指数走 {@code FixedPoint.pow}
 * 的 BigDecimal exp/ln 级数，跨平台逐位一致。
 *
 * <p>线程模型：结果缓存是惰性的 HashMap，<b>非线程安全</b>。每个使用方持有独立实例，
 * 或由上层做一次性预热后只读共享。这是刻意的取舍 —— 加锁会让战斗内每次曲线求值都付同步成本，
 * 而 Formula 实例的创建成本近乎为零。
 */
public final class Formula {

    /**
     * 等级缓存的上界。
     *
     * <p>这是基础设施容量参数而非游戏数值（不受铁律 1 约束）：超过该等级的求值仍然正确，
     * 只是不进缓存。取值 200 的依据是：全项目最高等级建筑为 30 级、武将 100 级、科技 60 级，
     * 200 留足余量且单条曲线的缓存开销仅 200×8B = 1.6KB。
     */
    static final int MAX_CACHED_LEVEL = 200;

    private final CurveSource curves;

    /** 缓存键 = curveId + '@' + baseFixed，值 = 按等级 1..n 索引的结果数组（下标 0 空置）。 */
    private final Map<String, long[]> cache = new HashMap<>();

    /** 「尚未计算」的哨兵值。用 Long.MIN_VALUE 而非 0：衰减型曲线（ratio &lt; 1）高等级的真实结果可能就是 0。 */
    private static final long NOT_COMPUTED = Long.MIN_VALUE;

    public Formula(CurveSource curves) {
        if (curves == null) {
            throw new IllegalArgumentException("Formula 的 CurveSource 不得为 null");
        }
        this.curves = curves;
    }

    /**
     * 用曲线自带的基数求值。
     *
     * @param curveId 曲线 id，如 BUILDING_TIME
     * @param level   等级，从 1 开始
     * @throws IllegalArgumentException 当 level &lt; 1，或该曲线的基数为 0（表示基数应由业务表提供）
     */
    public long evaluate(String curveId, int level) {
        CurveParams params = curves.curve(curveId);
        if (params.baseProvidedExternally()) {
            throw new IllegalArgumentException("曲线[" + curveId
                    + "]的基数由业务表逐行提供，请调用 evaluate(curveId, baseFixed, level)");
        }
        return evaluate(params, params.baseFixed(), level);
    }

    /**
     * 用调用方提供的基数求值（每种建筑有自己的 C0 / P0 / F0 时用这个重载）。
     *
     * @param curveId   曲线 id
     * @param baseFixed 基数（定点），必须为正
     * @param level     等级，从 1 开始
     */
    public long evaluate(String curveId, long baseFixed, int level) {
        CurveParams params = curves.curve(curveId);
        if (baseFixed <= 0L) {
            throw new IllegalArgumentException(
                    "基数必须为正定点数，curveId=" + curveId + ", baseFixed=" + baseFixed);
        }
        return evaluate(params, baseFixed, level);
    }

    /**
     * 求时间类曲线的结果，返回<b>秒</b>（已从定点落地为整数）。
     *
     * @throws IllegalStateException    当该曲线的量纲不是 SECOND —— 防止把产量当时间用
     * @throws IllegalArgumentException 当该曲线的基数为 0（表示基数应由业务表提供）
     */
    public long evaluateSeconds(String curveId, int level) {
        CurveParams params = curves.curve(curveId);
        requireSecondUnit(params);
        if (params.baseProvidedExternally()) {
            throw new IllegalArgumentException("曲线[" + curveId
                    + "]的基数由业务表逐行提供，请调用 evaluateSeconds(curveId, baseFixed, level)");
        }
        return FixedPoint.round(evaluate(params, params.baseFixed(), level));
    }

    /** 求时间类曲线的结果（秒），基数由业务表提供。 */
    public long evaluateSeconds(String curveId, long baseFixed, int level) {
        CurveParams params = curves.curve(curveId);
        requireSecondUnit(params);
        return FixedPoint.round(evaluate(params, baseFixed, level));
    }

    private static void requireSecondUnit(CurveParams params) {
        if (params.unit() != CurveUnit.SECOND) {
            throw new IllegalStateException("曲线[" + params.id() + "]的量纲是 " + params.unit()
                    + "，不是 SECOND，不能用 evaluateSeconds 读取");
        }
    }

    /** 取曲线参数，供上层做量纲判断或日志输出。 */
    public CurveParams params(String curveId) {
        return curves.curve(curveId);
    }

    /** 清空缓存。配置热更换掉 CurveSource 后必须调用，否则会读到旧曲线的结果。 */
    public void invalidateCache() {
        cache.clear();
    }

    public int cacheSize() {
        return cache.size();
    }

    // ---------- 内部 ----------

    private long evaluate(CurveParams params, long baseFixed, int level) {
        if (level < 1) {
            throw new IllegalArgumentException(
                    "等级必须 >= 1，curveId=" + params.id() + ", level=" + level);
        }
        if (level > MAX_CACHED_LEVEL) {
            return computeRaw(params, baseFixed, level);
        }
        String key = params.id() + "@" + baseFixed;
        long[] table = cache.computeIfAbsent(key, k -> {
            long[] fresh = new long[MAX_CACHED_LEVEL + 1];
            java.util.Arrays.fill(fresh, NOT_COMPUTED);
            return fresh;
        });
        if (table[level] == NOT_COMPUTED) {
            table[level] = computeRaw(params, baseFixed, level);
        }
        return table[level];
    }

    private long computeRaw(CurveParams params, long baseFixed, int level) {
        return switch (params.kind()) {
            case GEOMETRIC -> {
                if (params.ratioFixed() <= 0L) {
                    throw new IllegalStateException("GEOMETRIC 曲线[" + params.id()
                            + "]的 ratio 必须为正，实际=" + FixedPoint.format(params.ratioFixed()));
                }
                // base × ratio^(level-1)：全程 BigDecimal，只在落地定点时舍入一次
                yield FixedPoint.geometric(baseFixed, params.ratioFixed(), level - 1);
            }
            case POWER -> {
                if (params.exponentFixed() <= 0L) {
                    throw new IllegalStateException("POWER 曲线[" + params.id()
                            + "]的 exponent 必须为正，实际=" + FixedPoint.format(params.exponentFixed()));
                }
                // base × level^exponent
                yield FixedPoint.powerLaw(baseFixed, level, params.exponentFixed());
            }
        };
    }

    // ---------- B02 契约：静态公式层 ----------
    //
    // 这一层是「纯公式」：调用方已经把基数与系数从配置表读出来了，这里只做运算。
    // 上面的实例方法是「配置驱动层」：只给 curveId，自己去 CurveSource 取系数。
    // 两层分开是为了让批量平衡验证（跑万局调数值）不必构造 CurveSource。

    /**
     * 建筑时间：T(n) = base × ratio^(n-1)，返回定点秒。
     *
     * @param base       T0，1 级耗时（定点）
     * @param level      等级，从 1 开始
     * @param ratioFixed 比率（定点），来自 curve 表 BUILDING_TIME.ratio
     */
    public static long buildingTime(long base, int level, long ratioFixed) {
        return geometric(base, level, ratioFixed);
    }

    /** 建筑消耗：C(n) = base × ratio^(n-1)，比率来自 curve 表 BUILDING_COST.ratio。 */
    public static long buildingCost(long base, int level, long ratioFixed) {
        return geometric(base, level, ratioFixed);
    }

    /** 科技时间：TR(n) = base × ratio^(n-1)，比率来自 curve 表 TECH_TIME.ratio。 */
    public static long techTime(long base, int level, long ratioFixed) {
        return geometric(base, level, ratioFixed);
    }

    /** 兵种强度：S(n) = base × ratio^(n-1)，比率来自 curve 表 UNIT_STRENGTH.ratio。 */
    public static long unitStrength(long base, int level, long ratioFixed) {
        return geometric(base, level, ratioFixed);
    }

    /** 关卡难度：D(n) = base × ratio^(n-1)，比率来自 curve 表 CHAPTER_DIFFICULTY.ratio。 */
    public static long stageDifficulty(long base, int level, long ratioFixed) {
        return geometric(base, level, ratioFixed);
    }

    /**
     * 建筑产出：P(n) = base × n^exponent，指数来自 curve 表 BUILDING_OUTPUT.exponent。
     *
     * <p><b>对 B02 文档签名的有意偏离</b>：文档写的是 {@code buildingOutput(long base, int level)}
     * 两参形式，但那与文档自己的硬约束「指数从配置表读取，不写在代码里」冲突 ——
     * 静态方法拿不到配置，两参形式只能把 1.08 写死在方法体里。
     * 因此统一多收一个定点指数参数，由调用方从 curve 表读出后传入。
     */
    public static long buildingOutput(long base, int level, long exponentFixed) {
        return powerLaw(base, level, exponentFixed);
    }

    /** 战力贡献：F(n) = base × n^exponent，指数来自 curve 表 POWER_CONTRIB.exponent。签名偏离理由同 {@link #buildingOutput}。 */
    public static long powerContribution(long base, int level, long exponentFixed) {
        return powerLaw(base, level, exponentFixed);
    }

    /** 武将成长：H(n) = base × n^exponent，指数来自 curve 表 HERO_GROWTH.exponent。签名偏离理由同 {@link #buildingOutput}。 */
    public static long heroGrowth(long base, int level, long exponentFixed) {
        return powerLaw(base, level, exponentFixed);
    }

    private static long geometric(long base, int level, long ratioFixed) {
        requireLevel(level);
        return FixedPoint.geometric(base, ratioFixed, level - 1);
    }

    private static long powerLaw(long base, int level, long exponentFixed) {
        requireLevel(level);
        return FixedPoint.powerLaw(base, level, exponentFixed);
    }

    private static void requireLevel(int level) {
        if (level < 1) {
            throw new IllegalArgumentException("等级必须 >= 1，实际=" + level);
        }
    }

    /** 便于日志与排查。 */
    @Override
    public String toString() {
        return "Formula(curves=" + curves.getClass().getSimpleName() + ", cached=" + cache.size() + ")";
    }
}
