// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /city/speedUp 请求体。免费与付费共用（B03 §3），source 决定校验与埋点路径。
 */
public record SpeedUpReq(
        String requestId,
        String buildingId,
        SpeedUpSource source,
        String itemId)   // source=ITEM 时必填，指向 item.json 的行 id
{
}
