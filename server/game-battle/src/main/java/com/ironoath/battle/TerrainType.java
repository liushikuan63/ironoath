package com.ironoath.battle;

/**
 * 职责：地形类型 —— 乘区 E 的来源。
 * 依赖：无。
 *
 * <p>地形加成由调用方（game-world，B07 大地图）解析后通过 {@link BattleRules} 传入具体数值，
 * 本枚举只用于标识与日志，内核不在此处硬编码任何加成数字（铁律 1）。
 */
public enum TerrainType {
    /** 平原：无加成，基准地形。 */
    PLAIN,
    /** 山地：利于防守与弓兵。 */
    MOUNTAIN,
    /** 森林：利于伏击与步兵。 */
    FOREST,
    /** 河流：渡河方劣势。 */
    RIVER,
    /** 城下：攻城战，防守方有城墙加成。 */
    CITY_WALL
}
