package com.ironoath.web.activity;

import com.ironoath.config.cfg.ActivityCfg;
import com.ironoath.core.activity.ActivityWindow;

/** 日历主题窗口；旧按开服锚轮换的活动保持原时长。 */
final class ActivitySchedule {
    private ActivitySchedule() {}
    static void validate(ActivityCfg row) {
        if (row.cycleAnchorAt()==null && row.cycleMonths()==null && row.cycleMonthOffset()==null && row.cycleDay()==null) return;
        if (row.cycleAnchorAt()==null || row.cycleMonths()==null || row.cycleMonthOffset()==null || row.cycleDay()==null
                || row.cycleMonths()<1 || row.cycleMonths()>12 || row.cycleMonthOffset()<0
                || row.cycleMonthOffset()>=row.cycleMonths() || row.cycleDay()<1 || row.cycleDay()>28
                || row.cycleDay()+row.durationDays()>29)
            throw new IllegalArgumentException("主题活动日历须有完整锚点、周期和合法月内窗口");
        var anchor=java.time.Instant.ofEpochMilli(row.cycleAnchorAt()).atZone(com.ironoath.common.time.DayKey.CALENDAR_ZONE);
        if (anchor.getDayOfMonth()!=1 || anchor.getHour()!=4 || anchor.getMinute()!=0 || anchor.getSecond()!=0 || anchor.getNano()!=0)
            throw new IllegalArgumentException("主题活动锚点必须是北京时间每月1日04:00");
        if (row.rewardGold()<=0 || row.rewardItemId()!=null || row.battlePassPoints()!=0
                || row.activityType()!=ActivityCfg.ActivityType.KILL_MONSTER
                || row.conditionType()!=ActivityCfg.ConditionType.KILL_MONSTER_TOTAL)
            throw new IllegalArgumentException("日历主题须是普通独立野怪胜利与纯金币奖励");
    }
    static ActivityWindow.Span current(ActivityCfg row, long earliestStart, long now) {
        validate(row);
        if (row.cycleMonths()==null) return ActivityWindow.current(earliestStart,row.durationDays(),now);
        var zone=com.ironoath.common.time.DayKey.CALENDAR_ZONE;
        var anchor=java.time.Instant.ofEpochMilli(row.cycleAnchorAt()).atZone(zone).toLocalDate();
        var date=com.ironoath.common.time.DayKey.businessDateOf(Math.max(now,earliestStart));
        long months=Math.max(0,java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.from(anchor),java.time.YearMonth.from(date)));
        long cycle=months/row.cycleMonths();
        while (true) {
            var startDate=anchor.plusMonths(Math.addExact(Math.multiplyExact(cycle,row.cycleMonths()),row.cycleMonthOffset()))
                    .withDayOfMonth(row.cycleDay().intValue());
            long start=startDate.atTime(4,0).toInstant(zone).toEpochMilli();
            long end=startDate.plusDays(row.durationDays()).atTime(4,0).toInstant(zone).toEpochMilli();
            if (start>=earliestStart && now<end) return new ActivityWindow.Span(start,end);
            cycle++;
        }
    }
}
