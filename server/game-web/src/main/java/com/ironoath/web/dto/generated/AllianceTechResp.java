// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /alliance/tech 响应体。
 */
public record AllianceTechResp(
        String techId,   // 科技 id
        int level,   // 研究后的等级
        int levelCap,   // 当前联盟等级下的等级上限。**必须下发**：上限随联盟等级变，客户端自己算不出（要查两张表并做乘法）
        long fundCost,   // 本次消耗的联盟资金
        long fund,   // 研究后的联盟资金余额
        long effectValue,   // 研究后的效果值（定点）。全盟生效
        long serverNow)   // 服务端时间戳
{
}
