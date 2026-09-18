// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /battle/share 响应体。只回执「贴到哪了、是第几条」，**不回整条消息**：消息的形状属聊天域（`ChatMessageView` 的字段会随聊天演进），在这里复制一份就会分叉 —— 客户端要显示它就去 `/chat/list` 拉那一条。
 */
public record ReportShareResp(
        String reportId,   // 被分享的战报 id（原样回执，便于客户端把结果与请求对上）。
        ShareChannel channel,   // 落地的频道。
        String messageId,   // 落在频道里的那条消息 id。客户端据此把「我刚分享的那条」从窗口里认出来，不必按正文反查。
        long serverNow)   // 服务端时间戳。
{
}
