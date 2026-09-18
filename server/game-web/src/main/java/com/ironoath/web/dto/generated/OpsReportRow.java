// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一条举报留痕（运营只读出口的行，B22 §一 3 / §五 裁决②）。字段就是运营查证时要看的那几件事：谁报的、报的谁、哪条消息、什么原因、补了什么、什么时候。
 */
public record OpsReportRow(
        String reportId,   // 留痕记录 id。
        String reporterId,   // 举报人。
        String targetPlayerId,   // 被举报人。
        String messageId,   // 被举报的消息 id；没带就是 null。带上它运营才能看到被举报的原话。
        ReportReason reason,   // 举报原因。
        String detail,   // 举报人的补充说明；没写就是 null。
        long createdAt)   // 受理时刻（服务端时间戳）。
{
}
