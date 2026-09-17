package com.ironoath.common.num;

/**
 * 职责：把一个<b>已经合计好的加成率</b>作用到一个数值上，并固定住取整口径（B20 §五④）。
 * 依赖：{@link FixedPoint}（纯 Java，零框架）。
 *
 * <p><b>为什么这一个类存在，而不是各消费点自己写一次乘除</b>：同一条裁决要落在三条时长
 * （建造、训练、行军）与若干绝对值（医院容量、负载）上。各处自己写 {@code × (1 - pct)}，
 * 分歧不会报错，只会表现为<b>"同样 10% 减成，建造少 1 秒、训练多 1 秒"</b>——
 * 而那正是 B20 §一 逐点核对乘区归属要防的形状，也是玩家能在论坛里复述出来的不公平。
 *
 * <p><b>两条取整口径不对称是有意的</b>：
 * <ul>
 *   <li>{@link #shortenSeconds 缩短时长}向上取整（ceil）且<b>下限 1 秒</b>：
 *       裁决原文是"缩短时长统一 ceil（不足 1 秒向上取整，防 0 秒完成）"。
 *       0 秒的队列等于没有队列 —— 玩家可以瞬间连点十级，B02 定下的卡点节奏就没了；</li>
 *   <li>{@link #scaleUp 放大绝对值}按 HALF_UP：容量、负载这类值没有"0 就等于机制消失"的问题，
 *       套 ceil 只会让它系统性偏大一截，而那部分偏差没人能解释。</li>
 * </ul>
 *
 * <p>参数一律是<b>已经加好的总率</b>（定点万分比：2000 = 20%）。多个来源（个人科技、联盟科技、
 * 国家科技）必须在<b>调用本类之前</b>相加，不能各调一次 —— 相乘会让两条线都点满的玩家
 * 悄悄拿到超出设计值的收益（§五④：同类相加，作用于基础值一次）。
 */
public final class Rates {

    /** 缩短后的下限秒数。1 而不是 0：见类注释的"0 秒队列等于没有队列"。 */
    private static final long MIN_SECONDS = 1L;

    private Rates() {
    }

    /**
     * 按总加成率缩短一个秒数，向上取整，下限 1 秒。
     *
     * @param baseSeconds   基础时长（秒），必须为正 —— 0 是调用方算错了，不是"本来就瞬间完成"
     * @param percentFixed  缩短率（定点万分比）。0 或负数原样返回；≥ 100% 时压到下限 1 秒
     * @throws IllegalArgumentException 当 {@code baseSeconds <= 0}
     */
    public static long shortenSeconds(long baseSeconds, long percentFixed) {
        if (baseSeconds <= 0L) {
            throw new IllegalArgumentException("基础时长必须为正秒数，实际=" + baseSeconds);
        }
        if (percentFixed <= 0L) {
            return baseSeconds;
        }
        long kept = FixedPoint.ONE - Math.min(percentFixed, FixedPoint.ONE);
        long left = FixedPoint.mul(FixedPoint.of(baseSeconds), kept);
        return Math.max(MIN_SECONDS, (left + FixedPoint.SCALE - 1L) / FixedPoint.SCALE);
    }

    /**
     * 按总加成率放大一个绝对值（容量、负载这一类），HALF_UP 取整。
     *
     * <p>基数 ≤ 0 时返回 0：加成不该凭空造出一个本来不存在的东西
     * （没建医院就没有容量，满级的容量科技也一样）。
     *
     * @param base         基础值（整数，不是定点）
     * @param percentFixed 增加率（定点万分比）。0 或负数原样返回
     */
    public static long scaleUp(long base, long percentFixed) {
        if (base <= 0L) {
            return 0L;
        }
        if (percentFixed <= 0L) {
            return base;
        }
        return FixedPoint.round(FixedPoint.mul(FixedPoint.of(base), FixedPoint.ONE + percentFixed));
    }
}
