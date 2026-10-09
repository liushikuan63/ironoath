package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.social.Rally;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.nation.WarStore;
import com.ironoath.web.service.MarchAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.social.SocialRulesAssembler;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemorySocialStore;
import com.ironoath.web.store.memory.SortedMarchDueQueue;
import com.ironoath.web.store.mongo.MongoArmyStore;
import com.ironoath.web.store.mongo.MongoMarchDueQueue;
import com.ironoath.web.store.mongo.MongoMarchStore;
import com.ironoath.web.store.mongo.MongoSocialStore;

/** 实际 March/Social 生产服务的出发恢复；两后端同脚本，恢复前重建服务及 Mongo 三份仓储和队列。 */
@SpringBootTest @ActiveProfiles("test")
class RallyDepartureRecoveryTest {
    enum Backend { MEMORY, MONGO }
    private static TestMongo db;
    private static final long NOW = 1_800_000_000_000L;
    private static final String UNIT = "unit_infantry_t1";
    @Autowired private ApplicationContext context;
    @Autowired private ConfigRegistry configs;
    @Autowired private PlayerInitService init;
    @Autowired private WarStore wars;
    @Autowired private WorldRepository world;

    @BeforeAll static void connect() {
        db = TestMongo.tryOpen();
        assertThat(db).as("真实Mongo必须可用，不用跳过掩盖未验证").isNotNull();
    }
    @AfterAll static void close() { if (db != null) { db.close(); } }

    private record Ports(SocialStore social, ArmyRepository army, MarchRepository march, MarchDueQueue due,
                         Backend backend) { }
    private record Services(SocialAppService social, MarchAppService march) { }
    private record Fixture(Ports ports, Services services, String rallyId, String leader, String mate) { }

    private Ports ports(Backend backend) {
        int chunkSize = (int) configs.longParam("WORLD_CHUNK_SIZE");
        if (backend == Backend.MEMORY) {
            return new Ports(new InMemorySocialStore(), new InMemoryArmyStore(),
                    new InMemoryMarchStore(chunkSize), new SortedMarchDueQueue(), backend);
        }
        return new Ports(new MongoSocialStore(db.template(), new SocialRulesAssembler(configs)),
                new MongoArmyStore(db.template()), new MongoMarchStore(db.template(), chunkSize),
                new MongoMarchDueQueue(db.template()), backend);
    }

    private Services services(Ports ports) throws Exception {
        SocialAppService social = context.getAutowireCapableBeanFactory().createBean(SocialAppService.class);
        MarchAppService march = context.getAutowireCapableBeanFactory().createBean(MarchAppService.class);
        field(social, "store", ports.social);
        field(social, "armies", ports.army);
        field(march, "socialStore", ports.social);
        field(march, "socialAppService", social);
        field(march, "marches", ports.march);
        field(march, "dueQueue", ports.due);
        field(march, "armies", ports.army);
        return new Services(social, march);
    }

    private Fixture fixture(Backend backend) throws Exception {
        Ports ports = ports(backend);
        ports.social.clear();
        if (backend == Backend.MONGO) {
            for (String collection : List.of("army", "march", "march_due")) {
                db.template().remove(new org.springframework.data.mongodb.core.query.Query(), collection);
            }
        }
        String leader = player();
        String mate = player();
        for (String id : List.of(leader, mate)) {
            ArmyState army = new ArmyState(); army.add(UNIT, 700L);
            ports.army.insertIfAbsent(id, army);
        }
        Map<String, Rally.Participant> participants = new LinkedHashMap<>();
        participants.put(leader, new Rally.Participant(leader, Map.of(UNIT, 300L), List.of()));
        participants.put(mate, new Rally.Participant(mate, Map.of(UNIT, 300L), List.of()));
        String rallyId = "R-recovery-" + UUID.randomUUID();
        ports.social.saveRally(Rally.restore(rallyId, Rally.Scope.NATION, "N-" + rallyId, leader,
                4, 2, NOW - 600_000L, NOW, participants, Rally.Status.PREPARING, null,
                400L, 400L, "MONSTER", 1L), 0L);
        return new Fixture(ports, services(ports), rallyId, leader, mate);
    }

    private Services restarted(Fixture f) throws Exception {
        Ports rebuilt = f.ports.backend == Backend.MONGO ? ports(Backend.MONGO) : f.ports;
        return services(rebuilt);
    }

    private String player() {
        return init.init(new PlayerInitReq("req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(),
                "出发恢复", 1_700_000_000_000L, "")).playerId();
    }

    private static void field(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    @FunctionalInterface private interface Invocation { Object call(Method method, Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> type, Invocation call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, args) -> call.call(method, args)));
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
    private static void assertLockedTroops(Fixture f) {
        assertThat(f.ports.army.findByPlayerId(f.leader).orElseThrow().countOf(UNIT)).isEqualTo(700L);
        assertThat(f.ports.army.findByPlayerId(f.mate).orElseThrow().countOf(UNIT)).isEqualTo(700L);
    }
    private static March onlyMarch(Fixture f) {
        assertThat(f.ports.march.findByPlayerId(f.leader)).hasSize(1);
        return f.ports.march.findByPlayerId(f.leader).get(0);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void crashAfterDepartedBeforeMarchInsertRestoresTheOriginalFullSnapshot(Backend backend) throws Exception {
        Fixture f = fixture(backend);
        field(f.services.march, "marches", proxy(MarchRepository.class, (method, args) -> {
            if (method.getName().equals("insertIfAbsent")) { throw new IllegalStateException("March unavailable"); }
            return invoke(f.ports.march, method, args);
        }));
        assertThat(f.services.march.departDueRallies(NOW)).isZero();
        assertThat(f.ports.social.rallyOf(f.rallyId)).map(Rally::status).contains(Rally.Status.DEPARTED);
        var pending = f.ports.social.rallyDepartureOf(f.rallyId).orElseThrow();
        assertThat(pending.march()).isNotNull();
        assertThat(f.ports.march.findByPlayerId(f.leader)).isEmpty();
        Services rebooted = restarted(f);
        assertThat(rebooted.march.departDueRallies(NOW + 900_000L)).isEqualTo(1);
        assertThat(onlyMarch(f).snapshot()).isEqualTo(pending.march());
        assertThat(onlyMarch(f).startAt()).isEqualTo(NOW);
        assertThat(f.ports.due.size()).isEqualTo(1);
        assertThat(rebooted.march.departDueRallies(NOW + 900_000L)).isZero();
        assertThat(f.ports.social.pendingRallyDepartures()).isEmpty();
        assertLockedTroops(f);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void lostMarchInsertAcknowledgementCannotCreateTwoColumns(Backend backend) throws Exception {
        Fixture f = fixture(backend);
        field(f.services.march, "marches", proxy(MarchRepository.class, (method, args) -> {
            Object result = invoke(f.ports.march, method, args);
            if (method.getName().equals("insertIfAbsent")) { throw new IllegalStateException("March acknowledgement lost"); }
            return result;
        }));
        assertThat(f.services.march.departDueRallies(NOW)).isEqualTo(1);
        March first = onlyMarch(f);
        assertThat(restarted(f).march.departDueRallies(NOW + 100L)).isZero();
        assertThat(onlyMarch(f).snapshot()).isEqualTo(first.snapshot());
        assertThat(f.ports.due.size()).isEqualTo(1);
        assertLockedTroops(f);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void aQueueFailureKeepsTheCommittedMarchAndRecoversItsOriginalDueTime(Backend backend) throws Exception {
        Fixture f = fixture(backend);
        field(f.services.march, "dueQueue", proxy(MarchDueQueue.class, (method, args) -> {
            if (method.getName().equals("schedule")) { throw new IllegalStateException("queue unavailable"); }
            return invoke(f.ports.due, method, args);
        }));
        assertThat(f.services.march.departDueRallies(NOW)).isZero();
        March first = onlyMarch(f);
        assertThat(f.ports.due.size()).isZero();
        assertThat(restarted(f).march.departDueRallies(NOW + 900_000L)).isZero();
        assertThat(onlyMarch(f).snapshot()).isEqualTo(first.snapshot());
        assertThat(f.ports.due.dueBefore(first.arriveAt() - 1L, 10)).isEmpty();
        assertThat(f.ports.due.dueBefore(first.arriveAt(), 10)).containsExactly(first.id());
        assertThat(f.ports.social.pendingRallyDepartures()).isEmpty();
        assertLockedTroops(f);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void fatigueFailureKeepsTheExistingMarchAndThePersistentWork(Backend backend) throws Exception {
        Fixture f = fixture(backend);
        field(f.services.march, "wars", proxy(WarStore.class, (method, args) -> {
            Object result = invoke(wars, method, args);
            if (method.getName().equals("addMarchFatigueOnce")) {
                throw new IllegalStateException("fatigue acknowledgement lost");
            }
            return result;
        }));
        assertThat(f.services.march.departDueRallies(NOW)).isZero();
        March first = onlyMarch(f);
        assertThat(f.ports.social.pendingRallyDepartures()).hasSize(1);
        assertThat(restarted(f).march.departDueRallies(NOW + 100L)).isZero();
        assertThat(onlyMarch(f).snapshot()).isEqualTo(first.snapshot());
        assertThat(f.ports.social.pendingRallyDepartures()).isEmpty();
        assertLockedTroops(f);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void failedPlanCleanupBlocksDueDeletionAndRepeatingFogIsIdempotent(Backend backend) throws Exception {
        Fixture f = fixture(backend);
        SocialStore faulty = proxy(SocialStore.class, (method, args) -> {
            if (method.getName().equals("removeRallyDeparture")) { throw new IllegalStateException("journal removal unavailable"); }
            return invoke(f.ports.social, method, args);
        });
        field(f.services.march, "socialStore", faulty);
        field(f.services.social, "store", faulty);
        assertThat(f.services.march.departDueRallies(NOW)).isZero();
        March first = onlyMarch(f);
        var fog = java.util.Set.copyOf(world.fogOf(f.leader).chunks());
        assertThat(f.services.march.processDue(f.leader, first.arriveAt() + 1L)).isZero();
        assertThat(onlyMarch(f).status()).isEqualTo(March.Status.MARCHING);
        assertThat(f.ports.social.rallyOf(f.rallyId)).map(Rally::status).contains(Rally.Status.DEPARTED);
        assertThat(f.ports.social.pendingRallyDepartures()).hasSize(1);
        restarted(f).march.departDueRallies(NOW + 100L);
        assertThat(f.ports.social.pendingRallyDepartures()).isEmpty();
        assertThat(world.fogOf(f.leader).chunks()).containsExactlyInAnyOrderElementsOf(fog);
        assertThat(onlyMarch(f).snapshot()).isEqualTo(first.snapshot());
        assertLockedTroops(f);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void terminalRallyWithAResidualPlanNeverRecreatesADeletedMarch(Backend backend) throws Exception {
        Fixture f = fixture(backend);
        field(f.services.march, "socialStore", proxy(SocialStore.class, (method, args) -> {
            if (method.getName().equals("removeRallyDeparture")) { throw new IllegalStateException("journal removal unavailable"); }
            return invoke(f.ports.social, method, args);
        }));
        f.services.march.departDueRallies(NOW);
        March first = onlyMarch(f);
        Rally arrived = f.ports.social.rallyOf(f.rallyId).orElseThrow();
        long version = arrived.version(); arrived.arrive(); f.ports.social.saveRally(arrived, version);
        f.ports.march.delete(first.id()); f.ports.due.cancel(first.id());
        assertThat(restarted(f).march.departDueRallies(NOW + 900_000L)).isZero();
        assertThat(f.ports.march.findByPlayerId(f.leader)).isEmpty();
        assertThat(f.ports.social.pendingRallyDepartures()).isEmpty();
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void lostQueueAcknowledgementKeepsOneMarchAndItsOriginalDeadline(Backend backend) throws Exception {
        Fixture f = fixture(backend);
        field(f.services.march, "dueQueue", proxy(MarchDueQueue.class, (method, args) -> {
            Object result = invoke(f.ports.due, method, args);
            if (method.getName().equals("schedule")) { throw new IllegalStateException("queue acknowledgement lost"); }
            return result;
        }));
        assertThat(f.services.march.departDueRallies(NOW)).isZero();
        March first = onlyMarch(f);
        assertThat(f.ports.due.size()).isEqualTo(1);
        assertThat(restarted(f).march.departDueRallies(NOW + 900_000L)).isZero();
        assertThat(onlyMarch(f).snapshot()).isEqualTo(first.snapshot());
        assertThat(f.ports.due.dueBefore(first.arriveAt(), 10)).containsExactly(first.id());
        assertThat(f.ports.social.pendingRallyDepartures()).isEmpty();
        assertLockedTroops(f);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void lostPlanRemovalAcknowledgementDoesNotRefundOrRecreateTheMarch(Backend backend) throws Exception {
        Fixture f = fixture(backend);
        field(f.services.march, "socialStore", proxy(SocialStore.class, (method, args) -> {
            Object result = invoke(f.ports.social, method, args);
            if (method.getName().equals("removeRallyDeparture")) {
                throw new IllegalStateException("journal removal acknowledgement lost");
            }
            return result;
        }));
        assertThat(f.services.march.departDueRallies(NOW)).isZero();
        March first = onlyMarch(f);
        assertThat(f.ports.social.pendingRallyDepartures()).isEmpty();
        assertThat(restarted(f).march.departDueRallies(NOW + 900_000L)).isZero();
        assertThat(onlyMarch(f).snapshot()).isEqualTo(first.snapshot());
        assertLockedTroops(f);
    }

    private March returning(Fixture f) {
        assertThat(f.services.march.departDueRallies(NOW)).isEqualTo(1);
        March march = onlyMarch(f);
        long version = f.ports.march.versionOf(march.id());
        march.arrive(march.arriveAt());
        march.beginReturn(march.arriveAt(), 100L);
        f.ports.march.save(march, version);
        Rally rally = f.ports.social.rallyOf(f.rallyId).orElseThrow();
        long rallyVersion = rally.version(); rally.arrive(); f.ports.social.saveRally(rally, rallyVersion);
        f.ports.due.reschedule(march.id(), march.returnArriveAt());
        return march;
    }

    private static void assertAllReturned(Fixture f) {
        assertThat(f.ports.army.findByPlayerId(f.leader).orElseThrow().countOf(UNIT)).isEqualTo(1000L);
        assertThat(f.ports.army.findByPlayerId(f.mate).orElseThrow().countOf(UNIT)).isEqualTo(1000L);
        assertThat(f.ports.march.findByPlayerId(f.leader)).isEmpty();
        assertThat(f.ports.due.size()).isZero();
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void secondMemberReturnSaveFailureReplaysOnlyTheUncreditedMember(Backend backend) throws Exception {
        Fixture f = fixture(backend);
        March returning = returning(f);
        java.util.concurrent.atomic.AtomicReference<String> first = new java.util.concurrent.atomic.AtomicReference<>();
        field(f.services.march, "armies", proxy(ArmyRepository.class, (method, args) -> {
            if (method.getName().equals("save")) {
                first.compareAndSet(null, (String) args[0]);
                if (!first.get().equals(args[0])) { throw new IllegalStateException("second member Army unavailable"); }
            }
            return invoke(f.ports.army, method, args);
        }));
        assertThat(f.services.march.processDue(f.leader, returning.returnArriveAt())).isZero();
        assertThat(f.ports.army.findByPlayerId(first.get()).orElseThrow().countOf(UNIT)).isEqualTo(1000L);
        String second = first.get().equals(f.leader) ? f.mate : f.leader;
        assertThat(f.ports.army.findByPlayerId(second).orElseThrow().countOf(UNIT)).isEqualTo(700L);
        assertThat(onlyMarch(f).status()).isEqualTo(March.Status.RETURNING);
        Services rebooted = restarted(f);
        assertThat(rebooted.march.processDue(f.leader, returning.returnArriveAt() + 1L)).isEqualTo(1);
        assertAllReturned(f);
        assertThat(rebooted.march.processDue(f.leader, returning.returnArriveAt() + 2L)).isZero();
        assertAllReturned(f);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void lostSecondMemberReturnSaveAcknowledgementConfirmsReceiptWithoutRepeatingCredits(Backend backend) throws Exception {
        Fixture f = fixture(backend);
        March returning = returning(f);
        java.util.concurrent.atomic.AtomicReference<String> first = new java.util.concurrent.atomic.AtomicReference<>();
        field(f.services.march, "armies", proxy(ArmyRepository.class, (method, args) -> {
            Object result = invoke(f.ports.army, method, args);
            if (method.getName().equals("save")) {
                first.compareAndSet(null, (String) args[0]);
                if (!first.get().equals(args[0])) { throw new IllegalStateException("second member Army acknowledgement lost"); }
            }
            return result;
        }));
        assertThat(f.services.march.processDue(f.leader, returning.returnArriveAt())).isEqualTo(1);
        assertAllReturned(f);
        assertThat(restarted(f).march.processDue(f.leader, returning.returnArriveAt() + 1L)).isZero();
        assertAllReturned(f);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void finalMarchDeleteFailureRetainsTheReplayableReturnWithoutRepeatingCredits(Backend backend) throws Exception {
        Fixture f = fixture(backend);
        March returning = returning(f);
        field(f.services.march, "marches", proxy(MarchRepository.class, (method, args) -> {
            if (method.getName().equals("delete")) { throw new IllegalStateException("March deletion unavailable"); }
            return invoke(f.ports.march, method, args);
        }));
        assertThat(f.services.march.processDue(f.leader, returning.returnArriveAt())).isZero();
        assertThat(onlyMarch(f).status()).isEqualTo(March.Status.RETURNING);
        assertThat(restarted(f).march.processDue(f.leader, returning.returnArriveAt() + 1L)).isEqualTo(1);
        assertAllReturned(f);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void lostFinalMarchDeleteAcknowledgementCleansTheQueueWithoutRepeatingCredits(Backend backend) throws Exception {
        Fixture f = fixture(backend);
        March returning = returning(f);
        field(f.services.march, "marches", proxy(MarchRepository.class, (method, args) -> {
            Object result = invoke(f.ports.march, method, args);
            if (method.getName().equals("delete")) { throw new IllegalStateException("March deletion acknowledgement lost"); }
            return result;
        }));
        assertThat(f.services.march.processDue(f.leader, returning.returnArriveAt())).isZero();
        assertThat(f.ports.march.findByPlayerId(f.leader)).isEmpty();
        assertThat(restarted(f).march.processDue(f.leader, returning.returnArriveAt() + 1L)).isZero();
        assertAllReturned(f);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void aDepartedStateWithOnlyTheInitialIntentRebuildsAWholeMarchAfterRestart(Backend backend) throws Exception {
        Fixture f = fixture(backend);
        Rally actual = f.ports.social.rallyOf(f.rallyId).orElseThrow();
        f.services.social.settleDueRally(actual, NOW);
        assertThat(f.ports.social.rallyDepartureOf(f.rallyId).orElseThrow().march()).isNull();
        assertThat(restarted(f).march.departDueRallies(NOW + 900_000L)).isEqualTo(1);
        assertThat(onlyMarch(f).startAt()).isEqualTo(NOW);
        assertThat(onlyMarch(f).units()).containsEntry(UNIT, 600L);
        assertLockedTroops(f);
    }
}
