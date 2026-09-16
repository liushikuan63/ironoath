// 由 tools/config-gen 依据 contract/config/pay_product.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 pay_product 的一行。
 * 付费商品表（B19 §一.1）。三行 = 支付域唯一在卖的三类商品：月卡 / 成长基金 / 首充。
 * 本表只放**结构与权益**；价格不放这里（见 designNote 第 1 条：价格住在 global 的 PRODUCT_*_CENTS，本表用 priceCentsParam 指它的名字）。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/pay_product.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record PayProductCfg(
        String id,   // 主键
        String name,
        Kind kind,   // 枚举，取值见 PayProductKind
        String priceCentsParam,
        GrantOccasion grantOccasion,   // 枚举，取值见 PayProductGrantOccasion
        Long durationDays,
        boolean adFree,
        long extraQueues,
        String heroChoices)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Kind {
        MONTHLY_CARD,
        GROWTH_FUND,
        FIRST_CHARGE
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum GrantOccasion {
        ON_PURCHASE,
        DAILY,
        TIER
    }

}
