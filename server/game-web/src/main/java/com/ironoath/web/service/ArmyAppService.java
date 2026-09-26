package com.ironoath.web.service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.log.TraceContext;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.num.Rates;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.ConfigException;
import com.ironoath.config.cfg.BuildingCfg;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.config.cfg.UnitCfg;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.army.AutoTrainPolicy;
import com.ironoath.core.city.BuildingInstance;
import com.ironoath.core.city.CityState;
import com.ironoath.core.formula.Formula;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.web.dto.generated.ArmyListResp;
import com.ironoath.web.dto.generated.ArmyTreatSpeedUpReq;
import com.ironoath.web.dto.generated.ArmyUnitReq;
import com.ironoath.web.dto.generated.AutoTrainReq;
import com.ironoath.web.dto.generated.AutoTrainResp;
import com.ironoath.web.dto.generated.AutoTrainView;
import com.ironoath.web.dto.generated.HospitalView;
import com.ironoath.web.dto.generated.ResourceAmount;
import com.ironoath.web.dto.generated.ResourceType;
import com.ironoath.web.dto.generated.TrainCancelResp;
import com.ironoath.web.dto.generated.TrainReq;
import com.ironoath.web.dto.generated.TrainResp;
import com.ironoath.web.dto.generated.TreatReq;
import com.ironoath.web.dto.generated.TreatResp;
import com.ironoath.web.dto.generated.UnitReturned;
import com.ironoath.web.dto.generated.UnitType;
import com.ironoath.web.dto.generated.UnitView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：军队应用服务 —— 训练队列、兵种阶级解锁、医院与治疗（B05 §二）。
 * 依赖：game-core（军队聚合、公式）、game-config（unit / building / item / global）、
 * 城建服务（加锁与结算的唯一入口）、武将服务（带兵上限）。
 *
 * <p><b>所有涉及资源与城建的操作都走 {@link CityAppService#withSettledCity}</b>，
 * 不自己加锁、不自己 load 城建：仓储返回的是深拷贝，两个域各改各的会让后写的那份
 * 覆盖前一份的改动，而乐观锁在单线程内拦不住这种情况（详见那个方法的注释）。
 *
 * <p><b>带兵上限把「训练中」也算进去</b>：否则玩家可以先把队列塞满、再换上低统率的武将，
 * 用「已在训练」绕过上限 —— 那是一个不需要任何技巧、只要知道机制就能用的漏洞。
 *
 * <p><b>兵种阶级解锁完全由配置驱动</b>（铁律 1）：unit 表的 unlockBuilding + unlockBuildingLevel
 * 对上城建里那栋建筑的当前等级，代码里没有任何「T3 需要兵营 10 级」这样的字面量。
 * 所以调阶级门槛只需要改表，不需要改代码，也不需要重新发版。
 */
@Service
public class ArmyAppService {

    private static final Logger LOG = LoggerFactory.getLogger(ArmyAppService.class);

    /**
     * 求助请求 id 的前缀。带上 playerId 是必须的：请求 id 是社交存储的主键，
     * 而兵种 id（{@code unit_infantry_t1}）不含玩家 —— 两个玩家同时训同一个兵种时，
     * 只用兵种 + 完成时刻拼出来的 id 会撞成一条，其中一个人的求助会静默消失。
     */
    private static final String TRAIN_HELP_PREFIX = "help_train_";
    private static final String TREAT_HELP_PREFIX = "help_treat_";
    /** 治疗的 targetKey 前缀：加速时用不到它（治疗全局唯一），它的作用是让"下一轮治疗"不继承上一轮的额度。 */
    private static final String TREAT_TARGET_PREFIX = "treat_";

    private final ConfigRegistry configs;
    private final ArmyRepository armies;
    private final CityAppService cityAppService;
    private final HeroAppService heroAppService;
    private final RewardPorts.Bag bagPort;
    private final IdempotencyStore idempotency;
    private final com.ironoath.common.time.TimeService timeService;
    /**
     * 求助请求的登记器（叶子依赖）。训练与治疗的起点就是"可被帮助"的起点（B10 §2：
     * 升级／治疗可请求帮助），所以生产者在<b>本服务</b>，而不是让社交服务反过来依赖军队 ——
     * 依赖方向只有一条：军队/城建 → 登记器 → 社交存储。
     */
    private final com.ironoath.web.social.HelpRequestRegistrar helpRequests;
    /** 任务进度的事件入口（B12 §1）：训练/治疗这类"做完了"才算进展的动作由这里上报。 */
    private final com.ironoath.web.quest.QuestEvents questEvents;
    /** 读玩家存档用（只为拿科技那一位：医院容量要按 HOSPITAL_CAPACITY 放大）。 */
    private final com.ironoath.core.player.PlayerRepository players;
    /** 科技加成的唯一读取口（B20 块①）。 */
    private final com.ironoath.web.tech.TechEffects techEffects;
    /** 国家科技那一份（B20 块③）：与个人的**相加**后作用一次（§五④）。 */
    private final com.ironoath.web.nation.NationTechBonuses nationTechBonuses;

    public ArmyAppService(ConfigRegistry configs, ArmyRepository armies,
                          CityAppService cityAppService, HeroAppService heroAppService,
                          RewardPorts.Bag bagPort, IdempotencyStore idempotency,
                          com.ironoath.common.time.TimeService timeService,
                          com.ironoath.web.social.HelpRequestRegistrar helpRequests,
                          com.ironoath.web.quest.QuestEvents questEvents,
                          com.ironoath.core.player.PlayerRepository players,
                          com.ironoath.web.tech.TechEffects techEffects,
                          com.ironoath.web.nation.NationTechBonuses nationTechBonuses) {
        this.configs = configs;
        this.armies = armies;
        this.cityAppService = cityAppService;
        this.heroAppService = heroAppService;
        this.bagPort = bagPort;
        this.idempotency = idempotency;
        this.timeService = timeService;
        this.helpRequests = helpRequests;
        this.questEvents = questEvents;
        this.players = players;
        this.techEffects = techEffects;
        this.nationTechBonuses = nationTechBonuses;
    }

    /**
     * 「幂等 → 在城建锁内执行」的统一骨架。
     *
     * <p>幂等必须在<b>加锁之前</b>占用：反过来（先加锁再占幂等）会让一个重放请求
     * 先把玩家锁抢到手、再发现是重复请求而释放，白占一次锁；
     * 高并发下这会让正常请求排队等一个注定失败的请求。
     * 失败时必须释放幂等键，否则玩家重试会被永久挡在门外。
     */
    private <T> T guarded(String playerId, String requestId, java.util.function.Supplier<T> action) {
        long now = timeService.serverNow();
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + requestId);
        }
        try {
            return action.get();
        } catch (RuntimeException e) {
            idempotency.release(requestId);
            throw e;
        }
    }

    // ---------- 查询 ----------

    /**
     * 军队总览。
     *
     * <p>这是个有副作用的「读」：它会顺带收割到点的训练与治疗，并在自动续训/补兵开着时
     * 排下一批（B25 裁决③(a) 的惰性执行）。服务端不跑定时器，状态只能在有人读的时候被推进（B00 陷阱 2）。
     */
    public ArmyListResp list(String playerId) {
        return cityAppService.withSettledCity(playerId, snap -> {
            ArmyState army = settledArmy(playerId, snap.now());
            runAutoTrainIfDue(playerId, army, snap);
            return toListResp(playerId, army, snap);
        });
    }

    // ---------- 自动续训 / 补兵（B25 裁决③(a)）----------

    /**
     * 开关自动续训 / 补兵。
     *
     * <p>开启时用 {@link AutoTrainPolicy#on}（续训：每批 batchCount，排到预算用尽）或
     * {@link AutoTrainPolicy#refill}（补兵：把该兵种补回目标数）；关闭时就是把策略清成
     * {@link AutoTrainPolicy#off()} —— <b>关闭不需要再报一遍目标</b>，否则玩家会以为关不掉。
     *
     * <p>开启的当口就按同一条策略试排第一批：玩家会在队列空着的时候开这个开关，
     * 那批正好就是他开开关的直接理由，等下一次读才排会让人以为没生效。
     */
    public AutoTrainResp autoTrain(String playerId, AutoTrainReq req) {
        validateRequest(playerId, req == null ? null : req.requestId());
        if (req.enabled()) {
            if (req.unitId() == null || req.unitId().isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "开启自动续训必须指定 unitId");
            }
            if (req.count() == null || req.count() <= 0L) {
                throw new BizException(ErrorCode.PARAM_INVALID, "每批数量必须为正");
            }
            if (req.batchBudget() == null || req.batchBudget() <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "批次预算必须为正");
            }
            long maxBatches = configs.longParam("AUTO_TRAIN_MAX_BATCHES");
            if (req.batchBudget() > maxBatches) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "一次最多自动排 " + maxBatches + " 批，请求了 " + req.batchBudget() + " 批");
            }
            if (req.targetCount() != null && req.targetCount() <= 0L) {
                throw new BizException(ErrorCode.PARAM_INVALID, "补兵目标必须为正");
            }
        }

        return guarded(playerId, req.requestId(), () -> cityAppService.withSettledCity(playerId, snap -> {
            ArmyState army = loadOrCreate(playerId);
            long version = armies.versionOf(playerId);
            army.collectFinished(snap.now());
            if (req.enabled()) {
                UnitCfg unit = requireUnit(req.unitId());
                requireUnlocked(unit, snap.city());
                army.setAutoTrain(req.targetCount() == null
                        ? AutoTrainPolicy.on(req.unitId(), req.count(), req.batchBudget())
                        : AutoTrainPolicy.refill(req.unitId(), req.targetCount(), req.count(),
                                req.batchBudget()));
            } else {
                army.setAutoTrain(AutoTrainPolicy.off());
            }
            armies.save(playerId, army, version);
            runAutoTrainIfDue(playerId, army, snap);
            LOG.info("自动续训开关 playerId={} enabled={} 策略={}", playerId, req.enabled(),
                    army.autoTrain());
            return new AutoTrainResp(toAutoTrainView(army.autoTrain()), snap.now());
        }));
    }

    /**
     * 自动续训 / 补兵的**惰性执行**：此刻该不该排下一批，该就真的排一批。
     *
     * <p><b>为什么只能在"读"里执行</b>：服务端不跑定时器，一切随时间推进的状态都只能在有人读的
     * 时候被推进（B00 陷阱 2）—— /city/list 的产出、/army/list 的收割都是这条纪律。自动续训也一样：
     * 它的触发时刻是"上一批训完了"，而这件事恰恰只在结算那一瞬间才被服务端知道。
     *
     * <p><b>它走的是真人那条排队路径</b>（{@link #queueBatch}）：同一条阶级解锁与队列/带兵上限校验、
     * 同一条资源扣减、同一份训练时长（科技加成也在内）、同样登记联盟求助。红线（§8.8.3 P1）：
     * 自动续训**不得变成免资源或免冷却** —— 这里没有任何"免费"分支，唯一差别是谁按的确认键。
     *
     * <p><b>失败不能把读打挂</b>：这是 /army/list 的路径，玩家来看一眼军队不该收到 500。排队失败
     * （带兵上限满了、队列被别的批次占着、资源刚被别处花掉）时把策略落成"已停止 + 原因"，
     * 让玩家在列表里读到一句能照着做的提示。
     *
     * <p><b>调用方必须已经持有该玩家的锁</b>，且应当在调用前完成收割 —— 否则"上一批训完了"
     * 这件事还没被推进到军队状态里，判定会基于一个过期的队列。
     *
     * @return 有没有改动军队状态（决定要不要再落一次库）
     */
    private boolean runAutoTrainIfDue(String playerId, ArmyState army, CityAppService.CitySnapshot snap) {
        AutoTrainPolicy policy = army.autoTrain();
        if (!policy.enabled()) {
            return false;   // 绝大多数玩家走这条：没开开关就没有任何额外开销
        }
        AutoTrainPolicy.Outcome outcome;
        try {
            UnitCfg unit = requireUnit(policy.unitId());
            ArmyState.TrainingTask task = army.queue().get(policy.unitId());
            long current = army.countOf(policy.unitId()) + (task == null ? 0L : task.count());
            long want = policy.nextBatchCount(current);
            outcome = policy.plan(current, !army.queue().isEmpty(),
                    want > 0L && affordable(snap.player(), trainCost(unit, want)));
            if (outcome.decision() == AutoTrainPolicy.AutoTrainDecision.QUEUE_NEXT) {
                long finishAt = queueBatch(snap, army, unit, outcome.count(), snap.now());
                LOG.info("自动续训排下一批 playerId={} unit={} 数量={} 完成于={} 剩余预算={}批",
                        playerId, unit.id(), outcome.count(), finishAt, outcome.next().batchBudget());
            }
        } catch (BizException e) {
            // 排队失败不是玩家的操作错误，而是"这一刻排不了"：把原因留在策略上，让他在列表里读到
            outcome = new AutoTrainPolicy.Outcome(AutoTrainPolicy.AutoTrainDecision.STOPPED, 0L,
                    policy.stoppedBecause("自动续训已停下：" + e.getMessage()));
            LOG.info("自动续训停下 playerId={} 原因={}", playerId, e.getMessage());
        }
        if (outcome.next().equals(policy)) {
            return false;
        }
        army.setAutoTrain(outcome.next());
        armies.save(playerId, army, armies.versionOf(playerId));
        return true;
    }

    /**
     * 取<b>已结算</b>的军队状态：收割到点的训练与治疗，有变化就落库。
     *
     * <p><b>调用方必须已经持有该玩家的锁</b>（正常路径是在
     * {@link CityAppService#withSettledCity} 里面调它）。本方法自己不加锁 ——
     * 加锁的话就会与外层重复，而 {@code PlayerLock} 的可重入性是实现细节，不该被依赖。
     *
     * <p>为什么要单独抽出来：军队是「读的时候才推进」的惰性状态，
     * 任何需要真实兵力的地方都必须先收割一次。B08 的战力重算就是这样一个地方 ——
     * 少了这一步，玩家训完兵立刻看战力会看到一个没变化的数字，
     * 而这恰恰是他最想看到变化的时刻。三处（总览、战力、出征）共用一份实现，
     * 才不会出现「总览里兵到了、战力里没到」这种自相矛盾的状态。
     */
    public ArmyState settledArmy(String playerId, long now) {
        ArmyState army = loadOrCreate(playerId);
        long version = armies.versionOf(playerId);
        // 先记下这一批到点的训练（兵种 → 数量），再收割：任务进度要的是「真的训好了多少兵」，
        // 而 TrainingTask 自带 count。判定与 collectFinished 用的是同一条（finishAt <= now），
        // 由 QuestEndpointTest 的「训练完成推进任务进度」把两者钉在一起（漂了就会红）
        Map<String, Long> dueCounts = new LinkedHashMap<>();
        for (ArmyState.TrainingTask task : army.queue().values()) {
            if (task.finishAt() <= now) {
                dueCounts.merge(task.unitId(), task.count(), Long::sum);
            }
        }
        List<String> trained = army.collectFinished(now);
        Map<String, Long> healed = army.collectTreated(now);
        if (!trained.isEmpty() || !healed.isEmpty()) {
            armies.save(playerId, army, version);
            LOG.info("军队结算触发收割 playerId={} 训练完成={} 治疗归队={}", playerId, trained, healed);
        }
        // 训练完成才算「训过兵」（B12 §1）：在入队时就记的话，取消训练的人也会拿到进度，
        // 而任务链的下一条偏偏要用这批兵去打怪
        for (Map.Entry<String, Long> entry : dueCounts.entrySet()) {
            questEvents.progress(playerId, com.ironoath.core.quest.GoalType.TRAIN_UNIT,
                    entry.getKey(), entry.getValue(), now);
        }
        return army;
    }

    // ---------- 训练 ----------

    /** 开始一批训练。时间 = 单位时间 × 数量（B05 §二），批量不等于加速。 */
    public TrainResp train(String playerId, TrainReq req) {
        validateRequest(playerId, req == null ? null : req.requestId());
        if (req.unitId() == null || req.unitId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "unitId 不得为空");
        }
        if (req.count() <= 0L) {
            throw new BizException(ErrorCode.PARAM_INVALID, "count 必须为正，实际=" + req.count());
        }
        UnitCfg unit = requireUnit(req.unitId());

        return guarded(playerId, req.requestId(), () -> cityAppService.withSettledCity(playerId, snap -> {
            ArmyState army = loadOrCreate(playerId);
            long version = armies.versionOf(playerId);
            army.collectFinished(snap.now());

            long finishAt = queueBatch(snap, army, unit, req.count(), snap.now());
            armies.save(playerId, army, version);

            long baseSeconds = unit.trainTimeSec() * req.count();
            long remaining = Math.max(0L, (finishAt - snap.now()) / 1000L);
            Map<String, Long> cost = trainCost(unit, req.count());
            // 基础与实耗两个都记：只记表里那个数字的话，科技生效与否在日志里根本读不出来
            LOG.info("开始训练 playerId={} unit={} 数量={} 基础耗时={}秒 实耗={}秒 完成于={} 消耗={} 带兵={}/{}",
                    playerId, req.unitId(), req.count(), baseSeconds, remaining, finishAt, cost,
                    army.totalTroops() + army.totalTraining(), heroAppService.troopCap(playerId));
            // 开始训练不是加速，reducedSeconds 恒为 0
            return new TrainResp(req.unitId(), req.count(), finishAt, remaining, 0L,
                    toAmounts(cost), army.totalTroops(), heroAppService.troopCap(playerId), snap.now());
        }));
    }

    /**
     * 真正把一批兵排进队列：阶级解锁 → 队列/带兵上限 → 扣资源 → 入队 → 登记求助。
     *
     * <p><b>真人那条 {@link #train} 与自动续训共用这一份</b>（B25 裁决③(a) 的红线：自动续训
     * 不得变成免资源或免冷却）。两条路径的差别只有"谁按的确认键"，其余一律相同：同一条资源校验
     * 与扣减、同一条队列与带兵上限、同一份训练时长（含科技加成）、同一份联盟求助。
     *
     * <p><b>调用方必须已经持有该玩家的锁</b>（两个调用方都在 {@code withSettledCity} 里面），
     * 并负责在返回后落库 —— 这里只改内存里的聚合与快照。
     *
     * @return 这批训练的完成时刻
     */
    private long queueBatch(CityAppService.CitySnapshot snap, ArmyState army, UnitCfg unit,
                            long count, long now) {
        String playerId = snap.player().playerId();
        requireUnlocked(unit, snap.city());
        long troopCap = heroAppService.troopCap(playerId);
        int slots = availableSlots(army);
        long batchMax = configs.longParam("TRAIN_BATCH_MAX");
        // 校验先于扣费：玩家应当先看到「队列已满」「超出带兵上限」这种可操作的提示，
        // 而不是「资源不足」—— 后者会让人以为去攒资源就行，实际问题是队列或统帅值
        army.canTrain(unit.id(), count, slots, troopCap, batchMax);
        Map<String, Long> cost = trainCost(unit, count);

        // 扣资源先于入队：反过来在资源不足时会让玩家白训一批（可直接刷的漏洞）
        deduct(snap, cost);
        // §五④：个人与国家两份**相加**成总率，再由 ArmyState 作用在批次总时长上（只作用一次）
        long trainSpeedFixed = techEffects.trainSpeedPercent(snap.player().tech())
                + nationTechBonuses.trainSpeedPercent(playerId);
        long finishAt;
        try {
            finishAt = army.train(unit.id(), count, unit.trainTimeSec(), trainSpeedFixed,
                    slots, troopCap, batchMax, now);
        } catch (RuntimeException e) {
            refund(snap, cost);
            throw e;
        }

        // 训练一开始就登记求助请求（B10 §2）：别人帮一次就真的缩短这批训练的完成时刻。
        // targetKey 用 unitId —— 训练队列就是按兵种主键的，它就是这个目标的身份
        if (finishAt > now) {
            helpRequests.register(TRAIN_HELP_PREFIX + playerId + "_" + unit.id() + "_" + finishAt,
                    playerId, com.ironoath.web.dto.generated.HelpTargetKind.TRAINING,
                    unit.id(), unit.name() + " ×" + count, finishAt, now);
        }
        return finishAt;
    }

    /** 取消一批训练，按比例返还资源（与城建取消同一口径，比例来自 city_rule）。 */
    public TrainCancelResp cancel(String playerId, ArmyUnitReq req) {
        validateRequest(playerId, req == null ? null : req.requestId());
        if (req.unitId() == null || req.unitId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "unitId 不得为空");
        }
        UnitCfg unit = requireUnit(req.unitId());

        return guarded(playerId, req.requestId(), () -> cityAppService.withSettledCity(playerId, snap -> {
            ArmyState army = loadOrCreate(playerId);
            long version = armies.versionOf(playerId);
            ArmyState.TrainingTask task = army.cancel(req.unitId(), snap.now());

            // 返还按「这批训练实际花的资源 × 返还比例」算。比例复用城建的取消返还比例：
            // 两处各自配一个数字，玩家会问「为什么取消建造返 60% 取消训练返 40%」，
            // 而这种差异没有任何设计意图，只是配置分散的结果
            long refundFixed = snap.rules().cancelRefundFixed();
            Map<String, Long> spent = trainCost(unit, task.count());
            Map<String, Long> refund = new LinkedHashMap<>();
            spent.forEach((resource, amount) -> {
                long back = FixedPoint.round(FixedPoint.mul(FixedPoint.of(amount), refundFixed));
                if (back > 0L) {
                    refund.put(resource, back);
                }
            });
            grant(snap, refund);
            armies.save(playerId, army, version);
            // 目标没了，请求也不能留着：留着就会有人帮一个不存在的目标 ——
            // 额度真的扣掉、事件真的发出，而训练早就取消了
            helpRequests.withdraw(playerId, req.unitId());
            LOG.info("取消训练 playerId={} unit={} 数量={} 返还={}",
                    playerId, req.unitId(), task.count(), refund);
            return new TrainCancelResp(req.unitId(), task.count(), toAmounts(refund), snap.now());
        }));
    }

    /**
     * 加速一批训练。
     *
     * <p>{@code itemId} 非空时走道具加速（item 表 effectKind=REDUCE_TRAIN_SECONDS），
     * 秒数取自道具的 effectValue 而不是请求里的 seconds —— <b>客户端不能自己报加速量</b>，
     * 否则改一下请求体就能瞬间训完（B00 铁律 3：服务器权威）。
     */
    public TrainResp speedUp(String playerId, ArmyUnitReq req) {
        validateRequest(playerId, req == null ? null : req.requestId());
        if (req.unitId() == null || req.unitId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "unitId 不得为空");
        }
        return guarded(playerId, req.requestId(), () -> cityAppService.withSettledCity(playerId, snap -> {
            ArmyState army = loadOrCreate(playerId);
            long version = armies.versionOf(playerId);
            army.collectFinished(snap.now());

            long seconds = resolveSpeedUpSeconds(playerId, req);
            long reduced = army.speedUp(req.unitId(), seconds, snap.now());
            if (reduced <= 0L) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "兵种 " + req.unitId() + " 的训练已完成，请领取而不是加速");
            }
            armies.save(playerId, army, version);

            ArmyState.TrainingTask task = army.queue().get(req.unitId());
            long finishAt = task == null ? snap.now() : task.finishAt();
            long remaining = task == null ? 0L : task.remainingSeconds(snap.now());
            LOG.info("训练加速 playerId={} unit={} 提前={}秒 剩余={}秒 道具={}",
                    playerId, req.unitId(), reduced, remaining, req.itemId());
            return new TrainResp(req.unitId(), task == null ? 0L : task.count(), finishAt, remaining,
                    reduced, List.of(), army.totalTroops(),
                    heroAppService.troopCap(playerId), snap.now());
        }));
    }

    /**
     * 解析这次加速的秒数。
     *
     * <p>道具加速要先扣道具（原子扣减，不足则整体失败）；直接报秒数的路径
     * 只允许在服务端内部调用（例如联盟帮助），<b>不接受客户端传来的 seconds</b>。
     */
    private long resolveSpeedUpSeconds(String playerId, ArmyUnitReq req) {
        if (req.itemId() != null && !req.itemId().isBlank()) {
            ItemCfg item = requireItem(req.itemId());
            if (item.effectKind() != ItemCfg.EffectKind.REDUCE_TRAIN_SECONDS) {
                throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                        "道具 " + item.name() + " 的效果是 " + item.effectKind() + "，不能加速训练");
            }
            if (bagPort.remove(playerId, req.itemId(), 1L) == 0L) {
                throw new BizException(ErrorCode.ITEM_NOT_ENOUGH,
                        "需要 " + item.name() + " 1 个，当前持有 "
                                + bagPort.countOf(playerId, req.itemId()) + " 个");
            }
            return item.effectValue();
        }
        // 没有道具就必须由服务端给出秒数。客户端传 seconds 一律忽略 ——
        // 接受它等于把「加速多少」的决定权交给客户端
        throw new BizException(ErrorCode.PARAM_INVALID,
                "加速训练必须指定 itemId（联盟帮助等免费加速由 B10 落地后走独立入口）");
    }

    // ---------- 医院与治疗 ----------

    /** 开始治疗全部伤兵。耗时与资源都来自配置，客户端不参与计算。 */
    public TreatResp treat(String playerId, TreatReq req) {
        validateRequest(playerId, req == null ? null : req.requestId());
        return guarded(playerId, req.requestId(), () -> cityAppService.withSettledCity(playerId, snap -> {
            ArmyState army = loadOrCreate(playerId);
            long version = armies.versionOf(playerId);
            army.collectTreated(snap.now());

            long woundedTotal = army.totalWounded();
            if (woundedTotal <= 0L) {
                throw new BizException(ErrorCode.PARAM_INVALID, "没有伤兵需要治疗");
            }
            long secondsPerWounded = configs.longParam("TREAT_TIME_PER_WOUNDED");
            long totalSeconds = woundedTotal * secondsPerWounded;
            Map<String, Long> cost = treatCost(army, woundedTotal);

            deduct(snap, cost);
            long finishAt;
            try {
                finishAt = army.startTreatment(totalSeconds, cost, snap.now());
            } catch (RuntimeException e) {
                refund(snap, cost);
                throw e;
            }
            armies.save(playerId, army, version);

            // 与训练同理：治疗开始即求助（B10 §2 明写"升级／治疗可请求帮助"）。
            // targetKey 带 finishAt：治疗是一个玩家全局唯一的一份倒计时，
            // 下一轮治疗必须是新的目标键，否则会继承上一轮已用掉的加速额度（上限按目标记账）
            if (finishAt > snap.now()) {
                helpRequests.register(TREAT_HELP_PREFIX + playerId + "_" + finishAt, playerId,
                        com.ironoath.web.dto.generated.HelpTargetKind.TREATING,
                        TREAT_TARGET_PREFIX + finishAt,
                        "治疗 " + woundedTotal + " 名伤兵", finishAt, snap.now());
            }

            LOG.info("开始治疗 playerId={} 伤兵={} 耗时={}秒 完成于={} 消耗={}",
                    playerId, woundedTotal, totalSeconds, finishAt, cost);
            return toTreatResp(army, cost, List.of(), snap.now());
        }));
    }

    /** 加速治疗。与训练加速同一口径：秒数只能来自道具配置，不接受客户端报数。 */
    public TreatResp treatSpeedUp(String playerId, ArmyTreatSpeedUpReq req) {
        validateRequest(playerId, req == null ? null : req.requestId());
        return guarded(playerId, req.requestId(), () -> cityAppService.withSettledCity(playerId, snap -> {
            ArmyState army = loadOrCreate(playerId);
            long version = armies.versionOf(playerId);
            if (req.itemId() == null || req.itemId().isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "加速治疗必须指定 itemId");
            }
            ItemCfg item = requireItem(req.itemId());
            if (item.effectKind() != ItemCfg.EffectKind.REDUCE_TRAIN_SECONDS) {
                // 治疗与训练共用「减少秒数」这一类道具：两者的语义完全相同（缩短一个倒计时），
                // 再开一种 REDUCE_TREAT_SECONDS 只会让玩家背包里多两种功能重复的道具
                throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                        "道具 " + item.name() + " 的效果是 " + item.effectKind() + "，不能加速治疗");
            }
            if (bagPort.remove(playerId, req.itemId(), 1L) == 0L) {
                throw new BizException(ErrorCode.ITEM_NOT_ENOUGH,
                        "需要 " + item.name() + " 1 个，当前持有 "
                                + bagPort.countOf(playerId, req.itemId()) + " 个");
            }
            long reduced;
            try {
                reduced = army.speedUpTreatment(item.effectValue(), snap.now());
            } catch (RuntimeException e) {
                bagPort.add(playerId, req.itemId(), 1L);
                throw e;
            }
            if (reduced <= 0L) {
                bagPort.add(playerId, req.itemId(), 1L);
                throw new BizException(ErrorCode.PARAM_INVALID, "治疗已完成，请领取而不是加速");
            }
            armies.save(playerId, army, version);
            LOG.info("治疗加速 playerId={} 提前={}秒 剩余={}秒 道具={}",
                    playerId, reduced, army.treatRemainingSeconds(snap.now()), req.itemId());
            return toTreatResp(army, Map.of(), List.of(), snap.now());
        }));
    }

    /** 收割已完成的治疗：伤兵归队。归队不受带兵上限约束（这些兵本来就是玩家的）。 */
    public TreatResp collectTreated(String playerId, TreatReq req) {
        validateRequest(playerId, req == null ? null : req.requestId());
        return guarded(playerId, req.requestId(), () -> cityAppService.withSettledCity(playerId, snap -> {
            ArmyState army = loadOrCreate(playerId);
            long version = armies.versionOf(playerId);
            Map<String, Long> returned = army.collectTreated(snap.now());
            if (!returned.isEmpty()) {
                armies.save(playerId, army, version);
                LOG.info("伤兵归队 playerId={} 归队={}", playerId, returned);
            }
            List<UnitReturned> views = new ArrayList<>(returned.size());
            returned.forEach((unitId, count) -> views.add(new UnitReturned(unitId, count)));
            return toTreatResp(army, Map.of(), views, snap.now());
        }));
    }

    // ---------- 内部：配置解析与数值 ----------

    /**
     * 医院容量 = 医院等级 × woundedCapBase 按 BUILDING_OUTPUT 曲线递增，再乘上个人科技的
     * {@code HOSPITAL_CAPACITY}（定点万分比，作用一次）。没建医院就是 0 —— 加成没有可放大的基数，
     * 也不该凭空造出容量。
     *
     * <p>取整用 HALF_UP 而不是 §五④ 的 ceil：那条裁决管的是<b>缩短时长</b>（BUILD/TRAIN/MARCH 三条），
     * 向上取整是为了防"0 秒队列"；容量是绝对值，套同一条 ceil 只会让它系统性偏大一截。
     */
    public long hospitalCapacity(String playerId, CityState city) {
        long exponent = configs.curve("BUILDING_OUTPUT").exponentFixed();
        long capacity = 0L;
        for (BuildingInstance b : city.buildings()) {
            BuildingCfg cfg = configs.get(BuildingCfg.class, b.configId());
            if (cfg.woundedCapBase() <= 0L || b.level() <= 0 || b.isUpgrading()) {
                continue;   // 升级中的医院不提供容量，与「升级中的建筑不产资源」同一口径
            }
            capacity += FixedPoint.round(Formula.buildingOutput(
                    FixedPoint.of(cfg.woundedCapBase()), b.level(), exponent));
        }
        if (capacity <= 0L) {
            return 0L;
        }
        PlayerSave save = playerId == null ? null : players.findByPlayerId(playerId).orElse(null);
        long percentFixed = save == null ? 0L : techEffects.hospitalPercent(save.tech());
        return Rates.scaleUp(capacity, percentFixed);
    }

    /**
     * 兵种阶级是否已解锁（B05 §二：由兵营等级 + 科技共同决定）。
     *
     * <p><b>「科技」那一半今天接不了，而且不是"等接线"</b>：{@code tech.json} 的
     * {@code effectAttr} 枚举里没有任何"解锁兵阶"属性（只有产量/攻防/速度/容量/负载十一项），
     * 所以这里缺的是一行表 + 一个枚举取值 —— 那是设计裁决，不是这里能补的代码。
     * 曾经这条注释写着"等 B12 科技系统落地"，那是双重失真：科技系统已落地（B20 块①），
     * 而它没有这一项效果。<b>不要为了让这句话成立而发明一个属性 id。</b>
     */
    public boolean isUnlocked(UnitCfg unit, CityState city) {
        BuildingInstance building = city.findByConfigId(unit.unlockBuilding());
        return building != null && building.level() >= unit.unlockBuildingLevel();
    }

    /** 未解锁时的结构化提示。口径与城建的「需要 X，当前 Y」一致（B03 验收 6）。 */
    private String unlockHint(UnitCfg unit, CityState city) {
        BuildingInstance building = city.findByConfigId(unit.unlockBuilding());
        int current = building == null ? 0 : building.level();
        BuildingCfg cfg = configs.get(BuildingCfg.class, unit.unlockBuilding());
        return "需要" + cfg.name() + " " + unit.unlockBuildingLevel() + " 级，当前 " + current + " 级";
    }

    /** 单个兵的训练消耗 × 数量。三种资源都从 unit 表读，代码里没有数字。 */
    private Map<String, Long> trainCost(UnitCfg unit, long count) {
        Map<String, Long> cost = new LinkedHashMap<>();
        putIfPositive(cost, ResourceIds.WOOD, unit.trainCostWood(), count);
        putIfPositive(cost, ResourceIds.IRON, unit.trainCostIron(), count);
        putIfPositive(cost, ResourceIds.GRAIN, unit.trainCostGrain(), count);
        return cost;
    }

    private static void putIfPositive(Map<String, Long> cost, String resource, long perUnit, long count) {
        if (perUnit <= 0L) {
            return;
        }
        long total = perUnit * count;
        if (total / count != perUnit) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "训练消耗溢出 long：" + resource + " " + perUnit + " × " + count);
        }
        cost.put(resource, total);
    }

    /**
     * 治疗消耗 = Σ(该兵种伤兵数 × 该兵种训练消耗 × TREAT_COST_RATIO)。
     *
     * <p>按兵种逐个算而不是按总数算：T5 攻城器的治疗成本应当高于 T1 步兵，
     * 用「总伤兵数 × 某个平均单价」会让高级兵和低级兵治起来一样贵，
     * 玩家就会永远只治高级兵、把低级兵直接放弃。
     */
    private Map<String, Long> treatCost(ArmyState army, long woundedTotal) {
        long ratio = configs.fixedParam("TREAT_COST_RATIO");
        Map<String, Long> cost = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : army.wounded().entrySet()) {
            UnitCfg unit = requireUnit(entry.getKey());
            Map<String, Long> unitCost = trainCost(unit, entry.getValue());
            unitCost.forEach((resource, amount) -> cost.merge(resource,
                    FixedPoint.round(FixedPoint.mul(FixedPoint.of(amount), ratio)), Long::sum));
        }
        if (woundedTotal > 0L && cost.isEmpty()) {
            // 伤兵有数量却算不出消耗，说明 unit 表的三种训练消耗全是 0 —— 那是配置漏填
            throw new BizException(ErrorCode.CONFIG_INVALID,
                    "伤兵共 " + woundedTotal + " 名却算不出治疗消耗，检查 unit 表的 trainCost* 列");
        }
        return cost;
    }

    private int availableSlots(ArmyState army) {
        int base = (int) configs.longParam("TRAIN_QUEUE_SLOTS");
        return base + army.extraSlots();
    }

    // ---------- 内部：资源读写 ----------

    /**
     * 扣资源。直接改 CitySnapshot 里的 PlayerSave（已由 withSettledCity 结算过），
     * 由它统一落库 —— 这里<b>不</b>自己 save，否则会与外层的双写冲突。
     */
    private void deduct(CityAppService.CitySnapshot snap, Map<String, Long> cost) {
        for (Map.Entry<String, Long> entry : cost.entrySet()) {
            PlayerResourceState state = snap.player().resource(entry.getKey());
            if (state.current() < entry.getValue()) {
                throw new BizException(ErrorCode.RESOURCE_NOT_ENOUGH,
                        entry.getKey() + " 需要 " + entry.getValue() + "，当前 " + state.current());
            }
        }
        // 先全部校验再统一扣：逐项「校验一个扣一个」会在第二项不足时留下一个已扣第一项的中间态
        cost.forEach((resource, amount) -> {
            PlayerResourceState state = snap.player().resource(resource);
            snap.player().putResource(resource, new PlayerResourceState(
                    state.current() - amount, state.cap(), state.protectedAmount(),
                    state.perHour(), state.lastSettle()));
        });
    }

    /** 退还资源（后续步骤失败时）。受容量上限约束，超出部分丢弃并记日志。 */
    private void refund(CityAppService.CitySnapshot snap, Map<String, Long> cost) {
        try {
            grant(snap, cost);
        } catch (RuntimeException e) {
            LOG.error("【退还资源失败】playerId={} 明细={} traceId={} 必须人工补偿",
                    snap.player().playerId(), cost, TraceContext.traceId(), e);
        }
    }

    /** 发放资源（取消返还）。受容量上限约束，超出部分丢弃并记日志。 */
    private void grant(CityAppService.CitySnapshot snap, Map<String, Long> amounts) {
        amounts.forEach((resource, amount) -> {
            if (amount <= 0L) {
                return;
            }
            PlayerResourceState state = snap.player().resource(resource);
            long next = Math.min(state.cap(), state.current() + amount);
            if (next < state.current() + amount) {
                LOG.info("返还资源超出容量上限，多余部分丢弃 playerId={} resource={} 请求={} 实发={}",
                        snap.player().playerId(), resource, amount, next - state.current());
            }
            snap.player().putResource(resource, new PlayerResourceState(
                    next, state.cap(), state.protectedAmount(), state.perHour(), state.lastSettle()));
        });
    }

    // ---------- 内部：装配与校验 ----------

    private ArmyListResp toListResp(String playerId, ArmyState army, CityAppService.CitySnapshot snap) {
        long troopCap = heroAppService.troopCap(playerId);
        long hospitalCap = hospitalCapacity(playerId, snap.city());
        List<UnitView> units = new ArrayList<>();
        for (UnitCfg unit : configs.all(UnitCfg.class)) {
            ArmyState.TrainingTask task = army.queue().get(unit.id());
            boolean unlocked = isUnlocked(unit, snap.city());
            units.add(new UnitView(unit.id(), unit.name(), UnitType.valueOf(unit.type().name()),
                    (int) unit.tier(), army.countOf(unit.id()),
                    army.wounded().getOrDefault(unit.id(), 0L),
                    task == null ? 0L : task.count(),
                    task == null ? null : task.finishAt(),
                    task == null ? 0L : task.remainingSeconds(snap.now()),
                    unlocked, unlocked ? null : unlockHint(unit, snap.city()),
                    unit.trainTimeSec(), toAmounts(trainCost(unit, 1L))));
        }
        long secondsPerWounded = configs.longParam("TREAT_TIME_PER_WOUNDED");
        HospitalView hospital = new HospitalView(hospitalCap, army.totalWounded(),
                army.treatFinishAt() != null, army.treatFinishAt(),
                army.treatRemainingSeconds(snap.now()),
                secondsPerWounded, configs.fixedParam("TREAT_COST_RATIO"));
        return new ArmyListResp(units, troopCap, army.totalTroops(), army.totalTraining(),
                army.usedSlots(), availableSlots(army), hospital, toAutoTrainView(army.autoTrain()),
                snap.now());
    }

    /** 策略 → 视图。字段一一对应，不做加工：客户端要读的就是服务端真正在按它执行的那份策略。 */
    private static AutoTrainView toAutoTrainView(AutoTrainPolicy policy) {
        return new AutoTrainView(policy.enabled(), policy.unitId(), policy.batchCount(),
                policy.batchBudget(), policy.targetCount(), policy.stopReason());
    }

    private TreatResp toTreatResp(ArmyState army, Map<String, Long> cost,
                                  List<UnitReturned> returned, long now) {
        return new TreatResp(army.totalWounded(), army.treatFinishAt() != null,
                army.treatFinishAt(), army.treatRemainingSeconds(now),
                toAmounts(cost), returned, now);
    }

    private ArmyState loadOrCreate(String playerId) {
        var existing = armies.findByPlayerId(playerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        armies.insertIfAbsent(playerId, new ArmyState());
        return armies.findByPlayerId(playerId)
                .orElseThrow(() -> new BizException(ErrorCode.SYSTEM_ERROR, "军队存档创建后立即读不到"));
    }

    /** 「现在能训哪种兵、最多能训几个」的产物。 */
    public record Trainable(String unitId, long count) {
    }

    /**
     * 第一个<b>此刻解锁且队列/人口有空位</b>的兵种，判定复用 {@code isUnlocked} 与领域层的
     * {@code ArmyState#canTrain}（与 {@code train} 同一条算式，不在这里抄第二份）。
     *
     * <p><b>给谁用</b>：Bot 的 tick（{@code BotWorldAdapter}）。它要往决策树里填
     * {@code canAffordTraining}，而"自己比一次资源和等级"就是红点系统同款的双真相。
     *
     * <p><b>它是"挑一个值得尝试的目标"，不是"保证成功"</b>：真正的资源扣减判定仍在
     * {@link #train} 里（那里连扣费与回滚都在同一把锁内）。所以本方法近似偏乐观时，
     * 最坏结果是 Bot 空跑一次并被计入失败数 —— 不会有 Bot 白拿东西，也不会有真人被多扣。
     */
    public java.util.Optional<Trainable> firstTrainable(String playerId, long now) {
        return cityAppService.withSettledCity(playerId, ctx -> {
            ArmyState army = loadOrCreate(playerId);
            long troopCap = heroAppService.troopCap(playerId);
            int slots = availableSlots(army);
            long batchMax = configs.longParam("TRAIN_BATCH_MAX");
            long room = Math.min(batchMax, troopCap - army.totalTroops() - army.totalTraining());
            if (room <= 0L) {
                return java.util.Optional.<Trainable>empty();   // 带兵上限已满，问哪个兵种都没用
            }
            for (UnitCfg unit : configs.all(UnitCfg.class)) {
                if (!isUnlocked(unit, ctx.city())) {
                    continue;
                }
                try {
                    army.canTrain(unit.id(), room, slots, troopCap, batchMax);
                } catch (RuntimeException e) {
                    continue;   // 队列/批量等硬约束不过，换下一个兵种
                }
                if (!affordable(ctx.player(), trainCost(unit, room))) {
                    continue;   // 资源不够这个数量（真判定仍在 train 里，这里只是别选一个必败的目标）
                }
                return java.util.Optional.of(new Trainable(unit.id(), room));
            }
            return java.util.Optional.<Trainable>empty();
        });
    }

    /** 与 {@code deduct} 同一份比对的只读版本（不改存档、不抛玩家可见错误，只回答"付得起吗"）。 */
    private boolean affordable(PlayerSave player, Map<String, Long> cost) {
        for (Map.Entry<String, Long> entry : cost.entrySet()) {
            PlayerResourceState state = player.resources().get(entry.getKey());
            if (state != null && state.current() >= entry.getValue()) {
                continue;
            }
            return false;
        }
        return true;
    }

    private void requireUnlocked(UnitCfg unit, CityState city) {
        if (!isUnlocked(unit, city)) {
            throw new BizException(ErrorCode.UNIT_NOT_UNLOCKED,
                    unit.name() + " 尚未解锁：" + unlockHint(unit, city));
        }
    }

    private void validateRequest(String playerId, String requestId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "requestId 不得为空");
        }
    }

    private UnitCfg requireUnit(String unitId) {
        try {
            return configs.get(UnitCfg.class, unitId);
        } catch (ConfigException e) {
            throw new BizException(ErrorCode.CONFIG_NOT_FOUND, "兵种配置不存在: " + unitId);
        }
    }

    private ItemCfg requireItem(String itemId) {
        try {
            return configs.get(ItemCfg.class, itemId);
        } catch (ConfigException e) {
            throw new BizException(ErrorCode.ITEM_NOT_FOUND, "道具配置不存在: " + itemId);
        }
    }

    private static List<ResourceAmount> toAmounts(Map<String, Long> amounts) {
        List<ResourceAmount> out = new ArrayList<>(amounts.size());
        amounts.forEach((resource, amount) ->
                out.add(new ResourceAmount(ResourceType.valueOf(resource), amount)));
        return out;
    }
}
