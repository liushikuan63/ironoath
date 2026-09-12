// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 暴虐档位（B08 §4）。与 game-core 的 Tyranny.Level 一致（由 PowerContractParityTest 断言）。
 */
public enum TyrannyLevel {
    COMMONER,
    TYRANT,
    BRUTE,
    PUBLIC_ENEMY
}
