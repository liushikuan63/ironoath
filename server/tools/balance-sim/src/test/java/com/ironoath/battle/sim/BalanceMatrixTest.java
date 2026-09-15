package com.ironoath.battle.sim;

import com.ironoath.battle.UnitType;
import com.ironoath.config.ConfigRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B02 验收 4 / B05 验收 8 的矩阵判定证据。
 *
 * <p>两条验收标准在"单向克制对"上直接冲突（一边要求 38%~62%，另一边要求胜率差 >= 15%），
 * 口径裁定写在 {@code unit_counter.json} 的 designNote 里；本测试跑的就是那份口径。
 *
 * <p><b>为什么第一条用真实配置表</b>：矩阵的全部意义是"表里的数值是否平衡"，
 * 拿一份测试内自造的夹具去验只会证明夹具自己对。表被改动（哪怕只改一档攻防）时
 * 这条用例必须有机会变红 —— 那是它存在的唯一理由。
 */
class BalanceMatrixTest {

    @Test
    @DisplayName("B02 验收 4 + B05 验收 8：真实配置表下矩阵判定全绿，且单向对确实来自 unit_counter 表")
    void matrixSatisfiesBothAcceptanceCriteria() {
        ConfigRegistry configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        BattleParamsResolver resolver = new BattleParamsResolver(configs);

        // 单向对从表推导：若你是有意改克制关系，请同步 B02 验收 4 / B05 验收 8 与
        // unit_counter.json 的 designNote，并更新本断言 —— 不要让口径悄悄漂走
        assertThat(BalanceMatrix.oneWayPairs(configs))
                .containsExactlyInAnyOrder("CAVALRY>ARCHER", "CAVALRY>SIEGE");

        BalanceMatrix.Outcome outcome = BalanceMatrix.run(resolver, resolver.rules(),
                resolver.unitStats(1), BalanceMatrix.oneWayPairs(configs), 1000, 1000);

        assertThat(outcome.violations())
                .as("B02 验收 4（对称对双向落 38%%~62%%）与 B05 验收 8（单向克制领先 >= 15%%）")
                .isEmpty();
        assertThat(outcome.cells()).as("四兵种两两共 12 个方向").hasSize(12);

        // 反空转：单向对必须打出一边倒。若内核没跑起来（全平局），judge 的 gap 断言与这两条都会红
        Map<String, Double> rates = BalanceMatrix.rateByDirection(outcome);
        assertThat(rates.get("CAVALRY>ARCHER")).isGreaterThan(0.9);
        assertThat(rates.get("ARCHER>CAVALRY")).isLessThan(0.1);
        assertThat(rates.get("CAVALRY>SIEGE")).isGreaterThan(0.9);
    }

    @Test
    @DisplayName("判定器：对称对任一方向超界即违规（合成数据，不跑内核）")
    void judgeRejectsSymmetricOutOfRange() {
        List<String> violations = BalanceMatrix.judge(List.of(
                new BalanceMatrix.Pair(UnitType.INFANTRY, UnitType.CAVALRY, 0.35d, 0.50d, null)));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("INFANTRY>CAVALRY", "B02 验收 4");
    }

    @Test
    @DisplayName("判定器：单向克制领先不足 15% 即违规（合成数据）")
    void judgeRejectsWeakOneWayGap() {
        List<String> violations = BalanceMatrix.judge(List.of(
                new BalanceMatrix.Pair(UnitType.CAVALRY, UnitType.ARCHER, 0.60d, 0.50d, UnitType.CAVALRY)));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("CAVALRY>ARCHER", "B05 验收 8");
    }

    @Test
    @DisplayName("判定器：克制方向写反（反被压制）同样是违规（合成数据）")
    void judgeRejectsInvertedOneWay() {
        List<String> violations = BalanceMatrix.judge(List.of(
                new BalanceMatrix.Pair(UnitType.CAVALRY, UnitType.ARCHER, 0.40d, 0.60d, UnitType.CAVALRY)));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("CAVALRY>ARCHER", "B05 验收 8");
    }

    @Test
    @DisplayName("判定器：全部合规时不得报违规（防判定器恒红的假信号）")
    void judgeAcceptsAllWithinRange() {
        List<String> violations = BalanceMatrix.judge(List.of(
                new BalanceMatrix.Pair(UnitType.INFANTRY, UnitType.CAVALRY, 0.50d, 0.51d, null),
                new BalanceMatrix.Pair(UnitType.CAVALRY, UnitType.ARCHER, 1.00d, 0.00d, UnitType.CAVALRY)));

        assertThat(violations).isEmpty();
    }
}
