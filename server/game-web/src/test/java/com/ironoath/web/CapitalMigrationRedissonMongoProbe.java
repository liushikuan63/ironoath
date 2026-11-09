package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.NationCapitalMigrationReq;
import com.ironoath.web.dto.generated.NationFoundReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.CapitalMigrationService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.WorldTargets;
import com.ironoath.web.store.TestMongo;
import com.ironoath.web.store.mongo.MongoNationStore;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
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

/** Explicit Mongo + Redis acceptance probe; excluded from the default no-container test suite. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CapitalMigrationRedissonMongoProbe {
    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final String OWNER = UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE = "ironoath_b13_migrate_redisson_" + OWNER;
    private static final String MONGO_URI = requiredLoopbackUri("ironoath.test.mongo.uri", "mongodb");
    private static final String REDIS_ADDRESS = requiredLoopbackUri("ironoath.test.redis.address", "redis");

    @Autowired private CapitalMigrationService migrations;
    @Autowired private PlayerLock applicationLocks;
    @Autowired private PlayerInitService playerInit;
    @Autowired private PlayerRepository players;
    @Autowired private WorldRepository world;
    @Autowired private com.ironoath.config.ConfigRegistry configs;
    @Autowired private com.ironoath.web.nation.NationRulesAssembler nationRules;
    @Autowired private MongoTemplate mongo;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void isolatedStores(DynamicPropertyRegistry properties) {
        properties.add("ironoath.storage", () -> "mongo");
        properties.add("ironoath.lock", () -> "redisson");
        properties.add("ironoath.redis.address", () -> REDIS_ADDRESS);
        properties.add("spring.data.mongodb.uri", () -> TestMongo.uriForDatabase(MONGO_URI, DATABASE));
    }

    @BeforeAll
    static void markOwnedDatabase() {
        try (var client = MongoClients.create(MONGO_URI)) {
            client.getDatabase(DATABASE).getCollection("__owned_run")
                    .insertOne(new Document("_id", OWNER));
        }
    }

    @AfterAll
    static void dropOwnedDatabase() {
        try (var client = MongoClients.create(MONGO_URI)) {
            var database = client.getDatabase(DATABASE);
            assertThat(database.getCollection("__owned_run").countDocuments(new Document("_id", OWNER)))
                    .as("只能清理由本次owner marker标记的迁都验收库").isEqualTo(1L);
            assertThat(((Number) database.runCommand(new Document("dropDatabase", 1)
                    .append("comment", OWNER)).get("ok")).doubleValue()).isEqualTo(1.0);
            assertThat(client.getDatabase("admin").runCommand(new Document("listDatabases", 1)
                    .append("filter", new Document("name", DATABASE)).append("nameOnly", true))
                    .getList("databases", Document.class)).isEmpty();
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void independentRedisClientBlocksMongoCapitalMoveUntilTheCoordinateLockIsReleased() throws Exception {
        assertThat(applicationLocks).isInstanceOf(com.ironoath.web.lock.RedissonPlayerLock.class);

        String king = newPlayer();
        var allianceResponse = post200("/alliance/create", king,
                new AllianceCreateReq(requestId(), "B13跨客户端迁都验收", "RM"));
        String allianceId = allianceResponse.get("alliance").get("id").asText();
        String nationId = post200("/nation/found", king,
                new NationFoundReq(requestId(), "B13跨客户端迁都验收国", 100L, 200L))
                .get("nation").get("nationId").asText();

        var before = new MongoNationStore(mongo, nationRules).findById(nationId).orElseThrow();
        var history = before.allianceMembershipPeriods(allianceId);
        var targets = WorldTargets.from(configs);
        Coord destination = List.of(Coord.of(0, 0), Coord.of(0, 511), Coord.of(511, 0), Coord.of(511, 511))
                .stream()
                .filter(coord -> world.cityAt(coord).isEmpty())
                .filter(coord -> !targets.reserved(coord))
                .findFirst().orElseThrow();
        var request = new NationCapitalMigrationReq(requestId(), destination.x(), destination.y());

        RedissonClient peer = connectPeer();
        String destinationLockKey = "ironoath:lock:player:@world-coordinate:" + destination.storageKey();
        String originalLockKey = "ironoath:lock:player:@world-coordinate:"
                + Coord.of(Math.toIntExact(before.capitalX()), Math.toIntExact(before.capitalY())).storageKey();
        var peerLock = peer.getLock(destinationLockKey);
        try {
            assertThat(peerLock.tryLock(1, TimeUnit.SECONDS)).as("独立Redis客户端应取得新坐标锁").isTrue();
            assertThatThrownBy(() -> migrations.move(king, request))
                    .isInstanceOf(PlayerLock.LockTimeoutException.class);

            var afterRejectedMove = new MongoNationStore(mongo, nationRules).findById(nationId).orElseThrow();
            assertThat(afterRejectedMove.capitalX()).isEqualTo(before.capitalX());
            assertThat(afterRejectedMove.capitalY()).isEqualTo(before.capitalY());
            assertThat(afterRejectedMove.allianceMembershipPeriods(allianceId)).isEqualTo(history);

            var oldCellLock = peer.getLock(originalLockKey);
            assertThat(oldCellLock.tryLock(1, TimeUnit.SECONDS))
                    .as("迁都获取后续坐标锁超时后必须释放已持有的旧坐标锁").isTrue();
            oldCellLock.unlock();
        } finally {
            if (peerLock.isHeldByCurrentThread()) peerLock.unlock();
            peer.shutdown();
        }

        var moved = migrations.move(king, request);
        assertThat(moved.nationId()).isEqualTo(nationId);
        var reopened = new MongoNationStore(mongo, nationRules).findById(nationId).orElseThrow();
        assertThat(reopened.capitalX()).isEqualTo(destination.x());
        assertThat(reopened.capitalY()).isEqualTo(destination.y());
        assertThat(reopened.allianceMembershipPeriods(allianceId)).isEqualTo(history);
    }

    private String newPlayer() {
        String playerId = playerInit.init(new PlayerInitReq(requestId(), "dev-" + UUID.randomUUID(),
                "B13跨客户端迁都验收", 1_700_000_000_000L, "")).playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(16);
        PlayerResourceState gold = save.resources().get("GOLD");
        if (gold != null) save.putResource("GOLD", new PlayerResourceState(
                100_000L, gold.cap(), gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        players.save(save);
        return playerId;
    }

    private JsonNode post200(String path, String playerId, Object request) throws Exception {
        MvcResult result = mockMvc.perform(post(path).header(PLAYER_HEADER, playerId)
                        .contentType(MediaType.APPLICATION_JSON).content(JsonUtils.toJson(request)))
                .andExpect(status().isOk()).andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString(
                java.nio.charset.StandardCharsets.UTF_8));
        assertThat(root.get("code").asInt()).isZero();
        return root.get("data");
    }

    private static RedissonClient connectPeer() {
        var config = new Config();
        config.useSingleServer().setAddress(REDIS_ADDRESS).setConnectionPoolSize(2)
                .setConnectionMinimumIdleSize(1).setConnectTimeout(1_000).setTimeout(1_000)
                .setRetryAttempts(1);
        return Redisson.create(config);
    }

    private static String requiredLoopbackUri(String property, String scheme) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalStateException("必须显式设置 -D" + property);
        URI uri = URI.create(value);
        if (!scheme.equals(uri.getScheme()) || uri.getHost() == null
                || !(uri.getHost().equals("127.0.0.1") || uri.getHost().equalsIgnoreCase("localhost"))) {
            throw new IllegalArgumentException(property + "必须是明确的本地隔离服务地址");
        }
        return value;
    }

    private static String requestId() {
        return "req-" + UUID.randomUUID();
    }
}
