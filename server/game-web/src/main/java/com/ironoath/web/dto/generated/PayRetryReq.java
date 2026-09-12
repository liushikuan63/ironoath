// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /pay/retry 请求体：手工触发一次补单（客服入口用）。
 */
public record PayRetryReq(
        String requestId,   // 幂等键。
        String orderId)   // 要补发的订单号。
{
}
