// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 武将稀有度。取值与 hero 表、hero_rarity 表、item 表的 rarity 列一致，声明顺序为升序（N→SSR）。
 */
public enum HeroRarity {
    N,
    R,
    SR,
    SSR
}
