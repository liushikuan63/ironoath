package com.ironoath.core.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：收益分摊的守恒与确定性（集结合并行军的掠夺与掉落分给成员）。
 * 依赖：JUnit 5 + AssertJ，无 Spring。
 *
 * <p><b>本类只盯两件事</b>：Σ分回 == 要分的总量（一分都不能掉），以及零头归属确定
 * （同一场战斗重放必须得到同一份分配，铁律 4）。这两条都不是「算对了就行」的事：
 * 掉一分会让国库与玩家钱包对不上账，而零头随机会让战报无法复算 ——
 * 两者都不报错，只能靠断言钉住。
 */
class RewardSplitTest {

    @Test
    @DisplayName("单受益人恒等于全额：普通行军走的就是这条路")
    void singleBeneficiaryTakesEverything() {
        Map<String, Long> shares = RewardSplit.byWeight(Map.of("P1", 1L), 12_345L);

        assertThat(shares).containsExactly(entry("P1", 12_345L));
    }

    @Test
    @DisplayName("按承诺兵力比例分：300/200 出的人分 12000 得 7200 与 4800")
    void splitsProportionallyToWeights() {
        Map<String, Long> weights = new LinkedHashMap<>();
        weights.put("P1", 300L);
        weights.put("P2", 200L);

        Map<String, Long> shares = RewardSplit.byWeight(weights, 12_000L);

        assertThat(shares).containsEntry("P1", 7_200L).containsEntry("P2", 4_800L);
        assertThat(RewardSplit.sum(shares)).isEqualTo(12_000L);
    }

    @Test
    @DisplayName("除不尽时零头按余数给、余数相同按 id 字典序：三次重放结果必须一样")
    void remainderIsDeterministic() {
        Map<String, Long> weights = new LinkedHashMap<>();
        weights.put("pa", 100L);
        weights.put("pb", 100L);
        weights.put("pc", 100L);

        Map<String, Long> first = RewardSplit.byWeight(weights, 100L);
        Map<String, Long> second = RewardSplit.byWeight(new LinkedHashMap<>(weights), 100L);

        assertThat(first).as("100 兵对三个各出 100 的人：整数部分 33×3，剩下的 1 必须有确定归属")
                .containsEntry("pa", 34L).containsEntry("pb", 33L).containsEntry("pc", 33L);
        assertThat(second).isEqualTo(first);
        assertThat(RewardSplit.sum(first)).isEqualTo(100L);
    }

    @Test
    @DisplayName("零头优先给余数大的人，而不是字典序靠前的人")
    void largerRemainderWinsTheRemainder() {
        Map<String, Long> weights = new LinkedHashMap<>();
        // pa 字典序在前但只出 1 人份，pb 出 2 人份：100 分给 1:2 是 33.33/66.67，
        // 整数 33+66=99，余数 pa=0.33、pb=0.67 ⇒ 零头应给 pb
        weights.put("pa", 1L);
        weights.put("pb", 2L);

        Map<String, Long> shares = RewardSplit.byWeight(weights, 100L);

        assertThat(shares).containsEntry("pa", 33L).containsEntry("pb", 67L);
    }

    @Test
    @DisplayName("金额为 0 返回空表：没抢到东西不该产生一堆 0 元入账记录")
    void zeroAmountYieldsNothing() {
        assertThat(RewardSplit.byWeight(Map.of("P1", 300L), 0L)).isEmpty();
    }

    @Test
    @DisplayName("没有任何有效权重时直接炸：收益不能无人认领")
    void throwsWhenNobodyCanReceive() {
        Map<String, Long> weights = new LinkedHashMap<>();
        weights.put("P1", 0L);
        weights.put(null, 10L);
        weights.put(" ", 5L);

        assertThatThrownBy(() -> RewardSplit.byWeight(weights, 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有任何有效权重");
        assertThatThrownBy(() -> RewardSplit.byWeight(null, 100L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("非法入参被拒：负金额不允许分摊")
    void negativeAmountIsRejected() {
        assertThatThrownBy(() -> RewardSplit.byWeight(Map.of("P1", 1L), -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得为负");
    }

    @Test
    @DisplayName("大额收益 × 大权重不溢出 long：守恒仍然成立")
    void hugeValuesStillConserve() {
        Map<String, Long> weights = new LinkedHashMap<>();
        weights.put("P1", 9_000_000_000_000L);
        weights.put("P2", 7_000_000_000_000L);
        long amount = 9_000_000_000_000L;

        Map<String, Long> shares = RewardSplit.byWeight(weights, amount);

        assertThat(RewardSplit.sum(shares))
                .as("乘积超出 long 时走 BigInteger 分支，一分都不能少").isEqualTo(amount);
        assertThat(shares.get("P1")).isGreaterThan(shares.get("P2"));
    }
}
