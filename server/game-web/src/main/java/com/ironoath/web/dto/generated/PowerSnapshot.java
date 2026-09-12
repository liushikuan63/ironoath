// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 战力三元组。displayPower 仅展示；matchPower 用于 PVP 匹配校验（含峰值记忆）；peakPower 为历史峰值。铁律 11：匹配一律用 matchPower。
 */
public record PowerSnapshot(
        long displayPower,
        long matchPower,
        long peakPower)
{
}
