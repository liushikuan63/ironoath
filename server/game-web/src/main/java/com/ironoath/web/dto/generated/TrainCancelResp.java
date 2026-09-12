// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /army/cancel 响应体。refund 是返还的资源（与城建取消同一口径：按比例返还，比例来自配置）。
 */
public record TrainCancelResp(
        String unitId,
        long count,
        List<ResourceAmount> refund,
        long serverNow)
{
}
