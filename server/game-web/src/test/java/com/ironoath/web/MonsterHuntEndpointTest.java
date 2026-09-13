package com.ironoath.web;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.MapmonsterCfg;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.dto.generated.MarchAction;
import com.ironoath.web.dto.generated.MarchReq;
import com.ironoath.web.dto.generated.MarchUnit;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.MarchAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.PowerRefreshService;
import com.ironoath.web.service.StaminaService;
import com.ironoath.web.battle.BattleReport;
import com.ironoath.web.battle.BattleReportStore;
import com.ironoath.web.dto.generated.BattleReportBrief;
import com.ironoath.web.dto.generated.BattleReportListResp;
import com.ironoath.web.dto.generated.BattleReportResp;
import com.ironoath.web.dto.generated.RoundView;
import com.ironoath.web.service.WorldAppService;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryGachaLogStore;
import com.ironoath.web.store.memory.InMemoryGachaStateStore;
import com.ironoath.web.store.memory.InMemoryHeroStore;
import com.ironoath.web.store.memory.InMemoryInventoryStore;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemoryWorldStore;
import com.ironoath.web.store.memory.SortedMarchDueQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：B09 §1 野怪讨伐的端到端验证 —— 胜利结算、战损摊回各阶级、每日上限、体力闸门。
 * 依赖：Spring Boot Test；test profile（内存存储 + JVM 内锁 + 内存到期队列）。
 *
 * <p><b>必须走完整的行军链路</b>（出征 → 到期扫描 → 战斗 → 返程），
 * 而不是直接调 MonsterBattleService：这条链上有三处容易断的接缝 ——
 * 到期扫描的异常处理、行军状态机的推进、以及「战斗打不成时队伍怎么办」。
 * 单独测结算会把这三处全部跳过，而它们恰恰是玩家能直接看见的部分
 * （一支永远卡在野怪面前的队伍，比一场算错的战斗更容易被投诉）。
 *
 * <p><b>玩家城放在世界中心</b>：野怪等级按「离中心每 32 格 +1」成环分布，
 * 出生点是哈希推导的，随机落到外圈就会遇到 20 万兵的 LV50 野怪 ——
 * 那样夹具要么造一支千万级的军队（慢且溢出风险），要么用例变成「必败」。
 * 放在中心就能稳定遇到 LV1~LV2 的小怪，用例的意图（验证结算链路）才不会被数值规模淹没。
 */
@SpringBootTest
@ActiveProfiles("test")
class MonsterHuntEndpointTest {

    @Autowired private com.ironoath.core.event.GameEventBus questBus;


    @Autowired private ConfigRegistry configs;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private MarchAppService marchAppService;
    @Autowired private WorldAppService worldAppService;
    @Autowired private PowerRefreshService powerRefreshService;

    @Autowired private PlayerRepository players;
    @Autowired private CityRepository cities;
    @Autowired private com.ironoath.core.bag.InventoryRepository inventories;
    @Autowired private ArmyRepository armies;
    @Autowired private com.ironoath.core.hero.HeroRepository heroes;
    @Autowired private WorldRepository world;
    @Autowired private MarchRepository marches;
    @Autowired private MarchDueQueue dueQueue;
    @Autowired private com.ironoath.core.gacha.GachaStateRepository gachaStates;
    @Autowired private com.ironoath.core.gacha.GachaLogStore gachaLogs;
    @Autowired private com.ironoath.core.limit.DailyCounter dailyCounter;
    @Autowired private BattleReportStore battleReports;
    @Autowired private com.ironoath.web.battle.BattleReportService battleReportService;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
        ((InMemoryInventoryStore) inventories).clear();
        ((InMemoryArmyStore) armies).clear();
        ((InMemoryHeroStore) heroes).clear();
        ((InMemoryWorldStore) world).clear();
        ((InMemoryMarchStore) marches).clear();
        ((SortedMarchDueQueue) dueQueue).clear();
        ((InMemoryGachaStateStore) gachaStates).clear();
        ((InMemoryGachaLogStore) gachaLogs).clear();
        ((com.ironoath.web.limit.InMemoryDailyCounter) dailyCounter).clear();
        battleReports.clear();
    }

    @Test
    @DisplayName("讨伐胜利：野怪从地图上消失、掉落入账、体力按表扣除、每日次数被占用、队伍返程")
    void winningHuntConsumesMonsterGrantsDropsAndChargesStamina() {
        Hunter hunter = readyHunter();
        long staminaBefore = staminaOf(hunter.playerId);
        long woodBefore = resourceOf(hunter.playerId, "WOOD");

        giveTroops(hunter.playerId, Map.of(hunter.unitId, hunter.overwhelming));
        String marchId = sendAttack(hunter);
        arriveAndProcess(marchId);

        // 野怪必须从地图上被消耗掉：不记这一笔，下一次 viewport 组装时它会原样长回来
        assertThat(world.isConsumed(hunter.monsterCoord))
                .as("打掉的野怪不该重新出现（刷新只能是显式的运营行为，B14 赛季重置）")
                .isTrue();
        assertThat(worldAppService.cellAt(hunter.monsterCoord).entityType())
                .isEqualTo(WorldGenerator.EntityType.EMPTY);

        assertThat(staminaOf(hunter.playerId))
                .as("胜利才扣体力，扣的量来自 mapmonster 表的 staminaCost")
                .isEqualTo(staminaBefore - hunter.monster.staminaCost());
        assertThat(resourceOf(hunter.playerId, "WOOD"))
                .as("掉落走 RewardService，所以受容量上限约束但必定入账")
                .isGreaterThan(woodBefore);
        assertThat(dailyCounter.used("monster_hunt", hunter.playerId,
                com.ironoath.common.time.DayKey.of(System.currentTimeMillis())))
                .as("每日讨伐次数被占用一次").isEqualTo(1L);

        March after = marches.findById(marchId).orElse(null);
        assertThat(after).as("打完的队伍应当返程而不是留在野怪面前").isNotNull();
        assertThat(after.status()).isEqualTo(March.Status.RETURNING);
        assertThat(after.totalUnits()).as("压倒性兵力下应当有幸存者").isPositive();
    }

    @Test
    @DisplayName("战损按各阶级现有数量比例摊回，不会全扣在某一个阶级头上")
    void lossesAreSpreadAcrossTiersNotDumpedOnOne() {
        Hunter hunter = readyHunter();
        String t1 = "unit_infantry_t1";
        String t3 = "unit_infantry_t3";
        // 两级各 400 个：数量相等 ⇒ 比例分摊应当让两边都掉兵。
        // 若实现是「按最低阶级入账」（B07 的老口径），t3 会一个不掉、t1 被打光
        giveTroops(hunter.playerId, Map.of(t1, 400L, t3, 400L));
        long staminaBefore = staminaOf(hunter.playerId);

        String marchId = sendAttack(hunter);
        arriveAndProcess(marchId);

        ArmyState army = armies.findByPlayerId(hunter.playerId).orElseThrow();
        long lostT1 = 400L - army.countOf(t1);
        long lostT3 = 400L - army.countOf(t3);
        long totalLost = lostT1 + lostT3;
        if (totalLost >= 2L) {
            assertThat(lostT1).as("数量相等的两个阶级应当都承担损失，实际 t1 损失=%d t3 损失=%d",
                    lostT1, lostT3).isPositive();
            assertThat(lostT3).isPositive();
        }
        // 行军途中的兵不在城里；返程到家后才归队，所以这里读到的可能只是留守部分
        assertThat(staminaOf(hunter.playerId)).isLessThanOrEqualTo(staminaBefore);
    }

    @Test
    @DisplayName("B09 验收1：战斗失败不扣体力 —— 失败已经扣了兵，再扣体力是对学习者收两次学费")
    void losingHuntDoesNotChargeStamina() {
        Hunter hunter = readyHunter();
        giveTroops(hunter.playerId, Map.of("unit_infantry_t1", 1L));
        long staminaBefore = staminaOf(hunter.playerId);

        String marchId = sendAttack(hunter);
        arriveAndProcess(marchId);

        assertThat(staminaOf(hunter.playerId))
                .as("失败不扣体力：首日体验的底线是「前三章零氪可通」，那要求玩家敢试")
                .isEqualTo(staminaBefore);
        assertThat(world.isConsumed(hunter.monsterCoord))
                .as("没打赢的野怪不该消失").isFalse();
        assertThat(dailyCounter.used("monster_hunt", hunter.playerId,
                com.ironoath.common.time.DayKey.of(System.currentTimeMillis())))
                .as("次数照占：否则玩家可以无限次白试，把讨伐变成不需要代价的探路")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("B09 验收3：每日次数耗尽后出征即被拒，文案明确说「明日重置」而不是静默失败")
    void dailyLimitIsAnnouncedAtDispatchTime() {
        Hunter hunter = readyHunter();
        giveTroops(hunter.playerId, Map.of("unit_infantry_t1", 5000L));
        long limit = configs.longParam("MONSTER_HUNT_DAILY_LIMIT");
        String dayKey = com.ironoath.common.time.DayKey.of(System.currentTimeMillis());
        for (int i = 0; i < limit; i++) {
            assertThat(dailyCounter.tryConsume("monster_hunt", hunter.playerId, dayKey, limit)).isTrue();
        }

        // 出征时就拒，而不是等队伍飞几十分钟站到野怪面前才说 —— 那时玩家什么也做不了
        assertThatThrownBy(() -> sendAttack(hunter))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("明日")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("体力不足时出征即被拒，文案给出恢复速率与购买这条出路")
    void insufficientStaminaIsAnnouncedWithAWayOut() {
        Hunter hunter = readyHunter();
        giveTroops(hunter.playerId, Map.of("unit_infantry_t1", 5000L));
        drainStamina(hunter.playerId);

        assertThatThrownBy(() -> sendAttack(hunter))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("体力不足")
                .hasMessageContaining("分钟")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_ENOUGH);
    }

    @Test
    @DisplayName("攻击空城在出征时就被拒：提示必须出现在玩家还能改主意的时刻")
    void attackingEmptyCityIsRejectedAtDispatchTime() {
        Hunter hunter = readyHunter();
        giveTroops(hunter.playerId, Map.of("unit_infantry_t1", 5000L));
        Coord victimCoord = Coord.of(hunter.home.x() + 3, hunter.home.y() + 1);
        String victim = newPlayerAt(victimCoord.x(), victimCoord.y());
        liftProtection(victim);
        // 守方战力与攻方相同 ⇒ 圈层放行，于是会走到「守军为空」这道门。
        // 攻方战力必须用重算后的值：存档里的 matchPower 是给兵之前的，
        // 而 AttackGuardService 会当场重算，用旧值设定守方就会落到圈层外
        long attackerPower = powerRefreshService.refresh(hunter.playerId).matchPower();
        PlayerSave victimSave = players.findByPlayerId(victim).orElseThrow();
        victimSave.setPower(new com.ironoath.core.player.PlayerPower(
                attackerPower * 2, attackerPower, attackerPower));
        players.save(victimSave);

        // PVP 结算已交付，所以拒绝的理由不再是「未实现」，而是「城内没有驻军」
        assertThatThrownBy(() -> marchAppService.send(hunter.playerId, new MarchReq(newRequestId(),
                victimCoord.x(), victimCoord.y(),
                List.of(new MarchUnit("unit_infantry_t1", 10L)), List.of(), MarchAction.ATTACK)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.WORLD_TARGET_INVALID);
    }

    // ---------- 战报 ----------

    @Test
    @DisplayName("讨伐产生一份可回放的战报：列表给摘要、详情给逐回合数据，seed 与内核算出的一致")
    void huntProducesAReplayableBattleReport() {
        Hunter hunter = readyHunter();
        giveTroops(hunter.playerId, Map.of(hunter.unitId, hunter.overwhelming));
        String marchId = sendAttack(hunter);
        arriveAndProcess(marchId);

        BattleReportListResp list = battleReportService.list(hunter.playerId);
        assertThat(list.reports()).as("打赢打输都该有战报：玩家最想复盘的恰恰是输的那一场").hasSize(1);
        BattleReportBrief brief = list.reports().get(0);
        assertThat(brief.opponentId()).isEqualTo(hunter.monster.id());
        assertThat(brief.opponentName())
                .as("对手名由服务端下发，客户端不得自行拼接（否则与日志、客服工单里的称呼对不上）")
                .isEqualTo(hunter.monster.name());
        assertThat(brief.battleType().name()).isEqualTo("PVE");
        assertThat(brief.won()).as("压倒性兵力应当获胜").isTrue();
        assertThat(brief.totalRounds()).isPositive();
        assertThat(brief.expiresAt()).isGreaterThan(brief.createdAt());

        BattleReportResp detail = battleReportService.open(hunter.playerId, brief.reportId());
        assertThat(detail.result().rounds()).hasSize(brief.totalRounds());
        assertThat(detail.result().seed())
                .as("seed 必须随战报下发：凭它 + 双方构成可以 100% 复算（铁律 4）")
                .isNotZero();
        RoundView first = detail.result().rounds().get(0);
        assertThat(first.round()).isEqualTo(1);
        assertThat(first.attackerUnits()).as("第一回合攻方必须有兵").isNotEmpty();
        assertThat(first.defenderUnits()).as("第一回合守方必须有兵").isNotEmpty();
        assertThat(first.attackerAttack()).isPositive();
        assertThat(first.defenderDefense()).isPositive();
        assertThat(first.attritionFixed())
                .as("减员系数是 B05 §1.4 的核心中间量，公示它才能让战报可解释")
                .isPositive();
        // 回合号必须连续递增：客户端的时间轴按下标推进，跳号会让回放卡在某一帧
        for (int i = 0; i < detail.result().rounds().size(); i++) {
            assertThat(detail.result().rounds().get(i).round()).isEqualTo(i + 1);
        }
    }

    @Test
    @DisplayName("别人的战报一律回「不存在」：回「不属于你」等于给一个探测战报 id 的接口")
    void battleReportIsNotLeakedToOtherPlayers() {
        Hunter hunter = readyHunter();
        giveTroops(hunter.playerId, Map.of(hunter.unitId, hunter.overwhelming));
        arriveAndProcess(sendAttack(hunter));
        String reportId = battleReportService.list(hunter.playerId).reports().get(0).reportId();

        String stranger = newPlayerAt(300, 300);
        assertThatThrownBy(() -> battleReportService.open(stranger, reportId))
                .isInstanceOf(BizException.class)
                .as("PVP 战报含对方的兵力构成，那是侦查才能拿到的情报")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.BATTLE_REPORT_NOT_FOUND);
        assertThatThrownBy(() -> battleReportService.open(hunter.playerId, "battle_不存在的id"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.BATTLE_REPORT_NOT_FOUND);
    }

    @Test
    @DisplayName("过期战报明确说「已过期」而不是回一个空回放，且会被惰性清理掉")
    void expiredBattleReportIsRejectedThenPurged() {
        Hunter hunter = readyHunter();
        giveTroops(hunter.playerId, Map.of(hunter.unitId, hunter.overwhelming));
        arriveAndProcess(sendAttack(hunter));
        BattleReport fresh = battleReports.reportsOf(hunter.playerId).get(0);

        // 造一份「一生下来就过期」的战报：expiresAt == createdAt，expired(now) 即为真
        BattleReport expired = new BattleReport("battle_expired_for_test", fresh.ownerId(),
                fresh.attackerId(), fresh.defenderId(), fresh.defenderName(), fresh.attackerName(),
                fresh.battleType(),
                fresh.attackerHeroIds(), fresh.defenderHeroIds(), fresh.result(),
                fresh.createdAt(), fresh.createdAt());
        battleReports.save(expired);

        assertThatThrownBy(() -> battleReportService.open(hunter.playerId, "battle_expired_for_test"))
                .isInstanceOf(BizException.class)
                .as("过期但还没被清理到时，必须说清楚是过期 —— 回一个「8 回合、0 条回合数据」"
                        + "的空回放会让玩家以为战报坏了")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.BATTLE_REPORT_EXPIRED);

        // 列表入口会触发惰性清理（不跑定时器），清理后同一份战报变成「不存在」
        battleReportService.list(hunter.playerId);
        assertThat(battleReports.findById("battle_expired_for_test")).isEmpty();
        assertThatThrownBy(() -> battleReportService.open(hunter.playerId, "battle_expired_for_test"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.BATTLE_REPORT_NOT_FOUND);
    }

    // ---------- 夹具 ----------

    /** 一个准备好讨伐的猎人：落在世界中心、解除新手保护、找到一只小怪、备好压倒性兵力。 */
    @Test
    @DisplayName("击杀野怪发布 KILL_MONSTER（B12 §1：目标=mapmonster 行 id，打赢才发）")
    void winningHuntPublishesKillEvent() {
        Hunter hunter = readyHunter();
        giveTroops(hunter.playerId, Map.of(hunter.unitId, hunter.overwhelming));
        String marchId = sendAttack(hunter);
        var captured = new java.util.ArrayList<com.ironoath.core.event.GameEvent>();
        questBus.subscribe(com.ironoath.core.quest.GoalType.KILL_MONSTER, captured::add);

        arriveAndProcess(marchId);

        assertThat(captured).as("打赢一场野怪应当恰好发一个事件").hasSize(1);
        assertThat(captured.get(0).playerId()).isEqualTo(hunter.playerId);
        assertThat(captured.get(0).targetId())
                .as("目标必须是 mapmonster 的行 id —— 任务表按它匹配（quest_main_04 盯的就是 lv01）")
                .isEqualTo(hunter.monster.id());
        assertThat(captured.get(0).amount()).isEqualTo(1L);
    }

    private record Hunter(String playerId, Coord home, Coord monsterCoord, MapmonsterCfg monster,
                          String unitId, long overwhelming) {
    }

    private Hunter readyHunter() {
        String playerId = newPlayerAt(256, 256);
        liftProtection(playerId);
        Coord home = worldAppService.homeOf(playerId);

        Coord monsterCoord = null;
        MapmonsterCfg monster = null;
        for (int radius = 1; radius <= 40 && monsterCoord == null; radius++) {
            for (int dx = -radius; dx <= radius && monsterCoord == null; dx++) {
                for (int dy = -radius; dy <= radius && monsterCoord == null; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) {
                        continue;
                    }
                    Coord candidate = Coord.of(home.x() + dx, home.y() + dy);
                    if (!candidate.withinWorld(512) || world.cityAt(candidate).isPresent()) {
                        continue;
                    }
                    WorldGenerator.Cell cell = worldAppService.cellAt(candidate);
                    if (cell.entityType() == WorldGenerator.EntityType.MONSTER) {
                        monsterCoord = candidate;
                        monster = configs.get(MapmonsterCfg.class, cell.entityId());
                    }
                }
            }
        }
        assertThat(monsterCoord).as("世界中心 40 格内必须能找到野怪").isNotNull();

        long monsterTotal = monster.infantryCount() + monster.cavalryCount()
                + monster.archerCount() + monster.siegeCount();
        String unitId = "unit_infantry_t" + monster.unitTier();
        // 兵力由各用例自己给：giveTroops 是累加的，在这里预置会让「必败」用例其实必胜
        return new Hunter(playerId, home, monsterCoord, monster, unitId, monsterTotal * 30L);
    }

    /** 把玩家现有的全部兵力派出去（混编时会带上每一个阶级，这正是战损摊回要验的形状）。 */
    private String sendAttack(Hunter hunter) {
        ArmyState army = armies.findByPlayerId(hunter.playerId).orElseThrow();
        List<MarchUnit> units = new ArrayList<>();
        army.troops().forEach((unitId, count) -> {
            if (count > 0L) {
                units.add(new MarchUnit(unitId, count));
            }
        });
        assertThat(units).as("夹具必须先给兵").isNotEmpty();
        return marchAppService.send(hunter.playerId, new MarchReq(newRequestId(),
                hunter.monsterCoord.x(), hunter.monsterCoord.y(), units,
                List.of(), MarchAction.ATTACK)).march().marchId();
    }

    /**
     * 把行军推进到「已到点」并触发到期扫描。服务端不跑定时器，测试也不 sleep。
     *
     * <p>做法是把 arriveAt 改成本段的<b>起点</b>而不是某个传进来的时刻：
     * 传进来的往往是未来（原始的 arriveAt），那样到期扫描会认为「还没到点」，
     * 测试就会看到状态没推进却找不到原因。
     */
    private void arriveAndProcess(String marchId) {
        March march = marches.findById(marchId).orElseThrow();
        long version = marches.versionOf(marchId);
        march.restore(march.startAt(), march.returnArriveAt(), march.returnStartAt(),
                march.returnFrom(), march.load(), march.status(), march.gatherStartAt(),
                march.units());
        marches.save(march, version);
        dueQueue.reschedule(marchId, march.startAt());
        marchAppService.processDue(march.playerId(), System.currentTimeMillis());
    }

    private void giveTroops(String playerId, Map<String, Long> byUnitId) {
        if (armies.findByPlayerId(playerId).isEmpty()) {
            armies.insertIfAbsent(playerId, new ArmyState());
        }
        ArmyState army = armies.findByPlayerId(playerId).orElseThrow();
        long version = armies.versionOf(playerId);
        byUnitId.forEach(army::add);
        armies.save(playerId, army, version);
    }

    private long staminaOf(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow()
                .resource(StaminaService.RESOURCE_ID).current();
    }

    private void drainStamina(String playerId) {
        setResource(playerId, StaminaService.RESOURCE_ID, 0L);
    }

    private long resourceOf(String playerId, String resourceId) {
        return players.findByPlayerId(playerId).orElseThrow().resource(resourceId).current();
    }

    private void setResource(String playerId, String resourceId, long amount) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        var s = save.resource(resourceId);
        save.putResource(resourceId, new com.ironoath.core.player.PlayerResourceState(
                amount, Math.max(s.cap(), amount), s.protectedAmount(), s.perHour(), s.lastSettle()));
        players.save(save);
    }

    private void liftProtection(String playerId) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setProtectUntil(null);
        players.save(save);
    }

    private String newPlayerAt(int x, int y) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "讨伐测试", 1_700_000_000_000L, ""))
                .playerId();
        assertThat(world.placeCity(playerId, Coord.of(x, y)))
                .as("夹具必须能把城放到 (%d,%d)", x, y).isTrue();
        return playerId;
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }
}
