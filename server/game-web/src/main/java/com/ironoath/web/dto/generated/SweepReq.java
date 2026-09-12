// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /stage/sweep 请求体（B09 §6）。**最多 10 次只发 1 次请求**（验收 9）：客户端逐次发 10 个请求的话，每一次都要走一遍幂等、加锁、结算，弱网下会有几次超时，玩家看到的是「扫荡了 7 次」这种无法解释的结果。
 */
public record SweepReq(
        String requestId,
        String stageId,
        int count)   // 扫荡次数，1~10。超过 10 直接拒绝而不是截断：截断会让玩家以为扫了 10 次却只拿到 3 次的奖励
{
}
