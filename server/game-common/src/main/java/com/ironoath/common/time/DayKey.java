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
 * <p><b>按 UTC+8（Asia/Shanghai）取日</b>：日切落在本地 0 点，玩家的「今天」就是他所处的自然日。
 * 2026-09-08 裁决：改代码而不是改文档，因为日限次是玩家能直接感知的东西，
 * 而 UTC 的重置时刻对国内玩家意味着「上午八点日常任务突然全重置了」。
 * 偏移必须与 {@code city_rule_daily_reset_hour_utc} 表达的同一个重置时刻一致
 * （换算式：偏移 = (24 - 该字段的 UTC 小时) % 24，当前 16 ⇒ +8）。
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
     * 日历时区：固定 +8 小时（见类注释的裁决记录与 parity 测试）。
     *
     * <p>公开是因为 {@link WeekKey} 必须用<b>同一个</b>时区：日切与周边界分处两个时区时，
     * 周一凌晨的日常会被算进上一周。一条口径不该有两个家。
     */
    public static final ZoneOffset CALENDAR_ZONE = ZoneOffset.ofHours(8);

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
        return dateOf(nowMs)
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
