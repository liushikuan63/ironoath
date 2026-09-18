// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /ops/report/recent 响应。**窗口回显**（与 `/ops/mail/recent` 同一条理由）：一个不说明自己看了多大窗口的空结果，区分不开"那段时间没人举报"与"我把窗口传错了"。
 */
public record OpsReportRecentResp(
        long windowSeconds,   // 本次实际生效的窗口秒数。超出保留期会被服务端夹住（更早的记录已经不在表里了，给一个大窗口只会得到一张假表）。
        int total,   // 窗口内匹配的条数，**不受 limit 影响**。
        int listed,   // 本响应实际带出的条数。
        List<OpsReportRow> rows)   // 按受理时刻倒序，最多 limit 条。
{
}
