// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 行军目标类型，决定到达后的行为分支（B07 §2 的表格）。
 */
public enum TargetType {
    EMPTY,
    MONSTER,
    RESOURCE,
    PLAYER_CITY,
    ALLIANCE_BUILDING
}
