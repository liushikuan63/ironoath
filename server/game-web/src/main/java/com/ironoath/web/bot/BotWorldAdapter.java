package com.ironoath.web.bot;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ironoath.common.BizException;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.MapmonsterCfg;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.bot.BotDecisionTree;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.bot.BotPveTargets;
import com.ironoath.core.bot.BotScheduler;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.common.rng.Rng;
import com.ironoath.core.bot.BotChatBook;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.web.dto.generated.AllianceDonateReq;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.ChatChannel;
import com.ironoath.web.dto.generated.ChatSendReq;
import com.ironoath.web.dto.generated.CityUpgradeReq;
import com.ironoath.web.dto.generated.ExileReq;
import com.ironoath.web.dto.generated.MarchAction;
import com.ironoath.web.dto.generated.MarchReq;
import com.ironoath.web.dto.generated.MarchUnit;
import com.ironoath.web.dto.generated.RallyJoinReq;
import com.ironoath.web.dto.generated.RallyTroop;
import com.ironoath.web.dto.generated.SearchTargetsReq;
import com.ironoath.web.dto.generated.SearchTargetsResp;
import com.ironoath.web.dto.generated.TrainReq;
import com.ironoath.web.dto.generated.WorldEntity;
import com.ironoath.web.dto.generated.WorldEntityType;
import com.ironoath.web.service.ArmyAppService;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.ExileAppService;
import com.ironoath.web.service.HeroAppService;
import com.ironoath.web.service.MarchAppService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.service.TargetSearchService;
import com.ironoath.web.service.WorldAppService;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：{@code BotScheduler.World} 的唯一生产实现 —— 把 Bot 的一次 tick 变成<b>与真人一模一样的
 * 几次 service 调用</b>（B11 头号铁律：不许有第二条写库路径）。
 * 依赖：城建 / 军队两个应用服务（不是仓储！）、真人均值口径的<b>同一个来源</b>（{@link BotSpawnService}）、
 *      以及只读的世界与社交状态。
 *
 * <p><b>本类不实现「够不够」</b>：每一个喂给决策树的布尔都是问某个已经有生产调用点的读法拿到的
 * （{@code cityTickFacts} / {@code firstTrainable} / {@code troopCap} / {@code Wallet.available}…）。
 * 在这里比一次资源与等级，就是给「哪套口径算数」留第二个答案 —— 那是本仓库反复在防的形状
 * （红点亮着但点不了、Bot 决定要升但每次都被拒，两边各有日志）。
 *
 * <p><b>本类不抛异常</b>：{@code BotScheduler.handle} 一旦让异常逃出去，那个 Bot 就再也排不进
 * 下一次决策（重排代码在成功路径之后），表现是"地图上的某个 Bot 从某天起永远不动了"。
 * 所以每次 execute 自己兜住，并计入 {@link #failedCount()} / {@link #unhandledCount()}。
 *
 * <p><b>已落地的动作（全 13 个）</b>：升级建筑、训练兵种（C1）、受击反应（C2）、
 * 掠袭 / 聊天 / 社交 / 入盟（C3）、打野 / 采集（C1b 的 PvE 那半，收口清单 #96）、迁城与 IDLE。
 * {@code unhandled} 从"给没接的动作计数"退化成一条<b>兜底告警</b> ——
 * 决策树以后新增枚举而这里忘了接时，它仍然会响。
 */
@Component
public class BotWorldAdapter implements BotScheduler.World {

    private static final Logger LOG = LoggerFactory.getLogger(BotWorldAdapter.class);

    private final BotRegistry bots;
    private final PlayerRepository players;
    private final CityAppService cities;
    private final ArmyAppService army;
    private final HeroAppService heroes;
    private final ArmyRepository armies;
    private final SocialStore social;
    private final MarchRepository marches;
    private final BotSpawnService spawner;
    private final RewardPorts.Wallet wallet;
    private final ConfigRegistry configs;
    /** 反击走它（转调与真人完全相同的 service，护盾/圈层/外交/频控全都自动生效）。 */
    private final MarchAppService marches2;
    /** 迁城走它（免费随机落点 + 落地免战 + 冷却，与真人那条路一字不差）。 */
    private final ExileAppService exiles;
    /** 社交动作（捐献 / 一键帮助 / 响应集结 / 申请入盟 / 发言）—— 与真人同一个 service。 */
    private final SocialAppService social2;
    /** 掠袭的目标从它来（圈层、活跃窗口、护盾、免战一并继承真人口径，不自己挑）。 */
    private final TargetSearchService targetSearch;
    /** 句库从它拿（{@code chatBook()} 每次现装配，与其它装配器同一条「不缓存」纪律）。 */
    private final BotRulesAssembler assembler;
    /** 找攻击者的城在哪 —— 反击要一个坐标，而坐标只能从世界仓储读。 */
    private final com.ironoath.core.world.WorldRepository world;
    /**
     * 世界读服务：打野/采集要「像玩家一样先看见再打」，而「看得见什么」的口径唯一持有者是它
     * （{@link WorldAppService#visibleEntitiesOf}，与 viewport 共用同一份实体装配）。
     */
    private final WorldAppService worldService;

    /** 成功落地的动作数（按动作类型分别记，供 {@code /bot/tick} 与仿真断言用）。 */
    private final AtomicLong upgraded = new AtomicLong();
    private final AtomicLong trained = new AtomicLong();
    /**
     * <b>异常</b>次数（读世界/执行抛出来）。
     *
     * <p>契约（{@code bot.schema.json}）对它的定义就是"读世界状态或执行动作抛出来" ——
     * 所以"决策给了一个做不了的动作"（失误掷中了训练、而此刻没有可训兵种）<b>不算在这里</b>：
     * 那是失误率按设计在起作用，把它记成失败会把真正的异常淹掉。
     */
    private final AtomicLong failed = new AtomicLong();
    /**
     * <b>体面跳过</b>的次数：决策与执行之间状态变了（或决策本身来自失误掷骰），动作没做成但什么都没坏。
     *
     * <p>与 {@code failed} 分开是 2026-09-12（收口清单 #94）修的一处口径不一致：
     * C1 把这几条也记进了 failed，而契约里 failed 写的是"抛出来"。分开之后
     * 「Bot 在空转」看 skipped、「Bot 在报错」看 failed —— 两件事的下一步动作完全不同。
     */
    private final AtomicLong skipped = new AtomicLong();
    /** 决策树给出了、但 {@link #execute} 里没有对应分支的动作数（新增枚举忘了接时的兜底告警）。 */
    private final AtomicLong unhandled = new AtomicLong();
    /** 已完成一次受击反应的次数（不论最后选的是反击、迁城还是龟缩）。 */
    private final AtomicLong reacted = new AtomicLong();

    /** C3（#94）的六个动作计数：掠袭出门 / 联盟发言 / 申请入盟 / 捐献 / 一键帮助 / 响应集结。 */
    private final AtomicLong raided = new AtomicLong();
    private final AtomicLong chatted = new AtomicLong();
    private final AtomicLong soughtAlliance = new AtomicLong();
    private final AtomicLong donated = new AtomicLong();
    private final AtomicLong helped = new AtomicLong();
    private final AtomicLong rallied = new AtomicLong();
    /** C1b（#96）的两个动作计数：打野出征 / 采集出征。 */
    private final AtomicLong hunted = new AtomicLong();
    private final AtomicLong gathered = new AtomicLong();
    /** 已经为哪个动作打过 WARN —— 只提示一次，不然每一 tick 一条会淹掉日志。 */
    private final java.util.Set<BotDecisionTree.Action> warned =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 受击的「还来得及反应」窗口（行为常量，不是游戏数值）。
     *
     * <p>为什么要一个窗口：{@code attackerHits} 是 24 小时的账本（受害护盾用的），
     * 拿它当"正在被攻击"会让 Bot 在被揍之后的 24 小时里每一 tick 都反应一次 —— 那是 288 次。
     * 1 小时覆盖了最长 tick 间隔（15 分钟）加一段拟人延迟，够它在下一次决策里注意到。
     */
    private static final long ATTACK_NOTICE_MILLIS = 60L * 60L * 1000L;

    /**
     * 按袭击者凶猛程度分档（行为常量）。{@code aggression} 是表里的原型列：
     * 劫掠者 0.85 / 军阀 0.60 / 影子 0.45 / 邻居 0.30 / 盟友 0.20 / 陪跑者 0.05。
     * 分档只决定"先试哪一种反应"，不改任何游戏数值。
     */
    private static final long COUNTER_ATTACK_MIN_FIXED = 7000L;   // >= 0.70 先试着打回去
    private static final long RELOCATE_MIN_FIXED = 3500L;         // >= 0.35 先试着搬走

    /**
     * 已经回应到哪个时刻的攻击（botId → 该 Bot 回应过的最新受击时间戳）。
     *
     * <p><b>为什么必须在内存里记一笔</b>：受击账本里没有"回应过没有"这个位，
     * 而"每 tick 都回应同一发旧子弹"就是它缺位的症状。进程重启后这一笔会丢 ——
     * 与画像、待办队列同一笔债（#84 丙），代价是重启后的第一小时内可能多回一次，
     * 不构成玩法事故。
     */
    private final java.util.Map<String, Long> reactedUpTo = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 入盟申请的本地节流（botId → 上次尝试时刻）：行为常量，不是游戏数值。
     *
     * <p>为什么要它：申请被拒（人满 / 已申请过）之后，下一个 tick 还会再掷一次社交倾向 ——
     * 而 tick 是分钟级的，一天会给某个联盟的盟主刷几百条申请。**服务端已经有去重**
     * （重复申请会被 {@code ALLIANCE_APPLY_DUPLICATED} 拒掉），这里省掉的是"每天都去敲一次门"
     * 那类无意义调用与日志。6 小时一次 ≈ 一天最多敲四次门。
     */
    private static final long SEEK_ALLIANCE_INTERVAL_MILLIS = 6L * 3600L * 1000L;

    /**
     * 已经打过招呼的联盟（botId → allianceId）。
     *
     * <p>入盟问候只在"第一次以成员身份 tick"时说一次 —— 没有这个标记的话，
     * 那 2 句问候会按 tick 频率重复（分钟级），成了刷屏。
     */
    private final java.util.Map<String, String> greetedAlliance = new java.util.concurrent.ConcurrentHashMap<>();

    /** 入盟申请尝试的本地节流表。 */
    private final java.util.Map<String, Long> lastSeekAllianceAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** 最近一次发言用的场景（诊断与用例断言"问候只说一次"要用）。 */
    private final java.util.Map<String, String> lastChatScene = new java.util.concurrent.ConcurrentHashMap<>();

    /** 幂等键序号。Bot 没有客户端，requestId 必须由服务端造，且<b>全局唯一</b>（撞了就是 1002）。 */
    private final AtomicLong requestSeq = new AtomicLong();

    public BotWorldAdapter(BotRegistry bots, PlayerRepository players, CityAppService cities,
                           ArmyAppService army, HeroAppService heroes, ArmyRepository armies,
                           SocialStore social, MarchRepository marches, BotSpawnService spawner,
                           RewardPorts.Wallet wallet, ConfigRegistry configs,
                           MarchAppService marches2, ExileAppService exiles,
                           com.ironoath.core.world.WorldRepository world,
                           SocialAppService social2, TargetSearchService targetSearch,
                           BotRulesAssembler assembler, WorldAppService worldService) {
        this.bots = bots;
        this.players = players;
        this.cities = cities;
        this.army = army;
        this.heroes = heroes;
        this.armies = armies;
        this.social = social;
        this.marches = marches;
        this.spawner = spawner;
        this.wallet = wallet;
        this.configs = configs;
        this.marches2 = marches2;
        this.exiles = exiles;
        this.world = world;
        this.social2 = social2;
        this.targetSearch = targetSearch;
        this.assembler = assembler;
        this.worldService = worldService;
    }

    @Override
    public BotProfile profileOf(String botId) {
        return bots.profileOf(botId);
    }

    /**
     * 采集这个 Bot 此刻能看到的事实。<b>每个字段都有出处</b>，见类注释；这里只做一次
     * 「真人均值 vs 自己的匹配战力」的比较，因为那正是 {@code BotTuning} 之外唯一允许的比较
     * （它不产出数值，只回答"落后了吗"）。
     */
    @Override
    public BotDecisionTree.WorldState observe(String botId, long now) {
        PlayerSave save = players.findByPlayerId(botId).orElse(null);
        if (save == null) {
            // 画像还在、存档却没了：按"什么都不缺"回答会让决策树去升一个不存在的城。
            // 全部给 false，让它落到 IDLE 那一支（execute 里 IDLE 是明确的空动作）
            LOG.warn("Bot {} 有画像但没有存档，本次 tick 按无事可做处理", botId);
            return new BotDecisionTree.WorldState(false, false, false, false, false,
                    false, false, false, false, false, 0L);
        }
        CityAppService.CityTickFacts cityFacts = readCityQuietly(botId, now);
        ArmyState armyState = armies.findByPlayerId(botId).orElse(null);
        long troopCap = heroes.troopCap(botId);
        long troops = armyState == null ? 0L : armyState.totalTroops() + armyState.totalTraining();
        long average = spawner.humanAverageMatchPower();

        BotWorldAdapter.LatestHit lastHit = latestHit(save);
        Long answered = reactedUpTo.get(botId);
        // 「正在被攻击」= 最近一发还在窗口内、而且我还没回应过它。
        // 直接用 attackerHits 非空会让 Bot 在被揍后的 24 小时里每一 tick 都反应一次（账本是 24h 的）
        boolean underAttack = lastHit != null
                && now - lastHit.at() <= ATTACK_NOTICE_MILLIS
                && (answered == null || lastHit.at() > answered);
        return new BotDecisionTree.WorldState(
                underAttack,
                cityFacts.hasFreeBuildQueue(),
                cityFacts.canAffordBuilding(),
                // 「人口满」在 B05 的口径里就是带兵上限（troopCap 由武将统帅值决定），
                // 用 service 的那一份读法而不是自己乘一遍将领头数
                troops >= troopCap,
                army.firstTrainable(botId, now).isPresent(),
                wallet.available(botId, "STAMINA", now) > 0L,
                marches.activeCountOf(botId) < configs.longParam("MARCH_MAX_CONCURRENT"),
                social.allianceOf(botId).isPresent(),
                social.squadOf(botId).isPresent(),
                average > 0L && save.power().matchPower() < average,
                Math.max(0L, now - save.lastLoginAt()));
    }

    @Override
    public void execute(String botId, BotDecisionTree.Decision decision, long now) {
        try {
            switch (decision.action()) {
                case UPGRADE_BUILDING -> upgrade(botId, now, decision);
                case TRAIN_TROOPS -> train(botId, now, decision);
                case HUNT_MONSTER -> hunt(botId, now, decision);
                case GATHER_RESOURCE -> gather(botId, now, decision);
                case REACT_ATTACK -> react(botId, now, decision);
                case RAID -> raid(botId, now, decision);
                case SEND_CHAT -> chat(botId, now, decision);
                case SEEK_ALLIANCE -> seekAlliance(botId, now, decision);
                case ALLIANCE_DONATE -> donate(botId, decision);
                case ALLIANCE_HELP -> helpAllies(botId, now);
                case JOIN_RALLY -> joinRally(botId, now, decision);
                case RELOCATE_CITY -> {
                    // B11 §三 第 6 条：长时间无互动 → 按概率随机迁城（决策树已按 idle 与概率筛过）
                    if (!relocate(botId, now)) {
                        LOG.info("Bot {} 想迁城但没成行（冷却中或队伍在外），本次跳过", botId);
                    }
                }
                case IDLE -> {
                    // 无事可做是正常结局（决策树自己的第 7 条之外唯一"什么都不做"的分支）
                }
                default -> unhandled(botId, decision);
            }
        } catch (RuntimeException e) {
            // 兜住：一个 Bot 的一次失败不能让它退出调度，也不能带走整轮
            failed.incrementAndGet();
            LOG.warn("Bot {} 执行决策失败（决策={} 理由={}）：{}",
                    botId, decision.action(), decision.reason(), e.getMessage(), e);
        }
    }

    @Override
    public long matchPowerOf(String botId) {
        return players.findByPlayerId(botId).map(save -> save.power().matchPower()).orElse(0L);
    }

    @Override
    public long humanAverageMatchPower(long now) {
        // 口径的唯一持有者是孵化侧（那一份均值同时决定 Bot 的战力带），这里转问它，
        // 不在这里重算一遍 —— 两份均值迟早分叉，而分叉的表现是"校准跟着活数据走"变成假话
        return spawner.humanAverageMatchPower();
    }

    // ---------- C1：升级建筑与训练兵种 ----------

    private void upgrade(String botId, long now, BotDecisionTree.Decision decision) {
        CityAppService.CityTickFacts facts = readCityQuietly(botId, now);
        CityAppService.Upgradable target = facts.nextUpgradable();
        if (target == null) {
            // observe 说能升、现在不能升：中间有别的请求动了这个城（或者就是近似偏乐观）。
            // 什么都不做，但计一次 —— 「Bot 在空转」必须可见，否则没人知道它在原地打转
            skipped.incrementAndGet();
            LOG.info("Bot {} 决定升级但已无目标（决策理由={}）", botId, decision.reason());
            return;
        }
        // 已放置的建筑不需要坐标；首次建造要选格子，那是另一件事（类注释与 #91）
        String requestId = newRequestId(botId);
        cities.upgrade(botId, new CityUpgradeReq(requestId, target.configId(), null, null));
        upgraded.incrementAndGet();
        LOG.info("Bot {} 升级 {} → {} 级 幂等键={}", botId, target.configId(), target.targetLevel(), requestId);
    }

    private void train(String botId, long now, BotDecisionTree.Decision decision) {
        Optional<ArmyAppService.Trainable> trainable = army.firstTrainable(botId, now);
        if (trainable.isEmpty()) {
            // 失误掷骰会把动作选成训练，而此刻未必有可训兵种（没武将时 troopCap=0）——
            // 这是失误率按设计在起作用，不是故障
            skipped.incrementAndGet();
            LOG.info("Bot {} 决定训练但此刻没有可训兵种（决策理由={}）", botId, decision.reason());
            return;
        }
        ArmyAppService.Trainable target = trainable.get();
        army.train(botId, new TrainReq(newRequestId(botId), target.unitId(), target.count()));
        trained.incrementAndGet();
        LOG.info("Bot {} 训练 {} ×{}", botId, target.unitId(), target.count());
    }

    // ---------- C1b（#96）：打野 / 采集 ----------

    /**
     * 看得见的一只野怪：地图实体只带坐标与等级，mapmonster 行 id 与战力要回查格子与表。
     *
     * <p>实体列表与格子内容分两次读，所以逐只再确认一次 {@code cellAt}：
     * 两次读之间它可能已被别人打掉（那份实体就是过期快照），而拿一个已消耗的格子当目标是
     * 一次注定被拒的出征。
     */
    private java.util.List<BotPveTargets.Monster> visibleMonsters(String botId) {
        java.util.List<BotPveTargets.Monster> out = new java.util.ArrayList<>();
        for (WorldEntity entity : worldService.visibleEntitiesOf(botId)) {
            if (entity.type() != WorldEntityType.MONSTER) {
                continue;
            }
            Coord coord = Coord.of(entity.x(), entity.y());
            WorldGenerator.Cell cell = worldService.cellAt(coord);
            if (cell.entityType() != WorldGenerator.EntityType.MONSTER) {
                continue;
            }
            MapmonsterCfg monster = configs.get(MapmonsterCfg.class, cell.entityId());
            out.add(new BotPveTargets.Monster(coord, cell.entityId(), cell.level(), monster.power()));
        }
        return out;
    }

    /** 看得见的资源点（实体自带资源 id，不用回查格子）。 */
    private java.util.List<BotPveTargets.Resource> visibleResources(String botId) {
        java.util.List<BotPveTargets.Resource> out = new java.util.ArrayList<>();
        for (WorldEntity entity : worldService.visibleEntitiesOf(botId)) {
            if (entity.type() == WorldEntityType.RESOURCE && entity.resourceType() != null) {
                out.add(new BotPveTargets.Resource(Coord.of(entity.x(), entity.y()),
                        entity.resourceType()));
            }
        }
        return out;
    }

    /** 自己的城坐标（选目标的并列裁决与「看不见」的判断都要它）。没有城时返回 null。 */
    private Coord homeOrNull(String botId) {
        return world.cityOf(botId).orElse(null);
    }

    /**
     * 打野（§三 第 3 条）。目标只能从「看得见的野怪」里选，选择规则在 core：
     * {@link BotPveTargets#pickMonster} —— 打得过的最强怪（零新数值，口径见 #96）。
     *
     * <p><b>带全部可用兵力</b>，与反击/掠袭同口径（表里没有「打野带多少」这个数，记在收口清单等规格）。
     * 每日次数与体力由 {@code MonsterBattleService.precheck} 在出征时校验 ——
     * 被拒（今日打满 / 体力不够）是玩法常态：INFO 一条，<b>不算失败</b>。
     *
     * <p><b>看得见才打得到</b>：候选来自 {@link WorldAppService#visibleEntitiesOf}（视野内已探索块），
     * 与玩家客户端看到的同一份口径 —— Bot 不透视迷雾（B11 禁止项：不给 Bot 任何特权）。
     */
    private void hunt(String botId, long now, BotDecisionTree.Decision decision) {
        java.util.List<MarchUnit> units = allTroops(botId);
        if (units.isEmpty()) {
            LOG.info("Bot {} 想去打野但没有兵（决策理由={}）—— 新号没兵是常态", botId, decision.reason());
            return;
        }
        Coord home = homeOrNull(botId);
        if (home == null) {
            LOG.info("Bot {} 想去打野但还没有落位（没有城就没有视野）", botId);
            return;
        }
        java.util.List<BotPveTargets.Monster> visible = visibleMonsters(botId);
        long power = matchPowerOf(botId);
        Optional<BotPveTargets.Monster> pick = BotPveTargets.pickMonster(visible, power, home);
        if (pick.isEmpty()) {
            LOG.info("Bot {} 想去打野但打得过的怪一只都看不见（看得见 {} 只，自身战力 {}）",
                    botId, visible.size(), power);
            return;
        }
        BotPveTargets.Monster target = pick.get();
        try {
            marches2.send(botId, new MarchReq(newRequestId(botId),
                    target.coord().x(), target.coord().y(), units, java.util.List.of(),
                    MarchAction.ATTACK));
            hunted.incrementAndGet();
            LOG.info("Bot {} 打野 {}（Lv{}，战力 {}，带 {} 种兵）", botId, target.monsterId(),
                    target.level(), target.power(), units.size());
        } catch (RuntimeException e) {
            // 今日次数打满 / 体力不足 / 目标刚被别人打掉：都是玩法常态，INFO 一条就够
            LOG.info("Bot {} 打野未成行（目标={}）：{}", botId, target.monsterId(), e.getMessage());
        }
    }

    /**
     * 占领资源点采集（§三 第 3 条）。目标 = 看得见的最近资源点（{@link BotPveTargets#pickResource}）。
     *
     * <p>采集不消耗体力（体力是打野与关卡的闸），但<b>闭城死守期间会被拒</b>（B08 §5）——
     * 那条限制由 {@code MarchAppService} 在出征时执行，这里不重判第二遍（第二份判定迟早分叉）。
     */
    private void gather(String botId, long now, BotDecisionTree.Decision decision) {
        java.util.List<MarchUnit> units = allTroops(botId);
        if (units.isEmpty()) {
            LOG.info("Bot {} 想去采集但没有兵（决策理由={}）—— 新号没兵是常态", botId, decision.reason());
            return;
        }
        Coord home = homeOrNull(botId);
        if (home == null) {
            LOG.info("Bot {} 想去采集但还没有落位（没有城就没有视野）", botId);
            return;
        }
        java.util.List<BotPveTargets.Resource> visible = visibleResources(botId);
        Optional<BotPveTargets.Resource> pick = BotPveTargets.pickResource(visible, home);
        if (pick.isEmpty()) {
            LOG.info("Bot {} 想去采集但一个资源点都看不见（看得见 {} 个）", botId, visible.size());
            return;
        }
        BotPveTargets.Resource target = pick.get();
        try {
            marches2.send(botId, new MarchReq(newRequestId(botId),
                    target.coord().x(), target.coord().y(), units, java.util.List.of(),
                    MarchAction.GATHER));
            gathered.incrementAndGet();
            LOG.info("Bot {} 采集 {}（坐标 {}，带 {} 种兵）", botId, target.resourceId(),
                    target.coord(), units.size());
        } catch (RuntimeException e) {
            // 闭城死守 / 目标刚被采空 / 队列满：都是玩法常态，INFO 一条就够
            LOG.info("Bot {} 采集未成行（目标={}）：{}", botId, target.resourceId(), e.getMessage());
        }
    }

    // ---------- 受击反应（B11 §三 第 4 条） ----------

    /** 受击账本里最近的一发。 */
    private record LatestHit(String attackerId, long at) {
    }

    private LatestHit latestHit(PlayerSave save) {
        String attacker = null;
        long latest = 0L;
        for (Map.Entry<String, Long> hit : save.pvp().attackerHits().entrySet()) {
            if (hit.getValue() != null && hit.getValue() > latest) {
                latest = hit.getValue();
                attacker = hit.getKey();
            }
        }
        return attacker == null ? null : new LatestHit(attacker, latest);
    }

    /**
     * 一次受击反应。分档只决定<b>先试哪一种</b>，试不通就龟缩 —— <b>不叠动作</b>。
     *
     * <p><b>求援不在此列</b>：游戏里没有"求援"这个机制（互助请求是给加速用的，与战斗无关），
     * 造一个出来就是发明系统。它记在收口清单里等规格。
     *
     * <p><b>先记「已回应」再动手</b>：反应本身可能失败（没兵、迁城冷却中），
     * 但不记的话下一个 tick 会对着同一发旧子弹再选一次 —— 那正是计数器要显形的空转。
     */
    private void react(String botId, long now, BotDecisionTree.Decision decision) {
        PlayerSave save = players.findByPlayerId(botId).orElse(null);
        BotProfile profile = bots.profileOf(botId);
        LatestHit hit = save == null ? null : latestHit(save);
        if (hit == null || profile == null) {
            skipped.incrementAndGet();
            LOG.info("Bot {} 决定受击反应但已找不到攻击记录（决策理由={}）", botId, decision.reason());
            return;
        }
        reactedUpTo.merge(botId, hit.at(), Math::max);
        reacted.incrementAndGet();
        long aggression = profile.ai().aggression();
        if (aggression >= COUNTER_ATTACK_MIN_FIXED && counterAttack(botId, hit.attackerId(), now)) {
            return;
        }
        if (aggression < COUNTER_ATTACK_MIN_FIXED
                && aggression >= RELOCATE_MIN_FIXED && relocate(botId, now)) {
            return;
        }
        LOG.info("Bot {} 选择龟缩（aggression={}，攻击者={}）：本次受击不做别的事，等下一次决策",
                botId, com.ironoath.common.num.FixedPoint.format(aggression), hit.attackerId());
    }

    /**
     * 反击：打回攻击者的城。<b>走 {@code MarchAppService.send} 这条真人路径</b>，
     * 于是护盾、战力圈层、外交、以及 C2 刚接上的频控全都自动生效 —— 不需要在这里再判一遍。
     *
     * <p><b>带全部可用兵力</b>：表里没有"反击带多少"这个数，而"全体出动"是可解释的
     * （被打之后倾巢而出）。这条口径待规格，记在收口清单里。
     */
    private boolean counterAttack(String botId, String attackerId, long now) {
        java.util.Optional<com.ironoath.core.world.Coord> target = world.cityOf(attackerId);
        if (target.isEmpty()) {
            LOG.info("Bot {} 想反击但找不到攻击者 {} 的城（可能已迁城/删号）", botId, attackerId);
            return false;
        }
        ArmyState army = armies.findByPlayerId(botId).orElse(null);
        if (army == null || army.totalTroops() <= 0L) {
            LOG.info("Bot {} 想反击但没有可派出的兵（攻击者={}）—— 新号没兵是常态，不是异常",
                    botId, attackerId);
            return false;
        }
        java.util.List<MarchUnit> units = new java.util.ArrayList<>();
        for (Map.Entry<String, Long> entry : army.troops().entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0L) {
                units.add(new MarchUnit(entry.getKey(), entry.getValue()));
            }
        }
        try {
            marches2.send(botId, new MarchReq(newRequestId(botId),
                    target.get().x(), target.get().y(), units, java.util.List.of(), MarchAction.ATTACK));
            return true;
        } catch (RuntimeException e) {
            // 被闸门拒绝（圈层/护盾/外交/频控）是玩法常态，不是失败：记一条 INFO 就够了
            LOG.info("Bot {} 反击未成行（攻击者={}）：{}", botId, attackerId, e.getMessage());
            return false;
        }
    }

    /** 迁城：走真人那条 {@code ExileAppService.exile}（免费随机落点 + 落地免战 + 3 天冷却）。 */
    private boolean relocate(String botId, long now) {
        try {
            exiles.exile(botId, new ExileReq(newRequestId(botId)));
            return true;
        } catch (RuntimeException e) {
            LOG.info("Bot {} 想迁城但没成行（冷却中或队伍在外）：{}", botId, e.getMessage());
            return false;
        }
    }

    // ---------- C3（#94）：掠袭 / 聊天 / 社交 / 入盟 ----------

    /** 这个 Bot 现在带得动的全部兵力（按 unitId 记，与真人出征同一口径）。 */
    private java.util.List<MarchUnit> allTroops(String botId) {
        ArmyState army = armies.findByPlayerId(botId).orElse(null);
        if (army == null) {
            return java.util.List.of();
        }
        java.util.List<MarchUnit> units = new java.util.ArrayList<>();
        for (Map.Entry<String, Long> entry : army.troops().entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0L) {
                units.add(new MarchUnit(entry.getKey(), entry.getValue()));
            }
        }
        return units;
    }

    /**
     * 掠袭一个真人（§一 劫掠者「会掠夺」、§六 邻里「互相小规模攻伐」）。
     *
     * <p><b>目标只能从 {@code TargetSearchService} 来</b>：那一条链上挂着圈层、活跃窗口、
     * 护盾、免战、同国与外交六道口径（B08 §2 指定搜索/出征/集结共用同一套判定）。
     * 自己遍历世界挑一个"看起来弱的"就是绕过它们 —— 而 Bot 打穿保护规则是 §十 的禁止项。
     *
     * <p><b>频控不需要在这里判</b>：{@code send → AttackGuardService} 里 C2 已经把
     * 「Bot → 真人 ≤3 次/24h」接在统一漏斗上了，这里的每次尝试都会被它数进去。
     *
     * <p><b>带全部兵力</b>与反击同一口径（表里没有"掠袭带多少"这个数，记在收口清单等规格）。
     */
    private void raid(String botId, long now, BotDecisionTree.Decision decision) {
        java.util.List<MarchUnit> units = allTroops(botId);
        if (units.isEmpty()) {
            LOG.info("Bot {} 想去掠袭但没有兵（决策理由={}）—— 新号没兵是常态", botId, decision.reason());
            return;
        }
        long radius = configs.longParam("SEARCH_MAX_RADIUS");
        SearchTargetsResp resp = targetSearch.search(botId, new SearchTargetsReq((int) radius, 1));
        if (resp.targets().isEmpty()) {
            LOG.info("Bot {} 想去掠袭但搜索里没有可选目标（圈层内无人 / 都在护盾期）", botId);
            return;
        }
        var target = resp.targets().get(0);   // 搜索已按 B08 §8 的四项权重排好序，取第一个
        try {
            marches2.send(botId, new MarchReq(newRequestId(botId),
                    target.coord().x(), target.coord().y(), units, java.util.List.of(),
                    MarchAction.ATTACK));
            raided.incrementAndGet();
            LOG.info("Bot {} 掠袭 {}（距我 {} 档，目标战力 {}）", botId, target.name(),
                    target.distanceBand(), target.matchPower());
        } catch (RuntimeException e) {
            // 被圈层/护盾/外交/频控拒绝都是玩法常态：INFO 一条就够了，不是失败
            LOG.info("Bot {} 掠袭未成行（目标={}）：{}", botId, target.name(), e.getMessage());
        }
    }

    /**
     * 在联盟频道说一句话（§四「事件触发模板句库」）。
     *
     * <p><b>只有联盟频道</b>：句库里的场景（求助、集结号召、战报口风、商贸口风）全都发生在联盟内部，
     * 而世界频道加上分钟级 tick 会变成刷屏 —— 表里也没有任何一行是为陌生人说的。
     *
     * <p><b>门禁走 {@code BotRegistry.mayAppearIn}</b>：这条链让那条红线的判定函数第一次有了生产调用点。
     * 门禁的场景名是<b>这条消息落在哪个位置</b>（ALLIANCE_CHAT），不是内容分类 ——
     * 内容分类（TRADE 之类）属于句库自己的事，往白名单里塞内容词会让"新增一个内容场景"变成改红线代码。
     *
     * <p><b>场景今天只接了三种</b>：入盟问候（一次）、闲聊、商贸。HELP_REQUEST / RALLY_CALL /
     * ATTACKED / VICTORY / DEFEAT 需要事件钩子（升级登记求助、开集结、战斗结算），
     * 那些钩子不在本轮范围 —— 表里那几行今天不可达，如实记在收口清单 #94，不假装都接了。
     */
    private void chat(String botId, long now, BotDecisionTree.Decision decision) {
        if (!bots.mayAppearIn("ALLIANCE_CHAT")) {
            // 白名单默认拒绝：今天它一定通过（ALLIANCE_CHAT 在放行名单里），
            // 但这一句的存在让"哪天有人把聊天挪进付费场景"这件事当场被拦下来
            LOG.warn("Bot {} 的聊天被场景白名单拒绝（ALLIANCE_CHAT）—— 这不该发生，检查 BOT 场景白名单", botId);
            return;
        }
        var alliance = social.allianceOf(botId);
        if (alliance.isEmpty()) {
            LOG.info("Bot {} 想聊天但不在联盟里（联盟频道之外的聊天会变成刷屏，本轮不做）", botId);
            return;
        }
        PlayerSave save = players.findByPlayerId(botId).orElse(null);
        BotProfile profile = bots.profileOf(botId);
        if (save == null || profile == null) {
            return;
        }
        BotChatBook book = assembler.chatBook();
        Rng rng = rngFor(botId, now);
        BotChatBook.Scene scene;
        if (!alliance.get().id().equals(greetedAlliance.get(botId))) {
            scene = BotChatBook.Scene.ALLIANCE_JOIN;
        } else {
            scene = rng.nextLong() % 2L == 0L ? BotChatBook.Scene.CHAT_IDLE : BotChatBook.Scene.TRADE;
        }
        var line = book.pick(rng, scene, save.cityLevel());
        if (line.isEmpty()) {
            // 等级没到这几句的门槛：安静的等到能说为止，不是错误
            LOG.info("Bot {} 在场景 {} 下没有够门槛的话可说（主城 {} 级）", botId, scene, save.cityLevel());
            return;
        }
        try {
            social2.chatSend(botId, new ChatSendReq(newRequestId(botId), ChatChannel.ALLIANCE,
                    line.get().text(), null), now);
            greetedAlliance.put(botId, alliance.get().id());
            lastChatScene.put(botId, scene.name());
            chatted.incrementAndGet();
            LOG.info("Bot {} 在联盟频道发言（场景={} 句={}）：{}", botId, scene, line.get().id(),
                    line.get().text());
        } catch (RuntimeException e) {
            // 限流（同一句话太频繁）是玩法常态：INFO 一条，不当作失败
            LOG.info("Bot {} 发言未发出（句={}）：{}", botId, line.get().id(), e.getMessage());
        }
    }

    /**
     * 申请加入一个联盟（§六「联盟填充」）。<b>没有这一步，"在联盟中"那一支永远不成立</b> ——
     * 捐献/帮助/集结/聊天四件事会变成只有测试走得到的死代码。
     *
     * <p>挑目标：按 id 升序取第一个<b>还有人位且自己没申请过</b>的联盟。
     * 服务端本身有去重与满员校验（重复申请与满员都会被拒），这里挑只是少跑一趟。
     *
     * <p>节流见 {@link #SEEK_ALLIANCE_INTERVAL_MILLIS}：申请被拒之后不该每个 tick 都去敲门。
     */
    private void seekAlliance(String botId, long now, BotDecisionTree.Decision decision) {
        if (social.allianceOf(botId).isPresent()) {
            return;   // 已经被接纳了（申请通过发生在我们看不到的地方）
        }
        Long last = lastSeekAllianceAt.get(botId);
        if (last != null && now - last < SEEK_ALLIANCE_INTERVAL_MILLIS) {
            LOG.info("Bot {} 想申请入盟，但距上次尝试不足 {} 小时（决策理由={}）",
                    botId, SEEK_ALLIANCE_INTERVAL_MILLIS / 3_600_000L, decision.reason());
            return;
        }
        lastSeekAllianceAt.put(botId, now);
        for (var alliance : social.allAlliances()) {
            if (alliance.memberCount() >= alliance.effectiveMemberCap()
                    || social.hasApplication(alliance.id(), botId)) {
                continue;
            }
            try {
                social2.allianceApply(botId, new AllianceIdReq(newRequestId(botId), alliance.id()));
                soughtAlliance.incrementAndGet();
                LOG.info("Bot {} 申请加入联盟 {}（{} 人 / 上限 {}）", botId, alliance.name(),
                        alliance.memberCount(), alliance.effectiveMemberCap());
            } catch (RuntimeException e) {
                LOG.info("Bot {} 申请入盟未成行（联盟={}）：{}", botId, alliance.id(), e.getMessage());
            }
            return;
        }
        LOG.info("Bot {} 想入盟但世界上没有还有空位的联盟", botId);
    }

    /**
     * 联盟捐献。固定走<b>免费档</b>（tier 0）：档位与消耗都是表里的数，
     * 而"Bot 该捐哪一档"没有规格 —— 免费档不花钱、不会把一个正在发育的 Bot 掏空，
     * 又确实完成了一次捐献（贡献值与联盟资金按 {@code alliance_config} 的档位表入账）。
     */
    private void donate(String botId, BotDecisionTree.Decision decision) {
        try {
            social2.allianceDonate(botId, new AllianceDonateReq(newRequestId(botId), 0));
            donated.incrementAndGet();
            LOG.info("Bot {} 完成一次联盟捐献（免费档）", botId);
        } catch (RuntimeException e) {
            LOG.info("Bot {} 捐献未成行（理由={}）：{}", botId, decision.reason(), e.getMessage());
        }
    }

    /**
     * 一键帮助：把联盟/小队里所有可帮的请求帮一遍（B10 §2）。
     *
     * <p>用 {@code helpAll} 而不是逐个 {@code help}：徽标与候选出自同一次判定（服务层注释写明），
     * 自己数一遍列表就是第二份真相；而"一键帮助全部"本来就是真人有的动作。
     */
    private void helpAllies(String botId, long now) {
        try {
            var resp = social2.helpAll(botId, now);
            helped.incrementAndGet();
            LOG.info("Bot {} 一键帮助：帮了 {} 条（跳过 {} 条）", botId, resp.helped(), resp.skipped());
        } catch (RuntimeException e) {
            LOG.info("Bot {} 一键帮助未成行：{}", botId, e.getMessage());
        }
    }

    /**
     * 响应集结：加入自己组织里最早的一个进行中的集结（B10 §2），承诺全部可用兵力。
     *
     * <p>先看小队再看联盟：小队是更小更紧的那个圈（C00 公理七），同一次 tick 只加入一个 ——
     * 加入多个集结会让同一批兵被锁两次（第二次会被服务端拒，但那是白跑）。
     */
    private void joinRally(String botId, long now, BotDecisionTree.Decision decision) {
        java.util.List<MarchUnit> units = allTroops(botId);
        if (units.isEmpty()) {
            LOG.info("Bot {} 想响应集结但没有兵（决策理由={}）", botId, decision.reason());
            return;
        }
        java.util.List<String> groupIds = new java.util.ArrayList<>(2);
        social.squadOf(botId).ifPresent(squad -> groupIds.add(squad.id()));
        social.allianceOf(botId).ifPresent(alliance -> groupIds.add(alliance.id()));
        for (String groupId : groupIds) {
            var rallies = social.preparingRalliesOf(groupId);
            if (rallies.isEmpty()) {
                continue;
            }
            var rally = rallies.get(0);
            java.util.List<RallyTroop> troops = new java.util.ArrayList<>(units.size());
            for (MarchUnit unit : units) {
                troops.add(new RallyTroop(unit.unitId(), unit.count()));
            }
            try {
                social2.rallyJoin(botId, new RallyJoinReq(newRequestId(botId), rally.rallyId(),
                        troops, java.util.List.of()));
                rallied.incrementAndGet();
                LOG.info("Bot {} 加入集结 {}（承诺 {} 种兵，当前 {}/{} 人）", botId, rally.rallyId(),
                        troops.size(), rally.joinedCount(), rally.maxMembers());
            } catch (RuntimeException e) {
                LOG.info("Bot {} 响应集结未成行（集结={}）：{}", botId, rally.rallyId(), e.getMessage());
            }
            return;
        }
        LOG.info("Bot {} 想响应集结，但组织里没有进行中的集结", botId);
    }

    /**
     * <b>事件触发的一次发言</b>（B11 §四「事件触发模板句库」）—— 给「世界里刚发生了一件事」
     * 的那些调用方用：受击 / 战斗结果 / 求助登记 / 发起集结。
     *
     * <p><b>为什么入口长这样</b>：事件方手里只有「谁、发生了什么」，而「该说哪一句」是句库的事。
     * 让事件方自己挑句子，等于把「哪个场景配哪种事」这条判断抄到四个调用点去；
     * 这里只收一个已经映射好的 {@link BotChatBook.Scene}（映射本身在 core 的 Scene 里，见
     * {@link BotChatBook.Scene#ofBattle} 与 {@link BotChatBook.Scene#eventDriven()}）。
     *
     * <p><b>它不是 Bot，就安静返回</b>：事件发生在任何人身上（真人被打也要出事件），
     * 而只有托管账号才说话 —— 这是本方法唯一的身份判断，且判断本身来自注册表
     * （{@code check-no-bot-privilege.sh} 只允许注册表回答"这是不是 Bot"）。
     *
     * <p><b>失败一律吞掉并记 INFO</b>：这件事发生在别人的结算路径上（战斗结算、升级登记），
     * 一句寒暄发不出去不该把那一场结算变成 500。
     *
     * <p><b>限流不需要在这里再做一遍</b>：发言走 {@code SocialAppService.chatSend}，
     * B10 的防刷屏判定就在那里面（同内容 10 秒内最多 3 条）；在这里再判一次就是第二份口径。
     *
     * @return 真的发出去了才 true（不在联盟里、等级没到门槛、被限流都返回 false）
     */
    public boolean speakOnEvent(String playerId, BotChatBook.Scene scene, long now) {
        BotProfile profile = bots.profileOf(playerId);
        if (profile == null) {
            return false;   // 真人（或已回收的 Bot）：事件照发，话不由这里说
        }
        return speak(playerId, profile, scene, now);
    }

    /**
     * 挑一句并发出。<b>与自发聊天共用同一条落地路径</b>（同一个句库、同一个 chatSend、
     * 同一份白名单与限流），只是「说哪一类」由调用方给定而不是掷骰决定。
     */
    private boolean speak(String botId, BotProfile profile, BotChatBook.Scene scene, long now) {
        if (!bots.mayAppearIn("ALLIANCE_CHAT")) {
            LOG.warn("Bot {} 的发言被场景白名单拒绝（ALLIANCE_CHAT）—— 这不该发生，检查 BOT 场景白名单", botId);
            return false;
        }
        var alliance = social.allianceOf(botId);
        if (alliance.isEmpty()) {
            // 事件触发的话也只在联盟频道说：B11 §四 的五个事件场景全都发生在联盟内部
            // （求援找盟友、集结叫同盟、战报给盟友听），世界频道加上事件频率会变成刷屏
            return false;
        }
        PlayerSave save = players.findByPlayerId(botId).orElse(null);
        if (save == null) {
            return false;
        }
        BotChatBook book = assembler.chatBook();
        var line = book.pick(rngFor(botId, now), scene, save.cityLevel());
        if (line.isEmpty()) {
            LOG.info("Bot {} 在事件场景 {} 下没有够门槛的话可说（主城 {} 级）", botId, scene, save.cityLevel());
            return false;
        }
        try {
            social2.chatSend(botId, new ChatSendReq(newRequestId(botId), ChatChannel.ALLIANCE,
                    line.get().text(), null), now);
            lastChatScene.put(botId, scene.name());
            chatted.incrementAndGet();
            LOG.info("Bot {} 因事件发言（场景={} 句={}）：{}", botId, scene, line.get().id(),
                    line.get().text());
            return true;
        } catch (RuntimeException e) {
            // 限流（同一句话太频繁）是玩法常态：INFO 一条，不当作失败
            LOG.info("Bot {} 事件发言未发出（句={}）：{}", botId, line.get().id(), e.getMessage());
            return false;
        }
    }

    /** 与真人同一条"可复现随机"约定：种子由 botId 与时刻推导，不用 Math.random。 */
    private static Rng rngFor(String botId, long now) {
        return Rng.of(now * 31L + botId.hashCode());
    }

    private void unhandled(String botId, BotDecisionTree.Decision decision) {
        unhandled.incrementAndGet();
        if (warned.add(decision.action())) {
            LOG.warn("动作 {} 没有对应的落地实现，Bot 的这一 tick 空转 —— "
                    + "决策树加了枚举却忘了在 BotWorldAdapter 里接？（收口清单 #96 之后 13 个动作应全部可达）",
                    decision.action());
        }
    }

    // ---------- 内部 ----------

    /** 一次读城建事实，失败退回"什么都没有" —— observe 不许抛（见类注释）。 */
    private CityAppService.CityTickFacts readCityQuietly(String botId, long now) {
        try {
            return cities.cityTickFacts(botId, now);
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            LOG.warn("读 Bot {} 的城建事实失败，本次按无可用目标处理：{}", botId, e.getMessage());
            return new CityAppService.CityTickFacts(false, null);
        }
    }

    /**
     * 幂等键。格式 {@code bot_<序号>_<botId 尾段>} ——
     * <b>必须全局唯一</b>：{@code IdempotencyStore} 的键不按 playerId 分域（Mongo 用 {@code _id=requestId}），
     * 撞了就是 1002 而不是重试成功。尾段带上 botId 只为了 grep 时能看出是谁的哪一次。
     */
    private String newRequestId(String botId) {
        long seq = requestSeq.incrementAndGet();
        String tail = botId.length() <= 8 ? botId : botId.substring(botId.length() - 8);
        return "bot-" + seq + "-" + tail;
    }

    /** 已落地的动作计数（供 {@code /bot/tick} 回参与仿真断言）。 */
    public Map<String, Long> actionCounts() {
        return Map.ofEntries(
                Map.entry("upgraded", upgraded.get()), Map.entry("trained", trained.get()),
                Map.entry("hunted", hunted.get()), Map.entry("gathered", gathered.get()),
                Map.entry("reacted", reacted.get()),
                Map.entry("raided", raided.get()), Map.entry("chatted", chatted.get()),
                Map.entry("soughtAlliance", soughtAlliance.get()), Map.entry("donated", donated.get()),
                Map.entry("helped", helped.get()), Map.entry("rallied", rallied.get()),
                Map.entry("skipped", skipped.get()),
                Map.entry("failed", failed.get()), Map.entry("unhandled", unhandled.get()));
    }

    public long failedCount() {
        return failed.get();
    }

    public long unhandledCount() {
        return unhandled.get();
    }

    /** 测试用：清零计数（同一个 Spring 上下文里跑多条用例时避免互相污染）。 */
    public void resetCounters() {
        upgraded.set(0L);
        trained.set(0L);
        hunted.set(0L);
        gathered.set(0L);
        reacted.set(0L);
        raided.set(0L);
        chatted.set(0L);
        soughtAlliance.set(0L);
        donated.set(0L);
        helped.set(0L);
        rallied.set(0L);
        skipped.set(0L);
        failed.set(0L);
        unhandled.set(0L);
        warned.clear();
        reactedUpTo.clear();
        greetedAlliance.clear();
        lastSeekAllianceAt.clear();
        lastChatScene.clear();
    }

    /** 某个 Bot 已回应到哪个时刻（测试断言"同一发攻击不会被回应两次"要用）。 */
    public Long reactedUpToOf(String botId) {
        return reactedUpTo.get(botId);
    }

    /** 某个 Bot 上一次发言用的场景。 */
    public String lastChatSceneOf(String botId) {
        return lastChatScene.get(botId);
    }

    /** 某个 Bot 上一次尝试入盟的时刻（0 表示没试过）。 */
    public Long lastSeekAllianceAtOf(String botId) {
        return lastSeekAllianceAt.get(botId);
    }
}
