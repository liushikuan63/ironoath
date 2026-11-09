package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.core.nation.WarAwardPlan;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.NationCapitalMigrationReq;
import com.ironoath.web.dto.generated.NationFoundReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.nation.WarStore;
import com.ironoath.web.service.WarCapitalTargetResolver;
import com.ironoath.web.service.WorldTargets;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.TestMongo;
import com.ironoath.web.store.mongo.MongoNationStore;
import com.ironoath.web.store.mongo.MongoWarStore;
import com.ironoath.web.social.SocialStore;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Verifies the production migration service through a fresh Mongo-backed nation store. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CapitalMigrationMongoEndpointTest {
    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final String OWNER = UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE = "ironoath_b13_capital_migration_" + OWNER;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private SocialStore social;
    @Autowired private NationStore nations;
    @Autowired private NationRulesAssembler nationRules;
    @Autowired private WarRulesAssembler warRules;
    @Autowired private WarStore wars;
    @Autowired private WorldRepository world;
    @Autowired private com.ironoath.config.ConfigRegistry configs;
    @Autowired private MongoTemplate mongo;

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry properties) {
        properties.add("ironoath.storage", () -> "mongo");
        properties.add("spring.data.mongodb.uri", () -> TestMongo.uriForDatabase(DATABASE));
    }

    @BeforeAll
    static void markOwnedDatabase() {
        try (var client = MongoClients.create(TestMongo.uri())) {
            client.getDatabase(DATABASE).getCollection("__owned_run").insertOne(new Document("_id", OWNER));
        }
    }

    @AfterAll
    static void dropOwnedDatabase() {
        try (var client = MongoClients.create(TestMongo.uri())) {
            var database = client.getDatabase(DATABASE);
            assertThat(database.getCollection("__owned_run").countDocuments(new Document("_id", OWNER)))
                    .as("迁都Mongo验收只能删除本测试标记的临时库").isEqualTo(1L);
            assertThat(((Number) database.runCommand(new Document("dropDatabase", 1)
                    .append("comment", OWNER)).get("ok")).doubleValue()).isEqualTo(1.0);
            assertThat(client.getDatabase("admin").runCommand(new Document("listDatabases", 1)
                            .append("filter", new Document("name", DATABASE)).append("nameOnly", true))
                    .getList("databases", Document.class)).isEmpty();
        }
    }

    @Test
    void migrationIsVisibleThroughANewMongoNationStoreAndPreservesMembershipHistory() throws Exception {
        String king = newPlayer(16);
        String member = newPlayer(16);
        String allianceId = createAlliance(king, member);
        String nationId = post200("/nation/found", king,
                new NationFoundReq(requestId(), "Mongo迁都验收国", 100L, 200L))
                .get("nation").get("nationId").asText();
        var before = nations.findById(nationId).orElseThrow();
        var history = before.allianceMembershipPeriods(allianceId);
        var oldTreasury = before.treasury();
        var originalCapital = Coord.of(Math.toIntExact(before.capitalX()), Math.toIntExact(before.capitalY()));
        long warStartedAt = System.currentTimeMillis();
        var frozenRules = warRules.rules();
        var settledWar = new WarScoreBoard(frozenRules, warStartedAt, "migration-test-attacker",
                nationId, Long.MAX_VALUE);
        settledWar.registerNation("migration-test-attacker");
        settledWar.registerNation(nationId);
        for (int gate = 1; gate <= frozenRules.gateCount(); gate++) {
            settledWar.captureGate("migration-test-attacker", "gate_" + gate);
        }
        settledWar.beginSiege(warStartedAt);
        settledWar.captureCapital("migration-test-attacker", warStartedAt);
        long settledAt = warStartedAt + frozenRules.durationMillis();
        settledWar.settle(settledAt);
        var frozenAward = new WarAwardPlan(1, warStartedAt, "season-migration-test",
                java.util.List.of(new WarAwardPlan.Member("migration-old-member", "旧成员", 25L)));
        settledWar.freezeSeasonAwardPlan(frozenAward);
        settledWar.markSeasonAwarded(settledAt);
        assertThat(wars.insertIfNoneActive(settledWar)).isTrue();
        String capitalTargetId = WorldTargets.from(configs).targets().stream()
                .filter(target -> target.definition().kind() == com.ironoath.config.cfg.WorldTargetCfg.Kind.CAPITAL)
                .findFirst().orElseThrow().definition().id();
        assertThat(new WarCapitalTargetResolver(nations, wars, world).resolve(configs).targets().targets().stream()
                .filter(target -> target.definition().id().equals(capitalTargetId))
                .findFirst().orElseThrow().coord()).isEqualTo(originalCapital);
        long destinationX = 511L;
        long destinationY = 511L;

        JsonNode moved = post200("/nation/capital/migrate", king,
                new NationCapitalMigrationReq(requestId(), destinationX, destinationY));

        assertThat(moved.get("nationId").asText()).isEqualTo(nationId);
        assertThat(moved.get("capitalX").asLong()).isEqualTo(destinationX);
        assertThat(moved.get("capitalY").asLong()).isEqualTo(destinationY);
        NationStore reopened = new MongoNationStore(mongo, nationRules);
        var after = reopened.findById(nationId).orElseThrow();
        assertThat(after.capitalX()).isEqualTo(destinationX);
        assertThat(after.capitalY()).isEqualTo(destinationY);
        assertThat(after.treasury()).isEqualTo(oldTreasury);
        assertThat(after.allianceMembershipPeriods(allianceId)).isEqualTo(history);
        MongoWarStore reopenedWars = new MongoWarStore(mongo, warRules);
        assertThat(reopenedWars.findLatest().orElseThrow().seasonAwardPlan()).isEqualTo(frozenAward);
        var dynamicAfterMigration = new WarCapitalTargetResolver(reopened, reopenedWars, world).resolve(configs);
        assertThat(dynamicAfterMigration.targetNationId()).isEqualTo(nationId);
        assertThat(dynamicAfterMigration.targets().targets().stream()
                .filter(target -> target.definition().id().equals(capitalTargetId))
                .findFirst().orElseThrow().coord())
                .isEqualTo(Coord.of(Math.toIntExact(destinationX), Math.toIntExact(destinationY)));
    }

    private String createAlliance(String king, String member) throws Exception {
        String allianceId = post200("/alliance/create", king,
                new AllianceCreateReq(requestId(), "Mongo迁都联盟", "MIG"))
                .get("alliance").get("id").asText();
        post200("/alliance/apply", member, new AllianceIdReq(requestId(), allianceId));
        post200("/alliance/review", king, new AllianceReviewReq(requestId(), member, true));
        return allianceId;
    }

    private String newPlayer(int cityLevel) {
        String playerId = playerInitService.init(new PlayerInitReq(requestId(),
                "dev-" + UUID.randomUUID(), "迁都Mongo测试", 1_700_000_000_000L, "")).playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(cityLevel);
        PlayerResourceState gold = save.resources().get("GOLD");
        if (gold != null) save.putResource("GOLD", new PlayerResourceState(
                100_000L, gold.cap(), gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        players.save(save);
        return playerId;
    }

    private JsonNode post200(String path, String playerId, Object request) throws Exception {
        JsonNode root = postRaw(path, playerId, request);
        assertThat(root.get("code").asInt()).isZero();
        return root.get("data");
    }

    private JsonNode postRaw(String path, String playerId, Object request) throws Exception {
        MvcResult result = mockMvc.perform(post(path).header(PLAYER_HEADER, playerId)
                        .contentType(MediaType.APPLICATION_JSON).content(JsonUtils.toJson(request)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String requestId() {
        return "req-" + UUID.randomUUID();
    }
}
