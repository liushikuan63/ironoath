// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /pay/fund/claim 请求体：领取一档成长基金。
 */
public record FundClaimReq(
        String requestId,   // 幂等键。
        String tierId)   // 要领的档位行 id。**一次只领一档**：一键全领会让「哪一档领了」这件事在失败重发时变得说不清。
{
}
