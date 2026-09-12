package com.ironoath.common.config;

/**
 * 职责：全局参数的<b>来源端口</b>（依赖倒置的抽象侧），对应 contract/config/global.json。
 * 依赖：无。
 *
 * <p>铁律 1「数值零硬编码」的落地方式：任何模块需要常量时，通过本端口按 id 取值，
 * 而不是在代码里写字面量。game-config 的 ConfigRegistry 是唯一实现。
 *
 * <p>与 {@link CurveSource} 同理放在 game-common，保证 game-core / game-battle 能在
 * 不依赖 game-config 的前提下做到配置驱动。
 */
public interface GlobalParamSource {

    /** 取 LONG 类型参数。类型不匹配或不存在即抛异常，绝不返回默认值。 */
    long longParam(String id);

    /**
     * 取 DECIMAL 类型参数并转成定点 long（×10000）。
     * 例如 PVP_POWER_MAX_RATIO = "2.0" ⇒ 返回 20000。
     */
    long fixedParam(String id);

    /** 取 BOOL 类型参数。 */
    boolean boolParam(String id);

    /** 取 STRING 类型参数。 */
    String stringParam(String id);

    /** 判断参数是否存在。 */
    boolean hasParam(String id);
}
