package com.ironoath.web;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.BagItem;
import com.ironoath.web.dto.generated.BagListResp;
import com.ironoath.web.dto.generated.CityUpgradeReq;
import com.ironoath.web.dto.generated.CityUpgradeResp;
import com.ironoath.web.dto.generated.ItemUseReq;
import com.ironoath.web.dto.generated.ItemUseResp;
import com.ironoath.web.dto.generated.OpenBatchReq;
import com.ironoath.web.dto.generated.OpenBatchResp;
import com.ironoath.web.dto.generated.OutputBreak;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.ResourceDetail;
import com.ironoath.web.dto.generated.ResourceDetailResp;
import com.ironoath.web.dto.generated.RewardItemView;
import com.ironoath.web.reward.ServerSeedSource;
import com.ironoath.web.service.BagAppService;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.ResourceAppService;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryInventoryStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：B04 四个端点（/bag/list、/item/use、/item/openBatch、/resource/detail）的集成测试。
 * 依赖：Spring Boot Test，test profile（内存存储 + JVM 内锁，不需要 MongoDB / Redis）。
 *
 * <p>覆盖 B04 验收 2 / 3 / 4 / 5 / 9 / 10 与 §3 的排序分页规则。
 * 验收 1 / 6 / 7 / 8 分别在 ResourceDetailFullTest（本文件末尾）、
 * game-core 的 ResourceProtectionTest、RewardGrantorTest 与客户端飘字队列测试里。
 *
 * <p><b>开箱种子被替换成固定值</b>：生产的种子源是 SecureRandom（客户端无法预测），
 * 但验收 4 要求「同 seed 两次结果完全一致」，必须能指定种子才测得了。
 * 这里用 {@code @Primary} 的测试种子源覆盖生产 bean，不改动任何生产代码路径。
 */
@SpringBootTest
@ActiveProfiles("test")
class BagEndpointTest {

    /** 测试用固定种子。生产环境由 SecureRandom 提供。 */
    private static final AtomicLong SEED = new AtomicLong(20260906L);

    @TestConfiguration
    static class FixedSeedConfig {
        @Bean
        @Primary
        ServerSeedSource testServerSeedSource() {
            return SEED::get;
        }
    }

    @Autowired
    private BagAppService bagAppService;

    @Autowired
    private ResourceAppService resourceAppService;

    @Autowired
    private CityAppService cityAppService;

    @Autowired
    private PlayerInitService playerInitService;

    @Autowired
    private ConfigRegistry configs;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private CityRepository cities;

    @Autowired
    private InventoryRepository inventories;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
        ((InMemoryInventoryStore) inventories).clear();
        SEED.set(20260906L);
    }

    // ---------- B04 §3：背包排序与分页 ----------

    @Test
    @DisplayName("背包按「稀有度 > 类型 > 数量」排序，sortKey 由服务端算好且严格升序")
    void bagListIsSortedByRarityThenTypeThenCount() {
        String playerId = newPlayer();
        giveItems(playerId,
                "item_mat_hero_frag_ssr", 5L,     // SSR / MATERIAL
                "item_buff_peace_24h", 2L,        // SR  / BUFF
                "item_chest_resource", 3L,        // SR  / CHEST
                "item_res_wood_10k", 10L,         // N   / RESOURCE
                "item_speedup_build_5m", 100L);   // N   / SPEEDUP

        BagListResp resp = bagAppService.list(playerId, BagAppService.PAGE_ALL);
        assertThat(resp.items()).hasSize(5);

        List<String> order = resp.items().stream().map(BagItem::itemId).toList();
        assertThat(order).as("SSR 最先；同稀有度按类型声明顺序 CHEST(2) 在 BUFF(4) 前；"
                        + "N 档里 SPEEDUP(0) 在 RESOURCE(1) 前")
                .containsExactly("item_mat_hero_frag_ssr",
                        "item_chest_resource", "item_buff_peace_24h",
                        "item_speedup_build_5m", "item_res_wood_10k");

        // sortKey 必须严格升序：客户端只按它排，自己不再算权重
        long previous = Long.MIN_VALUE;
        for (BagItem item : resp.items()) {
            assertThat(item.sortKey()).as("%s 的 sortKey 必须大于前一项", item.itemId())
                    .isGreaterThan(previous);
            previous = item.sortKey();
        }
        // 数量多者靠前：同稀有度同类型时数量段决定顺序
        assertThat(BagAppService.sortKey(
                com.ironoath.web.dto.generated.ItemRarity.N, ItemCfg.Type.RESOURCE, 10L))
                .as("数量多的排前面 ⇒ sortKey 更小")
                .isLessThan(BagAppService.sortKey(
                        com.ironoath.web.dto.generated.ItemRarity.N, ItemCfg.Type.RESOURCE, 3L));
    }

    @Test
    @DisplayName("背包长按详情所需的来源与稀有度都随列表下发（B04 §3）")
    void bagItemCarriesRarityAndSource() {
        String playerId = newPlayer();
        giveItems(playerId, "item_chest_resource", 3L);

        BagItem item = bagAppService.list(playerId, BagAppService.PAGE_ALL).items().get(0);
        assertThat(item.name()).isEqualTo("军资补给箱");
        assertThat(item.rarity().name()).isEqualTo("SR");
        assertThat(item.obtainFrom()).as("来源必须非空，否则「长按看哪里能刷」这个转化设计就落空了")
                .isNotBlank();
        assertThat(item.stackMax()).isEqualTo(configs.get(ItemCfg.class, "item_chest_resource").stackMax());
        assertThat(item.count()).isEqualTo(3L);
    }

    @Test
    @DisplayName("判别字段随行下发：三本经验书是 MATERIAL，只靠 type 分不出来（V03-d）")
    void bagItemCarriesEffectKindSoClientsCanTellExpBooksApart() {
        String playerId = newPlayer();
        giveItems(playerId, "item_hero_exp_s", 2L, "item_hero_skillbook_main", 5L);

        // 两行**同 type**（都是 MATERIAL）—— 这正是客户端筛不出"能喂武将的道具"的原因；
        // 判据落在 effectKind 上：经验书是 GRANT_HERO_EXP，另一件材料不是
        assertThat(bagAppService.list(playerId, BagAppService.PAGE_ALL).items())
                .filteredOn(i -> i.itemId().equals("item_hero_exp_s"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.type()).isEqualTo("MATERIAL");
                    assertThat(i.effectKind()).as("客户端按这一列筛升级弹层的候选")
                            .isEqualTo(ItemCfg.EffectKind.GRANT_HERO_EXP.name());
                });
        assertThat(bagAppService.list(playerId, BagAppService.PAGE_ALL).items())
                .filteredOn(i -> i.itemId().equals("item_hero_skillbook_main"))
                .singleElement()
                .satisfies(i -> assertThat(i.effectKind()).isNotEqualTo("GRANT_HERO_EXP"));
        // 整表都带这一列（没有 null 行）——列可空的话客户端到处判空
        assertThat(configs.all(ItemCfg.class))
                // 空表会让这句恒真：先钉住"表里确实有行"（#342 同族）
                .as("道具表必须加载到行，否则逐行断言什么都没说")
                .isNotEmpty()
                .allSatisfy(cfg -> assertThat(cfg.effectKind()).as(cfg.id()).isNotNull());
    }

    @Test
    @DisplayName("两本技能书在 type 与 effectKind 两列下完全同型，只有 effectTarget 分得开（V03-d 最后一条）")
    void bagItemCarriesEffectTargetSoSkillBooksKnowTheirSlot() {
        String playerId = newPlayer();
        giveItems(playerId, "item_hero_skillbook_main", 3L, "item_hero_skillbook_sub", 4L,
                "item_hero_exp_s", 1L);

        var items = bagAppService.list(playerId, BagAppService.PAGE_ALL).items();
        // 三行**同 type 且两两同 effectKind**：这正是"客户端猜不出该发哪个 skillSlot"的现场
        assertThat(items).hasSize(3);
        assertThat(items).filteredOn(i -> i.itemId().startsWith("item_hero_skillbook"))
                .extracting(i -> i.effectKind())
                .containsOnly("UP_HERO_SKILL");
        assertThat(items).filteredOn(i -> i.itemId().equals("item_hero_skillbook_main"))
                .singleElement().satisfies(i ->
                        assertThat(i.effectTarget()).as("弹层靠这一列定 skillSlot，而不是让玩家选一个槽再赌")
                                .isEqualTo("MAIN"));
        assertThat(items).filteredOn(i -> i.itemId().equals("item_hero_skillbook_sub"))
                .singleElement().satisfies(i -> assertThat(i.effectTarget()).isEqualTo("SUB"));
        // 没配 target 的行下发 null（不是空串）：客户端按 null 判"这本没标主副"
        assertThat(items).filteredOn(i -> i.itemId().equals("item_hero_exp_s"))
                .singleElement().satisfies(i -> assertThat(i.effectTarget()).isNull());
    }

    @Test
    @DisplayName("背包按类型分页；拼错的类型名报错而不是静默返回空列表")
    void bagListFiltersByType() {
        String playerId = newPlayer();
        giveItems(playerId,
                "item_speedup_build_5m", 10L,
                "item_speedup_build_1h", 2L,
                "item_res_wood_10k", 5L);

        BagListResp speedups = bagAppService.list(playerId, "SPEEDUP");
        assertThat(speedups.items()).hasSize(2)
                .allSatisfy(i -> assertThat(i.type()).isEqualTo("SPEEDUP"));
        // 容量用的是整个背包的口径，不随分页变化：否则玩家会以为翻页能腾出格子
        assertThat(speedups.capacityUsed()).isEqualTo(3);

        assertThat(bagAppService.list(playerId, "resource").items()).hasSize(1);
        assertThatThrownBy(() -> bagAppService.list(playerId, "WEAPON"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("从没拿到过道具的玩家还没有背包文档，列表返回空而不是报错")
    void bagListWorksForPlayerWithoutInventory() {
        String playerId = newPlayer();
        BagListResp resp = bagAppService.list(playerId, BagAppService.PAGE_ALL);
        assertThat(resp.items()).isEmpty();
        assertThat(resp.capacityUsed()).isZero();
        assertThat(resp.capacityMax()).isEqualTo(configs.longParam("BAG_INITIAL_CAPACITY"));
    }

    // ---------- B04 §4：使用道具 ----------

    @Test
    @DisplayName("验收10：count 超过持有量时整体拒绝，一个都不扣、绝不扣成负数")
    void useItemRejectsCountOverHeld() {
        String playerId = newPlayer();
        giveItems(playerId, "item_res_wood_10k", 3L);

        assertThatThrownBy(() -> bagAppService.useItem(playerId,
                new ItemUseReq(newRequestId(), "item_res_wood_10k", 5L, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("需要")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ITEM_NOT_ENOUGH);

        assertThat(countOf(playerId, "item_res_wood_10k"))
                .as("拒绝时必须一个都不扣：扣一半会让玩家「道具没了资源也没拿到」")
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("验收2：资源道具超上限的部分进邮件，响应同时给出实发与溢出，不静默")
    void resourceItemOverflowGoesToMail() {
        String playerId = newPlayer();
        giveItems(playerId, "item_res_wood_10k", 2L);

        ItemUseResp resp = bagAppService.useItem(playerId,
                new ItemUseReq(newRequestId(), "item_res_wood_10k", 2L, null));

        assertThat(resp.consumed()).isEqualTo(2L);
        long granted = amountOf(resp.granted(), "WOOD");
        long overflow = amountOf(resp.overflow(), "WOOD");
        assertThat(granted + overflow).as("两个 1 万木材箱必须全部有下落，一点都不能凭空消失")
                .isEqualTo(20_000L);
        assertThat(overflow).as("WOOD 容量 20000、初始 5000，装不下的部分必须溢出")
                .isPositive();
        assertThat(resp.mailId()).as("溢出必须转邮件（B04 验收 2）").isNotNull();

        PlayerResourceState wood = players.findByPlayerId(playerId).orElseThrow().resource("WOOD");
        assertThat(wood.current()).isEqualTo(wood.cap());
        assertThat(countOf(playerId, "item_res_wood_10k")).isZero();
    }

    @Test
    @DisplayName("资源道具正常发放：实发量 = count × effectValue，两个数都来自配置表")
    void resourceItemGrantsConfiguredAmount() {
        String playerId = newPlayer();
        // 只用 1 个：IRON 初始 2000、容量 10000，用 3 个（15000）必然溢出，
        // 那就变成了 resourceItemOverflowGoesToMail 的场景，测不到「正常发放」这条路径
        giveItems(playerId, "item_res_iron_5k", 1L);
        long before = players.findByPlayerId(playerId).orElseThrow().resource("IRON").current();

        ItemUseResp resp = bagAppService.useItem(playerId,
                new ItemUseReq(newRequestId(), "item_res_iron_5k", 1L, null));

        long expected = configs.get(ItemCfg.class, "item_res_iron_5k").effectValue();
        assertThat(amountOf(resp.granted(), "IRON")).isEqualTo(expected);
        assertThat(resp.overflow()).isEmpty();
        assertThat(resp.mailId()).as("没有溢出就不该产生邮件").isNull();
        assertThat(players.findByPlayerId(playerId).orElseThrow().resource("IRON").current())
                .isEqualTo(before + expected);
    }

    @Test
    @DisplayName("验收9：加速道具按配置的 effectValue 减少剩余时间，一次用 N 个就是 N 倍")
    void speedUpItemReducesRemainingByConfiguredSeconds() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        extendUpgrade(playerId, upgrade.buildingId(), 100_000L);
        giveItems(playerId, "item_speedup_build_5m", 2L);

        long before = remainingOf(playerId, upgrade.buildingId());
        long perItem = configs.get(ItemCfg.class, "item_speedup_build_5m").effectValue();

        ItemUseResp resp = bagAppService.useItem(playerId, new ItemUseReq(
                newRequestId(), "item_speedup_build_5m", 2L, upgrade.buildingId()));

        assertThat(resp.reducedSeconds()).as("2 个 5 分钟建造令 = 600 秒")
                .isEqualTo(2L * perItem);
        assertThat(remainingOf(playerId, upgrade.buildingId()))
                .as("剩余时间必须精确减少，对照配置表手算")
                .isEqualTo(before - 2L * perItem);
        assertThat(resp.granted()).as("加速道具不产出资源").isEmpty();
        assertThat(countOf(playerId, "item_speedup_build_5m")).isZero();
    }

    @Test
    @DisplayName("弱网重投同一个 requestId：建造令只扣一张、时间只减一次")
    void replayedBuildSpeedUpChargesOnce() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        extendUpgrade(playerId, upgrade.buildingId(), 100_000L);
        giveItems(playerId, "item_speedup_build_5m", 2L);
        long perItem = configs.get(ItemCfg.class, "item_speedup_build_5m").effectValue();
        long before = remainingOf(playerId, upgrade.buildingId());
        String requestId = newRequestId();

        bagAppService.useItem(playerId, new ItemUseReq(
                requestId, "item_speedup_build_5m", 1L, upgrade.buildingId()));
        assertThatThrownBy(() -> bagAppService.useItem(playerId,
                new ItemUseReq(requestId, "item_speedup_build_5m", 1L, upgrade.buildingId())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .as("/item/use 的加速分流必须去重：建造令是花钱买的东西")
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED);

        assertThat(countOf(playerId, "item_speedup_build_5m")).as("重放不许再扣一张").isEqualTo(1L);
        assertThat(remainingOf(playerId, upgrade.buildingId()))
                .as("重放不许再减一次时间").isEqualTo(before - perItem);
    }

    @Test
    @DisplayName("失败那一笔没动过任何东西 ⇒ 幂等键必须释放，同一个 requestId 还能重试成功")
    void failedSpeedUpReleasesTheKeySoThePlayerCanRetry() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        extendUpgrade(playerId, upgrade.buildingId(), 100_000L);
        giveItems(playerId, "item_speedup_build_5m", 1L);
        String requestId = newRequestId();

        assertThatThrownBy(() -> bagAppService.useItem(playerId,
                new ItemUseReq(requestId, "item_speedup_build_5m", 3L, upgrade.buildingId())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ITEM_NOT_ENOUGH);

        // 不释放键的话这一次重试会报 REQUEST_DUPLICATED —— 玩家看到的是「我明明没加成却还要我等」
        ItemUseResp retry = bagAppService.useItem(playerId,
                new ItemUseReq(requestId, "item_speedup_build_5m", 1L, upgrade.buildingId()));
        assertThat(retry.reducedSeconds())
                .as("重试必须真的生效，而不是只回一个成功")
                .isEqualTo(configs.get(ItemCfg.class, "item_speedup_build_5m").effectValue());
        assertThat(countOf(playerId, "item_speedup_build_5m")).isZero();
    }

    @Test
    @DisplayName("加速到 0 时立即完成，剩余时间不会出现负数（B03 禁止项）")
    void speedUpItemTruncatesAtZero() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        extendUpgrade(playerId, upgrade.buildingId(), 10L);
        giveItems(playerId, "item_speedup_build_8h", 1L);

        ItemUseResp resp = bagAppService.useItem(playerId, new ItemUseReq(
                newRequestId(), "item_speedup_build_8h", 1L, upgrade.buildingId()));

        assertThat(resp.reducedSeconds()).as("只能提前剩下的 10 秒，多出来的 28790 秒被截断")
                .isEqualTo(10L);
        assertThat(remainingOf(playerId, upgrade.buildingId())).isZero();
    }

    @Test
    @DisplayName("验收8 之三：建筑落成真的会记 BUILDING_DONE（加速到 0 ⇒ 收割那一刻打标）")
    void finishingABuildingMarksBuildingDone() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        extendUpgrade(playerId, upgrade.buildingId(), 10L);
        giveItems(playerId, "item_speedup_build_8h", 1L);
        bagAppService.useItem(playerId, new ItemUseReq(
                newRequestId(), "item_speedup_build_8h", 1L, upgrade.buildingId()));
        assertThat(remainingOf(playerId, upgrade.buildingId())).as("加速到 0 才算落成（夹具前提）").isZero();

        cityAppService.collect(playerId, new com.ironoath.web.dto.generated.CityCollectReq(
                newRequestId(), upgrade.buildingId()));

        assertThat(players.findByPlayerId(playerId).orElseThrow().giftPopup().triggeredAtOf("BUILDING_DONE"))
                .as("落成是一类触发：收割那一刻要记下时刻").isPositive();
    }

    @Test
    @DisplayName("加速道具不给 targetId 就拒绝：B04 §4 要求先弹出可选目标再由玩家选")
    void speedUpItemRequiresTarget() {
        String playerId = newPlayer();
        giveItems(playerId, "item_speedup_build_5m", 1L);

        assertThatThrownBy(() -> bagAppService.useItem(playerId,
                new ItemUseReq(newRequestId(), "item_speedup_build_5m", 1L, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("targetId");
        assertThat(countOf(playerId, "item_speedup_build_5m"))
                .as("校验失败不能扣道具").isEqualTo(1L);
    }

    @Test
    @DisplayName("研究加速道具带上了建筑的 targetId 就拒绝：一次一队列，没有可指的对象")
    void researchSpeedUpItemRejectsForeignTarget() {
        String playerId = newPlayer();
        giveItems(playerId, "item_speedup_research_1h", 1L);
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);

        assertThatThrownBy(() -> bagAppService.useItem(playerId, new ItemUseReq(
                newRequestId(), "item_speedup_research_1h", 1L, upgrade.buildingId())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("targetId")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(countOf(playerId, "item_speedup_research_1h"))
                .as("被拒绝时不能扣道具").isEqualTo(1L);
    }

    @Test
    @DisplayName("研究队列空着时用研究令：明确拒绝而不是白扣一张道具")
    void researchSpeedUpItemNeedsABusyQueue() {
        String playerId = newPlayer();
        giveItems(playerId, "item_speedup_research_1h", 1L);

        assertThatThrownBy(() -> bagAppService.useItem(playerId, new ItemUseReq(
                newRequestId(), "item_speedup_research_1h", 1L, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("队列空着")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_NOT_RESEARCHING);
        assertThat(countOf(playerId, "item_speedup_research_1h"))
                .as("校验失败不能扣道具").isEqualTo(1L);
    }

    @Test
    @DisplayName("资源道具带了 targetId 就拒绝：targetId 只对加速类有意义，静默忽略会掩盖客户端路由 bug")
    void nonSpeedupItemCannotSpeedUpBuilding() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        giveItems(playerId, "item_res_wood_10k", 1L);

        assertThatThrownBy(() -> bagAppService.useItem(playerId, new ItemUseReq(
                newRequestId(), "item_res_wood_10k", 1L, upgrade.buildingId())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("targetId")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(countOf(playerId, "item_res_wood_10k")).as("被拒绝时不能扣道具").isEqualTo(1L);
    }

    @Test
    @DisplayName("宝箱走 /item/use 被拒绝并指向 openBatch：产出可能不是资源，塞不进这个响应类型")
    void chestViaUseItemIsRejectedWithPointer() {
        String playerId = newPlayer();
        giveItems(playerId, "item_chest_resource", 1L);

        assertThatThrownBy(() -> bagAppService.useItem(playerId,
                new ItemUseReq(newRequestId(), "item_chest_resource", 1L, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("openBatch");
        assertThat(countOf(playerId, "item_chest_resource")).isEqualTo(1L);
    }

    @Test
    @DisplayName("材料与装备都拒绝并指明该去哪个端点；免战牌真的生效（它是商店里在卖的付费道具）")
    void materialEquipAndBuffAreRoutedElsewhere() {
        String playerId = newPlayer();
        giveItems(playerId, "item_mat_hero_frag_sr", 1L, "item_buff_peace_24h", 1L,
                "eq_iron_sword", 1L);

        // B04 §4 对材料的要求本来就是「背包内展示，使用时跳转对应系统」。
        // B06 落地后这些系统真的存在了，所以这里不再是 NOT_IMPLEMENTED，
        // 而是明确告诉客户端该走 /hero/* 的哪个端点
        assertThatThrownBy(() -> bagAppService.useItem(playerId,
                new ItemUseReq(newRequestId(), "item_mat_hero_frag_sr", 1L, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("/hero/")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ITEM_CANNOT_USE);
        assertThatThrownBy(() -> bagAppService.useItem(playerId,
                new ItemUseReq(newRequestId(), "eq_iron_sword", 1L, null)))
                .isInstanceOf(BizException.class)
                .as("装备不能被「使用」掉，那等于销毁它")
                .hasMessageContaining("/hero/equip")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ITEM_CANNOT_USE);
        assertThat(countOf(playerId, "item_mat_hero_frag_sr")).isEqualTo(1L);
        assertThat(countOf(playerId, "eq_iron_sword")).isEqualTo(1L);

        // 免战牌：它在 shop.json 里标价卖，所以「用了报未实现」是付费内容不成立，
        // 不是功能欠账。这里验的是它真的落地到了停战状态上
        var resp = bagAppService.useItem(playerId,
                new ItemUseReq(newRequestId(), "item_buff_peace_24h", 1L, null));
        assertThat(countOf(playerId, "item_buff_peace_24h"))
                .as("生效就要扣一张，否则可以无限重复使用").isZero();
        long stored = players.findByPlayerId(playerId).orElseThrow().pvp().peaceUntil();
        assertThat(resp.peaceUntil()).as("响应与存档必须给同一个时刻")
                .isEqualTo(stored);
        assertThat(stored - System.currentTimeMillis())
                .as("时长来自 item.effectValue（86400 秒），不是代码里写的数")
                .isGreaterThan(86_000_000L);
        assertThat(players.findByPlayerId(playerId).orElseThrow().pvp().victimShieldUntil())
                .as("免战牌不得借用受害护盾那个字段：两者来源与生命周期都不同，"
                        + "共用会让一次战斗把玩家花钱买的 24h 改写成 4h")
                .isNull();
        // 「停战期不可被攻击、也不可主动出击」这条行为在 ProtectionTest 里断言：
        // 这里再测一次并不具判别性 —— 新号自带新手保护，不用牌同样会被挡
    }

    @Test
    @DisplayName("闭城死守令：8h 免战 + 8h 不可出兵采集，两个到期时刻同时落地")
    void closedCityCardSetsBothDeadlines() {
        String playerId = newPlayer();
        giveItems(playerId, "item_buff_closed_8h", 2L);

        var resp = bagAppService.useItem(playerId,
                new ItemUseReq(newRequestId(), "item_buff_closed_8h", 2L, null));

        assertThat(countOf(playerId, "item_buff_closed_8h")).as("生效就要扣两张").isZero();
        var pvp = players.findByPlayerId(playerId).orElseThrow().pvp();
        long now = System.currentTimeMillis();
        assertThat(pvp.closedUntil()).as("时长来自 item.effectValue × 张数（28800×2 秒）")
                .isGreaterThan(now + 57_000_000L);
        assertThat(pvp.peaceUntil())
                .as("闭城必然免战：关门却还能被人打，这个道具就自相矛盾了")
                .isEqualTo(pvp.closedUntil());
        assertThat(resp.peaceUntil()).as("响应回读的是真正落库的时刻").isEqualTo(pvp.peaceUntil());
        assertThat(pvp.victimShieldUntil()).as("不碰受害护盾字段").isNull();

        // 判别性：只买免战牌的人不该被禁采集，否则 24h 的贵道具比 8h 的更亏
        String peaceOnly = newPlayer();
        giveItems(peaceOnly, "item_buff_peace_24h", 1L);
        bagAppService.useItem(peaceOnly, new ItemUseReq(newRequestId(), "item_buff_peace_24h", 1L, null));
        assertThat(players.findByPlayerId(peaceOnly).orElseThrow().pvp().closedUntil())
                .isNull();
    }

    @Test
    @DisplayName("道具不存在时返回 ITEM_NOT_FOUND 而不是配置层的内部异常")
    void unknownItemIsRejected() {
        String playerId = newPlayer();
        assertThatThrownBy(() -> bagAppService.useItem(playerId,
                new ItemUseReq(newRequestId(), "item_not_exists", 1L, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ITEM_NOT_FOUND);
    }

    // ---------- B04 §4：批量开箱 ----------

    @Test
    @DisplayName("验收3：一次开 100 个宝箱只走一次调用，结果按掉落表顺序聚合、不出现重复条目")
    void openBatchHandlesHundredChestsInOneCall() {
        String playerId = newPlayer();
        giveItems(playerId, "item_chest_resource", 100L);

        OpenBatchResp resp = bagAppService.openBatch(playerId,
                new OpenBatchReq(newRequestId(), "item_chest_resource", 100));

        assertThat(resp.consumed()).isEqualTo(100L);
        assertThat(countOf(playerId, "item_chest_resource")).as("100 个箱子必须一次性扣光").isZero();
        assertThat(resp.results()).isNotEmpty();

        Set<String> ids = new HashSet<>();
        for (RewardItemView view : resp.results()) {
            assertThat(ids.add(view.id())).as("聚合后同一个奖励只能出现一行，否则 100 抽会回 100 行")
                    .isTrue();
            assertThat(view.count()).isPositive();
            assertThat(view.name()).as("名字由服务端从配置表解析，客户端不得自行翻译").isNotBlank();
        }
        assertThat(resp.seed()).as("种子必须回传，否则「结果可复现」在生产环境无法核查").isNotZero();
    }

    @Test
    @DisplayName("验收4：同 seed 开箱两次，逐条结果完全一致")
    void sameSeedProducesIdenticalResults() {
        String first = newPlayer();
        String second = newPlayer();
        giveItems(first, "item_chest_resource", 100L);
        giveItems(second, "item_chest_resource", 100L);
        SEED.set(987654321L);

        OpenBatchResp a = bagAppService.openBatch(first,
                new OpenBatchReq(newRequestId(), "item_chest_resource", 100));
        OpenBatchResp b = bagAppService.openBatch(second,
                new OpenBatchReq(newRequestId(), "item_chest_resource", 100));

        assertThat(a.seed()).isEqualTo(b.seed());
        assertThat(a.results()).as("同 seed + 同 count ⇒ 逐条相同（含顺序与数量）")
                .isEqualTo(b.results());
        assertThat(a.results()).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("换个 seed 结果就会变：证明抽取真的走了随机，而不是每次都返回同一份")
    void differentSeedProducesDifferentResults() {
        String first = newPlayer();
        String second = newPlayer();
        giveItems(first, "item_chest_resource", 100L);
        giveItems(second, "item_chest_resource", 100L);

        SEED.set(1L);
        OpenBatchResp a = bagAppService.openBatch(first,
                new OpenBatchReq(newRequestId(), "item_chest_resource", 100));
        SEED.set(2L);
        OpenBatchResp b = bagAppService.openBatch(second,
                new OpenBatchReq(newRequestId(), "item_chest_resource", 100));

        assertThat(a.results()).isNotEqualTo(b.results());
    }

    @Test
    @DisplayName("验收10：宝箱数量不足时整体拒绝，一个都不扣")
    void openBatchRejectsWhenNotEnoughChests() {
        String playerId = newPlayer();
        giveItems(playerId, "item_chest_resource", 5L);

        assertThatThrownBy(() -> bagAppService.openBatch(playerId,
                new OpenBatchReq(newRequestId(), "item_chest_resource", 10)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("需要")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ITEM_NOT_ENOUGH);

        assertThat(countOf(playerId, "item_chest_resource")).isEqualTo(5L);
    }

    @Test
    @DisplayName("超过 chest.maxBatchCount 时明确拒绝，不静默截断（静默截断等于吞道具或吞计数）")
    void openBatchRejectsCountAboveConfiguredMax() {
        String playerId = newPlayer();
        giveItems(playerId, "item_chest_resource", 200L);
        long max = configs.get(com.ironoath.config.cfg.ChestCfg.class, "item_chest_resource")
                .maxBatchCount();

        assertThatThrownBy(() -> bagAppService.openBatch(playerId,
                new OpenBatchReq(newRequestId(), "item_chest_resource", (int) max + 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("单次最多开");
        assertThat(countOf(playerId, "item_chest_resource")).isEqualTo(200L);
    }

    @Test
    @DisplayName("武将招募匣走 gacha 卡池（B06），不能与资源宝箱共用权重模型，必须明确拒绝")
    void heroChestIsRejectedUntilB06() {
        String playerId = newPlayer();
        giveItems(playerId, "item_chest_hero", 1L);

        assertThatThrownBy(() -> bagAppService.openBatch(playerId,
                new OpenBatchReq(newRequestId(), "item_chest_hero", 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B06")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_IMPLEMENTED);
        assertThat(countOf(playerId, "item_chest_hero")).isEqualTo(1L);
    }

    @Test
    @DisplayName("非宝箱道具不能被批量开启")
    void nonChestCannotBeBatchOpened() {
        String playerId = newPlayer();
        giveItems(playerId, "item_res_wood_10k", 5L);

        assertThatThrownBy(() -> bagAppService.openBatch(playerId,
                new OpenBatchReq(newRequestId(), "item_res_wood_10k", 5)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ITEM_CANNOT_USE);
    }

    @Test
    @DisplayName("开箱产出超过资源上限时溢出转邮件，抽到的东西一点都不会凭空消失")
    void openBatchOverflowGoesToMail() {
        String playerId = newPlayer();
        giveItems(playerId, "item_chest_resource", 100L);
        // 先把四种基础资源全部顶到容量上限，这样任何资源产出都必然溢出
        fillAllResourcesToCap(playerId);

        OpenBatchResp resp = bagAppService.openBatch(playerId,
                new OpenBatchReq(newRequestId(), "item_chest_resource", 100));

        long drawnTotal = resp.results().stream().mapToLong(RewardItemView::count).sum()
                + resp.overflow().stream().mapToLong(RewardItemView::count).sum();
        assertThat(drawnTotal).as("实发 + 溢出必须覆盖全部抽出的量").isPositive();
        assertThat(resp.overflow()).as("满仓时开箱必然溢出").isNotEmpty();
        assertThat(resp.mailId()).as("溢出必须转邮件（B04 验收 2）").isNotNull();
    }

    // ---------- B04 §2：产出明细面板 ----------

    @Test
    @DisplayName("验收5：明细各行之和精确等于实际每小时产出，也精确等于存档里的 perHour（误差 0）")
    void breakdownSumsExactlyToPerHour() {
        String playerId = newPlayer();
        assertBreakdownMatchesSave(playerId);

        // 建一个伐木场并升到 1 级，产率随之变化后仍然必须精确对齐
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        finishNow(playerId, upgrade.buildingId());
        assertBreakdownMatchesSave(playerId);

        ResourceDetail wood = detailOf(resourceAppService.detail(playerId), "WOOD");
        long base = configs.getResource("WOOD").basePerHour();
        long lumberBase = configs.get(com.ironoath.config.cfg.BuildingCfg.class, "lumber_camp")
                .outputBasePerHour();
        assertThat(wood.perHour()).as("领地底产 200 + 伐木场 1 级（120 × 1^1.08）= 320")
                .isEqualTo(base + lumberBase);
        assertThat(wood.breakdown()).extracting(OutputBreak::source)
                .contains("领地基础产出", "伐木场 Lv1", "科技加成", "联盟加成", "道具 buff");
    }

    @Test
    @DisplayName("三个百分比加成行为 0 时也必须出现：面板结构稳定，玩家不会因为少了一行而困惑")
    void bonusLinesAreAlwaysPresent() {
        String playerId = newPlayer();
        for (ResourceDetail detail : resourceAppService.detail(playerId).resources()) {
            assertThat(detail.breakdown()).extracting(OutputBreak::source)
                    .as("%s 的面板必须包含三个加成行", detail.type())
                    .contains("科技加成", "联盟加成", "道具 buff");
            for (OutputBreak bonus : bonusLines(detail)) {
                assertThat(bonus.isPercent()).isTrue();
                assertThat(bonus.percentFixed()).isZero();
                assertThat(bonus.amount()).isZero();
            }
        }
    }

    @Test
    @DisplayName("验收1/11：满仓时 full=true 且产量停止增长；容量与保护额度都来自配置")
    void fullStorageStopsProductionAndIsFlagged() {
        String playerId = newPlayer();
        fillAllResourcesToCap(playerId);
        // 把结算起点推到 10 小时前：如果满仓没有停产，这 10 小时的产量会被凭空加进来
        rewindSettlement(playerId, 10L * 3600_000L);

        ResourceDetailResp resp = resourceAppService.detail(playerId);
        for (ResourceDetail detail : resp.resources()) {
            assertThat(detail.full()).as("%s 已满仓必须置 full=true，UI 据此显示红色警告", detail.type())
                    .isTrue();
            assertThat(detail.current()).isEqualTo(detail.cap());
        }
        // 新号没有仓库 ⇒ 容量就是 resource 表的 initCap；保护额度 = 容量 × RESOURCE_PROTECT_RATIO
        ResourceDetail wood = detailOf(resp, "WOOD");
        assertThat(wood.cap()).isEqualTo(configs.getResource("WOOD").initCap());
        assertThat(wood.protectedAmount()).isEqualTo(com.ironoath.core.resource.ResourceProtection
                .protectedAmount(wood.cap(), configs.fixedParam("RESOURCE_PROTECT_RATIO")));
        // 付费货币不参与保护：金币被抢等于直接拿走玩家充的钱
        assertThat(detailOf(resp, "GOLD").protectedAmount()).isZero();
    }

    @Test
    @DisplayName("仓库升级后四种基础资源容量同步抬高，金币容量不受影响")
    void warehouseRaisesBaseResourceCapacity() {
        String playerId = newPlayer();
        long before = detailOf(resourceAppService.detail(playerId), "WOOD").cap();

        // 仓库 requireMainLevel=2，所以必须先把主城升到 2 级。
        // 这一步同时在验证 cityLevel 会随主城升级前进 —— 此前没有任何代码同步它，
        // 结果所有 requireMainLevel ≥ 2 的建筑（仓库、农田、兵营…）永远建不起来。
        CityUpgradeResp mainCity = startUpgrade(playerId, "main_city", 3, 3);
        finishNow(playerId, mainCity.buildingId());
        assertThat(players.findByPlayerId(playerId).orElseThrow().cityLevel())
                .as("主城升到 2 级后，前置校验依据的 cityLevel 必须跟着前进")
                .isEqualTo(2);

        CityUpgradeResp upgrade = startUpgrade(playerId, "warehouse", 2, 1);
        finishNow(playerId, upgrade.buildingId());

        ResourceDetailResp resp = resourceAppService.detail(playerId);
        long capBase = configs.get(com.ironoath.config.cfg.BuildingCfg.class, "warehouse").capBase();
        // 仓库 1 级 ⇒ 容量增加 capBase × 1^1.08 = capBase
        assertThat(detailOf(resp, "WOOD").cap()).isEqualTo(before + capBase);
        assertThat(detailOf(resp, "GRAIN").cap())
                .isEqualTo(configs.getResource("GRAIN").initCap() + capBase);
        assertThat(detailOf(resp, "GOLD").cap())
                .as("金币是 CURRENCY，不吃仓库容量")
                .isEqualTo(configs.getResource("GOLD").initCap());
    }

    // ---------- 辅助 ----------

    private void assertBreakdownMatchesSave(String playerId) {
        ResourceDetailResp resp = resourceAppService.detail(playerId);
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        for (ResourceDetail detail : resp.resources()) {
            long sum = detail.breakdown().stream().mapToLong(OutputBreak::amount).sum();
            assertThat(sum).as("%s 明细之和必须等于面板显示的每小时产出（验收 5：误差 0）", detail.type())
                    .isEqualTo(detail.perHour());
            assertThat(save.resource(detail.type().name()).perHour())
                    .as("%s 面板产率必须与存档里那个真正参与结算的 perHour 完全一致", detail.type())
                    .isEqualTo(detail.perHour());
            assertThat(save.resource(detail.type().name()).cap()).isEqualTo(detail.cap());
        }
    }

    private static List<OutputBreak> bonusLines(ResourceDetail detail) {
        List<OutputBreak> out = new ArrayList<>();
        for (OutputBreak b : detail.breakdown()) {
            if (b.isPercent()) {
                out.add(b);
            }
        }
        return out;
    }

    private static ResourceDetail detailOf(ResourceDetailResp resp, String resourceId) {
        for (ResourceDetail d : resp.resources()) {
            if (d.type().name().equals(resourceId)) {
                return d;
            }
        }
        throw new AssertionError("响应里没有资源 " + resourceId);
    }

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "背包测试", 1_700_000_000_000L, ""))
                .playerId();
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    /** 按 (道具, 数量) 成对入参直接铺一个背包，绕过发放链路以便精确控制初始状态。 */
    private void giveItems(String playerId, Object... itemIdCountPairs) {
        Inventory bag = Inventory.empty((int) configs.longParam("BAG_INITIAL_CAPACITY"));
        for (int i = 0; i < itemIdCountPairs.length; i += 2) {
            String itemId = (String) itemIdCountPairs[i];
            long count = (Long) itemIdCountPairs[i + 1];
            long added = bag.add(itemId, count, configs.get(ItemCfg.class, itemId).stackMax());
            assertThat(added).as("测试夹具必须能放下 %s × %d", itemId, count).isEqualTo(count);
        }
        assertThat(inventories.insertIfAbsent(playerId, bag)).isTrue();
    }

    private long countOf(String playerId, String itemId) {
        return inventories.findByPlayerId(playerId).map(b -> b.countOf(itemId)).orElse(0L);
    }

    private CityUpgradeResp startUpgrade(String playerId, String configId, int x, int y) {
        return cityAppService.upgrade(playerId, new CityUpgradeReq(newRequestId(), configId, x, y));
    }

    /**
     * 把升级工期改写为指定秒数。
     *
     * <p>1→2 级的工期只有几十秒（B00 五分钟体验刻意让前期几乎瞬时完成），
     * 而加速道具动辄 300/28800 秒，会被正确截断。要验证「加速量等于配置值」必须先把工期拉长。
     * 服务端不跑定时器，所以测试也不需要 sleep —— 直接改完成时刻即可。
     */
    private void extendUpgrade(String playerId, String buildingId, long seconds) {
        CityState city = cities.findByPlayerId(playerId).orElseThrow();
        long version = cities.versionOf(playerId);
        var b = city.building(buildingId);
        long now = System.currentTimeMillis();
        // 完成时刻必须是「未来」：写成过去会让下一次读取立刻把它收割掉
        b.restore(b.level(), b.gridX(), b.gridY(),
                com.ironoath.core.city.BuildingStatus.UPGRADING, now + seconds * 1000L,
                now, seconds, seconds, b.helpCount(), b.lastMovedAt(), b.lastFinishedAt());
        cities.save(playerId, city, version);
    }

    /** 把完成时刻改到过去并触发收割，等价于「时间流逝到升级完成后再读一次档」。 */
    private void finishNow(String playerId, String buildingId) {
        CityState city = cities.findByPlayerId(playerId).orElseThrow();
        long version = cities.versionOf(playerId);
        var b = city.building(buildingId);
        b.restore(b.level(), b.gridX(), b.gridY(),
                com.ironoath.core.city.BuildingStatus.UPGRADING, 1L,
                0L, 1L, 1L, b.helpCount(), b.lastMovedAt(), b.lastFinishedAt());
        cities.save(playerId, city, version);
        cityAppService.collect(playerId,
                new com.ironoath.web.dto.generated.CityCollectReq(newRequestId(), buildingId));
    }

    private long remainingOf(String playerId, String buildingId) {
        return cityAppService.list(playerId).buildings().stream()
                .filter(b -> b.id().equals(buildingId))
                .findFirst().orElseThrow().remainingSeconds();
    }

    /** 把所有资源顶到容量上限，用于验证满仓停产与溢出转邮件。 */
    private void fillAllResourcesToCap(String playerId) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        for (String id : configs.resourceIds()) {
            PlayerResourceState s = save.resource(id);
            save.putResource(id, new PlayerResourceState(
                    s.cap(), s.cap(), s.protectedAmount(), s.perHour(), s.lastSettle()));
        }
        players.save(save);
    }

    /** 把结算起点往回拨，模拟「玩家离线了这么久」。 */
    private void rewindSettlement(String playerId, long millis) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        long now = System.currentTimeMillis();
        for (String id : configs.resourceIds()) {
            PlayerResourceState s = save.resource(id);
            save.putResource(id, new PlayerResourceState(
                    s.current(), s.cap(), s.protectedAmount(), s.perHour(), now - millis));
        }
        players.save(save);
    }

    private static long amountOf(List<com.ironoath.web.dto.generated.ResourceAmount> list, String type) {
        long sum = 0L;
        for (var amount : list) {
            if (amount.type().name().equals(type)) {
                sum += amount.amount();
            }
        }
        return sum;
    }
}
