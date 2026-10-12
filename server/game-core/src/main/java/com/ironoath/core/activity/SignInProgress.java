package com.ironoath.core.activity;

import com.ironoath.common.time.DayKey;
import com.ironoath.core.reward.GoldClaimReceipt;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardPorts.Wallet.GoldRequest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 签到资格只从已接受的服务端金币收据派生，读取不发钱、不要求重登。 */
public final class SignInProgress {
    private static final String DAY_FACT = "signin-day:";
    private static final DateTimeFormatter KEY = DateTimeFormatter.BASIC_ISO_DATE;
    public enum Period { DAILY, WEEKLY, MONTHLY }
    public enum Metric { TOTAL_DAYS, CONSECUTIVE_DAYS }
    public record Rule(String id, String name, Period period, Metric metric,
                       Long targetDays, Long allowedMissDays, long gold) {
        public Rule {
            if (id == null || id.isBlank() || name == null || name.isBlank() || period == null || metric == null || gold <= 0)
                throw new IllegalArgumentException("签到规则必须有标识、名字、周期、指标和正奖励");
            if (period == Period.MONTHLY) {
                if (targetDays != null || allowedMissDays == null || allowedMissDays < 0 || allowedMissDays >= 28)
                    throw new IllegalArgumentException("月满勤必须按自然月日数减允许漏签天数计算");
            } else if (targetDays == null || targetDays <= 0 || allowedMissDays != null) {
                throw new IllegalArgumentException("日/周签到必须有正门槛且不得套月漏签规则");
            }
            if (period == Period.DAILY && (targetDays != 1 || metric != Metric.TOTAL_DAYS))
                throw new IllegalArgumentException("每日签到只能为当天一次");
        }
    }
    public record Row(Rule rule, String businessKey, long progress, long goal,
                      long windowEndAt, boolean claimed, boolean pending) {}
    public record Quote(List<Row> rows, List<GoldRequest> awards, long gold, boolean signedToday) {
        public Quote { rows = List.copyOf(rows); awards = List.copyOf(awards); }
    }

    private SignInProgress() {}

    public static Quote quote(long now, List<Rule> rules, Map<String, GoldClaimReceipt> receipts) {
        if (rules == null || rules.stream().filter(r -> r.period() == Period.DAILY).count() != 1
                || rules.stream().map(Rule::id).distinct().count() != rules.size())
            throw new IllegalArgumentException("签到必须恰有一个每日入口，规则id不得重复");
        Rule daily = rules.stream().filter(r -> r.period() == Period.DAILY).findFirst().orElseThrow();
        LocalDate today = DayKey.businessDateOf(now);
        TreeSet<LocalDate> signed = new TreeSet<>();
        for (var receipt : receipts.values()) {
            if (!"free_gold".equals(receipt.source()) || !daily.id().equals(receipt.sourceRef())) continue;
            for (String fact : receipt.facts()) if (fact.startsWith(DAY_FACT)) {
                LocalDate date = LocalDate.parse(fact.substring(DAY_FACT.length()), KEY);
                if (!date.isAfter(today)) signed.add(date);
            }
        }
        boolean signedToday = signed.contains(today);
        TreeSet<LocalDate> afterSign = new TreeSet<>(signed); afterSign.add(today);
        List<Row> rows = new ArrayList<>(); List<GoldRequest> awards = new ArrayList<>(); long total = 0;
        for (Rule rule : rules) {
            LocalDate start = start(rule.period(), today);
            LocalDate end = switch (rule.period()) {
                case DAILY -> start.plusDays(1);
                case WEEKLY -> start.plusWeeks(1);
                case MONTHLY -> start.plusMonths(1);
            };
            String key = "signin:" + rule.id() + ':' + start.format(KEY);
            var receipt = receipts.get(key);
            long goal = rule.period() == Period.MONTHLY ? today.lengthOfMonth() - rule.allowedMissDays() : rule.targetDays();
            if (receipt != null) {
                for (String fact : receipt.facts()) if (fact.startsWith("signin-goal:"))
                    goal = Long.parseLong(fact.substring("signin-goal:".length()));
            }
            long progress = measure(signed, start, end, rule.metric());
            rows.add(new Row(rule, key, Math.min(progress, goal), goal,
                    end.atTime(DayKey.RESET_HOUR, 0).toInstant(DayKey.CALENDAR_ZONE).toEpochMilli(),
                    receipt != null, receipt != null && receipt.pendingOverflow()));
            if (receipt == null && measure(afterSign, start, end, rule.metric()) >= goal) {
                Set<String> facts = rule.period() == Period.DAILY
                        ? Set.of(DAY_FACT + today.format(KEY), "signin-goal:" + goal) : Set.of("signin-goal:" + goal);
                awards.add(new GoldRequest(rule.gold(), new RewardContext("free_gold", rule.id(), key, true, facts)));
                total = Math.addExact(total, rule.gold());
            }
        }
        return new Quote(rows, awards, total, signedToday);
    }

    private static LocalDate start(Period period, LocalDate today) {
        return switch (period) {
            case DAILY -> today;
            case WEEKLY -> today.minusDays(today.getDayOfWeek().getValue() - 1L);
            case MONTHLY -> today.withDayOfMonth(1);
        };
    }
    private static long measure(TreeSet<LocalDate> signed, LocalDate start, LocalDate end, Metric metric) {
        var days = signed.subSet(start, true, end, false);
        if (metric == Metric.TOTAL_DAYS) return days.size();
        long longest = 0, run = 0; LocalDate last = null;
        for (LocalDate day : days) {
            run = last != null && last.plusDays(1).equals(day) ? run + 1 : 1;
            longest = Math.max(longest, run); last = day;
        }
        return longest;
    }
}
