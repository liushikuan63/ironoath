// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 领取一次活动奖励。
 */
public record ActivityClaimReq(
        String activityId,   // 要领的那一行。
        String requestId)   // 幂等键，与 `/quest/claim`、`/mail/claimAll` 同一要求：弱网重投与玩家连点都必须只发一次奖。
{
}
