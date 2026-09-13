package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：开服天数必须按 UTC+8 自然日推进，而不是按开服时刻满 24 小时推进。
 * 依赖：纯 Java 时间运算，不起 Spring 容器。
 */
class ServerCalendarTest {

    @Test
    @DisplayName("开服当晚仍算第 0 天，跨过 UTC+8 零点立刻进入第 1 天")
    void crossesAtCalendarMidnightInsteadOfOpenAnniversary() {
        long openAt = Instant.parse("2026-09-13T15:30:00Z").toEpochMilli();   // 23:30 +08

        assertThat(ServerCalendar.daysBetweenOpenAndNow(
                openAt, Instant.parse("2026-09-13T15:59:59Z").toEpochMilli()))
                .as("开服日 23:59:59 仍是第 0 天").isZero();
        assertThat(ServerCalendar.daysBetweenOpenAndNow(
                openAt, Instant.parse("2026-09-13T16:00:00Z").toEpochMilli()))
                .as("北京时间零点后就是第 1 天，不等到次日 23:30").isEqualTo(1L);
    }

    @Test
    @DisplayName("跨多个自然日按日期差计算；时间回拨到开服前仍夹为第 0 天")
    void countsCalendarDatesAndClampsBeforeOpen() {
        long openAt = Instant.parse("2026-09-13T15:30:00Z").toEpochMilli();

        assertThat(ServerCalendar.daysBetweenOpenAndNow(
                openAt, Instant.parse("2026-09-15T00:00:00Z").toEpochMilli()))
                .as("开服日 + 两个自然日 = D2").isEqualTo(2L);
        assertThat(ServerCalendar.daysBetweenOpenAndNow(
                openAt, Instant.parse("2026-09-13T15:00:00Z").toEpochMilli()))
                .as("时间源异常回拨也不能返回负数").isZero();
    }
}
