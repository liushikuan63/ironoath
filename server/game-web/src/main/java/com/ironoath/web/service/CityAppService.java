package com.ironoath.web.service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.log.TraceContext;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.num.Rates;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.BuildingCfg;
import com.ironoath.core.city.BuildingInstance;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityRules;
import com.ironoath.core.city.CityState;
import com.ironoath.core.city.ResourceSettlement;
import com.ironoath.core.city.UpgradeCheck;
import com.ironoath.core.formula.Formula;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.player.PlayerTech;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.web.dto.generated.BuildingView;
import com.ironoath.web.dto.generated.BuildOptionView;
import com.ironoath.web.dto.generated.CityCancelReq;
import com.ironoath.web.dto.generated.CityCancelResp;
import com.ironoath.web.dto.generated.CityCollectReq;
import com.ironoath.web.dto.generated.CityCollectResp;
import com.ironoath.web.dto.generated.CityListResp;
import com.ironoath.web.dto.generated.CityUpgradeReq;
import com.ironoath.web.dto.generated.CityUpgradeResp;
import com.ironoath.web.dto.generated.QueueView;
import com.ironoath.web.dto.generated.ResourceAmount;
import com.ironoath.web.dto.generated.ResourceStateView;
import com.ironoath.web.dto.generated.ResourceType;
import com.ironoath.web.dto.generated.SpeedUpReq;
import com.ironoath.web.dto.generated.SpeedUpResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：城建应用服务 —— 把「加锁 → 幂等 → 读档 → 校验 → 扣资源 → 改状态 → 落库」串成一条安全路径。
 * 依赖：game-core（城建逻辑与端口）、game-config（配置）、game-common（时间/错误码）。
 *
 * <p><b>为什么这些编排不在 game-core</b>：game-core 的 CityState 只做状态变更与规则校验，
 * 返回「应该扣多少、返还多少」，不碰存储。事务边界、锁、幂等都属于应用层职责 ——
 * 把它们塞进领域层，领域层就得依赖仓储与锁，也就再也无法脱离容器跑单测了。
 *
 * <p>并发安全的三道防线（B00 陷阱 3、B03 验收 2/10），缺一不可：
 * <ol>
 *   <li><b>requestId 幂等</b>：拦住客户端断网重放造成的重复提交</li>
 *   <li><b>玩家级锁</b>：让同一玩家的「读-改-写」串行，不同玩家完全并行</li>
 *   <li><b>乐观锁版本</b>：锁失效时（多实例 + JVM 内锁）的最后一道网</li>
 * </ol>
 * 只有幂等挡不住换 requestId 的并发；只有锁挡不住跨实例；只有乐观锁会让用户频繁看到重试失败。
 */
@Service
public class CityAppService {

    private static final Logger LOG = LoggerFactory.getLogger(CityAppService.class);

    /** 玩家锁的获取超时。超时即返回错误让客户端重试，绝不无限等待拖死线程池。 */
    private static final long LOCK_TIMEOUT_MS = 3000L;

    private final ConfigRegistry configs;
    private final Formula formula;
    private final PlayerRepository players;
    private final CityRepository cities;
    private final PlayerLock playerLock;
    private final IdempotencyStore idempotency;
    private final TimeService timeService;
    private final ResourceRateService resourceRates;
    private final com.ironoath.core.limit.DailyCounter dailyCounter;
    /** 背包写入的唯一入口（与发放器共用同一个适配器），详见 RewardBeansConfig#playerBag。 */
    private final com.ironoath.core.reward.RewardPorts.Bag bagPort;
    /**
     * 互助：升级开始时登记求助请求（B10 验收 6 的生产者）。
     *
     * <p><b>刻意不注入 {@code SocialAppService}</b>：它依赖着攻击闸门链
     * （→ AttackGuardService → PowerRefreshService → 本类），反过来注入会直接成环，
     * 上下文起不来。登记器只依赖存储，依赖方向永远只有一条。
     */
    private final com.ironoath.web.social.HelpRequestRegistrar helpRequests;
    /**
     * 任务进度的事件入口（B12 §1）。发布点选在 {@link #load} 的结算里而不是领取端点：
     * 「升级完成」这件事是在结算那一刻发生的（谁先读城谁触发），
     * 挂在领取端点上会让「升完级先看了列表、再没点领取」的玩家永远拿不到这条进度。
     */
    private final com.ironoath.web.quest.QuestEvents questEvents;
    /** 月卡的建造队列加成从这里推（付费表唯一的读处）。 */
    private final com.ironoath.web.pay.PaidProducts paidProducts;
    /** 建造速度科技加成的读取口（B20 块①；它只依赖配置表，不构成与 TechAppService 的环）。 */
    private final com.ironoath.web.tech.TechEffects techEffects;
    /** 国家科技那一份（B20 块③），与个人的相加后作用一次（§五④）。 */
    private final com.ironoath.web.nation.NationTechBonuses nationTechBonuses;

    public CityAppService(ConfigRegistry configs, Formula formula, PlayerRepository players,
                          CityRepository cities, PlayerLock playerLock,
                          IdempotencyStore idempotency, TimeService timeService,
                          ResourceRateService resourceRates,
                          com.ironoath.core.limit.DailyCounter dailyCounter,
                          com.ironoath.core.reward.RewardPorts.Bag bagPort,
                          com.ironoath.web.social.HelpRequestRegistrar helpRequests,
                          com.ironoath.web.quest.QuestEvents questEvents,
                          com.ironoath.web.pay.PaidProducts paidProducts,
                          com.ironoath.web.tech.TechEffects techEffects,
                          com.ironoath.web.nation.NationTechBonuses nationTechBonuses) {
        this.configs = configs;
        this.formula = formula;
        this.players = players;
        this.cities = cities;
        this.playerLock = playerLock;
        this.idempotency = idempotency;
        this.timeService = timeService;
        this.resourceRates = resourceRates;
        this.dailyCounter = dailyCounter;
        this.bagPort = bagPort;
        this.helpRequests = helpRequests;
        this.questEvents = questEvents;
        this.paidProducts = paidProducts;
        this.techEffects = techEffects;
        this.nationTechBonuses = nationTechBonuses;
    }

    /**
     * 城建规则：从 city_rule 表逐字段解析（铁律 1：不硬编码），再把<b>这个玩家此刻生效的
     * 付费队列加成</b>并进去（B19 月卡的 +1）。
     *
     * <p><b>加成并进三个队列数而不是另开一个参数</b>：{@code availableQueues} 的算法是
     * {@code min(上限, 基础 + 额外)}，只把"上限"抬到 4 而"基础"仍是 3，结果还是 3 ——
     * 那一格就白买了。三数同抬一个 bonus，等式两边一起动，读法与 {@code QueueView} 的展示口径
     * 不需要各自再判一次"有没有月卡"。
     *
     * <p><b>为什么每次现推、不把 +1 写进 {@code CityState.extraQueues}</b>：服务端不跑定时器
     * （B00 陷阱 2），落库的那一格没有任何人会在月卡到期时收走 —— 表现是"到期了还多一条队列，
     * 而且永远如此"。现推则到点自动回收，正合 §五②b「到期只禁新开、不中断在跑的升级」：
     * 上限回到 3 时正在跑的第四条队列照样跑完，只是再开新的会被 CITY_QUEUE_FULL 挡住。
     *
     * <p><b>玩家为 null 时按无加成处理</b>：只有单测与"还没建档"的路径会走到这里，
     * 而拿不准的时候少给一格，永远比多给一格好收拾。
     */
    public CityRules cityRules(PlayerSave player, long now) {
        int bonus = paidProducts.entitlements(player == null ? null : player.paid(), now)
                .bonusQueuesAsInt();
        return new CityRules(
                (int) cityLong("city_rule_grid_size"),
                cityBool("city_rule_wall_edge_only"),
                cityBool("city_rule_center_is_main_city"),
                Math.addExact((int) cityLong("city_rule_base_queue_count"), bonus),
                Math.addExact((int) cityLong("city_rule_max_queue_count"), bonus),
                Math.addExact((int) cityLong("city_rule_newbie_free_queue_count"), bonus),
                cityFixed("city_rule_cancel_refund_ratio"),
                cityFixed("city_rule_help_per_person_ratio"),
                cityFixed("city_rule_help_cap_ratio"),
                cityLong("city_rule_ad_speedup_daily_limit"),
                cityLong("city_rule_ad_speedup_seconds"),
                cityLong("city_rule_move_cooldown_seconds"));
    }

    private long cityLong(String id) {
        return cityParam(id).asLong();
    }

    private long cityFixed(String id) {
        return cityParam(id).asFixed();
    }

    private boolean cityBool(String id) {
        return cityParam(id).asBool();
    }

    /** city_rule 表与 global 表结构相同（键值型），复用 GlobalCfg 解析。 */
    private com.ironoath.config.model.GlobalCfg cityParam(String id) {
        for (var row : configs.rawTable("city_rule").rows()) {
            var candidate = com.ironoath.config.model.GlobalCfg.from(row);
            if (candidate.id().equals(id)) {
                return candidate;
            }
        }
        throw new com.ironoath.config.ConfigException("city_rule 表中不存在参数: " + id);
    }

    /**
     * 升级（或首次建造）一个建筑。
     *
     * @throws BizException 前置校验失败，携带结构化 need/current 详情
     */
    public CityUpgradeResp upgrade(String playerId, CityUpgradeReq req) {
        validateRequest(playerId, req);
        long now = timeService.serverNow();
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;

        // 第一道防线：幂等占用
        if (!idempotency.tryAcquire(req.requestId(), now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + req.requestId());
        }
        try {
            // 第二道防线：玩家级锁，让读-改-写串行
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS,
                    () -> doUpgrade(playerId, req, now));
        } catch (RuntimeException e) {
            // 失败必须释放幂等键：副作用没有产生，否则玩家重试会被永久挡在门外
            idempotency.release(req.requestId());
            throw e;
        }
    }

    private CityUpgradeResp doUpgrade(String playerId, CityUpgradeReq req, long now) {
        Ctx ctx = load(playerId, now);
        PlayerSave player = ctx.player();
        CityState city = ctx.city();
        CityRules rules = ctx.rules();

        BuildingCfg cfg = configs.get(BuildingCfg.class, req.configId());
        BuildingInstance instance = city.findByConfigId(req.configId());
        boolean isNew = instance == null;
        if (isNew) {
            int gridX = req.gridX() == null ? -1 : req.gridX();
            int gridY = req.gridY() == null ? -1 : req.gridY();
            if (gridX < 0 || gridY < 0) {
                throw new BizException(ErrorCode.CITY_GRID_INVALID,
                        "首次建造 " + req.configId() + " 必须给出地块坐标");
            }
            instance = city.place("bld_" + playerId.substring(Math.max(0, playerId.length() - 8))
                            + "_" + req.configId(),
                    req.configId(), gridX, gridY, rules,
                    cfg.type() == BuildingCfg.Type.DEFENSE && "wall".equals(req.configId()),
                    cfg.type() == BuildingCfg.Type.CORE);
        }

        // 算一次，取三个值。刻意不重复调用 attemptOf：同一次升级里校验三遍，
        // 就等于问"哪一遍算数"，而答案通常是"最后一遍"—— 那正是并发下最容易出错的样子
        UpgradeAttempt attempt = attemptOf(city, instance, player, rules, now);
        int targetLevel = attempt.targetLevel();
        Map<String, Long> cost = attempt.cost();
        long durationSeconds = upgradeDuration(player.playerId(), cfg, targetLevel, player.tech());

        attempt.check().orThrow();

        // 扣资源：CityState 只告诉我们该扣多少，真正扣减在这里做，
        // 且必须与写库在同一把锁内 —— 这就是「禁止先查后改」的落地方式
        deductResources(player, cost);

        int availableQueues = rules.availableQueues(isInNewbieProtect(player, now), city.extraQueues());
        long finishAt = city.startUpgrade(instance.instanceId(), durationSeconds, availableQueues, now);

        players.save(player);
        // 第三道防线：乐观锁。锁失效时（多实例 + JVM 内锁）这里是最后一道网
        cities.save(playerId, city, ctx.cityVersion());

        // 登记求助请求（B10 验收 6 的**生产者**）：升级真的在跑才登记 —— 秒完成的升级没有可减的时长，
        // 登记出来只会是一条点了没用的红点。id 由「谁 + 哪栋楼 + 这一轮何时完成」拼成，
        // 同一栋楼的同一轮升级因此只会有一条请求（重复登记会被 store 覆盖，语义等同）。
        // playerId 必须进 id：请求 id 是社交存储的主键，而 instanceId 不含玩家 ——
        // 两个玩家同时升级同一栋楼、又恰好同一毫秒完成时，只用「楼 + 完成时刻」会撞成一条，
        // 其中一个人的求助会静默消失（收口清单 #83 更正）。
        // 加速落地在 SocialAppService 那一侧（上限以社交账本为权威，见收口清单 #81 的裁决）
        if (finishAt > now) {
            helpRequests.register("help_" + playerId + "_" + instance.instanceId() + "_" + finishAt,
                    playerId, com.ironoath.web.dto.generated.HelpTargetKind.BUILDING,
                    instance.instanceId(), req.configId() + " 升到 " + targetLevel + " 级", finishAt, now);
        }

        long powerDelta = powerDelta(cfg, targetLevel);
        TraceContext.bindPlayer(playerId);
        LOG.info("建筑升级开始 playerId={} building={} 目标等级={} 耗时={}秒 完成于={} 消耗={}",
                playerId, req.configId(), targetLevel, durationSeconds, finishAt, cost);
        return new CityUpgradeResp(instance.instanceId(), targetLevel, finishAt,
                toAmountList(cost), powerDelta);
    }

    /**
     * 所有写操作共用的加载入口：读玩家档 → 取/建城建档 → <b>分段结算</b>。
     *
     * <p>结算是加载的一部分而不是可选步骤：产率与容量都存在存档里当缓存，
     * 不先结算就会拿旧速率去校验「资源是否充足」，玩家会看到明明够却提示不足。
     * 收割到点的升级也在这里发生（{@link ResourceRateService#settle} 内部按完成时刻切段），
     * 因此返回的 city 一定是「已完成升级都已落地」的状态。
     */
    private Ctx load(String playerId, long now) {
        PlayerSave player = requirePlayer(playerId);
        CityRules rules = cityRules(player, now);
        CityState city = loadOrCreateCity(playerId, rules, now);
        long cityVersion = cities.versionOf(playerId);
        ResourceRateService.Settlement settlement = resourceRates.settle(player, city, now);
        syncMainCityLevel(player, city);
        // 升级完成的事件在这里发（B12 §1）：收割是「结算那一刻」发生的，而结算是被读触发的 ——
        // 挂在任何单一端点上都会漏掉「先读列表再看别的」的路径。次数=收割到的栋数，目标=建筑配置 id
        for (String instanceId : settlement.harvested()) {
            questEvents.progress(playerId, com.ironoath.core.quest.GoalType.UPGRADE_BUILDING,
                    city.building(instanceId).configId(), 1L, now);
        }
        return new Ctx(player, city, rules, cityVersion, settlement);
    }

    /**
     * 把主城等级同步回玩家存档。
     *
     * <p>{@code PlayerSave.cityLevel} 是所有「需要主城 X 级」前置校验（{@code validateUpgrade} 的第 ① 步）
     * 的依据，而它的唯一真相是城建里那栋 CORE 建筑的等级。两者必须在这里对齐，否则
     * 主城升完级后 cityLevel 还停在旧值，所有 requireMainLevel 更高的建筑就永远建不起来。
     *
     * <p>放在分段结算<b>之后</b>：只有收割完成的升级才应该抬高主城等级，升级中不算。
     * 取 max 而不是直接赋值，是为了让存档在被外部改写（补偿、迁移）后也不会倒退 ——
     * 主城等级倒退会让已经建好的高级建筑瞬间变成「违规状态」。
     */
    private void syncMainCityLevel(PlayerSave player, CityState city) {
        for (BuildingInstance b : city.buildings()) {
            if (configs.get(BuildingCfg.class, b.configId()).type() != BuildingCfg.Type.CORE) {
                continue;
            }
            if (b.level() > player.cityLevel()) {
                LOG.info("主城等级同步 playerId={} {} → {}", player.playerId(), player.cityLevel(), b.level());
                player.setCityLevel(b.level());
            }
        }
    }

    /** 一次加载得到的上下文。cityVersion 用于落库时的乐观锁比对。 */
    private record Ctx(PlayerSave player, CityState city, CityRules rules,
                       long cityVersion, ResourceRateService.Settlement settlement) {
    }

    /** 取或创建城建存档。新号在中心格放一个 1 级主城。 */
    private CityState loadOrCreateCity(String playerId, CityRules rules, long now) {
        var existing = cities.findByPlayerId(playerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        CityState fresh = new CityState();
        int center = rules.gridSize() / 2;
        BuildingCfg mainCity = configs.get(BuildingCfg.class, "main_city");
        BuildingInstance main = fresh.place("bld_main_city", "main_city", center, center,
                rules, false, true);
        // 新号主城直接是 1 级（与 PlayerSave.cityLevel 一致），不占用建造队列
        main.restore(1, center, center, com.ironoath.core.city.BuildingStatus.IDLE,
                null, 0L, 0L, 0L, 0, now, 0L);
        if (mainCity.type() != BuildingCfg.Type.CORE) {
            throw new IllegalStateException("main_city 的配置类型应为 CORE，实际=" + mainCity.type());
        }
        cities.insertIfAbsent(playerId, fresh);
        return cities.findByPlayerId(playerId)
                .orElseThrow(() -> new BizException(ErrorCode.SYSTEM_ERROR, "城建存档创建后立即读不到"));
    }

    /** 升级消耗：四种资源的基数按 BUILDING_COST 曲线（比率 1.22）递增到目标等级。 */
    private Map<String, Long> upgradeCost(BuildingCfg cfg, int targetLevel) {
        long ratio = configs.curve("BUILDING_COST").ratioFixed();
        Map<String, Long> cost = new LinkedHashMap<>();
        putIfPositive(cost, ResourceIds.WOOD, cfg.costBaseWood(), targetLevel, ratio);
        putIfPositive(cost, ResourceIds.STONE, cfg.costBaseStone(), targetLevel, ratio);
        putIfPositive(cost, ResourceIds.IRON, cfg.costBaseIron(), targetLevel, ratio);
        putIfPositive(cost, ResourceIds.GRAIN, cfg.costBaseGrain(), targetLevel, ratio);
        return cost;
    }

    private void putIfPositive(Map<String, Long> cost, String resource, long base, int level, long ratio) {
        if (base <= 0L) {
            return;
        }
        // 基数是「1→2 级」的消耗，升到 targetLevel 对应曲线的第 targetLevel-1 项
        long fixed = Formula.buildingCost(FixedPoint.of(base), Math.max(1, level - 1), ratio);
        long amount = FixedPoint.round(fixed);
        if (amount > 0L) {
            cost.put(resource, amount);
        }
    }

    /**
     * 升级耗时（秒）。timeBaseSec=0 表示沿用 curve.BUILDING_TIME 自带的基数 30 秒。
     *
     * <p>建造速度按 §五④ 生效：<b>同类加成先加成总率、再作用于基础时长一次、缩短向上取整</b>。
     * 向上取整不是为了好看 —— 不取整（或按倍率连乘）都会让高加成把一次升级压成 0 秒，
     * 而 0 秒的队列等于没有队列：玩家可以瞬间连点十级，卡点节奏（B02 定下的第 7 天主城 13 级）就没了。
     */
    private long upgradeDuration(String playerId, BuildingCfg cfg, int targetLevel, PlayerTech tech) {
        long baseSeconds = cfg.timeBaseSec() == 0L
                ? formula.evaluateSeconds("BUILDING_TIME", Math.max(1, targetLevel - 1))
                : formula.evaluateSeconds("BUILDING_TIME", FixedPoint.of(cfg.timeBaseSec()),
                        Math.max(1, targetLevel - 1));
        // §五④：同类加成先<b>相加</b>成总率，再作用于基础时长一次。个人与国家两个来源在这一句里合并，
        // 不在各自读取口里分别乘一遍（两处各乘 = 满配时被钳到 1 秒的那条下限更早咬到）
        long percentFixed = techEffects.buildSpeedPercent(tech)
                + nationTechBonuses.buildSpeedPercent(playerId);
        return Rates.shortenSeconds(baseSeconds, percentFixed);
    }

    /** 战力增量：按 POWER_CONTRIB 曲线（指数 1.15）算目标等级与当前等级的差。 */
    private long powerDelta(BuildingCfg cfg, int targetLevel) {
        if (cfg.powerBase() <= 0L) {
            return 0L;
        }
        long exponent = configs.curve("POWER_CONTRIB").exponentFixed();
        long after = Formula.powerContribution(FixedPoint.of(cfg.powerBase()), targetLevel, exponent);
        long before = targetLevel <= 1 ? 0L
                : Formula.powerContribution(FixedPoint.of(cfg.powerBase()), targetLevel - 1, exponent);
        return FixedPoint.round(after - before);
    }

    /** 前置建筑：building 表的 requireBuilding 字段，null 表示无前置。 */
    private String firstRequirement(BuildingCfg cfg) {
        return cfg.requireBuilding();
    }

    /**
     * 前置建筑要求的等级。
     *
     * <p>building 表只声明了「需要哪个前置建筑」，没声明「需要它几级」——
     * 用「目标等级 - 1」作为默认口径：升 N 级要求前置建筑至少 N-1 级，
     * 这样前置建筑永远比依赖它的建筑低一级，形成一条不会自锁的升级链。
     * TODO(需确认): 若策划需要逐建筑指定前置等级，应在 building 表加 requireBuildingLevel 字段。
     */
    private int requirementLevel(BuildingCfg cfg) {
        // building 表当前没有 requireBuildingLevel 字段，统一返回 0（即「前置建筑只要存在即可」）。
        // 待策划需要逐建筑指定前置等级时，加字段并在此读取，不要在这里写死具体数字。
        return cfg.requireBuilding() == null ? 0 : 0;
    }

    /**
     * 当前可用资源快照。
     *
     * <p>不再自己结算：{@link #load} 里的分段结算已经把存档推到 now，
     * 存档里的 current 就是「此刻可用」。两处都结算会让「谁负责推进时间」变得含糊，
     * 而含糊的结算责任正是产率与面板对不上的根源。
     */
    private Map<String, Long> currentResources(PlayerSave player) {
        Map<String, Long> owned = new LinkedHashMap<>();
        player.resources().forEach((type, s) -> owned.put(type, s.current()));
        return owned;
    }

    /** 扣资源。调用前必须已经过 {@link #load} 的结算，且整个流程在玩家锁内。 */
    private void deductResources(PlayerSave player, Map<String, Long> cost) {
        for (Map.Entry<String, Long> e : cost.entrySet()) {
            PlayerResourceState s = player.resource(e.getKey());
            long need = e.getValue();
            if (s.current() < need) {
                // 理论上 validateUpgrade 已经挡过；这里是并发下的最后一道校验，
                // 因为校验与扣减之间存档可能被别的请求改过
                throw new BizException(ErrorCode.CITY_RESOURCE_LOW,
                        e.getKey() + " 需要 " + need + "，当前 " + s.current());
            }
            player.putResource(e.getKey(), new PlayerResourceState(
                    s.current() - need, s.cap(), s.protectedAmount(), s.perHour(), s.lastSettle()));
        }
    }

    /** 一次「能不能升」的算式产物。 */
    private record UpgradeAttempt(int targetLevel, Map<String, Long> cost, UpgradeCheck check) {
    }

    /**
     * 某个建筑此刻能不能升 —— 升级流程与红点共用这一份算式。
     *
     * <p>这里只准备参数（目标等级、消耗），<b>判定仍然全部在领域层
     * {@code CityState#validateUpgrade} 里</b>。如果红点这边自己比较一次资源和等级，
     * 就成了第二份真相：表现是「红点亮着、点进去升不了」，而两边都有日志。
     */
    private UpgradeAttempt attemptOf(CityState city, BuildingInstance instance, PlayerSave player,
                                     CityRules rules, long now) {
        BuildingCfg cfg = configs.get(BuildingCfg.class, instance.configId());
        int targetLevel = instance.level() + 1;
        Map<String, Long> cost = upgradeCost(cfg, targetLevel);
        UpgradeCheck check = city.validateUpgrade(
                instance.instanceId(), targetLevel, (int) cfg.maxLevel(),
                (int) cfg.requireMainLevel(), player.cityLevel(),
                firstRequirement(cfg), requirementLevel(cfg),
                cost, currentResources(player), rules, isInNewbieProtect(player, now), now);
        return new UpgradeAttempt(targetLevel, cost, check);
    }

    /**
     * 有没有能升的建筑（B12 §4 红点树注册的条件读的就是它）。
     *
     * <p>两条边界：① 只覆盖<b>已放置</b>的建筑 —— 首次建造必须要地块坐标（见 {@code CITY_GRID_INVALID}），
     * "哪里还能再放一座"是另一个问题，别混进来当同一个红点。
     * ② 它走 {@link #load}，因此会顺带推进惰性结算 —— 这不是廉价查询，只该被红点树这种
     * 低频入口调用（每次开面板 / 每次写操作之后），不要放进列表循环或每帧。
     */
    public boolean hasUpgradable(String playerId, long now) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
        Ctx ctx = load(playerId, now);
        for (BuildingInstance instance : ctx.city().buildings()) {
            if (attemptOf(ctx.city(), instance, ctx.player(), ctx.rules(), now).check().passed()) {
                return true;
            }
        }
        return false;
    }

    /** 「现在能升哪一栋」的产物 —— 目标由领域层的同一份判定选出，不由调用方猜。 */
    public record Upgradable(String configId, int targetLevel) {
    }

    /**
     * 一次 tick 要的城建事实：建造队列有没有空位 + 第一栋此刻真的能升的楼（没有则 null）。
     *
     * <p><b>为什么合成一个读法而不是两个</b>：{@code attemptOf} 里那句"判定仍然全部在领域层"
     * 如果只对红点成立，Bot 的 tick 就只能自己比较资源和等级 —— 那是第二份「够不够」，
     * 表现是"Bot 决定要升、升级每次都被拒"，两边各有日志、谁都不像 bug。
     * 两个事实都来自同一份 {@code attemptOf} / {@code rules.availableQueues}，
     * 而且<b>只加一次锁、只推进一次惰性结算</b>。
     *
     * <p><b>只覆盖已放置的建筑</b>（与 {@link #hasUpgradable} 同一条边界）：首次建造要选地块坐标，
     * 那是另一个问题（Bot 会升主城，不会自己找格子插新楼 —— 收口清单 #91 记着）。
     *
     * <p><b>不便宜</b>：它走 {@code load}，会顺带推进惰性结算。每 tick 一次是刻意的
     * （Bot 的 tick 等价于"这个玩家打开了一次面板"，与真人同一条路径），别放进循环里反复问。
     */
    public record CityTickFacts(boolean hasFreeBuildQueue, Upgradable nextUpgradable) {
        /** 有没有"现在就能升"的目标 —— 决策树要的就是这个布尔。 */
        public boolean canAffordBuilding() {
            return nextUpgradable != null;
        }
    }

    public CityTickFacts cityTickFacts(String playerId, long now) {
        return withSettledCity(playerId, ctx -> {
            boolean freeQueue = ctx.city().usedQueues()
                    < ctx.rules().availableQueues(isInNewbieProtect(ctx.player(), ctx.now()),
                            ctx.city().extraQueues());
            Upgradable target = null;
            for (BuildingInstance instance : ctx.city().buildings()) {
                UpgradeAttempt attempt = attemptOf(ctx.city(), instance, ctx.player(), ctx.rules(), ctx.now());
                if (attempt.check().passed()) {
                    target = new Upgradable(instance.configId(), attempt.targetLevel());
                    break;
                }
            }
            return new CityTickFacts(freeQueue, target);
        });
    }

    private boolean isInNewbieProtect(PlayerSave player, long now) {
        Long until = player.protectUntil();
        return until != null && until > now;
    }

    private void validateRequest(String playerId, CityUpgradeReq req) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        if (req.requestId() == null || req.requestId().isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "requestId 不得为空");
        }
        if (req.configId() == null || req.configId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "configId 不得为空");
        }
        if (!configs.hasTable("building") || configs.rawTable("building").has(req.configId())) {
            return;
        }
        throw new BizException(ErrorCode.CITY_BUILDING_NOT_FOUND, "建筑配置不存在: " + req.configId());
    }

    // ---------- B03 剩余端点：list / speedUp / cancel / collect ----------

    /** 广告加速的日限次计数域。 */
    private static final String SCOPE_AD_SPEEDUP = "ad_speedup";

    /**
     * 城内列表 —— 读的同时完成两件惰性工作：收割到点的升级、结算离线产出（B03 §2）。
     *
     * <p>「读」在这里是有副作用的，这是惰性结算的必然结果：没有定时器推进状态，
     * 状态只能在有人读的时候被推进。因此本方法也要走锁与乐观锁落库。
     */
    public CityListResp list(String playerId) {
        long now = timeService.serverNow();
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
            Ctx ctx = load(playerId, now);
            PlayerSave player = ctx.player();
            CityState city = ctx.city();
            CityRules rules = ctx.rules();

            List<BuildingView> buildings = new ArrayList<>();
            for (BuildingInstance b : city.buildings()) {
                buildings.add(toView(b, now));
            }
            List<BuildOptionView> buildOptions = new ArrayList<>();
            for (BuildingCfg cfg : configs.all(BuildingCfg.class)) {
                if (city.findByConfigId(cfg.id()) != null) {
                    continue;
                }
                buildOptions.add(new BuildOptionView(cfg.id(), cfg.name(), cfg.type().name(),
                        (int) cfg.requireMainLevel(), cfg.requireBuilding()));
            }
            QueueView queues = new QueueView(city.usedQueues(),
                    rules.availableQueues(isInNewbieProtect(player, now), city.extraQueues()),
                    (int) rules.maxQueueCount());

            players.save(player);
            cities.save(playerId, city, ctx.cityVersion());
            if (!ctx.settlement().harvested().isEmpty()) {
                LOG.info("城内列表触发到点收割 playerId={} 完成建筑={} 本次入账={}",
                        playerId, ctx.settlement().harvested(), ctx.settlement().credited());
            }
            return new CityListResp(buildings, buildOptions, queues,
                    toResourceMap(ctx.settlement().states()), now);
        });
    }

    /**
     * 在玩家锁内完成一次分段结算并落库，返回结算结果（含最后一段使用的产率与容量）。
     *
     * <p>供 {@code /resource/detail}（B04 §2 产出明细面板）使用。
     * 面板必须显示与存档<b>完全一致</b>的产率 —— 验收 5 要求「各项之和 = 实际每小时产出，误差 0」，
     * 而「实际每小时产出」就是存档里那个 perHour。所以面板不能自己再算一次：
     * 两次计算之间只要发生一次收割（升级完成），两份数字就会不同。
     * 唯一安全的做法是把这次结算用的那份产率直接交出去。
     *
     * <p>与 {@link #list} 一样，这是个「读」接口但有副作用：惰性结算没有定时器推进状态，
     * 状态只能在有人读的时候被推进（B00 陷阱 2）。
     */
    public ResourceRateService.Settlement settleAndSnapshot(String playerId) {
        long now = timeService.serverNow();
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
            Ctx ctx = load(playerId, now);
            players.save(ctx.player());
            cities.save(playerId, ctx.city(), ctx.cityVersion());
            return ctx.settlement();
        });
    }

    /**
     * 在玩家锁内完成分段结算，把上下文交给调用方执行动作，动作成功后统一落库。
     *
     * <p><b>为什么要把这段编排开放出去</b>：B05 的军队系统需要读城建状态（判断兵种阶级是否解锁、
     * 医院容量是多少）并扣资源，B07 的行军还要再读一次。如果每个域都自己
     * {@code load → 改 → save}，就会各自拿到一份<b>副本</b> —— 仓储返回的是深拷贝，
     * 两个域各改各的、后写的那份会覆盖前一份的改动，而乐观锁版本号在单线程内根本拦不住
     * （两次 save 各带自己读到的版本，第二次要么冲突要么覆盖）。
     * 所以「加锁 + 结算 + 落库」只能有一个入口。
     *
     * <p>动作抛异常时<b>不落库</b>：结算结果会丢，但结算是幂等的，下次读取会重新算出来；
     * 反过来「失败了还落库」会让一次半途而废的操作留下永久痕迹。
     *
     * @param action 在锁内执行的动作。它可以直接改 {@code player} 与 {@code city}，
     *               也可以调用其它仓储（军队、背包）—— 那些的落库由 action 自己负责
     */
    public <T> T withSettledCity(String playerId,
                                 java.util.function.Function<CitySnapshot, T> action) {
        long now = timeService.serverNow();
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
            Ctx ctx = load(playerId, now);
            T result = action.apply(new CitySnapshot(
                    ctx.player(), ctx.city(), ctx.rules(), ctx.settlement(), now));
            players.save(ctx.player());
            cities.save(playerId, ctx.city(), ctx.cityVersion());
            return result;
        });
    }

    /**
     * 一次「加锁 + 结算」之后交给调用方的上下文。
     *
     * @param now 本次操作使用的服务端时刻。<b>调用方必须用它而不是再取一次时间</b> ——
     *            同一次操作里出现两个 now，会让「校验时用 A、落库时用 B」这类偏差有机可乘
     */
    public record CitySnapshot(PlayerSave player,
                               CityState city,
                               CityRules rules,
                               ResourceRateService.Settlement settlement,
                               long now) {
    }

    /**
     * 加速（B03 §3）。免费与付费共用同一入口，用 source 区分 —— 便于埋点与防刷。
     *
     * <p>四种来源的校验路径完全不同，但<b>都不允许绕过幂等与锁</b>：
     * AD 要过日限次、ITEM 要校验道具类型、GOLD 要先扣费、ALLIANCE/SQUAD 走帮助上限。
     * 共用入口的好处是「加速」这个动作在埋点里只有一种事件，来源作为维度，
     * 而不是四套各自为政的接口让数据分析对不上。
     */
    public SpeedUpResp speedUp(String playerId, SpeedUpReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        requireRequestId(req.requestId());
        if (req.buildingId() == null || req.buildingId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "buildingId 不得为空");
        }
        if (req.source() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "source 不得为空");
        }
        long now = timeService.serverNow();
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(req.requestId(), now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + req.requestId());
        }
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> doSpeedUp(playerId, req, now));
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    private SpeedUpResp doSpeedUp(String playerId, SpeedUpReq req, long now) {
        Ctx ctx = load(playerId, now);
        PlayerSave player = ctx.player();
        CityState city = ctx.city();
        CityRules rules = ctx.rules();
        BuildingInstance instance = city.building(req.buildingId());

        if (!instance.isUpgrading()) {
            throw new BizException(ErrorCode.CITY_UPGRADING,
                    "建筑未在升级中，无需加速: " + req.buildingId());
        }
        if (instance.remainingSeconds(now) <= 0L) {
            throw new BizException(ErrorCode.CITY_UPGRADING,
                    "升级已完成，请领取而不是加速: " + req.buildingId());
        }

        long reduced = switch (req.source()) {
            case AD -> speedUpByAd(playerId, city, req.buildingId(), rules, now);
            case ALLIANCE, SQUAD -> speedUpByHelp(city, req.buildingId(), now);
            // /city/speedUp 一次只用 1 个道具；一次用 N 个走 /item/use（B04 §4 的 count 语义）
            case ITEM -> speedUpByItem(ctx, req.buildingId(), req.itemId(), 1L, now);
            case GOLD -> speedUpByGold(player, city, req.buildingId(), now);
        };

        players.save(player);
        cities.save(playerId, city, ctx.cityVersion());
        long remaining = city.building(req.buildingId()).remainingSeconds(now);
        LOG.info("建筑加速 playerId={} building={} 来源={} 提前={}秒 剩余={}秒",
                playerId, req.buildingId(), req.source(), reduced, remaining);
        return new SpeedUpResp(req.buildingId(), reduced, remaining, remaining <= 0L);
    }

    private long speedUpByAd(String playerId, CityState city, String buildingId, CityRules rules, long now) {
        long limit = rules.adSpeedupDailyLimit();
        String dayKey = dayKey(now);
        if (!dailyCounter.tryConsume(SCOPE_AD_SPEEDUP, playerId, dayKey, limit)) {
            throw new BizException(ErrorCode.RATE_LIMITED,
                    "广告加速今日已达上限 " + limit + " 次，明日 " + dayKey + " 之后重置");
        }
        try {
            return city.speedUp(buildingId, rules.adSpeedupSeconds(), now);
        } catch (RuntimeException e) {
            // 加速失败必须退还当日次数，否则玩家会因为一次失败白白损失一次免费机会
            dailyCounter.refund(SCOPE_AD_SPEEDUP, playerId, dayKey);
            throw e;
        }
    }

    /**
     * 联盟/小队帮助加速（B03 §3）：每次 1%、单个目标最多被帮掉 50%。
     *
     * <p><b>这两个数只有 global 一个家</b>（2026-09-11 口径裁决）：{@code ALLIANCE_HELP_SPEED_BONUS}
     * 与 {@code HELP_SPEEDUP_TOTAL_CAP} —— 与社交侧那本 {@code HelpLedger} 用的是同一对。
     * 原先这里读的是 {@code city_rule} 的 20% + 联盟科技加成：同一件事两个家、两个数，
     * 已在同一次裁决里作废（见那两行配置的 why）。
     *
     * <p><b>最后一次只发剩余额度</b>而不是整份 1%：否则"50% 封顶"会被最后一步超过去。
     * 已用额度按 {@code helpCount} 算（它由 {@code CityState.speedUpByRatio} 负责记账）。
     */
    private long speedUpByHelp(CityState city, String buildingId, long now) {
        long per = configs.fixedParam("ALLIANCE_HELP_SPEED_BONUS");
        long cap = configs.fixedParam("HELP_SPEEDUP_TOTAL_CAP");
        long used = FixedPoint.mul(FixedPoint.of(city.building(buildingId).helpCount()), per);
        long granted = Math.min(per, cap - used);
        if (granted <= 0L) {
            throw new BizException(ErrorCode.RATE_LIMITED,
                    "帮助加速已达上限（单个目标最多被帮掉 " + FixedPoint.format(cap) + "）");
        }
        return city.speedUpByRatio(buildingId, granted, now);
    }

    /**
     * 用加速道具加速某建筑（B04 §4）。
     *
     * <p><b>幂等键与玩家锁都在本方法这里</b>，不在 {@code BagAppService.useItem}：那一边对 SPEEDUP 类
     * 在 {@code tryAcquire} <b>之前</b>就分流返回了（谁改状态谁占键），所以这一路如果也不占，
     * 症状就是弱网重投一次 /item/use 白扣一张付费建造令 —— 钱货两讫的动作没有去重，
     * 在 B19 的口径里等价于一次可复现的资产损失。训练那一路与研究那一路本来就是各自占键的，
     * 这次把建造这一路补齐到同一条纪律上。
     *
     * <p>锁也一起补：原先它在锁外做「load → 改 → 带版本条件 save」，并发时靠版本冲突失败来兜底，
     * 而失败之后<b>道具已经扣了</b>（{@link #speedUpByItem} 先扣后加速，退还只在同一方法内）。
     *
     * <p><b>内部只做一次 {@link #load}</b>：仓储返回的是存档副本，二次 load 会拿到另一个
     * CityState 实例，外层已经改过的东西会被后写的那份覆盖掉。
     *
     * @param count     一次使用几个道具。扣减是原子的，持有量不足时整体失败、一个都不扣（B04 验收 10）
     * @param requestId 客户端带的幂等键（/item/use 的契约本来就要求它 —— 这次改动只是让它真的起作用）
     */
    public SpeedUpResp useSpeedUpItem(String playerId, String buildingId, String itemId,
                                      long count, long now, String requestId) {
        requireRequestId(requestId);
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + requestId);
        }
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS,
                    () -> doUseSpeedUpItem(playerId, buildingId, itemId, count, now));
        } catch (RuntimeException e) {
            // 副作用没产生，必须释放键，否则玩家重试被永久挡在门外
            idempotency.release(requestId);
            throw e;
        }
    }

    private SpeedUpResp doUseSpeedUpItem(String playerId, String buildingId, String itemId,
                                         long count, long now) {
        Ctx ctx = load(playerId, now);
        BuildingInstance instance = ctx.city().building(buildingId);
        if (!instance.isUpgrading()) {
            throw new BizException(ErrorCode.CITY_UPGRADING,
                    "建筑未在升级中，无需加速: " + buildingId);
        }
        if (instance.remainingSeconds(now) <= 0L) {
            throw new BizException(ErrorCode.CITY_UPGRADING,
                    "升级已完成，请领取而不是加速: " + buildingId);
        }
        long reduced = speedUpByItem(ctx, buildingId, itemId, count, now);
        players.save(ctx.player());
        cities.save(playerId, ctx.city(), ctx.cityVersion());
        long remaining = ctx.city().building(buildingId).remainingSeconds(now);
        LOG.info("道具加速 playerId={} building={} item={} 个数={} 提前={}秒 剩余={}秒",
                playerId, buildingId, itemId, count, reduced, remaining);
        return new SpeedUpResp(buildingId, reduced, remaining, remaining <= 0L);
    }

    /**
     * 道具加速的核心：扣库存 → 应用效果 → 由调用方落库。
     *
     * <p><b>顺序是「先扣道具再加速」，且加速失败必须退还道具。</b>
     * 反过来（先加速再扣）在库存不足时会让玩家白拿一次加速 —— 那是可以直接刷的漏洞。
     * 而先扣再加速的唯一风险是「扣了但加速失败」，用 try/catch 退还就能闭合。
     * 两害相权：一个能被玩家主动触发的漏洞，比一个需要异常路径才出现的短暂不一致严重得多。
     *
     * <p>整个过程在玩家锁内，因此扣减与落库之间不会有并发插入。
     */
    private long speedUpByItem(Ctx ctx, String buildingId, String itemId, long count, long now) {
        if (itemId == null || itemId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "加速道具必须给出 itemId");
        }
        if (count <= 0L) {
            throw new BizException(ErrorCode.PARAM_INVALID, "道具个数必须为正，实际=" + count);
        }
        ItemCfg item = configs.get(ItemCfg.class, itemId);
        if (item.type() != ItemCfg.Type.SPEEDUP) {
            throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                    "道具 " + itemId + " 不是加速道具（类型=" + item.type() + "）");
        }
        if (item.effectKind() != ItemCfg.EffectKind.REDUCE_BUILD_SECONDS) {
            // 明确拒绝而不是静默按建造加速处理：秒数长得一样，接错域的表现是「扣了研究令却减了建造时间」
            throw new BizException(ErrorCode.NOT_IMPLEMENTED,
                    "道具 " + itemId + " 的效果类型 " + item.effectKind() + " 不归建造加速"
                            + "（训练加速走 /item/use、研究加速走 /tech/speedUp 或 /item/use）");
        }

        String playerId = ctx.player().playerId();
        // 扣减走 RewardPorts.Bag（背包写入的唯一入口），不自己持有 Inventory 副本：
        // 副本 + 旧版本号在中间夹着一次发奖时会覆盖掉刚发出去的道具。
        // remove 是原子的：持有量不足时返回 0 且完全不扣，绝不扣成负数（B04 验收 10）
        if (bagPort.remove(playerId, itemId, count) == 0L) {
            throw new BizException(ErrorCode.ITEM_NOT_ENOUGH,
                    "需要 " + itemId + " " + count + " 个，当前持有 "
                            + bagPort.countOf(playerId, itemId) + " 个");
        }
        // 一次用 N 个 ⇒ 提前 N × 单个效果秒数；city.speedUp 会按剩余时间截断，不会出现负数
        try {
            return ctx.city().speedUp(buildingId, item.effectValue() * count, now);
        } catch (RuntimeException e) {
            // 加速失败必须退还道具，否则玩家因为一次失败白白损失道具
            long back = bagPort.add(playerId, itemId, count);
            if (back < count) {
                LOG.error("【加速道具退还失败】playerId={} item={} 应退={} 实退={} traceId={} "
                                + "退还不足说明玩家真的丢了道具，必须人工补偿",
                        playerId, itemId, count, back, TraceContext.traceId());
            }
            throw e;
        }
    }

    private long speedUpByGold(PlayerSave player, CityState city, String buildingId, long now) {
        long cost = cityParam("city_rule_gold_speedup_cost").asLong();
        long seconds = cityParam("city_rule_gold_speedup_seconds").asLong();
        // 金币余额已经是结算后的值（load() 里做过），这里不再自己推进时间
        PlayerResourceState gold = player.resource(ResourceIds.GOLD);
        if (gold.current() < cost) {
            throw new BizException(ErrorCode.RESOURCE_NOT_ENOUGH,
                    "需要 GOLD " + cost + "，当前 GOLD " + gold.current());
        }
        // 先加速再扣费：加速可能因剩余时间不足而被截断，
        // 若先扣费再发现只能提前 10 秒，就得处理退款，多一条失败路径
        long reduced = city.speedUp(buildingId, seconds, now);
        player.putResource(ResourceIds.GOLD, new PlayerResourceState(
                gold.current() - cost, gold.cap(), gold.protectedAmount(),
                gold.perHour(), gold.lastSettle()));
        return reduced;
    }

    /** 取消升级，返还 60% 资源（B03 §2 / 验收 4）。 */
    public CityCancelResp cancel(String playerId, CityCancelReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        requireRequestId(req.requestId());
        if (req.buildingId() == null || req.buildingId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "buildingId 不得为空");
        }
        long now = timeService.serverNow();
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(req.requestId(), now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + req.requestId());
        }
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> doCancel(playerId, req, now));
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    private CityCancelResp doCancel(String playerId, CityCancelReq req, long now) {
        Ctx ctx = load(playerId, now);
        PlayerSave player = ctx.player();
        CityState city = ctx.city();
        CityRules rules = ctx.rules();
        BuildingInstance instance = city.building(req.buildingId());

        // 返还额按「升到当前目标等级所花的消耗」重算：CityState 不保存历史花费，
        // 而花费完全由配置与等级决定，重算比存一份副本更可靠（配置热更后也不会拿到过期数字）
        BuildingCfg cfg = configs.get(BuildingCfg.class, instance.configId());
        Map<String, Long> spent = upgradeCost(cfg, instance.level() + 1);
        Map<String, Long> refund = city.cancelUpgrade(req.buildingId(), spent, rules);

        for (Map.Entry<String, Long> e : refund.entrySet()) {
            grantResource(player, e.getKey(), e.getValue());
        }
        players.save(player);
        cities.save(playerId, city, ctx.cityVersion());
        // 取消之后目标就不在了，请求也不能留在别人的可帮列表里 ——
        // 留着就会有人帮一栋已经停工的楼：额度与事件都真的发生，而时长一秒都不会少
        helpRequests.withdraw(playerId, req.buildingId());
        LOG.info("取消升级 playerId={} building={} 返还={}", playerId, req.buildingId(), refund);
        return new CityCancelResp(req.buildingId(), toAmountList(refund));
    }

    /**
     * 收割已到点的升级，并结算产量（B03 §2）。
     *
     * <p>产出是连续惰性结算的：建筑按等级计入每小时产率，升级中不计，完成时刻起按新等级计。
     * 因此「收割」与「领产出」是同一个动作的两面 —— 收割让新等级生效，结算把这段时间的产量入账。
     * 返回的 output 就是本次结算实际入账的量。
     *
     * <p>离线累积的上限由仓储容量承担（满仓即停产），不再另设追溯时长上限：
     * 两套限制同一件事的机制并存，必然出现「按哪个算」的分歧。
     */
    public CityCollectResp collect(String playerId, CityCollectReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        requireRequestId(req.requestId());
        long now = timeService.serverNow();
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(req.requestId(), now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + req.requestId());
        }
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> doCollect(playerId, req, now));
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    private CityCollectResp doCollect(String playerId, CityCollectReq req, long now) {
        Ctx ctx = load(playerId, now);
        PlayerSave player = ctx.player();
        CityState city = ctx.city();
        ResourceRateService.Settlement settlement = ctx.settlement();

        String requested = req.buildingId();
        if (requested != null && !requested.isBlank()) {
            BuildingInstance target = city.building(requested);
            // load() 已经把到点的升级收割掉了，所以「还在升级中」只可能是「没到点」
            if (target.isUpgrading()) {
                throw new BizException(ErrorCode.CITY_UPGRADING,
                        "建筑尚未完成升级: " + requested
                                + "，剩余 " + target.remainingSeconds(now) + " 秒");
            }
        }

        List<BuildingView> collected = new ArrayList<>();
        if (requested != null && !requested.isBlank()) {
            collected.add(toView(city.building(requested), now));
        } else {
            for (String id : settlement.harvested()) {
                collected.add(toView(city.building(id), now));
            }
        }

        players.save(player);
        cities.save(playerId, city, ctx.cityVersion());
        LOG.info("收割升级 playerId={} 本次收割={} 入账产出={}",
                playerId, settlement.harvested(), settlement.credited());
        return new CityCollectResp(collected, toAmountList(settlement.credited()), now);
    }

    // ---------- 内部辅助 ----------

    private PlayerSave requirePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
        return players.findByPlayerId(playerId)
                .orElseThrow(() -> new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId=" + playerId));
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "requestId 不得为空");
        }
    }

    /**
     * 发放资源（取消返还等）：受容量上限约束，超出部分丢弃。
     *
     * <p>调用方必须已经过 {@link #load} 的分段结算 —— 本方法只负责入账，
     * 不再自己推进时间。结算责任分散到多处是「产率与面板对不上」这类 bug 的温床。
     *
     * <p>溢出部分丢弃而不是转邮件：取消返还是玩家主动操作的结果，他自己能看到返还数额，
     * 与「离线发奖玩家不知情」不是一回事。真正需要转邮件的是 RewardService 那条路径（B04 验收 2）。
     */
    private void grantResource(PlayerSave player, String resourceType, long amount) {
        if (amount <= 0L) {
            return;
        }
        PlayerResourceState s = player.resource(resourceType);
        long next = Math.min(s.cap(), s.current() + amount);
        if (next < s.current() + amount) {
            LOG.info("发放资源超出容量上限，多余部分丢弃 playerId={} resource={} 请求={} 实发={}",
                    player.playerId(), resourceType, amount, next - s.current());
        }
        player.putResource(resourceType, new PlayerResourceState(
                next, s.cap(), s.protectedAmount(), s.perHour(), s.lastSettle()));
    }

    private Map<ResourceType, ResourceStateView> toResourceMap(Map<String, PlayerResourceState> settled) {
        Map<ResourceType, ResourceStateView> out = new LinkedHashMap<>();
        settled.forEach((type, s) -> out.put(ResourceType.valueOf(type),
                new ResourceStateView(s.current(), s.cap(), s.protectedAmount(), s.perHour(), s.lastSettle())));
        return out;
    }

    private static BuildingView toView(BuildingInstance b, long now) {
        // startedAt 与 totalSeconds 一起下发：进度是"响应那一刻"的快照，而倒计时在客户端本地走，
        // 只给快照会让进度条停在原地（时间在动、百分比不动）。客户端用这两个字段复刻
        // progressFixed 的同一条公式：elapsed = nowServer - startedAt，progress = elapsed / total。
        return new BuildingView(b.instanceId(), b.configId(), b.level(), b.gridX(), b.gridY(),
                com.ironoath.web.dto.generated.BuildingStatus.valueOf(b.status().name()),
                b.upgradeFinishAt(), b.remainingSeconds(now), b.progressFixed(now),
                b.upgradeStartedAt(), b.upgradeTotalSeconds(), b.helpCount());
    }

    /**
     * 日限次的日期键。
     *
     * <p>口径只写在 game-common 的 {@code DayKey} 一处（当前按 UTC+8 切日，与
     * {@code city_rule_daily_reset_hour_utc} 之间的一致性由 DailyResetZoneParityTest 钉住）。
     * 两处各写一份的后果不是代码重复，而是「一个重置了、另一个没重置」——
     * 那种 bug 只在跨时区部署或夏令时切换时出现，几乎无法复现。
     */
    private static String dayKey(long nowMs) {
        // 实现已抽到 game-common 的 DayKey：体力购买的日限次也要用同一个键
        return com.ironoath.common.time.DayKey.of(nowMs);
    }

    static List<ResourceAmount> toAmountList(Map<String, Long> amounts) {
        List<ResourceAmount> list = new ArrayList<>(amounts.size());
        for (Map.Entry<String, Long> e : amounts.entrySet()) {
            list.add(new ResourceAmount(
                    com.ironoath.web.dto.generated.ResourceType.valueOf(e.getKey()), e.getValue()));
        }
        return list;
    }
}
