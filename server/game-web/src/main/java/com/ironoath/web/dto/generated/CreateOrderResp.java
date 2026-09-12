// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 下单结果：订单号 + 调起支付所需的参数。
 */
public record CreateOrderResp(
        String orderId,   // 服务端订单号。**回调验签与补单都以它为唯一键** —— 用渠道的 transactionId 做键是不行的，因为取消后重新支付会产生新的 transactionId，而那是同一笔订单。
        PayParams payParams)   // 调起支付所需参数。
{
}
