package com.ironoath.battle.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B05 验收 4 的量具自身的证据：分位算法与判定口径。
 *
 * <p><b>为什么这两条要单测</b>：{@code --settle-bench} 的产出就是几个分位数，
 * 而秩算错一位会把 p99 **报低** —— 那是这个量具唯一要防的假绿（一个报低的 p99
 * 会让"结算变慢"这件事永远不被发现）。判定口径同理：它必须真的拿"超过阈值"当失败。
 */
class SettleBenchTest {

    @Test
    @DisplayName("最近秩分位：取真实样本，绝不插值；边界不越界")
    void percentileUsesNearestRank() {
        // 1..10 纳秒 -> 毫秒，刻意用整数好读：第 k 个样本就是 k ns = k/1e6 ms
        long[] sorted = new long[10];
        for (int i = 0; i < 10; i++) {
            sorted[i] = (i + 1) * 1_000_000L;
        }
        // 最近秩口径：rank = ceil(q*n) - 1
        assertThat(BalanceCli.percentileMs(sorted, 0.50)).as("p50 取第 5 个样本（ceil(5)-1=4）")
                .isEqualTo(5.0d);
        assertThat(BalanceCli.percentileMs(sorted, 0.99)).as("p99 取第 10 个样本")
                .isEqualTo(10.0d);
        assertThat(BalanceCli.percentileMs(sorted, 1.0)).as("q=1 不许越界").isEqualTo(10.0d);
        assertThat(BalanceCli.percentileMs(sorted, 0.0)).as("q=0 取最小的那个").isEqualTo(1.0d);
        // 插值实现会给出 5.5（p50）与 9.91（p99），这两条就是用来钉死"不许插值"的
        assertThat(BalanceCli.percentileMs(sorted, 0.50)).isNotEqualTo(5.5d);
        assertThat(BalanceCli.percentileMs(sorted, 0.99)).isNotEqualTo(9.91d);
    }

    @Test
    @DisplayName("判定口径：p99 超过阈值必须为假（假绿的形状是「超了还说通过」）")
    void judgmentFailsWhenP99ExceedsLimit() {
        assertThat(BalanceCli.settleWithinBudget(1.241d, 5L)).as("预算内").isTrue();
        assertThat(BalanceCli.settleWithinBudget(5.0d, 5L)).as("正好等于阈值算过").isTrue();
        assertThat(BalanceCli.settleWithinBudget(5.001d, 5L)).as("超一点点也算超").isFalse();
    }
}
