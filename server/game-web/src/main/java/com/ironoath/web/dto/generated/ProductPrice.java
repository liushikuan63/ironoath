// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一个商品的价格。**由服务端下发，客户端不得内置任何价格**：不同渠道/地区的定价可以不同，而内置价格的客户端在调价时必须发版 —— 更糟的是，发版前的旧客户端会显示旧价格却按新价格扣款。
 */
public record ProductPrice(
        String productId,   // 商品 id，形如 monthly_card / growth_fund / first_charge。
        long cents,   // 价格，单位是分。来源 global.PRODUCT_*_CENTS。
        String currency,   // 币种（ISO 4217，如 CNY）。客户端据此决定货币符号的位置与小数位数 —— 写死「¥」的话，出海时每一个界面都要改。
        Integer originalCents)   // 划线价（分），无折扣时为 null。**为 null 时客户端不得显示划线** —— 显示一个等于现价的划线价是价格欺诈的常见形态，而监管对这一条查得很细。
{
}
