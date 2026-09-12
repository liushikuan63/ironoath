package com.ironoath.common.config;

/**
 * 职责：成长曲线的形态枚举。
 * 依赖：无。
 *
 * <p>放在 game-common 而非 game-config，是为了让 game-core 的 Formula 能引用它而不违反
 * 「game-core 只能依赖 game-common」的分层规则（依赖倒置，见 {@link CurveSource}）。
 */
public enum CurveKind {

    /** 几何增长：value(n) = base × ratio^(n-1)。用于时间、消耗、科技、兵种强度、关卡难度。 */
    GEOMETRIC,

    /** 幂律增长：value(n) = base × n^exponent。用于产出、战力贡献、武将成长。 */
    POWER
}
