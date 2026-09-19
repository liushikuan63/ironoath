// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一档（两行奖励 + 三个结论位）。客户端**不重新计算**：`reached` 是服务端按当前赛季积分与这一档的 `requiredPoints` 比出来的，`freeClaimed` / `paidClaimed` 是领取账本里的两位。
 */
public record BattlePassTierView(
        long tier,   // 第几档（1 起）。档位号是这一档在表里的 `tier` 列，不是数组下标 —— 将来往中间插一档时，玩家已经领过的档位号不会整体错位。
        long requiredPoints,   // 累计到多少分才达成这一档（来自 `battle_pass` 表）。
        boolean reached,   // 是否已达成（当前赛季积分 ≥ requiredPoints）。未达成的档位**也要下发**：让玩家看见「下一档还差什么」正是战令的动力来源，藏起来等于把 20 档变成 1 档。
        boolean freeClaimed,   // 免费线这一档领过没有。领过就是领过（本表不重置）。
        boolean paidClaimed,   // 付费线这一档领过没有。与 `freeClaimed` 是两位：合成一位会让「领了免费那份」顺手把付费那份也标成已领。
        BattlePassRewardView freeReward,
        BattlePassRewardView paidReward)
{
}
