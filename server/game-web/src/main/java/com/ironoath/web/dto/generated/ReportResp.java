// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /social/report 响应体。只回执受理结果，**不回"是否处罚"** —— 那是运营的决定，且举报人也不该从响应里看出处置结果（表现成"报了就一定封"会让举报变成一种攻击工具）。
 */
public record ReportResp(
        String reportId,   // 留痕记录 id（运营侧按它查证）。
        long serverNow)   // 服务端时间戳。
{
}
