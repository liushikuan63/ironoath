// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 国策轮次处在哪一段。**三段而不是两段**：`NationVoteReq` 是二值的（B13 §二 的 `NationVoteReq(String proposalId, boolean support)`），所以「提案」与「投票」必然是两个不同的动作、两个不同的时间窗 —— 把它们压进一个窗口意味着玩家在投票窗口里才能提案，而提案要 24 小时被讨论、投票又要 24 小时，窗口就得 48 小时，那与 `NATION_VOTE_DURATION_HOURS=24` 的原意（一次投票 24 小时）不是一回事。
 *
 * 三个取值各自能做什么：
 * - `PROPOSING` —— 本国可以提案，投票窗口未开。提案不消耗任何资源（B13 §3 的国库三用途里没有「国策」）。
 * - `VOTING` —— 投票窗口开着（长度 = `NATION_VOTE_DURATION_HOURS`），可以投票，不能提案。
 * - `ACTIVE` —— 本轮已结算，通过的国策占住了 `policySlotCount` 个槽位并**生效中**，等最早到期那一刻自动开下一轮。
 *
 * **没有任何一段是「常驻定时器推进的」**：轮次由读取动作惰性推进（与 `settleTax` 同一手法），服务端不跑任何定时任务（`check-no-scheduled.sh` 是门禁）。
 */
public enum NationPolicyPhase {
    PROPOSING,
    VOTING,
    ACTIVE
}
