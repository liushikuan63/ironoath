// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 技能位（B06 §2.4）。主技能全队生效，副技能只在副将位生效。
 */
public enum SkillSlot {
    MAIN,
    SUB
}
