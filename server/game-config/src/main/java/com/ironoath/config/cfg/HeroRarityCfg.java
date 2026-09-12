// 由 tools/config-gen 依据 contract/config/hero_rarity.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 hero_rarity 的一行。
 * 武将稀有度经济表。一行 = 一个稀有度档：重复武将转多少碎片、合成需要多少碎片、每升一星需要多少碎片。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/hero_rarity.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record HeroRarityCfg(
        String id,   // 主键
        String name,
        long dupFragment,
        long composeFragment,
        long starUpFragment)
{
}
