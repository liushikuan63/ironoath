// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 战斗类型，决定死伤比例（B05 §1.5）。与 game-battle 的 BattleType 枚举一致。
 */
public enum BattleType {
    PVE,
    PVP_SOLO,
    PVP_RALLY,
    SIEGE
}
