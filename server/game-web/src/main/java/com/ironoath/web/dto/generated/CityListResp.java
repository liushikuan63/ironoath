// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;
import java.util.Map;

/**
 * GET /city/list 响应体 —— 含离线结算后的资源与到点收割后的建筑状态（B03 §2）。
 */
public record CityListResp(
        List<BuildingView> buildings,
        QueueView queues,
        Map<ResourceType, ResourceStateView> resources,   // 离线结算后的资源快照
        long serverNow)
{
}
