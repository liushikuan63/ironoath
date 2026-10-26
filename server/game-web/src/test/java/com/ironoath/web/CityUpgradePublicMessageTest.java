package com.ironoath.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.config.GameProperties;
import com.ironoath.web.controller.CityController;
import com.ironoath.web.dto.generated.CityUpgradeReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.web.GlobalExceptionHandler;
import com.ironoath.web.web.ResultBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Production redaction contract exercised through real city service/controller and MVC advice. */
@SpringBootTest
@ActiveProfiles("test")
@Import(CityUpgradePublicMessageTest.FixedTime.class)
class CityUpgradePublicMessageTest {
    private static final long NOW = 1_788_000_000_000L;

    @Autowired private CityAppService city;
    @Autowired private PlayerInitService init;
    @Autowired private PlayerRepository players;
    @Autowired private CityRepository cities;
    @Autowired private TimeService time;
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;

    @TestConfiguration
    static class FixedTime {
        @Bean @Primary
        TimeService cityPrerequisiteTime() { return new TimeService(() -> NOW); }
    }

    @BeforeEach
    void setUp() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
        mvc = mvcFor(city);
    }

    @Test
    void requiredAndCurrentLevelsRemainPublicWhenDiagnosticDetailIsDisabled() throws Exception {
        String playerId = newPlayer();
        JsonNode body = upgrade(mvc, playerId, request());

        assertThat(body.path("code").asInt()).isEqualTo(ErrorCode.CITY_MAIN_LEVEL_LOW.code());
        assertThat(body.path("msg").asText())
                .contains("主城 5 级", "主城 1 级")
                .doesNotContain("stable", "configId", playerId);
        assertNoDetail(body);
        assertThat(body.path("serverNow").asLong()).isEqualTo(NOW);
    }

    @Test
    void anOrdinaryExceptionWithTheSameCodeCannotPublishInternalDetail() throws Exception {
        CityAppService otherPath = mock(CityAppService.class);
        when(otherPath.upgrade(anyString(), any(CityUpgradeReq.class)))
                .thenThrow(new BizException(ErrorCode.CITY_MAIN_LEVEL_LOW,
                        "configId=private-row playerId=private-player need=999 current=111"));
        JsonNode body = upgrade(mvcFor(otherPath), "fixture-only", request());

        assertThat(body.path("code").asInt()).isEqualTo(ErrorCode.CITY_MAIN_LEVEL_LOW.code());
        assertThat(body.path("msg").asText()).isEqualTo(ErrorCode.CITY_MAIN_LEVEL_LOW.msg());
        assertThat(body.path("msg").asText()).doesNotContain("private", "999", "111");
        assertNoDetail(body);
    }

    @Test
    void prerequisiteFailureDoesNotSpendOrReserveAndTheSameRequestCanRetry() throws Exception {
        String playerId = newPlayer();
        JsonNode before = json.valueToTree(players.findByPlayerId(playerId).orElseThrow().resources());
        CityUpgradeReq request = request();

        for (int retry = 0; retry < 2; retry++) {
            JsonNode body = upgrade(mvc, playerId, request);
            assertThat(body.path("code").asInt()).isEqualTo(ErrorCode.CITY_MAIN_LEVEL_LOW.code());
            assertNoDetail(body);
        }
        JsonNode after = json.valueToTree(players.findByPlayerId(playerId).orElseThrow().resources());
        assertThat(after).isEqualTo(before);
        CityState saved = cities.findByPlayerId(playerId).orElseThrow();
        assertThat(saved.usedQueues()).isZero();
        assertThat(saved.findByConfigId("stable")).isNull();
    }

    private MockMvc mvcFor(CityAppService service) {
        return MockMvcBuilders.standaloneSetup(new CityController(service))
                .setControllerAdvice(new GlobalExceptionHandler(),
                        new ResultBodyAdvice(time, new GameProperties("contract/config", "memory", false)))
                .build();
    }

    private JsonNode upgrade(MockMvc target, String playerId, CityUpgradeReq request) throws Exception {
        String body = target.perform(post("/city/upgrade")
                        .header(CityController.PLAYER_HEADER, playerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    private String newPlayer() {
        String id = UUID.randomUUID().toString();
        return init.init(new PlayerInitReq("req-" + id, "device-" + id,
                "前置等级测试", NOW, "")).playerId();
    }

    private CityUpgradeReq request() {
        return new CityUpgradeReq("req-" + UUID.randomUUID(), "stable", 1, 2);
    }

    private static void assertNoDetail(JsonNode body) {
        assertThat(body.path("detail").isNull() || body.path("detail").isMissingNode()).isTrue();
    }
}
