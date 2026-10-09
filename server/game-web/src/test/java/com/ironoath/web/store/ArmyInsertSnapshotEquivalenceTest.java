package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.mongo.MongoArmyStore;

/** 新建存档的原对象也须受版本保护，不能配上后来的版本抹掉返兵凭据。 */
class ArmyInsertSnapshotEquivalenceTest {
    enum Backend { MEMORY, MONGO }
    private static TestMongo db;
    private static final String UNIT = "unit_infantry_t1";

    @BeforeAll static void connect() {
        db = TestMongo.tryOpen();
        assertThat(db).as("真实 Mongo 必须可用，不能跳过新建快照等价验证").isNotNull();
    }
    @AfterAll static void close() { if (db != null) { db.close(); } }

    private ArmyRepository repository(Backend backend) {
        return backend == Backend.MEMORY ? new InMemoryArmyStore() : new MongoArmyStore(db.template());
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void successfullyInsertedSourceCannotOverwriteALaterRefundReceipt(Backend backend) {
        ArmyRepository repository = repository(backend);
        String playerId = "P-insert-" + UUID.randomUUID();
        ArmyState source = new ArmyState(); source.add(UNIT, 700L);
        assertThat(repository.insertIfAbsent(playerId, source)).isTrue();
        ArmyState newer = repository.findByPlayerId(playerId).orElseThrow();
        newer.refundRallyOnce("rally-return:once", Map.of(UNIT, 300L));
        repository.save(playerId, newer, 0L);
        source.add(UNIT, 1L);

        assertThatThrownBy(() -> repository.save(playerId, source, repository.versionOf(playerId)))
                .isInstanceOf(IllegalStateException.class);
        ArmyState stored = repository.findByPlayerId(playerId).orElseThrow();
        assertThat(stored.countOf(UNIT)).isEqualTo(1000L);
        assertThat(stored.hasRallyRefund("rally-return:once")).isTrue();
        assertThat(repository.versionOf(playerId)).isEqualTo(1L);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void aRejectedInsertDoesNotBindTheUnpersistedSource(Backend backend) {
        ArmyRepository repository = repository(backend);
        String playerId = "P-rejected-" + UUID.randomUUID();
        ArmyState persisted = new ArmyState(); persisted.add(UNIT, 1000L);
        assertThat(repository.insertIfAbsent(playerId, persisted)).isTrue();
        persisted.add(UNIT, 1L);
        assertThat(repository.save(playerId, persisted, 0L)).isEqualTo(1L);
        ArmyState rejected = new ArmyState(); rejected.add(UNIT, 700L);
        assertThat(repository.insertIfAbsent(playerId, rejected)).isFalse();

        // 失败对象未获任何读/写版本身份；不要把现存存档的版本授给这份不同内容。
        assertThatCode(() -> rejected.requireRepositoryVersion(2L)).doesNotThrowAnyException();
        assertThat(repository.findByPlayerId(playerId).orElseThrow().countOf(UNIT)).isEqualTo(1001L);
        assertThat(repository.versionOf(playerId)).isEqualTo(1L);
    }
}
