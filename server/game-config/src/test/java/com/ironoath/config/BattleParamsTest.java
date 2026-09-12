package com.ironoath.config;

import com.ironoath.common.num.FixedPoint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：验证「战斗参数的唯一一个家」—— {@link BattleParams} 逐项对得上 global / unit_counter 表。
 * 依赖：JUnit 5 + AssertJ；读仓库里的真实配置表（不启动容器）。
 *
 * <p><b>为什么要逐项对表</b>：{@code BattleParams} 是线上与调数值 CLI 共用的数值来源，
 * 它读错一个参数名不会报错（{@code fixedParam} 找不到会抛，但读错成<b>另一个存在的</b>参数就不会），
 * 表现是战斗手感与配置表对不上，而这种偏差只能靠人肉对表发现。
 * 把这些断言写成测试之后，改配置而忘了改装配（或反之）会立刻变红。
 */
class BattleParamsTest {

    private static ConfigRegistry registry;

    @BeforeAll
    static void loadRealConfig() {
        registry = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    }

    @Test
    @DisplayName("14 个标量参数逐项来自 global 表，没有硬编码也没有读错参数名")
    void scalarsComeStraightFromTheGlobalTable() {
        BattleParams params = BattleParams.of(registry);

        assertThat(params.maxRounds()).isEqualTo((int) registry.longParam("BATTLE_MAX_ROUNDS"));
        assertThat(params.lanchesterK()).isEqualTo(registry.fixedParam("LANCHESTER_K"));
        assertThat(params.hpDefenseWeightFixed()).isEqualTo(registry.fixedParam("HP_DEFENSE_WEIGHT"));
        assertThat(params.jitterMinFixed()).isEqualTo(registry.fixedParam("BATTLE_JITTER_MIN"));
        assertThat(params.jitterMaxFixed()).isEqualTo(registry.fixedParam("BATTLE_JITTER_MAX"));
        assertThat(params.rowFrontFixed()).isEqualTo(registry.fixedParam("COUNTER_ADVANCE_FRONT"));
        assertThat(params.rowMidFixed()).isEqualTo(registry.fixedParam("COUNTER_ADVANCE_MID"));
        assertThat(params.rowBackFixed()).isEqualTo(registry.fixedParam("COUNTER_ADVANCE_BACK"));
        assertThat(params.counterBonusFixed()).isEqualTo(registry.fixedParam("COUNTER_BONUS"));
        assertThat(params.counterPenaltyFixed()).isEqualTo(registry.fixedParam("COUNTER_PENALTY"));
        assertThat(params.drawGapRatioFixed()).isEqualTo(registry.fixedParam("BATTLE_DRAW_GAP_RATIO"));
        assertThat(params.pveDeadRatioFixed()).isEqualTo(registry.fixedParam("WOUND_RATIO_PVE_DEAD"));
        assertThat(params.pvpAttackerDeadRatioFixed())
                .isEqualTo(registry.fixedParam("WOUND_RATIO_PVP_ATTACKER_DEAD"));
        assertThat(params.pvpDefenderDeadRatioFixed())
                .isEqualTo(registry.fixedParam("WOUND_RATIO_PVP_DEFENDER_DEAD"));

        // 三排分摊之和必须恰好是 1.0：这条不变量 BattleRules 的构造器也查，
        // 但在这里查能直接指出是配置表错了，而不是等内核抛一个看不懂的战斗异常
        assertThat(params.rowFrontFixed() + params.rowMidFixed() + params.rowBackFixed())
                .as("三排损失分摊之和必须等于定点 1.0，否则损失会凭空消失或把兵力算成负数")
                .isEqualTo(FixedPoint.ONE);
        assertThat(params.jitterMaxFixed()).isGreaterThanOrEqualTo(params.jitterMinFixed());
    }

    @Test
    @DisplayName("克制矩阵：四个兵种都在键里，只收兵种→兵种，建筑目标（WALL/TRAP）不进矩阵")
    void counterMatrixCoversAllUnitTypesAndSkipsBuildings() {
        BattleParams params = BattleParams.of(registry);
        assertThat(params.counterMatrix())
                .as("没有克制关系的兵种也必须在键里：缺键会让「遍历矩阵」的代码漏掉那个兵种，"
                        + "表现是它永远不触发克制，不报错，只在胜率矩阵上看起来偏弱")
                .containsOnlyKeys("INFANTRY", "CAVALRY", "ARCHER", "SIEGE");

        // unit_counter 表 8 行里，SIEGE→WALL 与 SIEGE→TRAP 的目标是建筑，不进矩阵 ⇒ 只剩 6 条
        assertThat(params.counterMatrix().get("INFANTRY")).containsExactlyInAnyOrder("CAVALRY", "ARCHER");
        assertThat(params.counterMatrix().get("CAVALRY"))
                .containsExactlyInAnyOrder("INFANTRY", "ARCHER", "SIEGE");
        assertThat(params.counterMatrix().get("ARCHER")).containsExactly("INFANTRY");
        assertThat(params.counterMatrix().get("SIEGE"))
                .as("攻城器的两条关系都是打建筑（WALL/TRAP），那由 unit 表的 vsBuildingBonus 表达")
                .isEmpty();
        assertThat(params.counterMatrix().values().stream().mapToLong(Set::size).sum())
                .isEqualTo(6L);
    }

    @Test
    @DisplayName("逐对覆盖被明确拒绝并点名是哪一行，而不是静默失效：内核目前没有逐对槽位")
    void perPairOverridesAreRejectedNotSilentlyIgnored() throws Exception {
        // unit_counter 表允许逐对填 bonusFixed/penaltyFixed，但 BattleRules 只有全局两个槽位。
        // 若照单全收，策划调了一对克制关系会发现毫无变化，然后开始怀疑整个克制系统 ——
        // 所以装配时当场报错，并且说清楚缺的是内核槽位、要改的是哪一行
        ConfigRegistry tweaked = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        String table = java.nio.file.Files.readString(
                locateTable("unit_counter.json"), java.nio.charset.StandardCharsets.UTF_8);
        String withOverride = table.replace("\"id\": \"infantry_vs_cavalry\",",
                "\"id\": \"infantry_vs_cavalry\",\n      \"bonusFixed\": \"0.30\",");
        assertThat(withOverride).as("夹具必须真的改到了那一行").isNotEqualTo(table);
        tweaked.reload("unit_counter", com.ironoath.config.cfg.UnitCounterCfg.class, withOverride);

        assertThatThrownBy(() -> BattleParams.of(tweaked))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("infantry_vs_cavalry")
                .hasMessageContaining("槽位");
        // 共享的 registry 没被动过：上面用的是独立实例
        assertThat(BattleParams.of(registry).counterMatrix()).isNotEmpty();
    }

    @Test
    @DisplayName("非法参数在构造期就被拒绝")
    void illegalConstructionIsRejected() {
        assertThatThrownBy(() -> new BattleParams(8, FixedPoint.parse("7.0"), FixedPoint.parse("0.20"),
                FixedPoint.parse("0.95"), FixedPoint.parse("1.05"),
                FixedPoint.parse("0.5"), FixedPoint.parse("0.3"), FixedPoint.parse("0.2"),
                FixedPoint.parse("0.25"), FixedPoint.parse("0.20"), FixedPoint.parse("0.05"),
                FixedPoint.parse("0.20"), FixedPoint.parse("0.35"), FixedPoint.parse("0.15"),
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counterMatrix");
        assertThatThrownBy(() -> new BattleParams(0, FixedPoint.ONE, 0L, 1L, 1L, 0L, 0L, 0L,
                0L, 0L, 0L, 0L, 0L, 0L, java.util.Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxRounds");
    }

    @Test
    @DisplayName("每次调用都重新读表，不缓存：配置热更必须在战斗这条路径上生效")
    void parametersAreRereadOnEveryCall() {
        BattleParams first = BattleParams.of(registry);
        BattleParams second = BattleParams.of(registry);
        assertThat(second).isEqualTo(first);
        // 值相等但必须是两次独立装配的结果（缓存会让 reload 之后仍然拿到旧参数）
        assertThat(BattleParams.of(registry).counterMatrix())
                .isNotSameAs(first.counterMatrix());
    }

    /**
     * 定位 contract/config 下的表文件。
     *
     * <p>从 cwd 逐级向上找：测试的工作目录是模块目录（server/game-config），
     * 而配置表在仓库根。写死 {@code Path.of("contract/config/x.json")} 在
     * 「从仓库根跑」与「从模块目录跑」两种方式下只有一种能成 ——
     * 那种只在某一种跑法下通过的测试，迟早会在 CI 上变成謎之失败。
     */
    private static Path locateTable(String fileName) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            Path candidate = dir.resolve("contract").resolve("config").resolve(fileName);
            if (java.nio.file.Files.exists(candidate)) {
                return candidate;
            }
            Path parent = dir.getParent();
            if (parent == null) {
                break;
            }
            dir = parent;
        }
        throw new IllegalStateException("找不到 contract/config/" + fileName
                + "，cwd=" + Path.of("").toAbsolutePath());
    }
}
