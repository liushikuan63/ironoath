// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 兵种类型。取值必须与 unit 表的 type 列、game-battle 的 UnitType 枚举一致（由 BattleContractParityTest 断言）。
 */
public enum UnitType {
    INFANTRY,
    CAVALRY,
    ARCHER,
    SIEGE
}
