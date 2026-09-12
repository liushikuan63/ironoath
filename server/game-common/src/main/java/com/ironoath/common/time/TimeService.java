package com.ironoath.common.time;

import java.time.Clock;
import java.util.function.LongSupplier;

/**
 * 职责：服务端时间戳与客户端时间校准（B01 输入输出契约）。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p>铁律 5「时间基于服务端时间戳」：客户端不得用本地 Date 做产出结算。本类是服务端<b>唯一</b>
 * 允许读取系统时钟的地方 —— 其他任何模块需要「现在几点」都必须由调用方把时间戳作为参数传进来，
 * 这也是 game-core / game-battle 能脱离容器批量跑单测的前提（C00 公理四·五）。
 *
 * <p>时间源通过 {@link LongSupplier} 注入，测试可完全控制时间流逝，不需要 sleep。
 */
public final class TimeService {

    /**
     * 异常偏移阈值的兜底默认值：10 年。
     * 生产环境由 game-web 从 contract/config/global.json 的 TIME_SYNC_MAX_SKEW_MS 注入（铁律 1：数值零硬编码）；
     * 此处保留默认值只是为了让纯 Java 层单测不必构造完整配置表。
     */
    public static final long DEFAULT_SUSPICIOUS_SKEW_MS = 10L * 365L * 24L * 3600L * 1000L;

    private static final System.Logger LOG = System.getLogger(TimeService.class.getName());

    private final LongSupplier millisSupplier;

    /** 超过该值的偏移视为客户端时钟异常（时区错乱或被篡改），仅告警，不影响服务端结算。 */
    private final long suspiciousSkewMs;

    /**
     * @param millisSupplier 毫秒时间戳来源。生产用 {@link #system()}，测试传入可控的时间持有器。
     */
    public TimeService(LongSupplier millisSupplier) {
        this(millisSupplier, DEFAULT_SUSPICIOUS_SKEW_MS);
    }

    /**
     * @param millisSupplier   毫秒时间戳来源
     * @param suspiciousSkewMs 异常偏移告警阈值，来源：global.json 的 TIME_SYNC_MAX_SKEW_MS
     */
    public TimeService(LongSupplier millisSupplier, long suspiciousSkewMs) {
        if (millisSupplier == null) {
            throw new IllegalArgumentException("TimeService 的时间源不得为 null");
        }
        if (suspiciousSkewMs <= 0L) {
            throw new IllegalArgumentException("suspiciousSkewMs 必须为正数，实际=" + suspiciousSkewMs);
        }
        this.millisSupplier = millisSupplier;
        this.suspiciousSkewMs = suspiciousSkewMs;
    }

    /** 生产实例，读系统 UTC 时钟，异常阈值取兜底默认值。 */
    public static TimeService system() {
        Clock clock = Clock.systemUTC();
        return new TimeService(clock::millis);
    }

    /** 服务端当前时间（毫秒）。所有结算、行军到期、惰性产出的唯一时间基准。 */
    public long serverNow() {
        return millisSupplier.getAsLong();
    }

    /**
     * 客户端上报本地时间，服务端返回校准结果。
     *
     * <p>offset = serverNow - clientNow。客户端拿到后<b>不能直接使用</b>：单次采样含网络单程延迟，
     * 必须由 {@link TimeOffsetTracker} 做加权移动平均并剔除抖动极值（B01 契约说明）。
     *
     * <p>服务端不信任也不校验 clientTime 的正确性 —— 客户端时间只用于算偏移给客户端自己显示，
     * 任何结算都以 serverNow() 为准，因此伪造 clientTime 无法带来任何收益。
     */
    public TimeSync calibrate(long clientTime) {
        long now = serverNow();
        long offset = now - clientTime;
        if (Math.abs(offset) > suspiciousSkewMs) {
            LOG.log(System.Logger.Level.WARNING,
                    "时间校准偏移异常，疑似客户端时钟被篡改或时区错误。offset={0}ms, clientTime={1}, serverNow={2}",
                    offset, clientTime, now);
        }
        return new TimeSync(offset, now);
    }

    /** 把客户端本地时间换算为服务端时间基准。offset 来自 {@link TimeSync#offset()}。 */
    public static long toServerTime(long clientNow, long offset) {
        return clientNow + offset;
    }

    /**
     * 时间校准结果。
     *
     * @param offset serverNow - clientNow，客户端本地时间加上该值即得服务端时间
     * @param syncAt 服务端发出本次校准的时间戳
     */
    public record TimeSync(long offset, long syncAt) {
    }
}
