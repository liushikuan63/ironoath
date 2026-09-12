// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /battle/reports 的响应：我的战报列表，按时间倒序（最新的在前）。
 */
public record BattleReportListResp(
        List<BattleReportBrief> reports,
        long serverNow)
{
}
