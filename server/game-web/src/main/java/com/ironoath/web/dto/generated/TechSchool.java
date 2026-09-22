// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 学派。取值与 `tech.json` 的 `school` 列 ENUM 声明逐项一致（`ContractEnumParityTest` 的 techSchoolMatchesTable 会核这一点，改表枚举不改这里就会红）。
 */
public enum TechSchool {
    AGRICULTURE,
    MILITARY,
    COMMERCE,
    FORTIFICATION
}
