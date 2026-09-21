// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /city/pause 请求体（B03 §2：队列中可暂停 / 取消）。暂停**不返还资源**：取消才返还 60%，两件事不要混。
 */
public record CityPauseReq(
        String requestId,
        String buildingId)
{
}
