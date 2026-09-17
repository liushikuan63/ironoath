// 由 tools/config-gen 依据 contract/config/product_reward.json（表 version=2） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 product_reward 的一行。
 * 付费商品的发货内容（B19 §一.1）。一个商品多行奖励：月卡的日包三行、首充的金币一行、成长基金的六档各一行。
 * 价格与「按什么节奏领」在 `pay_product`，本表只回答「一次发的是哪些东西、各多少个」。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/product_reward.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record ProductRewardCfg(
        String id,   // 主键
        String productId,   // 外键，指向 pay_product 表的 id
        Long requireMainLevel,
        RewardType rewardType,   // 枚举，取值见 ProductRewardRewardType
        String rewardId,
        long count)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum RewardType {
        RESOURCE,
        ITEM
    }

}
