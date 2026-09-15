// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /ops/mail/recent 响应。**窗口与过滤条件全部回显**（与 `/ops/track/recent` 的 `eventName` 同一条理由）：一个不说明自己看了多大窗口的空结果，区分不开"那段时间没人补"与"我把窗口传错了"。
 */
public record OpsMailRecentResp(
        String playerId,   // 回显过滤条件：空串表示全服（没按人筛）。
        long windowSeconds,   // 本次实际生效的窗口秒数（**夹过之后**的值）。上限就是保留期 —— 比它更早的邮件已被清，给一个更大的窗口只会得到一张"没人补发过"的假表，所以服务端会夹住并打 WARN。
        int total,   // 窗口内匹配的封数，**不受 limit 影响**。
        int listed,   // 本响应实际带出的条数。
        List<OpsMailRow> rows)   // 按 `createdAt` 倒序，最多 limit 条。
{
}
