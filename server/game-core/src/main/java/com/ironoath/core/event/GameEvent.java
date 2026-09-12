package com.ironoath.core.event;

import com.ironoath.core.quest.GoalType;

/**
 * 职责：一条领域事件 —— 「某个玩家的某个目标类型发生了进展」（B00 硬约束「事件总线化」的载体）。
 * 依赖：game-core 的 {@link GoalType}。<b>不依赖 Spring 的 ApplicationEvent</b>：
 * game-core 必须能脱离容器单测（B00 分层规则），而任务进度的累加口径恰恰是最需要被单测钉住的东西。
 *
 * <p><b>两个工厂方法对应两类目标，并且互相排斥</b>：
 * {@link #progress} 只接受累加型，{@link #state} 只接受状态型（见 {@link GoalType} 的类注释）。
 * 这样「把状态型当累加型实现」这个最隐蔽的错误会在<b>构造事件的那一刻</b>就炸，
 * 而不是在玩家囤粮的任务莫名其妙提前完成之后才被发现。
 *
 * <p><b>amount 的语义随类型而变</b>：累加型是增量（+20 个兵），状态型是当前值（持有 12000 粮）。
 * 一个字段两种语义是有意的 —— 分成两个字段的话，每个事件都会有一半是 null，
 * 而「哪个字段该填」这件事又变成一处需要记住的约定。
 *
 * @param playerId 事件归属的玩家
 * @param goalType 目标类型
 * @param targetId 目标细分（建筑 id / 资源 id / 兵种 id / 野怪 id / 卡池 id）。
 *                 <b>null 表示不限定</b>：任务表里 goalTarget 为空的行匹配任何 targetId
 * @param amount   增量或当前值，见上
 * @param at       服务端时间戳（铁律 5：不用客户端时间）
 */
public record GameEvent(String playerId, GoalType goalType, String targetId, long amount, long at) {

    public GameEvent {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空：没有归属的事件无法累加到任何人的进度上");
        }
        if (goalType == null) {
            throw new IllegalArgumentException("goalType 不得为 null");
        }
        if (at <= 0L) {
            throw new IllegalArgumentException("at 必须为正的服务端时间戳，实际=" + at);
        }
        targetId = targetId == null || targetId.isBlank() ? null : targetId;
    }

    /**
     * 累加型事件：进度增加 {@code delta}。
     *
     * @param delta 必须为正。0 或负数会让「累加」变成一个可以倒退的东西，
     *              而倒退的累加型进度在玩家看来就是「我明明训了兵，任务进度反而少了」
     */
    public static GameEvent progress(String playerId, GoalType goalType, String targetId,
                                     long delta, long at) {
        if (!goalType.accumulates()) {
            throw new IllegalArgumentException(goalType + " 是状态型目标（进度 = 当前状态，可升可降），"
                    + "不能用 progress 构造。请用 state() 并给出当前值 —— "
                    + "把状态型当累加型是任务系统最隐蔽的错误，见 GoalType 的类注释");
        }
        if (delta <= 0L) {
            throw new IllegalArgumentException("累加型事件的增量必须为正，实际=" + delta
                    + "：0 没有意义，负数会让「累加」变成可以倒退的东西");
        }
        return new GameEvent(playerId, goalType, targetId, delta, at);
    }

    /**
     * 状态型事件：进度<b>设为</b> {@code currentValue}（不是加上它）。
     *
     * @param currentValue 当前状态值，可以为 0（例如退出了小队、粮食花光了），不得为负
     */
    public static GameEvent state(String playerId, GoalType goalType, String targetId,
                                  long currentValue, long at) {
        if (goalType.accumulates()) {
            throw new IllegalArgumentException(goalType + " 是累加型目标（进度只增不减），"
                    + "不能用 state 构造。请用 progress() 并给出增量");
        }
        if (currentValue < 0L) {
            throw new IllegalArgumentException("状态值不得为负，实际=" + currentValue);
        }
        return new GameEvent(playerId, goalType, targetId, currentValue, at);
    }

    /** 是否累加型（转发到 goalType，让消费方少写一层判断）。 */
    public boolean accumulates() {
        return goalType.accumulates();
    }

    /**
     * 这个事件是否匹配「限定 targetId」的任务行。
     *
     * <p>规则：任务行的 goalTarget 为空 ⇒ 匹配任何事件；不为空 ⇒ 必须逐字相同。
     * 不做前缀或通配匹配 —— 「goalTarget=stage_01 应当匹配 stage_01_03 吗」这种问题
     * 一旦允许，就要再定一套通配语义，而语义每多一条，配置表就多一种写错的方式。
     */
    public boolean matchesTarget(String requiredTargetId) {
        if (requiredTargetId == null || requiredTargetId.isBlank()) {
            return true;
        }
        return requiredTargetId.equals(targetId);
    }
}
