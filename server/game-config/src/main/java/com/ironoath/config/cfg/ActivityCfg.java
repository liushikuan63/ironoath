// 由 tools/config-gen 依据 contract/config/activity.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 activity 的一行。
 * 活动表。B02 字段：活动类型/条件/奖励。durationDays 是开放时长，conditionValue 是达标门槛。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/activity.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record ActivityCfg(
        String id,   // 主键
        String name,
        ActivityType activityType,   // 枚举，取值见 ActivityActivityType
        ConditionType conditionType,   // 枚举，取值见 ActivityConditionType
        long conditionValue,
        long durationDays,
        long rewardGold,
        String rewardItemId,   // 外键，指向 item 表的 id
        long rewardItemCount)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum ActivityType {
        LOGIN_STREAK,
        KILL_MONSTER,
        JOIN_RALLY,
        DONATE,
        PVP_WIN,
        UPGRADE_BUILDING,
        HELP_SQUAD
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum ConditionType {
        LOGIN_DAYS,
        KILL_MONSTER_TOTAL,
        JOIN_RALLY_TOTAL,
        DONATE_TOTAL,
        PVP_WIN_TOTAL,
        UPGRADE_COUNT,
        HELP_COUNT
    }

}
