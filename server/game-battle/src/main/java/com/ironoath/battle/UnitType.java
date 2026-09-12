package com.ironoath.battle;

/**
 * 职责：兵种类型枚举 —— 战斗内核的兵种维度。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p>取值与 contract/config/unit.json 的 type 字段、unit_counter.json 的 attacker/defender
 * 字段完全一致。B00 的四兵种：重步兵 / 轻骑兵 / 弓兵 / 攻城器。
 *
 * <p><b>枚举声明顺序即遍历顺序</b>，这是确定性的一部分：内核用 EnumMap 并按 values() 顺序
 * 遍历求和，任何依赖 HashMap 迭代顺序的写法都会让同 seed 的战报在不同 JVM 上算出不同结果
 * （B05 验收 1、3）。禁止重排这里的常量顺序 —— 重排会改变所有历史战报的复算结果。
 */
public enum UnitType {
    /** 重步兵：高防高血、速度慢、负载高。克弓兵，被骑兵克。 */
    INFANTRY,
    /** 轻骑兵：高速、中攻、低防。克弓兵与攻城器，被步兵克。 */
    CAVALRY,
    /** 弓兵：高攻、低防低血、负载低。克步兵，被骑兵克。 */
    ARCHER,
    /** 攻城器：对兵极弱、对建筑极强、极慢。被骑兵克。 */
    SIEGE
}
