// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 情报里的一项观测值（含误差）。
 */
public record ScoutMetric(
        String name,   // 指标名：power / totalUnits / infantry / cavalry / archer / siege / wood / stone / iron / grain / gold
        long value)
{
}
