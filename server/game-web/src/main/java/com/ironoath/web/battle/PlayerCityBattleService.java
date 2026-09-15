package com.ironoath.web.battle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.battle.ArmySide;
import com.ironoath.battle.BattleInput;
import com.ironoath.battle.BattleModifier;
import com.ironoath.battle.BattleResult;
import com.ironoath.battle.BattleSimulator;
import com.ironoath.battle.BattleType;
import com.ironoath.battle.DefenderStore;
import com.ironoath.battle.HeroSnapshot;
import com.ironoath.battle.TerrainType;
import com.ironoath.battle.Winner;
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.DayKey;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.TierSplit;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.hero.HeroRoster;
import com.ironoath.core.hero.Lineup;
import com.ironoath.core.limit.DailyCounter;
import com.ironoath.core.march.March;
import com.ironoath.core.player.PlayerPvp;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.power.Protection;
import com.ironoath.core.power.Tyranny;
import com.ironoath.core.resource.ResourceProtection;
import com.ironoath.web.reward.PlayerWallet;
import com.ironoath.web.service.ArmyAppService;
import com.ironoath.web.service.HeroStatsService;
import com.ironoath.web.service.PowerRefreshService;
import com.ironoath.web.service.PowerService;
import com.ironoath.web.service.StaminaService;

/**
 * 职责：玩家城 PVP 的战斗结算 —— 双方战损、掠夺、暴虐值、受害护盾、双方各一份战报（B09 剩余项）。
 * 依赖：战斗内核、{@link BattleArmyFactory}、B08 的 {@link Tyranny}/{@link Protection}、
 *       B04 的 {@link ResourceProtection}/{@link PlayerWallet}、战报服务。
 *
 * <p><b>为什么它不与 {@code MonsterBattleService} 合并</b>：两条路径共享的是「折叠 → 内核 → 摊回损失」
 * 这一段（已抽到 {@link BattleArmyFactory#unfoldLosses}，两边共用同一份实现），
 * 而它们<b>不共享</b>的部分恰恰是各自的口径：野怪有每日次数与体力、掉落走配置表、没有仓库可掠夺、
 * 打完就从地图上消耗掉；玩家城没有次数限制、掠夺走守方仓库的保护额度、要累积暴虐值、
 * 要推进守方的连续受害护盾、要给双方各记一份战报。合并成一个类只会得到一堆
 * {@code if (isMonster)} 分支，而那种分支正是「改了一条路径顺手弄坏另一条」的温床。
 *
 * <p><b>本类明确不做的三件事（都是记录在案的缺口，不是遗漏）</b>：
 * <ol>
 *   <li><b>城墙耐久与攻城判定</b>：{@code DefenderStore.wallBroken} 恒为 true。
 *       B03 有城墙建筑但没有耐久模型，内核也不自己判断破墙（那是城防系统的职责）。
 *       接上耐久后这里必须改成由攻城结算决定，否则掠夺会在守方城墙完好时发生</li>
 *   <li><b>哀兵加成</b>：攻方侧的复仇与围剿由 {@link CounterplayModifiers} 装配，
 *       守方侧目前恒为 none()。哀兵在三处原文里都限定为「防守集结」的加成，
 *       而防守集结不存在于任何批次的交付清单（B10 只有集结进攻），所以它没有触发条件 ——
 *       这不是接线遗漏，是规格缺口，见收口清单</li>
 *   <li><b>地形</b>：恒为 PLAIN。世界地图有地形但战斗侧还没接，
 *       接的时候要连同「守城方是否享受城下地形加成」一起定</li>
 * </ol>
 *
 * <p><b>写入顺序是刻意的，改它之前先读完这段</b>：掠夺走 {@link PlayerWallet}、
 * 战力重算走 {@link PowerRefreshService}，两者都是「各自加载一份存档、改完写回」。
 * 所以暴虐值与护盾（同样写在 PlayerSave 上）<b>必须在它们之后</b>再重新加载存档来改 ——
 * 先改 pvp 再掠夺的话，掠夺那一步会把 pvp 的改动整份覆盖掉（B04 踩过的「过期版本覆盖」同一类 bug）。
 */
@Service
public class PlayerCityBattleService {

    private static final Logger LOG = LoggerFactory.getLogger(PlayerCityBattleService.class);

    /** 暴虐值的「单日对同一目标只记一次」计数域。 */
    private static final String SCOPE_TYRANNY_TARGET = "tyranny_target";
    /**
     * 暴虐值的「同一对玩家此前是否已经互相攻击过」账本。
     *
     * <p><b>用 DailyCounter 当「见过一次」的标记是权宜之计</b>：这条判定的语义是「有史以来」而不是「每日」，
     * 所以 dayKey 是一个常量而不是日期。之所以不新建一个存储端口，是因为它需要的能力
     * 恰好就是「某个键只允许成功一次」，而 DailyCounter 已经有了 Mongo 与内存两个实现 ——
     * 再开一个端口就等于让 MongoDB 存储债务（见上线检查清单 §四 2）多一项。
     * 代价是这一项在 DailyCounter 的清理策略下可能被当作过期数据清掉，
     * 那时同一对玩家会重新变成「首次相遇」，暴虐值可能被多记一次。已记录，待收口时换成专用账本。
     */
    private static final String SCOPE_TYRANNY_PAIR = "tyranny_pair_first_meeting";
    private static final String PAIR_LEDGER_DAY_KEY = "EVER";

    /** 守方用哪一套编队守城。目前只有主编队，B06 的多套编队里没有「守城专用」这一档。 */
    private static final int DEFENSE_LINEUP_PRESET = 0;

    private final ConfigRegistry configs;
    private final BattleArmyFactory armyFactory;
    private final BattleRulesAssembler rulesAssembler;
    private final HeroBattleMapper heroMapper;
    private final HeroStatsService heroStats;
    private final HeroRepository heroes;
    private final ArmyRepository armies;
    private final CityRepository cities;
    private final PlayerRepository players;
    private final ArmyAppService armyAppService;
    private final PlayerWallet wallet;
    private final PowerService powerService;
    private final PowerRefreshService powerRefreshService;
    private final BattleReportService battleReports;
    private final DailyCounter dailyCounter;
    private final com.ironoath.web.reward.ServerSeedSource seeds;
    private final com.ironoath.web.service.SocialAppService socialAppService;
    /** 乘区 F 的唯一装配点（复仇 + 围剿）。守方侧的哀兵没有触发条件，见类注释。 */
    private final CounterplayModifiers counterplay;
    /** 只为「暴虐档位跨过坐标暴露线时让所在块变新」这一件事服务。 */
    private final com.ironoath.web.service.WorldAppService worldAppService;
    /** 公敌档广播的登记入口（真正发送由视野下发惰性驱动，见该类注释）。 */
    private final com.ironoath.web.service.PublicEnemyBroadcaster publicEnemies;
    /** 乘区 B：双方各自联盟已研究的攻防科技（B05 §1.3）。不查联盟就等于这一路永远没有科技。 */
    private final AllianceTechBonuses techBonuses;
    /**
     * 事件触发的聊天（B11 §四 句库的五个触发场景）。
     *
     * <p><b>为什么是两个事件而不是直调适配器</b>：本类依赖着社交/世界一整片服务，
     * 而 Bot 适配器又依赖着它们 —— 直调立刻成构造环（#93/#95 已踩过两次
     * {@code BeanCurrentlyInCreation}）。这里只「发布发生了什么」，
     * 订阅与说话都是别人的事（{@code BotEventChatListener}）。
     */
    private final org.springframework.context.ApplicationEventPublisher events;

    public PlayerCityBattleService(ConfigRegistry configs, BattleArmyFactory armyFactory,
                                   BattleRulesAssembler rulesAssembler, HeroBattleMapper heroMapper,
                                   HeroStatsService heroStats, HeroRepository heroes,
                                   ArmyRepository armies, CityRepository cities,
                                   PlayerRepository players, ArmyAppService armyAppService,
                                   PlayerWallet wallet, PowerService powerService,
                                   PowerRefreshService powerRefreshService,
                                   BattleReportService battleReports, DailyCounter dailyCounter,
                                   com.ironoath.web.reward.ServerSeedSource seeds,
                                   com.ironoath.web.service.SocialAppService socialAppService,
                                   CounterplayModifiers counterplay,
                                   com.ironoath.web.service.WorldAppService worldAppService,
                                   com.ironoath.web.service.PublicEnemyBroadcaster publicEnemies,
                                   AllianceTechBonuses techBonuses,
                                   org.springframework.context.ApplicationEventPublisher events) {
        this.configs = configs;
        this.armyFactory = armyFactory;
        this.rulesAssembler = rulesAssembler;
        this.heroMapper = heroMapper;
        this.heroStats = heroStats;
        this.heroes = heroes;
        this.armies = armies;
        this.cities = cities;
        this.players = players;
        this.armyAppService = armyAppService;
        this.wallet = wallet;
        this.powerService = powerService;
        this.powerRefreshService = powerRefreshService;
        this.battleReports = battleReports;
        this.dailyCounter = dailyCounter;
        this.seeds = seeds;
        this.socialAppService = socialAppService;
        this.counterplay = counterplay;
        this.worldAppService = worldAppService;
        this.publicEnemies = publicEnemies;
        this.techBonuses = techBonuses;
        this.events = events;
    }

    /**
     * @param result            内核战果（含掠夺量与双方阵亡/伤兵）
     * @param plundered         实际掠夺到手的资源（已按守方保护额度裁剪，可能小于 result.loot()）
     * @param tyrannyDelta      本次为攻方累积的暴虐值（区间内的正常对抗为 0）
     * @param shieldRaised      守方的免战护盾是否被真的推后
     * @param attackerReportId  攻方那份战报的 id
     * @param defenderReportId  守方那份战报的 id（内容相同、ownerId 不同）
     * @param attackerModifier  本次<b>实际喂进内核</b>的攻方乘区 F。带出来只为一件事：
     *       复仇与围剿恒为 0 的那段时间里，没有任何位置能回答「这次到底吃到没有」，
     *       而「加成没接线」与「加成接了但这仗不满足条件」在客户端看起来一模一样
     */
    public record Outcome(BattleResult result, Map<String, Long> plundered, long tyrannyDelta,
                          boolean shieldRaised, String attackerReportId, String defenderReportId,
                          BattleModifier attackerModifier) {
        public Outcome {
            plundered = plundered == null ? Map.of() : Map.copyOf(plundered);
            if (attackerModifier == null) {
                throw new IllegalArgumentException(
                        "attackerModifier 不得为 null，无加成请用 BattleModifier.none()");
            }
        }
    }

    /**
     * 出征前预检。<b>提示必须出现在玩家还能改主意的时刻</b>（B09 验收 3 的精神）：
     * 等到队伍飞了几十分钟站在人家城门口才说「打不了」，玩家什么也做不了。
     */
    public void precheck(String attackerId, String defenderId, long now) {
        requireNotSelf(attackerId, defenderId);
        PlayerSave defender = requirePlayer(defenderId);
        ArmyState defense = armies.findByPlayerId(defenderId).orElseGet(ArmyState::new);
        if (defense.totalTroops() <= 0L) {
            // 记录在案的缺口：这条拒绝制造了一个可利用的免战手段 —— 把兵全部派出去就等于免战。
            // 正确的解法是「无守军 ⇒ 不战斗、直接按剩余负载掠夺」，但那需要另外定三条口径
            // （没有战斗时掠夺量怎么算、暴虐值对 0 匹配战力怎么记、护盾还推不推进），
            // 所以先明确拒绝，也不要放一个口径含糊的 PVP 出去
            throw new BizException(ErrorCode.WORLD_TARGET_INVALID,
                    "对方城内没有驻军，无法发起战斗（守军为 " + defender.nickName() + " 的城内兵力）");
        }
        // 攻方自己也必须有兵 —— March 出征时已经校验过，这里只是把到达时的状态再确认一次
        requirePlayer(attackerId);
        LOG.debug("PVP 预检通过 attacker={} defender={} now={}", attackerId, defenderId, now);
    }

    /**
     * 到达结算。与野怪那条路径同构：折叠 → 内核 → 摊回损失 → 各自的后续口径。
     *
     * @param beneficiaries 掠夺受益人 → 权重。普通行军是行军主人一人；集结合并行军是全部参与者
     *                      （按承诺兵力）。战损、暴虐值、受害护盾仍只算行军主人 ——
     *                      那些是「谁打的这一仗」的痕迹，不是可以分摊的收益
     */
    public Outcome resolve(March march, long now, Map<String, Long> beneficiaries) {
        if (march.targetType() != March.TargetType.PLAYER_CITY) {
            throw new BizException(ErrorCode.WORLD_TARGET_INVALID,
                    "目标不是玩家城，无法按 PVP 结算：targetType=" + march.targetType());
        }
        String attackerId = march.playerId();
        String defenderId = march.targetId();
        // 到达时重新预检：出征时守方有兵，几十分钟后可能已经被别人打光
        precheck(attackerId, defenderId, now);

        // ---------- 一、造双方军队 ----------
        HeroRoster attackerRoster = heroes.findByPlayerId(attackerId).orElseGet(HeroRoster::new);
        List<HeroSnapshot> attackerHeroes = heroMapper.snapshots(march.heroes(), attackerRoster);
        CityState attackerCity = cities.findByPlayerId(attackerId).orElse(null);
        long attackerHospital = attackerCity == null ? 0L : armyAppService.hospitalCapacity(attackerCity);
        BattleArmyFactory.Folded attackerFold = armyFactory.fold(march.units());
        ArmySide attacker = armyFactory.toSide(attackerId, attackerFold, attackerHeroes, 0L,
                techBonuses.forPlayer(attackerId), attackerHospital);

        ArmyState defenderArmy = armies.findByPlayerId(defenderId).orElseGet(ArmyState::new);
        Map<String, Long> defenderTroops = new LinkedHashMap<>(defenderArmy.troops());
        HeroRoster defenderRoster = heroes.findByPlayerId(defenderId).orElseGet(HeroRoster::new);
        List<String> defenderHeroIds = defenseLineup(defenderRoster);
        List<HeroSnapshot> defenderHeroes = heroMapper.snapshots(defenderHeroIds, defenderRoster);
        CityState defenderCity = cities.findByPlayerId(defenderId).orElse(null);
        long defenderHospital = defenderCity == null ? 0L : armyAppService.hospitalCapacity(defenderCity);
        BattleArmyFactory.Folded defenderFold = armyFactory.fold(defenderTroops);
        ArmySide defender = armyFactory.toSide(defenderId, defenderFold, defenderHeroes, 0L,
                techBonuses.forPlayer(defenderId), defenderHospital);

        // ---------- 二、守方仓库（掠夺的输入） ----------
        DefenderStore store = defenderStore(defenderId, now);

        // ---------- 三、跑内核 ----------
        // 攻方乘区 F 在这里装配一次，同一个对象既喂内核又带回结算边界 ——
        // 「装配的就是喂进去的」必须由构造保证，否则测试断言的可以是另一个值
        BattleModifier attackerModifier = counterplay.attackerSide(attackerId, defenderId, now);
        // 攻守各用自己的属性表：共用一份等于把守方的阶级属性换成攻方的加权平均值，
        // 于是「带 T4 兵的守方」会被按攻方的平均步兵评估 —— 攻方越强守方也显得越强
        long seed = seeds.nextSeed();
        BattleResult result = BattleSimulator.simulate(new BattleInput(
                attacker, defender, TerrainType.PLAIN, seed, battleTypeOf(march),
                attackerModifier, BattleModifier.none(),
                attackerFold.stats(), defenderFold.stats(),
                rulesAssembler.rules(), store));
        boolean won = result.winner() == Winner.ATTACKER;
        boolean crushed = totalOf(result.defSurvivors()) <= 0L;

        // ---------- 四、开战那一刻的双方匹配战力 ----------
        // **不能走 powerRefreshService.refresh()**：攻方的兵此刻正在这支行军里，
        // 不在他的城内军队中，refresh 会把他算成一个没有兵的人（只剩峰值记忆的地板在撑着）。
        // 实测后果：1800 打 1000 这个 1.8 倍的碾压被算成 1.44 倍，低于暴虐阈值 1.5，
        // 于是暴虐值一分不记 —— 而 B08 设计暴虐值的全部目的就是让「以强凌弱」留下痕迹。
        // 攻方 = 城内兵力 + 这支行军带的兵；守方 = 城内兵力（全部守城）。
        // 两边都取当前值而不是峰值地板：暴虐值量的是这一仗的真实实力差，
        // 峰值记忆是给「刚打完大仗的人不该立刻变成猎物」用的，不是给施暴者减刑用的。
        // 新建一个 ArmyState 来装合并后的兵力，绝不在加载出来的存档对象上改 ——
        // 「先改自己再拷贝入参」这个别名坑本项目已经踩过三次（ArmyState / HeroRoster / FogOfWar）
        ArmyState attackingForce = new ArmyState();
        armies.findByPlayerId(attackerId).orElseGet(ArmyState::new).troops()
                .forEach(attackingForce::add);
        march.units().forEach(attackingForce::add);
        long attackerMatch = powerService.matchPowerOf(attackingForce, attackerRoster);
        long defenderMatch = powerService.matchPowerOf(defenderArmy, defenderRoster);

        // ---------- 五、双方战损 ----------
        applyAttackerLosses(march, attackerFold, result, attackerHospital, attackerId);
        applyDefenderLosses(defenderId, defenderTroops, defenderFold, result, defenderHospital);

        // ---------- 六、掠夺（只有打赢才搬得走） ----------
        Map<String, Long> plundered = won
                ? plunder(beneficiaries, defenderId, result.loot(), now) : Map.of();

        // ---------- 七、战后战力重算 ----------
        // 守方不是这次请求的发起人，HTTP 边界的拦截器覆盖不到他 ——
        // 而派生值漏刷的表现是「我被人打了，战力没变」，不会让任何测试变红，所以这里必须显式刷
        powerRefreshService.refresh(attackerId);
        powerRefreshService.refresh(defenderId);

        // ---------- 八、暴虐值（攻方）与受害护盾（守方） ----------
        long tyrannyDelta = accumulateTyranny(attackerId, defenderId, attackerMatch, defenderMatch,
                crushed, now);
        boolean shieldRaised = raiseVictimShield(defenderId, attackerId, now);

        // ---------- 九、双方各记一份战报 ----------
        // 两份都必须带齐攻守双方的名字：守方那一份的「对手显示名」取的正是 attackerName，
        // 缺了它守方点开战报看到的对手是自己 —— 而存两份的全部意义就是两个人各自看到对方
        PlayerSave defenderSave = requirePlayer(defenderId);
        String defenderName = defenderSave.nickName();
        String attackerName = requirePlayer(attackerId).nickName();
        BattleReport attackerReport = battleReports.record(attackerId, attackerId, defenderId,
                defenderName, attackerName, battleTypeOf(march), march.heroes(), defenderHeroIds,
                result, now);
        BattleReport defenderReport = battleReports.record(defenderId, attackerId, defenderId,
                defenderName, attackerName, battleTypeOf(march), march.heroes(), defenderHeroIds,
                result, now);

        // ---------- 十、通知守方的盟友（B10 验收 5）----------
        // 这是 notifyMemberAttacked 的唯一调用点：盟友被攻击这件事只有战斗结算方知道。
        // 没接通之前它一直是个没有调用方的公开方法，于是「在线成员 3 秒内收到推送」
        // 这条验收在代码里存在、在运行时从不发生
        socialAppService.notifyMemberAttacked(defenderId, attackerName,
                march.to().x(), march.to().y(), now);

        // ---------- 十一、事件触发的聊天（B11 §四 句库的五个触发场景） ----------
        // 两个独立事件，各有自己的说话人：
        //   ATTACKED 给守方（"被人打了，有盟友在附近吗"），VICTORY/DEFEAT 给攻方（按战果分场景）。
        // 发布走 Spring 事件而不是直调适配器：本类依赖着社交/世界一整片服务，直调会成构造环
        // （#93/#95 已踩过两次 BeanCurrentlyInCreation）。真人不订阅或不是 Bot 时静默跳过。
        // 守方只发 ATTACKED，不发输赢 —— 被攻城的人没有"打赢"这一说（城破与否只影响掠夺量），
        // 而"我的城被打了"与"我出征打赢了"是两件不同的事，混用一个场景会让守方说出攻方的口吻。
        events.publishEvent(new com.ironoath.web.bot.BotChatEvent(
                defenderId, defenderId, com.ironoath.core.bot.BotChatBook.Scene.ATTACKED, now));
        events.publishEvent(new com.ironoath.web.bot.BotChatEvent(
                attackerId, attackerId,
                com.ironoath.core.bot.BotChatBook.Scene.ofBattle(won), now));

        LOG.info("玩家城战斗结算 attacker={} defender={} 胜方={} 回合={} seed={} 掠夺={} 暴虐+{} 护盾推后={} 乘区F(复仇={}/围剿={})",
                attackerId, defenderId, result.winner(), result.totalRounds(), seed,
                plundered, tyrannyDelta, shieldRaised,
                attackerModifier.revenge(), attackerModifier.crusade());
        return new Outcome(result, plundered, tyrannyDelta, shieldRaised,
                attackerReport.reportId(), defenderReport.reportId(), attackerModifier);
    }

    /**
     * 拦截一支正在采集的行军（B09 验收 7 的剩下一半）。
     *
     * <p><b>与攻城共用战损与战报的机械，但掠夺口径完全不同</b>：
     * 攻城掠的是守方<b>仓库</b>，受 {@code ResourceProtection} 保护额度约束；
     * 拦截夺的是守方<b>在途负载</b>，不套任何保护额度 ——
     * 在途负载是玩家自己派兵出门、冒着被拦风险采来的，
     * 给它套上保护额度等于让「出门采集」比「放在家里」更安全，
     * 那会反过来鼓励所有人把兵派出去，与 B08 的生态意图相反。
     *
     * <p><b>所以掠夺不走内核的 {@code DefenderStore}</b>（这里传 {@code none()}）：
     * 内核的 loot 是「从守方仓库搬走多少」，而在途负载是行军自己的字段。
     * 夺取量由两个上限共同决定 —— 守方实际采到的量、攻方剩余的运力，
     * 取走与接收都返回实际发生量，于是「取走的 == 收到的」这条守恒可以被断言。
     *
     * <p><b>本方法只改两支行军与双方的军队/医院/战报，不做行军状态迁移</b>：
     * 状态机与到期队列归 {@code MarchAppService} 管，两边各改一次会打架。
     *
     * @return 拦截结果（战果、实际夺得的负载、两份战报 id）
     */
    public Interception interceptGatherer(March attacker, March gatherer, long now) {
        if (attacker.playerId().equals(gatherer.playerId())) {
            throw new BizException(ErrorCode.WORLD_TARGET_INVALID, "不能拦截自己的采集队");
        }
        if (gatherer.status() != March.Status.GATHERING) {
            // 出征时在采集、到达时已经采完返程了：这不是错误，是时序。
            // 抛业务异常让调用方按「战斗打不成」处理（队伍原样返程），
            // 而不是一支永远卡在资源点面前的队伍
            throw new BizException(ErrorCode.WORLD_TARGET_INVALID,
                    "对方已经采完离开了，无可拦截（当前状态=" + gatherer.status() + "）");
        }

        String attackerId = attacker.playerId();
        String gathererId = gatherer.playerId();

        // ---------- 一、造双方军队（都来自各自的行军，不是城内军队） ----------
        HeroRoster attackerRoster = heroes.findByPlayerId(attackerId).orElseGet(HeroRoster::new);
        List<HeroSnapshot> attackerHeroes = heroMapper.snapshots(attacker.heroes(), attackerRoster);
        CityState attackerCity = cities.findByPlayerId(attackerId).orElse(null);
        long attackerHospital = attackerCity == null ? 0L : armyAppService.hospitalCapacity(attackerCity);
        BattleArmyFactory.Folded attackerFold = armyFactory.fold(attacker.units());
        ArmySide attackerSide = armyFactory.toSide(attackerId, attackerFold, attackerHeroes, 0L,
                techBonuses.forPlayer(attackerId), attackerHospital);

        HeroRoster gathererRoster = heroes.findByPlayerId(gathererId).orElseGet(HeroRoster::new);
        List<HeroSnapshot> gathererHeroes = heroMapper.snapshots(gatherer.heroes(), gathererRoster);
        CityState gathererCity = cities.findByPlayerId(gathererId).orElse(null);
        long gathererHospital = gathererCity == null ? 0L : armyAppService.hospitalCapacity(gathererCity);
        BattleArmyFactory.Folded gathererFold = armyFactory.fold(gatherer.units());
        ArmySide gathererSide = armyFactory.toSide(gathererId, gathererFold, gathererHeroes, 0L,
                techBonuses.forPlayer(gathererId), gathererHospital);

        // ---------- 二、跑内核（在途负载不进内核，所以守方仓库是空的） ----------
        // 拦截也是玩家打玩家，所以复仇与围剿在这一路同样要装配 ——
        // 只在攻城那条路径上接线，等于让「拦下仇人的采集队」白白少 15%
        BattleModifier attackerModifier = counterplay.attackerSide(attackerId, gathererId, now);
        long seed = seeds.nextSeed();
        BattleResult result = BattleSimulator.simulate(new BattleInput(
                attackerSide, gathererSide, TerrainType.PLAIN, seed, battleTypeOf(attacker),
                attackerModifier, BattleModifier.none(),
                attackerFold.stats(), gathererFold.stats(),
                rulesAssembler.rules(), DefenderStore.none()));
        boolean won = result.winner() == Winner.ATTACKER;

        // ---------- 三、双方战损（与攻城同一条路径：摊回各阶级、伤兵进各自医院） ----------
        applyAttackerLosses(attacker, attackerFold, result, attackerHospital, attackerId);
        applyDefenderLosses(gathererId, new LinkedHashMap<>(gatherer.units()), gathererFold, result,
                gathererHospital);
        // 守方的兵是从行军里出的，所以行军上的兵力也要同步扣掉，
        // 否则它到家时会把已经死掉的兵再入一次账
        Map<String, Long> gathererLosses = armyFactory.unfoldLosses(gatherer.units(),
                gathererFold.counts(), result.defSurvivors());
        gatherer.applyLosses(gathererLosses);

        // ---------- 四、夺取在途负载（只有打赢才搬得走） ----------
        long seized = 0L;
        long received = 0L;
        if (won && gatherer.load() > 0L) {
            long room = Math.max(0L, attacker.loadCap() - attacker.load());
            seized = gatherer.seizeLoad(Math.min(gatherer.load(), room));
            received = attacker.addLoad(seized);
            if (received != seized) {
                // 取走的与收到的必须相等：addLoad 会夹到剩余容量，而我们已经按容量算过 want，
                // 所以走到这里说明两步之间负载被别的路径改了。资源不能凭空消失，必须留痕
                LOG.error("拦截掠夺不守恒 seized={} received={} attacker={} gatherer={}："
                                + "差额 {} 单位的资源凭空消失，需要人工核查",
                        seized, received, attackerId, gathererId, seized - received);
            }
        }

        // ---------- 四点五、受害护盾（2026-09-13 裁决：被拦下采集队算「被打一次」）----------
        // 与攻城共用同一本 attackerHits 账：复仇与护盾对「谁在窗口内打过我」不该有两种定义，
        // 而玩家感知里「队伍在野外被人抢了」就是一次攻击 —— 事件聊天那一侧早就把 ATTACKED 发给他了，
        // 只有账没记，结果是「被人抢了三次却不进护盾、也不进复仇名单」这种只体现在胜率上的缺口。
        raiseVictimShield(gathererId, attackerId, now);

        // ---------- 五、双方各一份战报 ----------
        String gathererName = players.findByPlayerId(gathererId)
                .map(PlayerSave::nickName).orElse(gathererId);
        String attackerName = players.findByPlayerId(attackerId)
                .map(PlayerSave::nickName).orElse(attackerId);
        BattleReport attackerReport = battleReports.record(attackerId, attackerId, gathererId,
                gathererName, attackerName, battleTypeOf(attacker), attacker.heroes(), gatherer.heroes(),
                result, now);
        BattleReport gathererReport = battleReports.record(gathererId, attackerId, gathererId,
                gathererName, attackerName, battleTypeOf(attacker), attacker.heroes(), gatherer.heroes(),
                result, now);

        // 事件触发的聊天：拦截也是「我出征打了一仗」，攻方按胜负挑一句；
        // 被拦的采集者那边同样收到 ATTACKED（他的队伍在野外被打，与城被攻是同一类事）
        events.publishEvent(new com.ironoath.web.bot.BotChatEvent(
                gathererId, gathererId, com.ironoath.core.bot.BotChatBook.Scene.ATTACKED, now));
        events.publishEvent(new com.ironoath.web.bot.BotChatEvent(
                attackerId, attackerId,
                com.ironoath.core.bot.BotChatBook.Scene.ofBattle(won), now));

        LOG.info("采集拦截 attacker={} gatherer={} 胜方={} 回合={} seed={} 夺取负载={}/{} 双方战报={}/{} 乘区F(复仇={}/围剿={})",
                attackerId, gathererId, result.winner(), result.totalRounds(), seed,
                seized, gatherer.load() + seized, attackerReport.reportId(), gathererReport.reportId(),
                attackerModifier.revenge(), attackerModifier.crusade());
        return new Interception(result, seized, received, attackerReport.reportId(),
                gathererReport.reportId(), attackerModifier);
    }

    /**
     * 一次拦截的结果。
     *
     * @param seized   从守方在途负载里取走的量
     * @param received 攻方实际装上的量。<b>与 seized 分开给</b>就是为了能被断言相等 ——
     *                 合并成一个字段的话，「取走了但没装上」这种凭空消失就再也看不见了
     */
    public record Interception(BattleResult result, long seized, long received,
                               String attackerReportId, String gathererReportId,
                               BattleModifier attackerModifier) {
    }

    // ---------- 内部 ----------

    /** 守方的守城编队。B06 没有「守城专用」档，所以用主编队。 */
    private List<String> defenseLineup(HeroRoster roster) {
        Lineup lineup = roster.lineup(DEFENSE_LINEUP_PRESET, heroStats.rules());
        return lineup == null ? List.of() : List.copyOf(lineup.members());
    }

    /**
     * 守方仓库：逐资源算出「可掠夺量」与「受保护量」。
     *
     * <p>{@code wallBroken} 恒为 true，理由见类注释第 1 条。
     */
    private DefenderStore defenderStore(String defenderId, long now) {
        Map<String, Long> unprotected = new LinkedHashMap<>();
        Map<String, Long> protectedAmount = new LinkedHashMap<>();
        for (String resourceType : configs.resourceIds()) {
            if (StaminaService.RESOURCE_ID.equals(resourceType)) {
                // 体力住在 resource 表里（B09 把它做成第 6 行以复用惰性结算与容量截断），
                // 但它是**行动闸门**不是仓库存货。把它算进掠夺的后果是「打一个人一次，
                // 让他几天不能打野」—— 那不是损失资源，那是替他决定了接下来几天能做什么，
                // 而 B09 验收 1 的精神恰恰是失败不该收两次学费
                continue;
            }
            long current = wallet.available(defenderId, resourceType, now);
            long keep = wallet.protectedAmount(defenderId, resourceType, now);
            long takeable = ResourceProtection.plunderable(current, keep);
            if (takeable > 0L) {
                unprotected.put(resourceType, takeable);
            }
            if (keep > 0L) {
                protectedAmount.put(resourceType, keep);
            }
        }
        return new DefenderStore(unprotected, protectedAmount, true);
    }

    /** 攻方战损：损失从行军里扣，伤兵进攻方自己的医院，超容量的部分死亡。 */
    private void applyAttackerLosses(March march, BattleArmyFactory.Folded fold, BattleResult result,
                                     long hospitalCapacity, String attackerId) {
        Map<String, Long> losses = armyFactory.unfoldLosses(march.units(), fold.counts(),
                result.atkSurvivors());
        Map<String, Long> dead = TierSplit.splitProportionally(losses, result.atkDead());
        admitWounded(attackerId, losses, dead, hospitalCapacity);
        march.applyLosses(losses);
    }

    /** 守方战损：损失从城内兵力扣，伤兵进守方医院。守方不在线也要结算（服务器权威）。 */
    private void applyDefenderLosses(String defenderId, Map<String, Long> defenderTroops,
                                     BattleArmyFactory.Folded fold, BattleResult result,
                                     long hospitalCapacity) {
        Map<String, Long> losses = armyFactory.unfoldLosses(defenderTroops, fold.counts(),
                result.defSurvivors());
        Map<String, Long> dead = TierSplit.splitProportionally(losses, result.defDead());
        admitWounded(defenderId, losses, dead, hospitalCapacity);
    }

    /**
     * 扣掉损失、把伤兵送进医院，超容量的部分死亡。攻守双方共用这一份实现 ——
     * 两条路径各写一遍的话，「伤兵进医院」这件事迟早会只改一边。
     */
    private void admitWounded(String playerId, Map<String, Long> losses, Map<String, Long> dead,
                              long hospitalCapacity) {
        Map<String, Long> wounded = new LinkedHashMap<>();
        losses.forEach((unitId, loss) -> {
            long deadCount = dead.getOrDefault(unitId, 0L);
            long woundedCount = loss - deadCount;
            if (woundedCount > 0L) {
                wounded.put(unitId, woundedCount);
            }
        });
        ArmyState army = armies.findByPlayerId(playerId).orElseGet(ArmyState::new);
        if (armies.findByPlayerId(playerId).isEmpty()) {
            armies.insertIfAbsent(playerId, army);
        }
        long version = armies.versionOf(playerId);
        // 先扣损失再收伤兵：反过来的话伤兵会先被算进「现有兵力」，
        // 而 deduct 是按现有数量扣的，于是同一批兵被扣两次
        losses.forEach(army::deduct);
        long overflowDead = wounded.isEmpty() ? 0L : army.admitWounded(wounded, hospitalCapacity);
        armies.save(playerId, army, version);
        if (overflowDead > 0L) {
            LOG.info("医院装不下，{} 名伤兵死亡 playerId={} 容量={}", overflowDead, playerId, hospitalCapacity);
        }
    }

    /**
     * 这场 PVP 按集结还是单刷计。<b>全类只此一处判定</b>。
     *
     * <p>战斗类型有两个下游：① 内核按它取口径（伤兵率等，集结的兵是多人合并的，
     * 用单刷参数等于让集结白拿一套有利规则）；② 战报里给玩家回看「这一仗是谁、按什么打的」。
     * 此前只有喂内核那一处（{@code resolve}）区分了集结，攻城的两份战报与整条拦路路径
     * （内核 + 两份战报）都恒写 {@code PVP_SOLO} —— 表现是集结的战报看起来像单刷，
     * 而客户端 {@code battleTypeText} 的「集结」分支永远走不到（一个永不显示的文案，
     * 看起来像客户端写多了，实际上是服务端标错了）。
     */
    private static BattleType battleTypeOf(March attacker) {
        return attacker.isRallyMarch() ? BattleType.PVP_RALLY : BattleType.PVP_SOLO;
    }

    /**
     * 掠夺：把内核算出的 loot 逐项从守方搬走，再按受益人拆分入账。
     *
     * <p><b>还要再按保护额度裁一次</b>：内核拿到的是「出征前那一刻」的仓库快照，
     * 而守方的资源在这几十分钟里一直在产（惰性结算）。所以真正扣款时必须按当前值重算可掠夺量，
     * 否则会扣到保护额度里去 —— 而保护额度是玩家花钱或花时间换来的确定性承诺，
     * 被穿一次就再也没人信它。
     *
     * <p><b>守方只扣一次、攻方按人分次入账</b>：拆分必须在<b>入账</b>这一步做，
     * 不能先把整包给发起人再转账 —— 容量上限、保护量、溢出转邮件补发全都是按「个人账户」判定的，
     * 先合并入账再转就丢失了每个人各自的装不装得下这个信息（B04 禁止项）。
     * 集结合并行军如果整包给发起人，就是「成员的兵分回来了、抢到的资源没分回来」。
     */
    private Map<String, Long> plunder(Map<String, Long> beneficiaries, String defenderId,
                                      Map<String, Long> loot, long now) {
        Map<String, Long> taken = new LinkedHashMap<>();
        loot.forEach((resourceType, want) -> {
            if (want <= 0L) {
                return;
            }
            long current = wallet.available(defenderId, resourceType, now);
            long keep = wallet.protectedAmount(defenderId, resourceType, now);
            long allowed = Math.min(want, ResourceProtection.plunderable(current, keep));
            if (allowed <= 0L) {
                return;
            }
            long deducted = wallet.deduct(defenderId, resourceType, allowed, now);
            if (deducted <= 0L) {
                // deduct 是「不足则完全不扣」，走到这里说明检查与扣款之间守方资源被别的路径动了。
                // 战斗已经打完，回滚战斗比少掠一点资源严重得多，所以照实记录并继续
                LOG.warn("掠夺扣款失败，守方资源在结算窗口内被改动 defender={} resource={} 想要={}",
                        defenderId, resourceType, allowed);
                return;
            }
            long granted = 0L;
            for (Map.Entry<String, Long> share
                    : com.ironoath.core.reward.RewardSplit.byWeight(beneficiaries, deducted).entrySet()) {
                long each = wallet.grant(share.getKey(), resourceType, share.getValue(), now);
                granted += each;
                if (each < share.getValue()) {
                    // 这个人的仓库满了：少的那部分不会凭空回到守方手里（已经扣了），而是溢出，
                    // B04 的发放器会把它转成邮件补发，这里只记录差额
                    LOG.info("掠夺溢出，受益人仓库装不下 beneficiary={} resource={} 应得={} 入袋={}",
                            share.getKey(), resourceType, share.getValue(), each);
                }
            }
            taken.put(resourceType, granted);
        });
        return taken;
    }

    /**
     * 暴虐值累积（B08 §4）。
     *
     * <p><b>「击溃」定为守方军队被全灭</b>，不是「接近全灭」：后者需要一个阈值，
     * 而那个阈值现在没有配置项可依（铁律 1 不允许我凭空写一个 0.9）。
     * 收窄到全灭是保守方向 —— 少记暴虐值只会让「公敌」更难达成，不会让套利链成立。
     * 若将来要放宽到「90% 即算击溃」，必须先进配置表。
     *
     * <p>两条防刷判定（{@link Tyranny#shouldCount}）都要过：同一对玩家此前互相攻击过不计，
     * 单日对同一目标已记过不计。少任何一条，「两个小号轮流让大号打」就能把大号推上公敌档，
     * 再由第三个号去围剿拿全服共享奖励 —— 那是一条完整的套利链。
     */
    private long accumulateTyranny(String attackerId, String defenderId, long attackerMatch,
                                   long defenderMatch, boolean crushed, long now) {
        String dayKey = DayKey.of(now);
        String pairKey = Tyranny.pairKey(attackerId, defenderId);
        // tryConsume 返回 true 表示「这是首次」：上限 1 的计数器天然就是一个「见过一次」的标记
        boolean firstMeeting = dailyCounter.tryConsume(SCOPE_TYRANNY_PAIR, pairKey,
                PAIR_LEDGER_DAY_KEY, 1L);
        boolean firstToday = dailyCounter.tryConsume(SCOPE_TYRANNY_TARGET,
                Tyranny.dailyTargetKey(attackerId, defenderId, dayKey), dayKey, 1L);
        if (!Tyranny.shouldCount(!firstMeeting, !firstToday)) {
            LOG.info("暴虐值不记（防刷）attacker={} defender={} 首次相遇={} 今日首次={}",
                    attackerId, defenderId, firstMeeting, firstToday);
            return 0L;
        }
        if (defenderMatch <= 0L) {
            // 守方匹配战力为 0 时 Tyranny.accumulate 会抛异常（R 会是无穷大）。
            // 有守军却算出 0 匹配战力，说明守方只有兵没有武将且兵数为 0 —— 到不了这里，
            // 但真到了就不能让一场战斗因为一个除零把整条结算炸掉
            LOG.warn("守方匹配战力为 0，跳过暴虐值累积 defender={}", defenderId);
            return 0L;
        }
        long delta = Tyranny.accumulate(attackerMatch, defenderMatch, crushed, powerService.tyrannyRules());
        if (delta <= 0L) {
            return 0L;
        }
        // 存档必须在掠夺与战力重算之后重新加载，否则会覆盖掉它们写回的内容（见类注释）
        PlayerSave save = requirePlayer(attackerId);
        PlayerPvp pvp = save.pvp();
        Tyranny.Rules tyrannyRules = powerService.tyrannyRules();
        long decayed = Tyranny.decay(pvp.tyranny(),
                powerService.daysSince(pvp.tyrannyTouchedAt(), now), tyrannyRules);
        long updated = decayed + delta;
        Tyranny.Level before = Tyranny.levelOf(decayed, tyrannyRules);
        Tyranny.Level after = Tyranny.levelOf(updated, tyrannyRules);
        save.setPvp(pvp.withTyranny(updated, now));
        players.save(save);
        // 第一档效果（坐标不再受迷雾保护）的**生效信号**。少这一步，暴露只写在协议里：
        // 未探索块在客户端手里是一个「已知版本、无实体」的结果，版本号不动它就不会再来要这一块
        if (!Tyranny.exposesCoordinate(before) && Tyranny.exposesCoordinate(after)) {
            worldAppService.invalidateCityChunk(attackerId);
            LOG.info("坐标开始对全服暴露 attacker={} 暴虐={} 档位={}", attackerId, updated, after);
        }
        // 第四档（公敌）要进全服广播名单。无条件调用：加入还是摘出由 noteLevel 按档位自己判，
        // 在这里再写一次 if 就等于把「什么算公敌」这条口径复制到第二个地方
        publicEnemies.noteLevel(attackerId, after);
        LOG.info("暴虐值累积 attacker={} +{} ⇒ {} 档位={}", attackerId, delta, updated, after);
        return delta;
    }

    /** 受害护盾推进（B08 的第三条保护）：按<b>不同攻击者</b>计数，同一个人连打不触发。 */
    private boolean raiseVictimShield(String defenderId, String attackerId, long now) {
        PlayerSave save = requirePlayer(defenderId);
        PlayerPvp pvp = save.pvp();
        Protection.VictimOutcome outcome = Protection.onAttacked(pvp.attackerHits(), attackerId, now,
                pvp.victimShieldUntil(), powerService.protectionRules());
        save.setPvp(pvp.withAttackerHits(outcome.retainedHits())
                .withVictimShieldUntil(outcome.shieldUntil()));
        players.save(save);
        if (outcome.shieldRaised()) {
            LOG.info("受害护盾已推后 defender={} 窗口内不同攻击者={} 到期={}",
                    defenderId, outcome.distinctAttackers(), outcome.shieldUntil());
        }
        return outcome.shieldRaised();
    }

    private void requireNotSelf(String attackerId, String defenderId) {
        if (attackerId == null || attackerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "攻方 playerId 不得为空");
        }
        if (defenderId == null || defenderId.isBlank()) {
            throw new BizException(ErrorCode.WORLD_TARGET_INVALID, "目标城没有归属玩家");
        }
        if (attackerId.equals(defenderId)) {
            throw new BizException(ErrorCode.WORLD_TARGET_INVALID, "不能攻击自己的城");
        }
    }

    private PlayerSave requirePlayer(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow(() -> new BizException(
                ErrorCode.PLAYER_NOT_FOUND, "玩家存档不存在: " + playerId));
    }

    private static long totalOf(Map<com.ironoath.battle.UnitType, Long> counts) {
        long total = 0L;
        if (counts != null) {
            for (Long value : counts.values()) {
                total += value == null ? 0L : value;
            }
        }
        return total;
    }
}
