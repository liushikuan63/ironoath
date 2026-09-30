// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 公示名单里的一名投票者。**带中文名而不是只给 id**：B13 §4 明文要求「票数与**参与者**可查」，而玩家要看到的是名字。
 */
public record NationPolicyVoterView(
        String playerId,
        String name)   // 玩家名，服务端下发（要与聊天、战报、客服工单里的称呼一致）。
{
}
