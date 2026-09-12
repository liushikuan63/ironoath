// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /city/collect 响应体。output 是本次结算实际入账的产量：产出走连续惰性结算（建筑按等级计入每小时产率，升级中不计，完成时刻起按新等级计），所以「收割升级」与「结算产量」是同一个动作的两面。离线累积的上限由仓储容量承担（满仓即停产），不另设追溯时长上限。buildings 是本次被收割的建筑（等级已 +1），客户端据此播放升级动效。
 */
public record CityCollectResp(
        List<BuildingView> collected,   // 本次完成升级的建筑（等级已 +1、状态回到 IDLE）
        List<ResourceAmount> output,   // 补结算的离线产出合计
        long serverNow)
{
}
