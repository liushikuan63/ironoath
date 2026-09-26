package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.model.GlobalCfg;
import com.ironoath.core.release.ReleaseGate;
import com.ironoath.web.release.ReleaseRulesAssembler;

/**
 * 职责：B16 §1 性能预算与 §5 发布参数的配置自检 —— 铁律 1（数值零硬编码）的落地验证。
 * 依赖：game-config（独立实例，不动 Spring 上下文里那份）、{@link ReleaseRulesAssembler}。
 *
 * <p><b>本类断言的是关系而不是具体数值</b>。断言「首包上限 == 4194304」是一个变更探测器：
 * 微信哪天把限制放宽到 8MB，改配置就得同时改测试，而测试并没有在保护任何东西。
 * 真正需要保护的是那些<b>调参时容易被破坏的关系</b> ——
 * 例如「战斗结算的 P99 预算必须严于通用接口」，因为它是纯内存计算，
 * 若有人把它调到和通用接口一样宽松，那意味着国战的容量测算全部作废，
 * 而这个错误在功能测试里完全看不出来。
 *
 * <p><b>最后一条用例是整个热更设计的地基</b>：hash 必须由内容决定，与版本号无关。
 * 「改了内容忘了升版本」必须被发现（否则客户端认为自己是最新的），
 * 「升了版本没改内容」必须不触发下载（否则每次发版都让全服白下一遍配置表）。
 * 这两个方向都只能靠 hash 满足，而它们在功能测试里都不会失败 ——
 * 因为功能测试通常是「改内容 + 升版本」一起做，恰好绕开了两条边界。
 */
class PerfBudgetTest {

    private static ConfigRegistry configs;
    private static ReleaseRulesAssembler assembler;

    @BeforeAll
    static void loadConfigs() {
        configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        assembler = new ReleaseRulesAssembler(configs);
    }

    // ---------- 配置值本身必须能装配出合法规则 ----------

    @Test
    @DisplayName("B16 的参数能装配出合法规则：配错了要在测试里炸，而不是在服务启动时炸")
    void b16ParametersAssembleIntoValidRules() {
        assertThatCode(() -> assembler.trackRules()).doesNotThrowAnyException();
        assertThatCode(() -> assembler.gateRules()).doesNotThrowAnyException();
        assertThatCode(() -> assembler.manifest()).doesNotThrowAnyException();

        // 秒 → 毫秒的换算必须真的发生：漏乘的话攒批窗口只有 10 毫秒，等于逐条上报
        assertThat(assembler.trackRules().flushIntervalMillis())
                .isEqualTo(configs.longParam("TRACK_BATCH_FLUSH_SECONDS") * 1000L);
        assertThat(assembler.trackRules().maxBatchSize())
                .isEqualTo((int) configs.longParam("TRACK_BATCH_MAX_SIZE"));
        // DECIMAL 已由反序列化器转成定点，装配层不得再转一次（再转一次 5% 会变成 500%）
        assertThat(assembler.gateRules().grayPercentFixed())
                .isEqualTo(configs.fixedParam("RELEASE_GRAY_PERCENT"));
    }

    // ---------- 调参时容易被破坏的关系 ----------

    @Test
    @DisplayName("性能预算之间的关系：这些关系被破坏时功能测试全绿，只有容量测算会作废")
    void budgetRelationsHold() {
        ReleaseRulesAssembler.PerfBudget budget = assembler.perfBudget();

        assertThat(budget.battleSettleP99MaxMs())
                .as("战斗结算是纯内存定点运算，预算必须严于走网络的通用接口；"
                        + "调成一样宽松意味着国战的容量测算全部作废")
                .isLessThan(budget.apiP99MaxMs());
        assertThat(budget.firstScreenMaxMs())
                .as("首屏至少要包含一次接口往返，否则这个预算在物理上不可能达到")
                .isGreaterThan(budget.apiP99MaxMs());
        assertThat(budget.minFps())
                .as("帧率下限不该超过屏幕刷新率，否则「达标」变成一件靠运气的事")
                .isBetween(24L, 60L);
        assertThat(budget.firstPackageMaxBytes())
                .as("首包上限是微信硬限制 4MB，改大它不会让包变小，只会让 CI 卡口失去意义")
                .isEqualTo(4L * 1024 * 1024);
        assertThat(budget.totalPackageMaxBytes())
                .as("主包+分包合计上限是微信硬限制 30M；单个普通分包不限大小，所以合计是唯一会失控的一条。"
                        + "它必须不小于首包上限，否则主包自己就超过了合计")
                .isEqualTo(30L * 1024 * 1024)
                .isGreaterThanOrEqualTo(budget.firstPackageMaxBytes());
        assertThat(budget.fullGcPauseMaxMs())
                .as("单次 Full GC 停顿必须小于微信回调的时限，否则支付回调会被判超时并进补单队列")
                .isLessThan(budget.apiP99MaxMs() * 10);
    }

    @Test
    @DisplayName("视口的专用 payload 限制不得超过全局天花板（B07 与 B16 两处参数不能各说各话）")
    void viewportPayloadStaysUnderGlobalCeiling() {
        long global = configs.longParam("PERF_PAYLOAD_MAX_BYTES");
        long viewport = configs.longParam("VIEWPORT_PAYLOAD_MAX_BYTES");
        assertThat(viewport)
                .as("视口限制是全局天花板的一个特化，超过它的话 B07 会放行 B16 判定为超标的响应")
                .isLessThanOrEqualTo(global);
    }

    @Test
    @DisplayName("看板留存口径覆盖 B16 §4 要求的 D1/D3/D7/D30，且按升序（乱序会让看板列头与数据错位）")
    void retentionDaysCoverTheRequiredCohorts() {
        List<Integer> days = assembler.retentionDays();
        assertThat(days).contains(1, 3, 7, 30);
        assertThat(days).as("必须升序").isSorted();
        assertThat(days.get(days.size() - 1))
                .as("最大留存天数决定了埋点数据的保留期下限：保留期短于它，最后一个指标就算不出来")
                .isLessThanOrEqualTo(90);
    }

    @Test
    @DisplayName("首批灰度不得超过 20%：灰度的意义是「出问题时受影响的人少」，一上来就 50% 等于没有灰度")
    void firstGrayBatchStaysSmall() {
        long gray = configs.fixedParam("RELEASE_GRAY_PERCENT");
        assertThat(gray).isBetween(0L, 2000L);
    }

    // ---------- 热更的地基 ----------

    @Test
    @DisplayName("清单覆盖全部已加载的表，hash 为 16 位十六进制，且同一份内容两次装配结果一致")
    void manifestIsCompleteAndStable() {
        ReleaseGate.Manifest first = assembler.manifest();
        ReleaseGate.Manifest second = assembler.manifest();

        assertThat(first.tables()).hasSize(configs.tableNames().size());
        assertThat(first.manifestVersion()).isEqualTo(configs.fingerprint());
        for (ReleaseGate.TableMeta meta : first.tables()) {
            assertThat(meta.hash()).as("表 %s 的 hash", meta.name()).hasSize(16).matches("[0-9a-f]{16}");
        }
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("hash 由内容决定：改了内容忘了升版本 ⇒ hash 必须变；升了版本没改内容 ⇒ hash 必须不变")
    void hashFollowsContentNotVersion() throws Exception {
        Path file = locateTable("global.json");
        String original = Files.readString(file, StandardCharsets.UTF_8);
        int version = configs.rawTable("global").version();
        String baseline = assembler.manifest().table("global").hash();

        // 情形一：只升版本号，内容一个字符都没改
        String versionBumped = original.replace("\"version\": " + version, "\"version\": " + (version + 1));
        assertThat(versionBumped).as("夹具必须真的改到了版本号").isNotEqualTo(original);
        ConfigRegistry bumped = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        bumped.reload("global", GlobalCfg.class, versionBumped);
        ReleaseGate.TableMeta bumpedMeta = new ReleaseRulesAssembler(bumped).manifest().table("global");
        assertThat(bumpedMeta.version()).as("版本号确实升上去了").isEqualTo(String.valueOf(version + 1));
        assertThat(bumpedMeta.hash())
                .as("内容没变 ⇒ hash 不该变，否则每次发版都让全服白下一遍完全相同的配置表")
                .isEqualTo(baseline);

        // 情形二：只改内容，版本号保持不动（配置表最常见的一类事故）
        String contentChanged = original.replace(
                "\"id\": \"TRACK_BATCH_MAX_SIZE\",\n      \"valueType\": \"LONG\",\n      \"value\": 10,",
                "\"id\": \"TRACK_BATCH_MAX_SIZE\",\n      \"valueType\": \"LONG\",\n      \"value\": 11,");
        assertThat(contentChanged).as("夹具必须真的改到了那一行的值").isNotEqualTo(original);
        ConfigRegistry changed = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        changed.reload("global", GlobalCfg.class, contentChanged);
        ReleaseGate.TableMeta changedMeta = new ReleaseRulesAssembler(changed).manifest().table("global");
        assertThat(changedMeta.version()).as("版本号没动").isEqualTo(String.valueOf(version));
        assertThat(changedMeta.hash())
                .as("内容变了 ⇒ hash 必须变，否则客户端认为自己是最新的，症状是「我明明改了表却看不到效果」")
                .isNotEqualTo(baseline);

        // 热更 global 之后，全局参数本身也必须立即生效 ——
        // 键值索引曾经和原始表快照一样是构造期的不可变副本，于是 reload 之后 longParam 仍返回旧值，
        // 而「灰度比例改配置即生效，不用发版」正是线上出事时唯一来得及做的那件事
        assertThat(new ReleaseRulesAssembler(changed).trackBatchMaxSize())
                .as("热更 global 之后必须读到新值").isEqualTo(11);
        assertThat(assembler.trackBatchMaxSize())
                .as("共享的那份 registry 没被热更影响").isEqualTo(10);

        // 共享的那份 registry 没被动过：上面用的都是独立实例
        assertThat(assembler.manifest().table("global").hash()).isEqualTo(baseline);
    }

    /**
     * 定位 contract/config 下的表文件。从 cwd 逐级向上找：
     * 测试的工作目录可能是模块目录也可能是仓库根，写死相对路径的话只有一种跑法能成 ——
     * 那种只在某一种跑法下通过的测试，迟早会在 CI 上变成謎之失败。
     */
    private static Path locateTable(String fileName) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            Path candidate = dir.resolve("contract").resolve("config").resolve(fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
            Path parent = dir.getParent();
            if (parent == null) {
                break;
            }
            dir = parent;
        }
        throw new IllegalStateException("找不到 contract/config/" + fileName + "，cwd=" + Path.of("").toAbsolutePath());
    }
}
