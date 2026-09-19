// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /battlePass/claim 请求体：领某一档的某一条线。
 */
public record BattlePassClaimReq(
        String requestId,   // 幂等键。领奖会往背包/资源里写东西，重放等于刷奖励。
        long tier,   // 要领第几档（表里的 `tier`）。档位号由客户端回传而不是「领下一个」：玩家点的是他眼前那一行，而服务端按「下一个未领的档」发会在弱网重试下与玩家的点击目标错开。
        BattlePassTrack track)
{
}
