package com.ironoath.common.num;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：B20 §五④ 那条取整裁决的<b>唯一</b>机器化表达（纯 Java，不起容器）。
 * 依赖：JUnit 5 + AssertJ + {@link FixedPoint}。
 *
 * <p><b>为什么这套表放在 game-common 而不是各消费点的测试里</b>：同一条口径要作用在
 * 建造、训练、行军三条时长与医院容量上。每个消费点各测一遍取整，
 * 就意味着口径改了要改四处、而漏掉的那一处仍然全绿 —— 与 {@code Rates} 本身同一条理由。
 */
class RatesTest {

    @Test
    @DisplayName("缩短时长：一律 ceil，且下限 1 秒（0 秒队列等于没有队列）")
    void shortenAlwaysCeilsAndNeverReachesZero() {
        assertThat(Rates.shortenSeconds(100L, 0L)).as("没有加成就原样返回").isEqualTo(100L);
        assertThat(Rates.shortenSeconds(100L, -500L)).as("负数按无加成处理（守卫在调用方之前）").isEqualTo(100L);
        assertThat(Rates.shortenSeconds(100L, 900L)).as("100 × 0.91 = 91 整").isEqualTo(91L);
        assertThat(Rates.shortenSeconds(100L, 3300L)).isEqualTo(67L);
        assertThat(Rates.shortenSeconds(7L, 5000L))
                .as("3.5 秒 → 4 秒。向下取整就是每次升级少给玩家半秒")
                .isEqualTo(4L);
        assertThat(Rates.shortenSeconds(1L, 5000L))
                .as("1 秒减一半仍是 1 秒：ceil 而不是 0")
                .isEqualTo(1L);
        assertThat(Rates.shortenSeconds(10L, FixedPoint.ONE))
                .as("加成 100% 压到下限，不是 0")
                .isEqualTo(1L);
        assertThat(Rates.shortenSeconds(10L, FixedPoint.ONE * 5L))
                .as("配错成 500% 也不该出负数（负数会顺着 finishAt 变成一个已经完成的队列）")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("基础时长不是正数就抛：0 秒是调用方算错了，不是「本来就瞬间完成」")
    void nonPositiveBaseIsALoudFailure() {
        assertThatThrownBy(() -> Rates.shortenSeconds(0L, 500L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Rates.shortenSeconds(-3L, 0L))
                .as("即使没有加成也不能放过 0 —— 那会让队列的完成时刻等于开始时刻")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("放大绝对值：HALF_UP（不跟 ceil），基数非正时是 0")
    void scaleUpRoundsHalfUpAndRespectsZeroBase() {
        assertThat(Rates.scaleUp(100L, 0L)).isEqualTo(100L);
        assertThat(Rates.scaleUp(100L, 2000L)).as("100 × 1.2 = 120").isEqualTo(120L);
        assertThat(Rates.scaleUp(5L, 2500L))
                .as("5 × 1.25 = 6.25 → HALF_UP 是 6；套 ceil 会系统性偏大")
                .isEqualTo(6L);
        assertThat(Rates.scaleUp(5L, 7500L))
                .as("5 × 1.75 = 8.75 → 9")
                .isEqualTo(9L);
        assertThat(Rates.scaleUp(0L, 5000L))
                .as("基数为 0 就是 0：加成不能凭空造出一格不存在的医院/负载")
                .isZero();
        assertThat(Rates.scaleUp(-10L, 5000L)).isZero();
    }
}
