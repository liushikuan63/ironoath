// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 任务类型，决定重置周期（不决定奖励，奖励在 quest 表里）。取值与 game-core 的 QuestProgress.QuestType 逐一对应，由 NationPayEnumParityTest 一类的枚举守卫钉住。
 */
public enum QuestType {
    MAIN,
    SIDE,
    DAILY,
    WEEKLY
}
