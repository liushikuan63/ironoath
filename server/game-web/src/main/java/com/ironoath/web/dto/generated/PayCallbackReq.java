// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /pay/callback 请求体：渠道支付结果回调。
 *
 * **这个端点不校验玩家身份**：调用方是渠道服务器而不是客户端，所以它的可信性完全靠 sign 验签，而不是靠 X-Player-Id 头。
 */
public record PayCallbackReq(
        String orderId,   // 服务端订单号（下单时下发的那个）。
        String transactionId,   // 渠道流水号。同一个 orderId 可能对应多个 transactionId（取消后重付），所以它不能当幂等键，只作为凭据留存。
        String sign,   // 渠道签名。验签失败必须拒绝（PAY_SIGN_INVALID）—— 不验签的回调端点等于任何人 POST 一下就能给自己发货。
        Boolean success)   // 渠道告知的支付结果。false 时订单转为 FAILED，不发货。
{
}
