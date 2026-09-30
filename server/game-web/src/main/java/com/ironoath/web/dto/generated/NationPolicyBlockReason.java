// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 为什么现在不能做这个动作。`NONE` = 没拦着（此时对应的 `canPropose` / `canVote` 为 true）。
 *
 * `NOT_PROPOSER` 与 `NOT_VOTING` **刻意不合并**：前者是「你这个身份没有提案权」（读 `role_permission` 表的 `SET_NATIONAL_POLICY`，2026-09-30 裁决放开到官员档），后者是「身份够但此刻不是提案段」。合并后面板会对一个内政官说「你不是国王」而他明天可能就该收到别人的提案通知 —— 玩家能做的是等窗口开，而不是换个人。
 *
 * `BOT_NOT_ALLOWED` 同样独立：Bot 不投票是 2026-09-30 的裁决（B11/B13 的红线只管「Bot 不得任官职」，投票不是官职，那条红线一个字都没覆盖到这一格）。它与 `NOT_PROPOSER` 分开是因为**两条红线的来源不同**：一条是权限表，一条是合规。
 *
 * `NO_PROPOSAL_YET` 与 `NOT_VOTING` 也分开：前者是「本轮还没有任何提案，投票窗开不起来」（轮次自循环要有人提才有得投），后者是「窗口已经开过或正在开，提案段已经结束」。玩家在两种情况下的下一步不同：一种是自己提一条，另一种是等别人提完再投。
 */
public enum NationPolicyBlockReason {
    NONE,
    NOT_PROPOSER,
    NOT_VOTING,
    ALREADY_VOTED,
    ALREADY_PROPOSED,
    NO_PROPOSAL_YET,
    BOT_NOT_ALLOWED
}
