// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /city/cancel 响应体。refund 是实际返还量（B03 §2：取消返还 60%）。
 */
public record CityCancelResp(
        String buildingId,
        List<ResourceAmount> refund)
{
}
