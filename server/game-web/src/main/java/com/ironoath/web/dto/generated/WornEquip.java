// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 身上穿着的一件装备实例。**只列穿着的**，空槽不出现在数组里（见 HeroView.equips 的理由）。
 */
public record WornEquip(
        EquipSlot slot,   // 穿在哪个槽位。客户端按它把这一件落到四格里，所以数组可以是 0~4 项
        String uid,   // 实例 uid —— 换装与卸下要按它发请求。**只用于请求，不许出现在画面上**（玩家读不懂一个内部编号）
        String equipId,   // equip 表的行 id（同一行可以有多件实例，套装与筛选按它查）
        String name,   // 装备中文名，服务端按 equipId 查 equip 表的 name 列（#255 起那条「名字由服务端下发」的口径）
        int forgeLevel)   // 强化等级。玩家真正要看的「+2」是它，不是 uid
{
}
