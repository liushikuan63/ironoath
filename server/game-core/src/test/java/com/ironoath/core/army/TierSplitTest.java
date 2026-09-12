package com.ironoath.core.army;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：战损按阶级分摊的验证 —— 总量守恒、比例正确、结果可复现。
 * 依赖：JUnit 5 + AssertJ；纯 Java，不需要容器。
 *
 * <p><b>「总量守恒」是本类最重要的断言</b>，它与 B05 修过的那个吞兵 bug 是同一条纪律：
 * 三排分摊 0.5/0.3/0.2 原来只向后顺延溢出，某排为空时它那一份损失直接消失，
 * 于是单一兵种军队的实际承伤只有设计值的一半，整个平衡矩阵失真 ——
 * 而它不报错、不产生负数，普通单测也发现不了。
 * 任何按比例分摊的实现都必须断言「Σ摊回量 == 输入总量」。
 *
 * <p><b>可复现性同样必须断言</b>：余数分给谁决定了战报里哪一级兵少了一个。
 * 若顺序依赖 HashMap 的迭代顺序，同一场战斗在两台机器上会给出不同的战报，
 * 而「凭 seed 复算战报」（铁律 4）就失效了。
 */
class TierSplitTest {

    private static Map<String, Long> counts(Object... idCountPairs) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < idCountPairs.length; i += 2) {
            map.put((String) idCountPairs[i], (Long) idCountPairs[i + 1]);
        }
        return map;
    }

    private static long sum(Map<String, Long> map) {
        return map.values().stream().mapToLong(Long::longValue).sum();
    }

    @Test
    @DisplayName("单阶级：损失全部落在它头上，超过持有量时按持有量截断")
    void singleTierTakesEverything() {
        assertThat(TierSplit.splitProportionally(counts("unit_infantry_t3", 100L), 37L))
                .containsExactly(Map.entry("unit_infantry_t3", 37L));
        assertThat(TierSplit.splitProportionally(counts("unit_infantry_t3", 100L), 100L))
                .as("全灭").containsExactly(Map.entry("unit_infantry_t3", 100L));
        assertThat(TierSplit.splitProportionally(counts("unit_infantry_t3", 100L), 500L))
                .as("请求扣 500 但只有 100：截断到持有量，绝不扣成负数")
                .containsExactly(Map.entry("unit_infantry_t3", 100L));
    }

    @Test
    @DisplayName("总量守恒：任意损失量下 Σ摊回量 == min(损失, 持有总量)")
    void totalIsAlwaysConserved() {
        Map<String, Long> mixed = counts(
                "unit_infantry_t1", 333L,
                "unit_infantry_t3", 77L,
                "unit_infantry_t5", 990L);
        long total = sum(mixed);
        // 逐个损失量扫一遍：余数分配的边界最容易在「刚好差一个」的地方出错
        for (long loss = 0L; loss <= total + 50L; loss++) {
            Map<String, Long> split = TierSplit.splitProportionally(mixed, loss);
            assertThat(sum(split))
                    .as("损失 %d 的摊回总量必须守恒", loss)
                    .isEqualTo(Math.min(loss, total));
            split.forEach((unitId, share) -> {
                assertThat(share).as("%s 承担量必须为正（0 不该出现在结果里）", unitId).isPositive();
                assertThat(share).as("%s 承担量不得超过它的持有量", unitId)
                        .isLessThanOrEqualTo(mixed.get(unitId));
            });
        }
    }

    @Test
    @DisplayName("按比例摊：数量多的阶级承担得多（比例是唯一规则，只在余数并列时才用字典序定序）")
    void sharesAreProportionalToCounts() {
        Map<String, Long> split = TierSplit.splitProportionally(
                counts("unit_infantry_t1", 100L, "unit_infantry_t5", 300L), 100L);
        // 100 : 300 摊 100 个损失 ⇒ 25 : 75
        assertThat(split).containsOnly(
                Map.entry("unit_infantry_t1", 25L),
                Map.entry("unit_infantry_t5", 75L));
    }

    @Test
    @DisplayName("余数按最大余数法分配，且分配顺序与入参 map 的顺序无关（可复现）")
    void remainderAssignmentIsDeterministic() {
        // 3 个阶级各 1 个兵，损失 2 ⇒ 无法整除，必须靠余数规则决定谁活下来
        Map<String, Long> a = counts("unit_infantry_t1", 1L, "unit_infantry_t3", 1L, "unit_infantry_t5", 1L);
        Map<String, Long> b = new LinkedHashMap<>();
        b.put("unit_infantry_t5", 1L);
        b.put("unit_infantry_t1", 1L);
        b.put("unit_infantry_t3", 1L);

        Map<String, Long> splitA = TierSplit.splitProportionally(a, 2L);
        Map<String, Long> splitB = TierSplit.splitProportionally(b, 2L);
        assertThat(sum(splitA)).isEqualTo(2L);
        assertThat(splitB)
                .as("入参顺序不同也必须给出同一份分摊，否则同一场战斗在两台机器上战报不同")
                .containsExactlyInAnyOrderEntriesOf(splitA);
        // 三个余数相同 ⇒ 按 unitId 字典序补齐，于是 t1 与 t3 各承担 1 个。
        // 「字典序小的先承担」是一条确定的约定而不是中性的巧合：unitId 里带阶级号，
        // 字典序小就是阶级低，所以并列时低阶兵先死。这条约定必须写在这里，
        // 否则以后有人把它改成「字典序大的优先」时，会以为只是换了个排序细节
        assertThat(splitA).containsOnlyKeys("unit_infantry_t1", "unit_infantry_t3");
    }

    @Test
    @DisplayName("零数量与空输入不产生条目，也不会让分摊算错")
    void zeroCountsAreSkipped() {
        assertThat(TierSplit.splitProportionally(counts("unit_infantry_t1", 0L), 10L)).isEmpty();
        assertThat(TierSplit.splitProportionally(counts(), 10L)).isEmpty();
        assertThat(TierSplit.splitProportionally(null, 10L)).isEmpty();
        assertThat(TierSplit.splitProportionally(counts("unit_infantry_t1", 10L), 0L)).isEmpty();
        assertThat(TierSplit.splitProportionally(
                counts("unit_infantry_t1", 0L, "unit_infantry_t3", 50L), 10L))
                .as("已打光的阶级不参与分摊")
                .containsExactly(Map.entry("unit_infantry_t3", 10L));
    }

    @Test
    @DisplayName("非法输入立刻报错：负损失、空 unitId、负数量")
    void rejectsIllegalInput() {
        assertThatThrownBy(() -> TierSplit.splitProportionally(counts("unit_infantry_t1", 10L), -1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为负");
        assertThatThrownBy(() -> TierSplit.splitProportionally(counts(" ", 10L), 5L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unitId");
        assertThatThrownBy(() -> TierSplit.splitProportionally(counts("unit_infantry_t1", -3L), 5L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为负");
    }
}
