// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /world/exile 响应体。
 */
public record ExileResp(
        Coord coord,   // 新的城坐标。客户端必须以此为圆心重拉视野
        long peaceUntil,   // 免战到期时刻（服务端毫秒）。走的是自愿停战那本账（Protection.Kind.PEACE），双向生效：既不被打也不能打人
        long nextExileAt,   // 下一次可以再次流亡的时刻。下发而不是让客户端按冷却自己算，是因为两处算法一旦漂移，玩家看到的「还能不能迁」就会和实际拒绝结果不一致
        long seed,   // 落点由这个服务端种子推导（铁律 4：随机必须可复现）。客服接到「迁到了一个鸟不拉屎的地方」的申诉时，凭 seed + 探测次数能还原当时为什么只能落在那一格
        long serverNow)
{
}
