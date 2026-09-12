package com.ironoath.web.quest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ironoath.common.time.DayKey;
import com.ironoath.common.time.TimeService;
import com.ironoath.common.time.WeekKey;
import com.ironoath.core.event.GameEvent;
import com.ironoath.core.event.GameEventBus;
import com.ironoath.core.quest.GoalType;
import com.ironoath.core.quest.QuestProgress;

/**
 * 职责：把领域事件累加进任务进度账本（B12 §1、验收 7）。
 * 依赖：{@link GameEventBus}、{@link QuestProgressStore}、{@link QuestRulesAssembler}。
 *
 * <p><b>为什么是「一个全局监听器 + 按玩家载入账本」</b>：总线是按<b>类型</b>派发的（不带玩家过滤），
 * 而进度是<b>每人一本</b>的。两种接法里选了前者：
 * <ul>
 *   <li>全局监听器：每个事件载入那个人的账本 → 累加 → 存回。代价是一次读一次写，
 *       而事件本来就发生在写路径上（升级、训练、击杀…），这笔开销与那次操作同量级</li>
 *   <li>为每个人注册一个监听器：总线要持有全服玩家的监听器列表（玩家越多派发越慢），
 *       而且没人负责注销（退游的人永远留在列表里）—— 那是内存泄漏</li>
 * </ul>
 *
 * <p><b>逐个 {@link GoalType} 订阅，而不是 {@code subscribeAll}</b>：后者是给埋点这类
 * 「什么都要看」的消费者用的；任务进度只关心自己那 13 种，按类型订阅让一次发布只叫醒相关监听器。
 *
 * <p><b>绝不让异常逃出监听器</b>：{@code GameEventBus.publish} 会把监听器的失败收集起来抛给
 * <b>发布方</b>，而发布方是玩家的写请求 —— 一个任务进度的小 bug 不该让「训练兵」这个动作失败。
 * 所以这里全部 catch + ERROR 日志：任务进度落后可以按日志补记，玩家的操作失败不可接受
 * （钱扣了、兵没训出来）。
 */
@Component
public class QuestEventListener implements GameEventBus.Listener {

    private static final Logger LOG = LoggerFactory.getLogger(QuestEventListener.class);

    private final QuestProgressStore store;
    private final QuestRulesAssembler assembler;
    private final TimeService timeService;

    public QuestEventListener(GameEventBus bus, QuestProgressStore store,
                              QuestRulesAssembler assembler, TimeService timeService) {
        this.store = store;
        this.assembler = assembler;
        this.timeService = timeService;
        if (bus == null) {
            throw new IllegalArgumentException("事件总线不得为 null：没有它任务进度永远不动");
        }
        for (GoalType type : GoalType.values()) {
            bus.subscribe(type, this);
        }
    }

    @Override
    public void onEvent(GameEvent event) {
        try {
            long now = timeService.serverNow();
            String playerId = event.playerId();
            QuestProgressStore.State stored = store.load(playerId).orElse(null);
            QuestProgress progress = stored == null
                    ? QuestProgress.open(playerId, assembler.quests().stream()
                            .map(QuestRulesAssembler.QuestDef::def).toList(),
                            DayKey.of(now), WeekKey.of(now))
                    : QuestProgress.restore(playerId, stored.entries(), stored.dayKey(), stored.weekKey());
            // 跨期清零要在累加之前：反过来会把刚记上的进度一起抹掉，
            // 而那一刻恰逢跨天时，玩家看到的是「我明明做了，任务进度是 0」
            progress.rollover(DayKey.of(now), WeekKey.of(now));
            progress.onEvent(event);
            store.save(playerId, new QuestProgressStore.State(playerId, progress.entries(),
                    progress.dayKey(), progress.weekKey()));
        } catch (RuntimeException e) {
            LOG.error("任务进度累加失败（不影响玩家这次操作，事件可据此补记）：{}", event, e);
        }
    }
}
