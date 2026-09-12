package com.ironoath.common.time;

import java.time.LocalDate;

/**
 * 职责：「第几个月」与「本月起点」的唯一实现（未成年月度付费限额靠它划月界）。
 * 依赖：{@link DayKey}（只为共用同一个日历时区常量）。
 *
 * <p><b>为什么和 {@link DayKey} 同一个时区</b>：限额文案写的是"下月额度刷新"。如果月界用 UTC
 * 而日切用 UTC+8，玩家看到的"本月剩余"会在月初那天对不上自己手机上的月份，
 * 而月度限额是要拿账单申诉的口径 —— 差一天就是客服分不清谁对。
 *
 * <p><b>不用 {@code epoch ÷ 30 天} 近似</b>：那样月界既不对齐日历月也不固定长度，
 * 症状是"月末两天的付费被算进下个月"，而那正好是限额最容易被打穿的时候。
 */
public final class MonthKey {

    private MonthKey() {
    }

    /** 形如 {@code 2026-09}。零填充保证字典序与时间序一致，可以直接当键用。 */
    public static String of(long nowMs) {
        LocalDate date = localDateOf(nowMs);
        return "%d-%02d".formatted(date.getYear(), date.getMonthValue());
    }

    /** 单调递增的月序号（年 × 12 + 月），给"这是不是本月的记录"这类比较用。 */
    public static long number(long nowMs) {
        LocalDate date = localDateOf(nowMs);
        return date.getYear() * 12L + date.getMonthValue();
    }

    /**
     * 本月第一秒的服务端时间戳（按 {@link DayKey#CALENDAR_ZONE} 的自然月）。
     *
     * <p>月度统计的窗口起点。刻意由这里统一算：各处自己 {@code now - 30 天} 的话，
     * 「本月已花」会在不同的人身上给出不同的数，而那是决定能不能扣款的数。
     */
    public static long startMillis(long nowMs) {
        return localDateOf(nowMs).withDayOfMonth(1)
                .atStartOfDay(DayKey.CALENDAR_ZONE)
                .toInstant()
                .toEpochMilli();
    }

    private static LocalDate localDateOf(long nowMs) {
        if (nowMs <= 0L) {
            throw new IllegalArgumentException("nowMs 必须为正的服务端时间戳，实际=" + nowMs);
        }
        return java.time.Instant.ofEpochMilli(nowMs).atZone(DayKey.CALENDAR_ZONE).toLocalDate();
    }
}
