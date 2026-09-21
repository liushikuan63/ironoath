// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /city/pause 响应体。回的是暂停后的那一行读数，界面直接照着改，不用再拉一次 /city/list（拉一次会顺带把已完成的建筑收割掉，那是另一个动作）。
 */
public record CityPauseResp(
        String buildingId,
        String status,   // 暂停后的状态，恒为 PAUSED。
        long remainingSeconds)   // 剩余秒数。**暂停时恒为 0**：服务端不给暂停中的建筑算倒计时（照 finishAt 算会显示一个永远不走的表）。真正的剩余时间冻结在服务端，恢复时原样接上。
{
}
