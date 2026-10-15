package com.ironoath.web.bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.common.time.DayKey;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.bot.BotTuning;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;

/**
 * 职责：Bot 的<b>每日校准</b>与<b>回收</b>（B11 §五 与 §八，收口清单 §五 C4）。
 * 依赖：注册表（谁在册）、装配器（原型系数）、玩家仓储、孵化侧（真人均值口径的唯一持有者）。
 *
 * <p><b>为什么是「每日一次」而不是每个 tick 校准一次</b>：§五 明写「战力校准（每日一次，<b>滞后跟随</b>）」。
 * 滞后跟随的意思是：Bot 的战力跟着服务器真人均值走，但一天只对一次账 —— 每个 tick 都拉平
 * 会让 Bot 的战力曲线变成真人均值的即时镜像（真人均值一抖，几百个 Bot 一起抖），
 * 那既不像人，也会让"参差不齐"这件事消失。
 *
 * <p><b>由谁驱动</b>：与其余惰性结算同一条先例 —— 挂在 Bot tick 上（{@link BotRuntimeService#tick}），
 * 用<b>日期键</b>（{@code DayKey}，UTC+8）做幂等：同一天里无论 tick 多少次，校准只做一次。
 * 服务端不起常驻定时器，所以"每天"这件事只能由"有人推了一脚"来兑现 ——
 * 没有调度系统的部署就只在有请求的日子校准，这与孵化、行军到期同一条口径。
 *
 * <p><b>顺手刷新 {@code lastLoginAt}（#84 乙）</b>：那是「静止 Bot 48 小时后从可攻击列表里消失」
 * 这条已知缺口的正面修法 —— 校准本来就每天对每个 Bot 做一次，刷新活跃时间不额外花任何代价。
 * 刷新的量不是"现在"而是<b>校准时刻</b>：写死 now 会让所有 Bot 的活跃时间整齐划一，
 * 而参差正是 §四 要的东西。
 *
 * <p><b>回收（§八「单服上限 5000，超出回收最不活跃的」）</b>：只在真的超上限时动手，
 * 且按一个<b>可解释的序</b>挑人：最近活跃时间最旧优先，同值时战力最低优先
 * （两条都能从存档上直接读出来，不需要额外维护一张"活跃度"表）。
 * <b>只摘画像与排期，不删档</b> —— 存档里还有他的城、他的兵、他被别人打过的痕迹，
 * 删档是另一件事（B14 赛季重置才谈销毁），本档不做。被摘掉的 Bot 会从
 * 目标搜索的候选池与合规闸门里一起消失（两者都只认注册表），这是对的：
 * 它已经不再被当作"在册的 Bot"了。
 */
@Service
public class BotCalibrationService {

    private static final Logger LOG = LoggerFactory.getLogger(BotCalibrationService.class);
    private static final long LOCK_TIMEOUT_MS = 3000L;

    private final BotRegistry registry;
    private final BotRulesAssembler assembler;
    private final PlayerRepository players;
    private final BotSpawnService spawner;
    private final com.ironoath.core.lock.PlayerLock playerLock;
    private final BotCalibrationBatch batch;
    /**
     * 回收时撤销排期的窄口。<b>由运行时在装配期回填</b>：校准服务要"撤销排期"这个能力，
     * 而那个能力在运行时手里 —— 构造互相注入会形成 {@code runtime ↔ calibration} 的环
     * （Spring 当场拒绝启动）。方向只能有一条：运行时 → 校准。
     */
    private volatile BotSchedulerAccess schedulerAccess;

    /** 本实例当日扫描缓存；每个Bot是否完成以同份玩家存档的持久时刻为准。 */
    private volatile String lastCalibratedDay = "";

    /**
     * 回收的入口：它需要能做"取消排期"这件事，而那是调度器与队列的能力。
     * 用一个窄接口注入而不是直接依赖 {@link BotRuntimeService}，是为了让本类
     * 能被单独测（不需要起整个运行时）。
     */
    public interface BotSchedulerAccess {
        /** 把某个 Bot 从作息表上摘掉（撤销它的待办并忘记它的登记）。 */
        void forget(String botId);
    }

    public BotCalibrationService(BotRegistry registry, BotRulesAssembler assembler,
                                 PlayerRepository players, BotSpawnService spawner,
                                 com.ironoath.core.lock.PlayerLock playerLock) {
        this(registry,assembler,players,spawner,playerLock,new BotCalibrationBatch(1));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BotCalibrationService(BotRegistry registry, BotRulesAssembler assembler,
                                 PlayerRepository players, BotSpawnService spawner,
                                 com.ironoath.core.lock.PlayerLock playerLock,BotCalibrationBatch batch) {
        this.registry = registry;
        this.assembler = assembler;
        this.players = players;
        this.spawner = spawner;
        this.playerLock = playerLock;
        this.batch = java.util.Objects.requireNonNull(batch);
    }

    /** 由运行时在装配期注册"撤销排期"的能力（见字段注释：方向只能从运行时指向校准）。 */
    void bindScheduler(BotSchedulerAccess access) {
        this.schedulerAccess = access;
    }

    /**
     * 每轮 tick 顺带调一次：到了新的一天就校准 + 回收，同一天里直接返回。
     *
     * @return 这一轮是否真的做了校准（诊断与用例断言用）
     */
    public synchronized boolean calibrateIfNewDay(long now) {
        return calibrateDay(now,false);
    }

    /** 只供Runtime顶层使用；不得从持玩家锁的回调调用。旧公开入口仍在调用线程串行。 */
    public synchronized boolean calibrateInParallelIfNewDay(long now) {
        return calibrateDay(now,true);
    }

    private boolean calibrateDay(long now,boolean parallelAllowed) {
        String today = DayKey.of(now);
        if (today.equals(lastCalibratedDay)) {
            return false;
        }
        calibrate(now,parallelAllowed);
        lastCalibratedDay = today;
        return true;
    }

    /**
     * 一次全量校准：拉平战力带 + 刷新活跃时间 + 超限则回收。
     *
     * <p><b>顺序是有意的：先校准、再回收</b> —— 回收要看战力，而战力刚被校准过，
     * 于是"回收谁"这件事用的是同一份新鲜数据，不会出现"用昨天的战力决定今天的去留"。
     */
    public void calibrate(long now) {
        calibrate(now,false);
    }

    private void calibrate(long now,boolean parallelAllowed) {
        List<String> botIds = new ArrayList<>(registry.botIds());
        if (botIds.isEmpty()) {
            return;
        }
        long humanAverage = spawner.humanAverageMatchPower();
        BotTuning tuning = new BotTuning(assembler.tuningRules());
        int recalibrated = 0;
        int touched = 0;
        // 每个worker仍独立获取真实玩家锁，并在锁内重新读取权威档；所有已派发写入收口后才回收。
        boolean independent=parallelAllowed && batch.canDispatchInParallel(botIds.size())
                && players.supportsIndependentPlayerLocks(botIds);
        List<CalibrationResult> results=batch.run(botIds,botId->playerLock.runLocked(botId,LOCK_TIMEOUT_MS,
                ()->calibrateBot(botId,tuning,humanAverage,now)),independent);
        for (CalibrationResult result : results) {
            if (result.recalibrated()) { recalibrated++; }
            if (result.touched()) { touched++; }
        }
        int recycled = recycleIfOverCap(tuning);
        LOG.info("Bot 每日校准完成 日期键={} 在册={} 重定战力={} 刷新活跃={} 回收={} 真人均值={}",
                DayKey.of(now), botIds.size(), recalibrated, touched, recycled, humanAverage);
    }

    private record CalibrationResult(boolean recalibrated, boolean touched) { }

    private CalibrationResult calibrateBot(String botId, BotTuning tuning, long humanAverage, long now) {
        BotProfile profile = registry.profileOf(botId);
        PlayerSave save = players.findByPlayerId(botId).orElse(null);
        if (profile == null || save == null) { return new CalibrationResult(false, false); }
        Long completedAt = save.lastBotCalibrationAt();
        if (completedAt != null && DayKey.of(completedAt).compareTo(DayKey.of(now)) >= 0) {
            return new CalibrationResult(false, false);
        }
        boolean recalibrated = false;
        if (humanAverage > 0L) {
            long target = tuning.targetPower(humanAverage, powerFactorOf(profile));
            if (target > 0L && target != save.power().matchPower()) {
                // 三字段一起写，仍沿用原校准规则与PlayerPower不变量。
                save.setPower(new PlayerPower(target, target, target));
                recalibrated = true;
            }
        }
        save.touchLogin(now);
        save.recordBotCalibration(now);
        // 校准结果、活跃时刻及完成标记共享同一版本化写入，丢回执后重读仍能识别完成。
        players.save(save);
        return new CalibrationResult(recalibrated, true);
    }

    /**
     * 超出单服上限时回收最不活跃的。<b>不删档</b>（见类注释）。
     *
     * @return 本次摘掉几个
     */
    private int recycleIfOverCap(BotTuning tuning) {
        int cap = tuning.rules().maxPerServer();
        List<String> botIds = new ArrayList<>(registry.botIds());
        int over = botIds.size() - cap;
        if (over <= 0) {
            return 0;
        }
        // 序：最近活跃最旧优先；同值取战力最低。两条都直接读存档，不额外维护"活跃度"
        record Candidate(String botId, long lastLoginAt, long matchPower) {
        }
        List<Candidate> candidates = new ArrayList<>(botIds.size());
        for (String botId : botIds) {
            PlayerSave save = players.findByPlayerId(botId).orElse(null);
            if (save == null) {
                continue;
            }
            candidates.add(new Candidate(botId, save.lastLoginAt(), save.power().matchPower()));
        }
        candidates.sort(Comparator.comparingLong(Candidate::lastLoginAt)
                .thenComparingLong(Candidate::matchPower));
        int recycled = 0;
        for (Candidate candidate : candidates) {
            if (recycled >= over) {
                break;
            }
            registry.unregister(candidate.botId());
            BotSchedulerAccess access = schedulerAccess;
            if (access != null) {
                access.forget(candidate.botId());
            }
            recycled++;
            LOG.info("Bot 回收（超上限 {}）：摘掉 {} 最近活跃={} 战力={} —— 只摘画像与排期，存档保留",
                    cap, candidate.botId(), candidate.lastLoginAt(), candidate.matchPower());
        }
        return recycled;
    }

    /** 原型系数：画像里带着 archetypeId，装配器给的是"id → 系数"的清单。 */
    private long powerFactorOf(BotProfile profile) {
        for (var archetype : assembler.archetypes()) {
            if (archetype.id().equals(profile.archetypeId())) {
                return archetype.powerFactorFixed();
            }
        }
        // 画像的原型不在表里（表被改过、或这条画像是历史遗留）：不校准它，也不抛 ——
        // 抛出去会让整轮校准停在中途，而"某一个 Bot 校准不了"不该影响其余几百个
        LOG.warn("Bot {} 的原型 {} 不在 bot_archetype 表里，跳过它的战力校准",
                profile.botId(), profile.archetypeId());
        return 0L;
    }

    /** 测试用：忘掉"今天已校准过"这个事实。 */
    public void reset() {
        lastCalibratedDay = "";
    }
}
