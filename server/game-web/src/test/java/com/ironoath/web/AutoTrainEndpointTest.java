package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.AutoTrainReq;
import com.ironoath.web.dto.generated.CityCollectReq;
import com.ironoath.web.dto.generated.CityUpgradeReq;
import com.ironoath.web.dto.generated.CityUpgradeResp;
import com.ironoath.web.dto.generated.HeroIdReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.SetLineupReq;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.HeroAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryHeroStore;
import com.ironoath.web.store.memory.InMemoryInventoryStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 职责：B25-S2 的验收 3 / 4 / 5 在 HTTP 层成立 —— 自动续训与自动补兵是**花钱的真人路径**，
 * 且**关得掉**。依赖：Spring Boot Test + MockMvc（与 {@code MarchRepeatInvariantsTest} 同一套手法）。
 *
 * <p><b>本类要钉住的三件事</b>：
 * <ol>
 *   <li><b>验收 3</b>：一批训完之后真的又排了一批，且资源**真的又扣了一次** ——
 *       对账用「余额 = 满仓 − 两批成本」这种能失败的等式，不是「看起来变少了」；</li>
 *   <li><b>验收 4</b>：阵亡之后缺口被补回，但走的是训练那一套 ——
 *       完成时刻是「现在 + 数量 × 单位时长」，队列位照旧占着，**不是瞬间补满**；</li>
 *   <li><b>验收 5</b>：关掉之后不再排；资源不足会自动停且**不会自己恢复**（要玩家再开一次）。</li>
 * </ol>
 *
 * <p><b>夹具纪律</b>：初始兵力用 {@code ArmyState#add}、阵亡用 {@code ArmyState#deduct} ——
 * 这两个正是生产里战斗结算（{@code StageAppService#applyLosses}）与伤兵归队用的方法，
 * 不是「往聚合里塞一个生产永远不会产生的形状」。城建与武将走真实链路（升级 → 建造 → 合成 → 上阵），
 * 因为带兵上限必须是真的，否则「补兵」会先撞在上限上，而用例却在验别的东西。
 *
 * <p><b>资源对账为什么可以取等号</b>：先把各资源补到满仓，满仓之后产出被封顶丢弃
 * （{@code PlayerResourceState} 禁止 {@code current > cap}），于是「余额 = 上限 − 成本」
 * 是一个精确等式。这一步不做，产出会以每小时几百的速度渗进来，精确断言就成了碰运气。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutoTrainEndpointTest {

    private static final String UNIT = "unit_infantry_t1";
    private static final String LIST_URL = "/army/list";
    private static final String AUTO_URL = "/army/autoTrain";
    private static final String PLAYER_HEADER = "X-Player-Id";

    @Autowired private MockMvc mockMvc;
    @Autowired private ConfigRegistry configs;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private CityAppService cityAppService;
    @Autowired private HeroAppService heroAppService;
    @Autowired private PlayerRepository players;
    @Autowired private ArmyRepository armies;
    @Autowired private CityRepository cities;
    @Autowired private HeroRepository heroes;
    @Autowired private InventoryRepository inventories;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
        ((InMemoryArmyStore) armies).clear();
        ((InMemoryHeroStore) heroes).clear();
        ((InMemoryInventoryStore) inventories).clear();
    }

    // ---------- 验收 3 ----------

    @Test
    @DisplayName("验收 3：一批训完之后自动又排了一批，且资源真的又扣了一次（余额 = 满仓 − 两批成本）")
    void autoRetrainQueuesTheNextBatchAndPaysForIt() throws Exception {
        String playerId = readyPlayer();
        long ironCap = capOf(playerId, "IRON");
        long grainCap = capOf(playerId, "GRAIN");
        long perBatch = 50L;

        // 开续训：每批 50，预算 2 批。开的那一刻就该排上第一批（否则玩家会以为开关没生效）
        JsonNode enabled = autoTrain(playerId, on(perBatch, 2, null));
        assertThat(enabled.path("enabled").asBoolean()).isTrue();
        assertThat(enabled.path("batchCount").asLong()).isEqualTo(perBatch);
        assertThat(enabled.path("batchBudget").asInt())
                .as("排掉第一批之后只剩 1 批预算").isEqualTo(1);

        ArmyState.TrainingTask first = task(playerId);
        assertThat(first).as("开启时那批应当已经在队列里").isNotNull();
        assertThat(first.count()).isEqualTo(perBatch);
        assertThat(resourceOf(playerId, "IRON"))
                .as("第一批的资源必须已经扣了（余额 = 满仓 − 一批成本）")
                .isEqualTo(ironCap - ironCost(perBatch));

        // 装两批之间的时间流逝：把完成时刻改到开始那一刻，下次读就是「这批训完了」
        rewindTraining(playerId);
        JsonNode after = list(playerId);

        assertThat(after.path("autoTrain").path("enabled").asBoolean())
                .as("两批排完（预算用尽）即关，不留一个 enabled=true 的半开状态").isFalse();
        assertThat(after.path("autoTrain").path("batchBudget").asInt()).isZero();
        assertThat(after.path("autoTrain").path("stopReason").asText())
                .as("预算用尽要留下一句人话的原因").contains("预算");

        ArmyState.TrainingTask second = task(playerId);
        assertThat(second).as("自动续训必须真的又排了一批进队列").isNotNull();
        assertThat(second.count()).isEqualTo(perBatch);
        assertThat(second.finishAt()).as("新批次的完成时刻必须晚于现在（不是把上一批原样留下）")
                .isGreaterThan(after.path("serverNow").asLong());
        assertThat(remainingSecondsOf(after, UNIT)).as("训练时长照旧：50 个 T1 = 50 × 60 秒")
                .isEqualTo(perBatch * trainTimeSec());

        // 验收 3 的对账：两批成本都扣了，一分不多一分不少
        assertThat(resourceOf(playerId, "IRON"))
                .as("余额 = 满仓 − 两批铁").isEqualTo(ironCap - 2 * ironCost(perBatch));
        assertThat(resourceOf(playerId, "GRAIN"))
                .as("余额 = 满仓 − 两批粮").isEqualTo(grainCap - 2 * grainCost(perBatch));

        // 再读一次：预算已用尽，什么都不该发生（重登/刷新不多扣）
        JsonNode again = list(playerId);
        assertThat(again.path("autoTrain").path("enabled").asBoolean()).isFalse();
        assertThat(task(playerId).finishAt()).as("没有第三批").isEqualTo(second.finishAt());
        assertThat(resourceOf(playerId, "IRON")).as("第三次读不再扣资源")
                .isEqualTo(ironCap - 2 * ironCost(perBatch));
    }

    @Test
    @DisplayName("重连/刷新不叠批：同一批还在训时，再读几次也不会多排或多扣")
    void readingAgainWhileTheBatchIsRunningDoesNothing() throws Exception {
        String playerId = readyPlayer();
        long ironCap = capOf(playerId, "IRON");
        autoTrain(playerId, on(50L, 3, null));
        long finishAt = task(playerId).finishAt();
        long ironAfterFirst = resourceOf(playerId, "IRON");
        assertThat(ironAfterFirst).isEqualTo(ironCap - ironCost(50L));

        for (int i = 0; i < 3; i++) {
            list(playerId);
        }

        assertThat(task(playerId).finishAt()).as("队列里的还是那一批").isEqualTo(finishAt);
        assertThat(resourceOf(playerId, "IRON")).as("一次都没多扣").isEqualTo(ironAfterFirst);
        assertThat(list(playerId).path("autoTrain").path("batchBudget").asInt())
                .as("预算也没被多减").isEqualTo(2);
    }

    // ---------- 验收 4 ----------

    @Test
    @DisplayName("验收 4：阵亡后按缺口补回，且照旧占用队列与训练时长（不是瞬间补满）")
    void refillCoversTheGapThroughRealTraining() throws Exception {
        String playerId = readyPlayer();
        long ironCap = capOf(playerId, "IRON");
        long target = troopCapOf(playerId);
        assertThat(target).as("带兵上限要够容下本用例的编成").isGreaterThan(300L);
        giveTroops(playerId, target);

        // 开补兵：目标 = 编成（这里就用满编成），每批最多 500，预算 3
        JsonNode enabled = autoTrain(playerId, on(500L, 3, target));
        assertThat(enabled.path("targetCount").asLong()).isEqualTo(target);
        assertThat(enabled.path("enabled").asBoolean()).isTrue();
        assertThat(task(playerId)).as("编成是满的，此刻没有缺口要补").isNull();
        assertThat(enabled.path("stopReason").isNull())
                .as("补满是等着，不是停下：不留停止原因").isTrue();

        // 阵亡 300（与战斗结算同一条：army.deduct）
        killTroops(playerId, 300L);
        assertThat(troopCountOf(playerId, UNIT)).isEqualTo(target - 300L);

        JsonNode after = list(playerId);

        ArmyState.TrainingTask patch = task(playerId);
        assertThat(patch).as("缺口要用训练补，所以要真的排一批").isNotNull();
        assertThat(patch.count()).as("只补缺口（300 < 单批上限 500），不为凑批次多训").isEqualTo(300L);
        assertThat(troopCountOf(playerId, UNIT))
                .as("不是瞬间补满：手上还是缺的，那 300 在队列里").isEqualTo(target - 300L);
        assertThat(remainingSecondsOf(after, UNIT))
                .as("训练时长照旧：300 个 T1 = 300 × 60 秒").isEqualTo(300L * trainTimeSec());
        assertThat(after.path("queueSlots").asInt()).as("队列位照旧占用（1/1）").isEqualTo(1);
        assertThat(resourceOf(playerId, "IRON"))
                .as("补兵也要真花钱：余额 = 满仓 − 300 个的铁").isEqualTo(ironCap - ironCost(300L));
        assertThat(after.path("autoTrain").path("enabled").asBoolean())
                .as("补完这一批之后策略要留着等下一条命阵亡，而不是关掉").isTrue();

        // 补兵完成 ⇒ 编成回到目标，且不再继续排（没有缺口）
        rewindTraining(playerId);
        JsonNode filled = list(playerId);
        assertThat(troopCountOf(playerId, UNIT)).isEqualTo(target);
        assertThat(task(playerId)).as("补满了就不该再排").isNull();
        assertThat(resourceOf(playerId, "IRON")).as("补满那一刻不再扣钱").isEqualTo(ironCap - ironCost(300L));
        assertThat(filled.path("autoTrain").path("enabled").asBoolean()).isTrue();
        assertThat(filled.path("autoTrain").path("stopReason").isNull()).isTrue();
        assertThat(filled.path("autoTrain").path("batchBudget").asInt())
                .as("预算只剩 2 批（用掉的是补缺口那一批）").isEqualTo(2);
    }

    // ---------- 验收 5 ----------

    @Test
    @DisplayName("验收 5：关掉之后不再自动排下一批（一次都不排）")
    void turningItOffStopsTheNextBatch() throws Exception {
        String playerId = readyPlayer();
        long ironCap = capOf(playerId, "IRON");
        autoTrain(playerId, on(50L, 3, null));
        assertThat(task(playerId)).as("开着的时候第一批真的排上了").isNotNull();
        long ironWithFirstBatch = resourceOf(playerId, "IRON");
        assertThat(ironWithFirstBatch).isEqualTo(ironCap - ironCost(50L));

        // 关：只给 requestId 与 enabled=false（不必再报一遍目标，否则玩家会以为关不掉）
        JsonNode off = autoTrain(playerId, new AutoTrainReq(newRequestId(), false, null, null, null, null));
        assertThat(off.path("enabled").asBoolean()).isFalse();
        assertThat(off.path("stopReason").isNull()).as("玩家主动关掉，不是停出问题：没有停止原因").isTrue();

        // 让这一批训完，再读：关掉了就不该有第二批
        rewindTraining(playerId);
        JsonNode after = list(playerId);
        assertThat(task(playerId)).as("关掉之后不再自动排下一批").isNull();
        assertThat(countOf(after, UNIT)).as("第一批的 50 个兵照常到手").isEqualTo(50L);
        assertThat(resourceOf(playerId, "IRON")).as("关掉之后一分钱都不再扣").isEqualTo(ironWithFirstBatch);
        assertThat(after.path("autoTrain").path("enabled").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("资源不足 ⇒ 自动停下并留下原因，且不会自己恢复（要玩家再开一次）")
    void resourceShortfallStopsForGoodUntilReEnabled() throws Exception {
        String playerId = readyPlayer();
        autoTrain(playerId, on(50L, 3, null));
        rewindTraining(playerId);

        // 把资源清到付不起一批（玩家的资源本来就会被别处花掉，这是同一种状态）
        drainResources(playerId);
        long leftIron = resourceOf(playerId, "IRON");
        assertThat(leftIron).isLessThan(ironCost(50L));

        JsonNode stopped = list(playerId);
        assertThat(task(playerId)).as("付不起就不排").isNull();
        assertThat(stopped.path("autoTrain").path("enabled").asBoolean()).isFalse();
        assertThat(stopped.path("autoTrain").path("stopReason").asText())
                .as("资源不足的停止原因要玩家读得懂").contains("资源不够");
        assertThat(resourceOf(playerId, "IRON")).as("没排成就没扣钱").isEqualTo(leftIron);

        // 资源回来了也不接着排：否则玩家睡醒会发现攒下的资源被花光
        giveResources(playerId);
        JsonNode stillStopped = list(playerId);
        assertThat(task(playerId)).as("不自动恢复").isNull();
        assertThat(stillStopped.path("autoTrain").path("stopReason").asText()).contains("资源不够");
        assertThat(resourceOf(playerId, "IRON")).as("资源一分没动").isEqualTo(capOf(playerId, "IRON"));

        // 玩家再开一次：这次立刻排上（说明关得掉的同时也开得回来）
        autoTrain(playerId, on(50L, 3, null));
        assertThat(task(playerId)).as("再开一次就立刻恢复").isNotNull();
    }

    @Test
    @DisplayName("预算超上限当场被拒（PARAM_INVALID），且不落库 —— 服务端不接受无限支出")
    void budgetAboveTheCapIsRejected() throws Exception {
        String playerId = readyPlayer();
        int max = (int) configs.longParam("AUTO_TRAIN_MAX_BATCHES");
        int code = autoTrainCode(playerId, on(50L, max + 1, null));
        assertThat(code).isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(list(playerId).path("autoTrain").path("enabled").asBoolean())
                .as("被拒的请求不能留下半个开关").isFalse();
    }

    @Test
    @DisplayName("未解锁的兵种不许开自动续训（否则它会一直排在解锁门槛上）")
    void lockedUnitCannotBeEnabled() throws Exception {
        String playerId = readyPlayer();
        int code = autoTrainCode(playerId, new AutoTrainReq(newRequestId(), true,
                "unit_infantry_t3", 10L, 1, null));
        assertThat(code).isEqualTo(ErrorCode.UNIT_NOT_UNLOCKED.code());
        assertThat(list(playerId).path("autoTrain").path("enabled").asBoolean()).isFalse();
    }

    // ---------- HTTP ----------

    private AutoTrainReq on(long count, int budget, Long target) {
        return new AutoTrainReq(newRequestId(), true, UNIT, count, budget, target);
    }

    /** 开关一次，断言成功，返回响应里的 autoTrain 视图。 */
    private JsonNode autoTrain(String playerId, AutoTrainReq req) throws Exception {
        MvcResult result = mockMvc.perform(post(AUTO_URL)
                        .header(PLAYER_HEADER, playerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtils.toJson(req)))
                .andReturn();
        JsonNode root = JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(root.get("code").asInt()).as("开关自动续训失败：%s", root).isZero();
        return root.path("data").path("autoTrain");
    }

    private int autoTrainCode(String playerId, AutoTrainReq req) throws Exception {
        MvcResult result = mockMvc.perform(post(AUTO_URL)
                        .header(PLAYER_HEADER, playerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtils.toJson(req)))
                .andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("code").asInt();
    }

    /** 读军队总览（= 服务端做惰性结算与自动续训的那个入口），返回 data 节点。 */
    private JsonNode list(String playerId) throws Exception {
        MvcResult result = mockMvc.perform(get(LIST_URL).header(PLAYER_HEADER, playerId)).andReturn();
        JsonNode root = JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(root.get("code").asInt()).as("读军队总览失败：%s", root).isZero();
        return root.path("data");
    }

    private long remainingSecondsOf(JsonNode listData, String unitId) {
        return unitNode(listData, unitId).path("remainingSeconds").asLong();
    }

    private long countOf(JsonNode listData, String unitId) {
        return unitNode(listData, unitId).path("count").asLong();
    }

    private JsonNode unitNode(JsonNode listData, String unitId) {
        for (JsonNode unit : listData.path("units")) {
            if (unit.path("unitId").asText().equals(unitId)) {
                return unit;
            }
        }
        throw new AssertionError("响应里没有兵种 " + unitId);
    }

    // ---------- 夹具 ----------

    private String readyPlayer() {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "自动续训", 1_700_000_000_000L, ""))
                .playerId();
        prepareBarracks(playerId);
        fieldHeroForCap(playerId, "hero_ssr_02");
        giveResources(playerId);
        // 夹具在满仓，后面的「余额 = 上限 − 成本」才是一个精确等式
        assertThat(resourceOf(playerId, "IRON"))
                .as("夹具必须把资源补到满仓").isEqualTo(capOf(playerId, "IRON"));
        return playerId;
    }

    /** 主城 3 级 + 军营 1 级 ⇒ T1 解锁（unlockBuilding=barracks，unlockBuildingLevel=1）。 */
    private void prepareBarracks(String playerId) {
        for (int i = 1; i < 3; i++) {
            buildAndFinish(playerId, "main_city", 3, 3);
        }
        buildAndFinish(playerId, "barracks", 1, 1);
    }

    private void buildAndFinish(String playerId, String configId, int x, int y) {
        giveResources(playerId);
        CityUpgradeResp upgrade = cityAppService.upgrade(playerId,
                new CityUpgradeReq(newRequestId(), configId, x, y));
        var city = cities.findByPlayerId(playerId).orElseThrow();
        long version = cities.versionOf(playerId);
        var b = city.building(upgrade.buildingId());
        b.restore(b.level(), b.gridX(), b.gridY(),
                com.ironoath.core.city.BuildingStatus.UPGRADING, 1L,
                0L, 1L, 1L, b.helpCount(), b.lastMovedAt(), b.lastFinishedAt());
        cities.save(playerId, city, version);
        cityAppService.collect(playerId, new CityCollectReq(newRequestId(), upgrade.buildingId()));
    }

    /** 上阵一名统率最高的武将：带兵上限必须是真的，否则补兵会先撞在上限上。 */
    private void fieldHeroForCap(String playerId, String heroId) {
        var heroCfg = configs.get(com.ironoath.config.cfg.HeroCfg.class, heroId);
        long need = configs.get(com.ironoath.config.cfg.HeroRarityCfg.class, heroCfg.rarity().name())
                .composeFragment();
        giveItems(playerId, "item_mat_hero_frag_" + heroCfg.rarity().name().toLowerCase(java.util.Locale.ROOT), need);
        heroAppService.compose(playerId, new HeroIdReq(newRequestId(), heroId));
        heroAppService.setLineup(playerId, new SetLineupReq(newRequestId(), 0, heroId, null, null));
        assertThat(troopCapOf(playerId)).as("上阵后带兵上限必须为正").isPositive();
    }

    private void giveItems(String playerId, String itemId, long count) {
        Inventory inv = inventories.findByPlayerId(playerId)
                .orElseGet(() -> Inventory.empty((int) configs.longParam("BAG_INITIAL_CAPACITY")));
        long added = inv.add(itemId, count, configs.get(com.ironoath.config.cfg.ItemCfg.class, itemId).stackMax());
        assertThat(added).as("夹具必须能放下 %s × %d", itemId, count).isEqualTo(count);
        if (inventories.findByPlayerId(playerId).isEmpty()) {
            assertThat(inventories.insertIfAbsent(playerId, inv)).isTrue();
        } else {
            inventories.save(playerId, inv, inventories.versionOf(playerId));
        }
    }

    /** 补到满仓。满仓之后产出被封顶丢弃，所以「余额 = 上限 − 成本」是精确等式。 */
    private void giveResources(String playerId) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        for (String id : configs.resourceIds()) {
            PlayerResourceState s = save.resource(id);
            save.putResource(id, new PlayerResourceState(s.cap(), s.cap(), s.protectedAmount(),
                    s.perHour(), s.lastSettle()));
        }
        players.save(save);
    }

    /** 把资源清到只剩一点点：模拟「资源刚在别处花掉了」。 */
    private void drainResources(String playerId) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        for (String id : configs.resourceIds()) {
            PlayerResourceState s = save.resource(id);
            long keep = "GOLD".equals(id) || "STAMINA".equals(id) ? s.current() : 1L;
            save.putResource(id, new PlayerResourceState(keep, s.cap(), s.protectedAmount(),
                    s.perHour(), s.lastSettle()));
        }
        players.save(save);
    }

    /** 初始兵力。{@code ArmyState#add} 就是生产里伤兵归队走的那条路。 */
    private void giveTroops(String playerId, long count) {
        ArmyState army = armyOf(playerId);
        long version = armies.versionOf(playerId);
        army.add(UNIT, count);
        armies.save(playerId, army, version);
    }

    /** 阵亡。{@code ArmyState#deduct} 就是 {@code StageAppService#applyLosses} 扣损失走的那条路。 */
    private void killTroops(String playerId, long count) {
        ArmyState army = armyOf(playerId);
        long version = armies.versionOf(playerId);
        army.deduct(UNIT, count);
        armies.save(playerId, army, version);
    }

    private ArmyState armyOf(String playerId) {
        return armies.findByPlayerId(playerId).orElseGet(() -> {
            armies.insertIfAbsent(playerId, new ArmyState());
            return armies.findByPlayerId(playerId).orElseThrow();
        });
    }

    private ArmyState.TrainingTask task(String playerId) {
        return armyOf(playerId).queue().get(UNIT);
    }

    private long troopCountOf(String playerId, String unitId) {
        return armyOf(playerId).countOf(unitId);
    }

    /**
     * 把这一批的完成时刻改到开始那一刻（= 已到点）。
     *
     * <p>与 {@code ArmyEndpointTest#rewindTraining} 同一手法：不塞 finishAt=1（那会早于开始时刻、
     * 被 {@code TrainingTask} 的校验拒掉），而是把完成时刻设成开始时刻本身，剩余时间自然为 0。
     */
    private void rewindTraining(String playerId) {
        ArmyState army = armyOf(playerId);
        long version = armies.versionOf(playerId);
        Map<String, ArmyState.TrainingTask> queue = new LinkedHashMap<>(army.queue());
        ArmyState.TrainingTask t = queue.get(UNIT);
        assertThat(t).as("兵种 %s 应当在训练队列里", UNIT).isNotNull();
        queue.put(UNIT, t.withFinishAt(t.startedAt()));
        army.restore(army.troops(), queue, army.wounded(), army.treatFinishAt(),
                army.treatTotalSeconds(), army.treatOriginalSeconds(), army.treatCost(),
                army.extraSlots());
        armies.save(playerId, army, version);
    }

    private long troopCapOf(String playerId) {
        return heroAppService.troopCap(playerId);
    }

    private long resourceOf(String playerId, String resource) {
        return players.findByPlayerId(playerId).orElseThrow().resource(resource).current();
    }

    private long capOf(String playerId, String resource) {
        return players.findByPlayerId(playerId).orElseThrow().resource(resource).cap();
    }

    private long ironCost(long count) {
        return configs.get(com.ironoath.config.cfg.UnitCfg.class, UNIT).trainCostIron() * count;
    }

    private long grainCost(long count) {
        return configs.get(com.ironoath.config.cfg.UnitCfg.class, UNIT).trainCostGrain() * count;
    }

    private long trainTimeSec() {
        return configs.get(com.ironoath.config.cfg.UnitCfg.class, UNIT).trainTimeSec();
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }
}
