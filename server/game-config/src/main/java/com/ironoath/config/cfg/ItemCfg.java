// 由 tools/config-gen 依据 contract/config/item.json（表 version=7） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 item 的一行。
 * 道具表。B02 字段：id/名称/类型/使用效果/堆叠上限/是否可出售。类型五种：加速/资源/宝箱/材料/增益（B02 原文的「加速/资源/宝箱/材料」加上护盾与集结令所需的 BUFF）。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/item.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record ItemCfg(
        String id,   // 主键
        String name,
        Type type,   // 枚举，取值见 ItemType
        Rarity rarity,   // 枚举，取值见 ItemRarity
        EffectKind effectKind,   // 枚举，取值见 ItemEffectKind
        long effectValue,
        String effectTarget,
        long stackMax,
        boolean sellable,
        long sellPriceGold,
        String obtainFrom)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Type {
        SPEEDUP,
        RESOURCE,
        CHEST,
        MATERIAL,
        BUFF,
        EQUIP
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Rarity {
        N,
        R,
        SR,
        SSR
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum EffectKind {
        REDUCE_BUILD_SECONDS,
        REDUCE_TRAIN_SECONDS,
        REDUCE_RESEARCH_SECONDS,
        GRANT_RESOURCE,
        GRANT_RANDOM_RESOURCE,
        OPEN_GACHA,
        COMPOSE_HERO,
        GRANT_SHIELD,
        GRANT_RALLY_BONUS,
        GRANT_HERO_EXP,
        UP_HERO_SKILL,
        AWAKEN_HERO,
        EQUIP_HERO,
        CLOSE_CITY
    }

}
