package com.ironoath.config;

import com.ironoath.common.config.CurveKind;
import com.ironoath.common.config.CurveParams;
import com.ironoath.common.config.CurveSource;
import com.ironoath.common.config.GlobalParamSource;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.cfg.CurveCfg;
import com.ironoath.config.model.GlobalCfg;
import com.ironoath.config.cfg.ResourceCfg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：ConfigRegistry 单测 —— 验证真实配置表能加载、访问器找不到即抛、坏表拒绝加载。
 * 依赖：JUnit 5 + AssertJ；读仓库内真实的 contract/config（工作目录会向上回溯定位）。
 */
class ConfigRegistryTest {

    private static ConfigRegistry registry;

    @BeforeAll
    static void loadRealConfig() {
        registry = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    }

    @Test
    @DisplayName("真实配置表加载成功，必需表与业务表齐备，指纹可读")
    void loadsRealConfigTables() {
        assertThat(registry.tableNames())
                .contains("resource", "curve", "global", "building", "unit", "unit_counter");
        // 指纹不写死整串：每加一张表都会变，那不是本测试要守的东西。
        // 要守的是「每张表都带 version 且格式为 name@version」，以及版本号之间的相对关系
        String fingerprint = registry.fingerprint();
        String[] entries = fingerprint.split(",");
        assertThat(entries).hasSize(registry.tableNames().size());
        int maxVersion = 0;
        for (String entry : entries) {
            assertThat(entry).matches("[a-z_]+@\\d+");
            maxVersion = Math.max(maxVersion, Integer.parseInt(entry.substring(entry.indexOf('@') + 1)));
        }
        assertThat(registry.maxVersion()).isEqualTo(maxVersion);
        // 不钉任何单张表的版本号：curve 在 B06 加了 HERO_LEVEL_EXP 升到 v2，
        // resource 在 B04 删了 initProtected 升到 v2，以后还会随数值体检继续动。
        // 「改了内容就必须升版本」这条纪律已经由上面的 name@version 正则与
        // maxVersion 断言覆盖，再钉具体数字只会让测试变成「改数值就得改测试」的负担。
        // global 是全项目改动最频繁的表（护盾参数、战斗浮动、K 值校准、B04 的资源保护比例、
        // B06 的武将养成参数都动过它），版本必须严格高于初始值 1 —— 这条守住
        // 「改了内容就必须升版本」的纪律，否则客户端无法判断要不要重新拉表，
        // 热更与回滚都失去依据（B00：配置表带 version 字段）。
        int globalVersion = Integer.parseInt(
                fingerprint.replaceAll(".*global@(\\d+).*", "$1"));
        assertThat(globalVersion).as("global 表被改动过多次，版本应高于初始值").isGreaterThan(1);
        assertThat(registry.maxVersion()).isEqualTo(globalVersion);
        assertThat(registry.hasTable("not_exist")).isFalse();
        assertThatThrownBy(() -> registry.rawTable("not_exist"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("资源表：6 种资源（含体力），数值与 contract/config/resource.json 完全一致")
    void resourceTableMatchesConfigFile() {
        List<ResourceCfg> all = registry.allResources();
        assertThat(all).hasSize(6);
        assertThat(all).extracting(ResourceCfg::id)
                .containsExactly("WOOD", "STONE", "IRON", "GRAIN", "GOLD", "STAMINA");

        ResourceCfg wood = registry.getResource("WOOD");
        assertThat(wood.name()).isEqualTo("木材");
        assertThat(wood.kind()).isEqualTo(ResourceCfg.Kind.BASE);
        assertThat(wood.initAmount()).isEqualTo(5000L);
        assertThat(wood.initCap()).isEqualTo(20000L);
        assertThat(wood.basePerHour()).isEqualTo(200L);

        // 金币是货币，绝不自动产出（C00 公理四：终局必须有填不满的坑）
        ResourceCfg gold = registry.getResource("GOLD");
        assertThat(gold.kind()).isEqualTo(ResourceCfg.Kind.CURRENCY);
        assertThat(gold.basePerHour()).isZero();

        // 体力（B09 §5）：做成资源行而不是独立系统，于是「每 6 分钟 1 点」「溢出不超上限」
        // 「不用定时器重置」三条要求分别落在 basePerHour、cap 截断、惰性结算上，全是现成机制
        ResourceCfg stamina = registry.getResource("STAMINA");
        assertThat(stamina.kind()).isEqualTo(ResourceCfg.Kind.STAMINA);
        assertThat(stamina.basePerHour())
                .as("每小时 10 点 = 每 6 分钟 1 点；这个数必须能被 60 整除，"
                        + "否则「每 X 分钟恢复 1 点」就不是一个整数分钟数，UI 上的倒计时会对不上")
                .isEqualTo(10L);
        assertThat(60L % stamina.basePerHour())
                .as("恢复周期必须是整数分钟（60 / basePerHour），否则 UI 上的倒计时对不上")
                .isZero();
        assertThat(stamina.initCap())
                .as("建档用的 initCap 必须等于随等级成长公式在 1 级时的值，"
                        + "否则新号第一次打开面板就看到容量跳变")
                .isEqualTo(registry.longParam("STAMINA_CAP_BASE"));
    }

    @Test
    @DisplayName("读取不存在的配置 id 抛异常，绝不返回 null（B01 硬约束）")
    void missingIdThrowsInsteadOfReturningNull() {
        assertThatThrownBy(() -> registry.getResource("MITHRIL"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("MITHRIL");
        assertThatThrownBy(() -> registry.get(CurveCfg.class, "NOT_A_CURVE"))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> registry.getGlobal("NOT_A_PARAM"))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> registry.longParam("NOT_A_PARAM"))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    @DisplayName("曲线表：B00 数值速查的 8 条曲线齐备，参数逐条对应")
    void curveTableMatchesB00CheatSheet() {
        // 用 contains 而不是 containsExactlyInAnyOrder：B00 的 8 条是必须齐备的下限，
        // 后续批次会按需追加（B06 加了 HERO_LEVEL_EXP）。钉成「恰好 8 条」会让
        // 每加一条曲线都要改这个与曲线内容无关的断言。
        assertThat(registry.rawTable("curve").ids()).contains(
                "BUILDING_TIME", "BUILDING_COST", "BUILDING_OUTPUT", "POWER_CONTRIB",
                "TECH_TIME", "UNIT_STRENGTH", "HERO_GROWTH", "CHAPTER_DIFFICULTY");
        assertThat(registry.rawTable("curve").ids())
                .as("B06 武将升级经验曲线必须存在，否则武将升级无经验可算")
                .contains("HERO_LEVEL_EXP");

        CurveCfg buildingTime = registry.get(CurveCfg.class, "BUILDING_TIME");
        assertThat(buildingTime.kind()).isEqualTo(CurveCfg.Kind.GEOMETRIC);
        assertThat(buildingTime.base()).isEqualTo(FixedPoint.of(30));
        assertThat(buildingTime.ratio()).isEqualTo(FixedPoint.parse("1.18"));
        assertThat(buildingTime.unit()).isEqualTo(CurveCfg.Unit.SECOND);
        assertThat(buildingTime.formula()).isEqualTo("T(n) = T0 * 1.18^(n-1)");

        // why 是只给人看的文档字段，不进入生成类型（否则运行期对象白白背着一堆字符串），
        // 但 C00 公理三要求每个数值都说得出为什么，所以直接从原始表行断言它存在
        assertThat(registry.rawTable("curve").row("BUILDING_TIME").get("why").asText())
                .as("每条曲线都必须写明取值理由").isNotBlank();

        CurveCfg output = registry.get(CurveCfg.class, "BUILDING_OUTPUT");
        assertThat(output.kind()).isEqualTo(CurveCfg.Kind.POWER);
        assertThat(output.exponent()).isEqualTo(FixedPoint.parse("1.08"));
        assertThat(output.base()).as("基数由各建筑逐行提供").isZero();
        assertThat(registry.curve("BUILDING_OUTPUT").baseProvidedExternally()).isTrue();
    }

    @Test
    @DisplayName("全局参数表：按类型读取，类型不匹配立刻报错而不是静默返回 0")
    void globalParamsAreTypedAndStrict() {
        assertThat(registry.longParam("FIXED_POINT_SCALE")).isEqualTo(FixedPoint.SCALE);
        assertThat(registry.longParam("INIT_CITY_LEVEL")).isEqualTo(1L);
        assertThat(registry.longParam("NEWCOMER_PROTECT_SECONDS")).isEqualTo(259200L);
        assertThat(registry.longParam("BATTLE_MAX_ROUNDS")).isEqualTo(8L);

        assertThat(registry.fixedParam("PVP_POWER_MIN_RATIO")).isEqualTo(FixedPoint.parse("0.5"));
        assertThat(registry.fixedParam("PVP_POWER_MAX_RATIO")).isEqualTo(FixedPoint.parse("2.0"));
        // 集结上限刻意不设独立参数：它就是 PVP_POWER_MAX_RATIO × √N（√1 = 1 时退化为单人区间）。
        // 曾经有过一个 RALLY_POWER_FACTOR = 2.0，那是同一个数字的第二个家 —— 改一处忘一处，
        // 集结与单人的口径就会悄悄分叉，而没有任何测试会变红
        assertThat(registry.fixedParam("SEARCH_WEIGHT_POWER")
                + registry.fixedParam("SEARCH_WEIGHT_DISTANCE")
                + registry.fixedParam("SEARCH_WEIGHT_RESOURCE")
                + registry.fixedParam("SEARCH_WEIGHT_RANDOM"))
                .as("B08 §8 的四项搜索权重之和必须为 1.0，否则调一个权重的实际效果取决于其余三个的和")
                .isEqualTo(FixedPoint.ONE);
        assertThat(registry.fixedParam("BONUS_REVENGE")).isEqualTo(FixedPoint.parse("0.15"));
        assertThat(registry.fixedParam("COUNTER_ADVANCE_FRONT")
                + registry.fixedParam("COUNTER_ADVANCE_MID")
                + registry.fixedParam("COUNTER_ADVANCE_BACK"))
                .as("三排损失分摊之和必须等于定点 1.0")
                .isEqualTo(FixedPoint.ONE);

        // 用 LONG 的方式读 DECIMAL 参数必须报错：静默返回 0 会让战力区间变成 [0,0]
        assertThatThrownBy(() -> registry.longParam("PVP_POWER_MAX_RATIO"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能按 LONG 读取");
        assertThatThrownBy(() -> registry.fixedParam("FIXED_POINT_SCALE"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("未定稿参数被显式标记，启动时可一次性列出（B00：不要静默假设）")
    void pendingConfirmationsAreVisible() {
        List<GlobalCfg> pending = registry.pendingConfirmations();
        assertThat(pending).extracting(GlobalCfg::id)
                .contains("INIT_MATCH_POWER", "INIT_AVATAR_ID");
        for (GlobalCfg cfg : pending) {
            assertThat(cfg.todo()).contains("TODO(需确认)");
        }
        // 已定稿的参数不该出现在待确认列表里
        assertThat(pending).extracting(GlobalCfg::id).doesNotContain("FIXED_POINT_SCALE");
    }

    @Test
    @DisplayName("ConfigRegistry 实现了 game-common 的两个端口，game-core 因此无需依赖 game-config")
    void implementsDependencyInversionPorts() {
        assertThat(registry).isInstanceOf(CurveSource.class).isInstanceOf(GlobalParamSource.class);

        CurveSource curves = registry;
        CurveParams params = curves.curve("UNIT_STRENGTH");
        assertThat(params.id()).isEqualTo("UNIT_STRENGTH");
        assertThat(params.kind()).isEqualTo(CurveKind.GEOMETRIC);
        assertThat(params.ratioFixed()).isEqualTo(FixedPoint.parse("1.12"));
        assertThat(curves.hasCurve("UNIT_STRENGTH")).isTrue();
        assertThat(curves.hasCurve("NOPE")).isFalse();

        GlobalParamSource globals = registry;
        assertThat(globals.hasParam("LANCHESTER_K")).isTrue();
        assertThat(globals.fixedParam("LANCHESTER_K"))
                .as("K 已由 B00 默认的 1.0 校准为 7.0，理由见 global.json 的 why")
                .isEqualTo(FixedPoint.parse("7.0"));
    }

    @Test
    @DisplayName("验收6：改坏真实配置（类型错 + 缺字段）后拒绝加载，并一次性列出全部错误字段")
    void brokenConfigRefusesToLoadAndListsEveryError() {
        Map<String, String> tables = new LinkedHashMap<>();
        tables.put("global", readReal("global.json"));

        // resource 表：initAmount 写成字符串、缺 initCap、多出未声明字段
        tables.put("resource", """
                {
                  "table": "resource",
                  "version": 1,
                  "fieldTypes": {
                    "id": "STRING_KEY", "name": "STRING", "kind": "ENUM:BASE,CURRENCY",
                    "initAmount": "LONG_NONNEG", "initCap": "LONG_NONNEG",
                    "initProtected": "LONG_NONNEG", "basePerHour": "LONG_NONNEG", "why": "?STRING"
                  },
                  "rows": [
                    { "id": "WOOD", "name": "木材", "kind": "BASE", "initAmount": "很多",
                      "initCap": 20000, "initProtected": 0, "basePerHour": 200 },
                    { "id": "GOLD", "name": "金币", "kind": "MONEY", "initAmount": 200,
                      "initProtected": 0, "basePerHour": 0, "typo": 1 }
                  ]
                }
                """);

        // curve 表：整张表缺失 —— 必需的表不存在也必须报出来
        assertThatThrownBy(() -> ConfigRegistry.loadFromJson(tables))
                .isInstanceOf(ConfigException.class)
                .satisfies(e -> {
                    ConfigException ce = (ConfigException) e;
                    assertThat(ce.errorCount()).as("必须一次性列出全部错误").isGreaterThanOrEqualTo(5);
                    assertThat(ce.errors())
                            .anyMatch(s -> s.contains("resource#WOOD.initAmount") && s.contains("必须是整数"))
                            .anyMatch(s -> s.contains("resource#GOLD.initCap") && s.contains("缺失"))
                            .anyMatch(s -> s.contains("resource#GOLD.kind") && s.contains("取值非法"))
                            .anyMatch(s -> s.contains("resource#GOLD.typo") && s.contains("未在 fieldTypes 中声明"))
                            .anyMatch(s -> s.contains("curve") && s.contains("必需的表缺失"));
                    // 异常信息本身可直接打进启动日志
                    assertThat(ce.getMessage()).contains("拒绝启动").contains("WOOD.initAmount");
                });
    }

    @Test
    @DisplayName("缺少必需表 / 空目录内容 → 拒绝加载")
    void missingRequiredTableRefusesToLoad() {
        assertThatThrownBy(() -> ConfigRegistry.loadFromJson(Map.of(
                "resource", readReal("resource.json"))))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("必需的表缺失");

        assertThatThrownBy(() -> ConfigRegistry.loadFromJson(Map.of()))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    @DisplayName("配置目录不存在时给出可操作的错误信息（含回溯查找说明）")
    void missingDirectoryGivesActionableError() {
        assertThatThrownBy(() -> ConfigRegistry.loadFromDirectory(Path.of("/definitely/not/here")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("contract/config");
    }

    private static String readReal(String fileName) {
        try {
            Path dir = locateConfigDir();
            return java.nio.file.Files.readString(dir.resolve(fileName), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取真实配置表失败: " + fileName, e);
        }
    }

    /** 测试里手动复刻 ConfigRegistry 的回溯查找逻辑，定位真实的 contract/config。 */
    private static Path locateConfigDir() {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cursor != null; i++) {
            Path candidate = cursor.resolve("contract").resolve("config");
            if (java.nio.file.Files.isDirectory(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("未找到 contract/config，当前工作目录=" + Path.of("").toAbsolutePath());
    }
}
