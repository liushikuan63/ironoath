// 由 tools/config-gen 依据 contract/config/building.json（表 version=4） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 building 的一行。
 * 建筑表。每建筑一行，只给「基数」；每级的耗时/消耗/产出/战力由 game-core 的 Formula 套 curve 表的曲线算出（铁律 6：新增建筑只改配置表，不改代码）。timeBaseSec=0 表示沿用 curve.BUILDING_TIME 自带的基数（30 秒）。costBase* 是 1→2 级的消耗，按 BUILDING_COST（比率 1.22）递增；outputBasePerHour 是 1 级产量，按 BUILDING_OUTPUT（指数 1.08）递增；powerBase 是 1 级战力贡献，按 POWER_CONTRIB（指数 1.15）递增。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/building.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record BuildingCfg(
        String id,   // 主键
        String name,
        Type type,   // 枚举，取值见 BuildingType
        long maxLevel,
        long timeBaseSec,
        long costBaseWood,
        long costBaseStone,
        long costBaseIron,
        long costBaseGrain,
        String outputResource,   // 外键，指向 resource 表的 id
        Long outputBasePerHour,
        long capBase,
        long woundedCapBase,
        long powerBase,
        Long powerLevelCap,
        String requireBuilding,   // 外键，指向 building 表的 id
        long requireMainLevel)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Type {
        CORE,
        RESOURCE,
        MILITARY,
        SCIENCE,
        DEFENSE,
        UTILITY
    }

}
