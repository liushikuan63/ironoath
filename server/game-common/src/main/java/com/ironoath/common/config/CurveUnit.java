package com.ironoath.common.config;

/**
 * 职责：曲线结果的量纲，供上层决定如何解释数值（秒 / 定点量 / 每小时定点量 / 纯计数）。
 * 依赖：无。
 */
public enum CurveUnit {

    /** 结果单位是秒。建筑时间、科技时间。 */
    SECOND,

    /** 结果是定点数量（无时间维度）。建筑消耗、战力贡献、兵种强度、武将成长、关卡难度。 */
    FIXED,

    /** 结果是每小时定点产量。建筑产出。 */
    FIXED_PER_HOUR,

    /** 结果是纯计数（不放大）。 */
    COUNT
}
