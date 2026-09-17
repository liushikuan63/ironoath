package com.ironoath.web.battle;

import com.ironoath.battle.ArmySide;
import com.ironoath.battle.BattleInput;
import com.ironoath.battle.BattleResult;
import com.ironoath.battle.BattleSimulator;
import com.ironoath.battle.BattleType;
import com.ironoath.battle.HeroSnapshot;
import com.ironoath.battle.TechBonus;
import com.ironoath.battle.TerrainType;
import com.ironoath.battle.UnitType;
import com.ironoath.battle.BattleModifier;
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.time.DayKey;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.ConfigException;
import com.ironoath.config.cfg.MapmonsterCfg;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.army.TierSplit;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.hero.HeroRoster;
import com.ironoath.core.limit.DailyCounter;
import com.ironoath.core.march.March;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.service.ArmyAppService;
import com.ironoath.web.service.StaminaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：野怪讨伐的战斗结算（B09 §1）—— 造双方军队、跑内核、摊回战损、发掉落、扣体力、限次。
 * 依赖：{@link BattleArmyFactory}（兵种折算）、{@link HeroBattleMapper}（武将快照）、
 * {@link BattleRulesAssembler}（规则参数）、军队/城建/武将仓储、奖励发放器、每日计数器、钱包。
 *
 * <p><b>本类只负责「一场战斗怎么算完」，不负责行军状态机</b>：
 * 谁在什么时候到达、打完是返程还是删除、chunk 版本要不要推进，都是 {@code MarchAppService} 的事。
 * 把两件事分开的理由是它们的失败模式完全不同 ——
 * 战斗算错是数值问题（可以被平衡工具复现），行军状态错是时序问题（只能靠日志复盘）。
 *
 * <p><b>三条刻意的设计选择</b>：
 * <ol>
 *   <li><b>战斗失败不扣体力</b>（B09 验收 1 要求的明确选择）。理由：失败已经扣了兵，
 *       再扣体力等于对「正在学习这个系统的人」收两次学费；而首日体验的底线是
 *       B09 §二的「前三章零氪可通」，那要求玩家敢试。所以体力只在<b>胜利后</b>扣。
 *       检查仍然在战斗之前做（否则体力不足的人可以白打），只是扣款延后 ——
 *       两步之间玩家锁一直握着，不会被并发消费插进来。</li>
 *   <li><b>伤兵在结算当场进医院，不跟着队伍走回来</b>。{@code March} 没有「携带伤兵」的字段，
 *       加一个就要动仓储与协议；而当场收容在体验上是成立的（伤兵被送回家），
 *       玩家也能立刻看到医院被填满、可以马上开始治疗。代价是「队伍还在路上、伤兵已经到家」
 *       这个小小的时序不一致，换来的是不动行军实体。</li>
 *   <li><b>野怪掉落走配置表的固定值，不走内核的掠夺（loot）</b>。
 *       mapmonster 表逐行写了五种资源的掉落量，那是策划的产出曲线；
 *       内核的 loot 是「从守方仓库里搬走多少」，用于玩家城掠夺（B13）。
 *       给野怪造一个假仓库去被掠夺，等于把产出曲线交给负载上限决定 ——
 *       带攻城器（load 40）就能多拿资源，那是运输能力影响了掉落，口径是错的。</li>
 * </ol>
 */
@Service
public class MonsterBattleService {

    private static final Logger LOG = LoggerFactory.getLogger(MonsterBattleService.class);

    /** 每日讨伐次数的计数域。与体力购买、广告加速分域，互不占用。 */
    private static final String SCOPE_HUNT = "monster_hunt";

    /**
     * 全服 PvE 赛季配额的计数域（2026-09-13 裁决 C20）。
     *
     * <p>复用 {@link DailyCounter} 而不是新建一个存储：那个端口的周期位刻意是「周期标签」
     * （日限次传 DayKey、周限次传 WeekKey、永久额度传固定串），这里传的就是 seasonId。
     * 为一条配额另起一张表，就是给同一个事实造第二个家。
     */
    private static final String SCOPE_PVE_SEASON = "pve_season_consumed";
    /** 全服配额的 ownerId。配额是全服一份，不属于任何玩家，所以需要一个不会与玩家 id 撞上的哨兵。 */
    private static final String SERVER_OWNER = "server";

    private final ConfigRegistry configs;
    private final BattleArmyFactory armyFactory;
    private final BattleRulesAssembler rulesAssembler;
    private final HeroBattleMapper heroMapper;
    private final HeroRepository heroes;
    private final ArmyRepository armies;
    private final CityRepository cities;
    private final ArmyAppService armyAppService;
    private final RewardService rewardService;
    private final com.ironoath.core.reward.RewardPorts.Wallet wallet;
    private final DailyCounter dailyCounter;
    private final WorldRepository world;
    private final BattleReportService battleReports;
    private final com.ironoath.web.reward.ServerSeedSource seeds;
    private final BattleTechBonuses techBonuses;
    /** 本赛季的 id —— 全服 PvE 配额的周期标签。取法只有 {@code SeasonRulesAssembler} 一处。 */
    private final com.ironoath.web.season.SeasonRulesAssembler seasons;
    /**
     * 事件触发的聊天（B11 §四 句库的五个触发场景）：打野的胜负是 VICTORY / DEFEAT 两个场景的
     * 主要来源（「打赢了，掉的不多」「被打崩了，兵掉了一半」）。发布走 Spring 事件，
     * 订阅方在 web 的 bot 包内 —— 本类不认识任何 Bot 类型（依赖方向与 #96 同一条纪律）。
     */
    private final org.springframework.context.ApplicationEventPublisher events;

    public MonsterBattleService(ConfigRegistry configs, BattleArmyFactory armyFactory,
                                BattleRulesAssembler rulesAssembler, HeroBattleMapper heroMapper,
                                HeroRepository heroes, ArmyRepository armies, CityRepository cities,
                                ArmyAppService armyAppService, RewardService rewardService,
                                com.ironoath.core.reward.RewardPorts.Wallet wallet,
                                DailyCounter dailyCounter, WorldRepository world,
                                BattleReportService battleReports,
                                com.ironoath.web.reward.ServerSeedSource seeds,
                                BattleTechBonuses techBonuses,
                                com.ironoath.web.season.SeasonRulesAssembler seasons,
                                org.springframework.context.ApplicationEventPublisher events) {
        this.configs = configs;
        this.armyFactory = armyFactory;
        this.rulesAssembler = rulesAssembler;
        this.heroMapper = heroMapper;
        this.heroes = heroes;
        this.armies = armies;
        this.cities = cities;
        this.armyAppService = armyAppService;
        this.rewardService = rewardService;
        this.wallet = wallet;
        this.dailyCounter = dailyCounter;
        this.world = world;
        this.battleReports = battleReports;
        this.seeds = seeds;
        this.techBonuses = techBonuses;
        this.seasons = seasons;
        this.events = events;
    }

    /**
     * 一场野怪战斗的结果。
     *
     * @param result          内核的原始战果（含 seed，凭它可 100% 复算，铁律 4）
     * @param won             是否讨伐成功
     * @param lossesByUnitId  攻方各阶级的损失（死亡 + 伤兵）
     * @param deadByUnitId    其中真正死亡的部分
     * @param woundedByUnitId 其中成为伤兵的部分（已收容进医院，超容量的部分转为死亡）
     * @param overflowDead    因医院装不下而死亡的伤兵数
     * @param staminaCost     本场应付的体力
     * @param staminaCharged  实扣的体力（失败时为 0）
     * @param drops           胜利时发放的掉落
     */
    public record Outcome(BattleResult result,
                          boolean won,
                          Map<String, Long> lossesByUnitId,
                          Map<String, Long> deadByUnitId,
                          Map<String, Long> woundedByUnitId,
                          long overflowDead,
                          long staminaCost,
                          long staminaCharged,
                          List<RewardItem> drops) {

        public Outcome {
            lossesByUnitId = Map.copyOf(lossesByUnitId);
            deadByUnitId = Map.copyOf(deadByUnitId);
            woundedByUnitId = Map.copyOf(woundedByUnitId);
            drops = List.copyOf(drops);
            long deadSum = deadByUnitId.values().stream().mapToLong(Long::longValue).sum();
            long woundedSum = woundedByUnitId.values().stream().mapToLong(Long::longValue).sum();
            long lossSum = lossesByUnitId.values().stream().mapToLong(Long::longValue).sum();
            if (deadSum + woundedSum != lossSum) {
                // 死亡 + 伤兵必须正好等于总损失：差一个兵就意味着有兵凭空消失或凭空多出，
                // 而这不会报错，只会在玩家的兵力统计上表现为「打完一场少了莫名其妙的几个」
                throw new IllegalStateException("战损拆分不守恒：死亡 " + deadSum + " + 伤兵 " + woundedSum
                        + " != 总损失 " + lossSum);
            }
        }
    }

    /**
     * 出征前的预检：每日次数还剩吗、体力够吗。
     *
     * <p><b>为什么要在出征时就查一遍</b>：这两项是在到达时才真正消耗的，
     * 而行军要飞几分钟到几十分钟。等到队伍站在野怪面前才告诉玩家「今天打满了」，
     * 他什么也做不了 —— 那条提示必须出现在他还能改主意的时刻（B09 验收 3 的精神：
     * 提示的价值在于能不能指引下一步动作）。
     *
     * <p>预检<b>不消耗</b>任何东西：真正的占用发生在到达结算时。
     * 中间被别的行军抢走名额是可能的，那种情况由 {@code MarchAppService} 兜住
     * （队伍原样返程，不卡死在野怪面前）。
     */
    public void precheck(String playerId, String monsterId, long now) {
        MapmonsterCfg monster = requireMonster(monsterId);
        long limit = configs.longParam("MONSTER_HUNT_DAILY_LIMIT");
        long used = dailyCounter.used(SCOPE_HUNT, playerId, DayKey.of(now));
        if (used >= limit) {
            throw new BizException(ErrorCode.RATE_LIMITED,
                    "今日讨伐次数已用完（" + limit + " 次），明日 " + DayKey.of(now) + " 之后重置");
        }
        long staminaCost = monster.staminaCost();
        long available = wallet.available(playerId, StaminaService.RESOURCE_ID, now);
        if (available < staminaCost) {
            throw new BizException(ErrorCode.RESOURCE_NOT_ENOUGH,
                    "体力不足：讨伐 " + monster.name() + " 需要 " + staminaCost
                            + " 点，当前 " + available + " 点。体力每 " + recoverMinutes()
                            + " 分钟恢复 1 点，也可以用金币购买");
        }
        // C20 的全服配额同样要在出征时查（只读，不消耗）：配额满了还放行，
        // 玩家就要白飞几十分钟才被拒 —— 那正是这条预检存在要消除的形状
        long seasonCap = pveSeasonConsumeCap();
        String seasonId = seasons.timelineRules().seasonId();
        if (dailyCounter.used(SCOPE_PVE_SEASON, SERVER_OWNER, seasonId) >= seasonCap) {
            throw new BizException(ErrorCode.RATE_LIMITED,
                    "本赛季全服可讨伐的野怪已达上限（" + seasonCap + " 格，赛季 " + seasonId
                            + "）。野怪不会定时刷新，恢复要等下赛季");
        }
    }

    /**
     * 结算一支已到达野怪的行军。
     *
     * <p>调用方（{@code MarchAppService.advance}）负责行军状态与地图；本方法只改
     * 行军携带的兵力、玩家的军队/医院/体力/资源，以及消耗掉地图上的这只野怪。
     *
     * @param beneficiaries 掉落受益人 → 权重。<b>普通行军就是发起人一人</b>；集结合并行军传全部
     *                      参与者（按承诺兵力）。每日讨伐次数与体力<b>仍然只算行军主人一人</b> ——
     *                      一支行军就是一次讨伐，把次数摊到成员头上会让名额统计与玩家看到的面板对不上
     * @throws BizException 每日次数耗尽、体力不足、或目标不是野怪
     */
    public Outcome resolve(March march, long now, Map<String, Long> beneficiaries) {
        if (march.targetType() != March.TargetType.MONSTER) {
            throw new BizException(ErrorCode.WORLD_TARGET_INVALID,
                    "目标不是野怪，无法按野怪讨伐结算：targetType=" + march.targetType());
        }
        String playerId = march.playerId();
        MapmonsterCfg monster = requireMonster(march.targetId());

        // 一、每日讨伐次数。先占次数再打：反过来（先打再占）会让并发请求都打完了才发现超限，
        // 而战斗已经结算、掉落已经发出，回滚比拒绝贵得多
        String dayKey = DayKey.of(now);
        long limit = configs.longParam("MONSTER_HUNT_DAILY_LIMIT");
        if (!dailyCounter.tryConsume(SCOPE_HUNT, playerId, dayKey, limit)) {
            // 明确提示「明日重置」，绝不静默返回空结果（B09 验收 3）：
            // 静默失败会让玩家以为是搜索坏了、以为野怪刷新了、以为服务器出问题了，
            // 而正确答案只是「你今天打满了」
            throw new BizException(ErrorCode.RATE_LIMITED,
                    "今日讨伐次数已用完（" + limit + " 次），明日 " + dayKey + " 之后重置");
        }
        // 一·五、全服 PvE 赛季配额（2026-09-13 裁决 C20）。刻意排在每日次数之后：
        // 每日次数是"你今天打满了"，这一条是"这一季的野怪快被清空了"，两句话不能混
        String seasonId = seasons.timelineRules().seasonId();
        long seasonCap = pveSeasonConsumeCap();
        if (!dailyCounter.tryConsume(SCOPE_PVE_SEASON, SERVER_OWNER, seasonId, seasonCap)) {
            dailyCounter.refund(SCOPE_HUNT, playerId, dayKey);
            throw new BizException(ErrorCode.RATE_LIMITED,
                    "本赛季全服可讨伐的野怪已达上限（" + seasonCap + " 格，赛季 " + seasonId
                            + "）。野怪不会定时刷新，恢复要等下赛季");
        }
        try {
            Outcome outcome = doResolve(march, monster, now, beneficiaries);
            if (!outcome.won()) {
                // 打输没有消耗掉任何格子。不退的话，Bot 的失败次数会把真人的通道一点点吃掉，
                // 而那正是这道闸门要防的事
                dailyCounter.refund(SCOPE_PVE_SEASON, SERVER_OWNER, seasonId);
            }
            return outcome;
        } catch (RuntimeException e) {
            dailyCounter.refund(SCOPE_PVE_SEASON, SERVER_OWNER, seasonId);
            // 战斗没打成就必须退还次数，否则玩家会因为一次异常白白损失一次讨伐机会
            dailyCounter.refund(SCOPE_HUNT, playerId, dayKey);
            throw e;
        }
    }

    /**
     * 本赛季全服可消耗的野怪格上限 = 野怪格期望数 × global.PVE_SEASON_CONSUMED_RATIO。
     *
     * <p>分母是 {@code WORLD_SIZE² × WORLD_MONSTER_DENSITY} 的<b>期望值</b>而不是精确点数：
     * 精确值要扫 262144 格才能算出来，而这条闸门守的是「别把真人通道清空」这个量级的判断，
     * 期望值与真实值的偏差远小于 30% 这道余量。刻意不再配一个绝对格数 ——
     * 密度已经有自己的家，再写一个数就是同一条规则两个家。
     */
    public long pveSeasonConsumeCap() {
        long size = configs.longParam("WORLD_SIZE");
        long expectedMonsters = com.ironoath.common.num.FixedPoint.truncate(
                com.ironoath.common.num.FixedPoint.mul(
                        com.ironoath.common.num.FixedPoint.of(size * size),
                        configs.fixedParam("WORLD_MONSTER_DENSITY")));
        return com.ironoath.common.num.FixedPoint.truncate(
                com.ironoath.common.num.FixedPoint.mul(
                        com.ironoath.common.num.FixedPoint.of(expectedMonsters),
                        configs.fixedParam("PVE_SEASON_CONSUMED_RATIO")));
    }

    private Outcome doResolve(March march, MapmonsterCfg monster, long now, Map<String, Long> beneficiaries) {
        String playerId = march.playerId();

        // 二、体力检查（扣款延后到胜利之后，理由见类注释）
        long staminaCost = monster.staminaCost();
        long staminaAvailable = wallet.available(playerId, StaminaService.RESOURCE_ID, now);
        if (staminaAvailable < staminaCost) {
            throw new BizException(ErrorCode.RESOURCE_NOT_ENOUGH,
                    "体力不足：讨伐 " + monster.name() + " 需要 " + staminaCost
                            + " 点，当前 " + staminaAvailable + " 点。体力每 "
                            + recoverMinutes() + " 分钟恢复 1 点，也可以用金币购买");
        }

        // 三、造双方军队
        HeroRoster roster = heroes.findByPlayerId(playerId).orElseGet(HeroRoster::new);
        List<HeroSnapshot> heroSnapshots = heroMapper.snapshots(march.heroes(), roster);
        CityState city = cities.findByPlayerId(playerId).orElse(null);
        long hospitalCapacity = city == null ? 0L : armyAppService.hospitalCapacity(playerId, city);
        BattleArmyFactory.Folded attackerFold = armyFactory.fold(march.units());
        ArmySide attacker = armyFactory.toSide(playerId, attackerFold, heroSnapshots, 0L,
                techBonuses.forPlayer(playerId), hospitalCapacity);
        ArmySide defender = armyFactory.toSide(monster.id(),
                armyFactory.fold(monsterArmy(monster)), List.of(), 0L,
                // 野怪没有联盟，也就没有科技：显式传 none 而不是留空，
                // 免得以后有人以为「0」是某种默认强度
                TechBonus.none(),
                // 野怪没有医院：它的损失全是死亡。PVE 的死亡比例本来也由 WOUND_RATIO_PVE_DEAD 决定
                0L);

        // 四、跑内核。seed 必须来自服务端的不可预测源，并且随战报记下来（铁律 4：凭 seed 可复算）
        long seed = seeds.nextSeed();
        BattleResult result = BattleSimulator.simulate(new BattleInput(
                attacker, defender, TerrainType.PLAIN, seed, BattleType.PVE,
                BattleModifier.none(), BattleModifier.none(),
                attackerFold.stats(), rulesAssembler.rules(), null));
        boolean won = result.winner() == com.ironoath.battle.Winner.ATTACKER;

        // 五、把 per-UnitType 的战果摊回 per-unitId
        Map<String, Long> lossesByUnitId = armyFactory.unfoldLosses(march.units(),
                attackerFold.counts(), result.atkSurvivors());
        // 死亡名额按各阶级的损失量比例分：TierSplit 的键是通用的 String，
        // 这里喂的是 unitId，Σ 恰好等于内核算出的 atkDead
        Map<String, Long> deadByUnitId = TierSplit.splitProportionally(lossesByUnitId, result.atkDead());
        Map<String, Long> woundedByUnitId = new LinkedHashMap<>();
        lossesByUnitId.forEach((unitId, loss) -> {
            long dead = deadByUnitId.getOrDefault(unitId, 0L);
            long wounded = loss - dead;
            if (wounded > 0L) {
                woundedByUnitId.put(unitId, wounded);
            }
        });

        // 六、伤兵当场进医院，超容量的部分死亡
        long overflowDead = 0L;
        if (!woundedByUnitId.isEmpty()) {
            ArmyState army = armies.findByPlayerId(playerId).orElseGet(ArmyState::new);
            long armyVersion = armies.versionOf(playerId);
            boolean created = armies.findByPlayerId(playerId).isEmpty();
            if (created) {
                armies.insertIfAbsent(playerId, army);
                armyVersion = armies.versionOf(playerId);
            }
            overflowDead = army.admitWounded(woundedByUnitId, hospitalCapacity);
            armies.save(playerId, army, armyVersion);
        }

        // 七、行军带走的是幸存者
        march.applyLosses(lossesByUnitId);

        // 八、胜利才发掉落、才扣体力
        long staminaCharged = 0L;
        List<RewardItem> drops = List.of();
        if (won) {
            staminaCharged = wallet.deduct(playerId, StaminaService.RESOURCE_ID, staminaCost, now);
            if (staminaCharged < staminaCost) {
                // 检查与扣款之间体力被别的路径消费了（理论上不该发生：玩家锁一直握着）。
                // 照实记录并继续 —— 战斗已经打完，回滚战斗比少扣几点体力严重得多
                LOG.warn("体力扣款不足，可能存在并发消费 playerId={} 应付={} 实扣={}",
                        playerId, staminaCost, staminaCharged);
            }
            drops = grantDrops(beneficiaries, monster, march.id());
            // 打掉的野怪必须从地图上消耗掉：WorldRepository 只存「玩家造成的变化」，
            // 而野怪本体是 WorldGenerator 按 seed 确定性生成的 —— 不记这一笔，
            // 下一次 viewport 组装时它会原样长回来，玩家看到的是「刚打掉的野怪又回来了」。
            // 刷新应当是显式的运营行为（B14 赛季重置），不是查询的副作用
            world.markConsumed(march.to(), now);
        }

        // 战报落库：B05 交付的客户端 BattlePlayback 一直在等这份数据。
        // 输赢都要记 —— 玩家最想复盘的恰恰是输的那一场
        // attackerName 传 null 是安全的：PVE 战报的主人就是攻方，opponentName 永远取守方名（野怪名）。
        // 只有 PVP 的守方那一份会去读 attackerName，而那份由 PlayerCityBattleService 写入
        battleReports.record(playerId, playerId, monster.id(), monster.name(), null,
                BattleType.PVE, march.heroes(), List.of(), result, now);

        // 事件触发的聊天：打野是 VICTORY / DEFEAT 两个场景的主要来源。
        // 只给推图的主人发（beneficiaries 是集结合并的参与者，让每个成员都喊一句「打赢了」
        // 会在联盟频道里刷出同一场仗的复读）
        events.publishEvent(new com.ironoath.web.bot.BotChatEvent(playerId, playerId,
                com.ironoath.core.bot.BotChatBook.Scene.ofBattle(won), now));

        LOG.info("野怪讨伐结算 playerId={} marchId={} 野怪={}(Lv{}) seed={} 结果={} 回合={} "
                        + "损失={} 死亡={} 伤兵={} 超容量死亡={} 体力={}/{} 掉落={} 医院容量={}",
                playerId, march.id(), monster.id(), monster.level(), seed, result.winner(),
                result.totalRounds(), lossesByUnitId, deadByUnitId, woundedByUnitId,
                overflowDead, staminaCharged, staminaCost, drops.size(), hospitalCapacity);
        return new Outcome(result, won, lossesByUnitId, deadByUnitId, woundedByUnitId,
                overflowDead, staminaCost, staminaCharged, drops);
    }


    /** 野怪的掉落。只发配置表里写了的正数值，0 的项不进列表（否则飘字会出现一堆「+0」）。 */
    private List<RewardItem> grantDrops(Map<String, Long> beneficiaries, MapmonsterCfg monster, String marchId) {
        List<RewardItem> drops = new ArrayList<>(5);
        addDrop(drops, ResourceIds.WOOD, monster.dropWood());
        addDrop(drops, ResourceIds.STONE, monster.dropStone());
        addDrop(drops, ResourceIds.IRON, monster.dropIron());
        addDrop(drops, ResourceIds.GRAIN, monster.dropGrain());
        addDrop(drops, ResourceIds.GOLD, monster.dropGold());
        if (drops.isEmpty()) {
            return List.of();
        }
        // 走 RewardService 而不是直接改存档：容量上限、保护量、溢出转邮件全在里面（B04 禁止项）。
        // 集结合并行军按承诺兵力拆成「每人一份」，每人各自过一遍自己的仓库容量 ——
        // 整包塞给发起人等于让成员的兵替发起人装满仓库，而成员分不到任何东西
        Map<String, List<RewardItem>> perPlayer = new LinkedHashMap<>();
        for (RewardItem drop : drops) {
            com.ironoath.core.reward.RewardSplit.byWeight(beneficiaries, drop.count())
                    .forEach((player, count) -> perPlayer
                            .computeIfAbsent(player, k -> new ArrayList<>())
                            .add(drop.withCount(count)));
        }
        perPlayer.forEach((player, items) -> rewardService.grant(player, items,
                RewardContext.toMail("monster", monster.id(), marchId)));
        return drops;
    }

    private static void addDrop(List<RewardItem> drops, String resourceId, long amount) {
        if (amount > 0L) {
            drops.add(new RewardItem(RewardType.RESOURCE, resourceId, amount));
        }
    }

    /** 野怪的兵力构成：四个兵种的数量 × 表里指定的阶级。 */
    private Map<String, Long> monsterArmy(MapmonsterCfg monster) {
        Map<String, Long> units = new LinkedHashMap<>();
        putUnit(units, "INFANTRY", monster.unitTier(), monster.infantryCount());
        putUnit(units, "CAVALRY", monster.unitTier(), monster.cavalryCount());
        putUnit(units, "ARCHER", monster.unitTier(), monster.archerCount());
        putUnit(units, "SIEGE", monster.unitTier(), monster.siegeCount());
        if (units.isEmpty()) {
            throw new BizException(ErrorCode.CONFIG_INVALID, "野怪 " + monster.id()
                    + " 的四个兵种数量全是 0，无法构成一支防守军队");
        }
        return units;
    }

    private void putUnit(Map<String, Long> units, String typeName, long tier, long count) {
        if (count <= 0L) {
            return;
        }
        String unitId = "unit_" + typeName.toLowerCase(java.util.Locale.ROOT) + "_t" + tier;
        try {
            configs.get(com.ironoath.config.cfg.UnitCfg.class, unitId);
        } catch (ConfigException e) {
            // mapmonster 的 unitTier 与 unit 表的阶级号必须对得上。
            // 对不上时如果静默跳过，这只野怪就会少一个兵种 —— 打起来比表上写的弱，
            // 而策划看表完全看不出问题
            throw new BizException(ErrorCode.CONFIG_INVALID, "野怪引用了不存在的兵种 " + unitId
                    + "：mapmonster 的 unitTier 与 unit 表的阶级号不同步");
        }
        units.put(unitId, count);
    }

    private MapmonsterCfg requireMonster(String targetId) {
        try {
            return configs.get(MapmonsterCfg.class, targetId);
        } catch (ConfigException e) {
            throw new BizException(ErrorCode.CONFIG_NOT_FOUND, "野怪配置不存在: " + targetId);
        }
    }

    /** 恢复一点体力需要多少分钟，用于文案。由 basePerHour 推出来，不另存一个数。 */
    private long recoverMinutes() {
        long perHour = configs.getResource(StaminaService.RESOURCE_ID).basePerHour();
        return perHour <= 0L ? 0L : 60L / perHour;
    }
}
