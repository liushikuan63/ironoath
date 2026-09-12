package com.ironoath.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.RawConfigTable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 职责：代码生成器命令行入口 —— contract/proto/*.schema.json → Java DTO + TS interface。
 * 依赖：game-common（JsonUtils）、game-config（生成前顺带跑配置表全量校验）。
 *
 * <p>用法（由 scripts/gen.sh 调用，勿手工拼参数）：
 * <pre>
 *   mvn -pl tools/config-gen -am exec:java \
 *     -Dexec.mainClass=com.ironoath.codegen.ContractGenMain \
 *     -Dexec.args="--schema-dir=contract/proto --config-dir=contract/config \
 *                  --java-out=server/game-web/src/main/java/com/ironoath/web/dto/generated \
 *                  --ts-out=client/assets/scripts/net/generated"
 * </pre>
 *
 * <p>顺带做的两件校验（都是 CI 门禁，失败即非零退出）：
 * <ol>
 *   <li>配置表全量校验：调用 {@link ConfigRegistry#loadFromDirectory}，任一处错误即失败并列出全部</li>
 *   <li>枚举与配置表一致性：Schema 中声明了 {@code x-enum-source} 的枚举，
 *       其取值必须与对应配置表的 id 集合完全相同（防止「加了资源但忘了改协议」）</li>
 * </ol>
 *
 * <p>B02 会在本类基础上补齐 xlsx → JSON → 配置表 POJO 的完整链路。
 */
public final class ContractGenMain {

    private ContractGenMain() {
    }

    public static void main(String[] args) {
        Map<String, String> options = parseArgs(args);
        Path schemaDir = requirePath(options, "schema-dir");
        Path javaOut = requirePath(options, "java-out");
        Path tsOut = requirePath(options, "ts-out");
        Path configDir = options.containsKey("config-dir") ? Path.of(options.get("config-dir")) : null;
        Path javaCfgOut = options.containsKey("java-cfg-out") ? Path.of(options.get("java-cfg-out")) : null;
        Path tsCfgOut = options.containsKey("ts-cfg-out") ? Path.of(options.get("ts-cfg-out")) : null;

        try {
            List<ProtoSchema.Document> docs = loadSchemas(schemaDir);

            ConfigRegistry configs = null;
            if (configDir != null) {
                System.out.println("[config-gen] 校验配置表: " + configDir);
                configs = ConfigRegistry.loadFromDirectory(configDir);
                System.out.println("[config-gen] 配置表校验通过，指纹=" + configs.fingerprint());
            }
            verifyEnumSources(docs, configs);

            int javaCount = writeJava(docs, javaOut);
            int tsCount = writeTs(docs, tsOut);
            System.out.println("[config-gen] 协议生成完成：Java " + javaCount + " 个文件 → " + javaOut
                    + "；TS " + tsCount + " 个文件 → " + tsOut);

            if (configDir != null && javaCfgOut != null && tsCfgOut != null) {
                int cfgCount = writeConfigTypes(configDir, javaCfgOut, tsCfgOut);
                System.out.println("[config-gen] 配置表类型生成完成：" + cfgCount + " 张表 → "
                        + javaCfgOut + " / " + tsCfgOut);
            }
        } catch (CodeGenException | com.ironoath.config.ConfigException e) {
            System.err.println("[config-gen][FAIL] " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 依据每张表的 fieldTypes 生成 Java record 与 TS interface（B02：禁止手写配置类型）。
     *
     * @return 生成的表数量
     */
    private static int writeConfigTypes(Path configDir, Path javaOut, Path tsOut) {
        List<Path> files;
        try (Stream<Path> stream = Files.list(configDir)) {
            files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return !n.startsWith("_") && !n.startsWith(".");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取配置目录失败: " + configDir, e);
        }

        cleanDirectory(javaOut, ".java");
        cleanDirectory(tsOut, ".ts");

        List<ConfigTableSchema.Table> tables = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            name = name.substring(0, name.length() - ".json".length());
            if (!ConfigTableSchema.isGeneratable(name)) {
                System.out.println("[config-gen] 跳过不参与生成的表: " + name);
                continue;
            }
            JsonNode root;
            try {
                root = JsonUtils.readTree(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("读取配置表失败: " + file, e);
            }
            ConfigTableSchema.Table table = ConfigTableSchema.parse(name, root);
            tables.add(table);
            for (Map.Entry<String, String> e : ConfigCodeEmitter.emitJava(table).entrySet()) {
                write(javaOut.resolve(e.getKey()), e.getValue());
            }
        }
        write(tsOut.resolve(ConfigCodeEmitter.tsModuleName() + ".ts"), ConfigCodeEmitter.emitTs(tables));
        return tables.size();
    }

    // ---------- 参数 ----------

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new java.util.LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new CodeGenException("无法识别的参数: " + arg + "（期望形式 --key=value）");
            }
            String key = arg.substring(2, arg.indexOf('='));
            String value = arg.substring(arg.indexOf('=') + 1);
            if (value.isBlank()) {
                throw new CodeGenException("参数 --" + key + " 的值不得为空");
            }
            options.put(key, value);
        }
        return options;
    }

    private static Path requirePath(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new CodeGenException("缺少必需参数 --" + key);
        }
        return Path.of(value);
    }

    // ---------- 载入 Schema ----------

    private static List<ProtoSchema.Document> loadSchemas(Path schemaDir) {
        if (!Files.isDirectory(schemaDir)) {
            throw new CodeGenException("Schema 目录不存在: " + schemaDir.toAbsolutePath());
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(schemaDir)) {
            files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".schema.json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取 Schema 目录失败: " + schemaDir, e);
        }
        if (files.isEmpty()) {
            throw new CodeGenException("Schema 目录中没有任何 *.schema.json: " + schemaDir.toAbsolutePath());
        }
        List<ProtoSchema.Document> docs = new ArrayList<>();
        for (Path file : files) {
            String fileName = "contract/proto/" + file.getFileName();
            JsonNode root;
            try {
                root = JsonUtils.readTree(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("读取 Schema 失败: " + file, e);
            }
            docs.add(ProtoSchema.parse(fileName, root));
        }
        return docs;
    }

    // ---------- 枚举与配置表一致性 ----------

    private static void verifyEnumSources(List<ProtoSchema.Document> docs, ConfigRegistry configs) {
        for (ProtoSchema.Document doc : docs) {
            for (ProtoSchema.EnumDef def : doc.enums()) {
                if (def.sourceTable() == null) {
                    continue;
                }
                if (configs == null) {
                    throw new CodeGenException("枚举 " + def.name() + " 声明了 x-enum-source="
                            + def.sourceTable() + "，但未提供 --config-dir，无法校验一致性");
                }
                if (!configs.hasTable(def.sourceTable())) {
                    throw new CodeGenException("枚举 " + def.name() + " 的来源表不存在: " + def.sourceTable());
                }
                RawConfigTable table = configs.rawTable(def.sourceTable());
                Set<String> tableIds = new LinkedHashSet<>(table.ids());
                Set<String> enumValues = new LinkedHashSet<>(def.values());

                Set<String> missingInSchema = new LinkedHashSet<>(tableIds);
                missingInSchema.removeAll(enumValues);
                Set<String> missingInTable = new LinkedHashSet<>(enumValues);
                missingInTable.removeAll(tableIds);

                if (!missingInSchema.isEmpty() || !missingInTable.isEmpty()) {
                    StringBuilder sb = new StringBuilder("枚举 " + def.name() + " 与配置表 "
                            + def.sourceTable() + " 的 id 集合不一致：\n");
                    if (!missingInSchema.isEmpty()) {
                        sb.append("  配置表中有但 Schema 未声明: ").append(missingInSchema).append('\n');
                    }
                    if (!missingInTable.isEmpty()) {
                        sb.append("  Schema 中声明但配置表没有: ").append(missingInTable).append('\n');
                    }
                    sb.append("  请同步修改后重新运行 `npm run gen`。");
                    throw new CodeGenException(sb.toString());
                }
                System.out.println("[config-gen] 枚举一致性 OK: " + def.name()
                        + " ↔ " + def.sourceTable() + "（" + enumValues.size() + " 项）");
            }
        }
    }

    // ---------- 写盘 ----------

    private static int writeJava(List<ProtoSchema.Document> docs, Path outDir) {
        cleanDirectory(outDir, ".java");
        int count = 0;
        for (ProtoSchema.Document doc : docs) {
            for (Map.Entry<String, String> e : JavaProtoEmitter.emit(doc).entrySet()) {
                write(outDir.resolve(e.getKey()), e.getValue());
                count++;
            }
        }
        return count;
    }

    private static int writeTs(List<ProtoSchema.Document> docs, Path outDir) {
        cleanDirectory(outDir, ".ts");
        int count = 0;
        for (ProtoSchema.Document doc : docs) {
            for (TsProtoEmitter.GeneratedFile file : TsProtoEmitter.emit(doc)) {
                write(outDir.resolve(file.fileName()), file.content());
                count++;
            }
        }
        return count;
    }

    /**
     * 清理输出目录中同后缀的旧文件。
     *
     * <p>只删同后缀文件而不是整目录递归删除：生成目录可能被误配置到有源码的路径，
     * 递归删除会造成不可恢复的损失。删多了大不了重新生成，删错了别人的代码无法挽回。
     */
    private static void cleanDirectory(Path dir, String suffix) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> stale = stream
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .toList();
            for (Path p : stale) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("清理生成目录失败: " + dir, e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            // 显式 UTF-8 + LF：生成物要参与 diff 比对，换行符随平台变化会让 CI 无故失败
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入生成文件失败: " + file, e);
        }
    }
}
