// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 任务目标类型（B12 §1 的统一 GoalType）。18 个取值分成累加型与状态型两类 —— 前者进度只增不减（累计训练 20 个兵），后者进度是当前状态、可升可降（当前持有 10000 粮）。分类在服务端的 GoalType 枚举上，由 CI 的枚举一致性守卫保证两边同名同序。LOGIN_DAY / ALLIANCE_DONATE / PVP_WIN 三项是 B17 活动系统补的累加型目标，任务表暂未使用。 PVE_WIN为新增实际PvE胜利累加目标，关卡和野怪只有胜利结算才发布稳定事实。 EXPLORE_CHUNK仅接受已实际合法到达的不同地图块稳定事实，不从派遣预亮雾区或出生点亮推断。
 */
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
    JOIN_RALLY,
    LOGIN_DAY,
    ALLIANCE_DONATE,
    PVP_WIN,
    PVE_WIN,
    EXPLORE_CHUNK
}
