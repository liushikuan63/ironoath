package com.ironoath.codegen;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 职责：把 contract/config/*.json 的 fieldTypes 声明解析成配置表代码模型（B02 交付）。
 * 依赖：无（纯数据 + Jackson 节点读取）。
 *
 * <p>B02 禁止项：「不要手写配置类型，Java POJO 与 TS interface 必须由 tools/config-gen
 * 从同一份 JSON 生成」。本类与 {@link ProtoSchema} 一样先解析成中立模型，
 * 再分语言输出，因此双端类型必然来自同一次解析。
 *
 * <h2>规则 → 类型映射</h2>
 * <pre>
 *   STRING / STRING_KEY / REF:x  → String      / string
 *   LONG / LONG_NONNEG / LONG_POS → long       / number（可选时为 Long / number|null）
 *   DECIMAL / DECIMAL_NONNEG      → long(定点) / number（可选时为 Long / number|null）
 *   BOOL                          → boolean    / boolean
 *   ENUM:A,B                      → 嵌套 enum  / 字面量联合类型
 *   JSON                          → JsonNode   / unknown
 * </pre>
 *
 * <p><b>DECIMAL 生成为 long 而不是 double</b>：配置里写 {@code "1.18"}，
 * 生成的字段是放大 10000 倍的定点 long（11800）。这是「禁止 float/double 参与结算」
 * 在类型系统层面的落地 —— 拿不到 double，就不可能用 double 算。
 */
public final class ConfigTableSchema {

    /** 纯文档字段，不进入生成的 POJO：它们服务于「数值可解释」（C00 公理三）与 CI 校验，不是运行期数据。 */
    private static final Set<String> DOC_ONLY_FIELDS = Set.of("why", "todo", "source");

    /**
     * 不参与代码生成的表 —— 键值型参数表。
     *
     * <p>这类表的共同特征是 {@code value} 的类型由同行 {@code valueType} 决定（校验规则用 AUTO），
     * 因此无法映射成字段固定的 record；它们由 {@code GlobalCfg} 这类手工建模的类型承载，
     * 并通过 {@code GlobalParamSource} 端口按 id 取值。语义上它们也不是「一行一条配置」，
     * 而是「一个参数一行」，用强类型 record 反而会丢掉按类型取值的能力。
     */
    private static final Set<String> EXCLUDED_TABLES = Set.of("global", "city_rule");

    /** 一个生成字段。 */
    public record Field(String name, String javaType, String tsType, boolean optional,
                        boolean fixedPoint, String comment) {
    }

    /** 一个枚举（Java 侧生成为嵌套 enum，TS 侧生成为模块级联合类型）。 */
    public record EnumModel(String javaName, String tsName, List<String> values, String comment) {
        public EnumModel {
            values = List.copyOf(values);
        }
    }

    /** 一张表的生成模型。 */
    public record Table(String name, String javaName, String tsName, int version, String status,
                        List<Field> fields, List<EnumModel> enums, String comment) {
        public Table {
            fields = List.copyOf(fields);
            enums = List.copyOf(enums);
        }

        /** 预留表：只建结构不填数据，加载器与校验器对它放宽「rows 不得为空」的约束。 */
        public boolean isReserved() {
            return "RESERVED".equals(status);
        }
    }

    private ConfigTableSchema() {
    }

    /** 该表是否需要生成代码。 */
    public static boolean isGeneratable(String tableName) {
        return !EXCLUDED_TABLES.contains(tableName);
    }

    /** 解析一张表。 */
    public static Table parse(String tableName, JsonNode root) {
        if (!isGeneratable(tableName)) {
            throw new CodeGenException("表 " + tableName + " 不参与代码生成");
        }
        JsonNode fieldTypes = root.get("fieldTypes");
        if (fieldTypes == null || !fieldTypes.isObject()) {
            throw new CodeGenException("表[" + tableName + "] 缺少 fieldTypes，无法生成类型");
        }
        int version = root.hasNonNull("version") ? root.get("version").asInt() : 0;
        String status = root.hasNonNull("status") ? root.get("status").asText() : "ACTIVE";
        String tableComment = root.hasNonNull("comment") ? root.get("comment").asText() : "";

        String pascal = pascalCase(tableName);
        List<EnumModel> enums = new ArrayList<>();
        List<Field> fields = new ArrayList<>();

        Iterator<Map.Entry<String, JsonNode>> it = fieldTypes.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String fieldName = e.getKey();
            if ("id".equals(fieldName)) {
                // id 恒定为主键字符串，不参与枚举推导
                fields.add(new Field("id", "String", "string", false, false, "主键"));
                continue;
            }
            if (DOC_ONLY_FIELDS.contains(fieldName)) {
                continue;
            }
            if (!e.getValue().isTextual()) {
                throw new CodeGenException("表[" + tableName + "] 字段 " + fieldName + " 的规则不是字符串");
            }
            fields.add(parseField(tableName, pascal, fieldName, e.getValue().asText(), enums));
        }

        if (fields.isEmpty()) {
            throw new CodeGenException("表[" + tableName + "] 没有可生成的字段（全部是文档字段？）");
        }
        return new Table(tableName, pascal + "Cfg", pascal + "Cfg", version, status,
                fields, enums, tableComment);
    }

    private static Field parseField(String tableName, String tablePascal, String fieldName,
                                    String spec, List<EnumModel> enums) {
        String rule = spec.trim();
        boolean optional = false;
        if (rule.startsWith("?")) {
            optional = true;
            rule = rule.substring(1).trim();
        }
        String kind = rule;
        String arg = null;
        int colon = rule.indexOf(':');
        if (colon >= 0) {
            kind = rule.substring(0, colon).trim();
            arg = rule.substring(colon + 1).trim();
        }

        return switch (kind) {
            case "STRING", "STRING_KEY" -> new Field(fieldName, "String", "string", optional, false,
                    "STRING_KEY".equals(kind) ? "主键型标识符，只含字母数字下划线" : "");
            case "REF" -> new Field(fieldName, "String", "string", optional, false,
                    "外键，指向 " + arg + " 表的 id");
            case "LONG", "LONG_NONNEG", "LONG_POS" -> new Field(fieldName,
                    optional ? "Long" : "long", optional ? "number | null" : "number", optional, false, "");
            case "DECIMAL", "DECIMAL_NONNEG" -> new Field(fieldName,
                    optional ? "Long" : "long", optional ? "number | null" : "number", optional, true,
                    "定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double");
            case "BOOL" -> new Field(fieldName,
                    optional ? "Boolean" : "boolean", optional ? "boolean | null" : "boolean", optional, false, "");
            case "ENUM" -> {
                if (arg == null || arg.isBlank()) {
                    throw new CodeGenException("表[" + tableName + "] 字段 " + fieldName + " 的 ENUM 规则缺少取值");
                }
                Set<String> values = new LinkedHashSet<>();
                for (String v : arg.split(",")) {
                    if (!v.isBlank()) {
                        values.add(v.trim());
                    }
                }
                String fieldPascal = pascalCase(fieldName);
                // Java 侧嵌套在 Cfg 内，用短名即可；TS 侧是模块级类型，必须带表名前缀避免撞名
                String javaName = fieldPascal;
                String tsName = tablePascal + fieldPascal;
                enums.add(new EnumModel(javaName, tsName, new ArrayList<>(values), ""));
                yield new Field(fieldName, javaName, tsName, optional, false, "枚举，取值见 " + tsName);
            }
            case "JSON" -> new Field(fieldName, "JsonNode", "unknown", optional, false, "任意结构");
            case "AUTO" -> throw new CodeGenException("表[" + tableName + "] 字段 " + fieldName
                    + " 使用 AUTO 规则（类型由同行其它字段决定），无法生成静态类型。"
                    + "这类表应排除在代码生成之外（见 EXCLUDED_TABLES）");
            default -> throw new CodeGenException("表[" + tableName + "] 字段 " + fieldName
                    + " 使用了生成器不认识的规则: " + kind);
        };
    }

    /** {@code mapmonster} → {@code Mapmonster}；{@code alliance_tech} → {@code AllianceTech}。 */
    static String pascalCase(String snake) {
        StringBuilder sb = new StringBuilder();
        for (String part : snake.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
