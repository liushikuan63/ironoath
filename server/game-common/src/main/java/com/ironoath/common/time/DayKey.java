package com.ironoath.common.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 职责：日限次 / 每日重置用的「日期键」—— 全项目唯一的一处实现。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p><b>为什么必须只有一处</b>：广告加速的日限次、体力购买的日限次、
 * 暴虐值的「单日对同一目标只计一次」都要一个 dayKey。
 * 各自实现一份的后果不是代码重复，而是<b>重置时刻不一致</b>：
 * 一处按 UTC、另一处按本地时区，玩家就会遇到「加速次数重置了、体力购买次数还没重置」，
 * 而这种 bug 只在跨时区部署或夏令时切换时出现，几乎无法复现。
 *
 * <p><b>2026-10-13 裁决：北京时间 04:00 刷新每日内容</b>：凌晨四点之前
 * 属于前一业务日；时区仍是 UTC+8。自然日、赛季日历与旧月卡权益计算保持原口径。
 * {@code city_rule_daily_reset_hour_utc} 声明同一个切点（北京时间 04:00 = UTC 20:00）。
 * game-common 读不到配置表，所以这条一致性由 {@code DailyResetZoneParityTest} 钉住 ——
 * 表说一套、代码做一套比两边都没定更糟，因为它会让运营以为改表就能挪重置时刻（并不能）。
 *
 * <p><b>UTC 那一半理由仍然成立</b>：这里选的是一个<b>固定偏移</b>而不是 {@code ZoneId}，
 * 因为 Asia/Shanghai 没有夏令时、而任意带 DST 的时区都会让一年里出现
 * 「一天 23 小时或 25 小时」。将来出海要按服区分时区时，挪的是这个常量与那条 parity 断言。
 *
 * <p><b>键格式是 {@code yyyyMMdd}</b>（如 20260907），不含分隔符：
 * 它会拼进 Redis 键与内存 map 的键，短一点、且不需要转义。
 */
public final class DayKey {

    /**
     * 日历时区：固定 +8 小时。自然周/月沿用此时区，签到周期使用业务日期。
     */
    public static final ZoneOffset CALENDAR_ZONE = ZoneOffset.ofHours(8);

    /** 每日业务切点，北京时间凌晨四点。 */
    public static final int RESET_HOUR = 4;

    private DayKey() {
    }

    /**
     * 服务端毫秒时间戳 → 日期键。
     *
     * @param nowMs 服务端时间戳，由调用方从 {@link TimeService} 取（铁律 5：不用本地时钟）
     */
    public static String of(long nowMs) {
        if (nowMs <= 0L) {
            throw new IllegalArgumentException("nowMs 必须为正的服务端时间戳，实际=" + nowMs);
        }
        return businessDateOf(nowMs)
                .toString()
                .replace("-", "");
    }

    /**
     * 两个时刻相隔几个自然日（按 {@link #CALENDAR_ZONE} 的日期边界）。
     *
     * <p>这是「开服第 N 天」「赛季第 N 天」共用的时间差口径：不是除以 24 小时，
     * 而是先各自落到自然日，再比较日期。开服发生在晚上时，第二个自然日仍从次日 0 点开始。
     */
    public static long daysBetween(long fromMs, long toMs) {
        return ChronoUnit.DAYS.between(dateOf(fromMs), dateOf(toMs));
    }

    /** 北京时间自然日期键，用于节假日日历与旧月卡权益。 */
    public static String naturalOf(long nowMs) {
        if (nowMs <= 0L) {
            throw new IllegalArgumentException("nowMs 必须为正的服务端时间戳，实际=" + nowMs);
        }
        return dateOf(nowMs).toString().replace("-", "");
    }

    /** 时间戳所在的业务日期；00:00～03:59 属于前一日。 */
    public static LocalDate businessDateOf(long ms) {
        if (ms <= 0L) {
            throw new IllegalArgumentException("ms 必须为正的服务端时间戳，实际=" + ms);
        }
        return Instant.ofEpochMilli(ms).atZone(CALENDAR_ZONE).minusHours(RESET_HOUR).toLocalDate();
    }

    /** 跨过几个北京时间04:00边界，用于签到与连签，不改变自然日计算。 */
    public static long businessDaysBetween(long fromMs, long toMs) {
        return ChronoUnit.DAYS.between(businessDateOf(fromMs), businessDateOf(toMs));
    }

    /** 所在业务日再偏移days天的北京时间04:00。 */
    public static long startOfBusinessDayPlusDays(long fromMs, long days) {
        return businessDateOf(fromMs).plusDays(days).atTime(RESET_HOUR, 0)
                .toInstant(CALENDAR_ZONE).toEpochMilli();
    }

    /**
     * 从某个时刻所在自然日起再偏移 {@code days} 天后的 0 点。
     *
     * <p>赛季阶段结束时点用它：阶段天数表达的是自然日数，不能写成
     * {@code seasonStart + days * 24h}——那会把日界继续绑在开赛钟点上。
     */
    public static long startOfDayPlusDays(long fromMs, long days) {
        return dateOf(fromMs).plusDays(days)
                .atStartOfDay(CALENDAR_ZONE)
                .toInstant()
                .toEpochMilli();
    }

    private static LocalDate dateOf(long ms) {
        return Instant.ofEpochMilli(ms).atZone(CALENDAR_ZONE).toLocalDate();
    }
}
