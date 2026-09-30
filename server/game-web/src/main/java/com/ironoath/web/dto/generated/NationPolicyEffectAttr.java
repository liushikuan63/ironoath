// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 国策改的是哪一个数。取值与 `nation_policy.json` 的 `effectAttr` 列逐一对应（由 `ContractEnumParityTest` 断言）。
 *
 * **只有四个取值**：国策的效果最终落在四条算式上 —— `POLICY_ATTACK` / `POLICY_DEFENSE` 进战斗内核的**乘区 G**（B21 §五④ 块③「buff 走独立乘区，不许污染既有乘区」），`OUTPUT` 进 `ResourceRateService` 的每资源产率算式，`MARCH_SPEED` 进 `Rates.shortenSeconds` 那条时长算式。乘区 H（城墙）**刻意不在这个枚举里**：城墙等级 → 加成的幅度至今没有任何出处，声明一个恒为 0 的取值就是给一条不存在的分支起名字。
 */
public enum NationPolicyEffectAttr {
    POLICY_ATTACK,
    POLICY_DEFENSE,
    OUTPUT,
    MARCH_SPEED
}
