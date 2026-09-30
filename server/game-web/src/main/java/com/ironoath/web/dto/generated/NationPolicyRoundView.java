// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * `GET /nation/policy` 的响应：本国国策的全部状态（当前处在哪一段、本轮有哪些提案、哪些正在生效、什么时候开下一轮）。
 *
 * **一次给全**而不是分三个端点：面板本来就要同时显示「当前国策」与「本轮提案」，分两次查会得到两个时刻的数（提案刚被投掉、面板上还挂着），而国策公示的争议恰恰出在这种对不上的时刻。
 */
public record NationPolicyRoundView(
        String nationId,
        NationPolicyPhase phase,   // 轮次处在哪一段。读这个动作本身就会惰性推进轮次（结算过期的国策、在该开窗的时刻开窗），与 `GET /nation` 顺手 `settleTax` 同一手法。
        int policySlotCount,   // 可同时生效的国策数（`nation_config.policySlotCount`，Lv1/Lv2/Lv3 = 1/2/3）。这个数决定了同轮多条提案通过时谁能占住槽位。
        List<NationPolicyView> policies,   // 全部国策（表里的 8 行，顺序 = 表序）。提案下拉的候选就是这份，客户端不硬编码。
        List<NationPolicyProposalView> proposals,   // 本轮的全部提案（含已投完的）。空数组 = 本轮还没有人提案。
        List<NationPolicyView> active,   // **当前正在生效**的国策（B21 块③：「生效期间可查『当前国策』」）。到期那一刻它会从这里消失，所以这个数组天然表达「还剩多久」。
        boolean canPropose,   // 服务端算好的「此刻点提案会不会成功」（含权限位与轮次段判定）。客户端不许自己判第二遍。
        NationPolicyBlockReason proposeBlockReason,   // 拦着提案的原因；没拦着时是 `NONE`。
        boolean canVote,   // 服务端算好的「此刻点投票会不会成功」。**Bot 恒为 false**（裁决 A8），但那是服务端判定 —— 协议里没有任何字段能让客户端声明「我是真人」，所以客户端绕不过去。
        NationPolicyBlockReason voteBlockReason,
        List<String> myProposals,   // 我（调用者）这轮提过的提案 id。同一条国策在同一轮里只能被提一次（`ALREADY_PROPOSED`），所以这个数组天然去重。
        List<NationMyVoteView> myVotes,   // 我（调用者）这轮投过的票。同一个提案只能投一次（`ALREADY_VOTED`），改票要走「撤回再投」而那一格本批不做。
        long nextVoteAt,   // 下一次开投票窗的时刻。取「当前生效国策里最早到期的那一刻」—— 也就是轮次自循环的驱动点（2026-09-30 裁决 A4）。`ACTIVE` 段之外为 0。 **必须由服务端下发而不是客户端拿时长自己加**：铁律 5 禁止在展示与判定两侧各算一遍时间。
        long voteEndsAt,   // 本轮投票窗的结束时刻（服务端时间戳）。不在 `VOTING` 段时为 0。窗口长度读 `global.NATION_VOTE_DURATION_HOURS`，那是它的唯一家。
        String slotOrderNote,   // 同轮多条提案都通过时槽位怎么分 —— **一句可上屏的说明**，服务端下发。 规则是「赞成率降序 → 赞成票数降序 → 提案时刻升序 → policyId 字典序」。前三级都能从票数与时刻直接推出，最后一级是**纯粹为了确定性**（同率同数同时刻时不能靠哈希顺序决定，那会让同一份存档复算出不同的结果）。 ⚠ **B13 与 B21 都没写过这一条**（通过门槛用的是相对 50%，槽位竞争是裁决 Q3 明确没选的第三项），所以它是实现侧定的规则，落在服务端一处，改它只改一处。
        long serverNow)   // 服务端时刻。所有倒计时的基准，与 `nextVoteAt` / `voteEndsAt` 同源。
{
}
