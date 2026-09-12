// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /city/upgrade 请求体。gridX/gridY 用于新建 placement，已存在的建筑升级时可省略（服务端按 configId 找实例）。
 */
public record CityUpgradeReq(
        String requestId,   // 幂等键。同一 requestId 只扣一次资源（B03 验收 10）
        String configId,   // building.json 的行 id，如 barracks
        Integer gridX,   // 首次放置时的地块坐标
        Integer gridY)
{
}
