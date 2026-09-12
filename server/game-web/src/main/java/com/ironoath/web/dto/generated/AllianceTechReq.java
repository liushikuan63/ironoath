// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /alliance/tech 请求体（用联盟资金研究科技）。
 */
public record AllianceTechReq(
        String requestId,   // 幂等键。研究会扣联盟资金（公共资产），重放等于全盟被多扣一次
        String techId,   // alliance_tech 表的行 id
        int levels)   // 一次研究几级。上限由联盟等级决定（alliance_config.techCapBonus）
{
}
