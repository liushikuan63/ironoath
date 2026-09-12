package com.ironoath.codegen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职责：把中立模型输出成 Java 源文件（enum + record）。
 * 依赖：无（纯字符串拼接）。
 *
 * <p>输出必须<b>字节稳定</b>：不含时间戳、不含随机顺序、不依赖平台换行符。
 * 因为 CI 用 {@code diff} 比对生成物与仓库内已提交的副本（scripts/check-contract-sync.sh），
 * 任何一次无意义的字节变化都会让检查失败，进而让人学会无视这个检查。
 */
public final class JavaProtoEmitter {

    /** 生成物统一头注释，说明职责与「不要手改」。 */
    static final String HEADER = """
            // 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
            // 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
            """;

    private JavaProtoEmitter() {
    }

    /**
     * 生成一份 Schema 文档对应的全部 Java 文件。
     *
     * @return 文件名（不含目录）→ 文件内容，按声明顺序
     */
    public static Map<String, String> emit(ProtoSchema.Document doc) {
        Map<String, String> files = new LinkedHashMap<>();
        for (ProtoSchema.EnumDef e : doc.enums()) {
            files.put(e.name() + ".java", emitEnum(doc, e));
        }
        for (ProtoSchema.ObjectDef o : doc.objects()) {
            files.put(o.name() + ".java", emitRecord(doc, o));
        }
        return files;
    }

    private static String emitEnum(ProtoSchema.Document doc, ProtoSchema.EnumDef def) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER);
        sb.append("package ").append(doc.javaPackage()).append(";\n\n");
        sb.append("/**\n");
        appendJavadocBody(sb, def.comment(), " ");
        if (def.sourceTable() != null) {
            sb.append(" *\n");
            sb.append(" * <p>取值与配置表 {@code ").append(def.sourceTable())
                    .append("} 的 id 集合强制一致，由生成器在 CI 中校验。\n");
        }
        sb.append(" */\n");
        sb.append("public enum ").append(def.name()).append(" {\n");
        for (int i = 0; i < def.values().size(); i++) {
            sb.append("    ").append(def.values().get(i));
            sb.append(i == def.values().size() - 1 ? "\n" : ",\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String emitRecord(ProtoSchema.Document doc, ProtoSchema.ObjectDef def) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER);
        sb.append("package ").append(doc.javaPackage()).append(";\n\n");

        boolean needList = def.fields().stream().anyMatch(f -> f.javaType().startsWith("List<"));
        boolean needMap = def.fields().stream().anyMatch(f -> f.javaType().startsWith("Map<"));
        if (needList) {
            sb.append("import java.util.List;\n");
        }
        if (needMap) {
            sb.append("import java.util.Map;\n");
        }
        if (needList || needMap) {
            sb.append('\n');
        }

        sb.append("/**\n");
        appendJavadocBody(sb, def.comment(), " ");
        sb.append(" */\n");
        sb.append("public record ").append(def.name()).append("(\n");
        for (int i = 0; i < def.fields().size(); i++) {
            ProtoSchema.Field f = def.fields().get(i);
            sb.append("        ").append(f.javaType()).append(' ').append(f.name());
            sb.append(i == def.fields().size() - 1 ? ")" : ",");
            if (!f.comment().isBlank()) {
                sb.append("   // ").append(oneLine(f.comment()));
            }
            sb.append('\n');
        }
        sb.append("{\n}\n");
        return sb.toString();
    }

    /** 把说明文本写进 Javadoc，逐行加 {@code  *} 前缀；空说明写一行占位。 */
    static void appendJavadocBody(StringBuilder sb, String comment, String indent) {
        if (comment == null || comment.isBlank()) {
            sb.append(indent).append("* （Schema 未提供说明）\n");
            return;
        }
        for (String line : comment.split("\n")) {
            String trimmed = line.strip();
            sb.append(indent).append('*');
            if (!trimmed.isEmpty()) {
                sb.append(' ').append(trimmed);
            }
            sb.append('\n');
        }
    }

    /** 注释压成一行，避免破坏生成代码的结构。 */
    static String oneLine(String text) {
        return text.replaceAll("\\s*\\n\\s*", " ").strip();
    }
}
