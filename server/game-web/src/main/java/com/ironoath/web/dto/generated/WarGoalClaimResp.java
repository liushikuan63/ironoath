// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * `POST /nation/war/goal/claim` 的响应：这一次领到了多少金币。
 *
 * **回金额而不是只回 ok**：客户端要弹一句「领到 500 金币」，而那个数属于配置（`global.WAR_SERVER_GOAL_GOLD`）—— 让客户端自己拼就是把配置抄进客户端（红线：客户端不抄配置表）。
 */
public record WarGoalClaimResp(
        long gold,   // 本次领到的金币数。**恒为正**：领不到的情况全是业务拒绝（13024/13025），不走这条响应。
        long serverNow)   // 服务端时间戳。
{
}
