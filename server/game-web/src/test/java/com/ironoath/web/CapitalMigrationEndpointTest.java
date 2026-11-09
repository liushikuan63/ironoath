package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.NationCapitalMigrationReq;
import com.ironoath.web.dto.generated.NationAppointReq;
import com.ironoath.web.dto.generated.NationFoundReq;
import com.ironoath.web.dto.generated.NationOffice;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.WorldTargets;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemoryNationStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemorySocialStore;
import com.ironoath.web.world.TargetOccupationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class CapitalMigrationEndpointTest {
    private static final String PLAYER_HEADER = "X-Player-Id";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlayerInitService playerInitService;
    @SpyBean private PlayerRepository players;
    @Autowired private InMemorySocialStore social;
    @Autowired private InMemoryNationStore nations;
    @Autowired private InMemoryPlayerStore playerStore;
    @Autowired private BotRegistry bots;
    @Autowired private WorldRepository world;
    @Autowired private com.ironoath.web.service.WorldAppService worlds;
    @Autowired private com.ironoath.config.ConfigRegistry configs;
    @Autowired private com.ironoath.common.time.TimeService time;
    @Autowired private MarchRepository marches;
    @Autowired private TargetOccupationStore occupations;
    @Autowired private NationStore nationStore;

    private int allianceSeq;

    @BeforeEach
    void resetStores() {
        playerStore.clear();
        social.clear();
        nations.clear();
        ((InMemoryMarchStore) marches).clear();
        bots.clear();
        allianceSeq = 0;
    }

    @AfterEach
    void clearCapitalGarrisonFixture() {
        String capitalId = capitalTargetId();
        TargetOccupationStore.Snapshot existing = occupations.findByTargetIds(List.of(capitalId)).get(capitalId);
        if (existing != null && existing.garrison() != null
                && existing.garrison().kind() == TargetOccupationStore.Garrison.Kind.STATIONED) {
            var cleared = new TargetOccupationStore.Snapshot(existing.targetId(), existing.kind(), existing.x(),
                    existing.y(), existing.nationId(), existing.nationName(), existing.capturedAt(),
                    "migration-test-cleanup-" + UUID.randomUUID(), existing.version() + 1,
                    TargetOccupationStore.Garrison.empty(), existing.ownershipHistory());
            occupations.compareAndSet(cleared, existing.version());
        }
    }

    @Test
    @DisplayName("迁都端点已接线：没有国家时明确拒绝，不落坐标")
    void migrationRouteIsConnectedAndRejectsAnActorWithoutANation() throws Exception {
        JsonNode root = postRaw("/nation/capital/migrate", "b13-no-nation",
                new NationCapitalMigrationReq("b13-no-nation-move", 300L, 300L));

        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.NATION_NOT_FOUND.code());
    }

    @Test
    @DisplayName("迁都请求缺坐标或带未声明字段时拒绝，缺失数值不得被primitive默认成0")
    void migrationRequestEnforcesRequiredCoordinatesAndSchemaFields() throws Exception {
        JsonNode missingX = postJsonRaw("/nation/capital/migrate", "b13-no-nation",
                "{\"requestId\":\"" + newRequestId() + "\",\"y\":300}");
        assertThat(missingX.get("code").asInt()).isEqualTo(ErrorCode.PARAM_INVALID.code());

        JsonNode extraField = postJsonRaw("/nation/capital/migrate", "b13-no-nation",
                "{\"requestId\":\"" + newRequestId() + "\",\"x\":300,\"y\":300,\"fee\":1}");
        assertThat(extraField.get("code").asInt()).isEqualTo(ErrorCode.PARAM_INVALID.code());
    }

    @Test
    @DisplayName("国王可免费连续迁都：返回新坐标，国库与国家成员历史不变")
    void kingCanMoveRepeatedlyWithoutFeeOrCooldownAndKeepsNationHistory() throws Exception {
        Kingdom kingdom = kingdom();
        Nation before = nationStore.findById(kingdom.nationId()).orElseThrow();
        long treasury = before.treasury();
        var history = before.allianceMembershipPeriods(kingdom.allianceId());
        var balances = resourceBalances(players.findByPlayerId(kingdom.king()).orElseThrow());
        Coord first = freeCorner();
        Coord second = freeCorner(first);

        JsonNode moved = post200("/nation/capital/migrate", kingdom.king(),
                new NationCapitalMigrationReq(newRequestId(), first.x(), first.y()));
        assertThat(moved.get("capitalX").asLong()).isEqualTo(first.x());
        assertThat(moved.get("capitalY").asLong()).isEqualTo(first.y());

        JsonNode movedAgain = post200("/nation/capital/migrate", kingdom.king(),
                new NationCapitalMigrationReq(newRequestId(), second.x(), second.y()));
        assertThat(movedAgain.get("capitalX").asLong()).isEqualTo(second.x());
        assertThat(movedAgain.get("capitalY").asLong()).isEqualTo(second.y());

        Nation after = nationStore.findById(kingdom.nationId()).orElseThrow();
        assertThat(after.treasury()).isEqualTo(treasury);
        assertThat(after.allianceMembershipPeriods(kingdom.allianceId())).isEqualTo(history);
        assertThat(resourceBalances(players.findByPlayerId(kingdom.king()).orElseThrow())).isEqualTo(balances);
    }

    @Test
    @DisplayName("坐标只接受地图内未被城市或保留战略目标占用的格子")
    void coordinateMustBeInsideWorldAndFreeOfCitiesAndStrategicTargets() throws Exception {
        Kingdom kingdom = kingdom();
        Nation original = nationStore.findById(kingdom.nationId()).orElseThrow();

        assertRejectedCoordinate(kingdom.king(), -1L, 0L);
        long worldSize = configs.longParam("WORLD_SIZE");
        assertRejectedCoordinate(kingdom.king(), worldSize, worldSize - 1L);
        Coord city = worlds.homeOf(kingdom.king());
        assertRejectedCoordinate(kingdom.king(), city.x(), city.y());
        Coord reserved = WorldTargets.from(configs).targets().get(0).coord();
        assertRejectedCoordinate(kingdom.king(), reserved.x(), reserved.y());

        Nation unchanged = nationStore.findById(kingdom.nationId()).orElseThrow();
        assertThat(unchanged.capitalX()).isEqualTo(original.capitalX());
        assertThat(unchanged.capitalY()).isEqualTo(original.capitalY());
    }

    @Test
    @DisplayName("普通国家成员不能代行迁都")
    void memberWithoutAnOfficeCannotMoveCapital() throws Exception {
        Kingdom kingdom = kingdom();
        JsonNode denied = postRaw("/nation/capital/migrate", kingdom.mate(),
                new NationCapitalMigrationReq(newRequestId(), 300L, 300L));

        assertThat(denied.get("code").asInt()).isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.code());
        Nation unchanged = nationStore.findById(kingdom.nationId()).orElseThrow();
        assertThat(unchanged.capitalX()).isEqualTo(100L);
        assertThat(unchanged.capitalY()).isEqualTo(200L);
    }

    @Test
    @DisplayName("迁都成功后重放相同requestId被拒，不再次应用不同坐标")
    void duplicateRequestIdCannotApplyASecondDestination() throws Exception {
        Kingdom kingdom = kingdom();
        String requestId = newRequestId();
        Coord first = freeCorner();
        Coord second = freeCorner(first);
        post200("/nation/capital/migrate", kingdom.king(),
                new NationCapitalMigrationReq(requestId, first.x(), first.y()));

        JsonNode duplicate = postRaw("/nation/capital/migrate", kingdom.king(),
                new NationCapitalMigrationReq(requestId, second.x(), second.y()));

        assertThat(duplicate.get("code").asInt()).isEqualTo(ErrorCode.REQUEST_DUPLICATED.code());
        Nation unchanged = nationStore.findById(kingdom.nationId()).orElseThrow();
        assertThat(unchanged.capitalX()).isEqualTo(first.x());
        assertThat(unchanged.capitalY()).isEqualTo(first.y());
    }

    @Test
    @DisplayName("官员代行遇到缺失、0、未来、读取失败或未满一周的国王时间戳都关闭")
    void officerDelegationFailsClosedForInvalidKingLoginFacts() throws Exception {
        Kingdom kingdom = kingdom();
        post200("/nation/appoint", kingdom.king(), new NationAppointReq(
                newRequestId(), kingdom.mate(), NationOffice.MINISTER));
        long now = time.serverNow();

        stubKingLogin(kingdom.king(), OptionalLong.empty());
        assertOfficerDenied(kingdom.mate());
        stubKingLogin(kingdom.king(), OptionalLong.of(0L));
        assertOfficerDenied(kingdom.mate());
        stubKingLogin(kingdom.king(), OptionalLong.of(now + 1L));
        assertOfficerDenied(kingdom.mate());
        stubKingLogin(kingdom.king(), OptionalLong.of(now - 24L * 60L * 60L * 1_000L));
        assertOfficerDenied(kingdom.mate());
        doThrow(new IllegalStateException("test read failure")).when(players).findByPlayerId(kingdom.king());
        assertOfficerDenied(kingdom.mate());
    }

    @Test
    @DisplayName("国王离线满一周时官员可立即迁都；国王本人的权限不依赖最近登录读取")
    void officerMayMoveAfterSevenDaysAndKingBypassesLoginRead() throws Exception {
        Kingdom kingdom = kingdom();
        post200("/nation/appoint", kingdom.king(), new NationAppointReq(
                newRequestId(), kingdom.mate(), NationOffice.MINISTER));
        long now = time.serverNow();
        stubKingLogin(kingdom.king(), OptionalLong.of(now - 7L * 24L * 60L * 60L * 1_000L));

        Coord destination = freeCorner();
        JsonNode officerMove = post200("/nation/capital/migrate", kingdom.mate(),
                new NationCapitalMigrationReq(newRequestId(), destination.x(), destination.y()));
        assertThat(officerMove.get("capitalX").asLong()).isEqualTo(destination.x());
        assertThat(officerMove.get("capitalY").asLong()).isEqualTo(destination.y());

        doThrow(new IllegalStateException("King login read must be bypassed")).when(players)
                .findByPlayerId(kingdom.king());
        Coord kingDestination = freeCorner(destination);
        JsonNode kingMove = post200("/nation/capital/migrate", kingdom.king(),
                new NationCapitalMigrationReq(newRequestId(), kingDestination.x(), kingDestination.y()));
        assertThat(kingMove.get("capitalX").asLong()).isEqualTo(kingDestination.x());
        assertThat(kingMove.get("capitalY").asLong()).isEqualTo(kingDestination.y());
    }

    @Test
    @DisplayName("旧都驻军尚未撤防时迁都被拒，且坐标与驻军回执不变")
    void stationedGarrisonBlocksMigrationWithoutChangingCoordinatesOrOwnership() throws Exception {
        Kingdom kingdom = kingdom();
        String targetId = capitalTargetId();
        var stationed = new TargetOccupationStore.Snapshot(targetId, WorldGenerator.EntityType.CAPITAL,
                100, 200, kingdom.nationId(), "迁都测试国", 1L, "migration-garrison-fixture", 1L,
                TargetOccupationStore.Garrison.stationed("migration-garrison-march"));
        assertThat(occupations.compareAndSet(stationed, 0L)).isTrue();

        JsonNode denied = postRaw("/nation/capital/migrate", kingdom.king(),
                new NationCapitalMigrationReq(newRequestId(), freeCorner().x(), freeCorner().y()));

        assertThat(denied.get("code").asInt()).isEqualTo(ErrorCode.MARCH_STATE_INVALID.code());
        Nation unchanged = nationStore.findById(kingdom.nationId()).orElseThrow();
        assertThat(unchanged.capitalX()).isEqualTo(100L);
        assertThat(unchanged.capitalY()).isEqualTo(200L);
        assertThat(occupations.findByTargetIds(List.of(targetId)).get(targetId).garrison())
                .isEqualTo(stationed.garrison());
    }

    @Test
    @DisplayName("已发起但未结算的王城攻击行军阻止迁都")
    void inFlightCapitalAttackBlocksMigrationUntilItSettlesOrReturns() throws Exception {
        Kingdom kingdom = kingdom();
        long now = 1_800_000_000_000L;
        March attack = new March("migration-attack-fixture", kingdom.mate(), Coord.of(10, 10),
                Coord.of(100, 200), now, now + 60_000L, Map.of("unit_infantry_t1", 1L), List.of(),
                0L, 1, March.TargetType.STRATEGIC_TARGET, capitalTargetId(), March.Action.ATTACK);
        assertThat(marches.insertIfAbsent(attack)).isTrue();

        JsonNode denied = postRaw("/nation/capital/migrate", kingdom.king(),
                new NationCapitalMigrationReq(newRequestId(), freeCorner().x(), freeCorner().y()));

        assertThat(denied.get("code").asInt()).isEqualTo(ErrorCode.MARCH_STATE_INVALID.code());
        Nation unchanged = nationStore.findById(kingdom.nationId()).orElseThrow();
        assertThat(unchanged.capitalX()).isEqualTo(100L);
        assertThat(unchanged.capitalY()).isEqualTo(200L);
    }

    @Test
    @DisplayName("新都候选格已有在途行军时迁都拒绝，避免把行军已锁定的目标改成王城")
    void inFlightMarchToTheDestinationPreventsChangingItsMapMeaning() throws Exception {
        Kingdom kingdom = kingdom();
        Coord destination = freeCorner();
        long now = 1_800_000_000_000L;
        March march = new March("migration-destination-march", kingdom.mate(), Coord.of(10, 10), destination,
                now, now + 60_000L, Map.of("unit_infantry_t1", 1L), List.of(),
                0L, 1, March.TargetType.MONSTER, "monster_1", March.Action.ATTACK);
        assertThat(marches.insertIfAbsent(march)).isTrue();

        JsonNode denied = postRaw("/nation/capital/migrate", kingdom.king(),
                new NationCapitalMigrationReq(newRequestId(), destination.x(), destination.y()));

        assertThat(denied.get("code").asInt()).isEqualTo(ErrorCode.MARCH_STATE_INVALID.code());
        Nation unchanged = nationStore.findById(kingdom.nationId()).orElseThrow();
        assertThat(unchanged.capitalX()).isEqualTo(100L);
        assertThat(unchanged.capitalY()).isEqualTo(200L);
    }

    private void assertRejectedCoordinate(String playerId, long x, long y) throws Exception {
        JsonNode rejected = postRaw("/nation/capital/migrate", playerId,
                new NationCapitalMigrationReq(newRequestId(), x, y));
        assertThat(rejected.get("code").asInt()).isEqualTo(ErrorCode.WORLD_TARGET_INVALID.code());
    }

    private void assertOfficerDenied(String officerId) throws Exception {
        JsonNode denied = postRaw("/nation/capital/migrate", officerId,
                new NationCapitalMigrationReq(newRequestId(), freeCorner().x(), freeCorner().y()));
        assertThat(denied.get("code").asInt()).isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.code());
    }

    private void stubKingLogin(String kingId, OptionalLong lastLoginAt) {
        if (lastLoginAt.isEmpty()) {
            doReturn(Optional.empty()).when(players).findByPlayerId(kingId);
            return;
        }
        PlayerSave profile = mock(PlayerSave.class);
        when(profile.lastLoginAt()).thenReturn(lastLoginAt.getAsLong());
        doReturn(Optional.of(profile)).when(players).findByPlayerId(kingId);
    }

    private String capitalTargetId() {
        return WorldTargets.from(configs).targets().stream()
                .filter(target -> target.definition().kind() == com.ironoath.config.cfg.WorldTargetCfg.Kind.CAPITAL)
                .findFirst().orElseThrow().definition().id();
    }

    private Coord freeCorner() {
        return freeCorner(null);
    }

    private Coord freeCorner(Coord exclude) {
        int last = Math.toIntExact(configs.longParam("WORLD_SIZE") - 1L);
        return List.of(Coord.of(0, 0), Coord.of(last, last), Coord.of(0, last), Coord.of(last, 0),
                        Coord.of(1, 1), Coord.of(last - 1, last - 1))
                .stream().filter(coord -> !coord.equals(exclude))
                .filter(coord -> !WorldTargets.from(configs).reserved(coord))
                .filter(coord -> world.cityAt(coord).isEmpty())
                .findFirst().orElseThrow(() -> new AssertionError("测试地图边角没有可用迁都格"));
    }

    private Kingdom kingdom() throws Exception {
        String king = newPlayer(16);
        String mate = newPlayer(16);
        String allianceId = createAlliance(king, mate);
        String nationId = post200("/nation/found", king,
                new NationFoundReq(newRequestId(), "迁都测试国", 100L, 200L))
                .get("nation").get("nationId").asText();
        return new Kingdom(king, mate, allianceId, nationId);
    }

    private String createAlliance(String king, String mate) throws Exception {
        allianceSeq++;
        String allianceId = post200("/alliance/create", king,
                new AllianceCreateReq(newRequestId(), "迁都联盟" + allianceSeq,
                        String.format("M%03d", allianceSeq)))
                .get("alliance").get("id").asText();
        post200("/alliance/apply", mate, new AllianceIdReq(newRequestId(), allianceId));
        post200("/alliance/review", king, new AllianceReviewReq(newRequestId(), mate, true));
        return allianceId;
    }

    private String newPlayer(int cityLevel) {
        String playerId = playerInitService.init(new PlayerInitReq(newRequestId(),
                "dev-" + UUID.randomUUID(), "迁都测试", 1_700_000_000_000L, "")).playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(cityLevel);
        PlayerResourceState gold = save.resources().get("GOLD");
        if (gold != null) save.putResource("GOLD", new PlayerResourceState(
                100_000L, gold.cap(), gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        players.save(save);
        return playerId;
    }

    private JsonNode post200(String path, String playerId, Object request) throws Exception {
        return okData(postRaw(path, playerId, request));
    }

    private JsonNode postRaw(String path, String playerId, Object request) throws Exception {
        MvcResult result = mockMvc.perform(post(path).header(PLAYER_HEADER, playerId)
                        .contentType(MediaType.APPLICATION_JSON).content(JsonUtils.toJson(request)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode postJsonRaw(String path, String playerId, String json) throws Exception {
        MvcResult result = mockMvc.perform(post(path).header(PLAYER_HEADER, playerId)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode okData(JsonNode root) {
        assertThat(root.get("code").asInt()).isZero();
        return root.get("data");
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private static Map<String, Long> resourceBalances(PlayerSave save) {
        java.util.Map<String, Long> balances = new java.util.LinkedHashMap<>();
        save.resources().forEach((id, value) -> balances.put(id, value.current()));
        return Map.copyOf(balances);
    }

    private record Kingdom(String king, String mate, String allianceId, String nationId) {}
}
