// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 投票结果。同样回整份轮次视图：投票之后要立刻看到票数与（自己的）选择，公示是这一格的核心交付物（B13 验收 11「结果公示可查」）。
 */
public record NationPolicyVoteResp(
        String proposalId,
        boolean support,   // 这一票投的是什么（原样回显）。
        NationPolicyRoundView round)   // 投票之后的轮次视图。`yes` 与 `no` 之和必然等于「实际投票人数」（弃权不计入），界面可以拿它与两份名单的长度自证。
{
}
