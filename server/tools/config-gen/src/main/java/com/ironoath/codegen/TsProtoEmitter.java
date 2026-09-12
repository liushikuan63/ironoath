package com.ironoath.codegen;

import java.util.ArrayList;
import java.util.List;

/**
 * 职责：把中立模型输出成 TypeScript 类型文件（字面量联合类型 + interface）。
 * 依赖：无（纯字符串拼接）。
 *
 * <p>与 {@link JavaProtoEmitter} 共用同一份 {@link ProtoSchema.Document}，
 * 因此双端类型必然一致（B00 跨语言一致性策略第 3 条）。
 *
 * <p>注意：这里生成的是<b>纯类型</b>，不含任何运行期逻辑，客户端不得在此实现数值判定（铁律 2）。
 */
public final class TsProtoEmitter {

    private TsProtoEmitter() {
    }

    /**
     * 生成一份 Schema 文档对应的 TS 模块。
     *
     * @return 文件名 → 内容
     */
    public static List<GeneratedFile> emit(ProtoSchema.Document doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                /**
                 * 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
                 * 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
                 *
                 * 本文件只有类型声明，不含任何运行期逻辑 —— 客户端不得在此实现影响数值或胜负的判断（铁律 2）。
                 */
                """);

        for (ProtoSchema.EnumDef e : doc.enums()) {
            sb.append('\n');
            appendTsDoc(sb, e.comment(), e.sourceTable() == null ? null
                    : "取值与配置表 " + e.sourceTable() + " 的 id 集合强制一致，由生成器在 CI 中校验。");
            sb.append("export type ").append(e.name()).append(" =\n");
            for (String value : e.values()) {
                sb.append("  | '").append(value).append("'\n");
            }
        }

        for (ProtoSchema.ObjectDef o : doc.objects()) {
            sb.append('\n');
            appendTsDoc(sb, o.comment(), null);
            sb.append("export interface ").append(o.name()).append(" {\n");
            for (ProtoSchema.Field f : o.fields()) {
                if (!f.comment().isBlank()) {
                    sb.append("  /** ").append(JavaProtoEmitter.oneLine(f.comment())).append(" */\n");
                }
                sb.append("  ").append(f.name()).append(": ").append(f.tsType()).append('\n');
            }
            sb.append("}\n");
        }

        List<GeneratedFile> files = new ArrayList<>();
        files.add(new GeneratedFile(doc.tsModule() + ".ts", sb.toString()));
        return files;
    }

    private static void appendTsDoc(StringBuilder sb, String comment, String extra) {
        boolean hasComment = comment != null && !comment.isBlank();
        boolean hasExtra = extra != null && !extra.isBlank();
        if (!hasComment && !hasExtra) {
            return;
        }
        sb.append("/**\n");
        if (hasComment) {
            for (String line : comment.split("\n")) {
                String trimmed = line.strip();
                sb.append(" *");
                if (!trimmed.isEmpty()) {
                    sb.append(' ').append(trimmed);
                }
                sb.append('\n');
            }
        }
        if (hasExtra) {
            if (hasComment) {
                sb.append(" *\n");
            }
            sb.append(" * ").append(extra).append('\n');
        }
        sb.append(" */\n");
    }

    /** 一个待写盘的生成文件。 */
    public record GeneratedFile(String fileName, String content) {
    }
}
