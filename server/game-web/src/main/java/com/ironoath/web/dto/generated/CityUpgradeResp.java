// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /city/upgrade 响应体。
 */
public record CityUpgradeResp(
        String buildingId,
        int level,   // 升级后的等级（升级中为目标等级）
        long finishAt,   // 完成时刻（服务端毫秒时间戳）
        List<ResourceAmount> cost,   // 本次实际扣除的资源
        long powerDelta)   // 战力变化量，客户端飘字用（B03 §4）
{
}
