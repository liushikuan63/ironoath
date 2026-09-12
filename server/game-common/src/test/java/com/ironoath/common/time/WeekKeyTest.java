package com.ironoath.common.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：周键的三条性质 —— 与日切同时区、周内稳定、跨年不倒挂。
 * 依赖：无（纯 Java，直接构造时间戳）。
 *
 * <p><b>为什么值得单测</b>：周口径的错都是"看起来正常"的错。用 epoch ÷ 7 天算，
 * 周界会落在星期四，于是「本周税收」在一周里被切三次；用 {@code WEEK_OF_YEAR} 不加
 * 基准年，跨年那一周（2026-W53 与 2027-W01）比较大小会直接倒挂。两种都不会报错，
 * 只会让国库要么多收、要么少收。
 */
class WeekKeyTest {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    @Test
    @DisplayName("同一个键在周内稳定，跨过周一 0 点（UTC+8）才变")
    void stableWithinTheWeekAndFlipsAtMondayMidnight() {
        // 2026-09-07 是星期一：UTC+8 00:00 == UTC 2026-09-06T16:00
        long mondayZeroEight = LocalDateTime.of(2026, 9, 7, 0, 0)
                .toInstant(ZoneOffset.ofHours(8)).toEpochMilli();

        assertThat(WeekKey.of(mondayZeroEight)).isEqualTo(WeekKey.of(mondayZeroEight + 6 * DAY));
        assertThat(WeekKey.of(mondayZeroEight - 1)).as("差一毫秒还属于上一周")
                .isNotEqualTo(WeekKey.of(mondayZeroEight));
        assertThat(WeekKey.number(mondayZeroEight - 1))
                .as("数字键必须单调：领域层拿它做 <= 比较")
                .isLessThan(WeekKey.number(mondayZeroEight));
    }

    @Test
    @DisplayName("周键与日键共用同一个时区：周日 17:00(UTC) 已经属于东八区的下一周")
    void sharesTheSameZoneAsDayKey() {
        // 东八区的 2026-09-07 00:00 是周一，对应 UTC 2026-09-06T16:00。
        // 下面两个时刻只差一小时，却横跨了这个边界：如果周界与日切分处两个时区，
        // 它们就会被算进同一周，周一凌晨的任务跟着落到上一周去
        long before = Instant.parse("2026-09-06T15:30:00Z").toEpochMilli();
        long after = Instant.parse("2026-09-06T16:30:00Z").toEpochMilli();

        assertThat(WeekKey.of(after)).isNotEqualTo(WeekKey.of(before));
        assertThat(DayKey.of(after)).isNotEqualTo(DayKey.of(before));
        assertThat(WeekKey.number(after)).isGreaterThan(WeekKey.number(before));
    }

    @Test
    @DisplayName("跨年那一周不能倒挂：年末周键必须小于次年年初周键")
    void yearBoundaryKeepsMonotonic() {
        long endOf2026 = Instant.parse("2026-12-31T00:00:00Z").toEpochMilli();
        long startOf2027 = Instant.parse("2027-01-04T00:00:00Z").toEpochMilli();

        assertThat(WeekKey.number(endOf2026)).isLessThan(WeekKey.number(startOf2027));
    }

    @Test
    @DisplayName("非法时间戳直接炸：静默返回一个键会让所有周期判定同时失效")
    void rejectsNonPositiveTimestamp() {
        assertThatThrownBy(() -> WeekKey.of(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeekKey.number(-5L)).isInstanceOf(IllegalArgumentException.class);
    }
}
