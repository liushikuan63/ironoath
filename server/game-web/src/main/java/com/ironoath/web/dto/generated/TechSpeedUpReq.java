// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 用研究加速道具推进当前研究（B20 验收 8：`item_speedup_research_1h`，1 小时 = 3600 秒）。
 */
public record TechSpeedUpReq(
        String requestId,   // 幂等键。加速是<b>消耗品 + 改状态</b>的双重动作，弱网重投若不去重就是白丢道具。
        String itemId,   // 必须是 `type=SPEEDUP` 且 `effectKind=REDUCE_RESEARCH_SECONDS` 的道具。建造令与训练令走到这里会被拒（与 `CityAppService.speedUpByItem` 同一条：宁可响，也不静默按另一种加速处理）。
        long count)   // 用几张。一次多张是有意义的（研究后期一步以天计），与研究令「一张 = effectValue 秒」的定价单位一致。
{
}
