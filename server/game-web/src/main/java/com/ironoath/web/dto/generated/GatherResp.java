// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /world/collectGather 响应体。结束采集并让队伍返程，collected 是本次采到的资源。
 */
public record GatherResp(
        List<CollectedEntry> collected,   // 采到的资源。受负载上限约束（loadCap = Σ兵数 × 该兵 load）
        long returnArriveAt,
        MarchView march,
        long serverNow)
{
}
