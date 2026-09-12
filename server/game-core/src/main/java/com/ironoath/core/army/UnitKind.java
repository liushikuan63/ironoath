package com.ironoath.core.army;

/**
 * 职责：兵种类型（game-core 侧）。
 * 依赖：无。
 *
 * <p><b>为什么不复用 {@code com.ironoath.battle.UnitType}</b>：
 * 分层规则限定 game-core 只能依赖 game-common（CI 的 check-layering.sh 会读 pom 校验），
 * 而战斗内核在 game-battle。让 core 反向依赖 battle 会形成环，
 * 也会让「军队状态」与「战斗结算」这两个本该独立演进的模块绑死。
 *
 * <p>所以这里定义一份同取值的枚举，由 game-web 在边界上按 {@code name()} 互转，
 * 一致性由 {@code BattleContractParityTest} 断言 —— 这与协议枚举、配置枚举之间
 * 已经在用的做法完全相同（三份定义、一组断言，而不是一个跨层依赖）。
 *
 * <p><b>声明顺序即「显式枚举顺序」</b>：B07 禁止项要求「不要用 HashMap 存兵种并依赖其迭代顺序，
 * 用 EnumMap + 固定枚举序」。所有按兵种遍历的地方都必须走 {@code values()}，
 * 这样「谁先被扣兵」「战报里兵种怎么排」在任何 JVM 上都一致。
 */
public enum UnitKind {
    INFANTRY,
    CAVALRY,
    ARCHER,
    SIEGE
}
