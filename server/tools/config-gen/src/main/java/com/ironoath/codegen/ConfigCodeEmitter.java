package com.ironoath.codegen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：把配置表模型输出成 Java record 与 TS interface（B02：禁止手写配置类型）。
 * 依赖：无（纯字符串拼接）。
 *
 * <p>与 {@link JavaProtoEmitter} / {@link TsProtoEmitter} 同样要求<b>字节稳定</b>：
 * 不含时间戳、字段顺序跟随 fieldTypes 的声明顺序，CI 靠 diff 校验同步性。
 *
 * <p>生成的 Java 类型是不可变 record（B02 要求：「生成的 Java POJO 为不可变对象」），
 * 定点字段的 Javadoc 明确标注它是 ×10000 的 long，防止后来者当成真实值直接用。
 */
public final class ConfigCodeEmitter {

    private static final String JAVA_PACKAGE = "com.ironoath.config.cfg";

    private ConfigCodeEmitter() {
    }

    // ---------- Java ----------

    /**
     * 生成一张表的 Java 文件。
     *
     * @return 文件名 → 内容
     */
    public static Map<String, String> emitJava(ConfigTableSchema.Table table) {
        StringBuilder sb = new StringBuilder();
        sb.append(JavaProtoEmitter.HEADER.replace(
                "contract/proto/ 下的 JSON Schema",
                "contract/config/" + table.name() + ".json（表 version=" + table.version() + "）"));
        sb.append("package ").append(JAVA_PACKAGE).append(";\n\n");

        boolean needJsonNode = table.fields().stream().anyMatch(f -> "JsonNode".equals(f.javaType()));
        boolean hasFixedPoint = table.fields().stream().anyMatch(ConfigTableSchema.Field::fixedPoint);
        if (needJsonNode) {
            sb.append("import com.fasterxml.jackson.databind.JsonNode;\n");
        }
        if (hasFixedPoint) {
            sb.append("import com.fasterxml.jackson.databind.annotation.JsonDeserialize;\n");
            sb.append("import com.ironoath.common.json.FixedPointDeserializer;\n");
        }
        if (needJsonNode || hasFixedPoint) {
            sb.append('\n');
        }

        sb.append("/**\n");
        JavaProtoEmitter.appendJavadocBody(sb, describe(table), " ");
        sb.append(" *\n");
        sb.append(" * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/")
                .append(table.name()).append(".json} 的 fieldTypes 后运行 {@code npm run gen}。\n");
        if (hasFixedPoint) {
            sb.append(" *\n");
            sb.append(" * <p>标注为「定点数」的字段是真实值 ×10000 的 long（见 FixedPoint），\n");
            sb.append(" * 配置表里写成十进制字符串，加载时由 FixedPointDeserializer 转成定点。<b>不要把它当真实值直接比较或输出</b>。\n");
        }
        sb.append(" */\n");
        sb.append("public record ").append(table.javaName()).append("(\n");

        List<ConfigTableSchema.Field> fields = table.fields();
        for (int i = 0; i < fields.size(); i++) {
            ConfigTableSchema.Field f = fields.get(i);
            if (f.fixedPoint()) {
                sb.append("        @JsonDeserialize(using = FixedPointDeserializer.class)\n");
            }
            sb.append("        ").append(f.javaType()).append(' ').append(f.name());
            sb.append(i == fields.size() - 1 ? ")" : ",");
            String comment = f.fixedPoint()
                    ? (f.comment().isBlank() ? "定点数（真实值 ×10000）" : f.comment())
                    : f.comment();
            if (!comment.isBlank()) {
                sb.append("   // ").append(JavaProtoEmitter.oneLine(comment));
            }
            sb.append('\n');
        }
        sb.append("{\n");

        for (ConfigTableSchema.EnumModel e : table.enums()) {
            sb.append("    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */\n");
            sb.append("    public enum ").append(e.javaName()).append(" {\n");
            List<String> values = e.values();
            for (int i = 0; i < values.size(); i++) {
                sb.append("        ").append(values.get(i));
                sb.append(i == values.size() - 1 ? "\n" : ",\n");
            }
            sb.append("    }\n\n");
        }
        sb.append("}\n");

        Map<String, String> files = new LinkedHashMap<>();
        files.put(table.javaName() + ".java", sb.toString());
        return files;
    }

    // ---------- TypeScript ----------

    /**
     * 生成全部配置表类型的单一 TS 模块。
     *
     * <p>合成一个文件而不是每表一个：客户端 import 路径短，且 Cocos 的资源数据库里
     * 少几百个小文件能明显缩短编辑器导入时间。
     */
    public static String emitTs(List<ConfigTableSchema.Table> tables) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                /**
                 * 由 tools/config-gen 依据 contract/config/*.json 的 fieldTypes 自动生成，禁止手改。
                 * 要改表结构请改 JSON 后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
                 *
                 * ⚠️ 标注「定点数」的字段是真实值 ×10000 的整数（见 core/FixedPoint），
                 *   不要当成真实值直接显示或比较。
                 *
                 * ⚠️ 铁律 2：客户端不得用这些配置做任何影响数值或胜负的判断。
                 *   配置在客户端只用于展示文案、图标索引与输入提示；服务端会独立重新校验。
                 */
                """);

        for (ConfigTableSchema.Table table : tables) {
            for (ConfigTableSchema.EnumModel e : table.enums()) {
                sb.append('\n');
                sb.append("/** ").append(table.name()).append(".").append(lowerFirst(e.tsName()))
                        .append(" 的合法取值，与配置表 fieldTypes 的 ENUM 声明完全一致。 */\n");
                sb.append("export type ").append(e.tsName()).append(" =\n");
                for (String value : e.values()) {
                    sb.append("  | '").append(value).append("'\n");
                }
            }

            sb.append('\n');
            sb.append("/**\n");
            for (String line : describe(table).split("\n")) {
                sb.append(" *").append(line.isBlank() ? "" : " " + line.strip()).append('\n');
            }
            sb.append(" *\n");
            sb.append(" * 源表 version=").append(table.version()).append('\n');
            sb.append(" */\n");
            sb.append("export interface ").append(table.tsName()).append(" {\n");
            for (ConfigTableSchema.Field f : table.fields()) {
                String comment = f.fixedPoint()
                        ? (f.comment().isBlank() ? "定点数（真实值 ×10000）" : f.comment())
                        : f.comment();
                if (!comment.isBlank()) {
                    sb.append("  /** ").append(JavaProtoEmitter.oneLine(comment)).append(" */\n");
                }
                sb.append("  ").append(f.name());
                if (f.optional()) {
                    sb.append('?');
                }
                sb.append(": ").append(f.tsType()).append('\n');
            }
            sb.append("}\n");
        }
        return sb.toString();
    }

    private static String describe(ConfigTableSchema.Table table) {
        String base = "配置表 " + table.name() + " 的一行。";
        if (table.comment().isBlank()) {
            return base;
        }
        return base + "\n" + table.comment();
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** Java 侧生成物的包名，供 ContractGenMain 拼接输出路径。 */
    public static String javaPackage() {
        return JAVA_PACKAGE;
    }

    /** TS 侧生成物的模块名。 */
    public static String tsModuleName() {
        return "ConfigTypes";
    }
}
