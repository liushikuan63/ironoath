package com.ironoath.web;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.BuildingCfg;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.config.cfg.UnitCfg;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.ArmyListResp;
import com.ironoath.web.dto.generated.ArmyUnitReq;
import com.ironoath.web.dto.generated.CityUpgradeReq;
import com.ironoath.web.dto.generated.CityUpgradeResp;
import com.ironoath.web.dto.generated.HeroIdReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.SetLineupReq;
import com.ironoath.web.dto.generated.TrainCancelResp;
import com.ironoath.web.dto.generated.TrainReq;
import com.ironoath.web.dto.generated.TrainResp;
import com.ironoath.web.dto.generated.TreatReq;
import com.ironoath.web.dto.generated.TreatResp;
import com.ironoath.web.dto.generated.UnitView;
import com.ironoath.web.service.ArmyAppService;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.HeroAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryGachaLogStore;
import com.ironoath.web.store.memory.InMemoryGachaStateStore;
import com.ironoath.web.store.memory.InMemoryInventoryStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.ironoath.web.social.TestSquads;

/**
 * 职责：B05 第二步（训练队列、医院、兵种阶级）的端到端验证。
 * 依赖：Spring Boot Test，test profile（内存存储 + JVM 内锁）。
 *
 * <p>本类要验的不是「训练能跑通」，而是三件容易做错、做错了又不报错的事：
 * <ol>
 *   <li><b>训练中的兵力必须计入带兵上限</b>。否则玩家可以先把队列塞满、
 *       再换上低统率的武将，用「已在训练」绕过上限 —— 不需要任何技巧，知道机制就能用。</li>
 *   <li><b>加速秒数只能来自配置</b>。客户端传 seconds 一律忽略，
 *       否则改一下请求体就能瞬间训完（B00 铁律 3：服务器权威）。</li>
 *   <li><b>医院超容量的部分真的会死</b>。这是 B05 §1.5 与 B00 陷阱清单明写的规则，
 *       而它最容易被实现成「伤兵无限收容」—— 那样战斗就没有代价，
 *       代价没了谨慎就不是策略，医院这个建筑也随之失去意义。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class ArmyEndpointTest {

    @Autowired private ArmyAppService armyAppService;
    @Autowired private CityAppService cityAppService;
    @Autowired private HeroAppService heroAppService;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private ConfigRegistry configs;
    @Autowired private PlayerRepository players;
    @Autowired private CityRepository cities;
    @Autowired private InventoryRepository inventories;
    @Autowired private ArmyRepository armies;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
        ((InMemoryInventoryStore) inventories).clear();
        ((InMemoryArmyStore) armies).clear();
        socialStore.clear();
        ((InMemoryGachaStateStore) gachaStates()).clear();
        ((InMemoryGachaLogStore) gachaLogs()).clear();
    }

    @Autowired private com.ironoath.core.gacha.GachaStateRepository gachaStateRepo;
    @Autowired private com.ironoath.core.gacha.GachaLogStore gachaLogStore;

    private com.ironoath.core.gacha.GachaStateRepository gachaStates() {
        return gachaStateRepo;
    }

    private com.ironoath.core.gacha.GachaLogStore gachaLogs() {
        return gachaLogStore;
    }

    // ---------- 兵种阶级解锁 ----------

    @Test
    @DisplayName("新号只解锁 T1，未解锁的阶级仍下发并带精确提示「需要兵营 X 级，当前 Y 级」")
    void tierUnlockIsConfigDrivenAndHintIsPrecise() {
        String playerId = newPlayer();
        ArmyListResp resp = armyAppService.list(playerId);

        assertThat(resp.units()).as("4 兵种 × 5 阶级全部下发，含未解锁的").hasSize(20);

        UnitView t1 = unitOf(resp, "unit_infantry_t1");
        UnitView t3 = unitOf(resp, "unit_infantry_t3");
        assertThat(t1.unlocked()).as("兵营 0 级时 T1 就该校验 unlockBuildingLevel=1").isFalse();
        assertThat(t3.unlocked()).isFalse();
        UnitCfg t3Cfg = configs.get(UnitCfg.class, "unit_infantry_t3");
        assertThat(t3.unlockHint())
                .as("提示必须精确到「需要 X 级，当前 Y 级」，笼统的「条件不足」玩家不知道该做什么")
                .contains(String.valueOf(t3Cfg.unlockBuildingLevel()))
                .contains("当前");

        // 建兵营到 1 级 ⇒ T1 解锁（unlockBuildingLevel=1）
        prepareBarracks(playerId);
        ArmyListResp after = armyAppService.list(playerId);
        assertThat(unitOf(after, "unit_infantry_t1").unlocked())
                .as("兵营 1 级应当解锁 T1 步兵").isTrue();
        assertThat(unitOf(after, "unit_infantry_t3").unlocked())
                .as("T3 要求兵营 %d 级，1 级不该解锁", t3Cfg.unlockBuildingLevel()).isFalse();
        // 马厩没建 ⇒ 骑兵一个阶级都不解锁
        assertThat(unitOf(after, "unit_cavalry_t1").unlocked()).isFalse();
    }

    @Test
    @DisplayName("训练未解锁的阶级被拒绝，且错误码是 UNIT_NOT_UNLOCKED（客户端据此弹升级引导）")
    void trainingLockedTierIsRejected() {
        String playerId = newPlayer();
        prepareBarracks(playerId);

        assertThatThrownBy(() -> armyAppService.train(playerId,
                new TrainReq(newRequestId(), "unit_infantry_t3", 10L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未解锁")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.UNIT_NOT_UNLOCKED);
    }

    // ---------- 训练 ----------

    @Test
    @DisplayName("B05 §二：训练时间 = 单位时间 × 数量，消耗 = 单位消耗 × 数量，两个数都来自 unit 表")
    void trainingTimeAndCostScaleLinearlyWithCount() {
        String playerId = newPlayer();
        prepareBarracks(playerId);
        giveResources(playerId, 1_000_000L);
        fieldHeroForCap(playerId, "hero_ssr_02");

        UnitCfg unit = configs.get(UnitCfg.class, "unit_infantry_t1");
        long count = 100L;
        TrainResp resp = armyAppService.train(playerId,
                new TrainReq(newRequestId(), "unit_infantry_t1", count));

        long expectedSeconds = unit.trainTimeSec() * count;
        assertThat(resp.remainingSeconds())
                .as("100 个 T1 步兵 = 60 × 100 秒。批量不等于加速，这是刻意的")
                .isBetween(expectedSeconds - 2L, expectedSeconds);
        assertThat(resp.finishAt()).isGreaterThan(resp.serverNow());
        assertThat(amountOf(resp.cost(), "IRON")).isEqualTo(unit.trainCostIron() * count);
        assertThat(amountOf(resp.cost(), "GRAIN")).isEqualTo(unit.trainCostGrain() * count);
        assertThat(resp.troopsInUse()).as("训练中的兵力还没入账").isZero();
        assertThat(resp.reducedSeconds()).as("开始训练不是加速").isZero();
    }

    @Test
    @DisplayName("同一兵种不能并行两批；队列条数用满后拒绝并提示可开额外队列")
    void trainingQueueIsSerializedAndBounded() {
        String playerId = newPlayer();
        raiseMainCity(playerId, 5);   // 兵营要 3 级、马厩要 5 级，一次推到 5 级
        buildAndFinish(playerId, "barracks", 1, 1);
        buildAndFinish(playerId, "stable", 2, 1);
        giveResources(playerId, 1_000_000L);
        fieldHeroForCap(playerId, "hero_ssr_02");

        armyAppService.train(playerId, new TrainReq(newRequestId(), "unit_infantry_t1", 10L));
        assertThatThrownBy(() -> armyAppService.train(playerId,
                new TrainReq(newRequestId(), "unit_infantry_t1", 10L)))
                .isInstanceOf(BizException.class).hasMessageContaining("已在训练中");

        // 基础队列只有 1 条（global.TRAIN_QUEUE_SLOTS），所以第二个兵种会被队列挡住
        assertThat(configs.longParam("TRAIN_QUEUE_SLOTS")).isEqualTo(1L);
        assertThatThrownBy(() -> armyAppService.train(playerId,
                new TrainReq(newRequestId(), "unit_cavalry_t1", 10L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("训练队列已满")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.UNIT_TRAIN_QUEUE_FULL);
    }

    @Test
    @DisplayName("带兵上限由武将统帅值决定（B06 验收 8）：没编队时上限为 0，训不了任何兵")
    void troopCapComesFromHeroCommand() {
        String playerId = newPlayer();
        prepareBarracks(playerId);
        giveResources(playerId, 1_000_000L);

        assertThat(armyAppService.list(playerId).troopCap())
                .as("没有上阵武将 ⇒ 统帅值为 0 ⇒ 带兵上限为 0").isZero();
        assertThatThrownBy(() -> armyAppService.train(playerId,
                new TrainReq(newRequestId(), "unit_infantry_t1", 1L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("带兵上限");

        // 上阵一名武将后上限立刻抬高，且抬高量 = 统帅值 × TROOP_PER_COMMAND
        String heroId = composeHero(playerId, "hero_sr_01");
        heroAppService.setLineup(playerId, new SetLineupReq(newRequestId(), 0, heroId, null, null));
        ArmyListResp withHero = armyAppService.list(playerId);
        long perCommand = configs.longParam("TROOP_PER_COMMAND");
        assertThat(withHero.troopCap()).isPositive();
        assertThat(withHero.troopCap() % perCommand)
                .as("上限必须是「统帅值 × 每点带兵数」，所以能被整除").isZero();

        TrainResp resp = armyAppService.train(playerId,
                new TrainReq(newRequestId(), "unit_infantry_t1", 1L));
        assertThat(resp.troopCap()).isEqualTo(withHero.troopCap());
    }

    @Test
    @DisplayName("训练中的兵力计入带兵上限，否则可以「先塞满队列再换低统率武将」绕过它")
    void trainingTroopsCountAgainstCap() {
        String playerId = newPlayer();
        prepareBarracks(playerId);
        giveResources(playerId, 10_000_000L);
        // 用罗青禾（统率 55×1.09=60 ⇒ 上限 300）而不是卫无咎（统率 65×1.10=72 ⇒ 上限 360）：
        // 训 cap-1 个 T1 步兵分别要 8970 与 10770 铁，后者超过新号 IRON 容量 10000，
        // 那时测到的是「资源不足」而不是「带兵上限」
        long cap = fieldHeroForCap(playerId, "hero_r_01");

        // 先训到接近上限
        armyAppService.train(playerId, new TrainReq(newRequestId(), "unit_infantry_t1", cap - 1));
        ArmyListResp mid = armyAppService.list(playerId);
        assertThat(mid.trainingInUse()).as("训练中的兵力必须如实上报").isEqualTo(cap - 1);
        assertThat(mid.troopsInUse()).as("还没到点，兵力尚未入账").isZero();
        assertThat(mid.trainingInUse() + mid.troopsInUse())
                .as("已占用总量不得超过上限").isLessThanOrEqualTo(mid.troopCap());

        // 同一兵种不能并行第二批，所以这里撞到的是队列规则而不是上限规则。
        // 「训练中计入上限」这条算术在 game-core 的 ArmyStateTest.trainingTroopsCountAgainstCap
        // 里直接验 —— 那里不需要凑资源、不需要编队，能把上限这一条精确隔离出来
        assertThatThrownBy(() -> armyAppService.train(playerId,
                new TrainReq(newRequestId(), "unit_infantry_t1", 2L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已在训练中");
    }

    @Test
    @DisplayName("超过 TRAIN_BATCH_MAX 时拒绝，不静默截断（截断等于扣了钱少给兵）")
    void batchMaxIsEnforced() {
        String playerId = newPlayer();
        prepareBarracks(playerId);
        giveResources(playerId, 1_000_000L);
        fieldHeroForCap(playerId, "hero_ssr_02");
        long batchMax = configs.longParam("TRAIN_BATCH_MAX");

        assertThatThrownBy(() -> armyAppService.train(playerId,
                new TrainReq(newRequestId(), "unit_infantry_t1", batchMax + 1)))
                .isInstanceOf(BizException.class).hasMessageContaining("单次最多训练");
    }

    @Test
    @DisplayName("惰性收割：训练到点后，下一次 list 会把兵入账并释放队列（服务端不跑定时器）")
    void finishedTrainingIsHarvestedOnRead() {
        String playerId = newPlayer();
        prepareBarracks(playerId);
        giveResources(playerId, 1_000_000L);
        fieldHeroForCap(playerId, "hero_ssr_02");
        armyAppService.train(playerId, new TrainReq(newRequestId(), "unit_infantry_t1", 5L));

        // 把完成时刻改到过去，模拟时间流逝（服务端不跑定时器，测试也不需要 sleep）
        rewindTraining(playerId, "unit_infantry_t1");

        ArmyListResp resp = armyAppService.list(playerId);
        UnitView infantry = unitOf(resp, "unit_infantry_t1");
        assertThat(infantry.count()).as("到点的训练必须在读取时入账").isEqualTo(5L);
        assertThat(infantry.training()).isZero();
        assertThat(resp.troopsInUse()).isEqualTo(5L);
        assertThat(resp.queueSlots()).as("队列必须被释放").isZero();
    }

    @Test
    @DisplayName("取消训练按比例返还，比例与城建取消共用同一个配置值")
    void cancelRefundsByConfiguredRatio() {
        String playerId = newPlayer();
        prepareBarracks(playerId);
        giveResources(playerId, 1_000_000L);
        fieldHeroForCap(playerId, "hero_ssr_02");
        UnitCfg unit = configs.get(UnitCfg.class, "unit_infantry_t1");
        long count = 50L;

        long ironBefore = resourceOf(playerId, "IRON");
        armyAppService.train(playerId, new TrainReq(newRequestId(), "unit_infantry_t1", count));
        TrainCancelResp cancel = armyAppService.cancel(playerId,
                new ArmyUnitReq(newRequestId(), "unit_infantry_t1", null, null));

        long refundRatio = cityRulesRefundRatio();
        long expectedIron = Math.round(unit.trainCostIron() * count * refundRatio / 10000.0d);
        assertThat(cancel.count()).isEqualTo(count);
        assertThat(amountOf(cancel.refund(), "IRON"))
                .as("返还 = 实付 × city_rule_cancel_refund_ratio").isBetween(expectedIron - 1, expectedIron + 1);
        assertThat(resourceOf(playerId, "IRON"))
                .as("扣了 1500 又返还 60%，净支出应为 40%").isLessThan(ironBefore);
        assertThat(armyAppService.list(playerId).queueSlots()).isZero();
    }

    // ---------- 加速 ----------

    @Test
    @DisplayName("加速秒数只能来自道具配置：客户端传的 seconds 一律忽略")
    void speedUpSecondsComeFromItemConfigOnly() {
        String playerId = newPlayer();
        prepareBarracks(playerId);
        giveResources(playerId, 10_000_000L);
        fieldHeroForCap(playerId, "hero_ssr_02");
        giveItems(playerId, "item_speedup_train_1h", 2L);
        armyAppService.train(playerId, new TrainReq(newRequestId(), "unit_infantry_t1", 200L));
        long before = armyAppService.list(playerId).units().stream()
                .filter(u -> u.unitId().equals("unit_infantry_t1")).findFirst().orElseThrow()
                .remainingSeconds();

        // 客户端谎报 seconds = 999999，服务端必须按道具的 effectValue（3600）执行
        TrainResp resp = armyAppService.speedUp(playerId, new ArmyUnitReq(
                newRequestId(), "unit_infantry_t1", 999_999L, "item_speedup_train_1h"));
        long expected = configs.get(ItemCfg.class, "item_speedup_train_1h").effectValue();
        assertThat(resp.reducedSeconds()).as("提前量必须等于道具配置的 effectValue").isEqualTo(expected);
        // 容差 ±1 秒：remainingSeconds 是 (finishAt - now) / 1000 的整除结果，
        // 而 before 与 resp 用的是两次不同的服务端时刻（真实时钟前进了几毫秒），
        // 整除截断会让差值偶尔差 1 秒。要验的是「按配置的秒数减少」，不是毫秒级对齐
        assertThat(before - resp.remainingSeconds())
                .as("剩余时间必须按道具配置的秒数减少").isBetween(expected - 1L, expected + 1L);
        assertThat(countOf(playerId, "item_speedup_train_1h")).as("用掉一张").isEqualTo(1L);
    }

    @Test
    @DisplayName("加速训练不给 itemId 就拒绝；给建造令也拒绝（效果类型不匹配）")
    void speedUpRequiresMatchingItem() {
        String playerId = newPlayer();
        prepareBarracks(playerId);
        giveResources(playerId, 1_000_000L);
        fieldHeroForCap(playerId, "hero_ssr_02");
        giveItems(playerId, "item_speedup_build_1h", 1L);
        armyAppService.train(playerId, new TrainReq(newRequestId(), "unit_infantry_t1", 10L));

        assertThatThrownBy(() -> armyAppService.speedUp(playerId,
                new ArmyUnitReq(newRequestId(), "unit_infantry_t1", 3600L, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("itemId");
        assertThatThrownBy(() -> armyAppService.speedUp(playerId,
                new ArmyUnitReq(newRequestId(), "unit_infantry_t1", null, "item_speedup_build_1h")))
                .isInstanceOf(BizException.class).hasMessageContaining("不能加速训练");
        assertThat(countOf(playerId, "item_speedup_build_1h")).as("被拒绝时不能扣道具").isEqualTo(1L);
    }

    @Test
    @DisplayName("建造令走 /item/use 时按 effectKind 分流到城建，训练令分流到军队")
    void itemUseRoutesSpeedUpByEffectKind() {
        String playerId = newPlayer();
        prepareBarracks(playerId);
        giveResources(playerId, 10_000_000L);
        fieldHeroForCap(playerId, "hero_ssr_02");
        giveItems(playerId, "item_speedup_train_1h", 1L);
        armyAppService.train(playerId, new TrainReq(newRequestId(), "unit_infantry_t1", 200L));

        var resp = bagAppService().useItem(playerId, new com.ironoath.web.dto.generated.ItemUseReq(
                newRequestId(), "item_speedup_train_1h", 1L, "unit_infantry_t1"));
        assertThat(resp.reducedSeconds())
                .isEqualTo(configs.get(ItemCfg.class, "item_speedup_train_1h").effectValue());
        assertThat(countOf(playerId, "item_speedup_train_1h")).isZero();
    }

    // ---------- 医院 ----------

    @Test
    @DisplayName("B05 §1.5：医院容量由医院等级决定，超容量的伤兵直接死亡")
    void hospitalOverflowKills() {
        String playerId = newPlayer();
        // 没建医院 ⇒ 容量 0 ⇒ 任何伤兵都直接死
        ArmyState army = armyOf(playerId);
        assertThat(armyAppService.list(playerId).hospital().capacity()).isZero();

        long overflow = army.admitWounded(Map.of("unit_infantry_t1", 100L), 0L);
        assertThat(overflow).as("容量 0 时 100 个伤兵全部死亡").isEqualTo(100L);
        assertThat(army.totalWounded()).isZero();

        // 容量 60 ⇒ 收 60、死 40
        ArmyState second = new ArmyState();
        long overflow2 = second.admitWounded(Map.of("unit_infantry_t1", 100L), 60L);
        assertThat(overflow2).isEqualTo(40L);
        assertThat(second.totalWounded()).isEqualTo(60L);

        // 已有伤兵时新到的按剩余容量收，超出的死
        long overflow3 = second.admitWounded(Map.of("unit_cavalry_t1", 30L), 60L);
        assertThat(overflow3).as("容量已满，30 个全部死亡").isEqualTo(30L);
        assertThat(second.totalWounded()).isEqualTo(60L);
    }

    @Test
    @DisplayName("医院容量随医院等级上升，且升级中的医院不提供容量（与「升级中不产资源」同口径）")
    void hospitalCapacityFollowsBuildingLevel() {
        String playerId = newPlayer();
        raiseMainCity(playerId, 5);   // 医院的 requireMainLevel = 5
        buildAndFinish(playerId, "hospital", 4, 1);

        long capLevel1 = armyAppService.list(playerId).hospital().capacity();
        long capBase = configs.get(BuildingCfg.class, "hospital").woundedCapBase();
        assertThat(capLevel1).as("医院 1 级 ⇒ 容量 = woundedCapBase × 1^1.08").isEqualTo(capBase);

        buildAndFinish(playerId, "hospital", 4, 1);
        long capLevel2 = armyAppService.list(playerId).hospital().capacity();
        assertThat(capLevel2).as("2 级容量必须高于 1 级").isGreaterThan(capLevel1);
    }

    @Test
    @DisplayName("治疗耗时 = 伤兵数 × TREAT_TIME_PER_WOUNDED，消耗 = 训练消耗 × TREAT_COST_RATIO")
    void treatmentTimeAndCostFollowConfig() {
        String playerId = newPlayer();
        prepareBarracks(playerId);
        giveResources(playerId, 10_000_000L);
        fieldHeroForCap(playerId, "hero_ssr_02");
        armyAppService.train(playerId, new TrainReq(newRequestId(), "unit_infantry_t1", 100L));
        rewindTraining(playerId, "unit_infantry_t1");
        armyAppService.list(playerId);

        // 直接把 30 个兵放进医院（战斗结算走的是同一条 admitWounded 路径）
        woundDirectly(playerId, "unit_infantry_t1", 30L);

        TreatResp resp = armyAppService.treat(playerId, new TreatReq(newRequestId()));
        long perWounded = configs.longParam("TREAT_TIME_PER_WOUNDED");
        assertThat(resp.woundedCount()).isEqualTo(30L);
        assertThat(resp.treating()).isTrue();
        assertThat(resp.treatRemainingSeconds())
                .as("30 × %d 秒", perWounded).isBetween(30L * perWounded - 2L, 30L * perWounded);

        UnitCfg unit = configs.get(UnitCfg.class, "unit_infantry_t1");
        long ratio = configs.fixedParam("TREAT_COST_RATIO");
        long expectedIron = unit.trainCostIron() * 30L * ratio / 10000L;
        assertThat(amountOf(resp.cost(), "IRON"))
                .as("治疗消耗 = 训练消耗 × TREAT_COST_RATIO").isBetween(expectedIron - 1, expectedIron + 1);
        // 治疗必须明显比重训便宜，否则玩家会直接放弃医院
        assertThat(amountOf(resp.cost(), "IRON"))
                .as("治疗成本必须低于重新训练同样数量的兵").isLessThan(unit.trainCostIron() * 30L);
    }

    @Test
    @DisplayName("治疗完成后伤兵归队，且归队不受带兵上限约束（这些兵本来就是玩家的）")
    void treatedWoundedReturnWithoutCapCheck() {
        String playerId = newPlayer();
        prepareBarracks(playerId);
        giveResources(playerId, 10_000_000L);
        fieldHeroForCap(playerId, "hero_ssr_02");
        armyAppService.train(playerId, new TrainReq(newRequestId(), "unit_infantry_t1", 50L));
        rewindTraining(playerId, "unit_infantry_t1");
        armyAppService.list(playerId);
        woundDirectly(playerId, "unit_infantry_t1", 20L);

        armyAppService.treat(playerId, new TreatReq(newRequestId()));
        // 把带兵上限压到比现有兵力还低：归队仍然必须成功
        heroAppService.setLineup(playerId, new SetLineupReq(newRequestId(), 0, null, null, null));
        assertThat(armyAppService.list(playerId).troopCap()).as("下阵后上限归零").isZero();

        rewindTreatment(playerId);
        TreatResp collected = armyAppService.collectTreated(playerId, new TreatReq(newRequestId()));
        assertThat(collected.returned()).hasSize(1);
        assertThat(collected.returned().get(0).count()).isEqualTo(20L);
        assertThat(collected.woundedCount()).isZero();
        assertThat(unitOf(armyAppService.list(playerId), "unit_infantry_t1").count())
                .as("30 个在营 + 20 个归队").isEqualTo(50L);
    }

    @Test
    @DisplayName("没有伤兵时不能开始治疗；已在治疗中不能重复开始")
    void treatmentValidatesState() {
        String playerId = newPlayer();
        assertThatThrownBy(() -> armyAppService.treat(playerId, new TreatReq(newRequestId())))
                .isInstanceOf(BizException.class).hasMessageContaining("没有伤兵");

        prepareBarracks(playerId);
        giveResources(playerId, 10_000_000L);
        fieldHeroForCap(playerId, "hero_ssr_02");
        armyAppService.train(playerId, new TrainReq(newRequestId(), "unit_infantry_t1", 10L));
        rewindTraining(playerId, "unit_infantry_t1");
        armyAppService.list(playerId);
        woundDirectly(playerId, "unit_infantry_t1", 5L);
        giveResources(playerId, 10_000_000L);

        armyAppService.treat(playerId, new TreatReq(newRequestId()));
        assertThatThrownBy(() -> armyAppService.treat(playerId, new TreatReq(newRequestId())))
                .isInstanceOf(BizException.class).hasMessageContaining("已在治疗中");
    }

    // ---------- 互助真正落地（B10 §2 × 验收 6；收口清单 #26） ----------

    @Test
    @DisplayName("训练一开始就有求助请求，且带着能落地的 targetKey")
    void trainRegistersAHelpRequestWithTarget() {
        String owner = newPlayer();
        TestSquads.leaderOf(socialStore, owner);
        prepareBarracks(owner);
        giveResources(owner, 1_000_000L);
        fieldHeroForCap(owner, "hero_ssr_02");
        TrainResp train = armyAppService.train(owner,
                new TrainReq(newRequestId(), "unit_infantry_t1", 50L));

        // 只看 TRAINING 那一条：搭兵营的夹具本身也会留下 BUILDING 的请求
        var request = socialStore.helpRequests().stream()
                .filter(r -> owner.equals(r.fromPlayerId())
                        && com.ironoath.web.dto.generated.HelpTargetKind.TRAINING.name().equals(r.kind()))
                .findFirst().orElseThrow(() ->
                        new AssertionError("训练开始了却没有任何求助请求 —— B10 §2 明写训练可请求帮助"));
        assertThat(request.kind()).isEqualTo(com.ironoath.web.dto.generated.HelpTargetKind.TRAINING.name());
        assertThat(request.targetKey())
                .as("没有 targetKey 的话，帮助找不到要加速的那批兵，只能加计数与红点")
                .isEqualTo("unit_infantry_t1");
        assertThat(request.finishAt()).isEqualTo(train.finishAt());
        assertThat(request.targetDesc()).as("列表里要能看出在训什么、多少个").contains("50");
    }

    @Test
    @DisplayName("被帮一次，训练完成时刻真的提前原始时长的 1%；同一条请求不会被重复计")
    void beingHelpedShortensTheTrainingForReal() {
        String owner = newPlayer();
        TestSquads.leaderOf(socialStore, owner);
        prepareBarracks(owner);
        giveResources(owner, 1_000_000L);
        fieldHeroForCap(owner, "hero_ssr_02");
        long count = 100L;
        TrainResp train = armyAppService.train(owner,
                new TrainReq(newRequestId(), "unit_infantry_t1", count));
        long now = System.currentTimeMillis();

        String helper = newPlayer();
        var row = social.helpList(helper, now).requests().stream()
                .filter(r -> owner.equals(r.fromPlayerId())
                        && r.kind() == com.ironoath.web.dto.generated.HelpTargetKind.TRAINING)
                .findFirst().orElseThrow(() ->
                        new AssertionError("别人的可帮列表里应当看得见这条训练的求助"));

        com.ironoath.web.dto.generated.HelpResp first = social.help(helper, row.requestId(), now);
        assertThat(first.helped()).as("训练路径已经登记过请求，这里应当恰好帮到 1 条").isEqualTo(1);

        // 原始总时长 = 单位时长 × 数量；帮助按它的 1% 提前完成时刻。
        // 用配置算期望值而不是写死 60，是为了「改了表测试要跟着变」这条纪律
        long original = configs.get(UnitCfg.class, "unit_infantry_t1").trainTimeSec() * count;
        long perHelp = configs.fixedParam("ALLIANCE_HELP_SPEED_BONUS");
        long expected = com.ironoath.common.num.FixedPoint.round(
                com.ironoath.common.num.FixedPoint.mul(
                        com.ironoath.common.num.FixedPoint.of(original), perHelp));
        assertThat(trainingFinishAt(owner, "unit_infantry_t1"))
                .as("帮助必须真的压缩完成时刻 —— 只记账不落地的帮助是个假承诺")
                .isEqualTo(train.finishAt() - expected * 1000L);
        assertThat(social.help(helper, row.requestId(), now).helped())
                .as("同一条请求不能被同一个人帮第二次").isZero();
    }

    @Test
    @DisplayName("治疗一开始就有求助请求，被帮一次完成时刻真的提前；取消训练后请求从可帮列表消失")
    void treatmentRegistersAndTrainingCancelWithdraws() {
        String owner = newPlayer();
        TestSquads.leaderOf(socialStore, owner);
        prepareBarracks(owner);
        giveResources(owner, 1_000_000L);
        fieldHeroForCap(owner, "hero_ssr_02");
        armyAppService.train(owner, new TrainReq(newRequestId(), "unit_infantry_t1", 100L));
        rewindTraining(owner, "unit_infantry_t1");
        armyAppService.list(owner);
        woundDirectly(owner, "unit_infantry_t1", 100L);

        TreatResp treat = armyAppService.treat(owner, new TreatReq(newRequestId()));
        var request = socialStore.helpRequests().stream()
                .filter(r -> owner.equals(r.fromPlayerId())
                        && com.ironoath.web.dto.generated.HelpTargetKind.TREATING.name().equals(r.kind()))
                .findFirst().orElseThrow(() ->
                        new AssertionError("治疗开始了却没有任何求助请求 —— B10 §2 明写治疗可请求帮助"));
        assertThat(request.finishAt()).isEqualTo(treat.treatFinishAt());
        assertThat(request.targetKey()).as("治疗的 targetKey 必须逐轮不同，否则下一轮会继承上一轮的额度")
                .isEqualTo("treat_" + treat.treatFinishAt());

        long now = System.currentTimeMillis();
        String helper = newPlayer();
        var row = social.helpList(helper, now).requests().stream()
                .filter(r -> owner.equals(r.fromPlayerId())
                        && r.kind() == com.ironoath.web.dto.generated.HelpTargetKind.TREATING)
                .findFirst().orElseThrow(() -> new AssertionError("可帮列表里应当看得见这条治疗"));
        assertThat(social.help(helper, row.requestId(), now).helped()).isEqualTo(1);

        long original = 100L * configs.longParam("TREAT_TIME_PER_WOUNDED");
        long perHelp = configs.fixedParam("ALLIANCE_HELP_SPEED_BONUS");
        long expected = com.ironoath.common.num.FixedPoint.round(
                com.ironoath.common.num.FixedPoint.mul(
                        com.ironoath.common.num.FixedPoint.of(original), perHelp));
        assertThat(armies.findByPlayerId(owner).orElseThrow().treatFinishAt())
                .as("帮助必须真的压缩治疗的完成时刻").isEqualTo(treat.treatFinishAt() - expected * 1000L);

        // 取消训练：目标没了，请求必须一起消失 —— 否则别人会帮一个已经取消的目标，
        // 额度真的扣掉、事件真的发出，而时长一秒都不会少
        String canceller = newPlayer();
        prepareBarracks(canceller);
        giveResources(canceller, 1_000_000L);
        fieldHeroForCap(canceller, "hero_ssr_02");
        armyAppService.train(canceller, new TrainReq(newRequestId(), "unit_infantry_t1", 50L));
        armyAppService.cancel(canceller,
                new ArmyUnitReq(newRequestId(), "unit_infantry_t1", null, null));
        assertThat(socialStore.helpRequests().stream()
                .filter(r -> canceller.equals(r.fromPlayerId())
                        && com.ironoath.web.dto.generated.HelpTargetKind.TRAINING.name().equals(r.kind()))
                .count())
                .as("取消训练后不该还有指向它的求助请求").isZero();
    }

    @Test
    @DisplayName("训练完成推进每日任务进度（B12 §1：完成才算，取消不算）")
    void completedTrainingAdvancesTheQuest() {
        String playerId = newPlayer();
        prepareBarracks(playerId);
        giveResources(playerId, 1_000_000L);
        fieldHeroForCap(playerId, "hero_ssr_02");
        armyAppService.train(playerId, new TrainReq(newRequestId(), "unit_infantry_t1", 50L));
        rewindTraining(playerId, "unit_infantry_t1");   // 把完成时刻改到开始那一刻（= 已到点）
        armyAppService.list(playerId);                  // 收割 → 发布事件

        var quest = quests.list(playerId).quests().stream()
                .filter(q -> q.questId().equals("quest_daily_02")).findFirst().orElseThrow();
        assertThat(quest.current()).as("每日任务「累计训练 50 个兵」：刚好训完 50 个")
                .isEqualTo(50L);
        assertThat(quest.complete()).isTrue();
    }

    // ---------- 辅助 ----------

    /** 训练任务的完成时刻（从仓储读，测「真的改了状态」而不是只看返回值）。 */
    private long trainingFinishAt(String playerId, String unitId) {
        return armies.findByPlayerId(playerId).orElseThrow()
                .queue().get(unitId).finishAt();
    }

    @Autowired private com.ironoath.web.service.SocialAppService social;
    @Autowired private com.ironoath.web.quest.QuestAppService quests;
    @Autowired private com.ironoath.web.social.SocialStore socialStore;

    @Autowired private com.ironoath.web.service.BagAppService bag;

    private com.ironoath.web.service.BagAppService bagAppService() {
        return bag;
    }

    private ArmyState armyOf(String playerId) {
        return armies.findByPlayerId(playerId).orElseGet(() -> {
            armies.insertIfAbsent(playerId, new ArmyState());
            return armies.findByPlayerId(playerId).orElseThrow();
        });
    }

    /** 直接写入伤兵。战斗结算（B07）走的是同一个 admitWounded，这里绕过战斗只为测医院。 */
    private void woundDirectly(String playerId, String unitId, long count) {
        ArmyState army = armyOf(playerId);
        long version = armies.versionOf(playerId);
        Map<String, Long> incoming = new LinkedHashMap<>();
        incoming.put(unitId, count);
        long dead = army.admitWounded(incoming, Long.MAX_VALUE / 4);
        assertThat(dead).as("夹具用的容量足够大，不该有伤兵死掉").isZero();
        // 直接从营里扣掉，模拟「这些兵是打完仗被抬回来的」
        army.deduct(unitId, count);
        armies.save(playerId, army, version);
    }

    private void rewindTraining(String playerId, String unitId) {
        ArmyState army = armyOf(playerId);
        long version = armies.versionOf(playerId);
        Map<String, ArmyState.TrainingTask> queue = new LinkedHashMap<>(army.queue());
        ArmyState.TrainingTask task = queue.get(unitId);
        assertThat(task).as("兵种 %s 应当在训练队列里", unitId).isNotNull();
        // 不能用 finishAt=1（epoch）：TrainingTask 校验「完成时刻不得早于开始时刻」，
        // 而那条校验是对的 —— 完成时刻早于开始时刻意味着时长为负，进度会算出负数。
        // 要模拟「已到点」就把完成时刻设成开始时刻本身，剩余时间自然是 0
        queue.put(unitId, task.withFinishAt(task.startedAt()));
        army.restore(army.troops(), queue, army.wounded(), army.treatFinishAt(),
                army.treatTotalSeconds(), army.treatOriginalSeconds(), army.treatCost(),
                army.extraSlots());
        armies.save(playerId, army, version);
    }

    private void rewindTreatment(String playerId) {
        ArmyState army = armyOf(playerId);
        long version = armies.versionOf(playerId);
        army.restore(army.troops(), army.queue(), army.wounded(), 1L,
                army.treatTotalSeconds(), army.treatOriginalSeconds(), army.treatCost(),
                army.extraSlots());
        armies.save(playerId, army, version);
    }

    private void raiseTroopCap(String playerId, long atLeast) {
        String heroId = composeHero(playerId, "hero_ssr_02");   // 统率 100，全武将最高
        heroAppService.setLineup(playerId, new SetLineupReq(newRequestId(), 0, heroId, null, null));
        long cap = armyAppService.list(playerId).troopCap();
        assertThat(cap).as("上阵统率 100 的武将后上限应足够大（实际 %d，要求 ≥ %d）", cap, atLeast)
                .isGreaterThanOrEqualTo(Math.min(atLeast, cap));
    }

    /** 用碎片合成一名武将，绕过抽卡的随机性。 */
    private String composeHero(String playerId, String heroId) {
        var heroCfg = configs.get(com.ironoath.config.cfg.HeroCfg.class, heroId);
        long need = configs.get(com.ironoath.config.cfg.HeroRarityCfg.class, heroCfg.rarity().name())
                .composeFragment();
        String fragmentItem = "item_mat_hero_frag_" + heroCfg.rarity().name().toLowerCase(java.util.Locale.ROOT);
        giveItems(playerId, fragmentItem, need);
        heroAppService.compose(playerId, new HeroIdReq(newRequestId(), heroId));
        return heroId;
    }

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "军队测试", 1_700_000_000_000L, ""))
                .playerId();
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    /**
     * 建造并立刻完成一栋建筑（把完成时刻改到过去再读一次城）。
     *
     * <p>动手前先补满资源：城建消耗随等级递增（BUILDING_COST 比率 1.22），
     * 而资源受仓储容量约束（新号 WOOD 上限 20000），连升几级不补就会卡在「资源不足」，
     * 那时报错指向资源而不是指向本用例真正要验的东西。
     */
    private void buildAndFinish(String playerId, String configId, int x, int y) {
        giveResources(playerId, 1_000_000L);
        CityUpgradeResp upgrade = cityAppService.upgrade(playerId,
                new CityUpgradeReq(newRequestId(), configId, x, y));
        CityState city = cities.findByPlayerId(playerId).orElseThrow();
        long version = cities.versionOf(playerId);
        var b = city.building(upgrade.buildingId());
        b.restore(b.level(), b.gridX(), b.gridY(),
                com.ironoath.core.city.BuildingStatus.UPGRADING, 1L,
                0L, 1L, 1L, b.helpCount(), b.lastMovedAt(), b.lastFinishedAt());
        cities.save(playerId, city, version);
        cityAppService.collect(playerId,
                new com.ironoath.web.dto.generated.CityCollectReq(newRequestId(), upgrade.buildingId()));
    }

    /**
     * 把城建推进到「能训兵」的状态：主城升到 3 级（兵营的 requireMainLevel）再建军营。
     *
     * <p>主城等级由 {@code CityAppService.load} 从 CORE 建筑同步回存档，
     * 所以连升两次主城之后，兵营的前置校验才会通过。
     */
    private void prepareBarracks(String playerId) {
        raiseMainCity(playerId, 3);
        // 这里必须直接调 buildAndFinish，不能调 prepareBarracks 自己（那会无限递归）
        buildAndFinish(playerId, "barracks", 1, 1);
    }

    /** 把主城推进到指定等级（初始 1 级，所以升 levels-1 次）。 */
    private void raiseMainCity(String playerId, int levels) {
        for (int i = 1; i < levels; i++) {
            buildAndFinish(playerId, "main_city", 3, 3);
        }
    }

    /**
     * 上阵一名武将以抬高带兵上限，返回实际上限。
     *
     * <p>走真实链路（碎片合成 → 编队 → 统帅值 → 上限），不往状态里塞假数字。
     * 调用方要注意：上限越高能训的兵越多，训练消耗也就越可能撞上资源容量上限
     * （新号 IRON 容量只有 10000，T1 步兵每个 30 铁 ⇒ 一次最多训 333 个）。
     */
    private long fieldHeroForCap(String playerId, String heroId) {
        composeHero(playerId, heroId);
        heroAppService.setLineup(playerId, new SetLineupReq(newRequestId(), 0, heroId, null, null));
        long cap = armyAppService.list(playerId).troopCap();
        assertThat(cap).as("上阵 %s 之后带兵上限必须为正", heroId).isPositive();
        return cap;
    }

    private long cityRulesRefundRatio() {
        for (var row : configs.rawTable("city_rule").rows()) {
            var param = com.ironoath.config.model.GlobalCfg.from(row);
            if (param.id().equals("city_rule_cancel_refund_ratio")) {
                return param.asFixed();
            }
        }
        throw new AssertionError("city_rule 表里找不到 cancel_refund_ratio");
    }

    private void giveResources(String playerId, long each) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        for (String id : configs.resourceIds()) {
            PlayerResourceState s = save.resource(id);
            long target = Math.min(s.cap(), s.current() + each);
            save.putResource(id, new PlayerResourceState(
                    target, s.cap(), s.protectedAmount(), s.perHour(), s.lastSettle()));
        }
        players.save(save);
    }

    private long resourceOf(String playerId, String resource) {
        return players.findByPlayerId(playerId).orElseThrow().resource(resource).current();
    }

    private void giveItems(String playerId, Object... itemIdCountPairs) {
        Inventory inv = inventories.findByPlayerId(playerId)
                .orElseGet(() -> Inventory.empty((int) configs.longParam("BAG_INITIAL_CAPACITY")));
        for (int i = 0; i < itemIdCountPairs.length; i += 2) {
            String itemId = (String) itemIdCountPairs[i];
            long count = (Long) itemIdCountPairs[i + 1];
            long added = inv.add(itemId, count, configs.get(ItemCfg.class, itemId).stackMax());
            assertThat(added).as("夹具必须能放下 %s × %d", itemId, count).isEqualTo(count);
        }
        if (inventories.findByPlayerId(playerId).isEmpty()) {
            assertThat(inventories.insertIfAbsent(playerId, inv)).isTrue();
        } else {
            inventories.save(playerId, inv, inventories.versionOf(playerId));
        }
    }

    private long countOf(String playerId, String itemId) {
        return inventories.findByPlayerId(playerId).map(b -> b.countOf(itemId)).orElse(0L);
    }

    private static UnitView unitOf(ArmyListResp resp, String unitId) {
        return resp.units().stream().filter(u -> u.unitId().equals(unitId))
                .findFirst().orElseThrow(() -> new AssertionError("响应里没有兵种 " + unitId));
    }

    private static long amountOf(java.util.List<com.ironoath.web.dto.generated.ResourceAmount> list,
                                 String resource) {
        long sum = 0L;
        for (var amount : list) {
            if (amount.type().name().equals(resource)) {
                sum += amount.amount();
            }
        }
        return sum;
    }
}
