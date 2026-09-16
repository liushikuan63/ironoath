package com.ironoath.web.activity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ironoath.common.time.TimeService;
import com.ironoath.core.activity.ActivityCondition;
import com.ironoath.core.activity.ActivityProgress;
import com.ironoath.core.event.GameEvent;
import com.ironoath.core.event.GameEventBus;
import com.ironoath.web.activity.ActivityProgressStore.State;

/**
 * 职责：把领域事件累加进活动进度账本（B17 §一「进度推进必须事件驱动」）。
 * 依赖：{@link GameEventBus}、{@link ActivityProgressStore}、{@link ActivityRulesAssembler}、
 *       {@link PlayerRepository}（取玩家锚）。
 *
 * <p><b>为什么是「一个全局监听器 + 按玩家载入账本」</b>：与 {@code QuestEventListener} 同一条理由 ——
 * 总线按<b>类型</b>派发、进度是<b>每人一本</b>；给每个人注册监听器会让总线持有全服玩家的列表，
 * 而且没人负责注销（退游的人永远留在列表里）。
 *
 * <p><b>只订阅活动真正用到的那七类</b>，不是 {@code subscribeAll}：让一次发布只叫醒相关监听器。
 * 七个取值来自 {@link ActivityCondition#goalType()}（唯一的映射来源）——
 * 这里不再抄一遍类型清单，抄第二遍就会出现"漏订一类、那个活动永远停在 0/N"。
 *
 * <p><b>窗口轮换在累加之前</b>：反过来会把刚记上的进度一起抹掉 —— 那一刻恰逢跨轮时，
 * 玩家看到的是「我明明做了，活动进度是 0」（与任务系统"跨期清零要在累加之前"同一条）。
 *
 * <p><b>绝不让异常逃出监听器</b>：{@code GameEventBus.publish} 会把监听器的失败抛给发布方，
 * 而发布方是玩家的写请求 —— 活动进度的小 bug 不该让「训练兵」失败。全部 catch + ERROR 日志。
 */
@Component
public class ActivityEventListener implements GameEventBus.Listener {

    private static final Logger LOG = LoggerFactory.getLogger(ActivityEventListener.class);

    private final ActivityProgressStore store;
    private final ActivityRulesAssembler assembler;
    private final ActivityAnchors anchors;
    private final TimeService timeService;

    public ActivityEventListener(GameEventBus bus, ActivityProgressStore store,
                                 ActivityRulesAssembler assembler, ActivityAnchors anchors,
                                 TimeService timeService) {
        this.store = store;
        this.assembler = assembler;
        this.anchors = anchors;
        this.timeService = timeService;
        if (bus == null) {
            throw new IllegalArgumentException("事件总线不得为 null：没有它活动进度永远不动");
        }
        for (ActivityCondition condition : ActivityCondition.values()) {
            bus.subscribe(condition.goalType(), this);
        }
    }

    @Override
    public void onEvent(GameEvent event) {
        try {
            long now = timeService.serverNow();
            String playerId = event.playerId();
            State stored = store.load(playerId).orElse(null);
            ActivityProgress progress = stored == null
                    ? ActivityProgress.open(playerId, assembler.activities(),
                            anchors.serverOpenMs(playerId))
                    : ActivityProgress.restore(playerId, assembler.activities(), stored.entries(),
                            stored.serverOpenMs());
            progress.rollover(now, anchors.playerAnchorMs(playerId));
            progress.onEvent(event);
            store.save(playerId, new State(playerId, progress.entries(), progress.serverOpenMs()));
        } catch (RuntimeException e) {
            LOG.error("活动进度累加失败（不影响玩家这次操作，事件可据此补记）：{}", event, e);
        }
    }
}
