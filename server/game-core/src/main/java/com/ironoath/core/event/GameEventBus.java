package com.ironoath.core.event;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.ironoath.core.quest.GoalType;

/**
 * 职责：领域事件总线 —— 让任务进度、成就、红点这类派生状态<b>被事件推动</b>而不是被轮询（B12 验收 7）。
 * 依赖：无（纯 Java，零框架）。<b>刻意不用 Spring 的 ApplicationEventPublisher</b>：
 * game-core 必须能脱离容器单测，而「100 个事件累加下来一个不漏」这条验收恰恰要在单测里被精确验证。
 *
 * <p><b>为什么必须是事件驱动而不是轮询</b>：轮询版本要扫全部玩家 × 全部任务 × 每次请求，
 * 而 B00 禁止 @Scheduled 扫表；换成「读的时候顺便算」也不行 ——
 * 「累计训练 20 个兵」这类进度<b>无法从当前状态反推</b>（兵可能已经死了、被派出去了），
 * 它只能在发生的那一刻被记下来。漏掉一个事件，进度就永久少一格，
 * 而玩家看到的是「我明明做了，任务没动」。
 *
 * <p><b>同步派发，不开线程</b>：发布方通常正持有玩家锁，异步派发会让监听器在锁外读改存档 ——
 * 那是本项目已经踩过好几次的「过期版本覆盖」。同步的代价是监听器必须快，
 * 而这条约束由 {@link Listener} 的契约写死：<b>不许阻塞、不许发布新事件</b>。
 *
 * <p><b>一个监听器抛异常不会挡住其他监听器</b>：全部跑完之后再把失败一起抛出去
 * （{@link EventDispatchException}）。反过来（第一个异常就中断）的后果是
 * 一个有 bug 的成就监听器会让任务进度、红点、埋点全部停摆，
 * 而报错信息只指向那个成就 —— 排查的人会以为问题只有那么大。
 */
public final class GameEventBus {

    /**
     * 事件监听器。
     *
     * <p><b>契约</b>：① 不阻塞（不做 IO、不等锁）；② 不发布新事件（因果链会变得不可读，
     * 而且递归发布可以无限深）；③ 幂等（同一个事件被重放时不该把进度加两遍 ——
     * 发布方在补偿路径上可能重放）。
     */
    public interface Listener {
        void onEvent(GameEvent event);
    }

    /** 派发失败：所有监听器都跑过了，但至少有一个抛了异常。 */
    public static final class EventDispatchException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient List<Throwable> causes;

        EventDispatchException(GameEvent event, List<Throwable> causes) {
            super("事件 " + event.goalType() + "(player=" + event.playerId() + ", target=" + event.targetId()
                    + ", amount=" + event.amount() + ") 派发时有 " + causes.size()
                    + " 个监听器失败（其余监听器已正常执行）：" + describe(causes));
            this.causes = List.copyOf(causes);
        }

        /** 全部失败原因。第一个同时被设为 {@link #getCause()}，便于日志打印堆栈。 */
        public List<Throwable> causes() {
            return causes;
        }

        @Override
        public synchronized Throwable getCause() {
            return causes.isEmpty() ? null : causes.get(0);
        }

        private static String describe(List<Throwable> causes) {
            StringBuilder sb = new StringBuilder();
            for (Throwable cause : causes) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
            }
            return sb.toString();
        }
    }

    private final Map<GoalType, List<Listener>> byType = new EnumMap<>(GoalType.class);
    private final List<Listener> wildcard = new ArrayList<>();
    /** 递归发布守卫。<b>按线程</b>而不是按实例：不同玩家的请求会并发发布，用实例字段会互相误判。 */
    private final ThreadLocal<Boolean> dispatching = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final AtomicLong publishedCount = new AtomicLong();
    private final AtomicLong listenerFailureCount = new AtomicLong();

    /** 订阅某一类目标的事件。 */
    public void subscribe(GoalType goalType, Listener listener) {
        if (goalType == null || listener == null) {
            throw new IllegalArgumentException("goalType 与 listener 都不得为 null");
        }
        synchronized (byType) {
            byType.computeIfAbsent(goalType, key -> new ArrayList<>()).add(listener);
        }
    }

    /**
     * 订阅全部事件。
     *
     * <p>给「什么都要听」的消费者用（埋点、日志）。<b>任务进度不要用它</b>：
     * 按类型订阅的话，一次发布只叫醒相关的监听器；
     * 全量订阅会让每个监听器都自己过滤一遍，而过滤逻辑分散在各处就会各自漏一种情况。
     */
    public void subscribeAll(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener 不得为 null");
        }
        synchronized (byType) {
            wildcard.add(listener);
        }
    }

    /**
     * 发布一个事件，同步派发给所有相关监听器。
     *
     * @return 成功执行的监听器个数
     * @throws EventDispatchException 有监听器抛异常（此时其余监听器<b>已经</b>执行完毕）
     * @throws IllegalStateException  在监听器内部再次发布（递归发布被禁止）
     */
    public int publish(GameEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event 不得为 null");
        }
        if (Boolean.TRUE.equals(dispatching.get())) {
            throw new IllegalStateException("监听器内部不得再发布事件：" + event.goalType()
                    + "。递归发布让因果链变得不可读，而且可以无限深 —— "
                    + "需要连锁反应的话，让监听器改状态，由下一次业务调用去发布");
        }
        // 先在锁内拷一份监听器列表，再在锁外调用：
        // 持锁调用监听器的话，任何一个监听器再去订阅就会死锁
        List<Listener> targets = new ArrayList<>();
        synchronized (byType) {
            List<Listener> typed = byType.get(event.goalType());
            if (typed != null) {
                targets.addAll(typed);
            }
            targets.addAll(wildcard);
        }

        dispatching.set(Boolean.TRUE);
        List<Throwable> failures = new ArrayList<>();
        int invoked = 0;
        try {
            for (Listener listener : targets) {
                try {
                    listener.onEvent(event);
                    invoked++;
                } catch (RuntimeException | Error e) {
                    // 一个监听器的 bug 不该让其他监听器停摆：全部跑完再一起抛
                    failures.add(e);
                }
            }
        } finally {
            dispatching.set(Boolean.FALSE);
        }
        publishedCount.incrementAndGet();
        if (!failures.isEmpty()) {
            listenerFailureCount.addAndGet(failures.size());
            throw new EventDispatchException(event, failures);
        }
        return invoked;
    }

    /** 已发布的事件总数。<b>这个数长期为 0 说明没有任何业务在发事件</b> —— 那是接线漏了，不是设计。 */
    public long publishedCount() {
        return publishedCount.get();
    }

    /** 监听器抛异常的累计次数。非零就说明有监听器在带病运行。 */
    public long listenerFailureCount() {
        return listenerFailureCount.get();
    }

    /** 当前订阅总数（含全量订阅）。 */
    public int listenerCount() {
        synchronized (byType) {
            int total = wildcard.size();
            for (List<Listener> listeners : byType.values()) {
                total += listeners.size();
            }
            return total;
        }
    }
}
