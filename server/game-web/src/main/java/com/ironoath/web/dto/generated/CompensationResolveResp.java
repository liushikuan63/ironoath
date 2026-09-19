// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 销账结果。
 */
public record CompensationResolveResp(
        String compensationId,   // 被销的那一条。
        boolean resolved,   // 这一次是否真正完成了状态翻转。**只有从「未处理」翻到「已处理」的那一次是 true** —— 两个人同时处理同一条时，第二个人拿到 false，才知道自己那封补发邮件是重复的。
        long pendingCount)   // 销账之后还剩几笔没处理（让调用方一次请求就能确认自己在往下走）。
{
}
