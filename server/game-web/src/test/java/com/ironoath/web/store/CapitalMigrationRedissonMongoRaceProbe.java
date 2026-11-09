package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironoath.battle.BattleModifier;
import com.ironoath.common.BizException;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.march.March;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.player.PlayerTech;
import com.ironoath.core.social.Rally;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.battle.AllianceTechBonuses;
import com.ironoath.web.battle.BattleArmyFactory;
import com.ironoath.web.battle.BattleTechBonuses;
import com.ironoath.web.battle.CapturedBattlePreparation;
import com.ironoath.web.battle.CapturedStrategicBattle;
import com.ironoath.web.battle.HeroBattleMapper;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.NationAppointReq;
import com.ironoath.web.dto.generated.NationCapitalMigrationReq;
import com.ironoath.web.dto.generated.NationFoundReq;
import com.ironoath.web.dto.generated.NationJoinReq;
import com.ironoath.web.dto.generated.NationLeaveReq;
import com.ironoath.web.dto.generated.NationOffice;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.hero.EquipLedgers;
import com.ironoath.web.lock.RedissonPlayerLock;
import com.ironoath.web.nation.NationLeaders;
import com.ironoath.web.nation.NationPolicyBonuses;
import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.nation.WarStore;
import com.ironoath.web.service.HeroStatsService;
import com.ironoath.web.service.CapitalMigrationService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.WarCapitalTargetResolver;
import com.ironoath.web.service.WorldTargets;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.tech.TechEffects;
import com.ironoath.web.store.mongo.MongoNationStore;
import com.ironoath.web.store.mongo.MongoTargetOccupationStore;
import com.ironoath.web.store.mongo.MongoTargetOperationStore;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemoryNeutralTargetGarrisonStore;
import com.ironoath.web.store.memory.InMemoryTargetOccupationStore;
import com.ironoath.web.world.StrategicTargetDefender;
import com.ironoath.web.world.TargetOccupationStore;
import com.ironoath.web.world.TargetOperationStore;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Explicit Mongo+Redisson barrier probes for B3 race and failure edges; not in the default no-container suite. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CapitalMigrationRedissonMongoRaceProbe.RaceLockConfiguration.class)
class CapitalMigrationRedissonMongoRaceProbe {
    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final String OWNER = UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE = "ironoath_b13_migrate_races_" + OWNER;
    private static final String MONGO_URI = requiredLoopbackUri("ironoath.test.mongo.uri", "mongodb");
    private static final String REDIS_ADDRESS = requiredLoopbackUri("ironoath.test.redis.address", "redis");

    @Autowired private CapitalMigrationService migrations;
    @Autowired private com.ironoath.web.world.StrategicGarrisonWithdrawal targetOperations;
    @Autowired private PlayerInitService playerInit;
    @Autowired private RaceAwarePlayerLock playerLocks;
    @SpyBean(name = "nationStore") private NationStore nations;
    @SpyBean private PlayerRepository players;
    @Autowired private RedissonClient redisson;
    @Autowired private MongoTemplate mongo;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WorldRepository world;
    @Autowired private ConfigRegistry configs;
    @Autowired private NationRulesAssembler nationRules;
    @Autowired private WarRulesAssembler warRules;
    @Autowired private WarStore wars;
    @Autowired private TargetOperationStore operations;
    @Autowired private TargetOccupationStore occupations;
    @Autowired private TimeService time;

    @BeforeEach
    void clearOwnedRaceCollections() {
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), "world_target_operation");
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), "world_target_occupation");
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), "war");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RaceLockConfiguration {
        @Bean
        @Primary
        RaceAwarePlayerLock raceAwarePlayerLock(RedissonClient redisson) {
            return new RaceAwarePlayerLock(redisson);
        }
    }

    static final class RaceAwarePlayerLock implements PlayerLock {
        private record Watch(String lockId, PlayerLock probe,
                             CountDownLatch acquired, AtomicBoolean competingActionActive) {}

        private final PlayerLock delegate;
        private final AtomicReference<Watch> watch = new AtomicReference<>();
        private final ConcurrentLinkedQueue<String> seenIds = new ConcurrentLinkedQueue<>();

        RaceAwarePlayerLock(RedissonClient redisson) {
            this.delegate = new RedissonPlayerLock(redisson);
        }

        void watch(String lockId, PlayerLock probe,
                   CountDownLatch acquired, AtomicBoolean competingActionActive) {
            if (!watch.compareAndSet(null, new Watch(lockId, probe, acquired, competingActionActive))) {
                throw new IllegalStateException("已有B3目标锁观察器未释放");
            }
        }

        void clearWatch() {
            watch.set(null);
        }

        List<String> seenIds() {
            return List.copyOf(seenIds);
        }

        @Override
        public <T> T runLocked(String playerId, long timeoutMs, Supplier<T> action) {
            seenIds.add(playerId);
            Watch current = watch.get();
            if (current != null && current.lockId().equals(playerId)) {
                return current.probe().runLocked(playerId, timeoutMs, () -> {
                    if (current.competingActionActive().get()) {
                        throw new AssertionError("迁都进入目标锁时COMBAT仍持有同一临界区");
                    }
                    current.acquired().countDown();
                    return action.get();
                });
            }
            return delegate.runLocked(playerId, timeoutMs, action);
        }
    }

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
                    .as("只清理由本轮owner marker标记的B3竞争验收库").isEqualTo(1L);
            assertThat(((Number) database.runCommand(new Document("dropDatabase", 1)
                    .append("comment", OWNER)).get("ok")).doubleValue()).isEqualTo(1.0);
            assertThat(client.getDatabase("admin").runCommand(new Document("listDatabases", 1)
                    .append("filter", new Document("name", DATABASE)).append("nameOnly", true))
                    .getList("databases", Document.class)).isEmpty();
        }
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void nationLeaveRevokesAnOfficerMoveThatWasResolvedBeforeTheMembershipChange() throws Exception {
        String king = newPlayer();
        String kingAlliance = createAlliance(king, null);
        String memberLeader = newPlayer();
        String officer = newPlayer();
        String officerAlliance = createAlliance(memberLeader, officer);
        String nationId = foundNation(king);
        post200("/nation/join", memberLeader, new NationJoinReq(requestId(), nationId));
        post200("/nation/appoint", king,
                new NationAppointReq(requestId(), officer, NationOffice.PRIME_MINISTER));

        PlayerSave staleKingProfile = mock(PlayerSave.class);
        when(staleKingProfile.lastLoginAt()).thenReturn(time.serverNow() - 8L * 24L * 60L * 60L * 1_000L);
        doReturn(Optional.of(staleKingProfile)).when(players).findByPlayerId(king);

        var persisted = new MongoNationStore(mongo, nationRules).findById(nationId).orElseThrow();
        assertThat(persisted.memberAllianceIds()).contains(kingAlliance, officerAlliance);
        assertThat(persisted.officeOf(officer)).isEqualTo(Nation.Office.PRIME_MINISTER);
        Coord destination = freeCorner();
        var moveRequest = new NationCapitalMigrationReq(requestId(), destination.x(), destination.y());
        var memberReadReturned = new CountDownLatch(1);
        var releaseMemberRead = new CountDownLatch(1);
        var pauseOnce = new AtomicBoolean(true);

        doAnswer(invocation -> {
            Object response = invocation.callRealMethod();
            if (pauseOnce.compareAndSet(true, false)) {
                memberReadReturned.countDown();
                if (!releaseMemberRead.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out at the post-read migration barrier");
                }
            }
            return response;
        }).when(nations).findByAlliance(anyString());

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> move = worker.submit(() -> migrations.move(officer, moveRequest));
            assertThat(memberReadReturned.await(5, TimeUnit.SECONDS))
                    .as("迁都必须先解析到将被移出国家的旧官职快照").isTrue();

            post200("/nation/leave", memberLeader, new NationLeaveReq(requestId()));
            var afterLeave = new MongoNationStore(mongo, nationRules).findById(nationId).orElseThrow();
            assertThat(afterLeave.isDisbanded()).isFalse();
            assertThat(afterLeave.memberAllianceIds()).contains(kingAlliance).doesNotContain(officerAlliance);
            assertThat(afterLeave.officeOf(officer)).isNull();

            releaseMemberRead.countDown();
            ExecutionException denied = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class, () -> move.get(10, TimeUnit.SECONDS));
            assertThat(denied.getCause()).isInstanceOf(BizException.class)
                    .hasMessageContaining("只有国王或本国官职持有人");

            var finalNation = new MongoNationStore(mongo, nationRules).findById(nationId).orElseThrow();
            assertThat(finalNation.capitalX()).isEqualTo(afterLeave.capitalX());
            assertThat(finalNation.capitalY()).isEqualTo(afterLeave.capitalY());
            assertThat(finalNation.allianceMembershipPeriods(officerAlliance))
                    .isEqualTo(afterLeave.allianceMembershipPeriods(officerAlliance));
        } finally {
            releaseMemberRead.countDown();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void nationExpectedVersionWriteFailureHasNoPartialMigrationAndCanRetryTheSameRequest() throws Exception {
        String king = newPlayer();
        String allianceId = createAlliance(king, null);
        String nationId = foundNation(king);
        var store = new MongoNationStore(mongo, nationRules);
        var before = store.findById(nationId).orElseThrow();
        var history = before.allianceMembershipPeriods(allianceId);
        String capitalId = capitalTargetId();
        var ownerStore = new MongoTargetOccupationStore(mongo);
        var operationStore = new MongoTargetOperationStore(mongo);
        var ownerBefore = ownerStore.findByTargetIds(List.of(capitalId)).get(capitalId);
        var operationBefore = operationStore.findByTargetId(capitalId).orElse(null);
        Coord destination = freeCorner();
        var request = new NationCapitalMigrationReq(requestId(), destination.x(), destination.y());
        var failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failOnce.compareAndSet(true, false)) {
                throw new IllegalStateException("simulated NationStore.save(expectedVersion) rejection");
            }
            return invocation.callRealMethod();
        }).when(nations).save(any(Nation.class), anyLong());

        assertThatThrownBy(() -> migrations.move(king, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("国家状态已变化");
        assertThat(failOnce.get()).isFalse();
        var afterRejectedWrite = store.findById(nationId).orElseThrow();
        assertThat(afterRejectedWrite.capitalX()).isEqualTo(before.capitalX());
        assertThat(afterRejectedWrite.capitalY()).isEqualTo(before.capitalY());
        assertThat(afterRejectedWrite.version()).isEqualTo(before.version());
        assertThat(afterRejectedWrite.allianceMembershipPeriods(allianceId)).isEqualTo(history);
        assertThat(ownerStore.findByTargetIds(List.of(capitalId)).get(capitalId)).isEqualTo(ownerBefore);
        assertThat(operationStore.findByTargetId(capitalId).orElse(null)).isEqualTo(operationBefore);

        var retry = migrations.move(king, request);
        assertThat(retry.nationId()).isEqualTo(nationId);
        var afterRetry = store.findById(nationId).orElseThrow();
        assertThat(afterRetry.capitalX()).isEqualTo(destination.x());
        assertThat(afterRetry.capitalY()).isEqualTo(destination.y());
        assertThat(afterRetry.allianceMembershipPeriods(allianceId)).isEqualTo(history);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void completedCapitalCombatWarEventAndFrozenAnchorAreVisibleBeforeMigrationPassesTheSharedLock()
            throws Exception {
        assertThat(ReflectionTestUtils.getField(migrations, "playerLocks")).isSameAs(playerLocks);
        assertThat(ReflectionTestUtils.getField(targetOperations, "locks")).isSameAs(playerLocks);
        String king = newPlayer();
        String allianceId = createAlliance(king, null);
        String nationId = foundNation(king);
        var nationStore = new MongoNationStore(mongo, nationRules);
        var before = nationStore.findById(nationId).orElseThrow();
        Coord oldCapital = Coord.of(Math.toIntExact(before.capitalX()), Math.toIntExact(before.capitalY()));
        Coord destination = freeCorner();
        String capitalId = capitalTargetId();

        var occupationStore = new MongoTargetOccupationStore(mongo);
        var ownerBefore = occupationStore.findByTargetIds(List.of(capitalId)).get(capitalId);
        if (ownerBefore == null) {
            ownerBefore = new com.ironoath.web.world.TargetOccupationStore.Snapshot(capitalId,
                    WorldGenerator.EntityType.CAPITAL, oldCapital.x(), oldCapital.y(), nationId,
                    "B13 COMBAT竞态验收国", 110L, "b13-combat-owner", 1L,
                    com.ironoath.web.world.TargetOccupationStore.Garrison.empty(), List.of());
            assertThat(occupationStore.compareAndSet(ownerBefore, 0L)).isTrue();
        }
        assertThat(ownerBefore.kind()).isEqualTo(WorldGenerator.EntityType.CAPITAL);
        assertThat(ownerBefore.garrison()).isEqualTo(
                com.ironoath.web.world.TargetOccupationStore.Garrison.empty());

        long warStartedAt = 110L;
        var capitalDirectory = WorldTargets.from(configs).withCapitalCoord(oldCapital);
        WarCapitalTargetResolver resolver = mock(WarCapitalTargetResolver.class);
        when(resolver.resolve(any(ConfigRegistry.class))).thenReturn(
                new WarCapitalTargetResolver.Directory(capitalDirectory, 1L, nationId, 1L, 120L));
        var battle = capitalBattle(oldCapital, nationId, resolver);
        var combatPlan = TargetOperationStore.CombatPlan.create(battle, 1L);
        String operationId = combatPlan.operationId();

        var war = new WarScoreBoard(warRules.rules(), warStartedAt, "owned-N", nationId, Long.MAX_VALUE);
        war.registerNation("owned-N");
        war.registerNation(nationId);
        long kills = battle.result().atkKillsByMember().getOrDefault("owned-leader", 0L);
        var reservation = war.reserveBattle(capitalId, 1L, operationId, battle.happenedAt(),
                        List.of(new WarScoreBoard.BattleMember("owned-leader", "owned-N", kills)))
                .orElseThrow();
        var event = war.freezeReservedBattleEvent(operationId, Map.of("owned-leader", 0L));
        combatPlan = combatPlan.withWarRecovery(new TargetOperationStore.WarRecoveryPlan(reservation, event));
        var frozenCombatPlan = combatPlan;
        assertThat(wars.insertIfNoneActive(war)).isTrue();
        wars.save(war);
        var pending = TargetOperationStore.Snapshot.combat(1L, false, frozenCombatPlan);
        assertThat(operations.compareAndSet(pending, 0L)).isTrue();

        String targetLockId = "@strategic-target:" + capitalId;
        var combatOwnsLock = new CountDownLatch(1);
        var finishCombat = new CountDownLatch(1);
        var migrationTargetsLock = new CountDownLatch(1);
        var migrationAcquiredTargetLock = new CountDownLatch(1);
        var combatLockActive = new AtomicBoolean(false);
        RLock targetLock = redisson.getLock("ironoath:lock:player:" + targetLockId);
        var instrumentedTargetLock = mock(RLock.class);
        doAnswer(invocation -> {
            assertThat(combatLockActive.get()).as("迁都的目标锁请求必须与COMBAT临界区重叠").isTrue();
            migrationTargetsLock.countDown();
            return targetLock.tryLock(invocation.getArgument(0), invocation.getArgument(1));
        }).when(instrumentedTargetLock).tryLock(anyLong(), any(TimeUnit.class));
        doAnswer(invocation -> { targetLock.unlock(); return null; })
                .when(instrumentedTargetLock).unlock();
        RedissonClient instrumentedClient = mock(RedissonClient.class);
        when(instrumentedClient.getLock("ironoath:lock:player:" + targetLockId)).thenReturn(instrumentedTargetLock);
        PlayerLock targetLockProbe = new RedissonPlayerLock(instrumentedClient);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> completion = workers.submit(() -> playerLocks.runLocked(targetLockId, 3_000L, () -> {
                combatLockActive.set(true);
                combatOwnsLock.countDown();
                await(finishCombat, "COMBAT completion release");
                assertThat(wars.recordBattleEvent(event)).isEqualTo(event);
                assertThat(wars.completeBattleOperation(warStartedAt, operationId)).isTrue();
                assertThat(operations.compareAndSet(
                        TargetOperationStore.Snapshot.combat(2L, true, frozenCombatPlan), 1L))
                        .isTrue();
                combatLockActive.set(false);
                return null;
            }));
            assertThat(combatOwnsLock.await(5, TimeUnit.SECONDS)).isTrue();
            playerLocks.watch(targetLockId, targetLockProbe, migrationAcquiredTargetLock, combatLockActive);

            var request = new NationCapitalMigrationReq(requestId(), destination.x(), destination.y());
            Future<com.ironoath.web.dto.generated.NationCapitalMigrationResp> move = workers.submit(() -> {
                return migrations.move(king, request);
            });
            boolean reachedTargetLock = migrationTargetsLock.await(5, TimeUnit.SECONDS);
            assertThat(reachedTargetLock)
                    .as("race-aware PlayerLock must observe the migration's shared target lock; lock ids=%s",
                            playerLocks.seenIds())
                    .isTrue();
            assertThat(move.isDone()).isFalse();
            assertThat(migrationAcquiredTargetLock.getCount()).isEqualTo(1L);
            finishCombat.countDown();
            completion.get(10, TimeUnit.SECONDS);
            var response = move.get(10, TimeUnit.SECONDS);
            assertThat(response.nationId()).isEqualTo(nationId);
            assertThat(migrationAcquiredTargetLock.getCount()).isZero();

            var after = nationStore.findById(nationId).orElseThrow();
            assertThat(after.capitalX()).isEqualTo(destination.x());
            assertThat(after.capitalY()).isEqualTo(destination.y());
            assertThat(after.version()).isGreaterThan(before.version());
            assertThat(after.allianceMembershipPeriods(allianceId)).isEqualTo(before.allianceMembershipPeriods(allianceId));
            var ownerAfter = occupationStore.findByTargetIds(List.of(capitalId)).get(capitalId);
            assertThat(ownerAfter.x()).isEqualTo(destination.x());
            assertThat(ownerAfter.y()).isEqualTo(destination.y());
            assertThat(ownerAfter.nationId()).isEqualTo(ownerBefore.nationId());
            assertThat(ownerAfter.garrison()).isEqualTo(ownerBefore.garrison());
            assertThat(ownerAfter.ownershipHistory()).isEqualTo(ownerBefore.ownershipHistory());
            var completed = operations.findByTargetId(capitalId).orElseThrow();
            assertThat(completed.completed()).isTrue();
            assertThat(completed.combatPlan().battle().target().x()).isEqualTo(oldCapital.x());
            assertThat(completed.combatPlan().battle().target().y()).isEqualTo(oldCapital.y());
            var warAfter = wars.findByStartedAt(warStartedAt).orElseThrow();
            assertThat(warAfter.battleEvent(capitalId)).isEqualTo(event);
            assertThat(warAfter.pendingBattleReservations()).isEmpty();
        } finally {
            finishCombat.countDown();
            playerLocks.clearWatch();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String newPlayer() {
        String playerId = playerInit.init(new PlayerInitReq(requestId(), "dev-" + UUID.randomUUID(),
                "B13迁都交错验收", 1_700_000_000_000L, "")).playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(16);
        PlayerResourceState gold = save.resources().get("GOLD");
        if (gold != null) save.putResource("GOLD", new PlayerResourceState(
                100_000L, gold.cap(), gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        players.save(save);
        return playerId;
    }

    private String createAlliance(String leader, String member) throws Exception {
        String allianceId = post200("/alliance/create", leader,
                new AllianceCreateReq(requestId(), "B13迁都交错盟" + UUID.randomUUID(), shortTag()))
                .get("alliance").get("id").asText();
        if (member != null) {
            post200("/alliance/apply", member, new AllianceIdReq(requestId(), allianceId));
            post200("/alliance/review", leader, new AllianceReviewReq(requestId(), member, true));
        }
        return allianceId;
    }

    private String foundNation(String king) throws Exception {
        return post200("/nation/found", king,
                new NationFoundReq(requestId(), "B13迁都交错国" + UUID.randomUUID(), 100L, 200L))
                .get("nation").get("nationId").asText();
    }

    private Coord freeCorner() {
        var reserved = WorldTargets.from(configs);
        return List.of(Coord.of(0, 0), Coord.of(0, 511), Coord.of(511, 0), Coord.of(511, 511),
                        Coord.of(1, 1), Coord.of(510, 510))
                .stream()
                .filter(coord -> world.cityAt(coord).isEmpty())
                .filter(coord -> !reserved.reserved(coord))
                .findFirst().orElseThrow();
    }

    private String capitalTargetId() {
        return WorldTargets.from(configs).targets().stream()
                .filter(target -> target.definition().kind() == com.ironoath.config.cfg.WorldTargetCfg.Kind.CAPITAL)
                .findFirst().orElseThrow().definition().id();
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

    private CapturedStrategicBattle capitalBattle(Coord coord, String targetNationId,
                                                   WarCapitalTargetResolver resolver) {
        var cfg = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var social = mock(SocialStore.class);
        var heroes = new HeroBattleMapper(cfg, new HeroStatsService(cfg),
                new EquipLedgers(mock(InventoryRepository.class), cfg), mock(HeroRepository.class), social);
        var tech = new BattleTechBonuses(new AllianceTechBonuses(social, cfg), new TechEffects(cfg),
                mock(PlayerRepository.class));
        var policies = new NationPolicyBonuses(cfg, social, mock(NationStore.class), mock(NationLeaders.class),
                new BattleArmyFactory(cfg));
        var preparation = new CapturedBattlePreparation(cfg, heroes, tech, policies, resolver);
        var scope = preparation.capture(200);
        var troops = Map.of("unit_infantry_t4", 100L);
        var rally = Rally.restore("owned-dynamic-capital-rally", Rally.Scope.NATION, "owned-N", "owned-leader",
                2, 1, 100, 110, Map.of("owned-leader", new Rally.Participant("owned-leader", troops, List.of())),
                Rally.Status.PREPARING, null, coord.x(), coord.y(), "STRATEGIC_TARGET", 1);
        rally.depart(110);
        var march = new March("owned-dynamic-capital-march", "owned-leader", Coord.of(10, 10), coord, 110, 120,
                troops, List.of(), 1_000, 1, March.TargetType.STRATEGIC_TARGET, "capital_center", March.Action.ATTACK);
        march.attachToRally(rally.rallyId());
        march.arrive(120);
        var attacker = scope.playerSide(march, 7, PlayerTech.empty(), rally);
        var defenders = new StrategicTargetDefender(cfg, new InMemoryTargetOccupationStore(),
                new InMemoryMarchStore(32), social, new InMemoryNeutralTargetGarrisonStore(), resolver);
        var target = scope.readTarget(defenders, "capital_center");
        var simulation = scope.simulateTarget(attacker, target, null, null, 2405,
                BattleModifier.none(), BattleModifier.none());
        PlayerSave save = mock(PlayerSave.class);
        when(save.tech()).thenReturn(PlayerTech.empty());
        var member = new CapturedBattlePreparation.MemberHospital(mock(CityState.class), save);
        return scope.freezeTargetBattle(simulation, scope.prepareHospitals(Map.of("owned-leader", member)), null);
    }

    private static String shortTag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 3).toUpperCase(java.util.Locale.ROOT);
    }

    private static void await(CountDownLatch latch, String name) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out waiting for " + name);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for " + name, interrupted);
        }
    }

    private static String requestId() {
        return "req-" + UUID.randomUUID();
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
}
