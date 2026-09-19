package com.ironoath.core.army;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 职责：自动续训策略的判定用例（B25-S2，裁决③(a)）。
 * 依赖：JUnit + 纯领域类（无 Spring、无存储）。
 *
 * <p><b>这些用例盯的是三件事</b>：① 它**不是一个布尔开关**（预算用尽必须停，且留下原因）；
 * ② 停下之后**不会自己恢复**（否则"等资源够了自动接着排"等于无限支出）；
 * ③ 队列忙着是"等"而不是"停"（续训的时机是上一批完成之后，不是叠着排）。
 */
class AutoTrainPolicyTest {

    @Test
    @DisplayName("关掉的策略：判定是 STOPPED（不是 WAIT，也不是排下一批）")
    void offIsStopped() {
        AutoTrainPolicy off = AutoTrainPolicy.off();
        assertThat(off.enabled()).isFalse();
        assertThat(off.decide(false, true)).isEqualTo(AutoTrainPolicy.AutoTrainDecision.STOPPED);
    }

    @Test
    @DisplayName("开着、队列空、资源够 → 排下一批；队列忙着 → 等（不是停）")
    void waitWhenQueueIsBusy() {
        AutoTrainPolicy on = AutoTrainPolicy.on("unit_infantry_t1", 100L, 3);
        assertThat(on.decide(true, true))
                .as("上一批还在训：此刻什么都不做，但不是停下来")
                .isEqualTo(AutoTrainPolicy.AutoTrainDecision.WAIT);
        assertThat(on.decide(false, true)).isEqualTo(AutoTrainPolicy.AutoTrainDecision.QUEUE_NEXT);
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
        assertThat(policy.decide(false, true)).isEqualTo(AutoTrainPolicy.AutoTrainDecision.QUEUE_NEXT);

        policy = policy.afterNextBatch();
        assertThat(policy.enabled()).as("最后一批排完就关掉，不留半开状态").isFalse();
        assertThat(policy.batchBudget()).isZero();
        assertThat(policy.stopReason()).as("玩家要能读到一句人话的原因").contains("预算");
        assertThat(policy.decide(false, true)).isEqualTo(AutoTrainPolicy.AutoTrainDecision.STOPPED);
    }

    @Test
    @DisplayName("资源不足 ⇒ 停，且**不会**因为下次资源够了就自己恢复（那是无限支出）")
    void resourceShortfallStopsForGood() {
        AutoTrainPolicy policy = AutoTrainPolicy.on("unit_infantry_t1", 100L, 5);
        assertThat(policy.decide(false, false))
                .as("资源不够的那一刻：停下")
                .isEqualTo(AutoTrainPolicy.AutoTrainDecision.STOPPED);

        AutoTrainPolicy stopped = policy.stoppedBecauseOfResources();
        assertThat(stopped.enabled()).isFalse();
        assertThat(stopped.stopReason()).contains("资源不够");
        assertThat(stopped.stopReason()).contains("不会自动恢复");
        assertThat(stopped.decide(false, true))
                .as("资源又够了也不接着排：不然玩家睡醒会发现资源被花光")
                .isEqualTo(AutoTrainPolicy.AutoTrainDecision.STOPPED);
    }

    @Test
    @DisplayName("非法入参当场拒：空兵种、非正数量、负预算")
    void invalidInputsAreRejected() {
        assertThatThrownBy(() -> AutoTrainPolicy.on("", 100L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutoTrainPolicy.on("unit_infantry_t1", 0L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutoTrainPolicy.on("unit_infantry_t1", 100L, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
