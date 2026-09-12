package com.ironoath.battle;

import com.ironoath.common.config.BattleParamsSource;
import com.ironoath.common.num.FixedPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：验证 {@code BattleRules.from} 是「配置 → 内核」的唯一映射处，且映射不丢不改。
 * 依赖：JUnit 5 + AssertJ；纯 Java（game-battle 读不到配置表，所以参数用内联实现喂进来）。
 *
 * <p><b>这条测试的存在理由是防止两个消费方分叉</b>：线上（game-web）与调数值的 CLI（balance-sim）
 * 都必须走 {@code BattleRules.from}。映射逻辑一旦被复制到任何一边，
 * 那边就会慢慢与真源分叉，而表现是「CLI 的胜率矩阵很漂亮、线上完全不是那个手感」，两边都不报错。
 * 所以这里逐项断言映射是直译：参数进什么值，BattleRules 就是什么值。
 */
class BattleRulesFromTest {

    /** 一份可辨认的参数：每一项都取不同的值，这样「串了位」会立刻被看出来。 */
    private record Params(Map<String, Set<String>> counterMatrix) implements BattleParamsSource {

        @Override public int maxRounds() { return 8; }
        @Override public long lanchesterK() { return FixedPoint.parse("7.0"); }
        @Override public long hpDefenseWeightFixed() { return FixedPoint.parse("0.20"); }
        @Override public long jitterMinFixed() { return FixedPoint.parse("0.95"); }
        @Override public long jitterMaxFixed() { return FixedPoint.parse("1.05"); }
        @Override public long rowFrontFixed() { return FixedPoint.parse("0.5"); }
        @Override public long rowMidFixed() { return FixedPoint.parse("0.3"); }
        @Override public long rowBackFixed() { return FixedPoint.parse("0.2"); }
        @Override public long counterBonusFixed() { return FixedPoint.parse("0.25"); }
        @Override public long counterPenaltyFixed() { return FixedPoint.parse("0.20"); }
        @Override public long drawGapRatioFixed() { return FixedPoint.parse("0.05"); }
        @Override public long pveDeadRatioFixed() { return FixedPoint.parse("0.20"); }
        @Override public long pvpAttackerDeadRatioFixed() { return FixedPoint.parse("0.35"); }
        @Override public long pvpDefenderDeadRatioFixed() { return FixedPoint.parse("0.15"); }
    }

    private static Map<String, Set<String>> realMatrix() {
        Map<String, Set<String>> matrix = new LinkedHashMap<>();
        matrix.put("INFANTRY", setOf("CAVALRY", "ARCHER"));
        matrix.put("CAVALRY", setOf("INFANTRY", "ARCHER", "SIEGE"));
        matrix.put("ARCHER", setOf("INFANTRY"));
        matrix.put("SIEGE", Set.of());
        return matrix;
    }

    private static Set<String> setOf(String... names) {
        return new LinkedHashSet<>(java.util.Arrays.asList(names));
    }

    @Test
    @DisplayName("逐项直译：参数进什么值，BattleRules 就是什么值（串位会立刻被发现）")
    void everyFieldIsMappedOneToOne() {
        Params params = new Params(realMatrix());
        BattleRules rules = BattleRules.from(params);

        assertThat(rules.maxRounds()).isEqualTo(8);
        assertThat(rules.lanchesterK()).isEqualTo(params.lanchesterK());
        assertThat(rules.hpDefenseWeightFixed()).isEqualTo(params.hpDefenseWeightFixed());
        assertThat(rules.jitterMinFixed()).isEqualTo(params.jitterMinFixed());
        assertThat(rules.jitterMaxFixed()).isEqualTo(params.jitterMaxFixed());
        assertThat(rules.rowFrontFixed()).isEqualTo(params.rowFrontFixed());
        assertThat(rules.rowMidFixed()).isEqualTo(params.rowMidFixed());
        assertThat(rules.rowBackFixed()).isEqualTo(params.rowBackFixed());
        assertThat(rules.counterBonusFixed()).isEqualTo(params.counterBonusFixed());
        assertThat(rules.counterPenaltyFixed()).isEqualTo(params.counterPenaltyFixed());
        assertThat(rules.drawGapRatioFixed()).isEqualTo(params.drawGapRatioFixed());
        assertThat(rules.pveDeadRatioFixed()).isEqualTo(params.pveDeadRatioFixed());
        // 这两个最容易串位：都是「PVP 死亡比例」，写反了不会报错，
        // 只会让攻方与守方的战损比例互换 —— 而那是玩家一眼就能感觉到的不公平
        assertThat(rules.pvpAttackerDeadRatioFixed()).isEqualTo(params.pvpAttackerDeadRatioFixed());
        assertThat(rules.pvpDefenderDeadRatioFixed()).isEqualTo(params.pvpDefenderDeadRatioFixed());
        assertThat(rules.pvpAttackerDeadRatioFixed())
                .as("攻方死亡比例必须高于守方（B05 的既定口径），串位会让这条不再成立")
                .isGreaterThan(rules.pvpDefenderDeadRatioFixed());
    }

    @Test
    @DisplayName("兵种名翻译成 UnitType，克制关系逐条对得上")
    void counterMatrixIsTranslatedToUnitTypes() {
        BattleRules rules = BattleRules.from(new Params(realMatrix()));

        assertThat(rules.counters(UnitType.INFANTRY, UnitType.CAVALRY)).isTrue();
        assertThat(rules.counters(UnitType.INFANTRY, UnitType.ARCHER)).isTrue();
        assertThat(rules.counters(UnitType.CAVALRY, UnitType.SIEGE)).isTrue();
        assertThat(rules.counters(UnitType.ARCHER, UnitType.INFANTRY)).isTrue();
        assertThat(rules.counters(UnitType.SIEGE, UnitType.INFANTRY))
                .as("攻城器的克制目标是建筑（WALL/TRAP），不进兵种矩阵").isFalse();
        assertThat(rules.counters(UnitType.ARCHER, UnitType.CAVALRY)).isFalse();
        assertThat(rules.counterMatrix())
                .as("四个兵种都要在矩阵里，缺键会让遍历矩阵的代码漏掉它")
                .containsOnlyKeys(UnitType.values());
    }

    @Test
    @DisplayName("未知兵种名当场报错：静默跳过等于让那个兵种永远不触发克制")
    void unknownUnitNameIsRejected() {
        Map<String, Set<String>> bad = realMatrix();
        bad.put("INFANTRY", setOf("DRAGON"));
        assertThatThrownBy(() -> BattleRules.from(new Params(bad)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DRAGON")
                .hasMessageContaining("克制");
        assertThatThrownBy(() -> BattleRules.from(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("params");
    }

    @Test
    @DisplayName("地形加成目前是空的：地形数值表还没落地，落地后应当加到端口上而不是在调用方各填一份")
    void terrainBonusesAreEmptyUntilTheTableLands() {
        BattleRules rules = BattleRules.from(new Params(realMatrix()));
        for (TerrainType terrain : TerrainType.values()) {
            assertThat(rules.terrainAttack(terrain)).isZero();
            assertThat(rules.terrainDefense(terrain)).isZero();
        }
    }
}
