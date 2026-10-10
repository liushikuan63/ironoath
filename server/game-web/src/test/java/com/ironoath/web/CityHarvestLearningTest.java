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
                .isInstanceOf(BizException.class).hasMessageContaining("主城").hasMessageContaining("5");
    }

    @Test
    void levelFiveAloneDoesNotBypassManualResourceLearning() {
        String id = newPlayer();
        var player = players.findByPlayerId(id).orElseThrow();
        player.setCityLevel(5);
        players.save(player);
        assertThatThrownBy(() -> city.collect(id, new CityCollectReq("req-" + UUID.randomUUID(), null)))
                .isInstanceOf(BizException.class).hasMessageContaining("手动");
    }

    @Test void allFourManualCollectionsAndMainFiveAreBothRequired() {
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
                .hasMessageContaining("5");
        setMainLevel(id, 5);
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

    @Test void oneManualResourceAtLevelFiveStillCannotCollectAll() {
        String id = newPlayer(); setMainLevel(id, 5);
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
        assertThat(resp.requiredMainLevel()).isEqualTo(5);
    }
}
