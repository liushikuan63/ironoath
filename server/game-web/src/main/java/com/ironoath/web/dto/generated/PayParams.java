// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 微信小游戏道具直购响应，直接对应官方 wx.requestMidasPaymentGameItem。旧游戏币参数不再下发；旧客户端缺少 GameItem 字段时必须失败关闭。outTradeNo 与内部订单号分离并稳定映射。
 */
public record PayParams(
        String signData,   // 官方 GameItem 支付原串：服务端按固定属性顺序序列化的 JSON 字符串；paySig 与用户态 signature 均绑定这一个原串。旧订单缺少该字段时客户端不得临时重建。
        String paySig,   // GameItem 服务端支付签名：HMAC-SHA256(AppKey, 'requestMidasPaymentGameItem&' + signData) 的小写十六进制结果。AppKey 不下发客户端。
        String signature,   // GameItem 用户态签名，使用服务端持有的微信 session_key 对 signData 计算。旧订单可为 null，客户端遇到 null 必须拒绝拉起。session_key 不下发客户端，也不得写日志。
        String outTradeNo)   // 米大师通道订单号，稳定映射当前内部订单。新订单为32位小写十六进制；老订单无冻结映射时为null且不得据此新拉起支付。
{
}
