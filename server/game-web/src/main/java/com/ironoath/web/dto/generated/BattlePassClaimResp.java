// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /battlePass/claim 响应：领到了什么 + 领取之后的全量状态。**状态整份回传**：客户端据此重画，不在本地把那一档翻成已领 —— 本地翻法在「服务端拒了但界面已经翻过去了」时会骗人。
 */
public record BattlePassClaimResp(
        BattlePassTrack track,
        long tier,   // 刚领的是哪一档。
        BattlePassRewardView reward,
        BattlePassStatusResp status)
{
}
