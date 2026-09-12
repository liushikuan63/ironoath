// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /world/reports 响应体。已过期的报告也会返回（带 expired=true），因为「我曾经侦查过这里」本身是有用的信息。
 */
public record ScoutListResp(
        List<ScoutReportView> reports,
        long serverNow)
{
}
