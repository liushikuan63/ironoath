// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 提案结果。**回整份轮次视图**而不是只回一个 proposalId：提案面板要立刻显示新提案与刷新后的票数，再发一次查询就会有两个时刻的数（提案刚被投掉、面板还挂着上一份）。
 */
public record NationPolicyProposeResp(
        String proposalId,
        NationPolicyRoundView round)   // 提案之后的轮次视图（票数此刻全是 0，因为窗口还没开）。
{
}
