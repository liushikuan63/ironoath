// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 战令的两条线。`FREE` 人人可领；`PAID` 要本赛季战令已购买（随付费发货解锁，见 `pay_product` 的 `battle_pass` 行）。
 */
public enum BattlePassTrack {
    FREE,
    PAID
}
