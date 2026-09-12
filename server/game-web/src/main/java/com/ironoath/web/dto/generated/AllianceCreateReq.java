// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /alliance/create 请求体（B10 §二）。消耗 global.ALLIANCE_CREATE_COST_GOLD 金币。
 */
public record AllianceCreateReq(
        String requestId,   // 幂等键。创建会扣金币，没有幂等就等于允许重放刷掉一次扣费
        String name,   // 联盟名
        String tag)   // 联盟标签（1~4 字符，显示在昵称后）
{
}
