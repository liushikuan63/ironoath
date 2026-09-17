// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /equip/forge 响应：改完之后这一件的状态 + 本次花了多少、涨了多少。
 */
public record EquipForgeResp(
        EquipInstanceView instance,   // 强化后的这一件（等级、三维、下一级价格全在里面）。回整个视图而不是回三个字段：客户端刷新卡片只有一条路径， 不会出现「列表里那件还是旧等级，弹层里那件已经是新等级」。
        long costIron,   // 本次实际扣掉的铁。必为正：纯消耗、必成（§五②），所以没有「失败了退款一半」这种状态。
        long powerDelta,   // 本次强化带来的战力增量（刷新后）。穿在身上才涨战力，在包里时增量为 0 —— 这一位就是那句规则的机器化版本： 不是 0 就说明它此刻确实作用于某个武将。
        long serverNow)
{
}
