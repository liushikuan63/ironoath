// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /world/searchTargets 响应体。targets 已按 B08 §8 的四项权重排好序。**targets 里没有任何逐目标的距离数值字段**（B08 验收 12，只给 NEAR/MID/FAR 三档）。顶层那三个 radius* 不是距离读数，而是搜索参数本身的上下界与默认值：判定仍然只在服务端做，客户端拿它们只为了把 ± 键摆到正确的范围上。
 */
public record SearchTargetsResp(
        List<TargetBrief> targets,
        long selfMatchPower,   // 自己的匹配战力（含峰值记忆）。下发它是为了让客户端能解释「为什么这些目标可选」—— 但判定仍然只在服务端做（B08 禁止项：不要在客户端做战力校验）
        long bandLower,
        long bandUpper,
        int radiusMin,   // 半径下界：服务端把请求夹成的地板值。下发它而不是让客户端写死 1，是为了让 ± 键能按到的最小值与真正的截断口径同源（客户端猜的数会随服务端改夹取规则而说谎）
        int radiusDefault,   // 首次搜索的半径（SEARCH_DEFAULT_RADIUS）。客户端的 ± 键在收到这份响应之前连起点都不知道，两颗键于是静默 no-op —— 半径这个玩家参数必须是下发的，铁律 1 不许场景里写死
        int radiusMax,   // 半径上界（SEARCH_MAX_RADIUS）。超过它服务端只截断并记日志、不拒绝，所以下发上来客户端才不会把「已经到顶」演成「按了没反应」
        long serverNow)
{
}
