package com.ironoath.core.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：科技这一位的<b>构造期纪律</b>与惰性结算的纯 Java 单测（B20 块①，不起 Spring）。
 * 依赖：JUnit 5 + AssertJ。
 *
 * <p><b>为什么这一层值得单独测</b>：研究能不能开始、什么时候算完成、取消会不会连等级一起清掉，
 * 这三件事全部落在这一个值对象的四位字段上。集成测试（{@code TechEndpointTest}）测的是走完整条链路
 * 之后的读数，而这里测的是<b>构造不出坏状态</b>：坏状态一旦能构造出来，
 * 「空闲却带着完成时刻」这种组合就会在某个并发路径里悄悄出现，症状是每次读取都重复结算。
 */
class PlayerTechTest {

    private static final long T0 = 1_760_000_000_000L;

    @Test
    @DisplayName("空的一位读起来是「一行都没研究、队列空着」，而不是 null")
    void emptyMeansNothingResearchedAndIdle() {
        PlayerTech empty = PlayerTech.empty();
        assertThat(empty.isResearching()).isFalse();
        assertThat(empty.levelOf("tech_agri_wood")).as("缺失即 0").isZero();
        assertThat(empty.levels()).isEmpty();
        assertThat(empty.remainingSeconds(T0)).isZero();
    }

    @Test
    @DisplayName("账本里不许有 0 或负数等级：没研究过的行根本不该进账本")
    void ledgerRejectsZeroPlaceholders() {
        Map<String, Integer> withZero = new LinkedHashMap<>();
        withZero.put("tech_agri_wood", 0);
        assertThatThrownBy(() -> new PlayerTech(withZero, null, null, 0L, 0L))
                .as("0 占位是 B20 §一 明确不要的做法：11 行各存一个 0，以后每加一行就多一列永久为 0 的字段")
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Integer> negative = new LinkedHashMap<>();
        negative.put("tech_mil_atk", -2);
        assertThatThrownBy(() -> new PlayerTech(negative, null, null, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Integer> nullLevel = new LinkedHashMap<>();
        nullLevel.put("tech_mil_def", null);
        assertThatThrownBy(() -> new PlayerTech(nullLevel, null, null, 0L, 0L))
                .as("null 等级会被读路径拆箱成 NPE，构造期就要挡住")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("空闲态三位必须全空：finishAt=0 会被读成「1970 年就完成了」，于是每次读取都重复结算")
    void idleStateMustHaveAllThreeSlotFieldsClear() {
        assertThatThrownBy(() -> new PlayerTech(Map.of(), null, 0L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlayerTech(Map.of(), null, null, T0, 0L))
                .as("有开始时刻却没说在研究什么")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlayerTech(Map.of(), "  ", null, 0L, 0L))
                .as("空白 id 不是「空闲」，是笔误")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("研究中三位都必须为正，且完成时刻必须晚于开始时刻")
    void researchingStateGuardsItsOwnNumbers() {
        assertThatThrownBy(() -> new PlayerTech(Map.of(), "tech_agri_wood", null, T0, 13L))
                .as("缺 finishAt：读路径要么 NPE 要么当成已完成，两种都不是「没在研究」")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlayerTech(Map.of(), "tech_agri_wood", T0 + 13_000L, T0, 0L))
                .as("0 秒的研究等于没有队列（§五④ 的 ceil 就是为这条）")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlayerTech(Map.of(), "tech_agri_wood", T0, T0 + 13_000L, 13L))
                .as("完成早于开始：那是个永远结算是也不会变空闲的状态")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("开始研究：记的是目标完成时刻与总时长，队列一位只容得下一项")
    void startFillsTheSlotAndRefusesASecondOne() {
        PlayerTech started = PlayerTech.empty().started("tech_agri_wood", T0 + 13_000L, T0, 13L);
        assertThat(started.isResearching()).isTrue();
        assertThat(started.researchingId()).isEqualTo("tech_agri_wood");
        assertThat(started.remainingSeconds(T0)).as("刚开局：13 秒").isEqualTo(13L);

        assertThatThrownBy(() -> started.started("tech_agri_stone", T0 + 20_000L, T0, 20L))
                .as("一次一队列（§五①）落在结构上：占着槽位再开一项是程序错，不是玩家的错")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("剩余时间绝不为负，且不足一秒向上取整（不把还在跑的研究显示成「已完成」）")
    void remainingNeverGoesNegativeOrZeroWhileRunning() {
        PlayerTech running = PlayerTech.empty().started("tech_agri_wood", T0 + 13_000L, T0, 13L);
        assertThat(running.remainingSeconds(T0 + 13_000L)).as("正好到点").isZero();
        assertThat(running.remainingSeconds(T0 + 60_000L)).as("过期一分钟也不会变成负数").isZero();
        assertThat(running.remainingSeconds(T0 + 12_500L))
                .as("还剩半秒：取整成 1，界面不会提前显示完成")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("结算：到点就把等级记进账本并腾空队列；没到点或本来就空闲时什么都不做（可反复调用）")
    void settlementIsLazyIdempotentAndKeepsTheRestOfTheLedger() {
        Map<String, Integer> already = new LinkedHashMap<>();
        already.put("tech_agri_stone", 4);
        PlayerTech busy = new PlayerTech(already, "tech_agri_wood", T0 + 13_000L, T0, 13L);

        assertThat(busy.settled(T0 + 12_999L)).as("差一秒也没到点：不许提前结算").isNull();
        PlayerTech.Completion done = busy.settled(T0 + 13_000L);
        assertThat(done).isNotNull();
        assertThat(done.techId()).isEqualTo("tech_agri_wood");
        assertThat(done.level()).as("从 0 级到 1 级").isEqualTo(1);
        assertThat(done.tech().levelOf("tech_agri_wood")).isEqualTo(1);
        assertThat(done.tech().levelOf("tech_agri_stone"))
                .as("结算只动被研究的那一行，别的一级都不许掉")
                .isEqualTo(4);
        assertThat(done.tech().isResearching()).isFalse();
        assertThat(done.tech().settled(T0 + 99_000L))
                .as("已经腾空了：再读一百次也不会再长出一级（惰性结算必须幂等）")
                .isNull();
    }

    @Test
    @DisplayName("已有等级的行再研究：结算出来的是 +1，不是覆盖成 1")
    void settlementIncrementsInsteadOfOverwriting() {
        PlayerTech busy = new PlayerTech(Map.of("tech_agri_wood", 7), "tech_agri_wood",
                T0 + 13_000L, T0, 13L);
        PlayerTech.Completion done = busy.settled(T0 + 14_000L);
        assertThat(done.level()).isEqualTo(8);
        assertThat(done.tech().levelOf("tech_agri_wood")).isEqualTo(8);
    }

    @Test
    @DisplayName("取消只腾空队列：已研究的等级一位都不掉，且空闲时取消是原地返回")
    void cancelClearsTheQueueAndNothingElse() {
        PlayerTech busy = new PlayerTech(Map.of("tech_mil_atk", 3), "tech_agri_wood",
                T0 + 13_000L, T0, 13L);
        PlayerTech cancelled = busy.cancelled();
        assertThat(cancelled.isResearching()).isFalse();
        assertThat(cancelled.levelOf("tech_mil_atk")).as("取消不该动已完成的研究").isEqualTo(3);
        assertThat(cancelled.levelOf("tech_agri_wood")).as("取消掉的那一级从没进过账本").isZero();
        assertThat(cancelled.levels()).isEqualTo(busy.levels());
        assertThat(PlayerTech.empty().cancelled()).as("空闲时取消是原地返回（抛不抛由服务层判）")
                .isEqualTo(PlayerTech.empty());
    }

    @Test
    @DisplayName("账本是只读副本：拿到存档外面改不动它")
    void ledgerIsImmutable() {
        PlayerTech one = new PlayerTech(Map.of("tech_agri_wood", 2), null, null, 0L, 0L);
        assertThatThrownBy(() -> one.levels().put("tech_agri_stone", 9))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
