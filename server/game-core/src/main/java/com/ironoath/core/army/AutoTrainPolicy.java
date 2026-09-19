package com.ironoath.core.army;

/**
 * 职责：自动续训 / 自动补兵的**策略与判定**（B25 裁决③(a)）。纯 Java，无框架依赖。
 *
 * <p><b>为什么是一个"有预算的策略"而不是一个布尔开关</b>（裁决③(a) 的原话）：一个 `boolean autoTrain`
 * 意味着"只要资源够就一直训下去" —— 那是**无限支出**。玩家关掉游戏去睡觉，回来发现攒了三天的资源
 * 全变成了兵，而他从没同意过这件事。所以策略里必须带上"还允许自动排几批"，排完即停、并把
 * **停止原因**留在策略上 —— 玩家回来时能读到一句"预算排完了"，而不是一个悄悄变成 false 的开关。
 *
 * <p><b>红线（§8.8.3 P1 原文）</b>：自动续训**不得变成免资源或免冷却**。本类只做判定，
 * 真正的排队与扣资源仍然走真人那条 {@code ArmyAppService.train}（同一条资源校验、同一条队列上限），
 * 这条约束由调用方保证 —— 策略里没有任何"免费"或"跳过时间"的字段，是刻意的。
 *
 * <p><b>停止是一次性的、不可自动恢复的</b>：资源不足或预算用尽 ⇒ 策略落到"已停止"，
 * 需要玩家重新开启。反过来（"等资源够了自动接着排"）等于把支出决定权交给时间，
 * 那正是上面那个"醒来发现资源没了"的形状。
 */
public record AutoTrainPolicy(
        boolean enabled,
        /** 续训哪个兵种（一个流水线只盯一个兵种：多兵种交替的需求属于"编成"，不属于"续训"）。 */
        String unitId,
        /** 每批训多少。 */
        long batchCount,
        /** 还允许自动排几批。**这是预算本身**，不是"已经排了几批"。 */
        int batchBudget,
        /** 停下来时的原因（玩家可读）；正在跑为 null。 */
        String stopReason) {

    private static final String REASON_BUDGET = "自动续训的批次预算用完了，想继续请再开一次";
    private static final String REASON_RESOURCE = "资源不够，自动续训已停下（不会自动恢复，想继续请再开一次）";

    public AutoTrainPolicy {
        if (unitId == null || unitId.isBlank()) {
            throw new IllegalArgumentException("续训的兵种不得为空");
        }
        if (batchCount <= 0L) {
            throw new IllegalArgumentException("每批数量必须为正，实际=" + batchCount);
        }
        if (batchBudget < 0) {
            throw new IllegalArgumentException("批次预算不得为负，实际=" + batchBudget);
        }
    }

    /** 关闭（玩家按了"停"）：预算与原因都清掉 —— 重开时是一条干净的策略。 */
    public static AutoTrainPolicy off() {
        return new AutoTrainPolicy(false, "none", 1L, 0, null);
    }

    /** 玩家开启：给定兵种、每批数量与批次预算。 */
    public static AutoTrainPolicy on(String unitId, long batchCount, int batchBudget) {
        return new AutoTrainPolicy(true, unitId, batchCount, batchBudget, null);
    }

    /**
     * 排下一批之后的策略：预算 -1；**用尽即停**并写清原因（而不是留一个"enabled=true 但没预算"的
     * 半开状态 —— 那会让读代码的人以为它还会继续动）。
     */
    public AutoTrainPolicy afterNextBatch() {
        int left = batchBudget - 1;
        if (left <= 0) {
            return new AutoTrainPolicy(false, unitId, batchCount, 0, REASON_BUDGET);
        }
        return new AutoTrainPolicy(true, unitId, batchCount, left, null);
    }

    /** 因为某个原因停下（资源不足等）。**停下就是停下**：不会因为下一次读到时资源够了而自己恢复。 */
    public AutoTrainPolicy stoppedBecauseOfResources() {
        return new AutoTrainPolicy(false, unitId, batchCount, batchBudget, REASON_RESOURCE);
    }

    /** 判定：此刻该不该排下一批。 */
    public AutoTrainDecision decide(boolean queueBusy, boolean resourcesEnough) {
        if (!enabled || stopReason != null) {
            return AutoTrainDecision.STOPPED;
        }
        if (batchBudget <= 0) {
            return AutoTrainDecision.STOPPED;
        }
        if (queueBusy) {
            // 队列非空 = 上一批还在训。续训的时机是"一批完成之后"，不是叠着排
            return AutoTrainDecision.WAIT;
        }
        if (!resourcesEnough) {
            return AutoTrainDecision.STOPPED;
        }
        return AutoTrainDecision.QUEUE_NEXT;
    }

    /** 该不该排下一批。 */
    public enum AutoTrainDecision {
        /** 排下一批（调用方走真人那条 train，真扣资源、真占队列）。 */
        QUEUE_NEXT,
        /** 队列还忙着，什么都不做（不是停止，也不是失败）。 */
        WAIT,
        /** 已停：策略关闭 / 预算用尽 / 资源不足。调用方应把策略落成 {@link #stoppedBecauseOfResources()} 或关闭态。 */
        STOPPED
    }
}
