// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /alliance/review 请求体（审核申请）。
 */
public record AllianceReviewReq(
        String requestId,   // 幂等键
        String applicantId,   // 申请者玩家 id
        boolean approve)   // 通过还是拒绝。**拒绝也要显式调用** —— 只是不处理会让申请永远挂着，申请者不知道自己被忽略了
{
}
