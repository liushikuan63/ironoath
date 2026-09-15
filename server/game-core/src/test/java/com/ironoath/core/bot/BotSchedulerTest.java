package com.ironoath.core.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.rng.Rng;

/**
 * 职责：BotScheduler 的编排纪律 —— 拉取式队列、预算中断、拟人延迟两阶段、追赶只提频不改数值、
 *       「已不是 Bot」的摘除。
 * 依赖：JUnit 5 + AssertJ + 本包的决策树与作息表（全部真实对象，只有 World 与 TaskQueue 是替身）。
 *
 * <p><b>决策树用的是真的</b>：它是 final 类、拿不到任何仓储，也没有可替换的接口 ——
 * 这是 B11 头号铁律在类型层面的保证（{@code BotSystemTest} 的类注释写了同一条）。
 * 所以本类的断言落在<b>编排</b>上而不是「决策选了哪个动作」上：
 * 后者是 BotSystemTest 的职责，两处都断言会让同一个口径有两个家。
 *
 * <p><b>预算用注入的纳秒时钟</b>：靠真实时钟测「预算只够跑两个」是典型的 flaky 来源 ——
 * 机器快一点就多跑一个，慢一点就少跑一个，而同一条断言在 CI 上与本地给出不同答案。
 */
class BotSchedulerTest {

    private static final long SECOND = 1000L;
    private static final long HOUR = 3600 * SECOND;
    private static final long DAY = 24 * HOUR;
    private static final long NOW = 1_700_000_000_000L;

    // ---------- 替身 ----------

    /** 可控的世界：画像、观测、战力都由用例设定，执行只记录不落地。 */
    private static final class FakeWorld implements BotScheduler.World {
        private BotProfile profile;
        private BotDecisionTree.WorldState state = idleState();
        private final List<BotDecisionTree.Decision> executed = new ArrayList<>();
        private final List<String> cancelled = new ArrayList<>();
        private long botPower = 10_000L;
        private long humanAverage = 0L;
        /** 让 observe / execute 抛，用来验"一个 Bot 的失败不会带走整轮、也不会让它自己退出调度"。 */
        private RuntimeException throwOnObserve;
        private RuntimeException throwOnExecute;

        @Override
        public BotProfile profileOf(String botId) {
            return profile;
        }

        @Override
        public BotDecisionTree.WorldState observe(String botId, long now) {
            if (throwOnObserve != null) {
                throw throwOnObserve;
            }
            return state;
        }

        @Override
        public void execute(String botId, BotDecisionTree.Decision decision, long now) {
            if (throwOnExecute != null) {
                throw throwOnExecute;
            }
            executed.add(decision);
        }

        @Override
        public long matchPowerOf(String botId) {
            return botPower;
        }

        @Override
        public long humanAverageMatchPower(long now) {
            return humanAverage;
        }
    }

    /** 可控的队列：到期即摘除（与 MarchDueQueue 同一条约定）。 */
    private static final class FakeQueue implements BotScheduler.TaskQueue {
        private final List<BotScheduler.BotTask> tasks = new ArrayList<>();

        @Override
        public void schedule(BotScheduler.BotTask task) {
            tasks.add(task);
            tasks.sort((a, b) -> Long.compare(a.dueAt(), b.dueAt()));
        }

        @Override
        public List<BotScheduler.BotTask> dueBefore(long now, int limit) {
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
        public void cancel(String botId) {
            tasks.removeIf(task -> task.botId().equals(botId));
        }

        @Override
        public int size() {
            return tasks.size();
        }

        /**
         * 队列里还有哪些 Bot（含重复）。
         *
         * <p>用它而不是队列大小来断言「没有人凭空消失」：处理过的 Bot 会留下一个
         * 下一次 tick 的待办，所以大小不等于「剩下的没处理条数」，按大小断言会算错。
         */
        List<String> botIds() {
            return tasks.stream().map(BotScheduler.BotTask::botId).toList();
        }
    }


    /** 与 core 同一套日历（UTC+8）的小时序号。 */
    private static int hourOf(long millis) {
        return java.time.Instant.ofEpochMilli(millis).atZone(com.ironoath.common.time.DayKey.CALENDAR_ZONE)
                .getHour();
    }

    // ---------- 夹具 ----------

    private static BotDecisionTree.WorldState idleState() {
        return new BotDecisionTree.WorldState(false, false, true, true, false, true,
                true, true, false, false, false, 0L);
    }

    /** 受击状态：决策树会给 REACT_ATTACK，而它带 3~30 秒的拟人延迟。 */
    private static BotDecisionTree.WorldState underAttackState() {
        return new BotDecisionTree.WorldState(true, false, true, true, false, true,
                true, true, false, false, false, 0L);
    }

    private static BotProfile profile(String botId, String activeness, String reactionDelayMinSeconds,
                                      String reactionDelayMaxSeconds) {
        return new BotProfile(botId, "bot_linju",
                new BotProfile.AiProfile(FixedPoint.parse("0.50"), FixedPoint.parse("0.50"),
                        FixedPoint.parse("0.50"), FixedPoint.parse(activeness)),
                new BotProfile.Persona(42L, 7L, 99L, List.of(12, 13, 20, 21, 22),
                        Long.parseLong(reactionDelayMinSeconds), Long.parseLong(reactionDelayMaxSeconds),
                        FixedPoint.parse("0.10")),
                FixedPoint.parse("1.0"));
    }

    private static BotSchedule schedule() {
        return new BotSchedule(new BotSchedule.Rules(3, 8,
                Map.of(BotSchedule.Action.LOGIN, Map.of(20, 10))));
    }

    /** 纳秒时钟替身：用例可以把时间「拨快」来触发预算中断。 */
    private static final class FakeNanos {
        private long value;

        long get() {
            return value;
        }

        void advanceMillis(long millis) {
            value += millis * 1_000_000L;
        }
    }

    private record Harness(BotScheduler scheduler, FakeWorld world, FakeQueue queue, FakeNanos nanos) {
    }

    private static Harness harness(BotProfile profile, long budgetMillis, int batchLimit) {
        FakeWorld world = new FakeWorld();
        world.profile = profile;
        FakeQueue queue = new FakeQueue();
        FakeNanos nanos = new FakeNanos();
        // 每次读取纳秒时钟都推进 1ms：于是预算为 N 毫秒时，恰好只能处理 N 个待办，
        // 这让「预算中断」变成一条确定性的断言而不是靠机器快慢
        BotScheduler scheduler = new BotScheduler(
                new BotScheduler.Rules(budgetMillis, batchLimit), world, queue,
                new BotDecisionTree(), schedule(), Rng.of(20260908L),
                () -> {
                    long current = nanos.get();
                    nanos.advanceMillis(1L);
                    return current;
                });
        return new Harness(scheduler, world, queue, nanos);
    }

    // ---------- 用例 ----------

    @Test
    @DisplayName("登记后按活跃度排第一次 tick：一天 86400 秒 / 当日 tick 数，不多不少")
    void enrollSchedulesTheFirstTickByActiveness() {
        BotProfile bot = profile("bot-1", "0.60", "3", "30");
        Harness h = harness(bot, 5L, 50);
        h.world.humanAverage = 0L;

        h.scheduler.enroll("bot-1", NOW);

        assertThat(h.queue.size()).isEqualTo(1);
        int ticksPerDay = schedule().ticksPerDay(bot.ai().activeness());
        assertThat(ticksPerDay).as("活跃度 0.60 落在 [3,8] 之间").isBetween(3, 8);
        // 没有真人平均值时不做追赶加速，间隔就是基准值
        assertThat(h.scheduler.nextTickDelay(bot, NOW))
                .as("间隔由活跃度决定（相位不改变它）")
                .isEqualTo(DAY / ticksPerDay);
        assertThat(hourOf(h.queue.dueBefore(NOW + 3 * DAY, 1).get(0).dueAt()))
                .as("B11 §三：tick 按 persona.activeHours 分布 —— 第一次也不能排在凌晨")
                .isEqualTo(20);
    }

    @Test
    @DisplayName("到点即决策并执行，然后安排下一次 tick：一个待办进去、一个待办出来")
    void processDueExecutesAndReschedules() {
        BotProfile bot = profile("bot-1", "0.60", "3", "30");
        Harness h = harness(bot, 50L, 50);
        h.scheduler.enroll("bot-1", NOW);
        long firstDue = h.queue.dueBefore(NOW + DAY, 1).get(0).dueAt();
        h.queue.schedule(BotScheduler.BotTask.decisionAt("bot-1", firstDue));

        int processed = h.scheduler.processDue(firstDue);

        assertThat(processed).isEqualTo(1);
        assertThat(h.scheduler.executedCount()).isEqualTo(1);
        assertThat(h.world.executed).hasSize(1);
        assertThat(h.queue.size()).as("执行完必须安排下一次，否则这个 Bot 从此再也不动").isEqualTo(1);
        assertThat(h.queue.dueBefore(firstDue + DAY, 1).get(0).dueAt())
                .as("下一次 tick 必须晚于这一次").isGreaterThan(firstDue);
    }

    @Test
    @DisplayName("拟人延迟是两阶段的：受击反应不当场执行，而是排一个「到点执行这个已决定的动作」的待办")
    void humanizingDelayDefersInsteadOfExecutingNow() {
        BotProfile bot = profile("bot-1", "0.60", "3", "30");
        Harness h = harness(bot, 50L, 50);
        h.world.state = underAttackState();
        h.queue.schedule(BotScheduler.BotTask.decisionAt("bot-1", NOW));

        h.scheduler.processDue(NOW);

        if (h.scheduler.deferredCount() == 0L) {
            // 决策树在这一组画像下没有给出带延迟的动作，那这条用例就没有验到东西 ——
            // 明确失败比静默通过好：否则「拟人延迟」这条设计会在无人察觉的情况下失效
            assertThat(h.world.executed)
                    .as("受击状态下决策树应当给出带延迟的反应动作，实际执行了 %s", h.world.executed)
                    .isEmpty();
            return;
        }
        assertThat(h.world.executed).as("延迟期间不得执行").isEmpty();
        assertThat(h.queue.size()).isEqualTo(1);
        BotScheduler.BotTask deferred = h.queue.dueBefore(NOW + 60 * SECOND, 1).get(0);
        assertThat(deferred.isAction()).as("待办里必须带着已决定的动作，不能到点再重新决策一次").isTrue();
        assertThat(deferred.dueAt()).as("延迟必须落在 persona 配的 3~30 秒区间内")
                .isBetween(NOW + 3000L, NOW + 30_000L);

        // dueBefore 是「拉取即摘除」，所以上面那次查看已经把待办取走了 ——
        // 必须放回去，否则第二次 processDue 看不到任何东西（这正是端口约定的语义）
        h.queue.schedule(deferred);

        // 到点执行那一个动作，并安排下一次 tick
        h.scheduler.processDue(deferred.dueAt());
        assertThat(h.world.executed).hasSize(1);
        assertThat(h.scheduler.executedCount()).isEqualTo(1);
        assertThat(h.queue.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("预算中断：预算只够跑两个时，剩下的待办必须放回队列，不能凭空消失")
    void budgetInterruptsAndRequeuesTheRest() {
        BotProfile bot = profile("bot-1", "0.60", "3", "30");
        Harness h = harness(bot, 3L, 50);
        for (int i = 0; i < 5; i++) {
            h.queue.schedule(BotScheduler.BotTask.decisionAt("bot-" + i, NOW));
        }
        // 五个待办都能被 profileOf 认出来（替身对任何 botId 都返回同一个画像），
        // 所以它们都会走「决策 + 执行」这条消耗预算的路径，而不是「已不是 Bot」的摘除分支

        int processed = h.scheduler.processDue(NOW);

        assertThat(processed).as("预算 3ms、时钟每读一次推进 1ms ⇒ 最多处理 3 个").isLessThanOrEqualTo(3);
        assertThat(h.scheduler.budgetHitCount()).as("预算必须被记为用满过").isPositive();
        // dueBefore 已经把五个待办全部摘出队列，所以「放回」是唯一能让它们不消失的机会。
        // 断言按 botId 而不是队列大小：处理过的那几个会各留下一个下一次 tick 的待办，
        // 于是大小是「未处理数 + 已处理数」，恰好又等于 5，按大小断言看不出任何区别
        assertThat(h.queue.botIds())
                .as("没处理完的待办必须放回队列，否则那几个 Bot 从此再也不动，而日志里一切正常")
                .contains("bot-0", "bot-1", "bot-2", "bot-3", "bot-4");
    }

    @Test
    @DisplayName("一次最多取 dueBatchLimit 条：条数与预算是两道独立的闸")
    void batchLimitIsRespected() {
        BotProfile bot = profile("bot-1", "0.60", "3", "30");
        Harness h = harness(bot, 10_000L, 2);
        for (int i = 0; i < 5; i++) {
            h.queue.schedule(BotScheduler.BotTask.decisionAt("bot-" + i, NOW));
        }

        int processed = h.scheduler.processDue(NOW);

        assertThat(processed).as("预算很宽但条数上限是 2").isEqualTo(2);
        // 被处理的那两个各自留下了下一次 tick 的待办，所以队列里五个 Bot 一个都不少
        assertThat(h.queue.botIds()).contains("bot-0", "bot-1", "bot-2", "bot-3", "bot-4");
    }

    @Test
    @DisplayName("落后于真人平均值时 tick 间隔变短（追赶），但追赶只提频、不改任何数值")
    void catchUpShortensTheIntervalForBehindBots() {
        BotProfile bot = profile("bot-1", "0.60", "3", "30");

        Harness ahead = harness(bot, 50L, 50);
        ahead.world.botPower = 20_000L;
        ahead.world.humanAverage = 10_000L;
        long aheadDelay = ahead.scheduler.nextTickDelay(bot, NOW);

        Harness behind = harness(bot, 50L, 50);
        behind.world.botPower = 1_000L;
        behind.world.humanAverage = 10_000L;
        long behindDelay = behind.scheduler.nextTickDelay(bot, NOW);

        assertThat(behindDelay).as("落后的 Bot 动得更勤").isLessThan(aheadDelay);
        assertThat(aheadDelay).as("领先的 Bot 用基准间隔").isEqualTo(DAY / schedule().ticksPerDay(
                bot.ai().activeness()));
    }

    @Test
    @DisplayName("已不是 Bot 的玩家会被摘掉：对一个真人继续执行 Bot 决策等于替他玩他的号")
    void formerBotsAreDroppedFromTheQueue() {
        BotProfile bot = profile("bot-1", "0.60", "3", "30");
        Harness h = harness(bot, 50L, 50);
        h.queue.schedule(BotScheduler.BotTask.decisionAt("bot-1", NOW));
        h.queue.schedule(BotScheduler.BotTask.decisionAt("bot-1", NOW + HOUR));
        h.world.profile = null;

        h.scheduler.processDue(NOW);

        assertThat(h.scheduler.droppedCount()).isEqualTo(1);
        assertThat(h.world.executed).as("不该执行任何动作").isEmpty();
        assertThat(h.queue.size()).as("同一 Bot 的其余待办也要一并撤销").isZero();
    }

    /**
     * C1 补的韧性缺口：以前 observe / execute 抛出来的话会一路跑出 {@code processDue}，
     * 而"下一次决策"的重排代码写在成功路径之后 —— 一次异常就把这个 Bot 永久摘出作息表，
     * 症状是"地图上某个 Bot 从某天起再也不动"，日志里只有一行栈。
     */
    @Test
    @DisplayName("observe 抛出来：不带走整轮、只计 failed，且下一次决策照常排期")
    void observeFailureKeepsTheBotScheduled() {
        BotProfile bot = profile("bot-1", "0.60", "3", "30");
        Harness h = harness(bot, 50L, 50);
        h.world.throwOnObserve = new IllegalStateException("读世界失败（真实形状：某个存档读不到）");
        h.scheduler.enroll("bot-1", NOW);
        long firstDue = h.queue.dueBefore(NOW + DAY, 1).get(0).dueAt();
        h.queue.schedule(BotScheduler.BotTask.decisionAt("bot-1", firstDue));

        // 不抛 = 这一条用例的断言之一（抛出去的话 processDue 直接把测试炸掉）
        assertThat(h.scheduler.processDue(firstDue)).isEqualTo(1);

        assertThat(h.scheduler.failedCount()).as("失败必须被计数，而不是静默").isEqualTo(1);
        assertThat(h.scheduler.executedCount()).isZero();
        assertThat(h.queue.botIds())
                .as("失败之后仍然要有它下一次 tick 的待办 —— 否则这个 Bot 从此不动")
                .contains("bot-1");
    }

    /**
     * 「不为了曲线牺牲密度」这一半必须单独钉：已经在活跃小时里就该保持基准间隔。
     * 没有这条断言的话，把那个分支短路成"永远重挑小时"也不会有任何测试变红。
     */
    @Test
    @DisplayName("下一次 tick 已经落在活跃小时内时，相位不许再挪它：dueAt 必须精确等于 now + 间隔")
    void dueInsideActiveHoursKeepsTheBaseCadence() {
        BotProfile bot = profile("bot-1", "0.60", "3", "30");
        FakeWorld world = new FakeWorld();
        world.profile = bot;
        FakeQueue queue = new FakeQueue();
        BotScheduler scheduler = new BotScheduler(new BotScheduler.Rules(50L, 50), world, queue,
                new BotDecisionTree(), schedule(), Rng.of(7L));
        // 基准间隔 = 一天 / ticksPerDay(0.60)，落在 3~8 次/日之间 ⇒ 3~8 小时
        long interval = scheduler.nextTickDelay(bot, NOW);
        // 挑一个起点，使 now + interval 正好落在 20 点这一小时内（20 点既活跃又有权重）
        long hour20 = startOfHour(20);
        long now = hour20 - interval + 60_000L;

        scheduler.enroll("bot-1", now);
        long dueAt = queue.dueBefore(now + 3 * DAY, 1).get(0).dueAt();

        assertThat(hourOf(dueAt)).as("起点选得让基准 tick 正好落在 20 点内").isEqualTo(20);
        assertThat(dueAt)
                .as("活跃小时内不许再挪相位 —— 挪了就等于用曲线换掉密度")
                .isEqualTo(now + interval);
    }

    /** 当前时刻所在小时的起点（UTC+8 日历，与 core 的相位算法同一套时区）。 */
    private static long startOfHour(int hour) {
        return java.time.Instant.ofEpochMilli(NOW).atZone(com.ironoath.common.time.DayKey.CALENDAR_ZONE)
                .withHour(hour).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli();
    }

    /**
     * 验收 10 的形状，同时钉住「挑小时是按权重，不是随便挑一个」。
     *
     * <p>只用一个有权重的小时（20 点）区分不了"加权抽"与"取第一个"，所以这里给
     * 12 点权重 1、20 点权重 9：两者都在活跃表里，比例应当接近 9:1。
     * 凌晨必须一次都没有 —— 那一头才是验收 10 的分子。
     */
    @Test
    @DisplayName("验收10 的形状：tick 只落在活跃小时，且小时的选法服从 LOGIN 权重（9:1 而不是随便挑）")
    void ticksFollowActiveHoursAndTheLoginWeights() {
        BotProfile bot = profile("bot-1", "0.60", "3", "30");
        FakeWorld world = new FakeWorld();
        world.profile = bot;
        FakeQueue queue = new FakeQueue();
        BotScheduler scheduler = new BotScheduler(new BotScheduler.Rules(50L, 50), world, queue,
                new BotDecisionTree(),
                new BotSchedule(new BotSchedule.Rules(3, 8,
                        Map.of(BotSchedule.Action.LOGIN, Map.of(12, 1, 20, 9)))),
                Rng.of(20260912L));
        scheduler.enroll("bot-1", NOW);

        java.util.Map<Integer, Integer> histogram = new java.util.TreeMap<>();
        long cursor = NOW;
        for (int i = 0; i < 2000; i++) {
            BotScheduler.BotTask task = queue.dueBefore(cursor + 30 * DAY, 1).get(0);
            // 放回去再交给 processDue：相位逻辑必须走生产路径，不能在测试里自算一遍
            queue.schedule(task);
            scheduler.processDue(task.dueAt());
            histogram.merge(hourOf(task.dueAt()), 1, Integer::sum);
            cursor = task.dueAt();
        }

        assertThat(histogram.keySet())
                .as("所有 tick 的小时都必须来自 persona.activeHours，凌晨一次都不该有")
                .isSubsetOf(java.util.Set.of(12, 13, 20, 21, 22));
        for (int nightHour = 0; nightHour <= 6; nightHour++) {
            assertThat(histogram.getOrDefault(nightHour, 0))
                    .as("凌晨 %s 点的 tick 次数（验收 10 的分子）", nightHour)
                    .isZero();
        }
        int noon = histogram.getOrDefault(12, 0);
        int peak = histogram.getOrDefault(20, 0);
        assertThat(noon).as("12 点权重 1 也该被抽到，否则等于写死只在晚高峰").isPositive();
        assertThat((double) peak / noon)
                .as("LOGIN 权重是 12:1 / 20:9，两次抽小时的比例要接近 9，而不是 1（随便挑一个）")
                .isBetween(3.0, 30.0);
    }

    @Test
    @DisplayName("execute 抛出来：executed 不涨、failed 涨、待办继续排；同轮另一个 Bot 照跑")
    void executeFailureDoesNotStopTheRound() {
        BotProfile bot = profile("bot-1", "0.60", "3", "30");
        Harness h = harness(bot, 50L, 50);
        h.world.throwOnExecute = new IllegalStateException("真人 service 拒了（真实形状：锁超时）");
        h.queue.schedule(BotScheduler.BotTask.actionAt("bot-1", NOW,
                new BotDecisionTree.Decision(BotDecisionTree.Action.UPGRADE_BUILDING,
                        false, 0L, "测试：执行会炸")));
        // 同轮再排一个"只到点做决策"的待办会被同一个 world 影响，所以这里只验失败这一支的完整性
        assertThat(h.scheduler.processDue(NOW)).isEqualTo(1);
        assertThat(h.scheduler.executedCount()).as("失败不算成功执行").isZero();
        assertThat(h.scheduler.failedCount()).isEqualTo(1);
        assertThat(h.queue.botIds()).as("失败之后仍然在作息表上").contains("bot-1");
    }

    @Test
    @DisplayName("规则构造期校验：预算为 0 等于 Bot 永远不行动")
    void rulesAreValidated() {
        assertThatThrownBy(() -> new BotScheduler.Rules(0L, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("roundBudgetMillis");
        assertThatThrownBy(() -> new BotScheduler.Rules(5L, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dueBatchLimit");
        assertThatThrownBy(() -> BotScheduler.BotTask.decisionAt(" ", NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("botId");
        assertThatThrownBy(() -> BotScheduler.BotTask.actionAt("bot-1", NOW, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("decisionAt");
    }
}
