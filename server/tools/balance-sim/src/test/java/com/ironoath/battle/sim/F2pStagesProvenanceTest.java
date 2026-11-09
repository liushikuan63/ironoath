package com.ironoath.battle.sim;

import com.ironoath.battle.BattleRules;
import com.ironoath.config.ConfigRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks what the legacy diagnostic actually proves, without running CLI main/System.exit. */
class F2pStagesProvenanceTest {
    private static ConfigRegistry configs;
    private static BattleParamsResolver resolver;
    private static Method print;
    private static Method parse;

    @BeforeAll
    static void setup() throws Exception {
        configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        resolver = new BattleParamsResolver(configs);
        print = BalanceCli.class.getDeclaredMethod("printF2pStages", ConfigRegistry.class,
                BattleParamsResolver.class, BattleRules.class, Map.class, Map.class, int.class);
        print.setAccessible(true);
        parse = BalanceCli.class.getDeclaredMethod("parse", String[].class);
        parse.setAccessible(true);
    }

    @Test
    void default6833IsHistoricalInputNotAComputedSevenDayArmy() throws Exception {
        Snapshot out = capture("--f2p-stages", "--runs=1");
        assertThat(out.text()).contains("来源：#564 历史固定参考", "6833", "B09-4 未验",
                "未运行 --f2p7d", "不代表当前七天可用军队");
        assertThat(out.text()).doesNotContain("B09 验收 4：零氪第", "资源结余 ÷ trainCost");
    }

    @Test
    void explicitTroopsAndDaysAreLabeledAsCallerInputAndDisplayOnly() throws Exception {
        Snapshot out = capture("--f2p-stages", "--troops=123", "--f2p-days=14", "--runs=1");
        assertThat(out.text()).contains("来源：显式 --troops", "纯步兵 T1 123", "--f2p-days=14 仅为展示标签",
                "B09-4 未验", "未运行 --f2p7d");
        assertThat(out.text()).doesNotContain("来源：#564 历史固定参考", "零氪第 14 天的军队");
    }

    @Test
    void changingOnlyTheDayLabelDoesNotChangeDiagnosticRowsOrTheExistingReturnValue() throws Exception {
        Snapshot seven = capture("--f2p-stages", "--troops=123", "--f2p-days=7", "--runs=1");
        Snapshot fourteen = capture("--f2p-stages", "--troops=123", "--f2p-days=14", "--runs=1");
        List<String> rows = seven.text().lines().filter(s -> s.startsWith("stage_")).toList();
        assertThat(rows).isNotEmpty();
        assertThat(fourteen.text().lines().filter(s -> s.startsWith("stage_")).toList()).isEqualTo(rows);
        assertThat(seven.completed()).isTrue();
        assertThat(fourteen.completed()).isTrue();
    }

    @Test
    void actualUsageDoesNotPretendExplicitTroopsCameFromASevenDayModel(@TempDir Path temp) throws Exception {
        String javaName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", javaName).toString();
        Path output = temp.resolve("usage.log");
        Process child = new ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8",
                "-Dsun.stderr.encoding=UTF-8", "-cp", System.getProperty("java.class.path"),
                BalanceCli.class.getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(child.waitFor(15, TimeUnit.SECONDS)).as("only the owned usage JVM must finish").isTrue();
            assertThat(child.exitValue()).as("existing usage exit contract is preserved").isEqualTo(2);
            String usage = Files.readString(output, StandardCharsets.UTF_8);
            assertThat(usage).contains("诊断用显式纯步兵数量", "省略时沿用 #564 历史固定参考 6833",
                    "不代表当前七天画像");
            assertThat(usage).doesNotContain("**只**喂「零氪第 7 天能造多少」");
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Snapshot capture(String... args) throws Exception {
        Map<String, String> options = (Map<String, String>) parse.invoke(null, (Object) args);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        PrintStream stream = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        try {
            System.setOut(stream);
            boolean completed = (Boolean) print.invoke(null, configs, resolver, resolver.rules(),
                    resolver.unitStats(1), options, 1);
            return new Snapshot(completed, buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(previous);
            stream.close();
        }
    }

    private record Snapshot(boolean completed, String text) { }
}
