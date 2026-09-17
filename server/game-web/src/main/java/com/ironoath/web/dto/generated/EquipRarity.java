// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 装备稀有度。取值与 `equip.json` 的 `rarity` 列 ENUM 声明逐项一致。它决定 `forgeMax`（N=10 / SR=15，§五②），不决定单级价格 —— 价格跟着属性总和走。
 */
public enum EquipRarity {
    N,
    R,
    SR,
    SSR
}
