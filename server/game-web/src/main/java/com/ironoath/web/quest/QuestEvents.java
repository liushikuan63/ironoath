package com.ironoath.web.quest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ironoath.core.event.GameEvent;
import com.ironoath.core.event.GameEventBus;
import com.ironoath.core.quest.GoalType;

/**
 * 职责：业务侧发布领域事件的统一入口（B12 §1「进度自动订阅 EventBus 累加，不轮询」）。
 * 依赖：{@link GameEventBus}。
 *
 * <p><b>为什么不让业务服务直接调总线</b>：总线的 {@code publish} 会把监听器的失败
 * （{@link GameEventBus.EventDispatchException}）抛给调用方，而调用方是玩家的写请求 ——
 * 于是「任务进度的一个 bug」会变成「训练兵失败」。这个包装层把失败收口成一条 ERROR 日志：
 * <b>派生状态（任务/成就/红点）永远不该让主流程失败</b>，
 * 因为主流程那笔钱已经扣了、兵已经训了，而任务进度可以从日志补记。
 *
 * <p><b>事件在写路径内同步发布</b>（总线的契约：不开线程）：发布时调用方通常正持有玩家锁，
 * 所以监听器改的是同一份上下文里的状态，不会出现「锁外读改存档」那种过期版本覆盖。
 */
@Component
public class QuestEvents {

    private static final Logger LOG = LoggerFactory.getLogger(QuestEvents.class);

    private final GameEventBus bus;

    public QuestEvents(GameEventBus bus) {
        this.bus = bus;
    }

    /**
     * 累加型进展（升级一次、训了 N 个兵、杀了 N 只怪…）。
     *
     * @param delta 必须是正数（0 或负数会让「累加」变成可以倒退的东西，见 {@link GameEvent}）
     */
    public void progress(String playerId, GoalType goalType, String targetId, long delta, long now) {
        publish(GameEvent.progress(playerId, goalType, targetId, delta, now));
    }

    /**
     * 状态型进展（当前持有 N 粮、是否在小队/联盟、科技等级）。
     *
     * @param currentValue 当前状态值（不是增量），可以为 0
     */
    public void state(String playerId, GoalType goalType, String targetId, long currentValue, long now) {
        publish(GameEvent.state(playerId, goalType, targetId, currentValue, now));
    }

    private void publish(GameEvent event) {
        try {
            bus.publish(event);
        } catch (RuntimeException e) {
            // 派生状态失败不影响主流程：玩家的写操作必须成功，任务进度可以按日志补记
            LOG.error("发布领域事件失败（不影响玩家的这次操作）：{}", event, e);
        }
    }
}
