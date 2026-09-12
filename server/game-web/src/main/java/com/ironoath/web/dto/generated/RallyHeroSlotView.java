// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一个随军武将位（按加入顺序，发起人最先）。
 */
public record RallyHeroSlotView(
        String playerId,   // 提交这个武将位的成员。
        String heroId,   // 武将 id。
        String heroName,   // 武将名，服务端从 hero 表下发。客户端不得自行拼接：那份名字要与战报、聊天、客服工单里的称呼一致。
        RallyHeroSlotState state,   // 状态。
        boolean selected)   // 是否进入合并行军的名单。等于 state == SELECTED，单独给一个布尔是为了让客户端不必理解枚举就能画红点。
{
}
