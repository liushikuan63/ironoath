// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一次抽取的结果。isPity 是合规字段（B06 禁止项：抽卡日志不要缺 isPity），必须下发到客户端并存进日志 —— 监管要能区分「正常抽到」与「保底触发」。
 */
public record GachaResult(
        String heroId,
        String name,
        HeroRarity rarity,
        boolean isNew,   // 是否首次获得。false 表示重复，已按 hero_rarity.dupFragment 转成碎片
        boolean isPity,   // 本次是否由保底触发（合规必需字段）
        long fragments)   // 本次产生的碎片数；isNew=true 时为 0
{
}
