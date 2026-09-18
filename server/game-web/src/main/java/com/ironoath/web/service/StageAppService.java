package com.ironoath.web.service;

import com.ironoath.battle.BattleInput;
import com.ironoath.battle.BattleModifier;
import com.ironoath.battle.BattleResult;
import com.ironoath.battle.BattleSimulator;
import com.ironoath.battle.BattleType;
import com.ironoath.battle.TerrainType;
import com.ironoath.battle.Winner;
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.ConfigException;
import com.ironoath.config.cfg.ChapterCfg;
import com.ironoath.config.cfg.StageCfg;
import com.ironoath.config.cfg.UnitCfg;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.army.TierSplit;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.hero.HeroRoster;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;
import com.ironoath.core.stage.StageProgress;
import com.ironoath.core.stage.StageProgressRepository;
import com.ironoath.web.battle.BattleArmyFactory;
import com.ironoath.web.battle.BattleReportService;
import com.ironoath.web.battle.BattleRulesAssembler;
import com.ironoath.web.battle.HeroBattleMapper;
import com.ironoath.web.dto.generated.ChallengeStageReq;
import com.ironoath.web.dto.generated.ChallengeStageResp;
import com.ironoath.web.dto.generated.StageEntry;
import com.ironoath.web.dto.generated.StageListResp;
import com.ironoath.web.dto.generated.StageReward;
import com.ironoath.web.dto.generated.StageStars;
import com.ironoath.web.dto.generated.StageUnit;
import com.ironoath.web.dto.generated.SweepReq;
import com.ironoath.web.dto.generated.SweepResp;
import com.ironoath.web.dto.generated.SweepResult;
import com.ironoath.web.dto.generated.BossMechanic;
import com.ironoath.web.dto.generated.UnitRestriction;
import com.ironoath.web.reward.ServerSeedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：章节副本 —— 列表、挑战、三星判定、扫荡（B09 §4 / §6）。
 * 依赖：stage/chapter/unit 表、战斗内核、{@link BattleArmyFactory}、{@link HeroBattleMapper}、
 * {@link BattleReportService}、军队/城建/武将/进度仓储、奖励发放器、钱包、玩家锁与幂等。
 *
 * <p><b>验收 2「扫荡结果与手动战斗逐字段一致」是结构保证的，不是靠小心</b>：
 * 挑战与扫荡都调用同一个 {@link #execute}，它内部只有一个 {@code BattleSimulator.simulate} 调用点。
 * B09 把这条禁止项写了两遍（§五 的第一条与第六条），因为「扫荡走独立结算」是这类系统最常见的
 * 偷工减料 —— 独立的扫荡结算通常是个期望值公式，跑得快、看起来也对，
 * 直到玩家发现「手动打十次的收益和扫荡十次不一样」，那时他已经无法验证哪一边是真的。
 *
 * <p><b>扫荡消耗真实兵力与体力</b>。既然走同一个 simulate，战损就是真的；
 * 想让它不消耗兵力就得在 execute 之外再包一层「不落地」的分支，那又回到了独立结算。
 * 扫荡的价值是省时间，不是省代价 —— 而且它被三星门槛挡着，
 * 能扫荡的关卡本来就是压倒性胜利，战损很小。
 *
 * <p><b>BOSS 机制由内核执行，本类只负责把类型传进去</b>（B09 验收 5）：
 * stage 表声明这一关用哪种机制，{@code BattleRulesAssembler.bossMechanic} 从 global 表取参数，
 * {@code BattleSimulator} 在回合循环里执行。类型与参数分开是有意的 ——
 * 策划决定「哪一关用哪种机制」，数值只有一处可调；
 * 若把参数也写进 stage 表的每一行，调一次平衡要改 5 行，
 * 而漏改一行的表现是「只有某个 BOSS 不对劲」，那是最难定位的一类数值问题。
 */
@Service
public class StageAppService {

    private static final Logger LOG = LoggerFactory.getLogger(StageAppService.class);

    private static final long LOCK_TIMEOUT_MS = 3000L;

    private final ConfigRegistry configs;
    private final BattleArmyFactory armyFactory;
    private final BattleRulesAssembler rulesAssembler;
    private final HeroBattleMapper heroMapper;
    private final BattleReportService battleReports;
    private final HeroRepository heroes;
    private final ArmyRepository armies;
    private final CityRepository cities;
    private final ArmyAppService armyAppService;
    private final StageProgressRepository progressRepo;
    private final com.ironoath.core.player.PlayerRepository players;
    private final RewardService rewardService;
    private final com.ironoath.core.reward.RewardPorts.Wallet wallet;
    private final PlayerLock playerLock;
    private final IdempotencyStore idempotency;
    private final TimeService timeService;
    private final ServerSeedSource seeds;
    private final StaminaService staminaService;
    private final com.ironoath.web.battle.BattleTechBonuses techBonuses;

    /** 任务进度的事件入口（B12 §1）：通关关卡/章节由本服务上报。 */
    private final com.ironoath.web.quest.QuestEvents questEvents;

    public StageAppService(ConfigRegistry configs, BattleArmyFactory armyFactory,
                           BattleRulesAssembler rulesAssembler, HeroBattleMapper heroMapper,
                           BattleReportService battleReports, HeroRepository heroes,
                           ArmyRepository armies, CityRepository cities, ArmyAppService armyAppService,
                           StageProgressRepository progressRepo,
                           com.ironoath.core.player.PlayerRepository players,
                           RewardService rewardService,
                           com.ironoath.core.reward.RewardPorts.Wallet wallet,
                           PlayerLock playerLock, IdempotencyStore idempotency,
                           TimeService timeService, ServerSeedSource seeds,
                           StaminaService staminaService,
                           com.ironoath.web.battle.BattleTechBonuses techBonuses,
                           com.ironoath.web.quest.QuestEvents questEvents) {
        this.configs = configs;
        this.armyFactory = armyFactory;
        this.rulesAssembler = rulesAssembler;
        this.heroMapper = heroMapper;
        this.battleReports = battleReports;
        this.heroes = heroes;
        this.armies = armies;
        this.cities = cities;
        this.armyAppService = armyAppService;
        this.progressRepo = progressRepo;
        this.players = players;
        this.rewardService = rewardService;
        this.wallet = wallet;
        this.playerLock = playerLock;
        this.idempotency = idempotency;
        this.timeService = timeService;
        this.seeds = seeds;
        this.staminaService = staminaService;
        this.techBonuses = techBonuses;
        this.questEvents = questEvents;
    }

    /**
     * 一次战斗执行的结果。
     *
     * <p>挑战与扫荡共用这个形状 —— 验收 2 的「逐字段一致」指的就是这里的每一个字段。
     */
    public record Attempt(BattleResult result, long seed, boolean won, boolean noLoss,
                          boolean withinRounds, int starsEarned, Map<String, Long> lossesByUnitId,
                          Map<String, Long> deadByUnitId, Map<String, Long> woundedByUnitId) {

        public Attempt {
            lossesByUnitId = Map.copyOf(lossesByUnitId);
            deadByUnitId = Map.copyOf(deadByUnitId);
            woundedByUnitId = Map.copyOf(woundedByUnitId);
        }
    }

    // ---------- 列表 ----------

    /** 全部关卡的进度与解锁状态。 */
    public StageListResp list(String playerId) {
        requirePlayer(playerId);
        long now = timeService.serverNow();
        StageProgress progress = loadProgress(playerId);
        PlayerSave save = players.findByPlayerId(playerId)
                .orElseThrow(() -> new BizException(ErrorCode.PLAYER_NOT_FOUND, "存档不存在：" + playerId));
        List<StageEntry> entries = new ArrayList<>();
        for (StageCfg stage : configs.all(StageCfg.class)) {
            ChapterCfg chapter = requireChapter(stage.chapterId());
            String lockedReason = lockedReason(stage, chapter, progress, save.cityLevel());
            StageProgress.Record record = progress.of(stage.id());
            entries.add(new StageEntry(stage.id(), stage.chapterId(), stage.stageNo(), stage.name(),
                    stage.staminaCost(), stage.roundLimit(),
                    UnitRestriction.valueOf(stage.unitRestriction().name()),
                    BossMechanic.valueOf(stage.bossMechanic().name()),
                    lockedReason == null, lockedReason, record.cleared(), view(stage.id(), record)));
        }
        long stamina = wallet.available(playerId, StaminaService.RESOURCE_ID, now);
        return new StageListResp(entries, stamina, now);
    }

    // ---------- 挑战 ----------

    /** 挑战一关（B09 §三）。 */
    public ChallengeStageResp challenge(String playerId, ChallengeStageReq req) {
        requirePlayer(playerId);
        if (req == null || req.requestId() == null || req.requestId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "requestId 不得为空");
        }
        if (req.stageId() == null || req.stageId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "stageId 不得为空");
        }
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS,
                    () -> doChallenge(playerId, req, now));
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    private ChallengeStageResp doChallenge(String playerId, ChallengeStageReq req, long now) {
        StageCfg stage = requireStage(req.stageId());
        StageProgress progress = loadProgress(playerId);
        requireUnlocked(stage, progress, playerId);
        Map<String, Long> units = requestedUnits(req.units());
        requireOwned(playerId, units);
        requireComplies(stage, units);
        requireStamina(playerId, stage, now);

        Attempt attempt = execute(stage, playerId, units, req.heroes(), seeds.nextSeed(), now);
        // 失败不扣体力（B09 验收 1）：失败已经扣了兵，再扣体力是对正在学这个系统的人收两次学费
        long charged = attempt.won()
                ? wallet.deduct(playerId, StaminaService.RESOURCE_ID, stage.staminaCost(), now)
                : 0L;
        applyLosses(playerId, attempt, now);

        boolean clearedBefore = progress.cleared(stage.id());
        int earned = progress.recordResult(stage.id(), attempt.won(), attempt.noLoss(),
                attempt.withinRounds(), attempt.result().totalRounds(), now);
        StageProgress.Record record = progress.of(stage.id());
        // 章节宝箱只在「本关首次通关」时可能触发，扫荡永远不触发（见 grantStageRewards）
        boolean firstClear = attempt.won() && !clearedBefore;
        boolean newBest = attempt.won() && record.stars() == earned && earned > 0;
        saveProgress(playerId, progress);

        String reportId = battleReports.record(playerId, playerId, stage.id(), stage.name(), null,
                BattleType.PVE, req.heroes() == null ? List.of() : req.heroes(), List.of(),
                attempt.result(), now).reportId();

        List<RewardItem> rewards = attempt.won()
                ? grantStageRewards(playerId, stage, progress, firstClear) : List.of();
        if (attempt.won()) {
            // 通关算一次（B12 §1 的 CLEAR_STAGE）。**重复挑战也记**：周常问的是"这周打了几关"，
            // 而重复挑战同样是花体力打的一场；目标不细分时由事件带 targetId 交给任务表去匹配
            questEvents.progress(playerId, com.ironoath.core.quest.GoalType.CLEAR_STAGE,
                    stage.id(), 1L, now);
            if (firstClear && chapterCleared(requireChapter(stage.chapterId()), progress)) {
                // 章节只在"首次凑齐"那一刻记一次：它问的是"通关了几个章节"，不是"几次凑齐"
                questEvents.progress(playerId, com.ironoath.core.quest.GoalType.CLEAR_CHAPTER,
                        stage.chapterId(), 1L, now);
            }
        }
        LOG.info("关卡挑战 playerId={} stage={} seed={} 结果={} 回合={} 星={} 损失={} 体力={}/{}",
                playerId, stage.id(), attempt.seed(), attempt.result().winner(),
                attempt.result().totalRounds(), earned, attempt.lossesByUnitId(),
                charged, stage.staminaCost());
        return new ChallengeStageResp(reportId,
                stars(attempt.won(), attempt.noLoss(), attempt.withinRounds()),
                earned, newBest, toRewardViews(rewards),
                toStageUnits(attempt.lossesByUnitId()), stage.staminaCost(), charged,
                view(stage.id(), progress.of(stage.id())), now);
    }

    // ---------- 扫荡 ----------

    /** 扫荡（B09 §6）。必须已三星，最多 10 次，只发 1 次请求（验收 9）。 */
    public SweepResp sweep(String playerId, SweepReq req) {
        requirePlayer(playerId);
        if (req == null || req.requestId() == null || req.requestId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "requestId 不得为空");
        }
        if (req.stageId() == null || req.stageId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "stageId 不得为空");
        }
        if (req.count() < 1 || req.count() > 10) {
            // 超过 10 直接拒绝而不是截断：截断会让玩家以为扫了 10 次却只拿到 3 次的奖励
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "扫荡次数必须在 1~10 之间，实际=" + req.count());
        }
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> doSweep(playerId, req, now));
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    private SweepResp doSweep(String playerId, SweepReq req, long now) {
        StageCfg stage = requireStage(req.stageId());
        StageProgress progress = loadProgress(playerId);
        if (progress.stars(stage.id()) < 3) {
            throw new BizException(ErrorCode.STAGE_NOT_SWEEPABLE,
                    "只有三星通关的关卡才能扫荡，当前 " + progress.stars(stage.id()) + " 星。"
                            + "三星条件是通关、无损、" + stage.roundLimit() + " 回合内取胜");
        }

        List<SweepResult> results = new ArrayList<>(req.count());
        Map<String, Long> totalRewards = new LinkedHashMap<>();
        long staminaCost = 0L;
        long staminaCharged = 0L;
        int executed = 0;
        for (int i = 0; i < req.count(); i++) {
            // 体力或兵力不够时提前停下，把已经扫的几次照实结算：
            // 整批失败会让玩家损失已扣的体力，而「扫荡了 7 次」是他能理解的结果
            if (wallet.available(playerId, StaminaService.RESOURCE_ID, now) < stage.staminaCost()) {
                LOG.info("扫荡因体力不足提前结束 playerId={} stage={} 已执行={}/{}",
                        playerId, stage.id(), executed, req.count());
                break;
            }
            Map<String, Long> units = sweepArmy(playerId, stage);
            if (units.isEmpty()) {
                LOG.info("扫荡因兵力不足提前结束 playerId={} stage={} 已执行={}/{}",
                        playerId, stage.id(), executed, req.count());
                break;
            }
            Attempt attempt = execute(stage, playerId, units, List.of(), seeds.nextSeed(), now);
            staminaCharged += wallet.deduct(playerId, StaminaService.RESOURCE_ID,
                    stage.staminaCost(), now);
            staminaCost += stage.staminaCost();
            applyLosses(playerId, attempt, now);
            progress.recordResult(stage.id(), attempt.won(), attempt.noLoss(),
                    attempt.withinRounds(), attempt.result().totalRounds(), now);
            String reportId = battleReports.record(playerId, playerId, stage.id(), stage.name(), null,
                    BattleType.PVE, List.of(), List.of(), attempt.result(), now).reportId();
            List<RewardItem> rewards = attempt.won()
                    ? grantStageRewards(playerId, stage, progress, false) : List.of();
            for (RewardItem item : rewards) {
                totalRewards.merge(item.type() + "|" + item.id(), item.count(), Long::sum);
            }
            results.add(new SweepResult(reportId,
                    stars(attempt.won(), attempt.noLoss(), attempt.withinRounds()),
                    toRewardViews(rewards)));
            executed++;
        }
        progress.addSweeps(stage.id(), executed);
        saveProgress(playerId, progress);
        LOG.info("关卡扫荡 playerId={} stage={} 请求={} 实际执行={} 体力={}/{}",
                playerId, stage.id(), req.count(), executed, staminaCharged, staminaCost);
        return new SweepResp(results, aggregated(totalRewards), staminaCost, staminaCharged,
                executed, view(stage.id(), progress.of(stage.id())), now);
    }

    // ---------- 战斗执行（挑战与扫荡共用） ----------

    /**
     * 执行一次关卡战斗。<b>这是全类唯一的 simulate 调用点</b> —— 验收 2 靠这一点成立。
     *
     * @param seed 随机种子，由调用方给。挑战与扫荡都从服务端不可预测的种子源取，
     *             测试则可以传同一个 seed 来验证「两条路径逐字段一致」
     */
    public Attempt execute(StageCfg stage, String playerId, Map<String, Long> units,
                           List<String> heroIds, long seed, long now) {
        HeroRoster roster = heroes.findByPlayerId(playerId).orElseGet(HeroRoster::new);
        CityState city = cities.findByPlayerId(playerId).orElse(null);
        long hospitalCapacity = city == null ? 0L : armyAppService.hospitalCapacity(playerId, city);
        BattleArmyFactory.Folded attackerFold = armyFactory.fold(units);
        var attacker = armyFactory.toSide(playerId, attackerFold,
                heroMapper.snapshots(playerId, heroIds, roster), 0L,
                techBonuses.forPlayer(playerId), hospitalCapacity);
        var defender = armyFactory.toSide(stage.id(), armyFactory.fold(enemyArmy(stage)),
                List.of(), 0L, com.ironoath.battle.TechBonus.none(), 0L);

        BattleResult result = BattleSimulator.simulate(new BattleInput(
                attacker, defender, TerrainType.PLAIN, seed, BattleType.PVE,
                BattleModifier.none(), BattleModifier.none(),
                attackerFold.stats(), rulesAssembler.rules(), null,
                rulesAssembler.bossMechanic(stage.bossMechanic())));

        boolean won = result.winner() == Winner.ATTACKER;
        // 「无损」= 己方阵亡为 0，伤兵允许（伤兵可以治疗）。
        // 刻意不取「阵亡与伤兵都为 0」：这个内核里攻方损失约等于「敌方总兵力 / LANCHESTER_K」，
        // 与自己带多少兵无关（减员系数 = 1/(1+K×兵力比)，乘以己方兵力后收敛到敌方兵力/K），
        // 所以「一个都没少」在任何关卡都数学上不可达 —— 一颗永远拿不到的星比一颗定义稍宽的星更糟，
        // 玩家会理解成数值造假。门槛依然真实存在：敌方兵力越多，阵亡就越难压到 0
        boolean noLoss = result.atkDead() == 0L;
        boolean withinRounds = result.totalRounds() <= stage.roundLimit();

        Map<String, Long> losses = lossesByUnitId(units, attackerFold.counts(), result);
        Map<String, Long> dead = TierSplit.splitProportionally(losses, result.atkDead());
        Map<String, Long> wounded = new LinkedHashMap<>();
        losses.forEach((unitId, loss) -> {
            long left = loss - dead.getOrDefault(unitId, 0L);
            if (left > 0L) {
                wounded.put(unitId, left);
            }
        });
        int earned = (won ? 1 : 0) + (won && noLoss ? 1 : 0) + (won && withinRounds ? 1 : 0);
        return new Attempt(result, seed, won, noLoss, withinRounds, earned, losses, dead, wounded);
    }

    // ---------- 内部 ----------

    /** 把战损落到军队上：幸存者留在队伍里（关卡不走行军，所以直接扣），伤兵进医院。 */
    private void applyLosses(String playerId, Attempt attempt, long now) {
        if (attempt.lossesByUnitId().isEmpty()) {
            return;
        }
        ArmyState army = armies.findByPlayerId(playerId).orElseGet(ArmyState::new);
        long version = armies.versionOf(playerId);
        attempt.lossesByUnitId().forEach((unitId, count) -> army.deduct(unitId, count));
        if (!attempt.woundedByUnitId().isEmpty()) {
            CityState city = cities.findByPlayerId(playerId).orElse(null);
            long capacity = city == null ? 0L : armyAppService.hospitalCapacity(playerId, city);
            long overflow = army.admitWounded(attempt.woundedByUnitId(), capacity);
            if (overflow > 0L) {
                LOG.info("医院装不下，伤兵转为死亡 playerId={} 溢出={} 容量={}", playerId, overflow, capacity);
            }
        }
        armies.save(playerId, army, version);
    }

    /**
     * 逐关奖励 + 打通全章时的章节宝箱（chapter 表的三项奖励）。
     *
     * @param chapterChest 是否发放章节宝箱。<b>由调用方决定而不是在这里判断 chapterCleared</b>：
     *                     宝箱只能发一次（本关首次通关且刚好补齐全章时），
     *                     否则扫荡十次就会重发十遍全章奖励 —— 那是一个可以直接刷的漏洞，
     *                     而且扫荡走的就是这个方法，所以这个判断必须在调用方按「是否首次通关」做
     */
    private List<RewardItem> grantStageRewards(String playerId, StageCfg stage,
                                               StageProgress progress, boolean chapterChest) {
        List<RewardItem> items = new ArrayList<>();
        if (stage.rewardGold() > 0L) {
            items.add(new RewardItem(RewardType.RESOURCE, ResourceIds.GOLD, stage.rewardGold()));
        }
        ChapterCfg chapter = requireChapter(stage.chapterId());
        if (chapterChest && chapterCleared(chapter, progress)) {
            if (chapter.rewardGold() > 0L) {
                items.add(new RewardItem(RewardType.RESOURCE, ResourceIds.GOLD, chapter.rewardGold()));
            }
            if (chapter.rewardStamina() > 0L) {
                items.add(new RewardItem(RewardType.STAMINA, StaminaService.RESOURCE_ID,
                        chapter.rewardStamina()));
            }
            // 装备获取来源 (b)：通章给一件（规则见 StageEquipDrop，2026-09-18 裁决）。
            // **并入同一份 items**：这样它与章节宝箱一起走 rewardService.grant，也一起出现在给客户端的
            // rewards 视图里 —— 在 grant 之后再往视图里补是发不出去的（那份列表只是回执）。
            com.ironoath.config.cfg.EquipCfg drop = StageEquipDrop.pick(chapter.chapterNo(),
                    configs.all(com.ironoath.config.cfg.EquipCfg.class),
                    configs.longParam("STAGE_EQUIP_SR_FROM_CHAPTER"));
            if (drop == null) {
                // 配置缺行的症状要说得清：这一章没发装备，而不是发一件不属于这个档位的
                LOG.warn("通章奖励没有可选装备：equip 表里缺该稀有度的行 chapter={} 阈值={}",
                        chapter.chapterNo(), configs.longParam("STAGE_EQUIP_SR_FROM_CHAPTER"));
            } else {
                items.add(new RewardItem(RewardType.ITEM, drop.id(), 1L));
            }
        }
        if (items.isEmpty()) {
            return List.of();
        }
        rewardService.grant(playerId, items, RewardContext.toMail("stage", stage.id(), stage.id()));
        return items;
    }

    private boolean chapterCleared(ChapterCfg chapter, StageProgress progress) {
        for (long stageNo = 1; stageNo <= chapter.stageCount(); stageNo++) {
            if (!progress.cleared("stage_%02d_%02d".formatted(chapter.chapterNo(), stageNo))) {
                return false;
            }
        }
        return true;
    }

    /** 扫荡用的出战兵力：把玩家现有的兵全部带上（三星关卡本就是压倒性胜利）。 */
    private Map<String, Long> sweepArmy(String playerId, StageCfg stage) {
        ArmyState army = armies.findByPlayerId(playerId).orElseGet(ArmyState::new);
        Map<String, Long> units = new LinkedHashMap<>();
        army.troops().forEach((unitId, count) -> {
            if (count > 0L && complies(stage, unitId)) {
                units.put(unitId, count);
            }
        });
        return units;
    }

    private Map<String, Long> requestedUnits(List<StageUnit> units) {
        if (units == null || units.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "必须指定出战兵力");
        }
        Map<String, Long> out = new LinkedHashMap<>();
        for (StageUnit unit : units) {
            if (unit.count() <= 0L) {
                continue;
            }
            requireUnit(unit.unitId());
            out.merge(unit.unitId(), unit.count(), Long::sum);
        }
        if (out.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "出战兵力的数量必须为正");
        }
        return out;
    }

    /** 校验玩家真的拥有这些兵：客户端可以随便填数量，服务端必须自己核对（铁律 3）。 */
    private void requireOwned(String playerId, Map<String, Long> units) {
        ArmyState army = armies.findByPlayerId(playerId).orElseGet(ArmyState::new);
        units.forEach((unitId, count) -> {
            long have = army.countOf(unitId);
            if (have < count) {
                throw new BizException(ErrorCode.UNIT_NOT_ENOUGH,
                        "兵种 " + unitId + " 需要 " + count + " 个，当前只有 " + have + " 个");
            }
        });
    }

    private void requireUnlocked(StageCfg stage, StageProgress progress, String playerId) {
        String reason = lockedReason(stage, requireChapter(stage.chapterId()), progress,
                players.findByPlayerId(playerId).map(PlayerSave::cityLevel).orElse(1));
        if (reason != null) {
            throw new BizException(ErrorCode.STAGE_LOCKED, reason);
        }
    }

    /** 未解锁的原因；已解锁返回 null。 */
    private String lockedReason(StageCfg stage, ChapterCfg chapter, StageProgress progress,
                                int cityLevel) {
        if (cityLevel < chapter.requireMainLevel()) {
            return "需要主城 " + chapter.requireMainLevel() + " 级（当前 " + cityLevel + " 级）";
        }
        String previous = previousStageId(stage);
        if (previous != null && !progress.cleared(previous)) {
            return "需要先通关 " + previous;
        }
        return null;
    }

    /** 线性推图：本关的前一关必须已通关；每章第 1 关则要求上一章已打通。 */
    private String previousStageId(StageCfg stage) {
        if (stage.stageNo() > 1L) {
            return "stage_%02d_%02d".formatted(chapterNoOf(stage), stage.stageNo() - 1);
        }
        ChapterCfg chapter = requireChapter(stage.chapterId());
        if (chapter.preChapter() == null || chapter.preChapter().isBlank()) {
            return null;
        }
        ChapterCfg pre = requireChapter(chapter.preChapter());
        return "stage_%02d_%02d".formatted(pre.chapterNo(), pre.stageCount());
    }

    private long chapterNoOf(StageCfg stage) {
        return requireChapter(stage.chapterId()).chapterNo();
    }


    private void requireComplies(StageCfg stage, Map<String, Long> units) {
        for (String unitId : units.keySet()) {
            if (!complies(stage, unitId)) {
                throw new BizException(ErrorCode.STAGE_UNIT_RESTRICTED,
                        "本关限制 " + describe(stage.unitRestriction()) + "，"
                                + unitId + "（" + requireUnit(unitId).type() + "）不能出战");
            }
        }
    }

    private boolean complies(StageCfg stage, String unitId) {
        UnitCfg.Type type = requireUnit(unitId).type();
        return switch (stage.unitRestriction()) {
            case NONE -> true;
            case NO_SIEGE -> type != UnitCfg.Type.SIEGE;
            case CAVALRY_ONLY -> type == UnitCfg.Type.CAVALRY;
            case RANGED_ONLY -> type == UnitCfg.Type.ARCHER || type == UnitCfg.Type.SIEGE;
        };
    }

    private static String describe(StageCfg.UnitRestriction restriction) {
        return switch (restriction) {
            case NONE -> "无";
            case NO_SIEGE -> "不得带攻城器";
            case CAVALRY_ONLY -> "只能带骑兵";
            case RANGED_ONLY -> "只能带远程（弓兵/攻城器）";
        };
    }

    private void requireStamina(String playerId, StageCfg stage, long now) {
        long available = wallet.available(playerId, StaminaService.RESOURCE_ID, now);
        if (available < stage.staminaCost()) {
            throw new BizException(ErrorCode.RESOURCE_NOT_ENOUGH,
                    "体力不足：本关需要 " + stage.staminaCost() + " 点，当前 " + available + " 点");
        }
    }

    private Map<String, Long> enemyArmy(StageCfg stage) {
        Map<String, Long> units = new LinkedHashMap<>();
        putEnemy(units, "INFANTRY", stage.enemyTier(), stage.enemyInfantry());
        putEnemy(units, "CAVALRY", stage.enemyTier(), stage.enemyCavalry());
        putEnemy(units, "ARCHER", stage.enemyTier(), stage.enemyArcher());
        putEnemy(units, "SIEGE", stage.enemyTier(), stage.enemySiege());
        if (units.isEmpty()) {
            throw new BizException(ErrorCode.CONFIG_INVALID,
                    "关卡 " + stage.id() + " 的四个兵种数量全是 0，无法构成防守军队");
        }
        return units;
    }

    private void putEnemy(Map<String, Long> units, String typeName, long tier, long count) {
        if (count > 0L) {
            units.put("unit_" + typeName.toLowerCase(java.util.Locale.ROOT) + "_t" + tier, count);
        }
    }

    /** per-UnitType 的战果摊回 per-unitId 的损失（与野怪讨伐同一条口径）。 */
    private Map<String, Long> lossesByUnitId(Map<String, Long> sentUnits,
                                             Map<com.ironoath.battle.UnitType, Long> foldedCounts,
                                             BattleResult result) {
        Map<String, Long> losses = new LinkedHashMap<>();
        for (com.ironoath.battle.UnitType type : com.ironoath.battle.UnitType.values()) {
            long before = foldedCounts.getOrDefault(type, 0L);
            long after = result.atkSurvivors() == null ? 0L
                    : result.atkSurvivors().getOrDefault(type, 0L);
            long typeLoss = before - after;
            if (typeLoss <= 0L) {
                continue;
            }
            Map<String, Long> ofType = new LinkedHashMap<>();
            sentUnits.forEach((unitId, count) -> {
                if (count > 0L && requireUnit(unitId).type().name().equals(type.name())) {
                    ofType.put(unitId, count);
                }
            });
            losses.putAll(TierSplit.splitProportionally(ofType, typeLoss));
        }
        return losses;
    }

    private StageProgress loadProgress(String playerId) {
        return progressRepo.findByPlayerId(playerId).orElseGet(() -> {
            progressRepo.insertIfAbsent(playerId, new StageProgress());
            return progressRepo.findByPlayerId(playerId).orElseThrow();
        });
    }

    private void saveProgress(String playerId, StageProgress progress) {
        progressRepo.save(playerId, progress, progress.version());
    }

    private void acquire(String requestId, long now) {
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + requestId);
        }
    }

    private StageCfg requireStage(String stageId) {
        try {
            return configs.get(StageCfg.class, stageId);
        } catch (ConfigException e) {
            throw new BizException(ErrorCode.CONFIG_NOT_FOUND, "关卡不存在: " + stageId);
        }
    }

    private ChapterCfg requireChapter(String chapterId) {
        try {
            return configs.get(ChapterCfg.class, chapterId);
        } catch (ConfigException e) {
            throw new BizException(ErrorCode.CONFIG_NOT_FOUND, "章节不存在: " + chapterId);
        }
    }

    private UnitCfg requireUnit(String unitId) {
        try {
            return configs.get(UnitCfg.class, unitId);
        } catch (ConfigException e) {
            throw new BizException(ErrorCode.CONFIG_NOT_FOUND, "兵种配置不存在: " + unitId);
        }
    }

    private static StageStars stars(boolean won, boolean noLoss, boolean withinRounds) {
        boolean cleared = won;
        int total = (cleared ? 1 : 0) + (cleared && noLoss ? 1 : 0) + (cleared && withinRounds ? 1 : 0);
        return new StageStars(cleared, cleared && noLoss, cleared && withinRounds, total);
    }

    private static com.ironoath.web.dto.generated.StageProgressView view(
            String stageId, StageProgress.Record record) {
        return new com.ironoath.web.dto.generated.StageProgressView(stageId, record.stars(),
                record.bestRounds(), record.clearedAt(), record.sweepCount());
    }

    private static List<StageUnit> toStageUnits(Map<String, Long> units) {
        List<StageUnit> out = new ArrayList<>(units.size());
        units.forEach((unitId, count) -> out.add(new StageUnit(unitId, count)));
        return out;
    }

    private List<StageReward> toRewardViews(List<RewardItem> items) {
        List<StageReward> out = new ArrayList<>(items.size());
        for (RewardItem item : items) {
            out.add(new StageReward(item.type().name(), item.id(), item.count(), displayName(item)));
        }
        return out;
    }

    private List<StageReward> aggregated(Map<String, Long> totals) {
        List<StageReward> out = new ArrayList<>(totals.size());
        totals.forEach((key, count) -> {
            int sep = key.indexOf('|');
            String id = key.substring(sep + 1);
            out.add(new StageReward(key.substring(0, sep), id, count,
                    displayName(new RewardItem(RewardType.RESOURCE, id, count))));
        });
        return out;
    }

    /**
     * 奖励的中文显示名。
     *
     * <p>由服务端下发而不是客户端翻译：飘字、战报、客服工单里的称呼必须是同一个，
     * 客户端自己翻就会出现「服务端日志写 GOLD、玩家截图里写金币、客服看不懂」的情况。
     * 资源类查 resource 表；其它类型（武将碎片等）暂时回落到 id，
     * 等对应的表落地后再补 —— 回落而不是抛异常，因为奖励已经发出去了，
     * 不能因为一个显示名让整个响应失败。
     */
    private String displayName(RewardItem item) {
        try {
            return configs.getResource(item.id()).name();
        } catch (RuntimeException unknownResource) {
            return item.id();
        }
    }

    private static void requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
    }
}
