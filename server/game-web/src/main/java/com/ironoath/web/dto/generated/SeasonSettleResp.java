// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一次结算触发的结果。
 */
public record SeasonSettleResp(
        String seasonId,   // 被结算的赛季。
        int settledPlayers,   // 本次新结算的人数（重复触发的部分是幂等跳过，不计入）。
        long distributedSeasonCoin,   // 本次发出的赛季币总额。
        long distributedGold,   // 本次发出的金币总额。
        long snapshotAt,   // 结算依据的快照时刻。B14 禁止项：结算只按快照 —— 按实时榜会让最后一秒刷分的人挤掉从头打到尾的人。
        long serverNow)   // 服务端时间戳。
{
}
