// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 自动续训 / 自动补兵的策略视图（B25 裁决③(a)）。它是一个**有预算的策略**，不是一个布尔开关：玩家关掉游戏去睡觉时，它能自动排的批数是有上限的，用尽即停并把 stopReason 留给玩家读 —— 而不是悄悄把攒下的资源花光。
 */
public record AutoTrainView(
        boolean enabled,   // 玩家是否开着它
        String unitId,   // 续训 / 补回哪个兵种
        long batchCount,   // 每批训多少
        int batchBudget,   // 还允许自动排几批。**这是预算本身，不是已排数**；0 表示已用尽
        long targetCount,   // 补兵模式的目标兵力（把该兵种补回这个数）；0 = 续训模式（不设目标，每批 batchCount，排到预算用尽）
        String stopReason)   // 停下来时的原因（玩家可读，如「预算用完了」「资源不够」）；正在跑或在等队列时为 null
{
}
