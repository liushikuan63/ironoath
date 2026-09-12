package com.ironoath.common.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 月口径。它守的是「哪些钱算本月已花」，而那是决定未成年账号能不能扣款的数，
 * 所以月界错一天的后果不是数字偏一点，而是把上个月的消费算进这个月（玩家被冤枉）
 * 或反过来（限额被绕过去）。
 */
@DisplayName("B15 月度限额用的月口径：自然月、UTC+8、与日切同一个时区")
class MonthKeyTest {

    /** 2026-09-01T00:00+08:00，即本仓库日历意义上的九月第一刻。 */
    private static final long SEP_FIRST_IN_8 = 1_788_192_000_000L;

    /** 2026-08-01T00:00+08:00。 */
    private static final long AUG_FIRST_IN_8 = 1_785_513_600_000L;

    @Test
    @DisplayName("月界按 UTC+8 而不是 UTC：UTC 还在 8 月 31 日 17:00 的那一刻，日历上已经是 9 月")
    void monthBoundaryFollowsCalendarZone() {
        long utcStillAugust = 1_788_195_601_000L;   // 2026-08-31T17:00:01Z == 09-01T01:00:01+08
        assertThat(MonthKey.of(utcStillAugust)).as("按 UTC 判会算成 2026-08，那与日切口径互相矛盾")
                .isEqualTo("2026-09");
        assertThat(MonthKey.startMillis(utcStillAugust))
                .as("月初那一小时内发生的付费必须算进这个月（限额按自然月刷新）")
                .isEqualTo(SEP_FIRST_IN_8);
    }

    @Test
    @DisplayName("月内任一点都回到同一个起点；跨月那一秒立刻换月")
    void startIsStableWithinMonthAndFlipsAtBoundary() {
        long lastMsOfAugust = SEP_FIRST_IN_8 - 1L;
        assertThat(MonthKey.startMillis(AUG_FIRST_IN_8)).isEqualTo(AUG_FIRST_IN_8);
        assertThat(MonthKey.startMillis(lastMsOfAugust)).isEqualTo(AUG_FIRST_IN_8);
        assertThat(MonthKey.startMillis(SEP_FIRST_IN_8)).isEqualTo(SEP_FIRST_IN_8);
        assertThat(MonthKey.of(lastMsOfAugust)).isEqualTo("2026-08");
    }

    @Test
    @DisplayName("数字键逐月 +1 且跨年不断档，字符串键字典序即时间序（可以直接当存储键）")
    void keysAreMonotonicAcrossYearEnd() {
        long dec2026 = java.time.LocalDate.of(2026, 12, 15)
                .atStartOfDay(DayKey.CALENDAR_ZONE).toInstant().toEpochMilli();
        long jan2027 = java.time.LocalDate.of(2027, 1, 15)
                .atStartOfDay(DayKey.CALENDAR_ZONE).toInstant().toEpochMilli();
        assertThat(MonthKey.number(jan2027) - MonthKey.number(dec2026))
                .as("跨年必须恰好 +1，否则「哪个月刷新额度」会在年初跳一格").isEqualTo(1L);
        assertThat(MonthKey.of(dec2026)).isLessThan(MonthKey.of(jan2027));
    }

    @Test
    @DisplayName("拒绝 0 与负数：那多半是把「没取到时刻」传进来了，静默按 1970 年 1 月算会给出一个像样的答案")
    void rejectsNonPositiveTimestamp() {
        assertThatThrownBy(() -> MonthKey.startMillis(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MonthKey.of(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
