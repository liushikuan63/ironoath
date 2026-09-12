package com.ironoath.web.bot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.common.rng.Rng;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bot.BotDecisionTree;
import com.ironoath.core.bot.BotSchedule;
import com.ironoath.core.bot.BotScheduler;

/**
 * 职责：把 {@link BotScheduler} 从"写好但没人调"变成"真的在跑"（B11 §三 调度，收口清单 §五 C1）。
 * 依赖：装配器（每轮现读规则）、待办队列（端口，跨重建存活）、世界适配器（真人 service 的那一侧）、注册表。
 *
 * <p><b>两个驱动入口，分工不同</b>：
 * <ol>
 *   <li>{@link #tick(long)} —— 权威驱动。{@code POST /bot/tick} 由<b>外部调度系统</b>按秒级节奏打。
 *       服务端不起常驻定时器（B11 §三/§十，且 {@code check-layering.sh} 把它做成了 CI 卡口），
 *       所以"多久打一次"是部署事实而不是代码事实。为什么必须那么密：见 global 的
 *       {@code BOT_ROUND_BUDGET_MS} 那条算术 —— 5000 Bot × 288 次/日 摊进活跃窗口是峰值 40 tick/秒，
 *       而一轮最多清 50 条。</li>
 *   <li>{@link #driveFromRequestPath(long)} —— 兜底。玩家读写世界时顺带推一轮，节流 15 秒。
 *       它存在的意义是"没接调度系统的部署（本机 dev、单机）世界也照样动"，
 *       <b>不是</b>主驱动：没人请求时它就不跑。</li>
 * </ol>
 *
 * <p><b>调度器跟着配置走，而不是启动时钉死</b>：其余装配路径都刻意"每次现读、不缓存"，
 * 这样改了表不必发版；如果这里做成一个 Spring 单例，症状恰恰是"我调了 BOT_DUE_BATCH_LIMIT
 * 却没生效也没报错"。所以本类持有当前实例 + 它当时用的那两份规则，<b>规则一变就重建</b>
 * （队列是端口、独立存活，所以重建不会丢掉任何 Bot 的排期；只有累计计数复位，并打一条 INFO）。
 *
 * <p><b>登记是懒做的</b>：每轮开始前把注册表里还没排进作息表的 Bot 补进去。不在孵化那一刻直接调这里，
 * 是为了不形成「孵化 → 调度 → 适配器 → 孵化」的依赖环（适配器的真人均值口径要问孵化侧）。
 *
 * <p><b>进程内状态</b>：{@code enrolled} 与待办队列都在内存 —— 与画像、孵化节流同一笔债
 * （#84 丙：多实例要 Redis 才不会各跑各的）。重启后第一轮重新登记全部 Bot，
 * 代价是每个 Bot 的下一次 tick 被推后一个基准间隔，不丢档。
 */
@Service
public class BotRuntimeService implements BotCalibrationService.BotSchedulerAccess {

    private static final Logger LOG = LoggerFactory.getLogger(BotRuntimeService.class);

    /**
     * 请求路径兜底的最小间隔（行为常量，不是游戏数值）：比孵化的 60 秒节流密，因为 tick 才是
     * "让世界看起来有人气"的那件事；比 ops 节奏疏，因为它只是替代，不该变成主驱动。
     */
    static final long SAFETY_NET_BUDGET_MILLIS = 15_000L;

    private final BotRulesAssembler assembler;
    private final BotScheduler.TaskQueue queue;
    private final BotScheduler.World world;
    private final BotRegistry registry;
    private final BotWorldAdapter adapter;
    private final ConfigRegistry configs;
    /** 攻击频控的账本：清理超过 24h 的记录（唯一"系统自己会动的时刻"就是本类的 tick）。 */
    private final BotAttackLimiter attackLimiter;
    /**
     * 每日校准与回收（§五/§八）。<b>挂在 tick 上而不是常驻定时器</b>：
     * 「每天一次」由日期键做幂等，而"每天"这件事只能由"有人推了一脚"来兑现
     * （与孵化、行军到期同一条先例 —— 服务端禁常驻定时器）。
     */
    private final BotCalibrationService calibration;
    /**
     * Bot「开始活动」时补的手续：把家门口的迷雾点亮
     * （{@link com.ironoath.web.service.WorldAppService#ensureHomeExplored}）。
     *
     * <p>为什么在这一刻补而不是孵化那一刻：孵化器（{@code BotSpawnService}）不能依赖世界服务
     * （视野下发会驱动孵化，反向即成环），而本类是世界服务的下游，注入方向天然成立。
     * 顺带它还补上了「本次改动之前孵出来的 Bot」—— 它们下一次被登记时会一并点亮
     * （{@code BotRegistry} 清空后重登也一样，登记是幂等的）。这正是
     * {@code BotSpawnService#placeNear} 注释里写的「等运行时接上（它要自己看世界）再走
     * 与真人相同的入口，那时这一步才需要补」中的那一步。
     */
    private final com.ironoath.web.service.WorldAppService worldService;

    /** 当前调度器 + 它当时用的两份规则。规则一变就重建（见类注释）。 */
    private final AtomicReference<Wiring> wiring = new AtomicReference<>();
    /** 已排进作息表的 Bot。 */
    private final Set<String> enrolled = ConcurrentHashMap.newKeySet();
    private final AtomicLong nextSafetyNetAt = new AtomicLong();
    private final AtomicLong rounds = new AtomicLong();

    public BotRuntimeService(BotRulesAssembler assembler, BotScheduler.TaskQueue queue,
                             BotScheduler.World world, BotRegistry registry,
                             BotWorldAdapter adapter, ConfigRegistry configs,
                             BotAttackLimiter attackLimiter, BotCalibrationService calibration,
                             com.ironoath.web.service.WorldAppService worldService) {
        this.assembler = assembler;
        this.queue = queue;
        this.world = world;
        this.registry = registry;
        this.adapter = adapter;
        this.configs = configs;
        this.attackLimiter = attackLimiter;
        this.calibration = calibration;
        this.worldService = worldService;
        // 反向只给一个窄口（回收时要用的"撤销排期"），不把本类整个交出去。
        // 为什么是回填而不是构造注入：校准要撤销排期、排期在本类手里 —— 构造互相注入就是环
        // （BeanCurrentlyInCreation），与 #82 那次同一条解法：改依赖方向，不用 @Lazy 遮丑
        calibration.bindScheduler(new BotCalibrationService.BotSchedulerAccess() {
            @Override
            public void forget(String botId) {
                BotRuntimeService.this.forget(botId);
            }
        });
    }

    /** 一个调度器实例与让它成立的那两份规则（重建成不成立 = 规则变了）。 */
    private record Wiring(BotScheduler scheduler, BotScheduler.Rules rules, BotSchedule.Rules scheduleRules) {
    }

    /**
     * 跑一轮：补登记 + 推进所有到点的待办（预算与条数由 {@link BotScheduler.Rules} 管）。
     *
     * @return 这一轮的体检数。<b>processed 必须和 executed / failed 一起看</b>：
     *         只有前者在涨说明"轮在跑但什么都没发生"。
     */
    public Map<String, Long> tick(long now) {
        BotScheduler scheduler = scheduler();
        enrollNew(scheduler, now);
        // 顺带清理频控账本里超过 24h 的记录：这张表按受害者索引，不清理就只增不减
        // （与 BotTuning.evict 的注释同一条理由：它是内存泄漏而不是缓存）
        attackLimiter.evict(now);
        // 每天第一轮 tick 顺带做一次全量校准（战力滞后跟随真人均值 + 刷新活跃时间 + 超限回收）。
        // 放在 processDue 之前：让这一轮的决策跑在刚校准过的战力上，而不是拿昨天的战力做今天的判断
        calibration.calibrateIfNewDay(now);
        long start = System.nanoTime();
        int processed = scheduler.processDue(now);
        rounds.incrementAndGet();
        Map<String, Long> report = new LinkedHashMap<>();
        report.put("processed", (long) processed);
        report.put("executed", scheduler.executedCount());
        report.put("deferred", scheduler.deferredCount());
        report.put("dropped", scheduler.droppedCount());
        report.put("failed", scheduler.failedCount());
        report.put("budgetHit", scheduler.budgetHitCount());
        report.put("pending", (long) scheduler.pendingCount());
        report.put("agents", (long) registry.size());
        report.put("rounds", rounds.get());
        report.putAll(adapter.actionCounts());
        if (processed > 0) {
            LOG.info("Bot tick 第 {} 轮：处理={} executed={} failed={} unhandled={} deferred={}"
                            + " 待办={} Bot 数={} 本轮耗时={}ms",
                    rounds.get(), processed, report.get("executed"), report.get("failed"),
                    report.get("unhandled"), report.get("deferred"), report.get("pending"),
                    report.get("agents"), (System.nanoTime() - start) / 1_000_000L);
        }
        return report;
    }

    /**
     * 请求路径的兜底驱动。<b>不许抛</b>：这是别人（某个真人的读图请求）的路径 ——
     * Bot 的世界推进失败不该让那个请求失败（与任务进度那条派生状态同一条纪律，#87）。
     *
     * @return 是否真的跑了这一轮（被节流挡掉时为 false）
     */
    public boolean driveFromRequestPath(long now) {
        long due = nextSafetyNetAt.get();
        if (now < due || !nextSafetyNetAt.compareAndSet(due, now + SAFETY_NET_BUDGET_MILLIS)) {
            return false;
        }
        try {
            tick(now);
            return true;
        } catch (RuntimeException e) {
            LOG.warn("请求路径兜底驱动 Bot tick 失败（不影响本次请求）：{}", e.toString());
            return false;
        }
    }

    /** 已登记的 Bot 数（诊断与用例断言用）。 */
    public int enrolledCount() {
        return enrolled.size();
    }

    /** 测试用：丢掉登记与节流状态，并把已登记 Bot 的待办一并撤掉（不然重新登记会排出一份重复的）。 */
    public void reset() {
        for (String botId : enrolled) {
            queue.cancel(botId);
        }
        enrolled.clear();
        nextSafetyNetAt.set(0L);
        rounds.set(0L);
    }

    /**
     * 把一个 Bot 从作息表上摘掉（回收时调用，§八）。
     *
     * <p><b>两件事都要做</b>：撤销队列里的待办（{@code cancel}）与忘记本地登记
     * （{@code enrolled}）—— 只做前者的话，下一轮 {@code enrollNew} 会把它再排回来，
     * 于是"回收"变成一次假装的动作：它还在动，只是我们不再承认它在册。
     */
    @Override
    public void forget(String botId) {
        queue.cancel(botId);
        enrolled.remove(botId);
    }

    // ---------- 内部 ----------

    /** 拿到与当前配置一致的调度器；配置变了就重建（队列与画像都是外部的，所以不丢排期）。 */
    private BotScheduler scheduler() {
        BotScheduler.Rules rules = assembler.schedulerRules();
        BotSchedule.Rules scheduleRules = assembler.scheduleRules();
        Wiring current = wiring.get();
        if (current != null && current.rules().equals(rules)
                && current.scheduleRules().equals(scheduleRules)) {
            return current.scheduler();
        }
        BotScheduler next = new BotScheduler(rules, world, queue, new BotDecisionTree(),
                new BotSchedule(scheduleRules), Rng.of(rngSeed()));
        if (!wiring.compareAndSet(current, new Wiring(next, rules, scheduleRules))) {
            // 另一个线程刚重建过：用它的结果，别让自己这份带着一样的规则跑两份计数
            return wiring.get().scheduler();
        }
        if (current != null) {
            LOG.info("Bot 调度参数已热更生效：一轮预算={}ms 一轮条数={} 每日 tick={}~{}（计数随之复位）",
                    rules.roundBudgetMillis(), rules.dueBatchLimit(),
                    scheduleRules.ticksPerDayMin(), scheduleRules.ticksPerDayMax());
        }
        return next;
    }

    /**
     * 决策随机源的种子。<b>不开 {@code Math.random}</b>（铁律：随机必须可复现）——
     * 用开服锚点，于是同一台服同一份表跑出来的 Bot 行为序列可以复现。
     * 没配锚点的开发环境退回 0（仍然可复现，只是各台开发机同源，那是可以接受的）。
     */
    private long rngSeed() {
        return configs.hasParam("SERVER_OPEN_AT") ? configs.longParam("SERVER_OPEN_AT") : 0L;
    }

    private void enrollNew(BotScheduler scheduler, long now) {
        for (String botId : registry.botIds()) {
            if (enrolled.add(botId)) {
                scheduler.enroll(botId, now);
                // 「开始看世界」的补手续：点亮家门口（幂等，已亮不写）。
                // 放在登记之后、并且自己兜异常：这一步失败不该让 Bot 不排期 ——
                // 下一次 tick 时它已经登记过（enrolled 里），所以重试只发生在重启/重登之后；
                // 而它偶尔看不到东西的代价只是这一个 tick 落 IDLE，比整轮 tick 抛出去小得多
                try {
                    worldService.ensureHomeExplored(botId);
                } catch (RuntimeException e) {
                    LOG.warn("Bot {} 家门口点亮失败（不影响排期，视野留待下次登记时补）：{}",
                            botId, e.toString());
                }
            }
        }
    }
}
