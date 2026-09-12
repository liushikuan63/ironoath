// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 集结目标的类型。取值必须与 world 协议的 TargetType 一致（SocialContractParityTest 断言）。决定集结到达后的行为分支，与 B07 §2 的行军目标类型同一套口径。
 */
public enum SocialTargetType {
    EMPTY,
    MONSTER,
    RESOURCE,
    PLAYER_CITY,
    ALLIANCE_BUILDING
}
