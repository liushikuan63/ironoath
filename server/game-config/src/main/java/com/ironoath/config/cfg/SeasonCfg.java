// 由 tools/config-gen 依据 contract/config/season.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 season 的一行。
 * 赛季表。B02 字段：阶段时间轴/目标/结算奖励。一个赛季 = 5 阶段共 45 天。startDayOffset 是相对开服的天数偏移。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/season.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record SeasonCfg(
        String id,   // 主键
        String name,
        long phaseNo,
        RulePhase rulePhase,   // 枚举，取值见 SeasonRulePhase
        long startDayOffset,
        long durationDays,
        GoalType goalType,   // 枚举，取值见 SeasonGoalType
        long goalValue,
        long rewardGold,
        long rewardSeasonCoin,
        long rewardHeroFragment)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum RulePhase {
        PREPARE,
        EXPAND,
        CAPITAL_WAR,
        SETTLE
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum GoalType {
        REACH_MAIN_LEVEL,
        JOIN_ALLIANCE,
        KILL_MONSTER_TOTAL,
        CAPTURE_TERRITORY,
        SEASON_RANK
    }

}
