// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 三维属性。base 是配置表原值，final 是养成后的值（等级/星级/觉醒/装备共同作用）。
 */
public record AttrTriple(
        long might,
        long command,
        long wisdom)
{
}
