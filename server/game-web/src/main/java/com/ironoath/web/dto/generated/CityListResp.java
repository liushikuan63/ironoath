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
        List<BuildOptionView> buildOptions,   // 尚未放置的建筑配置；已放置的不会重复出现。
        QueueView queues,
        Map<ResourceType, ResourceStateView> resources,   // 离线结算后的资源快照
        int gridSize,   // 城内网格边长（`gridSize` × `gridSize`）。**为什么必须下发**：客户端要按网格排布内城与判定坐标越界，而网格边长住在 `city_rule.city_rule_grid_size` 这张表里 —— 客户端不抄配置表（数值一律服务端下发），所以这个数只能走协议过来。**不这么做会怎样**：表里的边长一改（6 → 8），服务端按新网格下发坐标（到 7），客户端还按 6×6 判越界 ⇒ 玩家自己的城被说成「坐标非法」，建筑掉进 unplaced。**注意**：这是 `CityListResp` 的必填字段 —— 旧服务端滚动升级期间不带它时，客户端按「没有这个字段」处理（见 `CityPanel` 的回退分支），不许静默当成 6。
        long serverNow)
{
}
