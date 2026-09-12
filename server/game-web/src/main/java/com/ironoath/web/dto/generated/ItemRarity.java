// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 道具稀有度。取值与 item 表、hero 表的 rarity 列一致。<b>声明顺序就是稀有度升序（N→SSR）</b>：服务端的背包排序键直接取生成枚举的 ordinal 反序（越稀有 ordinal 越大 ⇒ 排越前），所以这个顺序本身就是口径，改顺序等于改排序规则，必须双端同时重新生成。
 */
public enum ItemRarity {
    N,
    R,
    SR,
    SSR
}
