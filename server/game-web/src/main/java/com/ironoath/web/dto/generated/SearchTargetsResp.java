// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /world/searchTargets 响应体。targets 已按 B08 §8 的四项权重排好序。**响应里没有任何距离数值字段**（B08 验收 12）。
 */
public record SearchTargetsResp(
        List<TargetBrief> targets,
        long selfMatchPower,   // 自己的匹配战力（含峰值记忆）。下发它是为了让客户端能解释「为什么这些目标可选」—— 但判定仍然只在服务端做（B08 禁止项：不要在客户端做战力校验）
        long bandLower,
        long bandUpper,
        long serverNow)
{
}
