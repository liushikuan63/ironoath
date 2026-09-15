// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一键领取全部。带幂等键：一次点击发两次请求会重复发奖，而重复发奖在本项目里是经济口子（B04 禁止项「不得重复发放」）。**刻意不带 mailId 列表。**
 */
public record MailClaimAllReq(
        String requestId)   // 幂等键，与全仓写接口同一套（`IdempotencyStore`，TTL 取 `global.REQUEST_ID_TTL_SECONDS`）。
{
}
