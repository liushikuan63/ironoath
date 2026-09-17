// 由 tools/config-gen 依据 contract/config/gift.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 gift 的一行。
 * 礼包弹窗表。一行 = 一个礼包：什么时候推、推的是哪一档商品、每天能买几次、弹出后多久内有效。发货内容与价格不在本表（在 product_reward 与 pay_product）。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/gift.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record GiftCfg(
        String id,   // 主键
        String name,
        Trigger trigger,   // 枚举，取值见 GiftTrigger
        String productId,   // 外键，指向 pay_product 表的 id
        long limitCount,
        long offerTtlMinutes)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Trigger {
        STUCK_STAGE,
        BUILDING_DONE,
        BATTLE_LOST
    }

}
