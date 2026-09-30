// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * `POST /nation/policy/vote` 请求体：对一条提案投赞成或反对（B13 §二 的 `NationVoteReq(String proposalId, boolean support)` 原形）。
 *
 * **二值而不是排序选择**：B13 的契约原文就是 `boolean support`，所以玩家选的是「支持哪几条」，不是「把哪条排第一」。投票是每成员一票（裁决 A2），同一提案只能投一次；改票本批不做（要改就是先撤回再投，那是另一个动作与另一枚错误码）。
 */
public record NationPolicyVoteReq(
        String requestId,   // 幂等键。**这一条比提案更要紧**：重放一次投票会让票数凭空 +1，而公示的两个数字（票数与参与者名单）都是从这份账本算出来的 —— 票数与名单对不上正是这一格唯一要防的形状。
        String proposalId,   // 投哪一条提案。
        boolean support)   // true = 赞成，false = 反对。**不投也是一种选择**（弃权票不计入分母，裁决 A3 的推论），所以协议里没有「弃权」这个取值。
{
}
