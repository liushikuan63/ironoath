// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 国家间的四种外交关系（B13 §5）。取值必须与 game-core 的 Nation.Diplomacy 逐一对应（NationPayEnumParityTest 断言）。
 *
 * **关系直接影响国战分组与跨服匹配**，所以它不是装饰性的标签：盟约之间不能互相攻击（mayAttackNation 会拒绝），敌对之间才可以。这也意味着改一次关系会立刻改变谁能打谁 —— 所以变更必须留日志（谁、何时、从什么改成什么），否则一次误操作会变成一场无从追溯的战争。
 */
public enum DiplomacyRelation {
    ALLIED,
    HOSTILE,
    NEUTRAL,
    TRIBUTARY
}
