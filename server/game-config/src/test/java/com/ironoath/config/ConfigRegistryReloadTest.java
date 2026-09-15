package com.ironoath.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置表热更的证据（B16 §5 / 验收 7：修改配置表后客户端拉取新版本，不改包生效）。
 *
 * <p><b>这条链路此前是断的</b>：`reload(单表)` 早就写好并有用例，但它在生产代码里**零调用** ——
 * 改一张表只能重启。本测试盯的是新加的全量热更，两条性质各一条用例：
 * 改完真的生效、坏表不会把服务打成半套配置。
 *
 * <p><b>为什么用真实表做夹具</b>：热更判定与业务读取之间的关系（global 索引要不要一起换）
 * 只有拿真表才看得出来；自造一张两行的小表验不出「global 的强类型索引没跟着换」这类事故。
 */
class ConfigRegistryReloadTest {

    /** 从工作目录往上找仓库根：surefire 的 cwd 是模块目录，写死相对路径在别处会解析失败。 */
    private static Path locateRepoConfigDir() {
        for (Path cursor = Path.of("").toAbsolutePath(); cursor != null; cursor = cursor.getParent()) {
            Path candidate = cursor.resolve("contract/config");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到 contract/config 目录");
    }

    /** 把真实配置目录复制到临时目录：热更要改文件，不能在仓库里动手。 */
    private static Path copyOfRealConfig(Path tempDir) throws IOException {
        Path source = locateRepoConfigDir();
        Path target = tempDir.resolve("config");
        Files.createDirectories(target);
        try (Stream<Path> files = Files.list(source)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return target;
    }

    private static void rewriteParam(Path dir, String table, String paramId, long newValue) throws IOException {
        Path file = dir.resolve(table + ".json");
        String json = Files.readString(file, StandardCharsets.UTF_8);
        int at = json.indexOf("\"id\": \"" + paramId + "\"");
        assertThat(at).as("夹具里找不到参数 %s（表变了就改这里）", paramId).isGreaterThan(0);
        int valueAt = json.indexOf("\"value\":", at);
        String tail = json.substring(valueAt);
        String updated = tail.replaceFirst("\"value\":\\s*\\d+", "\"value\": " + newValue);
        Files.writeString(file, json.substring(0, valueAt) + updated, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("改表之后不重启就生效：数值读出来是新的")
    void reloadAppliesNewValuesWithoutRestart(@TempDir Path tempDir) throws Exception {
        Path dir = copyOfRealConfig(tempDir);
        ConfigRegistry registry = ConfigRegistry.loadFromDirectory(dir);
        long before = registry.longParam("TRACK_BATCH_MAX_SIZE");

        rewriteParam(dir, "global", "TRACK_BATCH_MAX_SIZE", before + 7);
        registry.reloadAllFromDirectory(dir);

        assertThat(registry.longParam("TRACK_BATCH_MAX_SIZE"))
                .as("热更的全部意义就是这条：改文件之后不重启就该读到新值")
                .isEqualTo(before + 7);
    }

    @Test
    @DisplayName("坏表让整次热更原地不动：旧值照常读得到，服务不受影响")
    void brokenTableLeavesEverythingUntouched(@TempDir Path tempDir) throws Exception {
        Path dir = copyOfRealConfig(tempDir);
        ConfigRegistry registry = ConfigRegistry.loadFromDirectory(dir);
        long before = registry.longParam("TRACK_BATCH_MAX_SIZE");

        // 先把一张好表改成新值（这次改动本该生效），再把另一张表弄坏 —— 整次热更必须什么都不做
        rewriteParam(dir, "global", "TRACK_BATCH_MAX_SIZE", before + 7);
        Path broken = dir.resolve("unit.json");
        Files.writeString(broken, "{ \"table\": \"unit\", \"version\": 1, \"rows\": [ {", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> registry.reloadAllFromDirectory(dir))
                .as("校验失败要响亮抛出，而不是部分生效")
                .isInstanceOf(ConfigException.class);

        assertThat(registry.longParam("TRACK_BATCH_MAX_SIZE"))
                .as("热更最坏的结果不是「没热上」，而是「热了一半」—— 那会留下一份没人设计过的表组合")
                .isEqualTo(before);
    }
}
