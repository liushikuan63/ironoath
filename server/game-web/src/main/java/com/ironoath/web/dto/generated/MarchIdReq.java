// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 只带 marchId 的请求（召回、领取采集、查看单支行军）。
 */
public record MarchIdReq(
        String requestId,
        String marchId)
{
}
