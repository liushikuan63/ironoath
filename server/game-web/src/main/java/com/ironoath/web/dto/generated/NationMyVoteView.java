// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 调用者本轮投出的一票。**刻意只回「我投了什么」而不是「我能不能改」**：改票本批不做（要改就是先撤回再投，那是另一个动作与另一枚错误码），给一个客户端算得出来而服务端不认的「可改」标志就是第二个家。
 */
public record NationMyVoteView(
        String proposalId,   // 投的是哪一条提案。
        boolean support)   // true = 赞成，false = 反对。
{
}
