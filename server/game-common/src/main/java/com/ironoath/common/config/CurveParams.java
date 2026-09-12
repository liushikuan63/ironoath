package com.ironoath.common.config;

/**
 * 职责：一条成长曲线的参数 —— 「基数 + 系数 + 指数」三元组，全部为定点 long。
 * 依赖：无（纯数据）。
 *
 * <p>跨语言一致性策略第 4 条：曲线在配置表中以这三个数表达，双端都只读配置套同一条简单公式，
 * 禁止 Java 与 TS 各自硬编码曲线。本 record 就是那条「同一条公式」的输入契约。
 *
 * @param id            曲线 id，对应 contract/config/curve.json 的 rows[].id，如 BUILDING_TIME
 * @param kind          曲线形态
 * @param baseFixed     基数（定点）。为 0 表示基数由具体业务表逐行提供（如每种建筑有自己的 C0）
 * @param ratioFixed    系数（定点），GEOMETRIC 用。如 1.18 ⇒ 11800
 * @param exponentFixed 指数（定点），POWER 用。如 1.08 ⇒ 10800
 * @param unit          结果量纲
 */
public record CurveParams(
        String id,
        CurveKind kind,
        long baseFixed,
        long ratioFixed,
        long exponentFixed,
        CurveUnit unit) {

    public CurveParams {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("CurveParams.id 不得为空");
        }
        if (kind == null) {
            throw new IllegalArgumentException("CurveParams.kind 不得为 null，曲线 id=" + id);
        }
        if (unit == null) {
            throw new IllegalArgumentException("CurveParams.unit 不得为 null，曲线 id=" + id);
        }
    }

    /** 基数是否由业务表逐行提供（而非曲线自带）。 */
    public boolean baseProvidedExternally() {
        return baseFixed == 0L;
    }
}
