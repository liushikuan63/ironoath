package com.ironoath.core.army;

/**
 * 职责：自动续训 / 自动补兵的**策略与判定**（B25 裁决③(a)）。纯 Java，无框架依赖。
 *
 * <p><b>为什么是一个"有预算的策略"而不是一个布尔开关</b>（裁决③(a) 的原话）：一个 `boolean autoTrain`
 * 意味着"只要资源够就一直训下去" —— 那是**无限支出**。玩家关掉游戏去睡觉，回来发现攒了三天的资源
 * 全变成了兵，而他从没同意过这件事。所以策略里必须带上"还允许自动排几批"，排完即停、并把
 * **停止原因**留在策略上 —— 玩家回来时能读到一句"预算排完了"，而不是一个悄悄变成 false 的开关。
 *
 * <p><b>两种模式，同一条执行路径</b>：
 * <ul>
 *   <li><b>续训</b>（{@code targetCount = 0}）：队列一空就按 {@code batchCount} 再排一批，排到预算用尽；</li>
 *   <li><b>补兵</b>（{@code targetCount > 0}）：把该兵种补回 {@code targetCount}（编成），只补差额、
 *       单批不超过 {@code batchCount}；补满之后**保持开着等下次阵亡**（不是停下来）。</li>
 * </ul>
 * 两者的差别只有"排多少"与"什么时候算够"，扣资源、占队列、训练时长一律走真人那条路径。
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
        /** 补兵模式的目标兵力；0 = 续训模式（不设目标）。 */
        long targetCount,
        /** 停下来时的原因（玩家可读）；正在跑或在等为 null。 */
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
        if (targetCount < 0L) {
            throw new IllegalArgumentException("补兵目标不得为负，实际=" + targetCount);
        }
    }

    /** 关闭（玩家按了"停"）：预算与原因都清掉 —— 重开时是一条干净的策略。 */
    public static AutoTrainPolicy off() {
        return new AutoTrainPolicy(false, "none", 1L, 0, 0L, null);
    }

    /** 玩家开启续训：给定兵种、每批数量与批次预算。 */
    public static AutoTrainPolicy on(String unitId, long batchCount, int batchBudget) {
        return new AutoTrainPolicy(true, unitId, batchCount, batchBudget, 0L, null);
    }

    /** 玩家开启补兵：把该兵种补回 {@code targetCount}（= 他要守住的编成）。 */
    public static AutoTrainPolicy refill(String unitId, long targetCount, long batchCount, int batchBudget) {
        if (targetCount <= 0L) {
            throw new IllegalArgumentException("补兵目标必须为正，实际=" + targetCount);
        }
        return new AutoTrainPolicy(true, unitId, batchCount, batchBudget, targetCount, null);
    }

    /** 是不是补兵模式（有目标）。 */
    public boolean refillMode() {
        return targetCount > 0L;
    }

    /**
     * 排下一批之后的策略：预算 -1；**用尽即停**并写清原因（而不是留一个"enabled=true 但没预算"的
     * 半开状态 —— 那会让读代码的人以为它还会继续动）。
     */
    public AutoTrainPolicy afterNextBatch() {
        int left = batchBudget - 1;
        if (left <= 0) {
            return new AutoTrainPolicy(false, unitId, batchCount, 0, targetCount, REASON_BUDGET);
        }
        return new AutoTrainPolicy(true, unitId, batchCount, left, targetCount, null);
    }

    /** 因为某个原因停下（资源不足等）。**停下就是停下**：不会因为下一次读到时资源够了而自己恢复。 */
    public AutoTrainPolicy stoppedBecauseOfResources() {
        return new AutoTrainPolicy(false, unitId, batchCount, batchBudget, targetCount, REASON_RESOURCE);
    }

    /**
     * 因为别的原因停下（带兵上限满了、兵种被配置删了等）。
     *
     * <p>把原因做成自由文本而不是再开一个枚举：这些原因都来自**执行那一刻**的真实处境
     * （例如"需要兵营 10 级，当前 6 级"），枚举只能把它们压成一句含糊的"出错了"，
     * 而玩家要的恰好是那一句能照着做的提示。文本由服务端生成，玩家只读不改。
     */
    public AutoTrainPolicy stoppedBecause(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("停止原因不得为空");
        }
        return new AutoTrainPolicy(false, unitId, batchCount, batchBudget, targetCount, reason);
    }

    /**
     * 此刻该排多少兵。续训模式恒为 {@code batchCount}；补兵模式是
     * {@code min(batchCount, 还差多少)} —— 差多少补多少，别为了凑批次多训出一堆。
     */
    public long nextBatchCount(long currentPlusTraining) {
        if (!refillMode()) {
            return batchCount;
        }
        long gap = targetCount - currentPlusTraining;
        return gap <= 0L ? 0L : Math.min(batchCount, gap);
    }

    /**
     * 判定：此刻该做什么，以及**做完之后策略该落成什么**。
     *
     * <p>把"下一个策略"和"这一刻的决定"放在同一次返回里，是为了让调用方<b>没有机会漏改</b>：
     * 旧版分成两个方法时，任何一条早退路径都可能忘记落库（预算用尽却仍写着 enabled=true），
     * 而那种状态在库里是看不出来的 —— 它只在"下次还继续排"时才现形。调用方只需
     * {@code if (!outcome.next().equals(army.autoTrain())) army.setAutoTrain(outcome.next())}。
     *
     * @param currentPlusTraining 该兵种当前兵力 + 训练中（补兵模式靠它算缺口；续训模式不看它）
     * @param queueBusy           训练队列是不是还占着（续训的时机是"上一批完成之后"，不是叠着排）
     * @param resourcesEnough     这一次要排的量付得起吗（判定口径与真人那条 {@code train} 一致）
     */
    public Outcome plan(long currentPlusTraining, boolean queueBusy, boolean resourcesEnough) {
        if (!enabled || stopReason != null || batchBudget <= 0) {
            return new Outcome(AutoTrainDecision.STOPPED, 0L, this);
        }
        if (refillMode() && currentPlusTraining >= targetCount) {
            // 补满了**不是停**：留着策略等下一条命阵亡（这正是"补兵"与"续训"的差别）。
            // 它也不会多花一分钱 —— 此刻根本没有要补的缺口
            return new Outcome(AutoTrainDecision.WAIT, 0L, this);
        }
        if (queueBusy) {
            return new Outcome(AutoTrainDecision.WAIT, 0L, this);
        }
        if (!resourcesEnough) {
            return new Outcome(AutoTrainDecision.STOPPED, 0L, stoppedBecauseOfResources());
        }
        return new Outcome(AutoTrainDecision.QUEUE_NEXT, nextBatchCount(currentPlusTraining), afterNextBatch());
    }

    /** 该不该排下一批。 */
    public enum AutoTrainDecision {
        /** 排下一批（调用方走真人那条 train，真扣资源、真占队列）。 */
        QUEUE_NEXT,
        /** 队列还忙着 / 已经补满，什么都不做（不是停止，也不是失败）。 */
        WAIT,
        /** 已停：策略关闭 / 预算用尽 / 资源不足。调用方应把 {@link Outcome#next()} 落库。 */
        STOPPED
    }

    /**
     * 一次判定的完整结果：做什么、排多少、落库后的策略。
     *
     * @param decision 这一刻的决定
     * @param count    要排的数量（只有 {@link AutoTrainDecision#QUEUE_NEXT} 时为正）
     * @param next     执行之后应落库的策略（未变时就是策略本身）
     */
    public record Outcome(AutoTrainDecision decision, long count, AutoTrainPolicy next) {
    }
}
