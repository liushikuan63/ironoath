// 由 tools/config-gen 依据 contract/config/shop.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 shop 的一行。
 * 商店表。B02 字段：商品/价格/限购/上架条件。四种货币：金币(可充值)、联盟币(捐献获得)、小队币(互助获得)、赛季币(赛季参与获得)。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/shop.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record ShopCfg(
        String id,   // 主键
        String name,
        String itemId,   // 外键，指向 item 表的 id
        String frameId,   // 外键，指向 avatar_frame 表的 id
        PriceCurrency priceCurrency,   // 枚举，取值见 ShopPriceCurrency
        long price,
        long limitCount,
        RefreshType refreshType,   // 枚举，取值见 ShopRefreshType
        long requireMainLevel)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum PriceCurrency {
        GOLD,
        ALLIANCE_COIN,
        SQUAD_COIN,
        SEASON_COIN
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum RefreshType {
        NONE,
        DAILY,
        WEEKLY,
        SEASON
    }

}
