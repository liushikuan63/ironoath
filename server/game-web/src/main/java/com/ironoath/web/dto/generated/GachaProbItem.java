// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 概率面板的一行（B06 §2：客户端概率面板必须读同一份配置，禁止写死）。
 */
public record GachaProbItem(
        String heroId,
        String name,
        HeroRarity rarity,
        long rateFixed,   // 定点概率（×10000）。用定点数而不是 double（B06 禁止项：不要用 double 表示概率）
        boolean isUp)   // 是否为当期 UP 武将
{
}
