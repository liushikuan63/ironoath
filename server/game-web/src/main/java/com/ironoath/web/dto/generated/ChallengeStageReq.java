// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /stage/challenge 请求体（B09 §三）。
 */
public record ChallengeStageReq(
        String requestId,   // 幂等键。挑战会扣兵、扣体力、发奖励，没有幂等就等于允许重放刷奖励
        String stageId,
        List<StageUnit> units,   // 出战兵力，unitId（含阶级）→ 数量。按 unitId 而不是按兵种：与行军、军队存档同一口径，否则一次挑战会把 T5 兵当 T1 用
        List<String> heroes)   // 上阵武将 id，顺序即站位（0 号主将）。可空
{
}
