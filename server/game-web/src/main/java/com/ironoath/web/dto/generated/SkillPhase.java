// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 技能触发时机。与 game-battle 的 SkillPhase 枚举一致。
 */
public enum SkillPhase {
    ROUND_START,
    EVERY_ROUND,
    ON_HIT,
    ON_DEATH
}
