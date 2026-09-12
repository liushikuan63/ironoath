// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 战斗结果方。与 game-battle 的 Winner 枚举一致。
 */
public enum BattleSide {
    ATTACKER,
    DEFENDER,
    DRAW
}
