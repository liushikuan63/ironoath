// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /city/collect 请求体。收割已到点的升级并结算其离线产出（B03 §2：升级中不产资源，完成后一次性结算）。
 */
public record CityCollectReq(
        String requestId,
        String buildingId)   // 指定收割某个建筑；为空表示收割全部已到点的建筑
{
}
