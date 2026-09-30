package com.ironoath.web.service;

import com.ironoath.common.BizException;
import com.ironoath.common.num.Rates;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.log.TraceContext;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.ConfigException;
import com.ironoath.config.cfg.MapmonsterCfg;
import com.ironoath.config.cfg.UnitCfg;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.army.TierSplit;
import com.ironoath.core.army.UnitKind;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchCalculator;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;
import com.ironoath.core.scout.ScoutReport;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.FogOfWar;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.dto.generated.CollectedEntry;
import com.ironoath.web.dto.generated.GatherResp;
import com.ironoath.web.dto.generated.MarchAction;
import com.ironoath.web.dto.generated.MarchIdReq;
import com.ironoath.web.dto.generated.MarchListResp;
import com.ironoath.web.dto.generated.MarchReq;
import com.ironoath.web.dto.generated.MarchResp;
import com.ironoath.web.dto.generated.MarchUnit;
import com.ironoath.web.dto.generated.MarchView;
import com.ironoath.web.dto.generated.RecallResp;
import com.ironoath.web.dto.generated.ScoutReq;
import com.ironoath.web.reward.ServerSeedSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 职责：行军应用服务 —— 出征、到达、召回、采集、侦查（B07 §2/§3）。
 * 依赖：game-core 的行军/世界/军队聚合与端口、game-config（unit / mapmonster / global）。
 *
 * <p><b>行军是「一条带状态的记录」，不是一个定时器</b>（B07 头号红线）。
 * 到达由 {@link MarchDueQueue} 的到期扫描触发，而扫描由请求驱动 ——
 * 与产出结算的惰性推进是同一套纪律（B03 验收 9：服务端无任何常驻定时器）。
 *
 * <p><b>{@link #processDue} 在每个读写入口的开头被调用</b>。这不是可选的优化：
 * 没有它，一支到点的行军会一直停在 MARCHING，玩家看到的队伍永远卡在半路，
 * 而服务端日志里一切正常（没有任何东西失败，只是没有人去推进它）。
 *
 * <p><b>攻击类行军（ATTACK）已接通两条路径</b>，按目标类型分流（见 {@link #resolveAttack}）：
 * 野怪走 {@code MonsterBattleService}，玩家城走 {@code PlayerCityBattleService}，
 * 其余目标类型（资源点、空地、联盟建筑）明确拒绝。
 *
 * <p>曾经的口径障碍已在 B09 解决：战斗内核要求「每兵种一份属性」，
 * 而一支行军可能同时带 T1 与 T4 步兵。折算口径定为<b>按数量加权平均</b>，
 * 且已证明是精确等价而不是近似 —— 内核的有效攻击与有效防御都对「数量 × 属性」线性，
 * 所以 {@code BattleArmyFactory} 的断言是「折算后总属性质量守恒」。
 * 在那之前这里对所有 ATTACK 一律抛 NOT_IMPLEMENTED，
 * 因为宁可明确拒绝，也不要先放出去一个口径错误的战斗：
 * 战斗数值一旦被玩家体验过就收不回来了。
 */
@Service
public class MarchAppService {

    private static final Logger LOG = LoggerFactory.getLogger(MarchAppService.class);

    private static final long LOCK_TIMEOUT_MS = 3000L;

    /** 一次到期扫描最多处理多少支行军。分批让单次请求的耗时可预测（B07 验收 2）。 */
    private static final int DUE_BATCH_LIMIT = 200;

    private final ConfigRegistry configs;
    private final MarchRepository marches;
    private final MarchDueQueue dueQueue;
    private final WorldRepository world;
    private final ArmyRepository armies;
    private final HeroAppService heroAppService;
    private final RewardService rewardService;
    private final PlayerLock playerLock;
    private final IdempotencyStore idempotency;
    private final TimeService timeService;
    private final ServerSeedSource seeds;
    private final WorldAppService worldAppService;
    private final ArmyAppService armyAppService;
    private final AttackGuardService attackGuardService;
    private final com.ironoath.web.battle.MonsterBattleService monsterBattleService;
    private final com.ironoath.web.battle.PlayerCityBattleService playerCityBattleService;
    /**
     * 集结的到点结算与出发撤销。
     *
     * <p><b>依赖方向只能是行军 → 社交，不能反</b>：出发要建行军，所以驱动点在这里；
     * 而 {@code SocialAppService} 一旦也依赖本类，Spring 上下文直接起不来。
     * 这就是 {@code SocialAppService.expireIfDue} 只管「人数不足退款」、
     * 把「人数够就出发」留给本类的原因。
     */
    private final SocialAppService socialAppService;
    /** 集结本体：出发时要跨组织扫到点的那几个，到家时要按参与者承诺把幸存兵力分回各人。 */
    private final com.ironoath.web.social.SocialStore socialStore;
    /** 只读：闭城死守的「期间不可出兵采集」限制记在玩家 PVP 侧状态上。仓储不依赖 service，无环。 */
    private final com.ironoath.core.player.PlayerRepository players;
    /** 只读：流亡迁城的冷却是它定的规则，本类只负责把「下一次可用时刻」随列表一起下发，不重算第二份。 */
    private final ExileAppService exileAppService;

    /** 任务进度的事件入口（B12 §1）：采集到的资源量与击杀的野怪由本服务上报。 */
    private final com.ironoath.web.quest.QuestEvents questEvents;
    /**
     * 攻击频控的记账端（B11 §五）。守卫在 {@code AttackGuardService} 判「还有额度吗」，
     * 这里在<b>仗真的打起来</b>之后才记一笔 —— 于是「出门又召回」与「半路目标没了」
     * 不会白吃真人的被攻击额度。
     */
    private final com.ironoath.web.bot.BotAttackLimiter botAttackLimiter;
    /** 科技加成的唯一读取口（B20 块①）：行军速度与负载上限都从它来。 */
    private final com.ironoath.web.tech.TechEffects techEffects;
    /** 国家科技那一份（B20 块③）：与个人的**相加**后作用一次（§五④）。 */
    private final com.ironoath.web.nation.NationTechBonuses nationTechBonuses;
    /** 国策加成（乘区 G 的非战斗那半：产出与行军速度）。与国家科技同类相加，见消费点那一行。 */
    private final com.ironoath.web.nation.NationPolicyBonuses policyBonuses;

    public MarchAppService(ConfigRegistry configs, MarchRepository marches, MarchDueQueue dueQueue,
                           WorldRepository world, ArmyRepository armies,
                           HeroAppService heroAppService, RewardService rewardService,
                           PlayerLock playerLock, IdempotencyStore idempotency,
                           TimeService timeService, ServerSeedSource seeds,
                           WorldAppService worldAppService, ArmyAppService armyAppService,
                           AttackGuardService attackGuardService,
                           com.ironoath.web.battle.MonsterBattleService monsterBattleService,
                           com.ironoath.web.battle.PlayerCityBattleService playerCityBattleService,
                           SocialAppService socialAppService,
                           com.ironoath.web.social.SocialStore socialStore,
                           com.ironoath.core.player.PlayerRepository players,
                           ExileAppService exileAppService,
                           com.ironoath.web.quest.QuestEvents questEvents,
                           com.ironoath.web.bot.BotAttackLimiter botAttackLimiter,
                           com.ironoath.web.tech.TechEffects techEffects,
                          com.ironoath.web.nation.NationTechBonuses nationTechBonuses,
                              com.ironoath.web.nation.NationPolicyBonuses policyBonuses) {
        this.configs = configs;
        this.marches = marches;
        this.dueQueue = dueQueue;
        this.world = world;
        this.armies = armies;
        this.heroAppService = heroAppService;
        this.rewardService = rewardService;
        this.playerLock = playerLock;
        this.idempotency = idempotency;
        this.timeService = timeService;
        this.seeds = seeds;
        this.worldAppService = worldAppService;
        this.armyAppService = armyAppService;
        this.attackGuardService = attackGuardService;
        this.monsterBattleService = monsterBattleService;
        this.playerCityBattleService = playerCityBattleService;
        this.socialAppService = socialAppService;
        this.socialStore = socialStore;
        this.players = players;
        this.exileAppService = exileAppService;
        this.questEvents = questEvents;
        this.botAttackLimiter = botAttackLimiter;
        this.techEffects = techEffects;
        this.nationTechBonuses = nationTechBonuses;
        this.policyBonuses = policyBonuses;
    }

    // ---------- 出征 ----------

    /** 发起一次行军（含侦查：侦查就是 action=SCOUT 的行军）。 */
    public MarchResp send(String playerId, MarchReq req) {
        validateRequest(playerId, req == null ? null : req.requestId());
        if (req.units() == null || req.units().isEmpty()) {
            throw new BizException(ErrorCode.MARCH_NO_TROOP, "行军必须带兵");
        }
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> doSend(playerId, req, now));
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    private MarchResp doSend(String playerId, MarchReq req, long now) {
        processDue(playerId, now);
        Coord home = worldAppService.homeOf(playerId);
        Coord target = requireCoord(req.toX(), req.toY());
        if (target.equals(home)) {
            throw new BizException(ErrorCode.WORLD_TARGET_INVALID, "不能向自己的城行军");
        }

        long active = marches.activeCountOf(playerId);
        long maxConcurrent = configs.longParam("MARCH_MAX_CONCURRENT");
        if (active >= maxConcurrent) {
            throw new BizException(ErrorCode.MARCH_QUEUE_FULL,
                    "同时出征的队伍已达上限 " + maxConcurrent + " 支，当前 " + active
                            + " 支。可开启额外名额（B15 特权）");
        }

        WorldGenerator.Cell cell = worldAppService.cellAt(target);
        March.Action action = March.Action.valueOf(req.action().name());
        March.TargetType targetType = resolveTargetType(cell, target);
        // 玩家自身状态的禁令排在目标校验之前：闭城期间换任何目标都没用，
        // 先说「目标不是资源点」会让人以为换个格子就能采 —— 那是句会误导人的真话
        requireNotClosedForGather(playerId, action, now);
        validateAction(target, action, targetType, playerId, now, targetEntityId(cell, target));

        // 武将校验必须在扣兵之前：非法 heroId 若放到战斗结算时才炸，
        // 那是在到期扫描里抛 IllegalStateException，行军会永久卡在目标前（理由见 requireOnMarch）
        List<String> heroes = req.heroes() == null ? List.of() : req.heroes();
        heroAppService.requireOnMarch(playerId, heroes);

        // 解析兵种：数量、速度、负载全部来自 unit 表，代码里没有任何数字。
        // 按 unitId（含阶级）记，不按兵种记 —— 按兵种记会在归队时丢掉阶级信息，
        // 于是一次出征就能把 T5 兵降成 T1（B07 留下的口径缺口，这里一并修掉）
        Map<String, Long> byUnitId = new LinkedHashMap<>();
        Map<String, Integer> speeds = new LinkedHashMap<>();
        Map<String, Long> loads = new LinkedHashMap<>();
        long totalUnits = 0L;
        for (MarchUnit mu : req.units()) {
            UnitCfg unit = requireUnit(mu.unitId());
            if (mu.count() <= 0L) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "兵种数量必须为正：" + mu.unitId() + " × " + mu.count());
            }
            byUnitId.merge(mu.unitId(), mu.count(), Long::sum);
            speeds.put(mu.unitId(), (int) unit.speed());
            loads.put(mu.unitId(), unit.load());
            totalUnits += mu.count();
        }
        if (totalUnits <= 0L) {
            throw new BizException(ErrorCode.MARCH_NO_TROOP, "行军必须带至少 1 个兵");
        }
        // 队伍速度与负载上限交给 MarchCalculator，不在这里再算一遍：
        // 「取最慢兵种」与「Σ数量×单位负载」这两条规则各自只能有一个实现，
        // 两处各写一份的后果是行军时长与采集运力对不上，而两者都在同一个响应里下发
        int slowest = MarchCalculator.teamSpeed(byUnitId, speeds);
        long loadCap = MarchCalculator.loadCap(byUnitId, loads);

        // 从军营扣兵：行军中的兵不在城里，也不能被再次派出。
        // 必须先收割队列 —— 训练/治疗是惰性状态，不收割就会出现「刚训完的兵派不出去」，
        // 玩家收到「兵力不足」却看不见任何不足，这是最难自助排查的一类失败
        ArmyState army = armyAppService.settledArmy(playerId, now);
        long armyVersion = armies.versionOf(playerId);
        for (Map.Entry<String, Long> entry : byUnitId.entrySet()) {
            long deducted = army.deduct(entry.getKey(), entry.getValue());
            if (deducted < entry.getValue()) {
                // 扣到一半发现不够：必须把已扣的还回去，否则玩家的兵凭空消失
                byUnitId.forEach((unitId, count) -> army.add(unitId, count));
                throw new BizException(ErrorCode.UNIT_NOT_ENOUGH,
                        "兵种 " + entry.getKey() + " 需要 " + entry.getValue()
                                + " 个，当前只有 " + (deducted) + " 个可用");
            }
        }

        March march = createMarch(playerId, home, target, byUnitId, heroes, targetType,
                targetEntityId(cell, target), action, slowest, loadCap, now,
                null,     // 个人出征不属于任何集结；到家时按普通行军全额归队
                () -> armies.save(playerId, army, armyVersion),
                () -> refundTroops(playerId, army, armyVersion, byUnitId));
        int distance = home.distanceTo(target);
        // 时长从行军自己反推，不再算第二遍：MarchCalculator.durationSeconds 是唯一实现，
        // 这里重新调一次的话，万一两次调用之间配置被热更，响应里的时长就会与实际到达时刻不一致
        long duration = (march.arriveAt() - now) / 1000L;
        return new MarchResp(toView(march, now), distance, duration, now);
    }

    /** 这个玩家的科技那一位。读不到存档按「一行都没研究」，与战斗装配同一条读法。 */
    private com.ironoath.core.player.PlayerTech techOf(String playerId) {
        return players.findByPlayerId(playerId)
                .map(com.ironoath.core.player.PlayerSave::tech)
                .orElseGet(com.ironoath.core.player.PlayerTech::empty);
    }

    /**
     * 建一支行军、入库、登记到期队列、解锁沿途迷雾。
     *
     * <p><b>本方法刻意不含四件事</b>：幂等键、玩家锁、名额预检、目标校验，也<b>不含扣兵</b>。
     * 那些是「个人出征」这条路径的职责（见 {@link #doSend}）；
     * 集结出发要用的是同一套建行军逻辑，但它一个都不能要 ——
     * 成员的兵在加入集结时就已经锁定扣走了，再扣一次就是双重扣兵。
     *
     * <p><b>抽出来而不是复制一份的理由</b>：队伍速度、负载上限、行军时长、迷雾解锁、
     * 到期登记这五件事如果各写一份，漂移的症状是「集结行军比普通行军快一点」
     * 或「集结不解锁迷雾」——两者都不会让任何测试变红，只会被玩家当成 bug 报上来。
     *
     * @param commit   入库成功后要提交的副作用（个人出征是「保存扣过兵的军队」，集结是空操作）
     * @param rollback 任一步失败时的回滚（个人出征是「把兵退回去」，集结是撤销出发）
     * @param rallyId  集结合并行军的归属；普通行军传 null
     */
    private March createMarch(String playerId, Coord home, Coord target, Map<String, Long> byUnitId,
                              List<String> heroes, March.TargetType targetType, String targetId,
                              March.Action action, int teamSpeed, long loadCap, long now, String rallyId,
                              Runnable commit, Runnable rollback) {
        // 科技加成只在这一个地方折进来：个人出征与集结共用本方法（理由见方法注释），
        // 所以「集结队伍比普行快一点」这类漂移在结构上就不可能出现。
        // 集结按发起者的科技算 —— 一支队伍只有一份速度与负载，这与联盟科技在战斗里同一口径。
        com.ironoath.core.player.PlayerTech tech = techOf(playerId);
        long carriedCap = Rates.scaleUp(loadCap, techEffects.loadCapacityPercent(tech));
        int distance = home.distanceTo(target);
        // §五④：行军速度同样是"同类相加、作用一次" —— 国家那一份与个人的相加后交给
        // MarchCalculator 的 speedBonusFixed 那一位（它内部是 ÷ (1 + 合计)）；分两处折就会漂
        long marchSpeedFixed = techEffects.marchSpeedPercent(tech)
                + nationTechBonuses.marchSpeedPercent(playerId)
                + policyBonuses.marchSpeedPercent(playerId);
        long duration = MarchCalculator.durationSeconds(distance, teamSpeed,
                configs.fixedParam("MARCH_SECONDS_PER_TILE"), marchSpeedFixed);
        String marchId = "march_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        March march;
        try {
            march = new March(marchId, playerId, home, target, now, now + duration * 1000L,
                    byUnitId, heroes, carriedCap, teamSpeed, targetType, targetId, action);
            // 必须在入库之前绑定：仓储读写都返回副本，入库之后再改，
            // 已入库的那份副本看不到。而中间只要插进一次到期扫描，
            // 到家时就会按「普通行军」处理 —— 全部幸存兵力记到发起人名下，
            // 成员的兵有去无回。那正是返程分兵要修的 bug，不能由绑定顺序决定它出不出事
            if (rallyId != null) {
                march.attachToRally(rallyId);
            }
        } catch (RuntimeException e) {
            rollback.run();
            throw e;
        }
        try {
            if (!marches.insertIfAbsent(march)) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, "行军 id 冲突: " + marchId);
            }
            dueQueue.schedule(marchId, march.arriveAt());
            commit.run();
        } catch (RuntimeException e) {
            // 撤销顺序与建立顺序相反：先摘到期登记再删行军。
            // 反过来的话中间那一次到期扫描会取到一个已经不存在的 marchId
            dueQueue.cancel(marchId);
            marches.delete(marchId);
            rollback.run();
            throw e;
        }

        // 行军经过的 chunk 解锁迷雾（B07 §3：行军经过 / 侦查解锁）
        //
        // 这里带一次重试是有意的判断：迷雾只是"我见过哪些块"这种派生状态，而此刻**行军已经成立**。
        // 让一次撞锁把出征变成 500，玩家丢的是一整支队伍的行军体验，换来的只是"路径没点亮"；
        // 那几块在下一次经过时本来就会重新点亮。反过来，钱与账目（国库、订单）的撞锁绝不这样吞 ——
        // 那些是"少一次写入就少一笔钱"的对象，必须响。
        FogOfWar fog = world.fogOf(playerId);
        fog.explorePath(home, target, chunkSize());
        try {
            world.saveFog(playerId, fog, fog.version());
        } catch (IllegalStateException first) {
            FogOfWar again = world.fogOf(playerId);
            again.explorePath(home, target, chunkSize());
            try {
                world.saveFog(playerId, again, again.version());
            } catch (IllegalStateException stillLocked) {
                LOG.warn("出征路上解锁迷雾连续两次撞锁 playerId={} marchId={}：这一段路下次经过会重新点亮，"
                        + "而行军本身不受影响（所以这里不 rollback）", playerId, marchId);
            }
        }
        world.bumpChunkVersion(target.chunkKey(chunkSize()));

        LOG.info("出征 playerId={} marchId={} {}→{} 距离={}格 速度={} 时长={}秒 兵力={} 负载上限={} 目标={} 行为={}",
                playerId, marchId, home, target, distance, teamSpeed, duration,
                byUnitId, carriedCap, targetType, action);
        return march;
    }

    /**
     * 闭城死守期间不可<b>出兵采集</b>（B08 §5：那是这条出路的代价 —— 用产出换安全，
     * 不是免费的无敌窗口）。
     *
     * <p><b>只挡新的派遣</b>：已在途的采集队不受影响，把它们强行召回等于让道具额外惩罚
     * 玩家已经付过的成本；而 {@link #recall} 永远不经过这里 —— 否则玩家会被自己的盾
     * 锁死在野外，那是比"能采集"严重得多的坑。
     *
     * <p><b>只按字面禁 GATHER</b>：B08 §5 原文只写了「期间无法出兵采集」。
     * 侦查 / 驻扎 / 增援 是否也算"出兵"是一个产品口径问题，记在收口清单 #20 等裁决，
     * 不由实现者顺手扩大限制范围 —— 把限制放宽到它们会让闭城变成"什么都不能做"，
     * 那已经不是同一个道具了。
     */
    private void requireNotClosedForGather(String playerId, March.Action action, long now) {
        if (action != March.Action.GATHER) {
            return;
        }
        Long closedUntil = players.findByPlayerId(playerId)
                .map(save -> save.pvp().closedUntil()).orElse(null);
        if (closedUntil == null || closedUntil <= now) {
            return;
        }
        throw new BizException(ErrorCode.MARCH_STATE_INVALID,
                "闭城死守期间不可出兵采集，剩余 " + ((closedUntil - now + 59_999L) / 60_000L)
                        + " 分钟。期间仍可行军、侦查与召回在途队伍；免战本身已让任何人打不了你");
    }

    /** 侦查：B07 §3 要求「派侦察兵、消耗时间」，所以它就是一支 action=SCOUT 的行军。 */
    public MarchResp scout(String playerId, ScoutReq req) {
        validateRequest(playerId, req == null ? null : req.requestId());
        if (req.units() == null || req.units().isEmpty()) {
            throw new BizException(ErrorCode.MARCH_NO_TROOP, "侦查必须派侦察兵");
        }
        return send(playerId, new MarchReq(req.requestId(), req.toX(), req.toY(),
                req.units(), List.of(), MarchAction.SCOUT));
    }

    // ---------- 召回 ----------

    /** 召回。返回耗时 = 已行军距离 / 速度，兵力零损失（B07 §2、验收 7）。 */
    public RecallResp recall(String playerId, MarchIdReq req) {
        validateRequest(playerId, req == null ? null : req.requestId());
        if (req.marchId() == null || req.marchId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "marchId 不得为空");
        }
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                processDue(playerId, now);
                March march = requireOwnMarch(playerId, req.marchId());
                long version = marches.versionOf(march.id());
                // 召回一支采集中的队伍时必须先结算已采集量（B09 验收 7：已采集部分不丢失）。
                // 此前这里直接 recall，队伍空着手回家，玩家采了一小时的东西凭空消失 ——
                // 不报错、不为负，只在资源账上表现为「我明明在采集，怎么什么都没多」
                long gathered = settleGathered(march, now);
                long returnAt;
                try {
                    returnAt = march.recall(now);
                } catch (IllegalStateException e) {
                    throw new BizException(ErrorCode.MARCH_STATE_INVALID, e.getMessage());
                }
                marches.save(march, version);
                // 改期而不是「取消 + 重新登记」：后者在两步之间有一个窗口，
                // 此时若正好有一次到期扫描，这支行军就会被漏掉（B07 验收 2：无漏触发）
                dueQueue.reschedule(march.id(), returnAt);
                long seconds = Math.max(0L, (returnAt - now) / 1000L);
                LOG.info("召回行军 playerId={} marchId={} 返程={}秒 到家于={} 兵力零损失 结算采集={}",
                        playerId, march.id(), seconds, returnAt, gathered);
                return new RecallResp(toView(march, now), returnAt, seconds, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    // ---------- 采集 ----------

    /**
     * 结束采集并让队伍带着负载返程。
     *
     * <p>采集量 = 负载上限 × 已采集时长 / GATHER_FILL_SECONDS（封顶在负载上限）。
     * <b>按「采满所需时间」而不是「每秒采多少」定</b>：后者会让负载大的兵种采得更久，
     * 玩家就会把所有兵换成攻城器（load 40 全兵种最高）当运输队，兵种身份被采集效率抹平。
     */
    public GatherResp collectGather(String playerId, MarchIdReq req) {
        validateRequest(playerId, req == null ? null : req.requestId());
        if (req.marchId() == null || req.marchId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "marchId 不得为空");
        }
        long now = timeService.serverNow();
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                processDue(playerId, now);
                March march = requireOwnMarch(playerId, req.marchId());
                long version = marches.versionOf(march.id());
                if (march.status() != March.Status.GATHERING) {
                    throw new BizException(ErrorCode.MARCH_STATE_INVALID,
                            "只有采集中的行军能领取，当前状态=" + march.status());
                }
                long gathered = settleGathered(march, now);

                // 采到的资源要等到家才入账：路上被拦截就该连资源一起损失（B08/B13 的掠夺）。
                // 现在还没有拦截机制，但口径先定下来，否则以后加拦截就得改「资源什么时候到账」，
                // 而那会让玩家已经看到的数字发生变化
                String resourceType = resourceTypeAt(march.to());
                long returnAt = march.beginReturn(now, MarchCalculator.recallMillis(march, now));
                marches.save(march, version);
                dueQueue.reschedule(march.id(), returnAt);
                world.bumpChunkVersion(march.to().chunkKey(chunkSize()));

                List<CollectedEntry> collected = new ArrayList<>();
                if (resourceType != null && gathered > 0L) {
                    collected.add(new CollectedEntry(resourceType, gathered));
                }
                LOG.info("结束采集 playerId={} marchId={} 采集={}×{} 返程={}秒",
                        playerId, march.id(), gathered, resourceType,
                        (returnAt - now) / 1000L);
                if (resourceType != null && gathered > 0L) {
                    // 采集量按实际入账的量累加（B12 §1 的 GATHER_RESOURCE）
                    questEvents.progress(playerId, com.ironoath.core.quest.GoalType.GATHER_RESOURCE,
                            resourceType, gathered, now);
                }
                return new GatherResp(collected, returnAt, toView(march, now), now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    /**
     * 结算一支采集中行军已经采到的量，并装进负载。
     *
     * <p>采集量 = 负载上限 × 已采集时长 / GATHER_FILL_SECONDS（封顶在负载上限）。
     * <b>按「采满所需时间」而不是「每秒采多少」定</b>：后者会让负载大的兵种采得更久，
     * 玩家就会把所有兵换成攻城器（load 40 全兵种最高）当运输队，兵种身份被采集效率抹平。
     *
     * <p><b>所有会中断采集的路径都必须走这里</b>：主动领取（{@code collectGather}）、
     * 召回（{@code recall}），以及将来被打断（B13 抢夺采集）。
     * 少一条路径就是一种「资源凭空消失」—— 而它不报错、不产生负数，
     * 只在玩家的资源账上表现为「我明明在采集，怎么什么都没多」。
     *
     * @return 本次结算装载的量；非采集状态返回 0
     */
    private long settleGathered(March march, long now) {
        if (march.status() != March.Status.GATHERING || march.gatherStartAt() == null) {
            return 0L;
        }
        long elapsed = now - march.gatherStartAt();
        long gathered = MarchCalculator.gathered(march.loadCap(), elapsed,
                configs.longParam("GATHER_FILL_SECONDS"));
        march.addLoad(gathered);
        return gathered;
    }

    // ---------- 查询 ----------

    /** 我的全部行军 + 家坐标 + 免战与流亡冷却的到期时刻。 */
    public MarchListResp list(String playerId) {
        long now = timeService.serverNow();
        processDue(playerId, now);
        List<MarchView> views = new ArrayList<>();
        for (March march : marches.findByPlayerId(playerId)) {
            views.add(toView(march, now));
        }
        // 免战与冷却都在这里下发，而不是让客户端缓存上一次迁城的本地时刻：
        // 冷却是一条滚动窗口，两端各算一份必然漂（换设备/重装后本地那份直接丢了）
        com.ironoath.core.player.PlayerSave save = players.findByPlayerId(playerId).orElse(null);
        Long peaceUntil = save == null ? null : save.pvp().peaceUntil();
        Long nextExileAt = save == null ? null : exileAppService.nextExileAtOrNull(save, now);
        // 世界布局参数随行军列表一起下发（协议注释写了为什么不是 ViewportResp：
        // 客户端要用它们建立地图模型，而第一个 viewport 请求必须等模型建立 —— 鸡生蛋）。
        // 客户端从此不再把 512 / 32 / 9 镜像成常数，改表即两端同步（铁律 1）
        return new MarchListResp(views, toCoord(worldAppService.homeOf(playerId)),
                (int) configs.longParam("MARCH_MAX_CONCURRENT"), peaceUntil, nextExileAt,
                (int) configs.longParam("WORLD_SIZE"), chunkSize(),
                (int) configs.longParam("VIEWPORT_CHUNK_COUNT"), now);
    }

    // ---------- 到期处理（请求驱动，无定时器） ----------

    /**
     * 推进所有已到点的行军。
     *
     * <p><b>幂等</b>：处理完就从队列里 cancel 掉，所以同一支行军不会被处理两次
     * （B07 验收 2：1000 支队伍同时到期，无漏触发、无重复触发）。
     * 处理失败时<b>不 cancel</b>，让它留在队列里等下一次扫描重试 ——
     * 反之「先删再处理」会让失败的那支行军永远没人再管，
     * 玩家看到的是一支停在半路、既不前进也不到家的队伍。
     */
    public int processDue(String playerId, long now) {
        // 集结出发挂在同一次扫描上：服务端不跑定时器，行军、Bot、集结都靠请求驱动推进。
        // 放在开头而不是结尾，是为了让「出发」与「到点」共用同一个 now，
        // 不会出现这一轮用旧时刻判定到点、下一轮用新时刻判定出发
        departDueRallies(now);
        List<String> due = dueQueue.dueBefore(now, DUE_BATCH_LIMIT);
        int processed = 0;
        for (String marchId : due) {
            March march = marches.findById(marchId).orElse(null);
            if (march == null) {
                // 行军已被删除（到家后清理），队列里的是残留记录，直接撤销
                dueQueue.cancel(marchId);
                continue;
            }
            try {
                if (advance(march, now)) {
                    processed++;
                }
                dueQueue.cancel(marchId);
            } catch (RuntimeException e) {
                // 单支失败不影响其余：一次到期扫描里如果有 1000 支，
                // 因为其中一支的数据异常就整批放弃，会让另外 999 支也卡住
                LOG.error("处理到期行军失败，保留在队列中等待重试 marchId={} playerId={} status={}",
                        marchId, march.playerId(), march.status(), e);
            }
        }
        if (processed > 0) {
            LOG.info("到期扫描 playerId={} 本次推进={} 支（队列剩余 {}）",
                    playerId, processed, dueQueue.size());
        }
        return processed;
    }

    // ---------- 集结出发（B10 §5、验收 11） ----------

    /**
     * 把到点且人数够的集结发出去：合并兵力建一支行军，归属发起人。
     *
     * <p><b>为什么不复用 {@link #send}</b>：{@code send} 那条路径上的四件事这里一件都不能做 ——
     * 幂等键（没有请求方）、玩家锁（锁的是成员，而本方法由任意一个玩家的扫描触发）、
     * 名额预检（下面单独做，见注释）、扣兵（成员的兵在加入集结时已锁定）。
     * 复用到的部分是 {@link #createMarch}：速度、负载、时长、迷雾解锁、到期登记这五件事
     * 必须与个人出征同一个实现，否则「集结行军比普通行军快一点」这种漂移没有任何测试会红。
     *
     * <p><b>单个集结失败不影响其余</b>：与 {@code processDue} 同一纪律 ——
     * 一批里有十个集结，因为一个的目标坐标越界就整批放弃，会让另外九个成员的兵白锁着。
     *
     * @return 本次实际出发的集结数
     */
    public int departDueRallies(long now) {
        int departed = 0;
        for (com.ironoath.core.social.Rally rally : socialStore.dueRallies(now)) {
            long maxConcurrent = configs.longParam("MARCH_MAX_CONCURRENT");
            if (marches.activeCountOf(rally.initiatorId()) >= maxConcurrent) {
                // 发起人在途已满 ⇒ 本轮跳过，集结仍是 PREPARING、兵力仍是锁定的，下次扫描再试。
                // 直接放行等于给「出发」开一条绕过行军上限的后门，
                // 而 B15 卖的额外名额就白卖了；永久满名额的情况不会发生：行军都会到家
                LOG.info("集结暂缓出发：发起人在途行军已满 rallyId={} 发起人={}",
                        rally.rallyId(), rally.initiatorId());
                continue;
            }
            java.util.Optional<com.ironoath.core.social.Rally.Departure> departure;
            try {
                departure = socialAppService.settleDueRally(rally, now);
            } catch (RuntimeException e) {
                LOG.error("集结到点结算失败，保持准备中等待下次扫描 rallyId={} 发起人={}",
                        rally.rallyId(), rally.initiatorId(), e);
                continue;
            }
            if (departure.isEmpty()) {
                continue;   // 人数不足已退款取消，或并发下别的扫描已经出发
            }
            try {
                createRallyMarch(rally, departure.get(), now);
                departed++;
            } catch (RuntimeException e) {
                // 兵已随出发锁定，而行军没建起来 ⇒ 既不到家也没法再退。
                // 必须当场退款并把集结退回取消，否则成员的兵就永久锁在一次没有行军的集结里
                socialAppService.abortDeparture(rally.rallyId());
                LOG.error("集结出发后建行军失败，已撤销出发并退回成员兵力 rallyId={} 发起人={}",
                        rally.rallyId(), rally.initiatorId(), e);
            }
        }
        return departed;
    }

    /**
     * 用合并兵力建集结合并行军。
     *
     * <p><b>退款只做一次，所以传给 {@code createMarch} 的两个回调都是空操作</b>：
     * 它内部的回滚负责「把扣掉的兵还回去」，而集结的兵是在加入时由社交域扣的，
     * 这里的回滚是「撤销出发」（外层 catch 调 {@code abortDeparture}）。
     * 两边都做就是双重退兵 —— 一次出发变两次发兵。
     *
     * <p><b>随军武将的上限按整支集结算，不按人算</b>：合并名单取
     * {@code rally.selectedHeroes(LINEUP_HERO_COUNT)}（按加入顺序，满了或与他人重复就落选）。
     * 若允许每人各带一整套，一次 N 人集结就把个人编队上限绕过了 N 倍 ——
     * 而 {@code LINEUP_HERO_COUNT} 是战斗平衡的地基，集结不能成为它的后门。
     * 落选的成员靠 {@code RallyView.heroSlots} 自己看得见原因。
     */
    private void createRallyMarch(com.ironoath.core.social.Rally rally,
                                  com.ironoath.core.social.Rally.Departure departure, long now) {
        String initiatorId = rally.initiatorId();
        Map<String, Long> troops = departure.mergedTroops();
        Coord target = requireCoord((int) rally.targetX(), (int) rally.targetY());
        March.TargetType targetType = rallyTargetType(rally.targetType());
        WorldGenerator.Cell cell = worldAppService.cellAt(target);
        String targetId = targetEntityId(cell, target);
        requireTargetOutsideRally(rally, targetType, target, targetId);

        Map<String, Integer> speeds = new LinkedHashMap<>();
        Map<String, Long> loads = new LinkedHashMap<>();
        for (String unitId : troops.keySet()) {
            UnitCfg unit = requireUnit(unitId);
            speeds.put(unitId, (int) unit.speed());
            loads.put(unitId, unit.load());
        }
        // 与个人出征同一实现：取最慢兵种、Σ数量×单位负载。集结的兵更快或运力更大都没有依据
        int teamSpeed = MarchCalculator.teamSpeed(troops, speeds);
        long loadCap = MarchCalculator.loadCap(troops, loads);
        List<String> heroes = rally.selectedHeroes((int) configs.longParam("LINEUP_HERO_COUNT"));

        createMarch(initiatorId, worldAppService.homeOf(initiatorId), target, troops,
                heroes, targetType, targetId, March.Action.ATTACK,
                teamSpeed, loadCap, now, rally.rallyId(), () -> { }, () -> { });
    }

    /**
     * 出发阶段的目标结构校验。个人出征有 {@link #validateAction} 这道关，集结过去完全没有，
     * 于是「集结打自己的城」「集结打成员的城」都能一路成立。
     *
     * <p><b>为什么必须拦</b>：{@code targetId} 对玩家城而言就是防守方的 playerId，
     * 参与者打自己人等于掠夺左口袋换右口袋，还要给发起人记一笔暴虐值、给守方推一座受害护盾 ——
     * 三个都是会让数据看板与客服工单出现荒谬结论的假信号。
     *
     * <p><b>只拦结构上不成立的，不拦时间性的</b>：护盾、守方已被别人打光这类
     * 到点这一刻才出现的不可打，仍由到达时的 {@code resolveAttack} 兜底为「原样返程」 ——
     * 为一场赶不上的仗把全员的兵退回去重新等一次集结，比让队伍飞一趟更扰民。
     */
    private void requireTargetOutsideRally(com.ironoath.core.social.Rally rally,
                                           March.TargetType targetType, Coord target, String targetId) {
        if (targetType != March.TargetType.PLAYER_CITY) {
            return;
        }
        if (targetId == null || targetId.isBlank()) {
            throw new BizException(ErrorCode.WORLD_TARGET_INVALID,
                    "集结目标 (" + target.x() + "," + target.y() + ") 上已经没有玩家城，无从出发");
        }
        if (rally.memberIds().contains(targetId)) {
            throw new BizException(ErrorCode.WORLD_TARGET_INVALID,
                    "集结不能以参与者自己的城为目标：被攻击者 " + targetId
                            + " 同时也在出兵名单里，掠夺会变成左口袋换右口袋");
        }
    }

    /**
     * 集结目标类型 → 行军目标类型。
     *
     * <p>只放行可攻击的目标：集结的语义就是「一起打一下」，而空地与资源点没有可打的实体。
     * 抛出去由调用方撤销出发（退款 + 取消），这比让集结卡在「准备中且已到点」强 ——
     * 那种状态下成员的兵一直被锁着，而面板上看起来只是「还没出发」。
     */
    private static March.TargetType rallyTargetType(String targetType) {
        final March.TargetType parsed;
        try {
            parsed = March.TargetType.valueOf(targetType);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BizException(ErrorCode.RALLY_PREPARE_INVALID, "集结目标类型无法识别: " + targetType);
        }
        if (parsed != March.TargetType.MONSTER && parsed != March.TargetType.PLAYER_CITY) {
            throw new BizException(ErrorCode.RALLY_PREPARE_INVALID,
                    "集结只能攻击野怪或玩家城，当前目标=" + parsed);
        }
        return parsed;
    }

    /** 把一支到点的行军推进到下一个状态。返回是否真的推进了。 */
    private boolean advance(March march, long now) {
        long version = marches.versionOf(march.id());
        if (march.status() == March.Status.MARCHING) {
            if (now < march.arriveAt()) {
                return false;
            }
            march.arrive(now);
            // 集结合并行军一到目标就把集结推到 ARRIVED：全灭时这支行军的记录当场被删，
            // 之后再也没有第二个能标记它的时刻，不在这里标就永久停在 DEPARTED
            markRallyArrived(march);
            switch (march.action()) {
                case GATHER -> {
                    march.startGathering(now);
                    // 采集没有「到点」事件：采满由 GATHER_FILL_SECONDS 决定，
                    // 但玩家可能一直不来领，所以登记一个「采满时刻」让状态可查
                    long fillMs = configs.longParam("GATHER_FILL_SECONDS") * 1000L;
                    dueQueue.schedule(march.id() + ":filled", now + fillMs);
                }
                case STATION, GARRISON -> {
                    // 驻扎没有后续事件，等玩家召回
                }
                case ATTACK -> resolveAttack(march, now);
                case SCOUT -> {
                    generateReport(march, now);
                    march.beginReturn(now, MarchCalculator.recallMillis(march, now));
                    dueQueue.reschedule(march.id(), march.returnArriveAt());
                }
            }
            world.bumpChunkVersion(march.to().chunkKey(chunkSize()));
            marches.save(march, version);
            LOG.info("行军到达 marchId={} playerId={} 目标={} 行为={} 新状态={}",
                    march.id(), march.playerId(), march.to(), march.action(), march.status());
            return true;
        }
        if (march.status() == March.Status.RETURNING) {
            if (march.returnArriveAt() == null || now < march.returnArriveAt()) {
                return false;
            }
            arriveHome(march, now, version);
            return true;
        }
        return false;
    }

    /**
     * 攻击到达后的分流：野怪走 PVE 结算，玩家城走 PVP 结算。
     *
     * <p><b>两条路径刻意不合并</b>：共享的只有「折叠 → 内核 → 摊回损失」那一段
     * （已抽到 {@code BattleArmyFactory.unfoldLosses}，两边共用同一份实现），
     * 而各自的后半段完全不同 —— 野怪有每日次数与体力、掉落走配置表、打完从地图上消耗掉；
     * 玩家城掠夺守方仓库的保护额度、累积暴虐值、推进守方的受害护盾、给双方各记一份战报。
     * 合成一个类只会得到一堆 {@code if (isMonster)} 分支，而那种分支正是
     * 「改了一条路径顺手弄坏另一条」的温床。
     *
     * <p>PVP 这条仍缺城墙耐久、复仇/哀兵加成与地形三项，全部记录在
     * {@code PlayerCityBattleService} 的类注释里，不是遗漏。
     *
     * <p><b>战斗打不成时队伍原样返程，绝不抛出去</b>：每日次数或体力在出征时预检过，
     * 但真正消耗发生在到达时，中间可能被同一玩家的其它行军抢走名额；
     * PVP 还多一种情况 —— 出征时守方有兵，几十分钟后可能已经被别人打光。
     * 那时如果抛异常，{@code processDue} 会把这支行军留在到期队列里反复重试 ——
     * 玩家看到的是一支永远卡在目标面前的队伍，而日志里一切正常。
     * 让它带着满编兵力回家是唯一不会留下烂状态的处理。
     */
    private void resolveAttack(March march, long now) {
        Map<String, Long> beneficiaries = beneficiaries(march);
        try {
            switch (march.targetType()) {
                case MONSTER -> {
                    var monsterOutcome = monsterBattleService.resolve(march, now, beneficiaries);
                    if (monsterOutcome.won()) {
                        // 打赢了才算击杀（B12 §1 的 KILL_MONSTER）：打输了也记的话，
                        // 「清剿城外流寇」会在玩家被流寇打回家的那一刻完成
                        questEvents.progress(march.playerId(),
                                com.ironoath.core.quest.GoalType.KILL_MONSTER, march.targetId(), 1L, now);
                    }
                }
                case PLAYER_CITY -> {
                    var pvpOutcome = playerCityBattleService.resolve(march, now, beneficiaries);
                    // 活动进度（B17 争锋令）：只有进攻方获胜才记 —— 打输了也记的话，
                    // 「拿下一座玩家城」会在自己被打回家的那一刻完成（与 KILL_MONSTER 同一条）
                    if (pvpOutcome.result().winner() == com.ironoath.battle.Winner.ATTACKER) {
                        questEvents.progress(march.playerId(),
                                com.ironoath.core.quest.GoalType.PVP_WIN, null, 1L, now);
                    }
                    // 仗真的打起来了才记频控的账（胜负都算 —— 真人挨了打这件事已经发生）
                    botAttackLimiter.recordAttack(march.playerId(), march.targetId(), now);
                }
                case RESOURCE -> interceptGatherer(march, now);
                default -> throw new BizException(ErrorCode.WORLD_TARGET_INVALID,
                        "该目标不可攻击：targetType=" + march.targetType());
            }
        } catch (BizException e) {
            LOG.warn("战斗未能开始，队伍原样返程 marchId={} playerId={} 目标={} 原因={}",
                    march.id(), march.playerId(), march.targetType(), e.detail());
        }
        if (march.totalUnits() <= 0L) {
            // 全军覆没：没有兵可以走回来，直接清理，否则地图上会留一支 0 兵的队伍在移动
            LOG.info("攻击失败且全军覆没，行军记录删除 marchId={} playerId={}", march.id(), march.playerId());
            marches.delete(march.id());
            return;
        }
        // 打完就返程：野怪不能被占领，玩家城的占领属 B13 国战，队伍都没有理由留在原地
        march.beginReturn(now, MarchCalculator.recallMillis(march, now));
        dueQueue.reschedule(march.id(), march.returnArriveAt());
    }

    /**
     * 这次战斗打下来的收益归谁、按什么权重分。
     *
     * <p><b>普通行军只有一个受益人</b>，权重取任意正数都一样（分摊结果恒等于全额）。
     * 集结合并行军必须按<b>承诺兵力</b>作权重：成员的兵在加入时就锁定了，
     * 如果掠夺与掉落全部记到发起人名下，那就是「兵分回来了、抢到的东西没分回来」——
     * 与返程分兵同一条守恒纪律的另一半，也是「组团不如单干」这种反向激励的来源。
     *
     * <p><b>读不到集结时退回全额给发起人</b>并记 ERROR：收益不能无人认领，
     * 而宁可让一个人多拿（可查、可补发）也不能让这笔钱凭空消失。
     */
    private Map<String, Long> beneficiaries(March march) {
        if (!march.isRallyMarch()) {
            return Map.of(march.playerId(), 1L);
        }
        com.ironoath.core.social.Rally rally = socialStore.rallyOf(march.rallyId()).orElse(null);
        Map<String, Long> weights = new LinkedHashMap<>();
        if (rally != null) {
            for (String member : rally.memberIds()) {
                com.ironoath.core.social.Rally.Participant participant = rally.participant(member);
                if (participant != null && participant.total() > 0L) {
                    weights.put(member, participant.total());
                }
            }
        }
        if (weights.isEmpty()) {
            LOG.error("【集结合并行军读不到参与者，战斗收益全额记给发起人】marchId={} rallyId={} 发起人={}"
                    + " 需要人工核对是否漏分给成员", march.id(), march.rallyId(), march.playerId());
            return Map.of(march.playerId(), 1L);
        }
        return Map.copyOf(weights);
    }

    /**
     * 拦截一支正在采集的行军（B09 验收 7 的剩下一半）。
     *
     * <p><b>先把守方已采集的量结算进负载，再打</b>：{@code settleGathered} 是惰性结算，
     * 不调它的话 {@code gatherer.load()} 只反映上一次结算时至今的量，
     * 而玩家实际采到的更多。少夺一点不是问题 —— 问题是守方会以为自己保住了
     * 那部分其实已经暴露在拦截之下的资源，而这笔账在任何日志里都看不出来。
     *
     * <p><b>守方的行军要在这里处置，不能丢给拦截结算</b>：
     * 状态机与到期队列归本类管，两边各改一次会打架（重复 reschedule 或漏 cancel）。
     * 处置口径与攻城一致 —— 全军覆没就删记录（没有兵能走回来，
     * 否则会留一支 0 兵的队伍在地图上移动），否则带着剩余负载原路返回。
     */
    private void interceptGatherer(March march, long now) {
        March gatherer = gatheringMarchAt(march.to(), march.playerId())
                .orElseThrow(() -> new BizException(ErrorCode.WORLD_TARGET_INVALID,
                        "对方已经采完离开了，无可拦截"));
        settleGathered(gatherer, now);
        long gathererVersion = marches.versionOf(gatherer.id());
        playerCityBattleService.interceptGatherer(march, gatherer, now);
        // 拦截同样算「Bot 打了这个真人」：采集队也是他的兵，挨打的是他
        botAttackLimiter.recordAttack(march.playerId(), gatherer.playerId(), now);
        if (gatherer.totalUnits() <= 0L) {
            LOG.info("采集队被全灭，行军记录删除 marchId={} playerId={} 剩余负载随队伍一起消失={}",
                    gatherer.id(), gatherer.playerId(), gatherer.load());
            marches.delete(gatherer.id());
            return;
        }
        gatherer.beginReturn(now, MarchCalculator.recallMillis(gatherer, now));
        marches.save(gatherer, gathererVersion);
        // 改期而不是「取消 + 重新登记」：后者在两步之间有一个窗口，
        // 此时若正好有一次到期扫描，这支行军就会被漏掉（B07 验收 2：无漏触发）
        dueQueue.reschedule(gatherer.id(), gatherer.returnArriveAt());
    }

    /**
     * 找出某格上正在采集的、不属于 {@code excludePlayerId} 的行军。
     *
     * <p><b>用 chunk 查询而不是全表扫描</b>：{@code findByChunkKeys} 已经是行军的空间索引入口，
     * 一格只会落在一个 chunk 里，所以候选集通常是个位数。
     * 全表扫描在国战那种量级（单服 5000 在线、行军 200 QPS）会把每次出征都变成一次全表遍历。
     */
    private java.util.Optional<March> gatheringMarchAt(Coord coord, String excludePlayerId) {
        for (March other : marches.findByChunkKeys(java.util.List.of(coord.chunkKey(chunkSize())))) {
            if (other.status() == March.Status.GATHERING
                    && other.to().x() == coord.x() && other.to().y() == coord.y()
                    && !other.playerId().equals(excludePlayerId)) {
                return java.util.Optional.of(other);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * 到家：兵归队、采集的资源入账、行军记录删除、名额释放。
     *
     * <p>资源入账走 {@link RewardService}，不直接改存档 ——
     * 否则绕过容量上限与事件通知（B04 禁止项）。
     */
    private void arriveHome(March march, long now, long version) {
        march.arriveHome(now);
        String playerId = march.playerId();

        Map<String, Long> returned;
        if (march.isRallyMarch()) {
            returned = returnRallyTroops(march);
        } else {
            ArmyState army = loadArmy(playerId);
            long armyVersion = armies.versionOf(playerId);
            // 行军本来就按 unitId 记兵，所以归队是原样入账，不需要任何折算 ——
            // 这里曾经是「按该兵种最低阶级入账」，于是一次出征就把 T5 兵降成了 T1
            returned = march.units();
            returned.forEach(army::add);
            armies.save(playerId, army, armyVersion);
        }

        if (march.load() > 0L) {
            String resourceType = resourceTypeAt(march.to());
            if (resourceType != null) {
                var grant = rewardService.grant(playerId,
                        List.of(new RewardItem(RewardType.RESOURCE, resourceType, march.load())),
                        RewardContext.toMail("gather", march.id(), march.id()));
                if (grant.hasCompensation()) {
                    LOG.error("【采集资源入账失败已进补偿队列】playerId={} marchId={} 数量={} compensationId={}",
                            playerId, march.id(), march.load(), grant.compensationId());
                }
            }
        }

        marches.save(march, version);
        marches.delete(march.id());
        dueQueue.cancel(march.id());
        dueQueue.cancel(march.id() + ":filled");
        world.bumpChunkVersion(march.from().chunkKey(chunkSize()));
        LOG.info("行军到家 playerId={} marchId={} 归队={} 带回资源={}",
                playerId, march.id(), returned, march.load());
    }

    /** 集结合并行军到达目标：把集结从 DEPARTED 推到 ARRIVED。非集结行军什么也不做。 */
    private void markRallyArrived(March march) {
        if (!march.isRallyMarch()) {
            return;
        }
        com.ironoath.core.social.Rally rally = socialStore.rallyOf(march.rallyId()).orElse(null);
        if (rally == null || rally.status() != com.ironoath.core.social.Rally.Status.DEPARTED) {
            return;
        }
        long expectedRallyVersion = rally.version();
        rally.arrive();
        socialStore.saveRally(rally, expectedRallyVersion);
    }

    /**
     * 集结合并行军到家：把幸存兵力按各自承诺量比例分回各人（B10 验收 11 的另一半）。
     *
     * <p><b>不做这件事就不能放行出发</b>：合并行军的主人是发起人，{@code arriveHome} 默认
     * 会把整支幸存部队记到他名下。那不是「分配有点偏」，而是成员的兵有去无回 ——
     * 比集结功能不存在严重得多，所以 {@code SocialAppService.expireIfDue} 宁可在到点时
     * 退款取消，也不在缺这一半的情况下出发。
     *
     * <p><b>按 unitId 逐个分，不按总兵力分</b>：合并是按 unitId 合的，散回去也必须按 unitId 散。
     * 按总数分就会出现「只出了 T5 的人拿回一堆 T1」，而那等于在结算里偷换了兵种。
     *
     * <p><b>复用 {@link TierSplit}，不在这里再写一份最大余数法</b>：那份实现已经带着
     * 「Σ摊回 == Σ应摊」的守恒与「键序决定余数归属」的确定性（铁律 4：战报必须可复现）。
     * 同一条数学写两份的漂移方式是「集结分兵比普通战损多还一个兵」，没有任何测试会红。
     *
     * @return 实际分回的兵力合计（用于到家日志）
     */
    private Map<String, Long> returnRallyTroops(March march) {
        com.ironoath.core.social.Rally rally = socialStore.rallyOf(march.rallyId()).orElse(null);
        if (rally == null) {
            // 绝不退回到「全额记给发起人」：那会把一次数据缺失洗成一次看起来正常的发兵，
            // 而当事人的兵就真的找不回来了。记 ERROR 等人工补兵是可查的，洗账不是
            LOG.error("【集结合并行军找不到对应集结，幸存兵力未分回】marchId={} rallyId={} 兵力={} 必须人工补兵",
                    march.id(), march.rallyId(), march.units());
            return Map.of();
        }
        // playerId → (unitId → 分回量)。先全部分完再一次人一次人入账，
        // 否则中途某个人保存失败，会出现「一部分人已收到、剩下的永远收不到」
        Map<String, Map<String, Long>> credits = new LinkedHashMap<>();
        long survivors = 0L;
        long credited = 0L;
        for (Map.Entry<String, Long> unit : march.units().entrySet()) {
            String unitId = unit.getKey();
            long count = unit.getValue();
            survivors += count;
            Map<String, Long> committedBy = new LinkedHashMap<>();
            for (String member : rally.memberIds()) {
                com.ironoath.core.social.Rally.Participant participant = rally.participant(member);
                long committed = participant == null ? 0L : participant.troops().getOrDefault(unitId, 0L);
                if (committed > 0L) {
                    committedBy.put(member, committed);
                }
            }
            Map<String, Long> shares = TierSplit.splitProportionally(committedBy, count);
            long shareTotal = 0L;
            for (Map.Entry<String, Long> share : shares.entrySet()) {
                credits.computeIfAbsent(share.getKey(), k -> new LinkedHashMap<>())
                        .merge(unitId, share.getValue(), Long::sum);
                shareTotal += share.getValue();
                credited += share.getValue();
            }
            if (shareTotal != count) {
                // 合并兵力本就是这些承诺之和，数学上不可能分不完 —— 分到就是上面算错了。
                // 与 B05「分摊比例凭空吞兵」同一条纪律：缺口必须响亮，静默少还会变成客服工单
                LOG.error("【集结分兵未分完】rallyId={} unitId={} 幸存={} 实分={} 承诺={} 差额={} 必须人工补兵",
                        march.rallyId(), unitId, count, shareTotal, committedBy, count - shareTotal);
            }
        }
        Map<String, Long> perPlayer = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Long>> entry : credits.entrySet()) {
            String member = entry.getKey();
            ArmyState army = loadArmy(member);
            long armyVersion = armies.versionOf(member);
            long each = 0L;
            for (Map.Entry<String, Long> share : entry.getValue().entrySet()) {
                army.add(share.getKey(), share.getValue());
                each += share.getValue();
            }
            armies.save(member, army, armyVersion);
            perPlayer.put(member, each);
        }
        LOG.info("集结兵力分回 rallyId={} marchId={} 幸存={} 实分={} 参与人数={} 明细={}",
                march.rallyId(), march.id(), survivors, credited, perPlayer.size(), perPlayer);
        return perPlayer;
    }

    // ---------- 侦查报告 ----------

    /**
     * 生成一份带误差的敌情报告（B07 §3、验收 8）。
     *
     * <p><b>误差幅度随等级差放大</b>：0 级差 ±5%，20 级差 ±40%（封顶）。
     * 这是「越级挑战有风险」的核心机制之一 —— 看不清对面有多少兵，
     * 玩家就要么集结（B08 给的组团出路）要么放弃，而不是单人硬堆数值。
     *
     * <p><b>种子由服务端生成</b>：若种子来自请求字段，玩家可以反复侦查直到刷出一份
     * 「看起来敌方很弱」的情报，那等于取消了误差。
     */
    private void generateReport(March march, long now) {
        Coord target = march.to();
        WorldGenerator.Cell cell = worldAppService.cellAt(target);
        int targetLevel = cell.level();
        int scoutLevel = 1;   // 侦查方的「等级」用主城等级近似
        var playerCity = worldAppService.playerLevelOf(march.playerId());
        if (playerCity > 0) {
            scoutLevel = playerCity;
        }
        int levelDiff = Math.abs(scoutLevel - targetLevel);
        long errorFixed = ScoutReport.errorFixed(levelDiff,
                configs.fixedParam("SCOUT_ERROR_BASE"),
                configs.fixedParam("SCOUT_ERROR_PER_LEVEL"),
                configs.fixedParam("SCOUT_ERROR_MAX"));

        Map<String, Long> truth = truthOf(cell, target);
        long seed = seeds.nextSeed();
        Map<String, Long> observed = ScoutReport.distort(truth, errorFixed, seed);

        long ttlSeconds = configs.longParam("SCOUT_REPORT_TTL_SECONDS");
        ScoutReport.Report report = new ScoutReport.Report(
                "scout_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                march.playerId(), target, cell.entityId(), targetLevel,
                now, now + ttlSeconds * 1000L, observed, errorFixed, seed);
        world.saveReport(report);
        LOG.info("侦查报告生成 playerId={} reportId={} 目标={} 等级差={} 误差=±{}% seed={}",
                march.playerId(), report.reportId(), target, levelDiff,
                com.ironoath.common.num.FixedPoint.format(errorFixed * 100), seed);
    }

    /** 目标的真实数值。空地与资源点没有战力，野怪从 mapmonster 表读。 */
    private Map<String, Long> truthOf(WorldGenerator.Cell cell, Coord coord) {
        Map<String, Long> truth = new LinkedHashMap<>();
        if (cell.entityType() == WorldGenerator.EntityType.MONSTER) {
            MapmonsterCfg monster = configs.get(MapmonsterCfg.class, cell.entityId());
            truth.put("power", monster.power());
            long total = monster.infantryCount() + monster.cavalryCount()
                    + monster.archerCount() + monster.siegeCount();
            truth.put("totalUnits", total);
            truth.put("infantry", monster.infantryCount());
            truth.put("cavalry", monster.cavalryCount());
            truth.put("archer", monster.archerCount());
            truth.put("siege", monster.siegeCount());
            truth.put("wood", monster.dropWood());
            truth.put("stone", monster.dropStone());
            truth.put("iron", monster.dropIron());
            truth.put("grain", monster.dropGrain());
            truth.put("gold", monster.dropGold());
            return truth;
        }
        String owner = world.cityAt(coord).orElse(null);
        if (owner != null) {
            // 玩家城的情报由 B08 的战力圈层给出（战力是公开的，兵力是机密的），
            // 这里先给一个占位口径：只报等级与战力，不报兵力构成
            truth.put("power", (long) worldAppService.playerLevelOf(owner) * 100L);
            truth.put("totalUnits", 0L);
            return truth;
        }
        truth.put("power", 0L);
        truth.put("totalUnits", 0L);
        return truth;
    }

    // ---------- 内部 ----------

    /**
     * 判定目标类型。<b>纯查询，不抛业务异常、不读存档、不写库</b> ——
     * 拒绝在 {@link #validateAction} 里做。
     *
     * <p>两者分开是必要的：解析是纯查询，校验会抛异常、会读存档、会重算战力。
     * 混在一个方法里，读代码的人无法从方法名判断它有没有副作用 ——
     * 而校验的副作用（重算并落库攻方战力）恰恰是最需要被看见的。
     *
     * <p><b>玩家城优先于地块实体类型</b>：城所在的地块也可能同时带着实体标记，
     * 而「这里有一座城」比「这一格是什么地形实体」更重要 ——
     * 判错的后果是玩家点了攻城却被当成打野，战斗按 PVE 结算，守方一分钱资源都不掉。
     */
    private March.TargetType resolveTargetType(WorldGenerator.Cell cell, Coord coord) {
        if (world.cityAt(coord).isPresent()) {
            return March.TargetType.PLAYER_CITY;
        }
        if (cell.entityType() == WorldGenerator.EntityType.MONSTER) {
            return March.TargetType.MONSTER;
        }
        if (cell.entityType() == WorldGenerator.EntityType.RESOURCE) {
            return March.TargetType.RESOURCE;
        }
        return March.TargetType.EMPTY;
    }

    /**
     * 按行为校验目标是否合法。
     *
     * <p>与「解析目标类型」分开是必要的：解析是纯查询，校验会抛异常、会读存档、会重算战力。
     * 混在一个方法里，读代码的人无法从方法名判断它有没有副作用 ——
     * 而这个方法的副作用（重算并落库攻方战力）恰恰是最需要被看见的。
     */
    private void validateAction(Coord coord, March.Action action, March.TargetType type,
                                String playerId, long now, String targetId) {
        if (action == March.Action.ATTACK) {
            // B08 §2 的三个统一入口之一：护盾与圈层校验对一切攻击目标都生效
            attackGuardService.guard(playerId, coord, now);
            // 出征时就预检：等到队伍飞了几十分钟站在目标面前才说「打不了」，
            // 玩家什么也做不了 —— 提示必须出现在他还能改主意的时刻（B09 验收 3 的精神）。
            // 这里只预检不消耗，真正的占用与结算发生在到达时
            switch (type) {
                case MONSTER -> monsterBattleService.precheck(playerId, targetId, now);
                case PLAYER_CITY -> playerCityBattleService.precheck(playerId, targetId, now);
                case RESOURCE -> {
                    // 资源点本身不能被「占领」或攻击，能拦的是**正在那里采集的那支队伍**。
                    // 没人在采就明确拒绝，而不是让队伍飞过去再发现无从下手 ——
                    // 提示必须出现在玩家还能改主意的时刻（B09 验收 3 的精神）
                    if (gatheringMarchAt(coord, playerId).isEmpty()) {
                        throw new BizException(ErrorCode.WORLD_TARGET_INVALID,
                                "这个资源点上没有人在采集，无从拦截（资源点本身不可攻击）");
                    }
                }
                default -> throw new BizException(ErrorCode.WORLD_TARGET_INVALID,
                        "只有野怪、玩家城与「有人正在采集的资源点」能被攻击，目标 ("
                                + coord.x() + "," + coord.y() + ") 是 " + type
                                + "（联盟建筑由 B10 交付）");
            }
            return;
        }
        if (action == March.Action.GATHER && type != March.TargetType.RESOURCE) {
            throw new BizException(ErrorCode.WORLD_TARGET_INVALID,
                    "只有资源点能采集，目标 (" + coord.x() + "," + coord.y() + ") 是 " + type);
        }
        if (action == March.Action.GARRISON && type != March.TargetType.PLAYER_CITY) {
            throw new BizException(ErrorCode.WORLD_TARGET_INVALID,
                    "只有玩家城能增援驻守，目标是 " + type + "（联盟建筑由 B10 交付）");
        }
        if (action == March.Action.SCOUT && type == March.TargetType.EMPTY) {
            // 空地也能侦查（要确认「这里真的没人」），但那是探索而不是敌情，
            // 走的是迷雾解锁路径，不必生成报告
            LOG.info("侦查空地，只解锁迷雾不生成报告 playerId={} coord={}", playerId, coord);
        }
    }

    private String targetEntityId(WorldGenerator.Cell cell, Coord coord) {
        if (cell.entityId() != null) {
            return cell.entityId();
        }
        return world.cityAt(coord).orElse(null);
    }

    /** 该坐标的资源点产什么资源；非资源点返回 null。 */
    private String resourceTypeAt(Coord coord) {
        WorldGenerator.Cell cell = worldAppService.cellAt(coord);
        return cell.entityType() == WorldGenerator.EntityType.RESOURCE ? cell.entityId() : null;
    }

    private void refundTroops(String playerId, ArmyState army, long version, Map<String, Long> units) {
        try {
            units.forEach(army::add);
            armies.save(playerId, army, version);
        } catch (RuntimeException e) {
            LOG.error("【出征失败后退还兵力失败】playerId={} 兵力={} traceId={} 必须人工补偿",
                    playerId, units, TraceContext.traceId(), e);
        }
    }

    private March requireOwnMarch(String playerId, String marchId) {
        March march = marches.findById(marchId)
                .orElseThrow(() -> new BizException(ErrorCode.MARCH_NOT_FOUND, "行军不存在: " + marchId));
        if (!march.playerId().equals(playerId)) {
            // 不能透露「这支行军存在但不属于你」：那等于给了一个探测别人行军 id 的接口
            throw new BizException(ErrorCode.MARCH_NOT_FOUND, "行军不存在: " + marchId);
        }
        return march;
    }

    private ArmyState loadArmy(String playerId) {
        return armies.findByPlayerId(playerId).orElseGet(() -> {
            armies.insertIfAbsent(playerId, new ArmyState());
            return armies.findByPlayerId(playerId).orElseThrow();
        });
    }

    private int chunkSize() {
        return (int) configs.longParam("WORLD_CHUNK_SIZE");
    }

    private Coord requireCoord(int x, int y) {
        int worldSize = (int) configs.longParam("WORLD_SIZE");
        if (x < 0 || y < 0 || x >= worldSize || y >= worldSize) {
            throw new BizException(ErrorCode.WORLD_COORD_INVALID,
                    "坐标越界：(" + x + "," + y + ")，世界边长 " + worldSize);
        }
        return Coord.of(x, y);
    }

    private UnitCfg requireUnit(String unitId) {
        try {
            return configs.get(UnitCfg.class, unitId);
        } catch (ConfigException e) {
            throw new BizException(ErrorCode.CONFIG_NOT_FOUND, "兵种配置不存在: " + unitId);
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

    private void acquire(String requestId, long now) {
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + requestId);
        }
    }

    private MarchView toView(March march, long now) {
        // March 已经按 unitId（含阶级）记兵，直接映射即可。
        // 这里曾经是「按 unit 表顺序找同兵种的第一个阶级」，于是带着 T3 步兵出征
        // 在客户端看起来是 T1 步兵 —— 协议里下发的阶级是错的，而战损也会算错
        List<MarchUnit> units = new ArrayList<>(march.units().size());
        march.units().forEach((unitId, count) -> units.add(new MarchUnit(unitId, count)));
        Long gatherFinishAt = null;
        if (march.status() == March.Status.GATHERING && march.gatherStartAt() != null) {
            gatherFinishAt = march.gatherStartAt()
                    + configs.longParam("GATHER_FILL_SECONDS") * 1000L;
        }
        return new MarchView(march.id(), toCoord(march.from()), toCoord(march.to()),
                com.ironoath.web.dto.generated.MarchStatus.valueOf(march.status().name()),
                com.ironoath.web.dto.generated.TargetType.valueOf(march.targetType().name()),
                march.targetId(), march.rallyId(),
                com.ironoath.web.dto.generated.MarchAction.valueOf(march.action().name()),
                march.startAt(), march.arriveAt(), march.returnStartAt(), march.returnArriveAt(),
                units, march.heroes(), march.load(), march.loadCap(), march.teamSpeed(),
                toCoord(march.positionAt(now)), march.progressFixed(now), gatherFinishAt, now);
    }

    private static com.ironoath.web.dto.generated.Coord toCoord(Coord coord) {
        return new com.ironoath.web.dto.generated.Coord(coord.x(), coord.y());
    }
}
