// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /battlePass/status 响应：本赛季的战令全貌。积分、解锁位、20 档一起下发，客户端一次画完。
 */
public record BattlePassStatusResp(
        String seasonId,   // 这些进度属于哪个赛季（与赛季账本、商店 `SEASON` 限购同一个键）。**下发给客户端是为了让界面能说清「这是哪一季的战令」**：跨赛季那一刻玩家的积分会归零，而界面上如果没有赛季名，看到的就只是「我的分突然没了」。
        long points,   // 本赛季已获得的战令积分。来源只有两处：任务领取与活动领取（各自的表里配分值）—— 战令**没有**自己的任务体系。
        boolean paidUnlocked,   // 付费线是否已解锁（本赛季战令买过没有）。它随付费发货落下，不由客户端上报。
        long seasonEndAt,   // 本赛季结束的服务端毫秒时刻（赛季时间轴的末段终点）。界面据此显示「还剩几天」—— 客户端不得用本地时钟推算（铁律 5）。
        List<BattlePassTierView> tiers,   // 20 档，按 tier 升序。**含未达成的档**：未达成的档位是玩家的目标，而不是噪音。
        long serverNow)   // 服务端时刻（与 `seasonEndAt` 相减得到剩余时间）。
{
}
