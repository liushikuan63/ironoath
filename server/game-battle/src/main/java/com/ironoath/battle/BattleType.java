package com.ironoath.battle;

/**
 * 职责：战斗类型 —— 决定伤兵/死亡比例与是否允许掠夺。
 * 依赖：无。
 *
 * <p>B00 战斗结算的伤兵规则按类型区分：
 * <pre>
 *   PVE       死 20% / 伤 80%
 *   PVP 攻方  死 35% / 伤 65%
 *   PVP 守方  死 15% / 伤 85%
 * </pre>
 * 具体比例数值来自 contract/config/global.json，本枚举只负责选择用哪一组。
 */
public enum BattleType {
    /** 打野、关卡：死亡率最低，鼓励高频出手（核心短循环不能停摆）。 */
    PVE,
    /** 玩家单打：攻方死亡率高于守方，进攻是有代价的选择。 */
    PVP_SOLO,
    /** 集结作战：伤兵比例同 PVP_SOLO，但允许更多人参战。 */
    PVP_RALLY,
    /** 攻城战：防守方有城墙加成，攻城器在此类型下才发挥 vsBuildingBonus。 */
    SIEGE
}
