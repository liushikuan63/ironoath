// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /chat/send 响应体。**被限流时走业务错误码而不是本响应** —— 返回一条「假装发成功」的消息会让玩家以为对方收到了（B10 验收 9）。
 */
public record ChatSendResp(
        ChatMessageView message,   // 已落地的消息
        long serverNow)   // 服务端时间戳
{
}
