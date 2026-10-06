// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * `POST /nation/war/goal/claim` 的入参：领取这一场的全服目标奖励（B13 §一 §7「全服累计击杀达标后每人可领一次」）。
 */
public record WarGoalClaimReq(
        String requestId)   // 幂等键。重复提交不会领两次 —— **但真正的护栏不在它**：领取名单在战事档里，同一个人第二次来会被 13025 挡（幂等键只挡网络重放，挡不住玩家换一条请求再点一次）。
{
}
