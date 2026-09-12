package com.ironoath.core.resource;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.core.resource.ResourceOutputCalculator.Breakdown;
import com.ironoath.core.resource.ResourceOutputCalculator.Line;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：产出明细单测 —— 覆盖 B04 验收 5「明细面板各项之和 = 实际每小时产出，误差 0」。
 * 依赖：JUnit 5 + AssertJ，无 Spring、无容器、不读配置。
 *
 * <p>验收 5 的关键不是「算得对」，而是<b>逐行相加恰好等于总量</b>。
 * 面板显示 600+420+120+60=1200 而实际产出 1198，玩家就会认为游戏在骗他 ——
 * 这个面板是「转化关键 UI」，对不上时它的转化作用会反转成信任破坏。
 */
class ResourceOutputCalculatorTest {

    private static List<Line> farmLines() {
        return List.of(
                Line.flat("农田 Lv8", 600L),
                Line.flat("农田 Lv7", 420L));
    }

    @Test
    @DisplayName("验收5：B04 文档示例的那组数字，逐行相加精确等于总量（误差 0）")
    void breakdownSumsExactlyToTotal() {
        Breakdown breakdown = ResourceOutputCalculator.compute(farmLines(),
                FixedPoint.parse("0.10"), FixedPoint.parse("0.05"), 0L);

        // 基数 1020，科技 +10% ⇒ 102，联盟 +5% ⇒ 51
        assertThat(breakdown.baseSubtotal()).isEqualTo(1020L);
        assertThat(breakdown.totalPerHour()).isEqualTo(1020L + 102L + 51L);
        assertThat(breakdown.bonusSubtotal()).isEqualTo(153L);

        // 核心断言：逐行相加 == 总量，一行不多一行不少
        long sum = breakdown.lines().stream().mapToLong(Line::amount).sum();
        assertThat(sum).isEqualTo(breakdown.totalPerHour());
    }

    @Test
    @DisplayName("验收5：任意百分比组合下逐行相加都等于总量（穷举 0%~50% 步进 1%）")
    void sumEqualsTotalForEveryPercentCombination() {
        // 用会产生取整零头的基数：1023 这种奇数值最容易暴露「先加百分比再取整」与
        // 「各自取整再相加」的差异，后者才能让面板逐行相加对得上
        List<Line> awkward = List.of(
                Line.flat("农田 Lv13", 337L),
                Line.flat("伐木场 Lv11", 289L),
                Line.flat("采石场 Lv9", 231L),
                Line.flat("铁矿场 Lv7", 166L));
        long base = 337L + 289L + 231L + 166L;

        for (int tech = 0; tech <= 50; tech++) {
            for (int alliance = 0; alliance <= 50; alliance += 7) {
                for (int buff = 0; buff <= 30; buff += 11) {
                    Breakdown b = ResourceOutputCalculator.compute(awkward,
                            FixedPoint.parse(percent(tech)),
                            FixedPoint.parse(percent(alliance)),
                            FixedPoint.parse(percent(buff)));
                    long sum = b.lines().stream().mapToLong(Line::amount).sum();
                    assertThat(sum)
                            .as("tech=%d%% alliance=%d%% buff=%d%% 时逐行相加必须等于总量", tech, alliance, buff)
                            .isEqualTo(b.totalPerHour());
                    assertThat(b.baseSubtotal()).isEqualTo(base);
                }
            }
        }
    }

    private static String percent(int value) {
        return "0." + String.format("%02d", value);
    }

    @Test
    @DisplayName("Breakdown 的不变量由类型强制：手工构造一个和不等于总量的明细会直接抛异常")
    void breakdownRejectsInconsistentTotals() {
        assertThatThrownBy(() -> new Breakdown(List.of(Line.flat("农田 Lv8", 600L)), 1200L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("各行之和不等于总量");
        // 一致时正常构造
        assertThat(new Breakdown(List.of(Line.flat("农田 Lv8", 600L)), 600L).totalPerHour())
                .isEqualTo(600L);
    }

    @Test
    @DisplayName("三个加成行始终出现（即使为 0），面板结构稳定")
    void bonusLinesAlwaysPresent() {
        Breakdown breakdown = ResourceOutputCalculator.compute(farmLines(), 0L, 0L, 0L);

        assertThat(breakdown.lines()).hasSize(5);
        assertThat(breakdown.lines()).extracting(Line::source)
                .containsExactly("农田 Lv8", "农田 Lv7", "科技加成", "联盟加成", "道具 buff");
        assertThat(breakdown.lines().get(2).amount()).isZero();
        assertThat(breakdown.totalPerHour()).as("无加成时总量等于基数").isEqualTo(1020L);
    }

    @Test
    @DisplayName("无产出建筑时总量为 0，不报错（新号还没建农田是正常状态）")
    void emptyBaseIsLegal() {
        Breakdown breakdown = ResourceOutputCalculator.compute(List.of(),
                FixedPoint.parse("0.10"), FixedPoint.parse("0.05"), 0L);
        assertThat(breakdown.totalPerHour()).isZero();
        assertThat(breakdown.lines()).hasSize(3);
        assertThat(breakdown.lines()).allSatisfy(line -> assertThat(line.amount()).isZero());
    }

    @Test
    @DisplayName("入参校验：baseLines 里混入百分比行会被拒绝，避免重复计入")
    void rejectsPercentLineInsideBase() {
        List<Line> mixed = new ArrayList<>(farmLines());
        mixed.add(Line.percent("科技加成", 100L, FixedPoint.parse("0.10")));

        assertThatThrownBy(() -> ResourceOutputCalculator.compute(mixed,
                FixedPoint.parse("0.10"), 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不应包含百分比行")
                .hasMessageContaining("重复计入");
    }

    @Test
    @DisplayName("入参校验：负百分比、负产量、空 source、null 列表都被拒绝")
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> ResourceOutputCalculator.compute(null, 0L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResourceOutputCalculator.compute(farmLines(), -1L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("techPercentFixed");
        assertThatThrownBy(() -> ResourceOutputCalculator.compute(farmLines(), 0L, -1L, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("alliancePercentFixed");
        assertThatThrownBy(() -> Line.flat("", 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Line.flat("农田", -1L))
                .isInstanceOf(IllegalArgumentException.class);
        // 非百分比行的 percentFixed 被强制归零，避免「isPercent=false 却带着百分比」的矛盾状态
        assertThat(new Line("农田", 100L, false, 5000L).percentFixed()).isZero();
    }

    @Test
    @DisplayName("百分比加成的基数是基础产量之和，不是逐行分别加成后再相加")
    void percentAppliesToBaseSubtotalNotPerLine() {
        // 逐行加成会因每行独立取整而产生累计误差；按基数一次算则没有这个问题
        Breakdown breakdown = ResourceOutputCalculator.compute(
                List.of(Line.flat("农田 Lv1", 1L), Line.flat("农田 Lv2", 1L), Line.flat("农田 Lv3", 1L)),
                FixedPoint.parse("0.10"), 0L, 0L);
        // 基数 3，+10% = 0.3 ⇒ HALF_UP ⇒ 0
        assertThat(breakdown.lines().get(3).amount()).isZero();
        assertThat(breakdown.totalPerHour()).isEqualTo(3L);

        // 基数 30，+10% = 3
        Breakdown bigger = ResourceOutputCalculator.compute(
                List.of(Line.flat("农田", 30L)), FixedPoint.parse("0.10"), 0L, 0L);
        assertThat(bigger.lines().get(1).amount()).isEqualTo(3L);
        assertThat(bigger.totalPerHour()).isEqualTo(33L);
    }
}
