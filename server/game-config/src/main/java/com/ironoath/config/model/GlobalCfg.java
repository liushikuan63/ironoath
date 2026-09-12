package com.ironoath.config.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.JsonFields;

/**
 * 职责：global 表的强类型行对象 —— 带类型标记的全局键值参数。
 * 依赖：game-common 的 FixedPoint，game-config 的 JsonFields。
 *
 * <p>铁律 1 的落地容器：所有跨批次共用常量（定点倍数、战力区间、伤兵比例、反击加成…）都在这里，
 * 代码中不允许出现同义魔法数字。
 *
 * <p>{@code source} 与 {@code why} 是<b>强制</b>的：C00 公理三要求「随机抽 10 个配置数值，
 * 问为什么是这个数，答不出来就是错的」。没有出处的数字不许进表。
 *
 * @param id        参数 id，如 PVP_POWER_MAX_RATIO
 * @param valueType 值类型标记，决定 value 的 JSON 类型
 * @param value     原始值节点
 * @param unit      量纲说明（秒/倍/比率/战力…）
 * @param source    该数值的出处（B00/C00/批次文档/设计意图）
 * @param why       取值理由
 * @param todo      尚未定稿的说明，空字符串表示已定稿
 */
public record GlobalCfg(
        String id,
        ValueType valueType,
        JsonNode value,
        String unit,
        String source,
        String why,
        String todo) {

    public static GlobalCfg from(JsonNode row) {
        return new GlobalCfg(
                JsonFields.text(row, "id"),
                JsonFields.enumValue(row, "valueType", ValueType.class),
                JsonFields.required(row, "value"),
                JsonFields.text(row, "unit"),
                JsonFields.text(row, "source"),
                JsonFields.optionalText(row, "why", ""),
                JsonFields.optionalText(row, "todo", ""));
    }

    /** 该参数是否尚未定稿（带 TODO(需确认) 标记）。启动日志会把它们全部列出来提醒。 */
    public boolean isPendingConfirmation() {
        return !todo.isBlank();
    }

    public long asLong() {
        requireType(ValueType.LONG);
        return value.asLong();
    }

    /** DECIMAL → 定点 long（×10000）。 */
    public long asFixed() {
        requireType(ValueType.DECIMAL);
        return FixedPoint.parse(value.asText());
    }

    public boolean asBool() {
        requireType(ValueType.BOOL);
        return value.asBoolean();
    }

    public String asString() {
        requireType(ValueType.STRING);
        return value.asText();
    }

    private void requireType(ValueType expected) {
        if (valueType != expected) {
            throw new IllegalStateException("全局参数[" + id + "]的类型是 " + valueType
                    + "，不能按 " + expected + " 读取。请检查调用处是否取错了参数。");
        }
    }

    /** 全局参数的值类型。与 JSON 书写形式的对应关系见 contract/README.md。 */
    public enum ValueType {
        /** JSON 整数。 */
        LONG,
        /** JSON 十进制字符串，如 "1.18"。 */
        DECIMAL,
        /** JSON 布尔。 */
        BOOL,
        /** JSON 字符串。 */
        STRING
    }
}
