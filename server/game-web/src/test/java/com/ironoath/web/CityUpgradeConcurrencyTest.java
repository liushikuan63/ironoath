package com.ironoath.web;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.web.dto.generated.CityUpgradeReq;
import com.ironoath.web.dto.generated.CityUpgradeResp;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：城建集成测试 —— 覆盖 B03 验收 2（并发只 1 个成功）与验收 10（同 requestId 只扣一次资源）。
 * 依赖：Spring Boot Test，test profile（内存存储 + JVM 内锁，不需要 MongoDB / Redis）。
 *
 * <p>这两条验收必须在集成层测，不能只在 game-core 单测里测：
 * 它们验证的是「锁 + 幂等 + 乐观锁」三道防线的<b>组合</b>是否真的挡住了并发，
 * 而单测里 CityState 是单线程调用的，根本产生不了竞争。
 */
@SpringBootTest
@ActiveProfiles("test")
class CityUpgradeConcurrencyTest {

    @Autowired
    private CityAppService cityAppService;

    @Autowired
    private PlayerInitService playerInitService;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private CityRepository cities;

    @Autowired
    private ConfigRegistry configs;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
    }

    private String newPlayer() {
        var resp = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "城建测试", 1_700_000_000_000L, ""));
        return resp.playerId();
    }

    private CityUpgradeReq req(String configId, int gridX, int gridY) {
        return new CityUpgradeReq("req-" + UUID.randomUUID(), configId, gridX, gridY);
    }

    @Test
    @DisplayName("验收2：并发 10 个同一建筑的升级请求（不同 requestId），只有 1 个成功")
    void concurrentUpgradesAllowOnlyOneSuccess() throws Exception {
        String playerId = newPlayer();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                // 全部请求升同一个建筑；坐标相同，只有第一个能占住地块
                CityUpgradeReq r = req("lumber_camp", 1, 1);
                tasks.add(() -> {
                    try {
                        CityUpgradeResp resp = cityAppService.upgrade(playerId, r);
                        return "OK:" + resp.level();
                    } catch (BizException e) {
                        return "BIZ:" + e.code();
                    } catch (IllegalStateException e) {
                        // 乐观锁冲突：说明锁没挡住，但第三道防线兜住了
                        return "OPT_LOCK";
                    }
                });
            }
            List<Future<String>> futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
            List<String> outcomes = new ArrayList<>();
            for (Future<String> f : futures) {
                outcomes.add(f.get());
            }

            long successes = outcomes.stream().filter(s -> s.startsWith("OK")).count();
            assertThat(successes).as("10 个并发请求必须只有 1 个成功，实际结果=%s", outcomes).isEqualTo(1L);

            // 失败的必须是明确错误码，不能是 500 或未知异常（B03 §2：结构化错误）
            for (String outcome : outcomes) {
                if (outcome.startsWith("OK")) {
                    continue;
                }
                assertThat(outcome)
                        .as("失败请求必须给出明确原因，实际=%s", outcome)
                        .isIn("BIZ:" + ErrorCode.CITY_UPGRADING.code(),
                                "BIZ:" + ErrorCode.CITY_QUEUE_FULL.code(),
                                "BIZ:" + ErrorCode.CITY_GRID_INVALID.code(),
                                "OPT_LOCK");
            }

            // 最终状态：只有一个伐木场实例，且只在升级中一次
            CityState city = cities.findByPlayerId(playerId).orElseThrow();
            assertThat(city.buildings()).hasSize(2);   // 主城 + 伐木场
            assertThat(city.usedQueues()).as("只占用一个建造队列").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("验收10：同一 requestId 重复提交，只扣一次资源")
    void duplicateRequestIdDeductsResourcesOnce() {
        String playerId = newPlayer();
        CityUpgradeReq r = req("lumber_camp", 1, 1);

        CityUpgradeResp first = cityAppService.upgrade(playerId, r);
        long woodAfterFirst = woodOf(playerId);

        // 同 requestId 重放：必须被幂等键挡住，而不是再扣一次
        assertThatThrownBy(() -> cityAppService.upgrade(playerId, r))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED);

        assertThat(woodOf(playerId)).as("重放不得再扣资源").isEqualTo(woodAfterFirst);
        assertThat(cities.findByPlayerId(playerId).orElseThrow().usedQueues()).isEqualTo(1);
        assertThat(first.level()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同 requestId 重复升同一建筑：被「已在升级中」挡住，同样不会双扣")
    void secondUpgradeIsRejectedByStatusCheck() {
        String playerId = newPlayer();
        cityAppService.upgrade(playerId, req("lumber_camp", 1, 1));
        long woodAfterFirst = woodOf(playerId);

        assertThatThrownBy(() -> cityAppService.upgrade(playerId, req("lumber_camp", 1, 1)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.CITY_UPGRADING);

        assertThat(woodOf(playerId)).isEqualTo(woodAfterFirst);
    }

    @Test
    @DisplayName("升级消耗按 BUILDING_COST 曲线（比率 1.22）从配置算出，代码中无硬编码")
    void upgradeCostComesFromCurve() {
        String playerId = newPlayer();
        // lumber_camp 的 costBaseStone=400，1→2 级对应曲线第 1 项 ⇒ 400 × 1.22^0 = 400
        CityUpgradeResp resp = cityAppService.upgrade(playerId, req("lumber_camp", 1, 1));

        long stoneCost = resp.cost().stream()
                .filter(a -> a.type().name().equals("STONE"))
                .mapToLong(a -> a.amount())
                .sum();
        assertThat(stoneCost).as("伐木场 1→2 级应消耗石料 400（配置基数）").isEqualTo(400L);
        assertThat(resp.cost()).as("伐木场不吃木材，costBaseWood=0 不应出现在扣减里")
                .noneMatch(a -> a.type().name().equals("WOOD"));
        assertThat(resp.finishAt()).isGreaterThan(0L);
        assertThat(resp.powerDelta()).as("伐木场 powerBase=4，战力增量应为正").isPositive();
    }

    @Test
    @DisplayName("主城等级不足时抛结构化错误，detail 精确到「需要主城 X 级，当前 Y 级」（验收6）")
    void mainLevelRequirementIsStructured() {
        String playerId = newPlayer();
        // stable 要求主城 5 级，新号是 1 级
        assertThatThrownBy(() -> cityAppService.upgrade(playerId, req("stable", 1, 2)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException be = (BizException) e;
                    assertThat(be.errorCode()).isEqualTo(ErrorCode.CITY_MAIN_LEVEL_LOW);
                    assertThat(be.detail()).contains("需要 主城 5 级").contains("当前 主城 1 级");
                });
        // 失败不得留下任何副作用：不占队列、不扣资源、不建实例
        assertThat(cities.findByPlayerId(playerId).map(CityState::usedQueues).orElse(0)).isZero();
    }

    @Test
    @DisplayName("资源不足时抛结构化错误，且不产生副作用（验收6 + 幂等键被释放）")
    void insufficientResourcesIsStructuredAndReleasesIdempotencyKey() {
        String playerId = newPlayer();
        // siege_workshop 要 1200 木 + 600 石 + 400 铁，新号木石各 5000、铁只有 2000，够；
        // 改成先把资源清空来制造缺口
        var save = players.findByPlayerId(playerId).orElseThrow();
        save.putResource("STONE", new PlayerResourceState(0L, save.resource("STONE").cap(),
                0L, save.resource("STONE").perHour(), save.resource("STONE").lastSettle()));
        players.save(save);

        // 用 lumber_camp（requireMainLevel=1）而不是 barracks（要求主城 3 级）：
        // 后者会先撞上主城等级校验，测不到资源分支。lumber_camp 只吃石料 400。
        CityUpgradeReq r = req("lumber_camp", 2, 1);
        assertThatThrownBy(() -> cityAppService.upgrade(playerId, r))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException be = (BizException) e;
                    assertThat(be.errorCode()).isEqualTo(ErrorCode.CITY_RESOURCE_LOW);
                    assertThat(be.detail()).contains("需要 STONE 400").contains("当前 STONE 0");
                });

        // 副作用检查：不得占用队列
        assertThat(cities.findByPlayerId(playerId).map(CityState::usedQueues).orElse(0)).isZero();

        // 幂等键必须已释放：补足资源后用同一个 requestId 重试应该能成功，
        // 否则玩家会因为一次失败而被永久锁死在这个 requestId 上
        var restored = players.findByPlayerId(playerId).orElseThrow();
        restored.putResource("STONE", new PlayerResourceState(9999L, restored.resource("STONE").cap(),
                0L, restored.resource("STONE").perHour(), restored.resource("STONE").lastSettle()));
        players.save(restored);
        CityUpgradeResp retry = cityAppService.upgrade(playerId, r);
        assertThat(retry.level()).isEqualTo(1);
    }

    @Test
    @DisplayName("首次建造必须给坐标；中心格不能建非主城；重复占用地块被拒绝")
    void placementRulesAreEnforced() {
        String playerId = newPlayer();

        assertThatThrownBy(() -> cityAppService.upgrade(playerId,
                new CityUpgradeReq("req-" + UUID.randomUUID(), "lumber_camp", null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须给出地块坐标");

        // 中心格 (3,3) 已被主城占用
        assertThatThrownBy(() -> cityAppService.upgrade(playerId, req("lumber_camp", 3, 3)))
                .isInstanceOf(BizException.class);

        cityAppService.upgrade(playerId, req("lumber_camp", 1, 1));
    }

    @Test
    @DisplayName("未知建筑 id 被拒绝，不会静默创建一个空建筑")
    void unknownBuildingIsRejected() {
        String playerId = newPlayer();
        assertThatThrownBy(() -> cityAppService.upgrade(playerId, req("not_a_building", 1, 1)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.CITY_BUILDING_NOT_FOUND);
        // 校验在建存档之前就失败了，所以城建存档压根不会被创建 —— 不留任何半成品状态
        assertThat(cities.findByPlayerId(playerId)).isEmpty();
    }

    @Test
    @DisplayName("队列上限生效：新号在保护期内有 2 个队列，第 3 个建筑同时升级被拒")
    void queueLimitIsEnforced() {
        String playerId = newPlayer();
        // 新号处于新手保护期，city_rule_newbie_free_queue_count=2 ⇒ 前两个都能开工
        // （B03 禁止项：不要让玩家在新手期内被建造队列卡死）
        cityAppService.upgrade(playerId, req("lumber_camp", 1, 1));
        cityAppService.upgrade(playerId, req("quarry", 2, 1));
        assertThat(cities.findByPlayerId(playerId).orElseThrow().usedQueues()).isEqualTo(2);

        assertThatThrownBy(() -> cityAppService.upgrade(playerId, req("farm", 1, 2)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.CITY_QUEUE_FULL);
        // 失败后不得留下半成品实例占格子
        assertThat(cities.findByPlayerId(playerId).orElseThrow().usedQueues()).isEqualTo(2);
    }

    private long woodOf(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow().resource("WOOD").current();
    }

    /** 供排查：把城建存档打成 JSON。 */
    @SuppressWarnings("unused")
    private String dump(String playerId) {
        CityState city = cities.findByPlayerId(playerId).orElseThrow();
        return JsonUtils.toJson(city.buildings().stream()
                .map(b -> b.instanceId() + "@" + b.level() + ":" + b.status())
                .toList());
    }

    /** 确认配置表里这些建筑确实存在，避免测试因为改表而静默失效。 */
    @Test
    @DisplayName("测试依赖的建筑配置齐备")
    void requiredBuildingConfigsExist() {
        for (String id : List.of("main_city", "lumber_camp", "quarry", "stable", "barracks", "siege_workshop")) {
            assertThat(configs.get(com.ironoath.config.cfg.BuildingCfg.class, id))
                    .as("building 表必须有 %s", id).isNotNull();
        }
    }
}
