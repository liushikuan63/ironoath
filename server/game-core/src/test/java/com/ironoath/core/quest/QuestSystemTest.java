package com.ironoath.core.quest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.event.GameEvent;
import com.ironoath.core.event.GameEventBus;

/**
 * 职责：B12 §1 任务系统的领域验证 —— 事件总线派发纪律、进度累加口径、状态型与累加型的区别、
 *       前置解锁、每日重置、领取幂等（验收 7：模拟 100 个事件，进度累加正确无遗漏）。
 * 依赖：JUnit 5 + AssertJ + 本包与 core/event（全部纯 Java，零框架）。
 *
 * <p><b>本类盯的三条都是「功能正常但线上会出错」的口径问题</b>：
 * <ol>
 *   <li><b>状态型被当累加型实现</b>：「持有 10000 粮」若按「累计获得 10000 粮」算，
 *       任务会在玩家还没真囤下粮时就完成，而下一条任务（训练兵）正需要那批粮 ——
 *       玩家卡在「任务说完成了，但我什么都做不了」的地方，日志里一切正常</li>
 *   <li><b>一个监听器抛异常挡住其他监听器</b>：一个有 bug 的成就监听器会让任务进度、
 *       红点、埋点全部停摆，而报错只指向那个成就，排查的人会以为问题只有那么大</li>
 *   <li><b>账本不认人</b>：总线按类型派发、不带玩家过滤，所以每本账必须自己认人。
 *       漏了这一条，全服的任务进度会串号 —— 而串号的表现是「我没做任务，进度自己涨了」</li>
 * </ol>
 */
class QuestSystemTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final String DAY = "2026-09-08";
    private static final String NEXT_DAY = "2026-09-09";
    private static final String WEEK = "2026-W37";
    private static final String NEXT_WEEK = "2026-W38";
    private static final String PLAYER = "p1";

    // ---------- 夹具 ----------

    private static QuestProgress.Def def(String id, QuestProgress.QuestType type, GoalType goal,
                                         String target, long value, String pre) {
        return new QuestProgress.Def(id, type, goal, target, value, pre);
    }

    /** 一本覆盖三种形状的账本：累加型、状态型、带前置的链。 */
    private static QuestProgress ledger() {
        return QuestProgress.open(PLAYER, List.of(
                def("q_train", QuestProgress.QuestType.DAILY, GoalType.TRAIN_UNIT, "unit_infantry_t1", 100L, null),
                def("q_grain", QuestProgress.QuestType.MAIN, GoalType.REACH_RESOURCE, "GRAIN", 10_000L, null),
                def("q_any_kill", QuestProgress.QuestType.MAIN, GoalType.KILL_MONSTER, null, 3L, null),
                def("q_step2", QuestProgress.QuestType.MAIN, GoalType.UPGRADE_BUILDING, "main_city", 2L, "q_grain")),
                DAY, WEEK);
    }

    // ---------- 验收 7：事件驱动累加 ----------

    @Test
    @DisplayName("验收7：模拟 100 个事件，进度累加正确无遗漏")
    void hundredEventsAccumulateWithoutLoss() {
        QuestProgress progress = ledger();
        for (int i = 0; i < 100; i++) {
            progress.onEvent(GameEvent.progress(PLAYER, GoalType.TRAIN_UNIT, "unit_infantry_t1", 1L, NOW + i));
        }
        assertThat(progress.entry("q_train").current()).as("100 个 +1 事件").isEqualTo(100L);
        assertThat(progress.entry("q_train").complete()).isTrue();
        assertThat(progress.eventCount()).isEqualTo(100L);
    }

    @Test
    @DisplayName("一次事件带大增量也正确：进度按增量累加，不是按事件条数")
    void singleEventWithLargeDeltaCounts() {
        QuestProgress progress = ledger();
        progress.onEvent(GameEvent.progress(PLAYER, GoalType.TRAIN_UNIT, "unit_infantry_t1", 60L, NOW));
        progress.onEvent(GameEvent.progress(PLAYER, GoalType.TRAIN_UNIT, "unit_infantry_t1", 40L, NOW));
        assertThat(progress.entry("q_train").current()).isEqualTo(100L);
        assertThat(progress.eventCount()).as("两个事件").isEqualTo(2L);
    }

    @Test
    @DisplayName("进度截断到 goalValue：累计到十倍量对「完成与否」没有额外信息，只会让面板显示 200/20")
    void progressIsCappedAtTheGoal() {
        QuestProgress progress = ledger();
        progress.onEvent(GameEvent.progress(PLAYER, GoalType.TRAIN_UNIT, "unit_infantry_t1", 5_000L, NOW));
        assertThat(progress.entry("q_train").current()).isEqualTo(100L);
    }

    // ---------- 状态型 vs 累加型 ----------

    @Test
    @DisplayName("状态型是置位不是累加：囤粮任务跟着当前库存走，花掉了进度就退回去")
    void stateGoalsTrackCurrentValueNotAccumulated() {
        QuestProgress progress = ledger();
        progress.onEvent(GameEvent.state(PLAYER, GoalType.REACH_RESOURCE, "GRAIN", 5_000L, NOW));
        assertThat(progress.entry("q_grain").current()).as("持有 5000，未达标").isEqualTo(5_000L);
        assertThat(progress.entry("q_grain").complete()).isFalse();

        progress.onEvent(GameEvent.state(PLAYER, GoalType.REACH_RESOURCE, "GRAIN", 12_000L, NOW));
        assertThat(progress.entry("q_grain").current()).as("置位到 12000，不是累加成 17000").isEqualTo(10_000L);
        assertThat(progress.entry("q_grain").complete()).isTrue();

        progress.onEvent(GameEvent.state(PLAYER, GoalType.REACH_RESOURCE, "GRAIN", 3_000L, NOW));
        assertThat(progress.entry("q_grain").current())
                .as("粮食花掉了，进度必须退回去 —— 否则玩家在没粮的情况下被判定为「已囤够」")
                .isEqualTo(3_000L);
        assertThat(progress.entry("q_grain").complete()).isFalse();
    }

    @Test
    @DisplayName("事件工厂互斥：状态型不能用 progress 构造，累加型不能用 state 构造")
    void eventFactoriesAreMutuallyExclusive() {
        assertThatThrownBy(() -> GameEvent.progress(PLAYER, GoalType.REACH_RESOURCE, "GRAIN", 100L, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("状态型");
        assertThatThrownBy(() -> GameEvent.state(PLAYER, GoalType.TRAIN_UNIT, "unit_infantry_t1", 100L, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("累加型");
        assertThatThrownBy(() -> GameEvent.progress(PLAYER, GoalType.TRAIN_UNIT, null, 0L, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须为正");
        assertThatThrownBy(() -> GameEvent.state(PLAYER, GoalType.JOIN_SQUAD, null, -1L, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为负");
    }

    // ---------- 匹配规则 ----------

    @Test
    @DisplayName("goalTarget 为空的行匹配任何目标；不为空的行只认逐字相同的那个")
    void targetMatchingIsLiteral() {
        QuestProgress progress = ledger();
        progress.onEvent(GameEvent.progress(PLAYER, GoalType.KILL_MONSTER, "mapmonster_lv01", 1L, NOW));
        progress.onEvent(GameEvent.progress(PLAYER, GoalType.KILL_MONSTER, "mapmonster_lv30", 1L, NOW));
        assertThat(progress.entry("q_any_kill").current()).as("goalTarget 为空 ⇒ 两种野怪都算").isEqualTo(2L);

        progress.onEvent(GameEvent.progress(PLAYER, GoalType.TRAIN_UNIT, "unit_cavalry_t1", 50L, NOW));
        assertThat(progress.entry("q_train").current()).as("限定了步兵，骑兵不算").isZero();
    }

    @Test
    @DisplayName("账本必须自己认人：别人的事件不进这本账（总线按类型派发，不带玩家过滤）")
    void otherPlayersEventsAreIgnored() {
        QuestProgress progress = ledger();
        progress.onEvent(GameEvent.progress("someone-else", GoalType.TRAIN_UNIT, "unit_infantry_t1", 100L, NOW));
        assertThat(progress.entry("q_train").current()).isZero();
        assertThat(progress.eventCount()).as("不属于本账本的事件连计数都不该动").isZero();
    }

    // ---------- 前置解锁 ----------

    @Test
    @DisplayName("前置未领取时后续任务不累计，领取后才开始算")
    void lockedQuestsDoNotAccumulate() {
        QuestProgress progress = ledger();
        progress.onEvent(GameEvent.progress(PLAYER, GoalType.UPGRADE_BUILDING, "main_city", 5L, NOW));
        assertThat(progress.entry("q_step2").current())
                .as("q_grain 还没领，q_step2 处于锁定状态，攒进度会让玩家在解锁瞬间看到一串已完成")
                .isZero();
        assertThat(progress.unlockedCount()).isEqualTo(3);

        progress.onEvent(GameEvent.state(PLAYER, GoalType.REACH_RESOURCE, "GRAIN", 20_000L, NOW));
        progress.claim("q_grain");
        assertThat(progress.unlockedCount()).as("前置领了，第四条解锁").isEqualTo(4);

        progress.onEvent(GameEvent.progress(PLAYER, GoalType.UPGRADE_BUILDING, "main_city", 2L, NOW));
        assertThat(progress.entry("q_step2").current()).isEqualTo(2L);
        assertThat(progress.entry("q_step2").complete()).isTrue();
    }

    // ---------- 领取 ----------

    @Test
    @DisplayName("领取幂等：未完成不能领、领过不能再领、未完成时也不允许跳过")
    void claimingIsGuarded() {
        QuestProgress progress = ledger();
        assertThatThrownBy(() -> progress.claim("q_train"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("还没完成");
        assertThatThrownBy(() -> progress.claim("q_step2"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("尚未解锁");

        progress.onEvent(GameEvent.progress(PLAYER, GoalType.TRAIN_UNIT, "unit_infantry_t1", 100L, NOW));
        progress.claim("q_train");
        assertThat(progress.entry("q_train").claimed()).isTrue();
        assertThatThrownBy(() -> progress.claim("q_train"))
                .isInstanceOf(IllegalStateException.class)
                .as("重复领取必须被拒而不是再发一次奖励").hasMessageContaining("已经领过");
        assertThatThrownBy(() -> progress.claim("nope"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("任务不存在");
    }

    @Test
    @DisplayName("claimableCount 是红点系统的输入：只数「已解锁 + 已完成 + 未领取」")
    void claimableCountFeedsTheRedDot() {
        QuestProgress progress = ledger();
        assertThat(progress.claimableCount()).isZero();
        progress.onEvent(GameEvent.progress(PLAYER, GoalType.TRAIN_UNIT, "unit_infantry_t1", 100L, NOW));
        assertThat(progress.claimableCount()).isEqualTo(1);
        progress.claim("q_train");
        assertThat(progress.claimableCount()).as("领完就灭").isZero();
    }

    // ---------- 每日 / 每周重置 ----------

    @Test
    @DisplayName("跨天重置每日任务的进度与领取状态，主线不受影响；同一天重复调用什么都不做")
    void dailyQuestsResetOnRollover() {
        QuestProgress progress = ledger();
        progress.onEvent(GameEvent.progress(PLAYER, GoalType.TRAIN_UNIT, "unit_infantry_t1", 100L, NOW));
        progress.claim("q_train");
        progress.onEvent(GameEvent.state(PLAYER, GoalType.REACH_RESOURCE, "GRAIN", 20_000L, NOW));

        assertThat(progress.rollover(DAY, WEEK)).as("日键没变 ⇒ 不重置").isZero();

        int reset = progress.rollover(NEXT_DAY, WEEK);
        assertThat(reset).as("只有 q_train 是 DAILY").isEqualTo(1);
        assertThat(progress.entry("q_train").current()).isZero();
        assertThat(progress.entry("q_train").claimed()).as("领取状态也要清，否则今天领不了").isFalse();
        assertThat(progress.entry("q_grain").current()).as("主线不受影响").isEqualTo(10_000L);
    }

    @Test
    @DisplayName("周任务只在跨周时重置：跨天不跨周不该动它")
    void weeklyQuestsResetOnlyOnWeekChange() {
        QuestProgress progress = QuestProgress.open(PLAYER, List.of(
                def("q_week", QuestProgress.QuestType.WEEKLY, GoalType.HELP_SQUAD, null, 5L, null),
                def("q_day", QuestProgress.QuestType.DAILY, GoalType.GATHER_RESOURCE, "WOOD", 100L, null)),
                DAY, WEEK);
        progress.onEvent(GameEvent.progress(PLAYER, GoalType.HELP_SQUAD, null, 5L, NOW));

        // 同一周里跨天：每日任务重置，每周任务不动（周界由 newWeekKey 是否变化决定）
        progress.rollover(NEXT_DAY, WEEK);
        assertThat(progress.entry("q_week").current()).as("同一周内不该被清零").isEqualTo(5L);

        progress.rollover(NEXT_DAY, NEXT_WEEK);
        assertThat(progress.entry("q_week").current()).as("跨周清零").isZero();
    }

    // ---------- 事件总线 ----------

    @Test
    @DisplayName("按类型订阅只叫醒相关监听器；全量订阅什么都能听到")
    void busDispatchesByType() {
        GameEventBus bus = new GameEventBus();
        AtomicInteger trainHits = new AtomicInteger();
        AtomicInteger allHits = new AtomicInteger();
        bus.subscribe(GoalType.TRAIN_UNIT, event -> trainHits.incrementAndGet());
        bus.subscribeAll(event -> allHits.incrementAndGet());

        bus.publish(GameEvent.progress(PLAYER, GoalType.TRAIN_UNIT, null, 1L, NOW));
        bus.publish(GameEvent.progress(PLAYER, GoalType.KILL_MONSTER, null, 1L, NOW));

        assertThat(trainHits.get()).as("只有训练事件叫醒它").isEqualTo(1);
        assertThat(allHits.get()).as("全量订阅听到两条").isEqualTo(2);
        assertThat(bus.publishedCount()).isEqualTo(2L);
        assertThat(bus.listenerCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("一个监听器抛异常不挡住其他监听器：全部跑完后一起抛，且原因都带着")
    void oneBadListenerDoesNotBlindTheOthers() {
        GameEventBus bus = new GameEventBus();
        List<String> reached = new ArrayList<>();
        bus.subscribe(GoalType.TRAIN_UNIT, event -> {
            throw new IllegalStateException("成就监听器有 bug");
        });
        bus.subscribe(GoalType.TRAIN_UNIT, event -> reached.add("second"));
        bus.subscribe(GoalType.TRAIN_UNIT, event -> {
            throw new IllegalArgumentException("红点监听器也有 bug");
        });
        bus.subscribe(GoalType.TRAIN_UNIT, event -> reached.add("fourth"));

        assertThatThrownBy(() -> bus.publish(GameEvent.progress(PLAYER, GoalType.TRAIN_UNIT, null, 1L, NOW)))
                .isInstanceOf(GameEventBus.EventDispatchException.class)
                .hasMessageContaining("2 个监听器失败");
        assertThat(reached).as("健康的监听器必须照常执行").containsExactly("second", "fourth");
        assertThat(bus.listenerFailureCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("监听器内部不得再发布事件：递归发布让因果链不可读，而且可以无限深")
    void publishingFromInsideAListenerIsRejected() {
        GameEventBus bus = new GameEventBus();
        bus.subscribe(GoalType.TRAIN_UNIT, event ->
                bus.publish(GameEvent.progress(PLAYER, GoalType.KILL_MONSTER, null, 1L, NOW)));

        // 守卫抛出的 IllegalStateException 发生在监听器内部，于是被总线的失败隔离机制接住，
        // 对外表现为 EventDispatchException —— 这恰恰是想要的：一条坏监听器不会让总线停摆，
        // 但原因仍然被完整带出来
        assertThatThrownBy(() -> bus.publish(GameEvent.progress(PLAYER, GoalType.TRAIN_UNIT, null, 1L, NOW)))
                .isInstanceOf(GameEventBus.EventDispatchException.class)
                .hasMessageContaining("监听器内部不得再发布事件");

        // 守卫必须按线程清理：抛出之后同一个线程还要能继续发布，否则总线一次异常就永久报废
        assertThat(bus.publish(GameEvent.progress(PLAYER, GoalType.KILL_MONSTER, null, 1L, NOW)))
                .as("没有监听器订阅 KILL_MONSTER，派发 0 个").isZero();
    }

    @Test
    @DisplayName("账本可以直接当监听器挂上总线：这就是「进度自动订阅 EventBus 累加」的形状")
    void ledgerCanBeSubscribedToTheBus() {
        GameEventBus bus = new GameEventBus();
        QuestProgress progress = ledger();
        // 每个玩家一本账，每本账自己认人（总线不带玩家过滤）
        bus.subscribeAll(progress::onEvent);

        for (int i = 0; i < 100; i++) {
            bus.publish(GameEvent.progress(PLAYER, GoalType.TRAIN_UNIT, "unit_infantry_t1", 1L, NOW + i));
        }
        assertThat(progress.entry("q_train").current()).isEqualTo(100L);
        assertThat(progress.entry("q_train").complete()).isTrue();
        assertThat(bus.listenerFailureCount()).isZero();
    }

    @Test
    @DisplayName("任务定义构造期校验：goalValue 为 0 的任务一创建就是完成状态，必须拒绝")
    void definitionsAreValidated() {
        assertThatThrownBy(() -> def("q", QuestProgress.QuestType.MAIN, GoalType.TRAIN_UNIT, null, 0L, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("goalValue");
        assertThatThrownBy(() -> QuestProgress.open(PLAYER, List.of(), DAY, WEEK))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("任务定义为空");
        assertThatThrownBy(() -> QuestProgress.open(PLAYER, List.of(
                def("q", QuestProgress.QuestType.MAIN, GoalType.TRAIN_UNIT, null, 1L, null),
                def("q", QuestProgress.QuestType.MAIN, GoalType.TRAIN_UNIT, null, 1L, null)), DAY, WEEK))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重复");
    }
}
