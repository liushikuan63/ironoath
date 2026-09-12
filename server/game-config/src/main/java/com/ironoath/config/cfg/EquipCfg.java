// 由 tools/config-gen 依据 contract/config/equip.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 equip 的一行。
 * 武将装备表。一行 = 一件装备：槽位、稀有度、三维固定加成、需求武将等级、所属套装。套装效果在 equip_set 表。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/equip.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record EquipCfg(
        String id,   // 主键
        String name,
        Slot slot,   // 枚举，取值见 EquipSlot
        Rarity rarity,   // 枚举，取值见 EquipRarity
        long might,
        long command,
        long wisdom,
        long requireLevel,
        String setId)   // 外键，指向 equip_set 表的 id
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Slot {
        WEAPON,
        ARMOR,
        MOUNT,
        ACCESSORY
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Rarity {
        N,
        R,
        SR,
        SSR
    }

}
