package com.ironoath.core.bot;

import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.time.DayKey;
import com.ironoath.common.rng.Rng;

/**
 * 职责：Bot 的作息调度 —— 决定「哪个 Bot 此刻该行动、行动做什么、下一次什么时候再动」（B11 §三/§四）。
 * 依赖：game-common 的 Rng 与 FixedPoint；本包的决策树与作息表。<b>不依赖任何仓储或 service</b>。
 *
 * <p><b>本类只编排，不执行</b>：真实动作全部经 {@link World} 端口出去，由 game-web 转调
 * 与真人<b>完全相同</b>的 service。这是 B11 的头号铁律，也是 CI 检查
 * （{@code scripts/check-no-bot-privilege.sh}）盯着的东西 —— 一旦 Bot 有了自己的执行路径，
 * 「用 Bot 验证数值」这件事就失去了全部意义：Bot 的成长曲线会与真人悄悄脱节，
 * 而脱节的表现是「压测数据很好看，真人留存很差」。
 *
 * <p><b>三个刻意的设计选择</b>：
 * <ol>
 *   <li><b>队列只有「拉」，没有回调注册</b>（{@link TaskQueue}）。与 {@code MarchDueQueue} 同一条纪律：
 *       接口一旦允许注册回调，实现方最自然的做法就是给每个 Bot 开一个定时器，
 *       而 B11 明写禁止 per-bot timer（一千个 Bot 就是一千个定时器）。
 *       「拉」这个形状本身就是红线的保障</li>
 *   <li><b>没有常驻线程</b>：{@link #processDue} 由外部驱动，设计上挂在"请求驱动推进"的现成位置
 *       （行军的到期扫描就是同一个形状）。<b>今天仍然没有任何驱动方调它</b> —— 与收口清单 #77 那次相比
 *       情况已经前进了一半：{@code BotRegistry.register} 现在有生产调用点了（孵化，#84），
 *       世界上真的有 Bot，但<b>没有一次 tick 会被推进</b>，所以它们是静止的。
 *       驱动方式与预算口径的裁定见收口清单 §五（C0/C1）。
 *       代价本来要说清楚 —— <b>没有玩家在线时 Bot 也不动</b>，这是可接受的：Bot 存在的理由是
 *       让世界对玩家显得有人气，一个没人看的服务器里世界是否在动没有任何观测意义；
 *       而换来的是「服务端无任何常驻定时调度」这条 CI 卡口不需要为 Bot 开豁免。
 *       <b>唯一的例外是反应延迟</b>：验收 9 要 3~30 秒，而懒驱动给不了上界 ——
 *       所以 prod 必须有外部调度按秒级打驱动端点，那条算术写在 global 的 BOT_ROUND_BUDGET_MS 里</li>
 *   <li><b>预算中断</b>（{@link Rules#roundBudgetMillis}）：一次驱动最多占用这么久，
 *       剩下的留到下一次。没有预算的话，一次长时间无人在线之后的追赶会把上千个 Bot
 *       的决策挤在同一个请求里，而那个请求的发起人是某个刚好点了下城池面板的倒霉玩家</li>
 * </ol>
 *
 * <p><b>拟人延迟是两阶段的</b>：{@code Decision.delayMillis} 表示「受击后 3~30 秒才反应」，
 * 所以决策与执行必须分成两个待办 —— 立即执行的话延迟就白设计了，
 * 而「被打之后 0 毫秒就反击」是 Bot 最容易被人一眼认出来的特征之一。
 */
public final class BotScheduler {

    /**
     * Bot 与真实世界之间唯一的接缝。
     *
     * <p><b>实现方必须转调与真人相同的 service</b>，不得为 Bot 单开一条写库路径。
     * 也不得在这里给 Bot 任何数值优待：{@code observe} 返回的每一个布尔都必须是
     * 「查真实钱包/军队/行军名额之后的事实」，而不是「Bot 版本放宽一点」——
     * 决策树只消费事实，放宽口径就会变成两套「够不够」，而两套口径迟早分叉。
     */
    public interface World {

        /**
         * 这个 id 当前的 Bot 画像。
         *
         * @return null 表示它<b>已经不是 Bot 了</b>（被清理、转成真人、或存档丢失）。
         *         调度器据此把它从队列里摘掉 —— 对一个不再是 Bot 的玩家继续执行 Bot 决策，
         *         等于让系统替他玩他的号
         */
        BotProfile profileOf(String botId);

        /** 采集这个 Bot 此刻能看到的世界状态（全部是查真实存档得到的事实）。 */
        BotDecisionTree.WorldState observe(String botId, long now);

        /** 执行一个决策。<b>必须转调真人走的同一批 service</b>。 */
        void execute(String botId, BotDecisionTree.Decision decision, long now);

        /** 这个 Bot 的匹配战力（用于判断它是否落后于真人平均值，落后就提高 tick 频率）。 */
        long matchPowerOf(String botId);

        /** 真人玩家的平均匹配战力。没有真人时返回 0，此时不做追赶加速。 */
        long humanAverageMatchPower(long now);
    }

    /**
     * 待办队列。<b>只有拉取，没有回调注册</b>，理由见类注释。
     */
    public interface TaskQueue {

        void schedule(BotTask task);

        /**
         * 取出到期时间早于 now 的待办，按到期时刻升序。
         *
         * <p><b>实现方必须在返回的同时把它们从队列里摘掉</b>（与 {@code MarchDueQueue.dueBefore}
         * 同一条约定）：否则同一个待办会被下一次驱动重复取出，
         * 而 Bot 的重复执行不像行军那样有幂等键兜着。
         */
        List<BotTask> dueBefore(long now, int limit);

        /** 撤销某个 Bot 的全部待办（清理 Bot 时用）。 */
        void cancel(String botId);

        int size();
    }

    /**
     * 一个待办。
     *
     * @param pending 非 null 表示「这是一个已经决定好、等到点执行的动作」；
     *                null 表示「到点该做一次新决策」
     */
    public record BotTask(String botId, long dueAt, BotDecisionTree.Decision pending) {

        public BotTask {
            if (botId == null || botId.isBlank()) {
                throw new IllegalArgumentException("botId 不得为空");
            }
            if (dueAt <= 0L) {
                throw new IllegalArgumentException("dueAt 必须为正的服务端时间戳，实际=" + dueAt);
            }
        }

        public static BotTask decisionAt(String botId, long dueAt) {
            return new BotTask(botId, dueAt, null);
        }

        public static BotTask actionAt(String botId, long dueAt, BotDecisionTree.Decision decision) {
            if (decision == null) {
                throw new IllegalArgumentException("actionAt 需要一个已决定的动作，否则用 decisionAt");
            }
            return new BotTask(botId, dueAt, decision);
        }

        public boolean isAction() {
            return pending != null;
        }
    }

    /**
     * @param roundBudgetMillis 一次 {@link #processDue} 最多占用多久（真正的墙钟闸，超时即中断留待下次）。
     *                          来源 global.BOT_ROUND_BUDGET_MS。<b>不要与 BOT_TICK_BUDGET_MS 混为一谈</b>：
     *                          那个 5ms 是「单个 Bot 一次 tick 的耗时目标」（验收 6 拿去对着日志量的量具），
     *                          不是这里的闸 —— 名字曾经叫 tickBudgetMillis 而干的是整轮的活，
     *                          2026-09-12 的 C0 拆开并改名。必须为正：为 0 等于一个 Bot 都不处理。
     * @param dueBatchLimit     一次最多从队列里取多少条。来源 global.BOT_DUE_BATCH_LIMIT。
     *                          与预算是两道闸：预算防「一次跑太久」，条数防「预算太大时一次跑太多」
     */
    public record Rules(long roundBudgetMillis, int dueBatchLimit) {
        public Rules {
            if (roundBudgetMillis < 1L) {
                throw new IllegalArgumentException("roundBudgetMillis 必须 >= 1，实际=" + roundBudgetMillis
                        + "。为 0 等于 Bot 永远不行动");
            }
            if (dueBatchLimit < 1) {
                throw new IllegalArgumentException("dueBatchLimit 必须 >= 1，实际=" + dueBatchLimit);
            }
        }
    }

    private static final long MILLIS_PER_DAY = 24L * 60 * 60 * 1000;

    /** 一小时（历法定义而不是可调数值：与 DayKey.CALENDAR_ZONE 同一套日历）。 */
    private static final long MILLIS_PER_HOUR = 60L * 60L * 1000L;

    private final Rules rules;
    private final World world;
    private final TaskQueue queue;
    private final BotDecisionTree tree;
    private final BotSchedule schedule;
    private final Rng rng;
    private final LongSupplier nanoClock;

    private long executedCount;
    private long deferredCount;
    private long droppedCount;
    private long budgetHitCount;
    /** observe / decide / execute 抛出来的次数（失败不重跑、也不让那个 Bot 退出调度）。 */
    private long failedCount;

    public BotScheduler(Rules rules, World world, TaskQueue queue, BotDecisionTree tree,
                        BotSchedule schedule, Rng rng) {
        this(rules, world, queue, tree, schedule, rng, System::nanoTime);
    }

    /**
     * @param nanoClock 预算计时用的纳秒时钟。<b>注入而不是直接读 {@code System.nanoTime()}</b>：
     *                  预算中断这条逻辑必须能在单测里被精确验证（「预算只够跑两个」），
     *                  而靠真实时钟去测超时是典型的 flaky 测试来源
     */
    public BotScheduler(Rules rules, World world, TaskQueue queue, BotDecisionTree tree,
                        BotSchedule schedule, Rng rng, LongSupplier nanoClock) {
        if (rules == null || world == null || queue == null || tree == null || schedule == null
                || rng == null || nanoClock == null) {
            throw new IllegalArgumentException("BotScheduler 的七个依赖都不得为 null");
        }
        this.rules = rules;
        this.world = world;
        this.queue = queue;
        this.tree = tree;
        this.schedule = schedule;
        this.rng = rng;
        this.nanoClock = nanoClock;
    }

    /**
     * 把一个 Bot 登记进作息表（孵化完成时调用一次）。
     *
     * <p>第一次行动的延迟与之后每次相同：不给「刚孵化就立刻动一下」的特殊照顾，
     * 因为一批同时孵化的 Bot 若都立刻行动，世界上会在同一秒冒出一批升级与出征 ——
     * 那比 Bot 本身更像 Bot。
     */
    public void enroll(String botId, long now) {
        BotProfile profile = world.profileOf(botId);
        if (profile == null) {
            throw new IllegalArgumentException("不是 Bot，无法登记进作息表: " + botId);
        }
        queue.schedule(BotTask.decisionAt(botId, nextTickAt(profile, now)));
    }

    /**
     * 推进所有到点的 Bot。<b>由外部驱动</b>，本类不起任何线程或定时器。
     *
     * <p><b>今天还没有驱动方</b>（别照旧版本的注释以为它挂在行军到期扫描上 —— 那是设计意图，不是事实）。
     * C1 要接的两个位置见收口清单 §五：请求路径兜底 + {@code POST /bot/tick}（外部调度按秒级打，
     * 否则验收 9 的 3~30 秒反应延迟没有上界保证）。
     *
     * @return 本次实际处理的待办条数
     */
    public int processDue(long now) {
        long startNanos = nanoClock.getAsLong();
        List<BotTask> due = queue.dueBefore(now, rules.dueBatchLimit());
        int processed = 0;
        for (BotTask task : due) {
            if (overBudget(startNanos)) {
                // 预算用完：剩下的待办已经被 dueBefore 摘出队列了，必须放回去，
                // 否则它们会永久消失 —— 那几个 Bot 从此再也不动，而日志里一切正常
                requeue(task);
                budgetHitCount++;
                continue;
            }
            handle(task, now);
            processed++;
        }
        return processed;
    }

    /**
     * 处理一个待办。<b>一个 Bot 的失败不许带走整轮、更不许让它自己退出调度</b>。
     *
     * <p>这里每件事都可能抛：{@code observe} 要读存档与钱包（真人侧的 service），{@code execute}
     * 要过锁与乐观锁，{@code nextTickDelay} 要问均值。原先的写法是让它们直接往外抛 ——
     * 而"下一次决策"的重排代码写在成功路径之后，于是<b>一次异常就把这个 Bot 永久摘出了作息表</b>，
     * 症状是"地图上某个 Bot 从某天起再也不动"，而日志里只有一行栈。
     * 失败改成计数（{@link #failedCount()}）+ 一律重排下一次；重排本身再失败时无处可兜，
     * 那就让它抛（那是队列坏了，不是 Bot 坏了，必须响）。
     */
    private void handle(BotTask task, long now) {
        BotProfile profile = world.profileOf(task.botId());
        if (profile == null) {
            // 它已经不是 Bot 了：摘掉，不再安排下一次
            droppedCount++;
            queue.cancel(task.botId());
            return;
        }
        if (task.isAction()) {
            runQuietly(() -> world.execute(task.botId(), task.pending(), now));
            requeueDecision(task.botId(), profile, now);
            return;
        }
        BotDecisionTree.Decision decision;
        try {
            decision = tree.decide(profile, world.observe(task.botId(), now), rng, now);
        } catch (RuntimeException e) {
            // 不往外抛、也不在这里打日志（core 零框架）：失败只进计数，
            // 由驱动方（game-web）每轮把 executed/failed 一起打出来，原因留给 World 实现自己记
            failedCount++;
            requeueDecision(task.botId(), profile, now);
            return;
        }
        if (decision.delayMillis() > 0L) {
            // 拟人延迟：等到点再执行，而不是现在执行完再等
            deferredCount++;
            queue.schedule(BotTask.actionAt(task.botId(), now + decision.delayMillis(), decision));
            return;
        }
        runQuietly(() -> world.execute(task.botId(), decision, now));
        requeueDecision(task.botId(), profile, now);
    }

    /** 跑一个动作，失败只计数。成功才计入 executedCount（仿真的断言就看这两个数的差）。 */
    private void runQuietly(Runnable action) {
        try {
            action.run();
            executedCount++;
        } catch (RuntimeException e) {
            failedCount++;
        }
    }

    /** 把某个 Bot 的下一次决策排回队列。 */
    private void requeueDecision(String botId, BotProfile profile, long now) {
        queue.schedule(BotTask.decisionAt(botId, nextTickAt(profile, now)));
    }

    /**
     * 下一次 tick 的<b>时刻</b>（间隔 × 相位两件事，这里管相位，间隔由 {@link #nextTickDelay} 管）。
     *
     * <p>B11 §三 的原话是「按 {@code persona.activeHours} 分布」，§四 补了曲线形状
     * （午休 12-13、晚高峰 20-22、凌晨低频）。<b>在这一行之前，间隔是全天均匀的</b> ——
     * 于是凌晨 3 点照样 tick，验收 10（凌晨 tick 次数 &lt; 高峰的 20%）从数学上就不可能成立，
     * 而 {@code bot_schedule} 那 142 行权重也没有任何人读（改了不生效、也不报错）。
     *
     * <p>规则只有两条，都能一句话解释：<b>落在活跃小时内就保持基准节奏</b>（不为了曲线牺牲密度），
     * <b>跨出活跃小时就按 LOGIN 权重挑下一个小时</b>（曲线是数据，不是硬规则 ——
     * 硬规则会被下一次改配置绕过，与 {@link BotSchedule#nightToPeakRatio} 同一条理由）。
     *
     * <p>小时起点再加一段 jitter（不超过基准间隔，也不超过一小时）：全部 Bot 都在整点动，
     * 是比"凌晨也动"更明显的机器特征。
     */
    private long nextTickAt(BotProfile profile, long now) {
        long interval = nextTickDelay(profile, now);
        long due = now + interval;
        List<Integer> active = profile.persona().activeHours();
        if (active.contains(hourOf(due))) {
            return due;
        }
        int target = pickWeightedActiveHour(active);
        if (target < 0) {
            return due;   // 表里没有可用的 LOGIN 权重时，宁可退回均匀节奏，也不要抛错让 Bot 停止调度
        }
        long hourStart = startOfHour(due);
        int current = hourOf(due);
        long aheadHours = (target - current + 24) % 24;
        if (aheadHours == 0) {
            aheadHours = 24;   // 目标小时就是当前这一小时（它不在活跃表里），等到明天同一小时
        }
        return hourStart + aheadHours * MILLIS_PER_HOUR + jitterMillis(interval);
    }

    /** 在活跃小时里按 {@code bot_schedule} 的 LOGIN 权重加权抽一个小时；无交集返回 -1。 */
    private int pickWeightedActiveHour(List<Integer> active) {
        Map<Integer, Integer> byHour = schedule.rules().weights().get(BotSchedule.Action.LOGIN);
        int total = 0;
        for (Integer hour : active) {
            Integer weight = byHour == null ? null : byHour.get(hour);
            if (weight != null && weight > 0) {
                total += weight;
            }
        }
        if (total <= 0) {
            return -1;
        }
        int roll = (int) rng.range(0, total - 1L);
        int accumulator = 0;
        for (Integer hour : active) {
            Integer weight = byHour == null ? null : byHour.get(hour);
            if (weight == null || weight <= 0) {
                continue;
            }
            accumulator += weight;
            if (roll < accumulator) {
                return hour;
            }
        }
        return -1;
    }

    /** 小时内偏移：取「基准间隔」与「一小时」里较小的那个作为上界，避免整点齐动。 */
    private long jitterMillis(long interval) {
        long bound = Math.min(MILLIS_PER_HOUR - 1L, interval);
        if (bound <= 0L) {
            return 0L;
        }
        return rng.range(0, bound);
    }

    /** 小时序号按 {@code DayKey.CALENDAR_ZONE}（UTC+8）算 —— 与日界、周界、赛季阶段同一套日历。 */
    private static int hourOf(long millis) {
        return java.time.Instant.ofEpochMilli(millis).atZone(DayKey.CALENDAR_ZONE).getHour();
    }

    private static long startOfHour(long millis) {
        return java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis),
                DayKey.CALENDAR_ZONE).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .toInstant().toEpochMilli();
    }

    /**
     * 下一次决策的<b>间隔</b>。
     *
     * <p>= 一天 / 当日 tick 数（由活跃度决定）/ 追赶倍率。
     * <b>追赶只缩短间隔，不改任何数值</b>：落后的 Bot 多动几次，
     * 每次动的收益与真人完全相同 —— 直接给它加战力就是禁止项说的特权捷径。
     */
    long nextTickDelay(BotProfile profile, long now) {
        int ticksPerDay = schedule.ticksPerDay(profile.ai().activeness());
        if (ticksPerDay < 1) {
            ticksPerDay = 1;
        }
        long base = MILLIS_PER_DAY / ticksPerDay;
        long botPower = world.matchPowerOf(profile.botId());
        long humanAverage = world.humanAverageMatchPower(now);
        boolean behind = humanAverage > 0L && botPower < humanAverage;
        long multiplier = tree.catchUpTickMultiplier(profile, behind);
        if (multiplier <= FixedPoint.ONE) {
            return base;
        }
        // multiplier 是定点数（1.5 → 15000），所以 base × SCALE / multiplier = base / 1.5
        long shortened = base * FixedPoint.SCALE / multiplier;
        return Math.max(1L, shortened);
    }

    private boolean overBudget(long startNanos) {
        long elapsedMillis = (nanoClock.getAsLong() - startNanos) / 1_000_000L;
        return elapsedMillis >= rules.roundBudgetMillis();
    }

    private void requeue(BotTask task) {
        queue.schedule(task);
    }

    /** 已执行的动作数（含延迟到期后执行的）。 */
    public long executedCount() {
        return executedCount;
    }

    /** 因拟人延迟而被推后执行的动作数。非零说明「受击反应」这条路径在生效。 */
    public long deferredCount() {
        return deferredCount;
    }

    /** 因「已不是 Bot」而被摘掉的待办数。 */
    public long droppedCount() {
        return droppedCount;
    }

    /** 预算被用满的次数。持续非零说明 Bot 数量或预算需要调整。 */
    public long budgetHitCount() {
        return budgetHitCount;
    }

    /**
     * 失败的 tick 数。<b>它与 executedCount 一起才说明"跑动了没有"</b>：
     * 只有 processed 而没有 executed/failed 的话，说明轮都在跑、什么都没发生。
     */
    public long failedCount() {
        return failedCount;
    }

    public int pendingCount() {
        return queue.size();
    }

    public Rules rules() {
        return rules;
    }
}
