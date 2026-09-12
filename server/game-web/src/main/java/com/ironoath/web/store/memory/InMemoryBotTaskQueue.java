package com.ironoath.web.store.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

import com.ironoath.core.bot.BotScheduler;

/**
 * 职责：Bot 作息待办队列的内存实现（dev / 单测零依赖启动）。
 * 依赖：{@link BotScheduler.TaskQueue} 端口。
 *
 * <p>与 {@code SortedMarchDueQueue} 同一套约定：按到期时刻排序、拉取即摘除、不起定时器。
 *
 * <p><b>「拉取即摘除」是必须的</b>：Bot 的执行没有幂等键兜着（真人写操作靠 requestId，
 * Bot 的动作是系统自己发起的），所以同一个待办被取出两次就会执行两次 ——
 * 表现为一个 Bot 在同一秒升级两次建筑、训练两批兵，而两次都「成功」。
 *
 * <p><b>排序键带上 botId</b>：只按到期时刻排的话，同一时刻到期的多个 Bot 谁先谁后
 * 取决于插入顺序，而 {@code TreeSet} 会把「时刻相同」判成同一个元素从而丢掉待办 ——
 * 那是最难查的一类 bug：一千个 Bot 里少了几个，没有任何地方报错。
 */
public final class InMemoryBotTaskQueue implements BotScheduler.TaskQueue {

    private static final Comparator<BotScheduler.BotTask> ORDER =
            Comparator.comparingLong(BotScheduler.BotTask::dueAt)
                    .thenComparing(BotScheduler.BotTask::botId)
                    .thenComparing(task -> task.isAction() ? 1 : 0);

    private final NavigableSet<BotScheduler.BotTask> tasks = new TreeSet<>(ORDER);

    @Override
    public synchronized void schedule(BotScheduler.BotTask task) {
        if (task == null) {
            throw new IllegalArgumentException("task 不得为 null");
        }
        tasks.add(task);
    }

    @Override
    public synchronized List<BotScheduler.BotTask> dueBefore(long now, int limit) {
        List<BotScheduler.BotTask> out = new ArrayList<>();
        var iterator = tasks.iterator();
        while (iterator.hasNext() && out.size() < limit) {
            BotScheduler.BotTask task = iterator.next();
            if (task.dueAt() > now) {
                break;
            }
            iterator.remove();
            out.add(task);
        }
        return out;
    }

    @Override
    public synchronized void cancel(String botId) {
        tasks.removeIf(task -> task.botId().equals(botId));
    }

    @Override
    public synchronized int size() {
        return tasks.size();
    }

    /** 测试辅助：清空。 */
    public synchronized void clear() {
        tasks.clear();
    }
}
