// 由 tools/config-gen 依据 contract/config/nation_config.json（表 version=2） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 nation_config 的一行。
 * 国家配置表（B13 交付数据）。按国家等级给出人数上限、官职数量、国库容量、国策槽位、宣战冷却。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/nation_config.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record NationConfigCfg(
        String id,   // 主键
        long nationLevel,
        long memberCap,
        long unlockMainLevel,
        long unlockDayOffset,
        long officeCount,
        long treasuryCap,
        long policySlotCount,
        long warCooldownHours)
{
}
