package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.core.march.March;
import com.ironoath.core.nation.WarAwardPlan;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldGenerator;
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
import com.ironoath.web.world.TargetOccupationStore;
import com.ironoath.web.world.TargetOperationStore;
import com.ironoath.web.store.TestMongo;
import com.ironoath.web.store.mongo.MongoNationStore;
import com.ironoath.web.store.mongo.MongoWarStore;
import com.ironoath.web.social.SocialStore;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;
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
    @SpyBean(name = "targetOccupationStore") private TargetOccupationStore occupations;
    @Autowired private TargetOperationStore operations;
    @Autowired private ApplicationContext context;
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
        var stationed = new March("migration-old-capital-march", king,
                Coord.of(Math.toIntExact(before.capitalX() - 1), Math.toIntExact(before.capitalY())),
                originalCapital, warStartedAt, warStartedAt + 1L, java.util.Map.of("unit_infantry_t4", 1L),
                java.util.List.of(), 10L, 1, March.TargetType.STRATEGIC_TARGET,
                capitalTargetId, March.Action.ATTACK);
        stationed.attachToRally("migration-old-capital-rally");
        stationed.arrive(warStartedAt + 1L);
        stationed.holdStrategicVictory(java.util.Map.of(king, stationed.units()));
        var ownerBeforeWithdrawal = new TargetOccupationStore.Snapshot(capitalTargetId,
                WorldGenerator.EntityType.CAPITAL, originalCapital.x(), originalCapital.y(), nationId,
                before.name(), warStartedAt, "migration-old-capital-capture", 1L,
                TargetOccupationStore.Garrison.stationed(stationed.id()));
        assertThat(occupations.compareAndSet(ownerBeforeWithdrawal, 0L)).isTrue();
        var completedWithdrawal = TargetOperationStore.WithdrawalPlan.create(
                ownerBeforeWithdrawal, stationed, 0L, 1L, warStartedAt + 2L);
        assertThat(occupations.compareAndSet(completedWithdrawal.after(), 1L)).isTrue();
        assertThat(operations.compareAndSet(new TargetOperationStore.Snapshot(capitalTargetId, 1L,
                false, completedWithdrawal), 0L)).isTrue();
        var completedWithdrawalSlot = new TargetOperationStore.Snapshot(capitalTargetId, 2L,
                true, completedWithdrawal);
        assertThat(operations.compareAndSet(completedWithdrawalSlot, 1L)).isTrue();
        assertThat(new WarCapitalTargetResolver(nations, wars, world).resolve(configs).targets().targets().stream()
                .filter(target -> target.definition().id().equals(capitalTargetId))
                .findFirst().orElseThrow().coord()).isEqualTo(originalCapital);
        long destinationX = 511L;
        long destinationY = 511L;

        AtomicBoolean failAfterNationSave = new AtomicBoolean(true);
        doAnswer(invocation -> failBeforeOwnerAnchorWrite(invocation, failAfterNationSave))
                .when(occupations).reanchorEmptyCapital(any(TargetOccupationStore.Snapshot.class),
                        anyInt(), anyInt());
        mockMvc.perform(post("/nation/capital/migrate").header(PLAYER_HEADER, king)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtils.toJson(new NationCapitalMigrationReq(
                                requestId(), destinationX, destinationY))))
                .andExpect(status().is5xxServerError());

        var afterNationCas = new MongoNationStore(mongo, nationRules).findById(nationId).orElseThrow();
        assertThat(afterNationCas.capitalX()).isEqualTo(destinationX);
        assertThat(afterNationCas.capitalY()).isEqualTo(destinationY);
        var beforeAnchorRecovery = new com.ironoath.web.store.mongo.MongoTargetOccupationStore(mongo)
                .findByTargetIds(java.util.List.of(capitalTargetId)).get(capitalTargetId);
        assertThat(beforeAnchorRecovery.x()).isEqualTo(originalCapital.x());
        assertThat(beforeAnchorRecovery.y()).isEqualTo(originalCapital.y());

        assertThat(context.getAutowireCapableBeanFactory().createBean(
                com.ironoath.web.service.WorldAppService.class))
                .as("重启必须从已CAS的Nation坐标恢复空驻军王城锚点")
                .isNotNull();
        NationStore reopened = new MongoNationStore(mongo, nationRules);
        var after = reopened.findById(nationId).orElseThrow();
        assertThat(after.capitalX()).isEqualTo(destinationX);
        assertThat(after.capitalY()).isEqualTo(destinationY);
        assertThat(after.treasury()).isEqualTo(oldTreasury);
        assertThat(after.allianceMembershipPeriods(allianceId)).isEqualTo(history);
        var relocatedOwner = new com.ironoath.web.store.mongo.MongoTargetOccupationStore(mongo)
                .findByTargetIds(java.util.List.of(capitalTargetId)).get(capitalTargetId);
        assertThat(relocatedOwner.x()).isEqualTo(Math.toIntExact(destinationX));
        assertThat(relocatedOwner.y()).isEqualTo(Math.toIntExact(destinationY));
        assertThat(relocatedOwner.nationId()).isEqualTo(ownerBeforeWithdrawal.nationId());
        assertThat(relocatedOwner.nationName()).isEqualTo(ownerBeforeWithdrawal.nationName());
        assertThat(relocatedOwner.capturedAt()).isEqualTo(ownerBeforeWithdrawal.capturedAt());
        assertThat(relocatedOwner.operationId()).isEqualTo(completedWithdrawal.after().operationId());
        assertThat(relocatedOwner.garrison()).isEqualTo(TargetOccupationStore.Garrison.empty());
        assertThat(relocatedOwner.ownershipHistory()).isEqualTo(ownerBeforeWithdrawal.ownershipHistory());
        assertThat(new com.ironoath.web.store.mongo.MongoTargetOperationStore(mongo)
                .findByTargetId(capitalTargetId)).contains(completedWithdrawalSlot);
        MongoWarStore reopenedWars = new MongoWarStore(mongo, warRules);
        assertThat(reopenedWars.findLatest().orElseThrow().seasonAwardPlan()).isEqualTo(frozenAward);
        var dynamicAfterMigration = new WarCapitalTargetResolver(reopened, reopenedWars, world).resolve(configs);
        assertThat(dynamicAfterMigration.targetNationId()).isEqualTo(nationId);
        assertThat(dynamicAfterMigration.targets().targets().stream()
                .filter(target -> target.definition().id().equals(capitalTargetId))
                .findFirst().orElseThrow().coord())
                .isEqualTo(Coord.of(Math.toIntExact(destinationX), Math.toIntExact(destinationY)));
        assertThat(new com.ironoath.web.store.mongo.MongoTargetOperationStore(mongo)
                .findByTargetId(capitalTargetId)).contains(completedWithdrawalSlot);

        Coord secondDestination = java.util.List.of(Coord.of(0,0),Coord.of(0,511),Coord.of(511,0),Coord.of(510,511))
                .stream().filter(coord->!coord.equals(Coord.of(Math.toIntExact(destinationX),Math.toIntExact(destinationY))))
                .filter(coord->!dynamicAfterMigration.targets().reserved(coord))
                .filter(coord->world.cityAt(coord).isEmpty()).findFirst().orElseThrow();
        JsonNode secondMove = post200("/nation/capital/migrate", king,
                new NationCapitalMigrationReq(requestId(), secondDestination.x(), secondDestination.y()));
        assertThat(secondMove.get("nationId").asText()).isEqualTo(nationId);
        assertThat(secondMove.get("capitalX").asInt()).isEqualTo(secondDestination.x());
        assertThat(secondMove.get("capitalY").asInt()).isEqualTo(secondDestination.y());
        var finalOwner = new com.ironoath.web.store.mongo.MongoTargetOccupationStore(mongo)
                .findByTargetIds(java.util.List.of(capitalTargetId)).get(capitalTargetId);
        assertThat(finalOwner.x()).isEqualTo(secondDestination.x());
        assertThat(finalOwner.y()).isEqualTo(secondDestination.y());
        assertThat(finalOwner.nationId()).isEqualTo(ownerBeforeWithdrawal.nationId());
        assertThat(finalOwner.ownershipHistory()).isEqualTo(ownerBeforeWithdrawal.ownershipHistory());
        assertThat(new com.ironoath.web.store.mongo.MongoWarStore(mongo,warRules)
                .findLatest().orElseThrow().seasonAwardPlan()).isEqualTo(frozenAward);
        assertThat(new com.ironoath.web.store.mongo.MongoTargetOperationStore(mongo)
                .findByTargetId(capitalTargetId)).contains(completedWithdrawalSlot);
    }

    private static Object failBeforeOwnerAnchorWrite(InvocationOnMock invocation,
                                                       AtomicBoolean failAfterNationSave) throws Throwable {
        if (failAfterNationSave.compareAndSet(true, false)) {
            throw new IllegalStateException("simulated process stop after Nation CAS and before owner-anchor CAS");
        }
        return invocation.callRealMethod();
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
