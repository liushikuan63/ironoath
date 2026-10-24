// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 调起微信小游戏虚拟支付的当前内部参数形状。服务端冻结一个符合平台长度/字符约束的 outTradeNo，与内部订单号分开；其余支付能力和正式渠道签名仍须按适用合同接通。
 */
public record PayParams(
        String mode,   // 米大师支付模式（game / short_series_goods 等），由服务端按渠道配置下发。
        String offerId,   // 米大师应用 id。**这是部署参数不是游戏数值**，来自环境变量而不是配置表 —— 配置表会进版本库，而 offerId 与密钥同属一类凭据。
        String buyQuantity,   // 下单金额（**分**），字符串形式透传 —— 服务端发的是 line.totalCents()，也就是这一单的应收金额。 2026-09-13 裁决：语义以代码现状（金额）为准，并改掉本字段此前那句「购买数量（游戏币个数）」。理由是全条支付链只有 totalCents 一个真实生产者，没有任何一处产出过「个数」这种语义，而 global 里的 PRODUCT_*_CENTS 本身就是分。 **接入真实渠道之前仍须拿米大师正式文档复核**：两种读法的实收金额可以差 100 倍，而唯一能定它的是官方文档（本地没有凭据，check-contract-sync 也查不出「描述与代码意图不一致」）。见收口清单 #49 与 §三·补 A2。
        String env,   // 环境（0 正式 / 1 沙箱）。**沙箱参数绝不能出现在正式包里**，所以它由服务端下发而不是客户端写死 —— 客户端写死的话，一次忘了改的提交就会让正式包指向沙箱。
        String currencyType,   // 币种，与 ProductPrice.currency 同源。
        String signature,   // 预留签名字段。**当前生产代码恒为 null**（PayAppService.payParams 最后一参），MidasPayment 桥也不传此字段；原注释声称『正式环境必有、不会下发 null』与代码不符。是否需要签名、签名来源及传参位置须按微信小游戏正式渠道文档和真机回执核实，本字段不构成已接通正式支付的证据。
        String outTradeNo)   // 米大师通道订单号，稳定映射当前内部订单。新订单为32位小写十六进制；老订单无冻结映射时为null且不得据此新拉起支付。
{
}
