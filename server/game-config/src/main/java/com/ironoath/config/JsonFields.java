package com.ironoath.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.num.FixedPoint;

/**
 * 职责：从配置行 JsonNode 中安全取值的工具 —— 缺字段/类型错一律抛异常，<b>绝不返回 null 或默认值</b>。
 * 依赖：game-common 的 FixedPoint、本模块的 ConfigException。
 *
 * <p>B01 要求：{@code get(id)} 找不到时抛异常，绝不返回 null。这个约束必须贯彻到字段级 ——
 * 返回 null 或默认值会让「配置漏填」静默变成一个错误的数值，直到线上才炸。
 */
public final class JsonFields {

    private JsonFields() {
    }

    /** 行定位描述，用于异常信息。 */
    private static String locate(JsonNode row, String field) {
        String id = (row != null && row.hasNonNull("id")) ? row.get("id").asText() : "<无id>";
        return "配置行[" + id + "].字段[" + field + "]";
    }

    public static JsonNode required(JsonNode row, String field) {
        JsonNode node = row == null ? null : row.get(field);
        if (node == null || node.isNull()) {
            throw new ConfigException(locate(row, field) + " 缺失");
        }
        return node;
    }

    public static String text(JsonNode row, String field) {
        JsonNode node = required(row, field);
        if (!node.isTextual()) {
            throw new ConfigException(locate(row, field) + " 必须是字符串，实际=" + node);
        }
        return node.asText();
    }

    /** 可选字符串：缺失或为 null 时返回默认值。仅用于说明性字段（why/todo/source）。 */
    public static String optionalText(JsonNode row, String field, String defaultValue) {
        JsonNode node = row == null ? null : row.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isTextual()) {
            throw new ConfigException(locate(row, field) + " 必须是字符串，实际=" + node);
        }
        return node.asText();
    }

    public static long longValue(JsonNode row, String field) {
        JsonNode node = required(row, field);
        if (!node.isIntegralNumber()) {
            throw new ConfigException(locate(row, field) + " 必须是整数，实际=" + node);
        }
        return node.asLong();
    }

    public static long optionalLong(JsonNode row, String field, long defaultValue) {
        JsonNode node = row == null ? null : row.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isIntegralNumber()) {
            throw new ConfigException(locate(row, field) + " 必须是整数，实际=" + node);
        }
        return node.asLong();
    }

    /**
     * 取小数字段并转定点 long。字段必须是十进制<b>字符串</b>（校验器已强制），
     * 这里再兜一次底，防止绕过校验器直接构造 JsonNode。
     */
    public static long decimalFixed(JsonNode row, String field) {
        JsonNode node = required(row, field);
        if (!node.isTextual()) {
            throw new ConfigException(locate(row, field)
                    + " 小数字段必须是十进制字符串（如 \"1.18\"），实际=" + node);
        }
        try {
            return FixedPoint.parse(node.asText());
        } catch (RuntimeException e) {
            throw new ConfigException(locate(row, field) + " 不是合法的定点小数：" + e.getMessage(), e);
        }
    }

    public static boolean boolValue(JsonNode row, String field) {
        JsonNode node = required(row, field);
        if (!node.isBoolean()) {
            throw new ConfigException(locate(row, field) + " 必须是布尔值，实际=" + node);
        }
        return node.asBoolean();
    }

    /** 枚举字段：字符串必须能映射到枚举常量名，否则抛异常并列出合法取值。 */
    public static <E extends Enum<E>> E enumValue(JsonNode row, String field, Class<E> type) {
        String raw = text(row, field);
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(locate(row, field) + " 取值非法：" + raw
                    + "，允许值=" + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }
}
