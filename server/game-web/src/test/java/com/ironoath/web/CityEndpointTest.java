package com.ironoath.web;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.dto.generated.CityCancelReq;
import com.ironoath.web.dto.generated.CityCancelResp;
import com.ironoath.web.dto.generated.CityCollectResp;
import com.ironoath.web.dto.generated.CityListResp;
import com.ironoath.web.dto.generated.CityUpgradeReq;
import com.ironoath.web.dto.generated.CityUpgradeResp;
import com.ironoath.web.dto.generated.SpeedUpReq;
import com.ironoath.web.dto.generated.SpeedUpResp;
import com.ironoath.web.dto.generated.SpeedUpSource;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.BuildingStatus;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：城建四个端点（list / speedUp / cancel / collect）的集成测试。
 * 依赖：Spring Boot Test，test profile（内存存储 + JVM 内锁，不需要 MongoDB / Redis）。
 *
 * <p>重点验证「读接口也有副作用」这件事：{@code /city/list} 会顺带收割到点的升级并结算离线产出。
 * 这是惰性结算的必然结果（没有定时器推进状态），也是最容易被忽略、进而在上线后
 * 表现为「玩家打开城内界面资源突然变多」的行为。
 */
@SpringBootTest
@ActiveProfiles("test")
class CityEndpointTest {

    @Autowired
    private CityAppService cityAppService;

    @Autowired
    private PlayerInitService playerInitService;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private CityRepository cities;

    @Autowired
    private com.ironoath.core.bag.InventoryRepository inventories;

    @Autowired
    private com.ironoath.web.service.SocialAppService social;

    @Autowired
    private com.ironoath.web.social.SocialStore socialStore;

    @Autowired
    private com.ironoath.web.quest.QuestAppService quests;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
        ((com.ironoath.web.store.memory.InMemoryInventoryStore) inventories).clear();
        // 求助请求挂在社交存储上，不清就会跨用例泄漏成"上一条用例留下的红点"
        socialStore.clear();
    }

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "端点测试", 1_700_000_000_000L, ""))
                .playerId();
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private CityUpgradeResp startUpgrade(String playerId, String configId, int x, int y) {
        return cityAppService.upgrade(playerId,
                new CityUpgradeReq(newRequestId(), configId, x, y));
    }

    // ---------- /city/list ----------

    @Test
    @DisplayName("list 返回主城、队列视图（含新手期的 2 个队列）与五种资源的结算结果")
    void listReturnsFullCitySnapshot() {
        String playerId = newPlayer();
        CityListResp resp = cityAppService.list(playerId);

        assertThat(resp.buildings()).as("新号只有中心格的主城").hasSize(1);
        assertThat(resp.buildings().get(0).configId()).isEqualTo("main_city");
        assertThat(resp.buildings().get(0).level()).isEqualTo(1);
        assertThat(resp.buildings().get(0).status()).isEqualTo(BuildingStatus.IDLE);
        assertThat(resp.buildings().get(0).gridX()).isEqualTo(3);
        assertThat(resp.buildings().get(0).gridY()).isEqualTo(3);
        assertThat(resp.buildOptions())
                .as("未放置的建筑目录是首次建造选择器的唯一来源")
                .isNotEmpty()
                .noneMatch(option -> option.configId().equals("main_city"));
        assertThat(resp.buildOptions())
                .anyMatch(option -> option.configId().equals("lumber_camp")
                        && option.name().equals("伐木场"));

        assertThat(resp.queues().used()).isZero();
        assertThat(resp.queues().available()).as("新号在保护期内应有 2 个队列").isEqualTo(2);
        assertThat(resp.queues().max()).isEqualTo(3);

        // 6 = resource 表的行数（WOOD/STONE/IRON/GRAIN/GOLD + B09 的 STAMINA）。
        // 这里写死而不是读配置表，是为了让「新增一种资源」这个动作必须显式过一次评审 ——
        // 资源条会同时出现在城建快照、产出明细与掠夺结算里，加一种不是改一个数字那么小
        assertThat(resp.resources()).hasSize(6);
        assertThat(resp.serverNow()).isPositive();
    }

    @Test
    @DisplayName("list 是幂等读取：连续两次调用不会重复结算产出")
    void listIsIdempotentForSettlement() {
        String playerId = newPlayer();
        long first = cityAppService.list(playerId).resources()
                .get(com.ironoath.web.dto.generated.ResourceType.WOOD).current();
        long second = cityAppService.list(playerId).resources()
                .get(com.ironoath.web.dto.generated.ResourceType.WOOD).current();
        // 两次调用间隔极短，产量按毫秒折算不足 1 单位 ⇒ 向下取整后应完全相同
        assertThat(second).as("重复读取不得刷出资源").isEqualTo(first);
    }

    // ---------- /city/speedUp ----------

    @Test
    @DisplayName("GOLD 加速：按配置扣 100 金币、提前 1 小时，剩余时间同步下降")
    void goldSpeedUpDeductsConfiguredAmount() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        extendUpgrade(playerId, upgrade.buildingId(), 100_000L);
        long goldBefore = goldOf(playerId);

        SpeedUpResp resp = cityAppService.speedUp(playerId,
                new SpeedUpReq(newRequestId(), upgrade.buildingId(), SpeedUpSource.GOLD, null));

        assertThat(resp.reducedSeconds()).isEqualTo(3600L);
        assertThat(goldOf(playerId)).as("应扣除 city_rule_gold_speedup_cost=100").isEqualTo(goldBefore - 100L);
        // 工期被延长到 100000 秒，扣掉 3600 后应剩约 96400 秒
        assertThat(resp.remainingSeconds()).isBetween(96_000L, 96_400L);
        assertThat(resp.finished()).isFalse();
    }

    @Test
    @DisplayName("GOLD 加速：金币不足时拒绝，且不提前任何时间")
    void goldSpeedUpRejectsWhenInsufficient() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        drainGold(playerId);

        assertThatThrownBy(() -> cityAppService.speedUp(playerId,
                new SpeedUpReq(newRequestId(), upgrade.buildingId(), SpeedUpSource.GOLD, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_ENOUGH);
    }

    @Test
    @DisplayName("AD 加速：受每日次数上限约束，超限后拒绝")
    void adSpeedUpRespectsDailyLimit() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        extendUpgrade(playerId, upgrade.buildingId(), 100_000L);

        // city_rule_ad_speedup_daily_limit = 5，每次减 300 秒
        for (int i = 0; i < 5; i++) {
            SpeedUpResp resp = cityAppService.speedUp(playerId,
                    new SpeedUpReq(newRequestId(), upgrade.buildingId(), SpeedUpSource.AD, null));
            assertThat(resp.reducedSeconds()).isEqualTo(300L);
        }
        assertThatThrownBy(() -> cityAppService.speedUp(playerId,
                new SpeedUpReq(newRequestId(), upgrade.buildingId(), SpeedUpSource.AD, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("ALLIANCE 帮助加速：每次减 1%，达到 20% 上限后拒绝")
    void allianceHelpSpeedUpIsCapped() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        extendUpgrade(playerId, upgrade.buildingId(), 100_000L);

        int applied = 0;
        for (int i = 0; i < 55; i++) {
            try {
                SpeedUpResp resp = cityAppService.speedUp(playerId,
                        new SpeedUpReq(newRequestId(), upgrade.buildingId(), SpeedUpSource.ALLIANCE, null));
                if (resp.reducedSeconds() > 0L) {
                    applied++;
                }
            } catch (BizException e) {
                assertThat(e.errorCode()).as("到顶之后应当是「限流/已达上限」这一类拒绝")
                        .isEqualTo(ErrorCode.RATE_LIMITED);
                break;
            }
        }
        // 上限 50%（global.HELP_SPEEDUP_TOTAL_CAP，2026-09-11 裁决）÷ 每次 1% = 50 次。
        // 旧口径是 city_rule 的 20% —— 那套已作废，两处各判一次就会漂
        assertThat(applied).as("帮助加速应在 50 次后触顶").isBetween(49, 50);
        assertThat(remainingOf(playerId, upgrade.buildingId()))
                .as("被帮满之后剩余时长应当恰好少掉原始时长的一半（100000 → 50000 秒）")
                .isBetween(49_900L, 50_100L);
    }

    @Test
    @DisplayName("ITEM 加速的入参校验：缺 itemId 拒绝；非加速类道具即使持有也拒绝")
    void itemSpeedUpValidatesInput() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        extendUpgrade(playerId, upgrade.buildingId(), 100_000L);

        // 先备货：加速类与非加速类各放一些，才能区分「类型不对」与「没有道具」两种失败
        com.ironoath.core.bag.Inventory bag = com.ironoath.core.bag.Inventory.empty(100);
        bag.add("item_speedup_build_5m", 3L, 999L);
        bag.add("item_res_wood_10k", 3L, 999L);
        inventories.insertIfAbsent(playerId, bag);

        // 缺 itemId：入参校验，早于类型与库存检查
        assertThatThrownBy(() -> cityAppService.speedUp(playerId, new SpeedUpReq(
                newRequestId(), upgrade.buildingId(), SpeedUpSource.ITEM, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);

        // 持有资源箱但不是加速道具：类型校验优先于库存扣减，不得消耗任何道具
        assertThatThrownBy(() -> cityAppService.speedUp(playerId, new SpeedUpReq(
                newRequestId(), upgrade.buildingId(), SpeedUpSource.ITEM, "item_res_wood_10k")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ITEM_CANNOT_USE);
        assertThat(inventories.findByPlayerId(playerId).orElseThrow()
                .countOf("item_res_wood_10k")).as("被拒绝的请求不得消耗道具").isEqualTo(3L);

        // 合法的加速道具：正常生效并扣 1 个
        SpeedUpResp ok = cityAppService.speedUp(playerId, new SpeedUpReq(
                newRequestId(), upgrade.buildingId(), SpeedUpSource.ITEM, "item_speedup_build_5m"));
        assertThat(ok.reducedSeconds()).isEqualTo(300L);
        assertThat(inventories.findByPlayerId(playerId).orElseThrow()
                .countOf("item_speedup_build_5m")).isEqualTo(2L);
    }

    @Test
    @DisplayName("加速不会出现负数剩余时间：超额加速被截断到 0 并标记完成")
    void speedUpNeverProducesNegativeRemaining() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        // 这个用例要验证的正是「超额加速被截断」，所以保持原始的短工期，不延长

        // 反复用金币加速，直到剩余时间归零
        for (int i = 0; i < 10; i++) {
            SpeedUpResp resp = cityAppService.speedUp(playerId,
                    new SpeedUpReq(newRequestId(), upgrade.buildingId(), SpeedUpSource.GOLD, null));
            assertThat(resp.remainingSeconds()).isGreaterThanOrEqualTo(0L);
            if (resp.finished()) {
                break;
            }
        }
        CityListResp after = cityAppService.list(playerId);
        long remaining = after.buildings().stream()
                .filter(b -> b.id().equals(upgrade.buildingId()))
                .mapToLong(b -> b.remainingSeconds())
                .sum();
        assertThat(remaining).as("剩余时间不得为负").isZero();
        long progress = after.buildings().stream()
                .filter(b -> b.id().equals(upgrade.buildingId()))
                .mapToLong(b -> b.progress())
                .sum();
        assertThat(progress).as("进度不得超过定点 1.0").isLessThanOrEqualTo(10000L);
    }

    @Test
    @DisplayName("对空闲建筑加速被拒绝")
    void speedUpRejectsIdleBuilding() {
        String playerId = newPlayer();
        CityListResp city = cityAppService.list(playerId);
        String mainCityId = city.buildings().get(0).id();

        assertThatThrownBy(() -> cityAppService.speedUp(playerId,
                new SpeedUpReq(newRequestId(), mainCityId, SpeedUpSource.GOLD, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.CITY_UPGRADING);
    }

    @Test
    @DisplayName("ITEM 加速必须扣库存：没有道具时拒绝，绝不免费加速（此前的未闭合漏洞）")
    void itemSpeedUpDeductsInventory() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        extendUpgrade(playerId, upgrade.buildingId(), 100_000L);

        // 背包为空 ⇒ 必须拒绝，而不是照样加速
        assertThatThrownBy(() -> cityAppService.speedUp(playerId, new SpeedUpReq(
                newRequestId(), upgrade.buildingId(), SpeedUpSource.ITEM, "item_speedup_build_1h")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ITEM_NOT_ENOUGH);

        // 剩余时间不得因为失败的请求而减少
        CityListResp untouched = cityAppService.list(playerId);
        long remainingBefore = untouched.buildings().stream()
                .filter(b -> b.id().equals(upgrade.buildingId()))
                .mapToLong(b -> b.remainingSeconds()).sum();

        // 发一个道具进背包，再用它加速 ⇒ 应当成功且库存归零
        com.ironoath.core.bag.Inventory bag = inventories.findByPlayerId(playerId)
                .orElseGet(() -> {
                    com.ironoath.core.bag.Inventory fresh =
                            com.ironoath.core.bag.Inventory.empty(100);
                    inventories.insertIfAbsent(playerId, fresh);
                    return inventories.findByPlayerId(playerId).orElseThrow();
                });
        long bagVersion = inventories.versionOf(playerId);
        assertThat(bag.add("item_speedup_build_1h", 2L, 999L)).isEqualTo(2L);
        inventories.save(playerId, bag, bagVersion);

        SpeedUpResp resp = cityAppService.speedUp(playerId, new SpeedUpReq(
                newRequestId(), upgrade.buildingId(), SpeedUpSource.ITEM, "item_speedup_build_1h"));
        assertThat(resp.reducedSeconds()).as("一小时建造令应提前 3600 秒").isEqualTo(3600L);

        com.ironoath.core.bag.Inventory after = inventories.findByPlayerId(playerId).orElseThrow();
        assertThat(after.countOf("item_speedup_build_1h")).as("用掉一个，应剩 1 个").isEqualTo(1L);

        CityListResp accelerated = cityAppService.list(playerId);
        long remainingAfter = accelerated.buildings().stream()
                .filter(b -> b.id().equals(upgrade.buildingId()))
                .mapToLong(b -> b.remainingSeconds()).sum();
        assertThat(remainingAfter).isLessThan(remainingBefore);
    }

    @Test
    @DisplayName("ITEM 加速：持有 1 个时用掉即归零，第二次请求被拒绝")
    void itemSpeedUpConsumesLastCopy() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        extendUpgrade(playerId, upgrade.buildingId(), 100_000L);

        com.ironoath.core.bag.Inventory bag = com.ironoath.core.bag.Inventory.empty(100);
        bag.add("item_speedup_build_5m", 1L, 999L);
        inventories.insertIfAbsent(playerId, bag);

        SpeedUpResp resp = cityAppService.speedUp(playerId, new SpeedUpReq(
                newRequestId(), upgrade.buildingId(), SpeedUpSource.ITEM, "item_speedup_build_5m"));
        assertThat(resp.reducedSeconds()).isEqualTo(300L);
        assertThat(inventories.findByPlayerId(playerId).orElseThrow()
                .countOf("item_speedup_build_5m")).isZero();

        assertThatThrownBy(() -> cityAppService.speedUp(playerId, new SpeedUpReq(
                newRequestId(), upgrade.buildingId(), SpeedUpSource.ITEM, "item_speedup_build_5m")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ITEM_NOT_ENOUGH);
    }

    @Test
    @DisplayName("非加速类道具即使持有也不能用于加速")
    void nonSpeedupItemIsRejected() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        extendUpgrade(playerId, upgrade.buildingId(), 100_000L);

        com.ironoath.core.bag.Inventory bag = com.ironoath.core.bag.Inventory.empty(100);
        bag.add("item_res_wood_10k", 5L, 999L);
        inventories.insertIfAbsent(playerId, bag);

        assertThatThrownBy(() -> cityAppService.speedUp(playerId, new SpeedUpReq(
                newRequestId(), upgrade.buildingId(), SpeedUpSource.ITEM, "item_res_wood_10k")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ITEM_CANNOT_USE);
        // 被拒绝的请求不得消耗道具
        assertThat(inventories.findByPlayerId(playerId).orElseThrow()
                .countOf("item_res_wood_10k")).isEqualTo(5L);
    }

    // ---------- /city/cancel ----------

    @Test
    @DisplayName("cancel 返还 60% 资源，建筑回到空闲、队列释放")
    void cancelRefundsSixtyPercent() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        long stoneBefore = stoneOf(playerId);
        // lumber_camp 1→2 级消耗石料 400，返还 60% = 240
        assertThat(stoneBefore).as("升级已扣掉 400 石料").isEqualTo(5000L - 400L);

        CityCancelResp resp = cityAppService.cancel(playerId,
                new CityCancelReq(newRequestId(), upgrade.buildingId()));

        assertThat(resp.refund()).anySatisfy(a -> {
            assertThat(a.type().name()).isEqualTo("STONE");
            assertThat(a.amount()).isEqualTo(240L);
        });
        assertThat(stoneOf(playerId)).isEqualTo(4600L + 240L);

        CityState city = cities.findByPlayerId(playerId).orElseThrow();
        assertThat(city.usedQueues()).as("取消后队列应释放").isZero();
        assertThat(city.building(upgrade.buildingId()).level()).as("取消不得保留等级").isZero();
    }

    @Test
    @DisplayName("cancel 同一 requestId 重放不会二次返还")
    void cancelIsIdempotent() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);
        CityCancelReq req = new CityCancelReq(newRequestId(), upgrade.buildingId());

        cityAppService.cancel(playerId, req);
        long stoneAfterFirst = stoneOf(playerId);

        assertThatThrownBy(() -> cityAppService.cancel(playerId, req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED);
        assertThat(stoneOf(playerId)).as("重放不得二次返还").isEqualTo(stoneAfterFirst);
    }

    // ---------- /city/collect ----------

    @Test
    @DisplayName("collect 在未到期时拒绝，不会把等级提前 +1")
    void collectRejectsBeforeFinish() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);

        assertThatThrownBy(() -> cityAppService.collect(playerId,
                new com.ironoath.web.dto.generated.CityCollectReq(newRequestId(), upgrade.buildingId())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.CITY_UPGRADING);

        assertThat(cities.findByPlayerId(playerId).orElseThrow()
                .building(upgrade.buildingId()).level()).isZero();
    }

    @Test
    @DisplayName("collect 不指定 buildingId 时返回空列表而不是报错（没有到点的建筑是正常状态）")
    void collectWithoutTargetReturnsEmpty() {
        String playerId = newPlayer();
        CityCollectResp resp = cityAppService.collect(playerId,
                new com.ironoath.web.dto.generated.CityCollectReq(newRequestId(), null));
        assertThat(resp.collected()).isEmpty();
        assertThat(resp.output()).isEmpty();
        assertThat(resp.serverNow()).isPositive();
    }

    @Test
    @DisplayName("产出建筑升级完成后，其每小时产量按 BUILDING_OUTPUT 曲线提升（验收5）")
    void outputRateRisesAfterUpgradeCompletes() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "lumber_camp", 1, 1);

        // 直接把完成时刻改到过去，模拟时间流逝（服务端不跑定时器，测试也不需要 sleep）
        CityState city = cities.findByPlayerId(playerId).orElseThrow();
        long version = cities.versionOf(playerId);
        city.building(upgrade.buildingId()).restore(
                0, 1, 1, com.ironoath.core.city.BuildingStatus.UPGRADING,
                1L, 0L, 1L, 1L, 0, 0L, 0L);
        cities.save(playerId, city, version);

        CityCollectResp collected = cityAppService.collect(playerId,
                new com.ironoath.web.dto.generated.CityCollectReq(newRequestId(), null));
        assertThat(collected.collected()).hasSize(1);
        assertThat(collected.collected().get(0).level()).isEqualTo(1);
        assertThat(collected.collected().get(0).status()).isEqualTo(BuildingStatus.IDLE);

        // 验收 5 的正题：产率必须真的提升，并且提升的量要与配置表手算值一致。
        // 伐木场底产 120、BUILDING_OUTPUT 指数 1.08 ⇒ 1 级产量 = 120 × 1^1.08 = 120；
        // 加上 resource 表的领地底产 200，WOOD 每小时应为 320。
        var wood = players.findByPlayerId(playerId).orElseThrow().resource("WOOD");
        assertThat(wood.perHour()).isEqualTo(320L);
        // 没建仓库 ⇒ 容量仍是 resource 表的 initCap，一点没变
        assertThat(wood.cap()).isEqualTo(20000L);
    }


    /**
     * 把升级工期改写为指定秒数。
     *
     * <p>必需的原因：1→2 级的工期只有 20~60 秒（B00 五分钟体验刻意让前期几乎瞬时完成），
     * 而加速请求动辄 300/3600 秒，会被 {@code speedUp} 正确截断到剩余时间。
     * 要验证「加速量、日限次、帮助上限」这些与工期长度无关的规则，必须先把工期拉长。
     * 服务端不跑定时器，所以测试也不需要 sleep —— 直接改完成时刻即可。
     */
    private void extendUpgrade(String playerId, String buildingId, long seconds) {
        CityState city = cities.findByPlayerId(playerId).orElseThrow();
        long version = cities.versionOf(playerId);
        var b = city.building(buildingId);
        // 完成时刻必须是「未来」：写成 0（epoch）会让下一次读取的 collectFinished 立刻把它收割掉，
        // 建筑回到 IDLE，后续加速就会被 CITY_UPGRADING 拒绝
        long now = System.currentTimeMillis();
        b.restore(b.level(), b.gridX(), b.gridY(),
                com.ironoath.core.city.BuildingStatus.UPGRADING, now + seconds * 1000L,
                now, seconds, seconds, b.helpCount(), b.lastMovedAt(),
                b.lastFinishedAt());
        cities.save(playerId, city, version);
    }

    // ---------- 互助真正落地（B03 §3 × B10 验收 6） ----------

    @Test
    @DisplayName("升级一开始就有求助请求，且带着能落地的 targetKey")
    void upgradeRegistersAHelpRequestWithTarget() {
        String owner = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(owner, "lumber_camp", 1, 1);

        var request = socialStore.helpRequests().stream()
                .filter(r -> owner.equals(r.fromPlayerId()))
                .findFirst().orElseThrow(() ->
                        new AssertionError("升级开始了却没有任何求助请求 —— 这正是「列表恒空」那种形状"));
        assertThat(request.kind()).isEqualTo(com.ironoath.web.dto.generated.HelpTargetKind.BUILDING.name());
        assertThat(request.targetKey())
                .as("没有 targetKey 的话，帮助找不到要加速的那栋楼，只能加计数与红点")
                .isEqualTo(upgrade.buildingId());
        assertThat(request.finishAt()).isEqualTo(upgrade.finishAt());
    }

    @Test
    @DisplayName("被帮一次，剩余时间真的变短；同一条请求不会被重复计")
    void beingHelpedShortensTheUpgradeForReal() {
        String owner = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(owner, "lumber_camp", 1, 1);
        long now = System.currentTimeMillis();
        long before = remainingOf(owner, upgrade.buildingId());

        String helper = newPlayer();
        String requestId = "help_" + owner + "_" + upgrade.buildingId() + "_" + upgrade.finishAt();
        com.ironoath.web.dto.generated.HelpResp first = social.help(helper, requestId, now);

        assertThat(first.helped()).as("升级路径已经登记过请求，这里应当恰好帮到 1 条").isEqualTo(1);
        assertThat(remainingOf(owner, upgrade.buildingId()))
                .as("帮助必须真的压缩完成时刻 —— 只记账不落地的帮助是个假承诺")
                .isLessThan(before);
        assertThat(social.help(helper, requestId, now).helped())
                .as("同一条请求不能被同一个人帮第二次").isZero();
    }

    @Test
    @DisplayName("取消升级后，求助请求从别人的可帮列表里消失（否则别人会白耗一次每日额度）")
    void cancellingUpgradeWithdrawsItsHelpRequest() {
        String owner = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(owner, "lumber_camp", 1, 1);
        assertThat(socialStore.helpRequests().stream()
                .filter(r -> owner.equals(r.fromPlayerId())).count())
                .as("升级开始了就该有一条求助请求").isEqualTo(1);

        cityAppService.cancel(owner, new CityCancelReq(newRequestId(), upgrade.buildingId()));

        assertThat(socialStore.helpRequests().stream()
                .filter(r -> owner.equals(r.fromPlayerId())).count())
                .as("取消后请求还留着，别人帮它只会扣掉自己的额度").isZero();
    }

    @Test
    @DisplayName("升级完成推进任务进度（B12 §1：事件在收割那一刻发布，不看是谁触发的）")
    void completedUpgradeAdvancesTheQuest() {
        String playerId = newPlayer();
        CityUpgradeResp upgrade = startUpgrade(playerId, "main_city", 3, 3);
        // 把完成时刻改到过去：结算会把它收割掉，而事件就在那一刻发
        CityState city = cities.findByPlayerId(playerId).orElseThrow();
        long version = cities.versionOf(playerId);
        var b = city.building(upgrade.buildingId());
        b.restore(b.level(), b.gridX(), b.gridY(),
                com.ironoath.core.city.BuildingStatus.UPGRADING, 1L,
                0L, 1L, 1L, b.helpCount(), b.lastMovedAt(), b.lastFinishedAt());
        cities.save(playerId, city, version);
        cityAppService.list(playerId);   // 读一次城：结算 → 收割 → 发布事件

        var quest = quests.list(playerId).quests().stream()
                .filter(q -> q.questId().equals("quest_main_01")).findFirst().orElseThrow();
        assertThat(quest.current()).as("主线首条「主城升到 2 级」：完成一次升级就 +1")
                .isEqualTo(1L);
        assertThat(quest.complete()).as("目标值是 2，所以还差一次").isFalse();
    }

    // ---------- 辅助 ----------

    /** 某建筑此刻的剩余秒数。读仓储而不是手里那份 —— 读端口给的是副本。 */
    private long remainingOf(String playerId, String buildingId) {
        return cities.findByPlayerId(playerId).orElseThrow()
                .building(buildingId).remainingSeconds(System.currentTimeMillis());
    }

    private long goldOf(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow().resource("GOLD").current();
    }

    private long stoneOf(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow().resource("STONE").current();
    }

    private void drainGold(String playerId) {
        var save = players.findByPlayerId(playerId).orElseThrow();
        var gold = save.resource("GOLD");
        save.putResource("GOLD", new com.ironoath.core.player.PlayerResourceState(
                0L, gold.cap(), gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        players.save(save);
    }
}
