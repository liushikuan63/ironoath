package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.BuildingCfg;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.config.cfg.ShopCfg;
import com.ironoath.config.cfg.TechCfg;
import com.ironoath.core.city.BuildingInstance;
import com.ironoath.core.city.BuildingStatus;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.player.PlayerTech;
import com.ironoath.core.quest.GoalType;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.web.dto.generated.ItemUseReq;
import com.ironoath.web.dto.generated.ItemUseResp;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.ResourceAmount;
import com.ironoath.web.dto.generated.QuestView;
import com.ironoath.web.dto.generated.ResourceType;
import com.ironoath.web.dto.generated.TechBlockReason;
import com.ironoath.web.dto.generated.TechCancelReq;
import com.ironoath.web.dto.generated.TechCancelResp;
import com.ironoath.web.dto.generated.TechListView;
import com.ironoath.web.dto.generated.TechResearchReq;
import com.ironoath.web.dto.generated.TechResearchResp;
import com.ironoath.web.dto.generated.TechSpeedUpReq;
import com.ironoath.web.dto.generated.TechSpeedUpResp;
import com.ironoath.web.dto.generated.TechView;
import com.ironoath.web.quest.QuestAppService;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.tech.TechAppService;

/**
 * 职责：个人科技四个动作（list / research / cancel / speedUp）的集成测试
 * （B20 块① 验收 1、2、6 的服务端半边 + 验收 8）。
 * 依赖：Spring Boot Test + test profile（内存存储，不需要 MongoDB / Redis）。
 *
 * <p><b>这里刻意断精确数而不是「变大了」</b>（B20 §一「断言密度」）：13 秒 / 17 秒 / 600 木 / 返还 360
 * 都是**从表里算出来的**数，改了 {@code curve.TECH_TIME} 或 {@code tech.json} 的任意一项，
 * 对应的断言就会红 —— 那正是验收 1 的「改表 ⇒ 时长变」。把它们写成"大于 0"就永远绿，
 * 而"永远绿的断言"是这个项目反复在防的东西。
 *
 * <p><b>加速这一族多三条判据</b>（验收 8）：道具<b>只在真的加速成功时才扣</b>（校验失败必须还剩原数）、
 * <b>减到点就在同一次调用里把等级记进账本</b>（不留给下一次读取，玩家花道具买的就是"现在就完成"）、
 * 以及<b>两个入口同一条口径</b>（{@code /tech/speedUp} 与背包里的 {@code /item/use} 共用一段核心，
 * 也共用同一把幂等键）。这三条都是玩家花金币买的东西，
 * 断"少扣了一个道具"和"没多扣"必须靠同一组用例。
 *
 * <p><b>时间怎么办</b>：test profile 用的是系统时钟，不能拨。所以"研究已完成"这类场景
 * 一律把完成时刻<b>写成过去</b>再走一次读取（{@code PlayerTech.settled} 是惰性的），
 * 与 {@code CityEndpointTest} 用 {@code restore(...)} 造升级中/已完成态同一条路子。
 */
@SpringBootTest
@ActiveProfiles("test")
class TechEndpointTest {

    private static final String WOOD_TECH = "tech_agri_wood";
    private static final String TRAIN_TECH = "tech_mil_train";
    /** 屯田令：木 600 / 石 200 起手，学院 1 级前置（tech.json）。 */
    private static final long WOOD_COST_L1 = 600L;
    private static final long STONE_COST_L1 = 200L;
    /** TR(1)=13s、TR(2)=13×1.28=16.64→17s、TR(3)=21.30→21s（基数由 #152 量出）。 */
    private static final long SECONDS_L1 = 13L;
    private static final long SECONDS_L2 = 17L;
    private static final long SECONDS_L3 = 21L;
    /** city_rule_cancel_refund_ratio = 0.60。 */
    private static final long REFUND_WOOD_L1 = 360L;
    private static final long REFUND_STONE_L1 = 120L;
    /** 一小时研究令（item.json 的 {@code effectValue=3600}），#46 下架、B20 验收 8 重上架的那一件。 */
    private static final String RESEARCH_TOKEN = "item_speedup_research_1h";
    private static final long TOKEN_SECONDS = 3600L;
    /** 建造令：拿它当「类型对但效果错」的探针（秒数同形，接错域不会报错只会少东西）。 */
    private static final String BUILD_TOKEN = "item_speedup_build_1h";
    private static final String RESEARCH_TOKEN_SHELF_ROW = "shop_speedup_research_1h";

    @Autowired
    private TechAppService techAppService;
    @Autowired
    private CityAppService cityAppService;
    @Autowired
    private PlayerInitService playerInitService;
    @Autowired
    private PlayerRepository players;
    @Autowired
    private CityRepository cities;
    @Autowired
    private QuestAppService quests;
    @Autowired
    private ConfigRegistry configs;
    /** 背包写入的唯一入口：加速用例要先发道具、再核「扣了几个 / 有没有白扣」。 */
    @Autowired
    private RewardPorts.Bag bag;
    /** 加速道具的第二个入口（{@code /item/use}）：与 {@code /tech/speedUp} 必须同口径、同一条幂等纪律。 */
    @Autowired
    private com.ironoath.web.service.BagAppService bagAppService;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
    }

    // ---------- 夹具 ----------

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "科技测试",
                1_700_000_000_000L, "")).playerId();
    }

    private static String requestId() {
        return "req-" + UUID.randomUUID();
    }

    /**
     * 把某种资源摆成一个确定的数。<b>上限一并钉成同一个数</b>：
     * 任何一次读取都会先做惰性结算（产率 × 距上次结算的秒数，向上限取整），
     * 而夹具里的 createdAt 与真实时钟差着几年 —— 不锁上限的话每次结算都会把它灌满，
     * 「扣了 600」这种断言就会取决于跑测试的时刻。
     */
    private void setResource(String playerId, String resource, long amount) {
        PlayerSave player = players.findByPlayerId(playerId).orElseThrow();
        PlayerResourceState s = player.resource(resource);
        player.putResource(resource, new PlayerResourceState(amount, amount,
                Math.min(s.protectedAmount(), amount), s.perHour(), s.lastSettle()));
        players.save(player);
    }

    /** 学院建到指定等级（直接登记建筑实例，不真去点城建升级 —— 那是被测系统的上游，不是它自己）。 */
    private void giveAcademy(String playerId, int level) {
        cityAppService.list(playerId);   // 城建存档是首次访问时才建的：先让城建自己建好
        CityState city = cities.findByPlayerId(playerId).orElseThrow();
        long version = cities.versionOf(playerId);
        BuildingInstance academy = city.findByConfigId("academy");
        if (academy == null) {
            city.restoreBuilding(new BuildingInstance("b-academy-fixture", "academy", level, 2, 2));
        } else {
            academy.restore(level, academy.gridX(), academy.gridY(), BuildingStatus.IDLE,
                    null, 0L, 0L, 0L, academy.helpCount(), academy.lastMovedAt(), academy.lastFinishedAt());
        }
        cities.save(playerId, city, version);
    }

    /** 直接摆科技账本（造「已经研究到 N 级」或「队列里有一项且已完成」的现场）。 */
    private void putTech(String playerId, PlayerTech tech) {
        PlayerSave player = players.findByPlayerId(playerId).orElseThrow();
        player.setTech(tech);
        players.save(player);
    }

    private PlayerTech techOf(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow().tech();
    }

    private TechResearchResp research(String playerId, String techId) {
        return techAppService.research(playerId, new TechResearchReq(requestId(), techId));
    }

    private TechView row(TechListView list, String techId) {
        return list.techs().stream().filter(v -> v.techId().equals(techId)).findFirst().orElseThrow();
    }

    private long balance(String playerId, ResourceType resource) {
        return players.findByPlayerId(playerId).orElseThrow()
                .resource(resource.name()).current();
    }

    /**
     * 摆一个「正在研究、还剩 {@code remainingSeconds} 秒」的现场。
     *
     * <p>不能靠真等（13 秒一步的研究要测「减一半」得等到天黑），也不能拨系统时钟
     * （test profile 用真实时钟，全项目没有测试时钟注入点）—— 与 {@code CityEndpointTest}
     * 造升级中/已完成态同一条路子：直接把完成时刻写到要的位置。
     */
    private void putResearching(String playerId, String techId, long totalSeconds, long remainingSeconds) {
        long now = System.currentTimeMillis();
        putTech(playerId, new PlayerTech(Map.of(), techId, now + remainingSeconds * 1000L,
                now - (totalSeconds - remainingSeconds) * 1000L, totalSeconds));
    }

    private TechSpeedUpResp speedUp(String playerId, String itemId, long count) {
        return techAppService.speedUp(playerId, new TechSpeedUpReq(requestId(), itemId, count));
    }

    // ---------- 表本身 ----------

    @Test
    @DisplayName("前置建筑 id 认错家的话所有科技都研究不了：academy 必须真的在建筑表里")
    void academyMustExistInBuildingTable() {
        BuildingCfg academy = configs.get(BuildingCfg.class, TechAppService.ACADEMY_BUILDING_ID);
        assertThat(academy.name())
                .as("tech.json 的 requireAcademyLevel 指的是这一行；它被改名或删掉时 academyLevel 会恒为 0，"
                        + "于是每一条研究都被 TECH_ACADEMY_REQUIRED 挡回来，而玩家看不出为什么")
                .isNotBlank();
        assertThat(academy.type()).isEqualTo(BuildingCfg.Type.SCIENCE);
    }

    // ---------- 列表 ----------

    @Test
    @DisplayName("整棵树来自表：11 行、等级全 0、没建学院时全部标 ACADEMY_LOW")
    void listShowsEveryRowFromTheTable() {
        String playerId = newPlayer();
        TechListView list = techAppService.list(playerId);

        assertThat(list.techs()).hasSize(configs.all(TechCfg.class).size()).hasSize(11);
        assertThat(list.academyLevel()).as("新号只有主城").isZero();
        assertThat(list.serverNow()).isPositive();
        assertThat(row(list, WOOD_TECH).level()).isZero();
        assertThat(row(list, WOOD_TECH).name())
                .as("名字从表里来，客户端不硬编码科技名")
                .isEqualTo(configs.get(TechCfg.class, WOOD_TECH).name());
        assertThat(list.techs()).allSatisfy(view -> {
            assertThat(view.canResearch()).isFalse();
            assertThat(view.blockedReason()).isEqualTo(TechBlockReason.ACADEMY_LOW);
        });
    }

    @Test
    @DisplayName("下一级要什么，是现算的：13 秒 / 600 木 / 200 石，且只列该行为正的资源")
    void nextStepIsComputedFromTheTables() {
        String playerId = newPlayer();
        giveAcademy(playerId, 8);

        TechView wood = row(techAppService.list(playerId), WOOD_TECH);
        assertThat(wood.nextTimeSec()).as("TR(1) = curve.TECH_TIME.base").isEqualTo(SECONDS_L1);
        assertThat(wood.nextCost()).containsExactlyInAnyOrder(
                new ResourceAmount(ResourceType.WOOD, WOOD_COST_L1),
                new ResourceAmount(ResourceType.STONE, STONE_COST_L1));
        assertThat(wood.researching()).isFalse();

        putTech(playerId, new PlayerTech(Map.of(WOOD_TECH, 1), null, null, 0L, 0L));
        TechView second = row(techAppService.list(playerId), WOOD_TECH);
        assertThat(second.level()).isEqualTo(1);
        assertThat(second.nextTimeSec())
                .as("升到 2 级取 TR(2)=13×1.28=16.64 → 17 秒：时长随等级变，说明它来自曲线而不是常量")
                .isEqualTo(SECONDS_L2);
        assertThat(second.nextCost())
                .as("消耗按该行的 costCurve 递增：600×1.22=732")
                .contains(new ResourceAmount(ResourceType.WOOD, 732L));
    }

    @Test
    @DisplayName("满级的行：不报时长、不报消耗，理由是 MAX_LEVEL（不是「队列被占」）")
    void maxedRowSaysMaxed() {
        String playerId = newPlayer();
        giveAcademy(playerId, 8);
        TechCfg cfg = configs.get(TechCfg.class, WOOD_TECH);
        putTech(playerId, new PlayerTech(Map.of(WOOD_TECH, (int) cfg.maxLevel()), null, null, 0L, 0L));

        TechView view = row(techAppService.list(playerId), WOOD_TECH);
        assertThat(view.blockedReason()).isEqualTo(TechBlockReason.MAX_LEVEL);
        assertThat(view.canResearch()).isFalse();
        assertThat(view.nextTimeSec()).isZero();
        assertThat(view.nextCost()).isEmpty();

        assertThatThrownBy(() -> research(playerId, WOOD_TECH))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_LEVEL_MAX);
    }

    @Test
    @DisplayName("钱不够时标 RESOURCE_LOW：按钮不该在玩家付不起的时候还亮着")
    void unaffordableRowSaysResourceLow() {
        String playerId = newPlayer();
        giveAcademy(playerId, 1);
        setResource(playerId, ResourceIds.WOOD, WOOD_COST_L1);
        setResource(playerId, ResourceIds.STONE, STONE_COST_L1 - 1L);

        TechView view = row(techAppService.list(playerId), WOOD_TECH);
        assertThat(view.blockedReason()).isEqualTo(TechBlockReason.RESOURCE_LOW);
        assertThat(view.canResearch()).isFalse();
    }

    // ---------- 前置与队列 ----------

    @Test
    @DisplayName("学院等级不够就研究不了，且错误里带「需要几级 / 当前几级」")
    void academyPrerequisiteIsCheckedAgainstTheRealBuildingLevel() {
        String playerId = newPlayer();
        giveAcademy(playerId, 1);
        setResource(playerId, ResourceIds.WOOD, 9_999L);
        setResource(playerId, ResourceIds.STONE, 9_999L);

        assertThatThrownBy(() -> research(playerId, TRAIN_TECH))
                .as("募兵令要学院 8 级，这里只有 1 级")
                .isInstanceOf(BizException.class)
                .hasMessageContaining("需要学院 8 级")
                .hasMessageContaining("当前 1 级");
    }

    @Test
    @DisplayName("一次一队列：研究中的第二项被拒，且列表里能看见在研究哪一行")
    void onlyOneResearchAtATime() {
        String playerId = newPlayer();
        giveAcademy(playerId, 1);
        setResource(playerId, ResourceIds.WOOD, 10_000L);
        setResource(playerId, ResourceIds.STONE, 10_000L);

        long beforeCall = System.currentTimeMillis();
        TechResearchResp started = research(playerId, WOOD_TECH);
        long afterCall = System.currentTimeMillis();
        assertThat(started.level()).isEqualTo(1);
        assertThat(started.timeSec()).isEqualTo(SECONDS_L1);
        assertThat(started.finishAt())
                .as("完成时刻 = 开始 + 13 秒：区间两头都是真实时钟，不取决于任何写死的数")
                .isBetween(beforeCall + SECONDS_L1 * 1000L, afterCall + SECONDS_L1 * 1000L);

        assertThatThrownBy(() -> research(playerId, "tech_agri_stone"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_QUEUE_BUSY);

        TechListView list = techAppService.list(playerId);
        assertThat(list.queue().techId()).isEqualTo(WOOD_TECH);
        assertThat(list.queue().totalSeconds()).isEqualTo(SECONDS_L1);
        assertThat(list.queue().remainingSeconds())
                .as("刚开完局：剩余等于总时长，绝不为负")
                .isEqualTo(SECONDS_L1);
        assertThat(row(list, WOOD_TECH).researching()).isTrue();
        assertThat(row(list, WOOD_TECH).blockedReason()).isEqualTo(TechBlockReason.QUEUE_BUSY);
        assertThat(row(list, "tech_agri_stone").blockedReason()).isEqualTo(TechBlockReason.QUEUE_BUSY);
    }

    @Test
    @DisplayName("requestId 幂等：同一个键重投第二次被拒，钱只扣一次")
    void researchIsIdempotentOnRequestId() {
        String playerId = newPlayer();
        giveAcademy(playerId, 1);
        setResource(playerId, ResourceIds.WOOD, WOOD_COST_L1 * 3L);
        setResource(playerId, ResourceIds.STONE, STONE_COST_L1 * 3L);
        long woodBefore = balance(playerId, ResourceType.WOOD);

        String key = requestId();
        techAppService.research(playerId, new TechResearchReq(key, WOOD_TECH));
        assertThat(balance(playerId, ResourceType.WOOD)).isEqualTo(woodBefore - WOOD_COST_L1);

        assertThatThrownBy(() -> techAppService.research(playerId, new TechResearchReq(key, WOOD_TECH)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED);
        assertThat(balance(playerId, ResourceType.WOOD))
                .as("重投不该再扣一次")
                .isEqualTo(woodBefore - WOOD_COST_L1);
        assertThat(techOf(playerId).levelOf(WOOD_TECH)).as("更不该把同一级记两次").isZero();
    }

    @Test
    @DisplayName("缺 requestId 直接拒：宁可响，也不给一条没有幂等键的写路径")
    void missingRequestIdIsRejected() {
        String playerId = newPlayer();
        giveAcademy(playerId, 1);
        assertThatThrownBy(() -> techAppService.research(playerId, new TechResearchReq("  ", WOOD_TECH)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.REQUEST_ID_MISSING);
    }

    @Test
    @DisplayName("表里没有的 id：参数错，不是 500，也不是「隐藏科技」")
    void unknownTechIdIsAParameterError() {
        String playerId = newPlayer();
        giveAcademy(playerId, 1);
        assertThatThrownBy(() -> research(playerId, "tech_does_not_exist"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    // ---------- 结算与取消 ----------

    @Test
    @DisplayName("到点靠读结算：读一次列表就进账本，再读十次也不会多长一级")
    void completionSettlesOnReadAndOnlyOnce() {
        String playerId = newPlayer();
        giveAcademy(playerId, 1);
        long past = System.currentTimeMillis() - 60_000L;
        putTech(playerId, new PlayerTech(Map.of(), WOOD_TECH, past, past - SECONDS_L1 * 1000L, SECONDS_L1));

        TechListView first = techAppService.list(playerId);
        assertThat(first.queue().techId()).as("结算之后队列腾空").isNull();
        assertThat(first.queue().remainingSeconds()).isZero();
        assertThat(row(first, WOOD_TECH).level()).isEqualTo(1);
        assertThat(techOf(playerId).levelOf(WOOD_TECH)).as("结算是落库的，不是只在响应里").isEqualTo(1);

        for (int i = 0; i < 3; i++) {
            assertThat(row(techAppService.list(playerId), WOOD_TECH).level())
                    .as("第 %d 次重复读取不该再加一级", i + 2)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("取消：按城建同一比例返还（60%）、已研究的等级一位不掉")
    void cancelRefundsWithTheCityRatioAndKeepsTheLedger() {
        String playerId = newPlayer();
        giveAcademy(playerId, 3);
        setResource(playerId, ResourceIds.WOOD, WOOD_COST_L1 + 40L);
        setResource(playerId, ResourceIds.STONE, STONE_COST_L1 + 40L);
        long woodBefore = balance(playerId, ResourceType.WOOD);
        long stoneBefore = balance(playerId, ResourceType.STONE);

        research(playerId, WOOD_TECH);
        assertThat(balance(playerId, ResourceType.WOOD)).isEqualTo(woodBefore - WOOD_COST_L1);

        TechCancelResp cancelled = techAppService.cancel(playerId, new TechCancelReq(requestId()));
        assertThat(cancelled.techId()).isEqualTo(WOOD_TECH);
        assertThat(cancelled.refund()).containsExactlyInAnyOrder(
                new ResourceAmount(ResourceType.WOOD, REFUND_WOOD_L1),
                new ResourceAmount(ResourceType.STONE, REFUND_STONE_L1));
        assertThat(balance(playerId, ResourceType.WOOD))
                .as("返还 60%（比例与城建一致，不另配一份）")
                .isEqualTo(woodBefore - WOOD_COST_L1 + REFUND_WOOD_L1);
        assertThat(balance(playerId, ResourceType.STONE)).isEqualTo(stoneBefore - STONE_COST_L1 + REFUND_STONE_L1);
        assertThat(techOf(playerId).isResearching()).isFalse();
        assertThat(techOf(playerId).levels()).as("取消的那一级从没进过账本").isEmpty();
    }

    @Test
    @DisplayName("队列空着时取消是明确的失败，不是原地返回一个空返还")
    void cancelWithoutResearchFails() {
        String playerId = newPlayer();
        assertThatThrownBy(() -> techAppService.cancel(playerId, new TechCancelReq(requestId())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_NOT_RESEARCHING);
    }

    // ---------- 研究加速（验收 8：#46 的闭环） ----------

    @Test
    @DisplayName("重上架的货架行指的就是本套件花掉的那张令，且定价与限购按 #46 删掉前那一档")
    void relistedShelfRowPointsAtTheResearchToken() {
        ShopCfg shelf = configs.all(ShopCfg.class).stream()
                .filter(row -> RESEARCH_TOKEN_SHELF_ROW.equals(row.id()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "货架行不见了：#46 的下架理由（没有任何东西可加速）已被验收 8 消掉，重上架是它的闭环"));
        assertThat(shelf.itemId()).isEqualTo(RESEARCH_TOKEN);
        assertThat(shelf.priceCurrency()).isEqualTo(ShopCfg.PriceCurrency.GOLD);
        assertThat(shelf.price())
                .as("50 金 = 这张令自己的回收价，买—卖同价才不会变成套利；三张一小时令里研究最贵（40/45/50）")
                .isEqualTo(50L);
        assertThat(shelf.limitCount()).isEqualTo(20L);
        assertThat(shelf.requireMainLevel()).as("加速道具是付费第一级台阶，不该卡等级").isZero();

        assertThat(configs.get(ItemCfg.class, RESEARCH_TOKEN).effectValue())
                .as("本套件按 TOKEN_SECONDS 断每一条减时长，这个数必须就是表里的 effectValue")
                .isEqualTo(TOKEN_SECONDS);
    }

    @Test
    @DisplayName("一张令减 3600 秒：完成时刻与总时长一起往前挪，道具正好扣一个")
    void speedUpSubtractsExactlyOneTokenWorthOfSeconds() {
        String playerId = newPlayer();
        putResearching(playerId, WOOD_TECH, 7200L, 7200L);
        bag.add(playerId, RESEARCH_TOKEN, 3L);
        long finishAtBefore = techOf(playerId).finishAt();

        TechSpeedUpResp resp = speedUp(playerId, RESEARCH_TOKEN, 1L);
        assertThat(resp.techId()).isEqualTo(WOOD_TECH);
        assertThat(resp.reducedSeconds()).isEqualTo(TOKEN_SECONDS);
        assertThat(resp.remainingSeconds()).isEqualTo(7200L - TOKEN_SECONDS);
        assertThat(resp.finished()).as("还剩 3600 秒，不该完成").isFalse();

        PlayerTech after = techOf(playerId);
        assertThat(after.finishAt()).as("完成时刻精确往前挪一小时")
                .isEqualTo(finishAtBefore - TOKEN_SECONDS * 1000L);
        assertThat(after.totalSeconds()).as("总时长一并缩短：进度条靠 startedAt + totalSeconds 画，"
                + "只挪 finishAt 会让玩家看到一条突然多出来的空档").isEqualTo(7200L - TOKEN_SECONDS);
        assertThat(after.researchingId()).isEqualTo(WOOD_TECH);
        assertThat(after.levelOf(WOOD_TECH)).as("没到点，等级一位都不涨").isZero();
        assertThat(bag.countOf(playerId, RESEARCH_TOKEN)).isEqualTo(2L);
    }

    @Test
    @DisplayName("一张 8 小时当量的令去加速只剩 10 秒的研究：减 10 秒、就地结算一级、道具照扣")
    void oversizedTokenSettlesTheLevelInsideTheSameCall() {
        String playerId = newPlayer();
        putResearching(playerId, WOOD_TECH, 20L, 10L);
        bag.add(playerId, RESEARCH_TOKEN, 1L);

        TechSpeedUpResp resp = speedUp(playerId, RESEARCH_TOKEN, 1L);
        assertThat(resp.reducedSeconds())
                .as("报实际提前量而不是名义值：报 3600 就等于让玩家看见一次凭空的 3590 秒损失")
                .isEqualTo(10L);
        assertThat(resp.remainingSeconds()).isZero();
        assertThat(resp.finished()).isTrue();

        PlayerTech after = techOf(playerId);
        assertThat(after.isResearching()).as("完成即腾空队列，不等下一次读取").isFalse();
        assertThat(after.levelOf(WOOD_TECH)).as("等级在同一把锁里进账本").isEqualTo(1);
        assertThat(bag.countOf(playerId, RESEARCH_TOKEN)).as("超出的部分不找零，道具按张扣").isZero();
    }

    @Test
    @DisplayName("建造令加速不了研究：效果类型不同，接错域不会报错只会白扣东西")
    void buildTokenCannotSpeedUpResearch() {
        String playerId = newPlayer();
        putResearching(playerId, WOOD_TECH, 7200L, 7200L);
        bag.add(playerId, BUILD_TOKEN, 1L);

        assertThatThrownBy(() -> speedUp(playerId, BUILD_TOKEN, 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("REDUCE_BUILD_SECONDS")
                .hasMessageContaining("不能加速研究")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(bag.countOf(playerId, BUILD_TOKEN)).as("被拒绝时不能扣道具").isEqualTo(1L);
        assertThat(techOf(playerId).totalSeconds()).as("队列状态一位没动").isEqualTo(7200L);
    }

    @Test
    @DisplayName("非加速道具走到 /tech/speedUp 是 ITEM_CANNOT_USE，不是 500")
    void resourceItemCannotSpeedUpResearch() {
        String playerId = newPlayer();
        putResearching(playerId, WOOD_TECH, 7200L, 7200L);
        bag.add(playerId, "item_res_wood_10k", 1L);

        assertThatThrownBy(() -> speedUp(playerId, "item_res_wood_10k", 1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ITEM_CANNOT_USE);
        assertThat(bag.countOf(playerId, "item_res_wood_10k")).isEqualTo(1L);
    }

    @Test
    @DisplayName("队列空着时加速：明确失败，且不因为「先扣道具再发现没东西可加速」而白扣")
    void speedUpWithoutResearchFails() {
        String playerId = newPlayer();
        bag.add(playerId, RESEARCH_TOKEN, 1L);

        assertThatThrownBy(() -> speedUp(playerId, RESEARCH_TOKEN, 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("队列空着")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_NOT_RESEARCHING);
        assertThat(bag.countOf(playerId, RESEARCH_TOKEN)).isEqualTo(1L);
    }

    @Test
    @DisplayName("手里没有令：ITEM_NOT_ENOUGH，队列一秒没减")
    void speedUpWithoutItemsFails() {
        String playerId = newPlayer();
        putResearching(playerId, WOOD_TECH, 7200L, 7200L);

        assertThatThrownBy(() -> speedUp(playerId, RESEARCH_TOKEN, 1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ITEM_NOT_ENOUGH);
        assertThat(techOf(playerId).totalSeconds()).isEqualTo(7200L);
    }

    @Test
    @DisplayName("requestId 幂等：同一个键重投只减一次时长、只扣一张令")
    void speedUpIsIdempotentOnRequestId() {
        String playerId = newPlayer();
        putResearching(playerId, WOOD_TECH, 7200L, 7200L);
        bag.add(playerId, RESEARCH_TOKEN, 2L);

        String key = requestId();
        techAppService.speedUp(playerId, new TechSpeedUpReq(key, RESEARCH_TOKEN, 1L));
        assertThat(bag.countOf(playerId, RESEARCH_TOKEN)).isEqualTo(1L);
        long totalAfterFirst = techOf(playerId).totalSeconds();

        assertThatThrownBy(() -> techAppService.speedUp(playerId, new TechSpeedUpReq(key, RESEARCH_TOKEN, 1L)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED);
        assertThat(bag.countOf(playerId, RESEARCH_TOKEN)).as("弱网重投不该白丢一张付费道具").isEqualTo(1L);
        assertThat(techOf(playerId).totalSeconds())
                .as("时长也不该被减两次").isEqualTo(totalAfterFirst);
    }

    @Test
    @DisplayName("从背包里用研究令：同一个入口的两条性质 —— 真减到研究队列上、且不因弱网重投白扣一张")
    void tokenSpentThroughTheBackpackRouteHitsTheQueueAndIsIdempotent() {
        String playerId = newPlayer();
        putResearching(playerId, WOOD_TECH, 7200L, 7200L);
        bag.add(playerId, RESEARCH_TOKEN, 2L);

        String key = requestId();
        ItemUseResp used = bagAppService.useItem(playerId, new ItemUseReq(key, RESEARCH_TOKEN, 1L, null));
        assertThat(used.reducedSeconds()).as("背包那条路必须与 /tech/speedUp 同口径").isEqualTo(TOKEN_SECONDS);
        assertThat(bag.countOf(playerId, RESEARCH_TOKEN)).isEqualTo(1L);
        long totalAfterFirst = techOf(playerId).totalSeconds();
        assertThat(totalAfterFirst).isEqualTo(7200L - TOKEN_SECONDS);

        assertThatThrownBy(() -> bagAppService.useItem(playerId, new ItemUseReq(key, RESEARCH_TOKEN, 1L, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED);
        assertThat(bag.countOf(playerId, RESEARCH_TOKEN)).as("重投不该白扣一张付费道具").isEqualTo(1L);
        assertThat(techOf(playerId).totalSeconds()).as("也不该把时长减两次").isEqualTo(totalAfterFirst);
    }

    @Test
    @DisplayName("count 为 0 是参数错：不给一条「不扣道具却走到加速核心」的路")
    void speedUpWithNonPositiveCountFails() {
        String playerId = newPlayer();
        putResearching(playerId, WOOD_TECH, 7200L, 7200L);
        bag.add(playerId, RESEARCH_TOKEN, 1L);

        assertThatThrownBy(() -> speedUp(playerId, RESEARCH_TOKEN, 0L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(bag.countOf(playerId, RESEARCH_TOKEN)).isEqualTo(1L);
    }

    // ---------- 主线断链的那一环 ----------

    @Test
    @DisplayName("RESEARCH_TECH 有数据源了；但 quest_main_08 仍锁着，进度按设计保持 0（验收 6 卡在前置链，不卡在科技）")
    void statePullIsWiredWhileTheLockedQuestHoldsAtZero() {
        String playerId = newPlayer();
        giveAcademy(playerId, 1);
        long past = System.currentTimeMillis() - 60_000L;
        putTech(playerId, new PlayerTech(Map.of(), WOOD_TECH, past, past - SECONDS_L1 * 1000L, SECONDS_L1));
        techAppService.list(playerId);

        assertThat(QuestAppService.STATE_TYPES_WITH_SOURCE)
                .as("状态型目标的数据源登记表：科技这一位在 B20 块① 接上，缺了它那条任务的进度会永远是 0")
                .contains(GoalType.RESEARCH_TECH);
        assertThat(techOf(playerId).levelOf(WOOD_TECH)).isEqualTo(1);

        QuestView quest = quests.list(playerId).quests().stream()
                .filter(q -> "quest_main_08".equals(q.questId()))
                .findFirst().orElseThrow();
        assertThat(quest.goalTarget())
                .as("任务表指的就是这一行科技（goalTarget 与科技 id 同源）")
                .isEqualTo(WOOD_TECH);
        assertThat(quest.locked()).as("preQuest 链还没走完（01~07 未领）").isTrue();
        assertThat(quest.current())
                .as("QuestProgress.onEvent 对未解锁的任务直接跳过（不记进度）—— 这是既有设计，不是本批的缺陷。"
                                + "所以 B20 验收 6「主线 quest_main_08 端到端可完成」仍挂在 01~07 那条前置链上，"
                                + "而那条链的阻塞点是 #152 之前一直记着的「七步要 1 万粮 vs 新号 400/小时」")
                .isZero();
    }
}
