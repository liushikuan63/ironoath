// 由 tools/config-gen 依据 contract/config/quest.json（表 version=3） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 quest 的一行。
 * 任务表。B02 字段：目标类型/目标值/奖励，四类主/支/日/周常。goalTarget 指向具体配置 id（建筑/兵种/野怪/章节/卡池）；无特定目标的任务（如「打野 3 次」不限定哪只怪）直接省略该字段，而不是填空串——空串是一个「看起来有值但没值」的状态，会让读取方分不清是漏填还是本就不需要。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/quest.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record QuestCfg(
        String id,   // 主键
        String name,
        QuestType questType,   // 枚举，取值见 QuestQuestType
        GoalType goalType,   // 枚举，取值见 QuestGoalType
        String goalTarget,
        long goalValue,
        long rewardGold,
        long rewardWood,
        long rewardIron,
        long rewardGrain,
        long rewardHeroFragment,
        RewardFragmentRarity rewardFragmentRarity,   // 枚举，取值见 QuestRewardFragmentRarity
        String rewardHeroId,   // 外键，指向 hero 表的 id
        String rewardHeroChoices,
        String preQuest)   // 外键，指向 quest 表的 id
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum QuestType {
        MAIN,
        SIDE,
        DAILY,
        WEEKLY,
        ACHIEVEMENT
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum GoalType {
        UPGRADE_BUILDING,
        REACH_RESOURCE,
        TRAIN_UNIT,
        KILL_MONSTER,
        GACHA_PULL,
        JOIN_SQUAD,
        JOIN_ALLIANCE,
        RESEARCH_TECH,
        CLEAR_CHAPTER,
        CLEAR_STAGE,
        GATHER_RESOURCE,
        HELP_SQUAD,
        JOIN_RALLY
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum RewardFragmentRarity {
        N,
        R,
        SR,
        SSR
    }

}
