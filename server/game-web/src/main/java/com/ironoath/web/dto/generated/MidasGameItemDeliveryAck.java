// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * Tencent MiniGame GameItem 支付回调应答；returnCode='0' 表示成功，requestId回显本次HTTP trace id。
 */
public record MidasGameItemDeliveryAck(
        String returnCode,
        String returnMessage,
        String data,
        String requestId)   // Callback ACK correlation id, echoed from the server trace context.
{
}
