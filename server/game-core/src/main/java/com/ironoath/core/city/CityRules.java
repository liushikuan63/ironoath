package com.ironoath.core.city;

/**
 * 职责：城建规则参数（由调用方从 contract/config/city_rule.json 解析后传入）。
 * 依赖：无（纯数据）。
 *
 * <p>与战斗内核同一套约定：game-core 不读配置表，所有数值由外层解析好传进来。
 * 于是城建逻辑可以脱离容器跑单测 —— 想验证「取消返还 60%」不需要启动 Spring、不需要连库。
 *
 * @param gridSize                城内网格边长（6 ⇒ 6×6）。来源 city_rule_grid_size
 * @param wallEdgeOnly            城墙是否只能建在边缘格。来源 city_rule_wall_edge_only
 * @param centerIsMainCity        中心格是否固定为主城。来源 city_rule_center_is_main_city
 * @param baseQueueCount          基础建造队列数。来源 city_rule_base_queue_count
 * @param maxQueueCount           队列上限（含特权）。来源 city_rule_max_queue_count
 * @param newbieFreeQueueCount    新手保护期内的队列数。来源 city_rule_newbie_free_queue_count
 * @param cancelRefundFixed       取消返还比例（定点 0.60 ⇒ 6000）。来源 city_rule_cancel_refund_ratio
 * @param helpPerPersonFixed      <b>已作废</b>（2026-09-11 裁决：互助加速以社交侧为权威）。
 *                                保留字段只为不动既有装配；每次帮助减多少比例、单目标上限多少，
 *                                都由 {@code global.ALLIANCE_HELP_SPEED_BONUS / HELP_SPEEDUP_TOTAL_CAP}
 *                                经 {@code HelpLedger} 说了算，本字段没有任何读取点
 * @param helpCapFixed            <b>已作废</b>，同 {@code helpPerPersonFixed}：city_rule 里那两行
 *                                保留只为兼容装配，不再参与任何判定（见收口清单 #81/#82）
 * @param adSpeedupDailyLimit     广告加速每日次数上限。来源 city_rule_ad_speedup_daily_limit
 * @param adSpeedupSeconds        单次广告加速秒数。来源 city_rule_ad_speedup_seconds
 * @param moveCooldownSeconds     建筑换位冷却秒数。来源 city_rule_move_cooldown_seconds
 */
public record CityRules(
        int gridSize,
        boolean wallEdgeOnly,
        boolean centerIsMainCity,
        int baseQueueCount,
        int maxQueueCount,
        int newbieFreeQueueCount,
        long cancelRefundFixed,
        long helpPerPersonFixed,
        long helpCapFixed,
        long adSpeedupDailyLimit,
        long adSpeedupSeconds,
        long moveCooldownSeconds) {

    public CityRules {
        if (gridSize < 3) {
            throw new IllegalArgumentException("gridSize 至少为 3（要容纳中心主城与一圈边缘），实际=" + gridSize);
        }
        if (baseQueueCount < 1) {
            throw new IllegalArgumentException("baseQueueCount 必须 >= 1，否则玩家永远无法建造，实际=" + baseQueueCount);
        }
        if (maxQueueCount < baseQueueCount) {
            throw new IllegalArgumentException("maxQueueCount 不得小于 baseQueueCount：max="
                    + maxQueueCount + ", base=" + baseQueueCount);
        }
        if (newbieFreeQueueCount < baseQueueCount) {
            // B03 禁止项：不要让玩家在新手期内被建造队列卡死。新手队列少于基础队列等于反向惩罚新手
            throw new IllegalArgumentException("newbieFreeQueueCount 不得小于 baseQueueCount（新手期不能比平时更难过）：newbie="
                    + newbieFreeQueueCount + ", base=" + baseQueueCount);
        }
        if (newbieFreeQueueCount > maxQueueCount) {
            throw new IllegalArgumentException("newbieFreeQueueCount 不得超过 maxQueueCount");
        }
        requireRatio(cancelRefundFixed, "cancelRefundFixed");
        requireRatio(helpPerPersonFixed, "helpPerPersonFixed");
        requireRatio(helpCapFixed, "helpCapFixed");
        if (adSpeedupDailyLimit < 0L || adSpeedupSeconds < 0L || moveCooldownSeconds < 0L) {
            throw new IllegalArgumentException("次数与时长类参数不得为负");
        }
    }

    private static void requireRatio(long fixed, String field) {
        if (fixed < 0L || fixed > com.ironoath.common.num.FixedPoint.SCALE) {
            throw new IllegalArgumentException(field + " 必须落在 [0, 1.0] 的定点区间，实际=" + fixed);
        }
    }

    /**
     * 当前可用的队列数。
     *
     * @param inNewbieProtect 是否处于新手保护期
     * @param extraQueues     已通过特权/道具开启的额外队列数
     */
    public int availableQueues(boolean inNewbieProtect, int extraQueues) {
        if (extraQueues < 0) {
            throw new IllegalArgumentException("extraQueues 不得为负：" + extraQueues);
        }
        int base = inNewbieProtect ? Math.max(baseQueueCount, newbieFreeQueueCount) : baseQueueCount;
        return Math.min(maxQueueCount, base + extraQueues);
    }
}
