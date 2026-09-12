package com.ironoath.common.config;

/**
 * 职责：曲线参数的<b>来源端口</b>（依赖倒置的抽象侧）。
 * 依赖：无。
 *
 * <p><b>为什么这个接口在 game-common</b>：B00 分层规则要求 {@code game-core → 只能依赖 game-common}，
 * 而 C00 公理三要求「所有曲线走 game-core 的 Formula，系数与指数从配置表读取」。
 * 两者看似冲突，解法是把契约下沉到 game-common：
 * <ul>
 *   <li>game-common 定义本接口（抽象）</li>
 *   <li>game-config 的 ConfigRegistry 实现它（读 contract/config/curve.json）</li>
 *   <li>game-core 的 Formula 只依赖本接口</li>
 * </ul>
 * 于是 game-core 既拿到了配置驱动能力，又没有依赖 game-config，单测时可直接传一个内存实现，
 * 无需加载任何配置文件（C00 公理四·五：核心逻辑必须能脱离容器跑）。
 */
public interface CurveSource {

    /**
     * 按 id 取曲线参数。
     *
     * @param curveId 曲线 id
     * @return 曲线参数，<b>绝不返回 null</b>
     * @throws com.ironoath.common.BizException 或 ConfigException 当 id 不存在
     */
    CurveParams curve(String curveId);

    /** 判断曲线是否存在，供上层做可选曲线的降级处理。 */
    boolean hasCurve(String curveId);
}
