package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.army.ArmyVersionConflictException;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.dto.generated.ArmyUnitReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.nation.NationTechBonuses;
import com.ironoath.web.quest.QuestEvents;
import com.ironoath.web.reward.PlayerBag;
import com.ironoath.web.service.ArmyAppService;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.HeroAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.social.HelpRequestRegistrar;
import com.ironoath.web.social.RallySettlementRecovery;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryIdempotencyStore;
import com.ironoath.web.store.memory.InMemoryInventoryStore;
import com.ironoath.web.store.mongo.MongoArmyStore;
import com.ironoath.web.store.mongo.MongoInventoryStore;
import com.ironoath.web.tech.TechEffects;

/** 真实扣令与返兵交错：确定未写入才返令，提交结果未知不能白送加速。 */
@SpringBootTest
@ActiveProfiles("test")
class ArmySpeedUpConflictEquivalenceTest {
    enum Backend { MEMORY, MONGO }
    enum RefundTiming { AFTER_STATE_READ, AFTER_DEBIT }
    private static final String UNIT = "unit_infantry_t1";
    private static final String ITEM = "item_speedup_train_1h";
    private static TestMongo db;

    @Autowired private ConfigRegistry configs;
    @Autowired private CityAppService city;
    @Autowired private HeroAppService heroes;
    @Autowired private PlayerInitService playerInit;
    @Autowired private TimeService time;
    @Autowired private HelpRequestRegistrar helpRequests;
    @Autowired private QuestEvents questEvents;
    @Autowired private PlayerRepository players;
    @Autowired private TechEffects techEffects;
    @Autowired private NationTechBonuses nationTechBonuses;

    @BeforeAll static void connect() {
        db = TestMongo.tryOpen();
        assertThat(db).as("真实 Mongo 必须可用，不能跳过消费冲突等价验证").isNotNull();
    }
    @AfterAll static void close() { if (db != null) { db.close(); } }

    private record Fixture(String playerId, ArmyRepository armies, PlayerBag bag,
                           long finishAt, Backend backend) { }

    private Fixture fixture(Backend backend) {
        String playerId = playerInit.init(new PlayerInitReq("req-" + UUID.randomUUID(),
                "dev-" + UUID.randomUUID(), "军队冲突测试", 1_700_000_000_000L, "")).playerId();
        ArmyRepository armies = backend == Backend.MEMORY
                ? new InMemoryArmyStore() : new MongoArmyStore(db.template());
        InventoryRepository inventories = backend == Backend.MEMORY
                ? new InMemoryInventoryStore() : new MongoInventoryStore(db.template(), configs);
        PlayerBag bag = new PlayerBag(inventories, configs);
        assertThat(bag.add(playerId, ITEM, 2L)).isEqualTo(2L);
        ArmyState army = new ArmyState();
        army.add(UNIT, 700L);
        long finishAt = army.train(UNIT, 100L, 60L, 0L, 1, 2_000L, 1_000L, time.serverNow());
        assertThat(armies.insertIfAbsent(playerId, army)).isTrue();
        return new Fixture(playerId, armies, bag, finishAt, backend);
    }

    private ArmyAppService service(ArmyRepository armies, PlayerBag bag) {
        return new ArmyAppService(configs, armies, city, heroes, bag,
                new InMemoryIdempotencyStore(), time, helpRequests, questEvents,
                players, techEffects, nationTechBonuses);
    }

    private ArmyUnitReq request() {
        return new ArmyUnitReq("req-" + UUID.randomUUID(), UNIT, null, ITEM);
    }

    private ArmyState rereadArmy(Fixture f) {
        ArmyRepository reader = f.backend() == Backend.MONGO
                ? new MongoArmyStore(db.template()) : f.armies();
        return reader.findByPlayerId(f.playerId()).orElseThrow();
    }

    private long rereadItemCount(Fixture f) {
        return f.backend() == Backend.MONGO
                ? new PlayerBag(new MongoInventoryStore(db.template(), configs), configs)
                        .countOf(f.playerId(), ITEM)
                : f.bag().countOf(f.playerId(), ITEM);
    }

    static Stream<Arguments> refundCases() {
        return Stream.of(Backend.values()).flatMap(backend ->
                Stream.of(RefundTiming.values()).map(timing -> Arguments.of(backend, timing)));
    }

    @ParameterizedTest @MethodSource("refundCases")
    void definiteConflictRefundsItemWithoutErasingRefundOrAdvancingTraining(
            Backend backend, RefundTiming timing) {
        Fixture f = fixture(backend);
        String receipt = "rally-return:" + UUID.randomUUID();
        AtomicBoolean refunded = new AtomicBoolean();
        ArmyRepository hookedArmy = new DelegatingArmy(f.armies()) {
            @Override public long versionOf(String playerId) {
                if (timing == RefundTiming.AFTER_STATE_READ && refunded.compareAndSet(false, true)) {
                    RallySettlementRecovery.refundOnce(f.armies(), playerId, receipt, Map.of(UNIT, 300L));
                }
                return super.versionOf(playerId);
            }
        };
        PlayerBag hookedBag = spy(f.bag());
        doAnswer(call -> {
            long removed = (long) call.callRealMethod();
            assertThat(removed).isEqualTo(1L);
            assertThat(f.bag().countOf(f.playerId(), ITEM)).as("真实扣令已经落库").isEqualTo(1L);
            if (timing == RefundTiming.AFTER_DEBIT && refunded.compareAndSet(false, true)) {
                RallySettlementRecovery.refundOnce(f.armies(), f.playerId(), receipt, Map.of(UNIT, 300L));
            }
            return removed;
        }).when(hookedBag).remove(f.playerId(), ITEM, 1L);

        assertThatThrownBy(() -> service(hookedArmy, hookedBag).speedUp(f.playerId(), request()))
                .isInstanceOf(ArmyVersionConflictException.class)
                .hasMessageContaining(timing == RefundTiming.AFTER_STATE_READ ? "快照版本" : "乐观锁");
        assertThat(refunded).isTrue();
        assertThat(rereadItemCount(f)).as("确定未写入必须归还训练令").isEqualTo(2L);
        ArmyState stored = rereadArmy(f);
        assertThat(stored.countOf(UNIT)).isEqualTo(1_000L);
        assertThat(stored.hasRallyRefund(receipt)).isTrue();
        assertThat(stored.queue().get(UNIT).finishAt()).isEqualTo(f.finishAt());
        assertThat(f.armies().versionOf(f.playerId())).isEqualTo(1L);
    }

    static Stream<Arguments> unknownSaveCases() {
        return Stream.of(Backend.values()).flatMap(backend ->
                Stream.of(false, true).map(committed -> Arguments.of(backend, committed)));
    }

    @ParameterizedTest @MethodSource("unknownSaveCases")
    void ordinarySaveFailureNeverAutomaticallyRefundsItem(Backend backend, boolean committed) {
        Fixture f = fixture(backend);
        AtomicBoolean saveReached = new AtomicBoolean();
        ArmyRepository failingArmy = new DelegatingArmy(f.armies()) {
            @Override public long save(String playerId, ArmyState army, long expectedVersion) {
                saveReached.set(true);
                assertThat(f.bag().countOf(playerId, ITEM)).isEqualTo(1L);
                if (committed) { super.save(playerId, army, expectedVersion); }
                // 两种实际结果返回同类异常；生产不能根据它推断是否已写入。
                throw new IllegalStateException("army save acknowledgement unavailable");
            }
        };

        assertThatThrownBy(() -> service(failingArmy, f.bag()).speedUp(f.playerId(), request()))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("acknowledgement unavailable");
        assertThat(saveReached).isTrue();
        assertThat(rereadItemCount(f)).as("未知结果不能一概返令").isEqualTo(1L);
        long reductionMs = committed ? configs.get(ItemCfg.class, ITEM).effectValue() * 1_000L : 0L;
        ArmyState stored = rereadArmy(f);
        assertThat(stored.queue().get(UNIT).finishAt()).isEqualTo(f.finishAt() - reductionMs);
        assertThat(stored.countOf(UNIT)).isEqualTo(700L);
        assertThat(stored.snapshot().rallyRefunds()).isEmpty();
        assertThat(f.armies().versionOf(f.playerId())).isEqualTo(committed ? 1L : 0L);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void missingArmyIsNotClassifiedAsVersionConflict(Backend backend) {
        ArmyRepository armies = backend == Backend.MEMORY
                ? new InMemoryArmyStore() : new MongoArmyStore(db.template());
        assertThatThrownBy(() -> armies.save("missing-" + UUID.randomUUID(), new ArmyState(), 0L))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessageContaining("不存在");
    }

    private static class DelegatingArmy implements ArmyRepository {
        private final ArmyRepository delegate;
        DelegatingArmy(ArmyRepository delegate) { this.delegate = delegate; }
        @Override public Optional<ArmyState> findByPlayerId(String playerId) {
            return delegate.findByPlayerId(playerId);
        }
        @Override public boolean insertIfAbsent(String playerId, ArmyState army) {
            return delegate.insertIfAbsent(playerId, army);
        }
        @Override public long save(String playerId, ArmyState army, long expectedVersion) {
            return delegate.save(playerId, army, expectedVersion);
        }
        @Override public long versionOf(String playerId) { return delegate.versionOf(playerId); }
    }
}
