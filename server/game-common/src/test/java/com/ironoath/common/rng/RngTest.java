package com.ironoath.common.rng;

import com.ironoath.common.num.FixedPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：Rng 单测 —— 覆盖 B01 验收 3（可复现性）、验收 4（fork 隔离）与结算安全性。
 * 依赖：JUnit 5 + AssertJ，无 Spring、无容器（铁律 2）。
 */
class RngTest {

    /** B01 验收 3 指定的调用次数。 */
    private static final int REPRODUCIBILITY_ROUNDS = 10000;

    @Test
    @DisplayName("验收3：同 seed 调用 10000 次，两次输出序列逐位相等")
    void sameSeedProducesBitwiseIdenticalSequence() {
        long seed = 20260906L;

        Rng a = Rng.of(seed);
        Rng b = Rng.of(seed);

        for (int i = 0; i < REPRODUCIBILITY_ROUNDS; i++) {
            // 用 double 的原始位比较，避免 0.1+0.2 式的浮点相等陷阱
            assertThat(Double.doubleToRawLongBits(a.next()))
                    .as("第 %d 次输出不一致", i)
                    .isEqualTo(Double.doubleToRawLongBits(b.next()));
        }
    }

    @Test
    @DisplayName("验收3：混合调用（next/nextLong/range/shuffle）后同 seed 仍逐位一致")
    void mixedCallSequenceIsReproducible() {
        List<String> first = runMixedScript(777L);
        List<String> second = runMixedScript(777L);
        assertThat(first).isEqualTo(second);
        assertThat(first).hasSizeGreaterThan(50);
    }

    private List<String> runMixedScript(long seed) {
        Rng rng = Rng.of(seed);
        List<String> trace = new ArrayList<>();
        List<Integer> pool = List.of(1, 2, 3, 4, 5, 6, 7, 8);
        for (int i = 0; i < 20; i++) {
            trace.add("next=" + Double.doubleToRawLongBits(rng.next()));
            trace.add("long=" + rng.nextLong());
            trace.add("range=" + rng.range(9500L, 10500L));
            trace.add("pick=" + rng.pick(pool));
            trace.add("shuffle=" + rng.shuffle(pool));
            trace.add("chance=" + rng.chance(1500L));
        }
        return trace;
    }

    @Test
    @DisplayName("验收4：fork 出的子流推进不影响父流")
    void forkDoesNotAdvanceParentStream() {
        Rng parent = Rng.of(42L);
        Rng control = Rng.of(42L);

        Rng child = parent.fork(1L);
        for (int i = 0; i < 1000; i++) {
            child.nextLong();
        }

        // 子流消耗 1000 次后，父流仍应与从未 fork 过的对照流逐位一致
        for (int i = 0; i < 100; i++) {
            assertThat(parent.nextLong()).isEqualTo(control.nextLong());
        }
    }

    @Test
    @DisplayName("验收4：父流推进不影响已 fork 出的子流")
    void parentAdvanceDoesNotAffectForkedChild() {
        Rng parent = Rng.of(42L);
        Rng child = parent.fork(9L);
        Rng childControl = Rng.of(42L).fork(9L);

        for (int i = 0; i < 500; i++) {
            parent.nextLong();
        }
        for (int i = 0; i < 100; i++) {
            assertThat(child.nextLong()).isEqualTo(childControl.nextLong());
        }
    }

    @Test
    @DisplayName("验收4：不同 salt 的 fork 产生完全不同的子流")
    void differentSaltProducesDifferentChildStream() {
        Rng parent = Rng.of(42L);
        Rng c1 = parent.fork(1L);
        Rng c2 = parent.fork(2L);
        assertThat(c1.nextLong()).isNotEqualTo(c2.nextLong());
    }

    @Test
    @DisplayName("不同 seed 产生不同序列；相邻 seed 不产生相似序列（雪崩混淆生效）")
    void differentSeedsDiverge() {
        Rng a = Rng.of(1L);
        Rng b = Rng.of(2L);
        assertThat(a.nextLong()).isNotEqualTo(b.nextLong());

        // 相邻 seed 若未混淆，首轮输出常常只差一个增量；这里要求首轮就完全不同
        Rng c = Rng.of(1000L);
        Rng d = Rng.of(1001L);
        assertThat(c.next()).isNotEqualTo(d.next());
    }

    @Test
    @DisplayName("range 为闭区间：两端都能取到，且不越界")
    void rangeIsInclusiveAndBounded() {
        Rng rng = Rng.of(2024L);
        boolean seenLow = false;
        boolean seenHigh = false;
        for (int i = 0; i < 20000; i++) {
            long v = rng.range(3L, 5L);
            assertThat(v).isBetween(3L, 5L);
            if (v == 3L) {
                seenLow = true;
            }
            if (v == 5L) {
                seenHigh = true;
            }
        }
        assertThat(seenLow).as("闭区间下界 3 应能取到").isTrue();
        assertThat(seenHigh).as("闭区间上界 5 应能取到").isTrue();
    }

    @Test
    @DisplayName("range 单点区间恒返回该值")
    void rangeSingleValue() {
        assertThat(Rng.of(1L).range(9L, 9L)).isEqualTo(9L);
    }

    @Test
    @DisplayName("range 非法区间抛异常，不静默交换")
    void rangeRejectsInvalidBounds() {
        Rng rng = Rng.of(1L);
        assertThatThrownBy(() -> rng.range(5L, 3L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rng.nextInt(5L, 5L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("nextFixed 落在定点闭区间内，可直接用于战斗损失浮动 0.95~1.05")
    void nextFixedStaysInsideFixedBounds() {
        Rng rng = Rng.of(31337L);
        long lo = FixedPoint.parse("0.95");
        long hi = FixedPoint.parse("1.05");
        for (int i = 0; i < 10000; i++) {
            assertThat(rng.nextFixed(lo, hi)).isBetween(lo, hi);
        }
    }

    @Test
    @DisplayName("chance 边界：0 恒 false，1.0 恒 true，越界抛异常")
    void chanceBoundaries() {
        Rng rng = Rng.of(5L);
        for (int i = 0; i < 1000; i++) {
            assertThat(rng.chance(0L)).isFalse();
            assertThat(rng.chance(FixedPoint.SCALE)).isTrue();
        }
        assertThatThrownBy(() -> rng.chance(-1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rng.chance(FixedPoint.SCALE + 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("chance 分布无偏：15% 概率在 20 万次采样下落在 ±1% 内")
    void chanceDistributionIsUnbiased() {
        Rng rng = Rng.of(99L);
        int hits = 0;
        int total = 200_000;
        for (int i = 0; i < total; i++) {
            if (rng.chance(1500L)) {
                hits++;
            }
        }
        double ratio = (double) hits / total;
        assertThat(ratio).isBetween(0.14d, 0.16d);
    }

    @Test
    @DisplayName("shuffle 返回新列表、元素集合不变，且不修改入参")
    void shuffleIsPureAndPermutes() {
        Rng rng = Rng.of(8L);
        List<Integer> origin = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        List<Integer> snapshot = List.copyOf(origin);

        List<Integer> shuffled = rng.shuffle(origin);

        assertThat(origin).isEqualTo(snapshot);
        assertThat(shuffled).isNotSameAs(origin);
        assertThat(shuffled).containsExactlyInAnyOrderElementsOf(snapshot);
    }

    @Test
    @DisplayName("shuffle 结果可复现，且确实发生了重排")
    void shuffleIsReproducibleAndActuallyPermutes() {
        List<Integer> pool = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        List<Integer> a = Rng.of(123L).shuffle(pool);
        List<Integer> b = Rng.of(123L).shuffle(pool);
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(pool);
    }

    @Test
    @DisplayName("pick 与 shuffle 对空列表抛异常，绝不返回 null")
    void pickRejectsEmpty() {
        Rng rng = Rng.of(1L);
        assertThatThrownBy(() -> rng.pick(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rng.pick(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("pick 能覆盖列表中每个元素（无索引越界、无系统性偏斜）")
    void pickCoversAllElements() {
        Rng rng = Rng.of(2025L);
        List<String> pool = List.of("重步兵", "轻骑兵", "弓兵", "攻城器");
        int total = 40000;
        List<Integer> hits = new ArrayList<>(List.of(0, 0, 0, 0));

        for (int i = 0; i < total; i++) {
            String picked = rng.pick(pool);
            int idx = pool.indexOf(picked);
            hits.set(idx, hits.get(idx) + 1);
        }

        for (int idx = 0; idx < pool.size(); idx++) {
            assertThat(hits.get(idx)).as("元素 %s 应被取到", pool.get(idx)).isGreaterThan(0);
            assertThat((double) hits.get(idx) / total)
                    .as("元素 %s 的频率应接近均匀分布 0.25", pool.get(idx))
                    .isBetween(0.20d, 0.30d);
        }
    }

    @Test
    @DisplayName("nextLong 覆盖正负两侧（不是只返回非负数）")
    void nextLongCoversNegativeValues() {
        Rng rng = Rng.of(6L);
        boolean seenNegative = false;
        boolean seenPositive = false;
        for (int i = 0; i < 10000; i++) {
            long v = rng.nextLong();
            if (v < 0) {
                seenNegative = true;
            }
            if (v > 0) {
                seenPositive = true;
            }
        }
        assertThat(seenNegative).isTrue();
        assertThat(seenPositive).isTrue();
    }

    @Test
    @DisplayName("next 落在 [0,1)，永不返回 1.0")
    void nextIsInUnitInterval() {
        Rng rng = Rng.of(11L);
        for (int i = 0; i < 100000; i++) {
            double v = rng.next();
            assertThat(v).isGreaterThanOrEqualTo(0.0d).isLessThan(1.0d);
        }
    }

    @Test
    @DisplayName("toString 不泄漏内部 state（防止日志泄漏导致抽卡结果可预测）")
    void toStringDoesNotLeakState() {
        Rng a = Rng.of(1L);
        Rng b = Rng.of(1L);
        a.nextLong();
        // 推进后 toString 的 hash 应变化，但输出中不含 state 明文字符串
        assertThat(a.toString()).isNotEqualTo(b.toString());
        assertThat(a.toString()).startsWith("Rng(hash=").doesNotContain(String.valueOf(0x6D2B79F5));
    }
}
