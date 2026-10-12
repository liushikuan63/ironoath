package com.ironoath.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BusinessDayTest {
    private static long at(String value) { return Instant.parse(value).toEpochMilli(); }

    @Test
    void midnightDoesNotResetDailyRewards() {
        assertThat(DayKey.of(at("2026-10-12T15:59:59.999Z"))).isEqualTo("20261012");
        assertThat(DayKey.of(at("2026-10-12T16:00:00Z"))).isEqualTo("20261012");
    }

    @Test
    void oneMillisecondAcrossFourBeijingStartsANewBusinessDay() {
        assertThat(DayKey.of(at("2026-10-12T19:59:59.999Z"))).isEqualTo("20261012");
        assertThat(DayKey.of(at("2026-10-12T20:00:00Z"))).isEqualTo("20261013");
    }

    @Test
    void calendarTimezoneAndExistingNaturalDayMathRemainBeijing() {
        assertThat(DayKey.CALENDAR_ZONE).isEqualTo(ZoneOffset.ofHours(8));
        assertThat(DayKey.daysBetween(at("2026-10-12T15:59:59.999Z"),
                at("2026-10-12T16:00:00Z"))).isEqualTo(1);
    }

    @Test
    void consecutiveDaysUseFourOClockRatherThanMidnight() {
        assertThat(DayKey.businessDaysBetween(at("2026-10-12T15:59:59.999Z"),
                at("2026-10-12T16:00:00Z"))).isZero();
        assertThat(DayKey.businessDaysBetween(at("2026-10-12T19:59:59.999Z"),
                at("2026-10-12T20:00:00Z"))).isEqualTo(1);
    }

    @Test
    void nextResetCrossesMonthsAndLeapYearsAtFour() {
        assertThat(DayKey.startOfBusinessDayPlusDays(at("2028-02-29T19:59:59.999Z"), 1))
                .isEqualTo(at("2028-02-29T20:00:00Z"));
        assertThat(DayKey.businessDateOf(at("2028-02-29T19:59:59.999Z")).toString())
                .isEqualTo("2028-02-29");
        assertThat(DayKey.businessDateOf(at("2028-02-29T20:00:00Z")).toString())
                .isEqualTo("2028-03-01");
    }
}
