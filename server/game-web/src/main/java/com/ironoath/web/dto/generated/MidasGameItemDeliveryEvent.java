// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * Tencent MiniGame GameItem 发货回调。HTTP 头 TC-Timestamp/TC-Signature 与原始 payload 的 payEventSig 均需验签。
 */
public record MidasGameItemDeliveryEvent(
        String eventType,   // TRANSACTION.SUCCESS 或 TRANSACTION.PAYERROR。
        String event,   // 创建订单时使用的事件名。
        String payload,   // 支付回调 JSON 原串，验签时不得先 parse 再重序列化。
        String payEventSig,   // HMAC-SHA256(AppKey, event + '&' + payload) 的小写十六进制签名。
        String outTradeNo,   // 平台回调外层订单号，必须与已验签 payload 内订单号一致。
        String transactionId)   // 平台流水号；履约使用签名 Payload.PayInfo 中的值。
{
}
