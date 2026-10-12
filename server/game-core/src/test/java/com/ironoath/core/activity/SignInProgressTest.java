package com.ironoath.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import com.ironoath.common.time.DayKey;
import com.ironoath.core.reward.GoldClaimReceipt;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignInProgressTest {
    private static final List<SignInProgress.Rule> RULES = List.of(
            new SignInProgress.Rule("signin_daily", "每日签到", SignInProgress.Period.DAILY, SignInProgress.Metric.TOTAL_DAYS, 1L, null, 20),
            new SignInProgress.Rule("signin_week_full", "一周全勤", SignInProgress.Period.WEEKLY, SignInProgress.Metric.TOTAL_DAYS, 7L, null, 60),
            new SignInProgress.Rule("signin_week_third", "三日连签", SignInProgress.Period.WEEKLY, SignInProgress.Metric.CONSECUTIVE_DAYS, 3L, null, 20),
            new SignInProgress.Rule("signin_week_seventh", "七日连签", SignInProgress.Period.WEEKLY, SignInProgress.Metric.CONSECUTIVE_DAYS, 7L, null, 40),
            new SignInProgress.Rule("signin_month_full", "月度满勤", SignInProgress.Period.MONTHLY, SignInProgress.Metric.TOTAL_DAYS, null, 3L, 160));
    private static long at(String date) { return LocalDate.parse(date).atTime(4, 0).toInstant(DayKey.CALENDAR_ZONE).toEpochMilli(); }
    private static long accept(Map<String, GoldClaimReceipt> ledger, long now) {
        var q = SignInProgress.quote(now, RULES, ledger);
        for (var award : q.awards()) {
            var c = award.context();
            ledger.put(c.traceId(), new GoldClaimReceipt(c.traceId(), c.source(), c.sourceRef(),
                    award.amount(), award.amount(), now, c.claimFacts(), null));
        }
        return q.gold();
    }
    @Test void fullNaturalWeekGives260AndRetryCannotRepeatAnyComponent() {
        Map<String, GoldClaimReceipt> ledger = new LinkedHashMap<>(); long total = 0;
        for (int d = 1; d <= 7; d++) total += accept(ledger, at("2026-06-0" + d));
        assertThat(total).isEqualTo(260);
        assertThat(accept(ledger, at("2026-06-07"))).isZero();
        assertThat(ledger.values()).filteredOn(r -> r.sourceRef().equals("signin_week_full")).hasSize(1);
        assertThat(SignInProgress.quote(at("2026-06-08"), RULES, ledger).gold()).isEqualTo(20);
    }
    @Test void fullJuneHas1240IncludingNaturalMonthAttendance() {
        Map<String, GoldClaimReceipt> ledger = new LinkedHashMap<>(); long total = 0;
        for (int d = 1; d <= 30; d++) total += accept(ledger, at("2026-06-" + String.format("%02d", d)));
        assertThat(total).isEqualTo(1240);
        assertThat(ledger.values()).filteredOn(r -> r.sourceRef().equals("signin_month_full")).hasSize(1);
        assertThat(accept(ledger, at("2026-06-30") + 1)).isZero();
    }
    @Test void midnightDoesNotResetButMondayFourStartsANewWeek() {
        Map<String, GoldClaimReceipt> ledger = new LinkedHashMap<>();
        accept(ledger, at("2026-06-07"));
        assertThat(SignInProgress.quote(at("2026-06-08") - 1, RULES, ledger).awards()).isEmpty();
        var next = SignInProgress.quote(at("2026-06-08"), RULES, ledger);
        assertThat(next.gold()).isEqualTo(20);
        assertThat(next.rows().stream().filter(r -> r.rule().period() == SignInProgress.Period.WEEKLY))
                .allSatisfy(r -> assertThat(r.progress()).isZero());
    }
    @Test void brokenStreakStillGetsDailyAndLaterThirdOnlyOncePerWeek() {
        Map<String, GoldClaimReceipt> ledger = new LinkedHashMap<>();
        accept(ledger, at("2026-06-01"));
        assertThat(accept(ledger, at("2026-06-03"))).isEqualTo(20);
        assertThat(accept(ledger, at("2026-06-04"))).isEqualTo(20);
        assertThat(accept(ledger, at("2026-06-05"))).isEqualTo(40);
        assertThat(accept(ledger, at("2026-06-07"))).isEqualTo(20);
        assertThat(ledger.values()).filteredOn(r -> r.sourceRef().equals("signin_week_third")).hasSize(1);
        assertThat(ledger.values()).noneSatisfy(r -> assertThat(r.sourceRef()).isEqualTo("signin_week_full"));
    }
    @Test void monthAllowanceCountsDatesNotConsecutiveDays() {
        Map<String, GoldClaimReceipt> ledger = new LinkedHashMap<>();
        for (int d = 1; d <= 30; d++) if (d != 2 && d != 5 && d != 8)
            accept(ledger, at("2026-06-" + String.format("%02d", d)));
        assertThat(ledger.values()).filteredOn(r -> r.sourceRef().equals("signin_month_full")).hasSize(1);
        Map<String, GoldClaimReceipt> fourMisses = new LinkedHashMap<>();
        for (int d = 1; d <= 30; d++) if (d != 2 && d != 5 && d != 8 && d != 11)
            accept(fourMisses, at("2026-06-" + String.format("%02d", d)));
        assertThat(fourMisses.values()).noneSatisfy(r -> assertThat(r.sourceRef()).isEqualTo("signin_month_full"));
    }
    @Test void leapFebruaryAndMonthBoundaryUseTheBusinessCalendar() {
        for (String date : List.of("2026-02-15", "2028-02-15", "2026-06-15", "2026-10-15")) {
            var q = SignInProgress.quote(at(date), RULES, Map.of());
            var month = q.rows().stream().filter(r -> r.rule().period() == SignInProgress.Period.MONTHLY).findFirst().orElseThrow();
            assertThat(month.goal()).isEqualTo(LocalDate.parse(date).lengthOfMonth() - 3);
        }
        var before = SignInProgress.quote(at("2028-03-01") - 1, RULES, Map.of());
        var after = SignInProgress.quote(at("2028-03-01"), RULES, Map.of());
        assertThat(before.rows().get(4).goal()).isEqualTo(26);
        assertThat(after.rows().get(4).goal()).isEqualTo(28);
        assertThat(before.rows().get(4).businessKey()).isNotEqualTo(after.rows().get(4).businessKey());
    }
    @Test void changedConfigurationDoesNotReopenAnAcceptedBonusOrRewriteItsGoal() {
        Map<String, GoldClaimReceipt> ledger = new LinkedHashMap<>();
        for (int d = 1; d <= 3; d++) accept(ledger, at("2026-06-0" + d));
        var changed = RULES.stream().map(rule -> "signin_week_third".equals(rule.id())
                ? new SignInProgress.Rule(rule.id(), rule.name(), rule.period(), rule.metric(), 4L, null, 999)
                : rule).toList();
        var quote = SignInProgress.quote(at("2026-06-04"), changed, ledger);
        var bonus = quote.rows().stream().filter(row -> "signin_week_third".equals(row.rule().id())).findFirst().orElseThrow();
        assertThat(bonus.claimed()).isTrue();
        assertThat(bonus.goal()).isEqualTo(3);
        assertThat(ledger.get(bonus.businessKey()).requested()).isEqualTo(20);
        assertThat(quote.awards()).singleElement().satisfies(award -> assertThat(award.context().sourceRef()).isEqualTo("signin_daily"));
    }

    @Test void aPriorDailyReceiptCanRecoverMissingEarnedBonusWithoutAnotherDailyGold() {
        Map<String, GoldClaimReceipt> ledger = new LinkedHashMap<>();
        for (int d = 1; d <= 7; d++) accept(ledger, at("2026-06-0" + d));
        ledger.entrySet().removeIf(e -> e.getValue().sourceRef().equals("signin_week_full"));
        var q = SignInProgress.quote(at("2026-06-07"), RULES, ledger);
        assertThat(q.signedToday()).isTrue();
        assertThat(q.gold()).isEqualTo(60);
        assertThat(q.awards()).singleElement().satisfies(a -> assertThat(a.context().sourceRef()).isEqualTo("signin_week_full"));
    }
}
