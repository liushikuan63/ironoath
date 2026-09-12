// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /world/scout 请求体。侦查走的是行军系统（action=SCOUT），所以也需要派兵。
 */
public record ScoutReq(
        String requestId,
        int toX,
        int toY,
        List<MarchUnit> units)
{
}
