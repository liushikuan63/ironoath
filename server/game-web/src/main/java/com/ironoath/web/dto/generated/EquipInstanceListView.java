// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /equip/instances 响应：这个玩家的全部装备实例。整份下发而不分页 —— 与科技树同一条理由：
 * 装备上限是「16 行 × 少量件数」这个量级，而服务端替客户端决定该看哪几件会造出第二个真相。
 * 包里的与穿着的都在这一个数组里（靠 `wornByHeroId` 区分），因为强化对两者都开放：
 * 「穿着的不能强化」这种规则从来没被写进任何文档，而它一旦被客户端猜出来就再也收不掉了。
 */
public record EquipInstanceListView(
        List<EquipInstanceView> instances,
        long serverNow)   // 服务端时钟（epoch 毫秒）。本机制没有任何到期时间，带上它只为与其他列表响应同形，省掉客户端一处特例。
{
}
