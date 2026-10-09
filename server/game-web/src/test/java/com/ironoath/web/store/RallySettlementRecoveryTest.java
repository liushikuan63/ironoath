package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.social.Rally;
import com.ironoath.web.social.RallySettlementRecovery;
import com.ironoath.web.social.SocialRulesAssembler;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemorySocialStore;
import com.ironoath.web.store.mongo.MongoArmyStore;
import com.ironoath.web.store.mongo.MongoSocialStore;

/** 相同故障脚本跑 memory 与真实 Mongo；Mongo 恢复前重建两个仓储实例，验证恢复不靠进程字段。 */
class RallySettlementRecoveryTest {
    enum Backend { MEMORY, MONGO }
    private static TestMongo db;
    private static SocialRulesAssembler rules;
    private static final String UNIT = "unit_infantry_t1";

    @BeforeAll
    static void openMongo() {
        Path directory = Path.of("../../contract/config").toAbsolutePath().normalize();
        if (!java.nio.file.Files.exists(directory)) { directory = Path.of("contract/config"); }
        rules = new SocialRulesAssembler(ConfigRegistry.loadFromDirectory(directory));
        db = TestMongo.tryOpen();
        assertThat(db).as("真实 Mongo 前提必须成立，不能跳过后报绿").isNotNull();
    }

    @AfterAll
    static void closeMongo() { if (db != null) { db.close(); } }

    private record Stores(SocialStore social, ArmyRepository armies, Backend backend) {
        Stores rebuilt() {
            return backend == Backend.MONGO
                    ? new Stores(new MongoSocialStore(db.template(), rules), new MongoArmyStore(db.template()), backend)
                    : this;
        }
    }

    private Stores stores(Backend backend) {
        Stores stores = backend == Backend.MONGO
                ? new Stores(new MongoSocialStore(db.template(), rules), new MongoArmyStore(db.template()), backend)
                : new Stores(new InMemorySocialStore(), new InMemoryArmyStore(), backend);
        stores.social.clear();
        if (backend == Backend.MONGO) { db.template().remove(new org.springframework.data.mongodb.core.query.Query(), "army"); }
        for (String player : List.of("P-1", "P-2")) {
            ArmyState army = new ArmyState();
            army.add(UNIT, 700L);
            stores.armies.insertIfAbsent(player, army);
        }
        stores.social.saveRally(rally(), 0L);
        return stores;
    }

    private Rally rally() {
        Map<String, Rally.Participant> members = new LinkedHashMap<>();
        members.put("P-1", new Rally.Participant("P-1", Map.of(UNIT, 300L), List.of()));
        members.put("P-2", new Rally.Participant("P-2", Map.of(UNIT, 300L), List.of()));
        return Rally.restore("R-1", Rally.Scope.NATION, "N-1", "P-1", 4, 2, 10L, 600_010L,
                members, Rally.Status.PREPARING, null, 400L, 400L, "MONSTER", 1L);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void journalIsImmutableInsertOnceFilteredAndCleared(Backend backend) {
        Stores stores = stores(backend);
        Map<String, Long> troops = new LinkedHashMap<>(Map.of(UNIT, 300L));
        Map<String, Map<String, Long>> refunds = new LinkedHashMap<>(Map.of("P-1", troops));
        var plan = new SocialStore.RallySettlement("S-1", "R-1", "N-1", null, false, refunds);
        stores.social.putRallySettlementIfAbsent(plan);
        troops.put(UNIT, 999L);
        refunds.clear();
        var duplicate = new SocialStore.RallySettlement("S-1", "R-1", "N-1", null, false,
                Map.of("P-1", Map.of(UNIT, 1L)));
        assertThat(stores.social.putRallySettlementIfAbsent(duplicate)).isEqualTo(plan);
        assertThat(stores.rebuilt().social.pendingRallySettlementsOf("N-1")).containsExactly(plan);
        assertThat(stores.social.pendingRallySettlementsOf("OTHER")).isEmpty();
        assertThatThrownBy(() -> plan.refunds().get("P-1").put(UNIT, 1L))
                .isInstanceOf(UnsupportedOperationException.class);
        stores.social.removeRallySettlement("S-1");
        stores.social.removeRallySettlement("S-1");
        assertThat(stores.social.pendingRallySettlementsOf("N-1")).isEmpty();
        stores.social.putRallySettlementIfAbsent(plan);
        stores.social.clear();
        assertThat(stores.social.pendingRallySettlementsOf("N-1")).isEmpty();
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void secondRecipientFailureRecoversFromFreshStoreInstances(Backend backend) {
        Stores stores = stores(backend);
        AtomicInteger failures = new AtomicInteger(2);
        ArmyRepository faulty = proxy(ArmyRepository.class, stores.armies, (method, args) -> {
            if (method.getName().equals("save") && args[0].equals("P-2")
                    && failures.getAndDecrement() > 0) { throw new IllegalStateException("Army unavailable"); }
            return invoke(stores.armies, method, args);
        });
        assertThatThrownBy(() -> RallySettlementRecovery.settle(stores.social, faulty, rally(), null, false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(stores.social.rallyOf("R-1")).map(Rally::status).contains(Rally.Status.CANCELLED);
        assertThat(count(stores.armies, "P-1")).isEqualTo(1_000L);
        assertThat(count(stores.armies, "P-2")).isEqualTo(700L);
        assertThat(stores.social.pendingRallySettlementsOf("N-1")).hasSize(1);
        Stores restarted = stores.rebuilt();
        RallySettlementRecovery.recover(restarted.social, restarted.armies, "N-1");
        RallySettlementRecovery.recover(restarted.social, restarted.armies, "N-1");
        assertThat(count(restarted.armies, "P-1")).isEqualTo(1_000L);
        assertThat(count(restarted.armies, "P-2")).isEqualTo(1_000L);
        assertThat(restarted.social.pendingRallySettlementsOf("N-1")).isEmpty();
        assertThat(restarted.armies.findByPlayerId("P-1").orElseThrow().snapshot().rallyRefunds()).hasSize(1);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void aRemovedMemberStillHasADurableRefundAfterFailure(Backend backend) {
        Stores stores = stores(backend);
        ArmyRepository faulty = proxy(ArmyRepository.class, stores.armies, (method, args) -> {
            if (method.getName().equals("save")) { throw new IllegalStateException("Army unavailable"); }
            return invoke(stores.armies, method, args);
        });
        assertThatThrownBy(() -> RallySettlementRecovery.settle(stores.social, faulty, rally(), "P-2", false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(stores.social.rallyOf("R-1").orElseThrow().participant("P-2")).isNull();
        Stores restarted = stores.rebuilt();
        RallySettlementRecovery.recover(restarted.social, restarted.armies, "N-1");
        assertThat(count(restarted.armies, "P-1")).isEqualTo(700L);
        assertThat(count(restarted.armies, "P-2")).isEqualTo(1_000L);
        assertThat(restarted.social.rallyOf("R-1")).map(Rally::status).contains(Rally.Status.PREPARING);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void acknowledgementLossOnRallyAndArmySaveDoesNotDuplicateRefunds(Backend backend) {
        Stores stores = stores(backend);
        AtomicInteger rallyWrites = new AtomicInteger();
        SocialStore faultySocial = proxy(SocialStore.class, stores.social, (method, args) -> {
            Object result = invoke(stores.social, method, args);
            if (method.getName().equals("saveRally") && rallyWrites.incrementAndGet() == 1) {
                throw new IllegalStateException("Rally acknowledgement lost");
            }
            return result;
        });
        AtomicInteger armyWrites = new AtomicInteger();
        ArmyRepository faultyArmy = proxy(ArmyRepository.class, stores.armies, (method, args) -> {
            Object result = invoke(stores.armies, method, args);
            if (method.getName().equals("save")) {
                armyWrites.incrementAndGet();
                throw new IllegalStateException("Army acknowledgement lost");
            }
            return result;
        });
        RallySettlementRecovery.settle(faultySocial, faultyArmy, rally(), null, false);
        assertThat(rallyWrites).hasValue(1);
        assertThat(armyWrites).hasValue(2);
        assertThat(count(stores.rebuilt().armies, "P-1")).isEqualTo(1_000L);
        assertThat(count(stores.rebuilt().armies, "P-2")).isEqualTo(1_000L);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void journalRemovalFailureReplaysOnlyReceiptsAfterRestart(Backend backend) {
        Stores stores = stores(backend);
        SocialStore faulty = proxy(SocialStore.class, stores.social, (method, args) -> {
            if (method.getName().equals("removeRallySettlement")) {
                throw new IllegalStateException("journal removal unavailable");
            }
            return invoke(stores.social, method, args);
        });
        assertThatThrownBy(() -> RallySettlementRecovery.settle(faulty, stores.armies, rally(), null, false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count(stores.armies, "P-1")).isEqualTo(1_000L);
        assertThat(count(stores.armies, "P-2")).isEqualTo(1_000L);
        long version1 = stores.armies.versionOf("P-1");
        long version2 = stores.armies.versionOf("P-2");
        Stores restarted = stores.rebuilt();
        RallySettlementRecovery.recover(restarted.social, restarted.armies, "N-1");
        assertThat(restarted.armies.versionOf("P-1")).isEqualTo(version1);
        assertThat(restarted.armies.versionOf("P-2")).isEqualTo(version2);
        assertThat(restarted.social.pendingRallySettlementsOf("N-1")).isEmpty();
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void staleArmyCannotUseALaterVersionToEraseCommittedRefunds(Backend backend) {
        Stores stores = stores(backend);
        ArmyState stale = stores.armies.findByPlayerId("P-1").orElseThrow();
        RallySettlementRecovery.settle(stores.social, stores.armies, rally(), null, false);
        stale.deduct(UNIT, 100L);
        long newerVersion = stores.armies.versionOf("P-1");
        assertThatThrownBy(() -> stores.armies.save("P-1", stale, newerVersion))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("快照版本");
        assertThat(count(stores.armies, "P-1")).isEqualTo(1_000L);
        assertThat(stores.armies.findByPlayerId("P-1").orElseThrow().snapshot().rallyRefunds()).hasSize(1);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void laterQuitAfterRejoiningGetsItsOwnRefundKey(Backend backend) {
        Stores stores = stores(backend);
        RallySettlementRecovery.settle(stores.social, stores.armies, rally(), "P-2", false);
        Rally current = stores.social.rallyOf("R-1").orElseThrow();
        long rallyVersion = current.version();
        ArmyState army = stores.armies.findByPlayerId("P-2").orElseThrow();
        army.deduct(UNIT, 100L);
        stores.armies.save("P-2", army, stores.armies.versionOf("P-2"));
        current.join("P-2", Map.of(UNIT, 100L), List.of());
        stores.social.saveRally(current, rallyVersion);
        RallySettlementRecovery.settle(stores.social, stores.armies, current, "P-2", false);
        assertThat(count(stores.rebuilt().armies, "P-2")).isEqualTo(1_000L);
        assertThat(stores.armies.findByPlayerId("P-2").orElseThrow().snapshot().rallyRefunds()).hasSize(2);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void abortingANationalDepartureHasTheSameDurableRefunds(Backend backend) {
        Stores stores = stores(backend);
        Rally departed = stores.social.rallyOf("R-1").orElseThrow();
        long version = departed.version();
        departed.depart(600_010L);
        stores.social.saveRally(departed, version);
        RallySettlementRecovery.settle(stores.social, stores.armies, departed, null, true);
        RallySettlementRecovery.recover(stores.rebuilt().social, stores.rebuilt().armies, "N-1");
        assertThat(stores.social.rallyOf("R-1")).map(Rally::status).contains(Rally.Status.CANCELLED);
        assertThat(count(stores.armies, "P-1")).isEqualTo(1_000L);
        assertThat(count(stores.armies, "P-2")).isEqualTo(1_000L);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void aFreshCancelledSnapshotCannotCreateAnotherRefundOperation(Backend backend) {
        Stores stores = stores(backend);
        RallySettlementRecovery.settle(stores.social, stores.armies, rally(), null, false);
        Rally cancelled = stores.social.rallyOf("R-1").orElseThrow();
        assertThatThrownBy(() -> RallySettlementRecovery.settle(stores.social, stores.armies,
                cancelled, null, false)).isInstanceOf(IllegalStateException.class);
        assertThat(count(stores.armies, "P-1")).isEqualTo(1_000L);
        assertThat(count(stores.armies, "P-2")).isEqualTo(1_000L);
        assertThat(stores.social.pendingRallySettlementsOf("N-1")).isEmpty();
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void aJournalInsertWithLostAcknowledgementIsRecoveredBeforeAnyRefund(Backend backend) {
        Stores stores = stores(backend);
        SocialStore faulty = proxy(SocialStore.class, stores.social, (method, args) -> {
            Object result = invoke(stores.social, method, args);
            if (method.getName().equals("putRallySettlementIfAbsent")) {
                throw new IllegalStateException("journal insert acknowledgement lost");
            }
            return result;
        });
        assertThatThrownBy(() -> RallySettlementRecovery.settle(faulty, stores.armies, rally(), null, false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(stores.social.rallyOf("R-1")).map(Rally::status).contains(Rally.Status.PREPARING);
        assertThat(count(stores.armies, "P-1")).isEqualTo(700L);
        Stores restarted = stores.rebuilt();
        RallySettlementRecovery.recover(restarted.social, restarted.armies, "N-1");
        assertThat(count(restarted.armies, "P-1")).isEqualTo(1_000L);
        assertThat(count(restarted.armies, "P-2")).isEqualTo(1_000L);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void concurrentArmyChangeIsRereadBeforeApplyingTheRefund(Backend backend) {
        Stores stores = stores(backend);
        AtomicInteger injected = new AtomicInteger();
        ArmyRepository faulty = proxy(ArmyRepository.class, stores.armies, (method, args) -> {
            if (method.getName().equals("save") && args[0].equals("P-1")
                    && injected.getAndIncrement() == 0) {
                ArmyState other = stores.armies.findByPlayerId("P-1").orElseThrow();
                other.deduct(UNIT, 50L);
                stores.armies.save("P-1", other, stores.armies.versionOf("P-1"));
            }
            return invoke(stores.armies, method, args);
        });
        RallySettlementRecovery.settle(stores.social, faulty, rally(), null, false);
        assertThat(injected).hasValue(2);
        assertThat(count(stores.rebuilt().armies, "P-1")).isEqualTo(950L);
        assertThat(count(stores.rebuilt().armies, "P-2")).isEqualTo(1_000L);
    }

    private static long count(ArmyRepository armies, String playerId) {
        return armies.findByPlayerId(playerId).orElseThrow().totalTroops();
    }

    @FunctionalInterface private interface Invocation {
        Object call(Method method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> port, T delegate, Invocation callback) {
        return port.cast(Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[]{port},
                (ignored, method, args) -> callback.call(method, args)));
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
}
