// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 本轮的一条提案（B13 §二 的 `NationVoteReq(proposalId, support)` 投的就是它）。
 *
 * **公示的两份名单一次给全**（2026-09-30 裁决 A9）：200 人国约 2KB、800 人国约 8KB，都在 `global.PERF_PAYLOAD_MAX_BYTES=20480` 预算内（对照：排行榜最坏 4101B）。分页要引入另一个 N 与一套游标，而 800 人国要翻十几页才看得到名单 —— 为省几 KB 换一个「公示查不到人」，是本末倒置。
 */
public record NationPolicyProposalView(
        String proposalId,   // 提案 id，投票时原样回传。
        NationPolicyView policy,   // 这条提案指向的国策（表里的一行）。
        long yes,   // 赞成票数。
        long no,   // 反对票数。
        List<NationPolicyVoterView> supporters,   // 投了赞成的玩家（B13 §4「参与者可查」的正面那一份）。
        List<NationPolicyVoterView> opponents,   // 投了反对的玩家。
        String proposedBy,   // 提案人（国王或内政官，2026-09-30 裁决 A1 放开到官员档）的玩家 id。
        long proposedAt)   // 提案时刻（服务端时间戳）。它同时是槽位竞争的**末位排序键**（见 `NationPolicyRoundView.slotOrderNote`）。
{
}
