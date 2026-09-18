package com.ironoath.core.policy;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Set;

import com.ironoath.common.time.DayKey;

/**
 * 职责：未成年时长限制的**规则本体**（B15 §合规：非法定节假日 20:00–21:00 之外不得提供服务）。
 * 依赖：无（纯函数 + `DayKey` 的时区口径）。
 *
 * <p><b>为什么规则要单独成类、而不是写在登录服务里</b>：它有明确的边界（19:59 / 20:00 / 20:59 / 21:00 / 节假日），
 * 而这些边界是这条规则的**全部内容** —— 埋在服务里就只能靠端到端用例间接碰，
 * 边界一错（比如把窗口写成闭区间）就是合规事故。
 *
 * <p><b>时区按 UTC+8 判</b>（与 {@link DayKey#CALENDAR_ZONE} 同一个口径）："20 点"在玩家的挂钟上，
 * 不在服务器所在时区上。用服务器本地时区会让这个窗口在海外部署时整体平移。
 *
 * <p><b>节假日"不知道就按平常日"</b>：`holidayDayKeys` 为空（运营还没填）时每天都按平常日判 ——
 * 少放行是投诉、多放行是合规事故，两者不对等。
 */
public final class MinorCurfew {

    private MinorCurfew() {
    }

    /**
     * 一次判定。
     *
     * @param allowed        此刻是否可提供服务
     * @param reason         不可时的原因（**给玩家看的那句话的一半**：服务端只负责说清"为什么"与"什么时候再来"）
     * @param nextAllowedAt  下一次可玩的时刻（毫秒）；可玩时为 0
     */
    public record Verdict(boolean allowed, String reason, long nextAllowedAt) {

        static Verdict allow() {
            return new Verdict(true, null, 0L);
        }
    }

    /**
     * @param nowMs          服务端时刻
     * @param holidayDayKeys 法定节假日（`DayKey` 形状的日期集合）；空集合 = 一个节假日都没有
     * @param startHour      窗口起点（含），UTC+8 的小时
     * @param endHour        窗口终点（不含）
     */
    public static Verdict evaluate(long nowMs, Set<String> holidayDayKeys, long startHour, long endHour) {
        if (startHour < 0L || endHour > 24L || startHour >= endHour) {
            throw new IllegalArgumentException(
                    "窗口非法：需要 0 <= start < end <= 24，实际 start=" + startHour + " end=" + endHour);
        }
        if (holidayDayKeys != null && holidayDayKeys.contains(DayKey.of(nowMs))) {
            return Verdict.allow();
        }
        ZonedDateTime local = Instant.ofEpochMilli(nowMs).atZone(DayKey.CALENDAR_ZONE);
        long hour = local.getHour();
        if (hour >= startHour && hour < endHour) {
            return Verdict.allow();
        }
        // 窗口外：今天还没到就今天，已经过了就明天 —— 给玩家的"什么时候再来"必须具体到时刻
        ZonedDateTime next = hour < startHour ? local : local.plusDays(1L);
        long nextAllowedAt = next.withHour((int) startHour).withMinute(0).withSecond(0).withNano(0)
                .toInstant().toEpochMilli();
        return new Verdict(false,
                "未成年人仅可在非法定节假日 " + startHour + ":00–" + endHour + ":00 之间游玩", nextAllowedAt);
    }
}
