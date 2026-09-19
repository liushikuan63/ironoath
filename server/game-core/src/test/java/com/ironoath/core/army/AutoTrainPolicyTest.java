package com.ironoath.core.army;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：自动续训 / 自动补兵的策略判定用例（B25-S2，裁决③(a)）。纯领域类，无 Spring、无存储。
 *
 * <p><b>这些用例盯的是四件事</b>：① 它**不是一个布尔开关**（预算用尽必须停，且留下原因）；
 * ② 停下之后**不会自己恢复**（否则"等资源够了自动接着排"等于无限支出）；
 * ③ 队列忙着是"等"而不是"停"（续训的时机是上一批完成之后，不是叠着排）；
 * ④ 补兵只补缺口且补满即不动（补满不是"停"，它要留着等下一条命阵亡）。
 */
class AutoTrainPolicyTest {

    @Test
    @DisplayName("关掉的策略：判定是 STOPPED（不是 WAIT，也不是排下一批）")
    void offIsStopped() {
        AutoTrainPolicy off = AutoTrainPolicy.off();
        assertThat(off.enabled()).isFalse();
        assertThat(off.plan(0L, false, true).decision())
                .isEqualTo(AutoTrainPolicy.AutoTrainDecision.STOPPED);
    }

    @Test
    @DisplayName("开着、队列空、资源够 → 排下一批；队列忙着 → 等（不是停）")
    void waitWhenQueueIsBusy() {
        AutoTrainPolicy on = AutoTrainPolicy.on("unit_infantry_t1", 100L, 3);
        assertThat(on.plan(0L, true, true).decision())
                .as("上一批还在训：此刻什么都不做，但不是停下来")
                .isEqualTo(AutoTrainPolicy.AutoTrainDecision.WAIT);
        AutoTrainPolicy.Outcome outcome = on.plan(0L, false, true);
        assertThat(outcome.decision()).isEqualTo(AutoTrainPolicy.AutoTrainDecision.QUEUE_NEXT);
        assertThat(outcome.count()).as("续训模式每批就是 batchCount").isEqualTo(100L);
    }

    @Test
    @DisplayName("预算：排一批就少一批，用尽即停并留下原因（不是一个半开的 enable=true）")
    void budgetDecrementsAndStops() {
        AutoTrainPolicy policy = AutoTrainPolicy.on("unit_infantry_t1", 100L, 2);
        assertThat(policy.batchBudget()).isEqualTo(2);

        policy = policy.afterNextBatch();
        assertThat(policy.enabled()).isTrue();
        assertThat(policy.batchBudget()).isEqualTo(1);
        assertThat(policy.stopReason()).as("还有预算时没有停止原因").isNull();
        assertThat(policy.plan(0L, false, true).decision())
                .isEqualTo(AutoTrainPolicy.AutoTrainDecision.QUEUE_NEXT);

        policy = policy.afterNextBatch();
        assertThat(policy.enabled()).as("最后一批排完就关掉，不留半开状态").isFalse();
        assertThat(policy.batchBudget()).isZero();
        assertThat(policy.stopReason()).as("玩家要能读到一句人话的原因").contains("预算");
        assertThat(policy.plan(0L, false, true).decision())
                .isEqualTo(AutoTrainPolicy.AutoTrainDecision.STOPPED);
    }

    @Test
    @DisplayName("资源不足 ⇒ 停，且**不会**因为下次资源够了就自己恢复（那是无限支出）")
    void resourceShortfallStopsForGood() {
        AutoTrainPolicy policy = AutoTrainPolicy.on("unit_infantry_t1", 100L, 5);
        AutoTrainPolicy.Outcome broke = policy.plan(0L, false, false);
        assertThat(broke.decision())
                .as("资源不够的那一刻：停下")
                .isEqualTo(AutoTrainPolicy.AutoTrainDecision.STOPPED);

        AutoTrainPolicy stopped = broke.next();
        assertThat(stopped.enabled()).isFalse();
        assertThat(stopped.stopReason()).contains("资源不够");
        assertThat(stopped.stopReason()).contains("不会自动恢复");
        assertThat(stopped.plan(0L, false, true).decision())
                .as("资源又够了也不接着排：不然玩家睡醒会发现资源被花光")
                .isEqualTo(AutoTrainPolicy.AutoTrainDecision.STOPPED);
    }

    @Test
    @DisplayName("补兵：只补缺口，缺多少补多少（不为凑批次多训）")
    void refillOnlyCoversTheGap() {
        AutoTrainPolicy policy = AutoTrainPolicy.refill("unit_infantry_t1", 800L, 500L, 3);
        assertThat(policy.refillMode()).isTrue();

        assertThat(policy.nextBatchCount(800L)).as("没缺口就不排").isZero();
        assertThat(policy.nextBatchCount(700L)).as("缺 100 就补 100（小于单批上限）").isEqualTo(100L);
        assertThat(policy.nextBatchCount(0L)).as("缺 800 但单批最多 500").isEqualTo(500L);

        AutoTrainPolicy.Outcome outcome = policy.plan(700L, false, true);
        assertThat(outcome.decision()).isEqualTo(AutoTrainPolicy.AutoTrainDecision.QUEUE_NEXT);
        assertThat(outcome.count()).isEqualTo(100L);
        assertThat(outcome.next().targetCount())
                .as("补兵排完一批之后目标仍在 —— 它要一直守着这个编成").isEqualTo(800L);
    }

    @Test
    @DisplayName("补兵：补满了是 WAIT 不是 STOP —— 留着等下一条命阵亡，且此刻一分钱不花")
    void refillWaitsWhenFull() {
        AutoTrainPolicy policy = AutoTrainPolicy.refill("unit_infantry_t1", 800L, 100L, 3);
        AutoTrainPolicy.Outcome full = policy.plan(800L, false, true);
        assertThat(full.decision()).isEqualTo(AutoTrainPolicy.AutoTrainDecision.WAIT);
        assertThat(full.count()).isZero();
        assertThat(full.next()).as("策略原样留着").isSameAs(policy);
        assertThat(full.next().enabled()).as("补满不等于关掉：下次阵亡还要接着补").isTrue();
        assertThat(full.next().stopReason()).isNull();
    }

    @Test
    @DisplayName("走到队列忙那一步之前，先看补满了没有：队列忙着且没缺口，同样是等")
    void refillFullBeatsQueueBusy() {
        AutoTrainPolicy policy = AutoTrainPolicy.refill("unit_infantry_t1", 500L, 100L, 3);
        assertThat(policy.plan(500L, true, true).decision())
                .isEqualTo(AutoTrainPolicy.AutoTrainDecision.WAIT);
    }

    @Test
    @DisplayName("非法入参当场拒：空兵种、非正数量、负预算、负目标、空原因")
    void invalidInputsAreRejected() {
        assertThatThrownBy(() -> AutoTrainPolicy.on("", 100L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutoTrainPolicy.on("unit_infantry_t1", 0L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutoTrainPolicy.on("unit_infantry_t1", 100L, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutoTrainPolicy.refill("unit_infantry_t1", 0L, 100L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutoTrainPolicy.off().stoppedBecause(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
