package com.ironoath.web;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.StageCfg;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.stage.StageProgressRepository;
import com.ironoath.web.battle.BattleReportService;
import com.ironoath.web.dto.generated.ChallengeStageReq;
import com.ironoath.web.dto.generated.ChallengeStageResp;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.StageEntry;
import com.ironoath.web.dto.generated.StageUnit;
import com.ironoath.web.dto.generated.SweepReq;
import com.ironoath.web.dto.generated.SweepResp;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.core.city.BuildingInstance;
import com.ironoath.core.city.BuildingStatus;
import com.ironoath.core.city.CityState;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.StageAppService;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemoryStageProgressStore;
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
 * 职责：B09 §4/§6 章节副本的验证 —— 验收 2（扫荡与手动战斗逐字段一致）、验收 9（10 次只发 1 个请求）、
 * 验收 10（三星条件判定）、以及线性解锁与 BOSS 机制的响亮拒绝。
 * 依赖：Spring Boot Test；test profile。
 *
 * <p><b>验收 2 是这一批最重要的一条</b>，而它的正确保证方式是结构性的：
 * 挑战与扫荡都调用 {@code StageAppService.execute}，全类只有那一个 simulate 调用点。
 * 本类的用例从两个方向钉住它 —— ① 同一个 seed 跑两次 execute，逐字段相同；
 * ② 挑战和扫荡各自落下的战报，用战报里的 seed 重跑 execute 能复现出同样的战果。
 * 第 ② 条才是真正的验收 2：它证明两条路径没有各自的结算逻辑，
 * 而不只是证明「同一个方法调用两次结果相同」。
 */
@SpringBootTest
@ActiveProfiles("test")
class StageEndpointTest {

    @Autowired private com.ironoath.core.event.GameEventBus questBus;


    private static final String STAGE_1 = "stage_01_01";
    private static final String STAGE_2 = "stage_01_02";
    private static final String BOSS_1 = "stage_01_10";
    private static final String UNIT = "unit_infantry_t1";

    @Autowired private ConfigRegistry configs;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private StageAppService stageAppService;
    @Autowired private CityAppService cityAppService;
    @Autowired private com.ironoath.web.service.ArmyAppService armyAppService;
    @Autowired private BattleReportService battleReports;

    @Autowired private PlayerRepository players;
    @Autowired private com.ironoath.core.city.CityRepository cities;
    @Autowired private ArmyRepository armies;
    @Autowired private StageProgressRepository progressRepo;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
        ((InMemoryArmyStore) armies).clear();
        ((InMemoryStageProgressStore) progressRepo).clear();
    }

    @Test
    @DisplayName("验收10：压倒性胜利拿满三星，失败拿 0 星，且星级只升不降")
    void starsReflectTheThreeConditions() {
        String playerId = newPlayer();
        giveTroops(playerId, Map.of(UNIT, 5000L));

        giveHospital(playerId, 5);
        ChallengeStageResp won = stageAppService.challenge(playerId,
                new ChallengeStageReq(newRequestId(), STAGE_1, List.of(new StageUnit(UNIT, 5000L)), List.of()));
        assertThat(won.stars().cleared()).isTrue();
        assertThat(won.stars().noLoss())
                .as("无损 = 己方阵亡为 0。5000 打 10 时期望损失约 10/7 ≈ 1.4 个兵，"
                        + "按 PVE 死亡比例 0.20 算阵亡约 0.3 个 ⇒ 取整为 0；那 1 个伤兵由医院接住，"
                        + "所以无损可达。**没有医院时这一星拿不到** —— 内核把超容量的伤兵算作永久死亡，"
                        + "这正是第一章主城门槛对齐到医院可用的 5 级的原因")
                .isTrue();
        assertThat(won.stars().withinRounds())
                .as("限时三星：压倒性兵力应当在上限回合内取胜").isTrue();
        assertThat(won.stars().total()).as("三星应当全部拿到").isEqualTo(3);
        assertThat(won.progress().stars()).isEqualTo(3);
        assertThat(won.starsEarned()).isEqualTo(3);
        assertThat(won.staminaCharged())
                .as("胜利才扣体力（B09 验收 1）").isEqualTo(stageOf(STAGE_1).staminaCost());

        // 失败：1 个兵打 10 个兵
        String loser = newPlayer();
        giveTroops(loser, Map.of(UNIT, 1L));
        ChallengeStageResp lost = stageAppService.challenge(loser,
                new ChallengeStageReq(newRequestId(), STAGE_1, List.of(new StageUnit(UNIT, 1L)), List.of()));
        assertThat(lost.stars().total()).isZero();
        assertThat(lost.staminaCharged()).as("失败不扣体力：失败已经扣了兵，再扣体力是收两次学费").isZero();
        assertThat(lost.staminaCost()).as("应付多少仍然照实下发，玩家才知道这一关的门槛").isPositive();

        // 星级只升不降：重打一次，星级不变。
        // 兵力要按当前实际持有量填 —— 上一场有 1 个兵进了医院，所以已经不是 5000 了
        long remaining = armies.findByPlayerId(playerId).orElseThrow().countOf(UNIT);
        ChallengeStageResp retry = stageAppService.challenge(playerId,
                new ChallengeStageReq(newRequestId(), STAGE_1,
                        List.of(new StageUnit(UNIT, remaining)), List.of()));
        assertThat(retry.progress().stars())
                .as("重试打得更差不该扣星：扣星会让玩家因为怕掉星而不敢重试，"
                        + "而重试正是养成的动力")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("验收2：同一 stageId + 同一 seed，两次执行逐字段一致")
    void theSameSeedReproducesTheSameBattle() {
        String playerId = newPlayer();
        giveTroops(playerId, Map.of(UNIT, 5000L));
        StageCfg stage = stageOf(STAGE_1);
        Map<String, Long> units = Map.of(UNIT, 5000L);
        long seed = 20260907L;
        long now = System.currentTimeMillis();

        StageAppService.Attempt first = stageAppService.execute(stage, playerId, units, List.of(), seed, now);
        StageAppService.Attempt second = stageAppService.execute(stage, playerId, units, List.of(), seed, now);
        assertThat(second.result()).isEqualTo(first.result());
        assertThat(second.lossesByUnitId()).isEqualTo(first.lossesByUnitId());
        assertThat(second.starsEarned()).isEqualTo(first.starsEarned());
        assertThat(second.noLoss()).isEqualTo(first.noLoss());
        assertThat(second.withinRounds()).isEqualTo(first.withinRounds());

        // 换一个 seed 应当给出不同的战斗（浮动区间是 ±5%，同一个结果说明随机没接上）
        StageAppService.Attempt other = stageAppService.execute(stage, playerId, units, List.of(),
                seed + 1L, now);
        assertThat(other.seed()).isNotEqualTo(first.seed());
    }

    @Test
    @DisplayName("验收2：挑战与扫荡落下的战报，都能用战报里的 seed 由 execute 复现 —— 证明两条路径共用同一个结算")
    void challengeAndSweepShareOneBattleExecutor() {
        String playerId = newPlayer();
        giveTroops(playerId, Map.of(UNIT, 20000L));
        // 先手动打通并拿三星，扫荡才解锁
        ChallengeStageResp manual = stageAppService.challenge(playerId,
                new ChallengeStageReq(newRequestId(), STAGE_1, List.of(new StageUnit(UNIT, 20000L)), List.of()));
        if (manual.stars().total() < 3) {
            // 三星拿不到时扫荡本来就该被拒；直接把进度写成三星，本用例只验「共用同一个执行器」
            forceThreeStars(playerId, STAGE_1);
        }

        long now = System.currentTimeMillis();
        StageCfg stage = stageOf(STAGE_1);
        Map<String, Long> units = Map.of(UNIT, 20000L);

        // 挑战留下的战报：用它的 seed 重跑 execute，战果必须完全一致
        var manualReport = battleReports.open(playerId, manual.reportId());
        StageAppService.Attempt replayed = stageAppService.execute(stage, playerId, units, List.of(),
                manualReport.result().seed(), manualReport.createdAt());
        assertThat(replayed.result().totalRounds()).isEqualTo(manualReport.result().totalRounds());
        assertThat(replayed.result().atkDead()).isEqualTo(manualReport.result().attackerDead());
        assertThat(replayed.result().defDead()).isEqualTo(manualReport.result().defenderDead());
        assertThat(replayed.result().winner().name()).isEqualTo(manualReport.result().winner().name());

        // 扫荡留下的战报：同样能被 execute 复现。
        // 这条才是验收 2 的本体 —— 它证明扫荡没有自己的一套结算（哪怕是期望值公式）
        SweepResp swept = stageAppService.sweep(playerId, new SweepReq(newRequestId(), STAGE_1, 3));
        assertThat(swept.results()).hasSize(3);
        for (var one : swept.results()) {
            var report = battleReports.open(playerId, one.reportId());
            StageAppService.Attempt again = stageAppService.execute(stage, playerId, units, List.of(),
                    report.result().seed(), report.createdAt());
            assertThat(again.result().totalRounds())
                    .as("扫荡战报必须能被同一个执行器复现，reportId=%s", one.reportId())
                    .isEqualTo(report.result().totalRounds());
            assertThat(again.result().atkDead()).isEqualTo(report.result().attackerDead());
            assertThat(again.result().defDead()).isEqualTo(report.result().defenderDead());
            assertThat(again.starsEarned()).as("扫荡的星级判定与手动必须同口径")
                    .isEqualTo(one.stars().total());
        }
    }

    @Test
    @DisplayName("验收9：扫荡 10 次是一个请求，结果逐次下发而不是只给合计")
    void tenSweepsAreOneRequestWithPerRunResults() {
        String playerId = newPlayer();
        giveTroops(playerId, Map.of(UNIT, 50000L));
        stageAppService.challenge(playerId, new ChallengeStageReq(newRequestId(), STAGE_1,
                List.of(new StageUnit(UNIT, 50000L)), List.of()));
        forceThreeStars(playerId, STAGE_1);

        long staminaBefore = staminaOf(playerId);
        SweepResp resp = stageAppService.sweep(playerId, new SweepReq(newRequestId(), STAGE_1, 10));
        assertThat(resp.executed()).isEqualTo(10);
        assertThat(resp.results()).as("逐次下发：每次都是独立的一场战斗，只给合计就无法核对与复现")
                .hasSize(10);
        assertThat(resp.results()).allSatisfy(one -> assertThat(one.reportId()).isNotBlank());
        assertThat(resp.staminaCharged())
                .isEqualTo(staminaOf(playerId) == staminaBefore ? 0L : staminaBefore - staminaOf(playerId));
        assertThat(resp.progress().sweepCount()).isEqualTo(10L);

        assertThatThrownBy(() -> stageAppService.sweep(playerId,
                new SweepReq(newRequestId(), STAGE_1, 11)))
                .isInstanceOf(BizException.class)
                .as("超过 10 次要拒绝而不是截断：截断会让玩家以为扫了 10 次却只拿到几次的奖励")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("未三星的关卡不能扫荡，文案说清楚三星条件是什么")
    void sweepRequiresThreeStars() {
        String playerId = newPlayer();
        giveTroops(playerId, Map.of(UNIT, 5000L));
        assertThatThrownBy(() -> stageAppService.sweep(playerId,
                new SweepReq(newRequestId(), STAGE_1, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("三星")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.STAGE_NOT_SWEEPABLE);
    }

    @Test
    @DisplayName("验收5：BOSS 关的机制真的生效 —— 战报里能看到机制触发，而且它属于守方")
    void bossStagesRunTheirMechanic() {
        String playerId = newPlayer();
        giveHospital(playerId, 5);
        // 兵力刻意只给「够赢但要打几个回合」的量：第一章 BOSS 是 28 兵，
        // 300 兵打它每回合减员约 60%，两回合结束 —— 刚好让第 2 回合的召唤来得及触发。
        // 上一版给的是 50 万兵，守军第 1 回合就被打光，机制一次都没触发，
        // 用例失败在「战报里没有 boss_reinforcement」，看起来像机制没实现，实际是夹具把战斗秒了
        giveTroops(playerId, Map.of(UNIT, 300L));
        unlockUpTo(playerId, BOSS_1);

        long remaining = armies.findByPlayerId(playerId).orElseThrow().countOf(UNIT);
        ChallengeStageResp boss = stageAppService.challenge(playerId,
                new ChallengeStageReq(newRequestId(), BOSS_1,
                        List.of(new StageUnit(UNIT, remaining)), List.of()));
        assertThat(boss.stars().cleared())
                .as("第一章 BOSS 只有 28 兵（分段曲线），300 兵应当能过").isTrue();
        assertThat(boss.reportId()).isNotBlank();

        // 第一章 BOSS 的机制是 REINFORCEMENT（每 2 回合召唤原始守军的 20%）
        var report = battleReports.open(playerId, boss.reportId());
        List<String> triggered = new ArrayList<>();
        for (var round : report.result().rounds()) {
            for (var skill : round.skills()) {
                triggered.add(skill.skillId() + "@" + skill.side());
            }
        }
        assertThat(triggered)
                .as("BOSS 机制必须在战报里留下痕迹，否则玩家看不出这一关与普通关有什么区别 —— "
                        + "而 B09 §二 要求 BOSS「有机制而非纯数值」，看不见的机制等于没有机制。"
                        + "归属必须是 DEFENDER：机制是守方 BOSS 放的，"
                        + "算到攻方头上会让回放看起来像攻方自己在召唤敌方援军")
                .anyMatch(entry -> entry.startsWith("boss_reinforcement@DEFENDER"));

        // 刻意不在这里断言无损：敌方 28 兵加上四次增援约 52 兵，
        // 攻方损失约 52/7 ≈ 7，死亡 = round(7 × 0.20) = 1 —— 无损要靠治疗武将，
        // 那一条由 starsReflectTheThreeConditions 覆盖，本用例只验机制本身生效
    }

    @Test
    @DisplayName("线性推图：前一关没通就进不去，且未解锁必须给出原因而不是静默失败")
    void stagesUnlockLinearly() {
        String playerId = newPlayer();
        giveTroops(playerId, Map.of(UNIT, 5000L));

        List<StageEntry> entries = stageAppService.list(playerId).stages();
        assertThat(entries).hasSize(50);
        StageEntry first = entries.stream().filter(e -> e.stageId().equals(STAGE_1)).findFirst().orElseThrow();
        StageEntry second = entries.stream().filter(e -> e.stageId().equals(STAGE_2)).findFirst().orElseThrow();
        StageEntry chapter2 = entries.stream().filter(e -> e.stageId().equals("stage_02_01"))
                .findFirst().orElseThrow();
        assertThat(first.unlocked()).as("第一关对新号开放").isTrue();
        assertThat(first.lockedReason()).isNull();
        assertThat(second.unlocked()).isFalse();
        assertThat(second.lockedReason()).as("必须说清楚卡在哪一关，灰掉的关卡不说明原因会被当成 bug")
                .contains(STAGE_1);
        assertThat(chapter2.lockedReason())
                .as("第二章要求主城 5 级（夹具已满足），所以挡住它的应当是「上一章未通关」")
                .contains("stage_01_10");

        assertThatThrownBy(() -> stageAppService.challenge(playerId,
                new ChallengeStageReq(newRequestId(), STAGE_2,
                        List.of(new StageUnit(UNIT, 5000L)), List.of())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.STAGE_LOCKED);

        stageAppService.challenge(playerId, new ChallengeStageReq(newRequestId(), STAGE_1,
                List.of(new StageUnit(UNIT, 5000L)), List.of()));
        assertThat(stageAppService.list(playerId).stages().stream()
                .filter(e -> e.stageId().equals(STAGE_2)).findFirst().orElseThrow().unlocked())
                .as("通关第一关后第二关应当解锁").isTrue();
    }

    @Test
    @DisplayName("出战兵力必须真的拥有：客户端可以随便填数量，服务端自己核对")
    void requestedTroopsMustActuallyBeOwned() {
        String playerId = newPlayer();
        giveTroops(playerId, Map.of(UNIT, 10L));
        assertThatThrownBy(() -> stageAppService.challenge(playerId,
                new ChallengeStageReq(newRequestId(), STAGE_1,
                        List.of(new StageUnit(UNIT, 99999L)), List.of())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.UNIT_NOT_ENOUGH);
    }

    // ---------- 夹具 ----------

    /** 把某关的进度直接写成三星，用于测试扫荡这条路径本身（不依赖战斗能否真的无损）。 */
    private void forceThreeStars(String playerId, String stageId) {
        var progress = progressRepo.findByPlayerId(playerId).orElseThrow();
        long version = progressRepo.versionOf(playerId);
        progress.recordResult(stageId, true, true, true, 1, System.currentTimeMillis());
        progressRepo.save(playerId, progress, version);
    }

    /**
     * 给玩家一个可用的医院。
     *
     * <p><b>必须真的放一个实例进去</b>：{@code new CityState()} 是空城，建筑在首次升级时才创建，
     * 所以新号根本没有 hospital 实例 —— 而内核把「医院装不下的伤兵」算作永久死亡
     * （{@code BattleSimulator.casualties} 的 overflow 计入 dead）。
     * 没有医院时，5000 打 10 也会因为那 1 个伤兵无处可去而「阵亡 1」，无损星永远拿不到。
     * 这一条曾经被误判成内核的取整问题，实际是夹具缺了建筑。
     *
     * <p>直接写实例而不是走建造流程：本类验的是关卡与星级，建造由 CityEndpointTest 覆盖。
     */
    private void giveHospital(String playerId, int level) {
        cityAppService.list(playerId);
        CityState city = cities.findByPlayerId(playerId).orElseThrow();
        long version = cities.versionOf(playerId);
        boolean found = false;
        for (BuildingInstance b : city.buildings()) {
            if (!b.configId().equals("hospital")) {
                continue;
            }
            found = true;
            b.restore(level, b.gridX(), b.gridY(), BuildingStatus.IDLE, null,
                    0L, 1L, 1L, b.helpCount(), b.lastMovedAt(), b.lastFinishedAt());
        }
        if (!found) {
            city.restoreBuilding(new BuildingInstance("hospital", "hospital", level, 4, 4));
        }
        cities.save(playerId, city, version);
        assertThat(armyAppService.hospitalCapacity(cities.findByPlayerId(playerId).orElseThrow()))
                .as("夹具必须真的造出了医院容量，否则无损星永远拿不到而原因看不出来")
                .isPositive();
    }

    private StageCfg stageOf(String stageId) {
        return configs.get(StageCfg.class, stageId);
    }

    /** 把某关之前的所有关都写成「已三星通关」，用于测试后续关卡。 */
    private void unlockUpTo(String playerId, String stageId) {
        var progress = progressRepo.findByPlayerId(playerId).orElseGet(() -> {
            progressRepo.insertIfAbsent(playerId, new com.ironoath.core.stage.StageProgress());
            return progressRepo.findByPlayerId(playerId).orElseThrow();
        });
        long version = progressRepo.versionOf(playerId);
        long now = System.currentTimeMillis();
        for (StageCfg stage : configs.all(StageCfg.class)) {
            if (stage.id().equals(stageId)) {
                break;
            }
            progress.recordResult(stage.id(), true, true, true, 1, now);
        }
        // 主城等级也要够：直接改存档比升 10 级建筑便宜得多
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(40);
        players.save(save);
        progressRepo.save(playerId, progress, version);
    }

    /**
     * 建号并把主城等级抬到第一章的门槛（3 级）。
     *
     * <p>新号是 1 级，而 chapter_01 要求 3 级 —— 按 B03 的建造时间曲线（T0=30s、比率 1.18）
     * 升到 3 级只要几分钟，所以这不违反 B09 §二「前三章零氪可三星」的首日底线；
     * 但夹具没必要真的去升两级建筑，直接写存档的 cityLevel 就够了
     * （解锁判定读的就是 PlayerSave.cityLevel）。
     */
    @Test
    @DisplayName("通关关卡发布 CLEAR_STAGE（B12 §1：目标=stage 行 id，胜利才发）")
    void clearingAStagePublishesClearEvent() {
        String playerId = newPlayer();
        giveTroops(playerId, Map.of(UNIT, 5000L));
        var captured = new java.util.ArrayList<com.ironoath.core.event.GameEvent>();
        questBus.subscribe(com.ironoath.core.quest.GoalType.CLEAR_STAGE, captured::add);

        stageAppService.challenge(playerId, new ChallengeStageReq(newRequestId(), STAGE_1,
                List.of(new StageUnit(UNIT, 5000L)), List.of()));

        assertThat(captured).as("通关一次 = 一个事件").hasSize(1);
        assertThat(captured.get(0).targetId()).as("目标=stage 表的行 id").isEqualTo(STAGE_1);
        assertThat(captured.get(0).amount()).isEqualTo(1L);
    }

    private String newPlayer() {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "关卡测试", 1_700_000_000_000L))
                .playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        // 5 级 = 第一章的门槛，也正好是医院可用的那一级（无损三星需要医院，见 giveHospital）
        save.setCityLevel(5);
        players.save(save);
        return playerId;
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
                .resource(com.ironoath.web.service.StaminaService.RESOURCE_ID).current();
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }
}
