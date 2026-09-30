package com.ironoath.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.cfg.BuildingCfg;
import com.ironoath.config.cfg.UnitCfg;
import com.ironoath.config.cfg.UnitCounterCfg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：B02 配置表体系验收测试 —— 泛型加载契约、热更原子性、无浮点扫描、外键完整性。
 * 依赖：JUnit 5 + AssertJ、生成的 cfg 类型；读仓库内真实的 contract/config。
 */
class ConfigRegistryB02Test {

    /**
     * 主城等级上限。
     *
     * <p><b>2026-10-01 由 40 改为 27</b>（收口清单 #494/#495）：`balance-sim --f2p7d --days=45`
     * 实测零氪 45 天停在 27 级，而 40 级造价 ≈ 3.9M 木石、45 天只实攒 22 万 —— 差 17 倍。
     * 影响面已核：没有任何建筑或科技行的 {@code requireMainLevel ≥ 27}。
     *
     * <p><b>为什么钉住具体数字而不是从表里读</b>：从表里读的话，表改错时这些用例会跟着一起
     * 变绿 —— 而它们的作用恰恰是「表被改了就红」。
     */
    private static final int MAIN_CITY_CAP = 27;

    private static ConfigRegistry registry;
    private static Path configDir;

    @BeforeAll
    static void loadRealConfig() {
        configDir = locateConfigDir();
        registry = ConfigRegistry.loadFromDirectory(configDir);
    }

    // ---------- B02 契约：泛型访问 ----------

    @Test
    @DisplayName("B02 契约：load/all/get 三个泛型入口按 Class 取表，类型名自动反推表名")
    void genericAccessorsWork() {
        // BuildingCfg → building，UnitCounterCfg → unit_counter（生成器 pascalCase 的逆变换）
        assertThat(ConfigRegistry.tableNameOf(BuildingCfg.class)).isEqualTo("building");
        assertThat(ConfigRegistry.tableNameOf(UnitCounterCfg.class)).isEqualTo("unit_counter");
        assertThat(ConfigRegistry.tableNameOf(UnitCfg.class)).isEqualTo("unit");

        ConfigTable<BuildingCfg> buildings = registry.load("building", BuildingCfg.class);
        assertThat(buildings.name()).isEqualTo("building");
        // 版本号不写死：策划改表就会递增，写死会让这个与版本无关的测试反复挂。
        // 改成断言「强类型视图与原始视图版本一致」，既稳定又仍然有意义。
        assertThat(buildings.version()).isEqualTo(registry.rawTable("building").version());
        assertThat(buildings.size()).isEqualTo(15);

        // all() 与 load().rows() 是同一份不可变列表
        assertThat(registry.all(BuildingCfg.class)).isEqualTo(buildings.rows());
        assertThat(registry.get(BuildingCfg.class, "main_city")).isSameAs(buildings.get("main_city"));
    }

    @Test
    @DisplayName("生成的 record 被正确装配：枚举、外键、定点字段各就各位")
    void generatedRecordsArePopulatedCorrectly() {
        BuildingCfg mainCity = registry.get(BuildingCfg.class, "main_city");
        assertThat(mainCity.name()).isEqualTo("主城");
        assertThat(mainCity.type()).isEqualTo(BuildingCfg.Type.CORE);
        assertThat(mainCity.maxLevel()).as("主城上限 27 级（2026-10-01 由 40 下调，见 building.json 的 why）")
                .isEqualTo(MAIN_CITY_CAP);
        assertThat(mainCity.costBaseWood()).isEqualTo(1000L);
        assertThat(mainCity.timeBaseSec()).as("0 表示沿用 curve.BUILDING_TIME 的基数 30 秒").isZero();
        assertThat(mainCity.outputResource()).as("主城不产资源").isNull();

        BuildingCfg lumber = registry.get(BuildingCfg.class, "lumber_camp");
        assertThat(lumber.outputResource()).isEqualTo("WOOD");
        assertThat(lumber.outputBasePerHour()).isEqualTo(120L);
        assertThat(lumber.type()).isEqualTo(BuildingCfg.Type.RESOURCE);

        // 可选字段缺失时必须反序列化成 null，而不是 0 —— 0 会被当成「产量为 0」参与结算
        assertThat(mainCity.requireBuilding()).isNull();
        assertThat(lumber.requireBuilding()).isNull();
    }

    @Test
    @DisplayName("兵种表：20 行（4 兵种 × T1~T5），阶级与解锁建筑齐备")
    void unitTableCoversAllTiers() {
        List<UnitCfg> units = registry.all(UnitCfg.class);
        assertThat(units).hasSize(20);

        for (UnitCfg.Type type : UnitCfg.Type.values()) {
            List<UnitCfg> ofType = units.stream().filter(u -> u.type() == type).toList();
            assertThat(ofType).as("兵种 %s 应有 T1~T5 五个阶级", type).hasSize(5);
            assertThat(ofType).extracting(UnitCfg::tier).containsExactly(1L, 2L, 3L, 4L, 5L);
        }

        // 速度与负载不随阶级变化（兵种身份特征）
        for (UnitCfg.Type type : UnitCfg.Type.values()) {
            List<UnitCfg> ofType = units.stream().filter(u -> u.type() == type).toList();
            UnitCfg t1 = ofType.get(0);
            assertThat(ofType).as("兵种 %s 的速度不应随阶级变化", type)
                    .allSatisfy(u -> assertThat(u.speed()).isEqualTo(t1.speed()));
            assertThat(ofType).as("兵种 %s 的负载不应随阶级变化", type)
                    .allSatisfy(u -> assertThat(u.load()).isEqualTo(t1.load()));
        }
    }

    @Test
    @DisplayName("阶级数值单调：攻击/生命/训练成本逐级严格递增；防御允许取整平台期但不减")
    void tierProgressionIsMonotonic() {
        for (UnitCfg.Type type : UnitCfg.Type.values()) {
            List<UnitCfg> tiers = registry.all(UnitCfg.class).stream()
                    .filter(u -> u.type() == type)
                    .sorted((a, b) -> Long.compare(a.tier(), b.tier()))
                    .toList();
            for (int i = 1; i < tiers.size(); i++) {
                UnitCfg prev = tiers.get(i - 1);
                UnitCfg cur = tiers.get(i);
                // 攻击与生命决定「这个阶级值不值得升」，必须严格递增
                assertThat(cur.attack()).as("%s T%d 攻击应严格高于 T%d", type, cur.tier(), prev.tier())
                        .isGreaterThan(prev.attack());
                assertThat(cur.hp()).as("%s T%d 生命应严格高于 T%d", type, cur.tier(), prev.tier())
                        .isGreaterThan(prev.hp());
                assertThat(cur.trainTimeSec()).as("%s T%d 训练耗时应严格高于 T%d", type, cur.tier(), prev.tier())
                        .isGreaterThan(prev.trainTimeSec());
                assertThat(cur.trainCostIron() + cur.trainCostGrain() + cur.trainCostWood())
                        .as("%s T%d 训练总成本应严格高于 T%d", type, cur.tier(), prev.tier())
                        .isGreaterThan(prev.trainCostIron() + prev.trainCostGrain() + prev.trainCostWood());
                // 防御只要求不减：低基数下 1.12 曲线取整会出现平台期（见 unit.json 的 roundingNote），
                // 弓兵防御 4/4/5/6/6 是有意接受的 —— 强行逐级 +1 会把成长速率抬到 25%/级
                assertThat(cur.defense()).as("%s T%d 防御不得低于 T%d", type, cur.tier(), prev.tier())
                        .isGreaterThanOrEqualTo(prev.defense());
            }
        }
    }

    @Test
    @DisplayName("外键完整性：每个兵种的 unlockBuilding 都指向真实存在的建筑")
    void unitUnlockBuildingsExist() {
        for (UnitCfg unit : registry.all(UnitCfg.class)) {
            BuildingCfg building = registry.get(BuildingCfg.class, unit.unlockBuilding());
            assertThat(building.type())
                    .as("兵种 %s 的解锁建筑 %s 应是军事建筑", unit.id(), building.id())
                    .isEqualTo(BuildingCfg.Type.MILITARY);
            assertThat(unit.unlockBuildingLevel())
                    .as("兵种 %s 的解锁等级不得超过建筑 %s 的上限", unit.id(), building.id())
                    .isLessThanOrEqualTo(building.maxLevel());
        }
    }

    @Test
    @DisplayName("克制矩阵：8 条有向关系，与 B00 原文逐条对应")
    void counterMatrixMatchesB00() {
        List<UnitCounterCfg> counters = registry.all(UnitCounterCfg.class);
        assertThat(counters).hasSize(8);
        assertThat(counters).extracting(UnitCounterCfg::id).containsExactlyInAnyOrder(
                "infantry_vs_cavalry", "cavalry_vs_infantry",
                "infantry_vs_archer", "archer_vs_infantry",
                "cavalry_vs_archer", "cavalry_vs_siege",
                "siege_vs_wall", "siege_vs_trap");

        // 未逐对覆盖时，加成与减益回落到 global 表的默认值
        long defaultBonus = registry.fixedParam("COUNTER_BONUS");
        long defaultPenalty = registry.fixedParam("COUNTER_PENALTY");
        assertThat(defaultBonus).isEqualTo(FixedPoint.parse("0.25"));
        assertThat(defaultPenalty).isEqualTo(FixedPoint.parse("0.20"));
        for (UnitCounterCfg c : counters) {
            assertThat(c.bonusFixed()).as("%s 未覆盖时应为 null（用全局默认）", c.id()).isNull();
            assertThat(c.penaltyFixed()).as("%s 未覆盖时应为 null（用全局默认）", c.id()).isNull();
        }
    }

    @Test
    @DisplayName("曲线表的定点字段被 FixedPointDeserializer 正确转换（\"1.18\" → 11800）")
    void decimalFieldsBecomeFixedPointLongs() {
        var curve = registry.get(com.ironoath.config.cfg.CurveCfg.class, "BUILDING_TIME");
        assertThat(curve.base()).isEqualTo(FixedPoint.of(30));
        assertThat(curve.ratio()).isEqualTo(11800L);
        assertThat(curve.exponent()).isEqualTo(FixedPoint.ONE);

        var unitCurve = registry.get(com.ironoath.config.cfg.CurveCfg.class, "UNIT_STRENGTH");
        assertThat(unitCurve.ratio()).isEqualTo(FixedPoint.parse("1.12"));
    }

    // ---------- 验收 9：热更原子替换 ----------

    @Test
    @DisplayName("验收9：reload 原子替换整表，旧引用继续可用且数据一致，不崩溃")
    void reloadAtomicallySwapsTable() {
        ConfigTable<BuildingCfg> oldTable = registry.load("building", BuildingCfg.class);
        BuildingCfg oldMainCity = oldTable.get("main_city");
        assertThat(oldMainCity.maxLevel()).isEqualTo(MAIN_CITY_CAP);
        // 版本不写死：building.json 每被策划改一次版本就 +1，写死会让本用例与「热更是否原子」这件事无关地反复挂
        int oldVersion = oldTable.version();
        int newVersion = oldVersion + 1;

        // 热更：把主城上限改成 45，version 递增一档
        String updated = readTable("building.json")
                .replace("\"version\": " + oldVersion, "\"version\": " + newVersion)
                .replace("\"maxLevel\": " + MAIN_CITY_CAP + ",\n      \"timeBaseSec\": 0",
                "\"maxLevel\": 45,\n      \"timeBaseSec\": 0");
        assertThat(updated).as("替换必须真的生效，否则这个测试什么都没验证").contains("\"maxLevel\": 45");
        assertThat(updated).as("版本号替换必须真的生效").contains("\"version\": " + newVersion);

        registry.reload("building", BuildingCfg.class, updated);

        ConfigTable<BuildingCfg> newTable = registry.load("building", BuildingCfg.class);
        assertThat(newTable.version()).isEqualTo(newVersion);
        assertThat(newTable.get("main_city").maxLevel()).isEqualTo(45);

        // 关键：进行中的请求持有的旧引用必须仍然是完整一致的旧快照
        assertThat(oldTable.version()).isEqualTo(oldVersion);
        assertThat(oldTable.get("main_city").maxLevel()).isEqualTo(MAIN_CITY_CAP);
        assertThat(oldMainCity.maxLevel()).isEqualTo(MAIN_CITY_CAP);
        assertThat(oldTable.rows()).hasSize(newTable.rows().size());

        // 还原，避免污染同一 JVM 内的其它测试
        registry.reload("building", BuildingCfg.class, readTable("building.json"));
        assertThat(registry.load("building", BuildingCfg.class).get("main_city").maxLevel())
                .isEqualTo(MAIN_CITY_CAP);
    }

    @Test
    @DisplayName("验收9：热更内容非法时抛异常且保留旧版本（热更失败不能把服务打挂）")
    void failedReloadKeepsOldVersion() {
        ConfigTable<BuildingCfg> before = registry.load("building", BuildingCfg.class);
        String broken = readTable("building.json")
                .replace("\"maxLevel\": " + MAIN_CITY_CAP, "\"maxLevel\": \"很多\"");

        assertThatThrownBy(() -> registry.reload("building", BuildingCfg.class, broken))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("热更校验失败")
                .hasMessageContaining("已保留旧版本");

        ConfigTable<BuildingCfg> after = registry.load("building", BuildingCfg.class);
        assertThat(after).isSameAs(before);
        assertThat(after.get("main_city").maxLevel()).isEqualTo(MAIN_CITY_CAP);
    }

    // ---------- 验收 10：无浮点 ----------

    @Test
    @DisplayName("验收10：全部配置表的 JSON 中没有任何浮点数字（小数一律写成字符串）")
    void noFloatingPointNumbersInAnyConfigTable() {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(configDir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String name = file.getFileName().toString();
                JsonNode root = JsonUtils.readTree(Files.readString(file, StandardCharsets.UTF_8));
                scanForFloats(name, "$", root, offenders);
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描配置目录失败", e);
        }
        assertThat(offenders)
                .as("JSON number 是 IEEE-754 double，写 1.18 实际存的是 1.1799999…，会让双端算出不同结果")
                .isEmpty();
    }

    /** 递归扫描：任何 isDouble/isFloat 的数值节点都算违规。 */
    private static void scanForFloats(String table, String path, JsonNode node, List<String> out) {
        if (node.isDouble() || node.isFloat() || node.isBigDecimal()) {
            out.add(table + " " + path + " = " + node);
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> scanForFloats(table, path + "." + e.getKey(), e.getValue(), out));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                scanForFloats(table, path + "[" + i + "]", node.get(i), out);
            }
        }
    }

    // ---------- 验收 8：非法字段 ----------

    @Test
    @DisplayName("验收8：任意表加一个非法字段值，一次性列出全部精确字段路径")
    void illegalFieldIsReportedWithExactPath() {
        String broken = readTable("unit.json")
                .replace("\"tier\": 1,", "\"tier\": \"abc\",")
                .replace("\"attack\": 14,", "\"attack\": -5,")
                .replace("\"unlockBuilding\": \"archery_range\"", "\"unlockBuilding\": \"not_a_building\"");

        assertThatThrownBy(() -> ConfigRegistry.loadFromJson(Map.of(
                "unit", broken,
                "building", readTable("building.json"),
                "resource", readTable("resource.json"),
                "curve", readTable("curve.json"),
                "global", readTable("global.json"),
                "unit_counter", readTable("unit_counter.json"))))
                .isInstanceOf(ConfigException.class)
                .satisfies(e -> {
                    ConfigException ce = (ConfigException) e;
                    assertThat(ce.errorCount()).as("必须一次性列出全部错误").isGreaterThanOrEqualTo(3);
                    assertThat(ce.errors())
                            .anyMatch(s -> s.contains("unit#unit_infantry_t1.tier") && s.contains("必须是整数"))
                            .anyMatch(s -> s.contains("unit#unit_archer_t1.attack") && s.contains("必须 > 0"))
                            .anyMatch(s -> s.contains("unit#unit_archer_t1.unlockBuilding")
                                    && s.contains("building") && s.contains("not_a_building"));
                });
    }

    @Test
    @DisplayName("预留表（status=RESERVED）允许空 rows，普通表不允许")
    void reservedTablesMayBeEmpty() {
        String reserved = """
                { "table": "bot_name", "version": 1, "status": "RESERVED",
                  "comment": "B11 交付",
                  "fieldTypes": { "id": "STRING_KEY", "name": "STRING" },
                  "rows": [] }
                """;
        assertThat(ConfigValidator.validateTable("bot_name", JsonUtils.readTree(reserved))).isEmpty();

        String active = reserved.replace("\"status\": \"RESERVED\",", "");
        assertThat(ConfigValidator.validateTable("bot_name", JsonUtils.readTree(active)))
                .anyMatch(i -> i.toString().contains("RESERVED"));
    }

    // ---------- 辅助 ----------

    private static String readTable(String fileName) {
        try {
            return Files.readString(configDir.resolve(fileName), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取配置表失败: " + fileName, e);
        }
    }

    private static Path locateConfigDir() {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cursor != null; i++) {
            Path candidate = cursor.resolve("contract").resolve("config");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("未找到 contract/config");
    }
}
