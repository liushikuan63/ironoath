package com.ironoath.web;

import com.ironoath.common.BizException;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.dto.generated.CityCollectReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.PlayerInitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.BuildingInstance;
import com.ironoath.web.config.ClockSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.junit.jupiter.api.BeforeEach;
import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 一键收割的门槛必须由真实生产服务检查，而不是由客户端是否画按钮决定。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(CityHarvestLearningTest.ControlledClock.class)
class CityHarvestLearningTest {
    @Autowired private CityAppService city;
    @Autowired private PlayerInitService init;
    @Autowired private PlayerRepository players;
    @Autowired private CityRepository cities;
    @Autowired private com.ironoath.web.pay.PaidProducts catalog;
    @TestConfiguration
    static class ControlledClock {
        static final AtomicLong NOW = new AtomicLong(1_790_000_000_000L);
        @Bean @Primary ClockSource harvestClock() { return NOW::get; }
    }
    @BeforeEach void resetClock() { ControlledClock.NOW.set(1_790_000_000_000L); }

    /** 时间及建筑是测试前提；生产判定、读取、手动写入与幂等均调用真实服务。 */
    private String prepareResource(String id, String config, int x) {
        city.list(id);
        var state = cities.findByPlayerId(id).orElseThrow();
        String buildingId = "building-" + UUID.randomUUID();
        state.restoreBuilding(new BuildingInstance(buildingId, config, 1, x, 0));
        cities.save(id, state, cities.versionOf(id));
        return buildingId;
    }

    private void setMainLevel(String id, int level) {
        var player = players.findByPlayerId(id).orElseThrow();
        player.setCityLevel(level); players.save(player);
    }

    private String newPlayer() {
        return init.init(new PlayerInitReq("req-" + UUID.randomUUID(),
                "dev-" + UUID.randomUUID(), "资源学习测试", System.currentTimeMillis(), "")).playerId();
    }

    @Test
    void freshAccountCannotCollectAll() {
        String id = newPlayer();
        assertThatThrownBy(() -> city.collect(id, new CityCollectReq("req-" + UUID.randomUUID(), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("主城").hasMessageContaining("12");
    }

    @Test
    void levelTwelveAloneDoesNotBypassManualResourceLearning() {
        String id = newPlayer();
        var player = players.findByPlayerId(id).orElseThrow();
        player.setCityLevel(12);
        players.save(player);
        assertThatThrownBy(() -> city.collect(id, new CityCollectReq("req-" + UUID.randomUUID(), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("手动");
    }

    @Test void allFourManualCollectionsAndMainTwelveAreBothRequired() {
        String id = newPlayer(); setMainLevel(id, 4);
        String[] configs = {"lumber_camp", "quarry", "iron_mine", "farm"};
        String[] types = {"WOOD", "STONE", "IRON", "GRAIN"};
        for (int i = 0; i < configs.length; i++) {
            final String resourceType = types[i];
            String target = prepareResource(id, configs[i], i);
            ControlledClock.NOW.addAndGet(60_000L);
            // 先读取已经入账的真实产出，不能因此丢失手动教学的可确认状态。
            city.list(id);
            assertThat(city.harvestLearning(id).resources().stream()
                    .filter(r -> r.type().name().equals(resourceType)).findFirst().orElseThrow().collectableBuildingId())
                    .isEqualTo(target);
            var before = players.findByPlayerId(id).orElseThrow().resource(types[i]).current();
            String request = "req-" + UUID.randomUUID();
            city.collect(id, new CityCollectReq(request, target));
            assertThat(players.findByPlayerId(id).orElseThrow().resource(types[i]).current()).isEqualTo(before);
            assertThat(players.findByPlayerId(id).orElseThrow().manuallyCollected()).contains(types[i]);
            assertThatThrownBy(() -> city.collect(id, new CityCollectReq(request, target))).isInstanceOf(BizException.class);
            assertThat(city.harvestLearning(id).collectAllUnlocked()).isFalse();
        }
        assertThatThrownBy(() -> city.collect(id, new CityCollectReq("req-" + UUID.randomUUID(), null)))
                .hasMessageContaining("12");
        setMainLevel(id, 11);
        assertThat(city.harvestLearning(id).collectAllUnlocked()).isFalse();
        setMainLevel(id, 12);
        assertThat(city.harvestLearning(id).collectAllUnlocked()).isTrue();
        city.collect(id, new CityCollectReq("req-" + UUID.randomUUID(), null));
        assertThat(players.findByPlayerId(id).orElseThrow().manuallyCollected()).containsExactlyInAnyOrder(types);
    }

    @Test void zeroOutputAndInvalidTargetCannotCountAsManualCollection() {
        String id = newPlayer(); String target = prepareResource(id, "lumber_camp", 0);
        city.collect(id, new CityCollectReq("req-" + UUID.randomUUID(), target));
        assertThat(players.findByPlayerId(id).orElseThrow().manuallyCollected()).isEmpty();
        assertThatThrownBy(() -> city.collect(id, new CityCollectReq("req-" + UUID.randomUUID(), "missing-building")))
                .isInstanceOf(RuntimeException.class);
        assertThat(players.findByPlayerId(id).orElseThrow().manuallyCollected()).isEmpty();
    }

    @Test void oneManualResourceAtLevelTwelveStillCannotCollectAll() {
        String id = newPlayer(); setMainLevel(id, 12);
        String target = prepareResource(id, "lumber_camp", 0);
        ControlledClock.NOW.addAndGet(60_000L);
        city.list(id); city.collect(id, new CityCollectReq("req-" + UUID.randomUUID(), target));
        assertThat(players.findByPlayerId(id).orElseThrow().manuallyCollected()).containsExactly("WOOD");
        assertThat(city.harvestLearning(id).collectAllUnlocked()).isFalse();
        assertThatThrownBy(() -> city.collect(id, new CityCollectReq("req-" + UUID.randomUUID(), null)))
                .hasMessageContaining("石料").hasMessageContaining("铁矿").hasMessageContaining("粮草");
    }

    @Test void responseExplainsSixResourcesButOnlyFourNeedManualCollection() {
        var resp = city.harvestLearning(newPlayer());
        assertThat(resp.resources()).hasSize(6);
        assertThat(resp.resources().stream().filter(r -> r.manualRequired()).count()).isEqualTo(4);
        assertThat(resp.resources()).allSatisfy(r -> {
            assertThat(r.name()).isNotBlank().isNotEqualTo(r.type().name());
            assertThat(r.description()).isNotBlank();
        });
        assertThat(resp.requiredMainLevel()).isEqualTo(12);
    }

    private void monthlyEntitlementFixture(String uid,boolean material,boolean expired) {
        var kind=material?com.ironoath.config.cfg.PayProductCfg.Kind.MONTHLY_MATERIAL_CARD:com.ironoath.config.cfg.PayProductCfg.Kind.MONTHLY_GOLD_CARD;
        var row=catalog.requireKind(kind);long now=ControlledClock.NOW.get();long start=expired?now-31*86_400_000L:now-60_000;
        var period=new com.ironoath.core.pay.MonthlyCardPurchase("harvest-order-"+UUID.randomUUID(),row.id(),catalog.cardTerms(row),start,start+30*86_400_000L,1,0);
        var player=players.findByPlayerId(uid).orElseThrow();player.setPaid(com.ironoath.core.player.PlayerPaid.empty().withMonthlyPurchase(period));players.save(player);
    }
    private void actuallyTeachAllFour(String uid) {
        var config=com.ironoath.config.ConfigRegistry.loadFromDirectory(configDirForFixture());int x=10;
        for(String type:java.util.List.of("WOOD","STONE","IRON","GRAIN")){
            String building=config.all(com.ironoath.config.cfg.BuildingCfg.class).stream().filter(b->type.equals(b.outputResource())).findFirst().orElseThrow().id();
            String target=prepareResource(uid,building,x++);ControlledClock.NOW.addAndGet(60_000);city.list(uid);city.collect(uid,new CityCollectReq("req-"+UUID.randomUUID(),target));
        }
        assertThat(players.findByPlayerId(uid).orElseThrow().manuallyCollected()).containsExactlyInAnyOrder("WOOD","STONE","IRON","GRAIN");
    }
    private java.nio.file.Path configDirForFixture(){var p=java.nio.file.Path.of("").toAbsolutePath();while(p!=null){if(java.nio.file.Files.isDirectory(p.resolve("contract/config")))return p.resolve("contract/config");p=p.getParent();}throw new IllegalStateException();}
    @Test void activeMaterialCardBypassesOnlyLevelAndStillNeedsAllFourActualManualFacts() {
        String uid=newPlayer();monthlyEntitlementFixture(uid,true,false);
        assertThat(city.harvestLearning(uid).collectAllUnlocked()).isFalse();assertThatThrownBy(()->city.collect(uid,new CityCollectReq("req-"+UUID.randomUUID(),null))).isInstanceOf(BizException.class).hasMessageContaining("手动");
        var player=players.findByPlayerId(uid).orElseThrow();var baseline=player.copy();baseline.setPaid(com.ironoath.core.player.PlayerPaid.empty());
        assertThat(city.cityRules(player,ControlledClock.NOW.get()).maxQueueCount()).isEqualTo(city.cityRules(baseline,ControlledClock.NOW.get()).maxQueueCount());
        assertThat(city.cityRules(player,ControlledClock.NOW.get()).adSpeedupDailyLimit()).isEqualTo(city.cityRules(baseline,ControlledClock.NOW.get()).adSpeedupDailyLimit());
        actuallyTeachAllFour(uid);assertThat(city.harvestLearning(uid).collectAllUnlocked()).isTrue();city.collect(uid,new CityCollectReq("req-"+UUID.randomUUID(),null));
    }
    @Test void pureGoldCardAndExpiredMaterialCardCannotBypassFreeMainLevel() {
        String uid=newPlayer();monthlyEntitlementFixture(uid,false,false);actuallyTeachAllFour(uid);assertThat(city.harvestLearning(uid).collectAllUnlocked()).isFalse();
        assertThatThrownBy(()->city.collect(uid,new CityCollectReq("req-"+UUID.randomUUID(),null))).hasMessageContaining("12");
        monthlyEntitlementFixture(uid,true,true);assertThat(city.harvestLearning(uid).collectAllUnlocked()).isFalse();
        assertThatThrownBy(()->city.collect(uid,new CityCollectReq("req-"+UUID.randomUUID(),null))).hasMessageContaining("12");
        setMainLevel(uid,12);assertThat(city.harvestLearning(uid).collectAllUnlocked()).isTrue();
    }
}
