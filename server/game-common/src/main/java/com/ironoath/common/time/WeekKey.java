package com.ironoath.common.time;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;

/**
 * 职责：「第几周」的唯一实现（国库周税、商店周限购、周常任务都靠它）。
 * 依赖：无（纯 Java）。
 *
 * <p><b>为什么必须只有一处</b>：周口径的坑比日口径更隐蔽 —— 用「epoch ÷ 7 天」算出的周界
 * 落在星期四，而 ISO 周从星期一开始，用 {@code get(WEEK_OF_YEAR)} 又会跨年断裂
 * （2026-W01 与 2025-W53 比大小直接错）。三种写法各自都能让功能"看起来正常"，
 * 而症状是「国库税一周收了两次」或「跨年那一周谁都不收」。
 *
 * <p><b>与 {@link DayKey} 共用同一个时区常量</b>：日切与周边界如果分处两个时区，
 * 「本周一 0 点」和「今天」就会互相矛盾 —— 周一凌晨的任务会被算进上一周。
 * 所以这里刻意引用 {@code DayKey.CALENDAR_ZONE} 而不是自己再选一个偏移。
 *
 * <p><b>两种读法同一个来源</b>：{@link #of} 给字符串键（Redis / 内存 map 的键），
 * {@link #number} 给单调递增的数字键（领域里那些 {@code if (weekKey <= lastWeekKey)} 的幂等判定
 * 需要「比大小」而不是「比相等」）。两个值都由同一个规则推出，所以永远不会算出不同的周。
 */
public final class WeekKey {

    /** ISO 周：星期一起始。跨年时以「包含星期四的那一年」为准，不会出现年初断档。 */
    private static final WeekFields WEEKS = WeekFields.ISO;

    private WeekKey() {
    }

    /**
     * 形如 {@code 2026-W37}。零填充保证字典序与时间序一致，可以直接当 Redis 键排序。
     *
     * @param nowMs 服务端时间戳，由调用方从 {@link TimeService} 取（铁律 5：不用本地时钟）
     */
    public static String of(long nowMs) {
        LocalDate date = localDateOf(nowMs);
        return "%d-W%02d".formatted(date.get(WEEKS.weekBasedYear()), date.get(WEEKS.weekOfWeekBasedYear()));
    }

    /**
     * 单调递增的周序号（ISO 周基准年 × 100 + 周号，如 202637）。
     *
     * <p>给领域层做「本周期还没缴过」这种比较用。刻意不用「epoch ÷ 7 天」：
     * 那会和 {@link #of} 算出不同的周界，两个键在跨年那一周给出不同的答案。
     */
    public static long number(long nowMs) {
        LocalDate date = localDateOf(nowMs);
        return date.get(WEEKS.weekBasedYear()) * 100L + date.get(WEEKS.weekOfWeekBasedYear());
    }

    private static LocalDate localDateOf(long nowMs) {
        if (nowMs <= 0L) {
            throw new IllegalArgumentException("nowMs 必须为正的服务端时间戳，实际=" + nowMs);
        }
        return java.time.Instant.ofEpochMilli(nowMs).atZone(DayKey.CALENDAR_ZONE).toLocalDate();
    }

}
