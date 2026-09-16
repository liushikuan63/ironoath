package com.ironoath.core.activity;

/**
 * 职责：活动窗口的计算 —— 给定锚点与时长，算出「此刻在第几轮、这一轮的起止」。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p><b>两块锚点由 B17 §五① 裁决，这里只实现算法、不决定用哪块</b>：
 * <ul>
 *   <li>六类全服活动：锚点 = 开服时刻，第 n 轮 = {@code [开服 + n×时长, 开服 + (n+1)×时长)}，
 *       一轮结束<b>自动开下一轮</b>（8 行长期轮换，不做一次性活动）。</li>
 *   <li>LOGIN_STREAK 两行：锚点 = 该玩家<b>首次登录时刻</b>。
 *       理由（裁决原文）：全服锚会让"开服第 5 天才进来的新号"永远做不满连续 7 天，
 *       而这两行是零氪链路的一部分。</li>
 * </ul>
 * 哪一行用哪块锚由 {@link ActivityType#playerAnchored()} 回答 —— 算法本身对锚点的来源一无所知。
 *
 * <p><b>时长按自然日 × 24 小时换算，而不是"日切"</b>：窗口是运营时长（"限时 3 天"从开服那一刻起算），
 * 与 {@code DayKey} 的自然日边界不是同一件事。签到那两行的"连续 N 天"用 {@code DayKey} 判断跨天
 * （见 {@link ActivityProgress}），窗口只用这一段算术 —— 两者混用会让"限时 7 天"在第一天的 0 点就过期。
 */
public final class ActivityWindow {

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private ActivityWindow() {
    }

    /**
     * 一轮窗口。
     *
     * @param startAt 本轮开始时刻（毫秒）
     * @param endAt   本轮结束时刻；<b>null 表示没有终点</b>（时长 ≤ 0 的常驻活动）。
     *                编一个天文数字当终点会让"还有多少天"算出一个荒谬的值，而 null 有明确的语义
     */
    public record Span(long startAt, Long endAt) {

        public Span {
            if (startAt <= 0L) {
                throw new IllegalArgumentException("窗口开始时刻必须为正的服务端时间戳，实际=" + startAt);
            }
            if (endAt != null && endAt <= startAt) {
                throw new IllegalArgumentException("窗口结束时刻必须晚于开始时刻：start=" + startAt + " end=" + endAt);
            }
        }

        /** 此刻是否还在窗口内（没有终点的窗口永远在窗口内）。 */
        public boolean contains(long nowMs) {
            return endAt == null || nowMs < endAt;
        }

        /** 此刻是否已过期。<b>与 {@link #contains} 互为反面，只留一个判定</b>。 */
        public boolean expired(long nowMs) {
            return !contains(nowMs);
        }

        /** 下一轮的起点（没有终点的窗口没有下一轮）。 */
        public Long nextStart() {
            return endAt;
        }
    }

    /**
     * 算出 {@code nowMs} 落在哪一轮。
     *
     * @param anchorMs    锚点：全服活动给开服时刻，LOGIN_STREAK 给玩家首次登录时刻
     * @param durationDays 时长（天）。<b>≤ 0 表示常驻</b>：窗口从锚点开始、没有终点
     * @param nowMs       服务端当前时刻（铁律 5）
     */
    public static Span current(long anchorMs, long durationDays, long nowMs) {
        if (anchorMs <= 0L) {
            throw new IllegalArgumentException("窗口锚点必须为正的服务端时间戳，实际=" + anchorMs);
        }
        if (nowMs <= 0L) {
            throw new IllegalArgumentException("nowMs 必须为正的服务端时间戳，实际=" + nowMs);
        }
        if (durationDays <= 0L) {
            return new Span(anchorMs, null);
        }
        long durationMs = durationDays * DAY_MS;
        // 锚点在将来（还没开服 / 玩家首登时刻被写成了一个未来的值）：按第 0 轮处理而不是抛错 ——
        // 这一刻"活动还没开始"是真实状态，而抛错会让读取端点整条挂掉
        long elapsed = nowMs - anchorMs;
        long round = elapsed <= 0L ? 0L : elapsed / durationMs;
        long start = anchorMs + round * durationMs;
        return new Span(start, start + durationMs);
    }
}
