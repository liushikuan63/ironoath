// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 加成落在哪个乘区（B06 硬约束 1：配置表里要明确标注每个加成落在哪个乘区）。HERO=乘区A武将本体、BOND=乘区A的缘分子乘区、EQUIP_SET=装备乘区（B05 §1.3 的六乘区之一，与武将乘区隔离）。
 */
public enum BonusZone {
    HERO,
    BOND,
    EQUIP_SET
}
