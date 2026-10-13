package com.ironoath.web.activity;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import com.ironoath.config.cfg.ActivityCfg;

class ActivityScheduleTest {
    private static long at(String date) { return OffsetDateTime.parse(date+"+08:00").toInstant().toEpochMilli(); }
    private ActivityCfg row(String suffix) { return ActivityCampaignBudgetTest.configs().get(ActivityCfg.class,"activity_theme_"+suffix); }
    private void span(String id,String now,String start,String end) {
        var row=row(id); var span=ActivitySchedule.current(row,at("2026-10-13T08:00:00"),at(now));
        assertThat(span.startAt()).isEqualTo(at(start)); assertThat(span.endAt()).isEqualTo(at(end));
    }
    @Test void allFourWindowsUse04BoundaryAndDoNotRepeatEveryDuration() {
        span("major","2026-11-01T03:59:59","2026-11-01T04:00:00","2026-11-15T04:00:00");
        span("major","2026-11-15T03:59:59","2026-11-01T04:00:00","2026-11-15T04:00:00");
        span("major","2026-11-15T04:00:00","2027-01-01T04:00:00","2027-01-15T04:00:00");
        span("first_minor","2026-11-16T04:00:00","2026-11-16T04:00:00","2026-11-23T04:00:00");
        span("second_early","2026-12-01T04:00:00","2026-12-01T04:00:00","2026-12-08T04:00:00");
        span("second_late","2026-12-16T04:00:00","2026-12-16T04:00:00","2026-12-23T04:00:00");
    }
    @Test void oddLengthAndLeapMonthsDoNotDriftTheTwoMonthCycle() {
        span("second_early","2027-02-28T12:00:00","2027-04-01T04:00:00","2027-04-08T04:00:00");
        span("second_early","2028-02-01T04:00:00","2028-02-01T04:00:00","2028-02-08T04:00:00");
        span("second_late","2028-02-29T03:59:59","2028-04-16T04:00:00","2028-04-23T04:00:00");
    }
    @Test void introducingRowAfterItsStartDoesNotRetroactivelyOpenTheEarlierWindow() {
        var row=row("major"); long introduced=at("2026-11-03T12:00:00");
        var next=ActivitySchedule.current(row,introduced,introduced);
        assertThat(next.startAt()).isEqualTo(at("2027-01-01T04:00:00"));
    }
}
