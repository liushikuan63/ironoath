// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 调起微信虚拟支付（米大师）所需的参数。**字段全部是字符串**：这些值由渠道生成，服务端只做透传，任何一侧试图解析或重算它们都会在渠道改格式时静默出错。
 */
public record PayParams(
        String mode,   // 米大师支付模式（game / short_series_goods 等），由服务端按渠道配置下发。
        String offerId,   // 米大师应用 id。**这是部署参数不是游戏数值**，来自环境变量而不是配置表 —— 配置表会进版本库，而 offerId 与密钥同属一类凭据。
        String buyQuantity,   // 购买数量（游戏币个数），字符串形式透传。
        String env,   // 环境（0 正式 / 1 沙箱）。**沙箱参数绝不能出现在正式包里**，所以它由服务端下发而不是客户端写死 —— 客户端写死的话，一次忘了改的提交就会让正式包指向沙箱。
        String currencyType,   // 币种，与 ProductPrice.currency 同源。
        String signature)   // 渠道要求的签名。为 null 表示当前环境不需要签名（本地开发）—— 而**正式环境必须有**，所以服务端在正式环境下不下发 null，客户端也不该把 null 当成正常情况静默放过。
{
}
