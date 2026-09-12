// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /chat/list 响应体。
 */
public record ChatListResp(
        List<ChatMessageView> messages,   // 消息，按时间升序（客户端直接从上往下画）
        boolean hasMore,   // 是否还有更早的消息
        long serverNow)   // 服务端时间戳
{
}
