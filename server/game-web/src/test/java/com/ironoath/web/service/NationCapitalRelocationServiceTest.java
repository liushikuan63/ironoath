package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.nation.NationCapitalRelocationStore;
import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.nation.WarStore;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemoryNationCapitalRelocationStore;
import com.ironoath.web.store.memory.InMemoryTargetOccupationStore;
import com.ironoath.web.store.memory.InMemoryTargetOperationStore;
import com.ironoath.web.lock.InJvmPlayerLock;
import com.ironoath.web.world.TargetOccupationStore;
import com.ironoath.web.world.TargetOperationStore;
import org.junit.jupiter.api.Test;

class NationCapitalRelocationServiceTest {
    private static final String NATION_ID = "relocation-nation";
    private static final String KING_ID = "relocation-king";
    private static final long NOW = 1_800_000_000_000L;

    private static final class SimulatedProcessCrash extends Error {
        private static final long serialVersionUID = 1L;
    }

    @Test
    void restartRecognizesNationWriteThatCompletedBeforeProcessCrash() {
        ConfigRegistry configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        NationRulesAssembler rules = new NationRulesAssembler(configs);
        Nation initial = Nation.found(NATION_ID, "移都恢复测试国", KING_ID, "relocation-alliance",
                100, 200, NOW, rules.rules());
        AtomicReference<Nation> current = new AtomicReference<>(Nation.fromSnapshot(initial.snapshot(), rules.rules()));
        NationStore nations = mock(NationStore.class);
        when(nations.findById(NATION_ID)).thenAnswer(call -> Optional.of(
                Nation.fromSnapshot(current.get().snapshot(), rules.rules())));
        when(nations.save(any(Nation.class), anyLong())).thenAnswer(call -> {
            Nation submitted = call.getArgument(0);
            Nation persisted = Nation.fromSnapshot(submitted.snapshot(), rules.rules());
            persisted.incrementVersion();
            current.set(persisted);
            throw new SimulatedProcessCrash();
        });

        var ledger = new InMemoryNationCapitalRelocationStore();
        TargetOccupationStore occupations = new InMemoryTargetOccupationStore();
        TargetOperationStore operations = new InMemoryTargetOperationStore();
        MarchRepository marches = mock(MarchRepository.class);
        WarStore wars = mock(WarStore.class);
        WorldRepository world = mock(WorldRepository.class);
        PlayerRepository players = mock(PlayerRepository.class);
        when(wars.findLatest()).thenReturn(Optional.empty());
        when(world.cityAt(any(Coord.class))).thenReturn(Optional.empty());
        when(marches.findByTargetId(anyString())).thenReturn(List.of());
        PlayerLock locks = new InJvmPlayerLock();
        TimeService time = new TimeService(() -> NOW);
        var service = new NationCapitalRelocationService(nations, rules, ledger, occupations, operations,
                marches, wars, configs, world, players, locks, time);
        Coord destination = firstFreeCapital(configs);

        assertThatThrownBy(() -> service.relocate(KING_ID, NATION_ID, "request-1", destination.x(), destination.y()))
                .isInstanceOf(SimulatedProcessCrash.class);
        assertThat(ledger.findPending(10)).singleElement()
                .extracting(NationCapitalRelocationStore.Snapshot::stage)
                .isEqualTo(NationCapitalRelocationStore.Stage.OCCUPATION_MOVED);

        var restarted = new NationCapitalRelocationService(nations, rules, ledger, occupations, operations,
                marches, wars, configs, world, players, locks, time);
        restarted.recoverPending();

        assertThat(current.get().capitalX()).isEqualTo(destination.x());
        assertThat(current.get().capitalY()).isEqualTo(destination.y());
        assertThat(ledger.findPending(10)).isEmpty();
        assertThat(ledger.findByNationId(NATION_ID)).get()
                .extracting(NationCapitalRelocationStore.Snapshot::stage)
                .isEqualTo(NationCapitalRelocationStore.Stage.COMPLETED);
        verify(nations, times(1)).save(any(Nation.class), anyLong());
    }

    @Test
    void relocationMovesTheExistingGarrisonAndOccupationWithoutChangingOwnerShares() {
        ConfigRegistry configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        NationRulesAssembler rules = new NationRulesAssembler(configs);
        Nation initial = Nation.found(NATION_ID, "驻军迁都测试国", KING_ID, "relocation-alliance",
                100, 200, NOW, rules.rules());
        AtomicReference<Nation> current = new AtomicReference<>(Nation.fromSnapshot(initial.snapshot(), rules.rules()));
        NationStore nations = mock(NationStore.class);
        when(nations.findById(NATION_ID)).thenAnswer(call -> Optional.of(
                Nation.fromSnapshot(current.get().snapshot(), rules.rules())));
        when(nations.save(any(Nation.class), anyLong())).thenAnswer(call -> {
            Nation submitted = call.getArgument(0);
            Nation persisted = Nation.fromSnapshot(submitted.snapshot(), rules.rules());
            persisted.incrementVersion();
            current.set(persisted);
            return persisted.version();
        });

        var occupations = new InMemoryTargetOccupationStore();
        var marches = new InMemoryMarchStore(8);
        var ownerShares = Map.of(KING_ID, Map.of("unit_infantry_t4", 3L),
                "garrison-member", Map.of("unit_archer_t4", 2L));
        var garrison = new March("relocation-garrison", KING_ID, Coord.of(10, 10), Coord.of(100, 200),
                NOW - 20, NOW - 10, Map.of("unit_infantry_t4", 3L, "unit_archer_t4", 2L), List.of(),
                50, 1, March.TargetType.STRATEGIC_TARGET, "capital_center", March.Action.ATTACK);
        garrison.attachToRally("relocation-rally");
        garrison.arrive(NOW - 10);
        garrison.holdStrategicVictory(ownerShares);
        assertThat(marches.insertIfAbsent(garrison)).isTrue();
        var occupation = new TargetOccupationStore.Snapshot("capital_center",
                com.ironoath.core.world.WorldGenerator.EntityType.CAPITAL, 100, 200, NATION_ID,
                "驻军迁都测试国", NOW - 100, "capital-capture-1", 1,
                TargetOccupationStore.Garrison.stationed(garrison.id()));
        assertThat(occupations.compareAndSet(occupation, 0)).isTrue();

        var ledger = new InMemoryNationCapitalRelocationStore();
        TargetOperationStore operations = new InMemoryTargetOperationStore();
        WarScoreBoard currentWar = mock(WarScoreBoard.class);
        when(currentWar.targetNationId()).thenReturn(NATION_ID);
        WarStore wars = mock(WarStore.class);
        when(wars.findLatest()).thenReturn(Optional.of(currentWar));
        WorldRepository world = mock(WorldRepository.class);
        when(world.cityAt(any(Coord.class))).thenReturn(Optional.empty());
        PlayerRepository players = mock(PlayerRepository.class);
        var service = new NationCapitalRelocationService(nations, rules, ledger, occupations, operations,
                marches, wars, configs, world, players, new InJvmPlayerLock(), new TimeService(() -> NOW));
        Coord destination = firstFreeCapital(configs);

        service.relocate(KING_ID, NATION_ID, "garrison-noop-0", 100, 200);
        service.relocate(KING_ID, NATION_ID, "garrison-move-1", destination.x(), destination.y());

        var movedMarch = marches.findById(garrison.id()).orElseThrow();
        assertThat(movedMarch.status()).isEqualTo(March.Status.STATIONED);
        assertThat(movedMarch.to()).isEqualTo(destination);
        assertThat(movedMarch.garrisonTroopsByPlayer()).isEqualTo(ownerShares);
        assertThat(occupations.findByTargetIds(List.of("capital_center")).get("capital_center"))
                .satisfies(moved -> {
                    assertThat(moved.x()).isEqualTo(destination.x());
                    assertThat(moved.y()).isEqualTo(destination.y());
                    assertThat(moved.garrison()).isEqualTo(TargetOccupationStore.Garrison.stationed(garrison.id()));
                });
        assertThat(current.get().capitalX()).isEqualTo(destination.x());
        assertThat(current.get().capitalY()).isEqualTo(destination.y());
        assertThat(ledger.findByNationId(NATION_ID)).get()
                .extracting(NationCapitalRelocationStore.Snapshot::stage)
                .isEqualTo(NationCapitalRelocationStore.Stage.COMPLETED);

        Coord nextDestination = firstFreeCapitalExcept(configs, destination);
        service.relocate(KING_ID, NATION_ID, "garrison-move-2", nextDestination.x(), nextDestination.y());
        service.relocate(KING_ID, NATION_ID, "garrison-move-1", destination.x(), destination.y());
        service.relocate(KING_ID, NATION_ID, "garrison-noop-0", 100, 200);
        assertThat(current.get().capitalX()).isEqualTo(nextDestination.x());
        assertThat(current.get().capitalY()).isEqualTo(nextDestination.y());
    }

    @Test
    void pendingCapitalCombatRejectsMigrationBeforeWritingIntent() {
        ConfigRegistry configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        NationRulesAssembler rules = new NationRulesAssembler(configs);
        Nation initial = Nation.found(NATION_ID, "战斗期间迁都测试国", KING_ID, "relocation-alliance",
                100, 200, NOW, rules.rules());
        AtomicReference<Nation> current = new AtomicReference<>(Nation.fromSnapshot(initial.snapshot(), rules.rules()));
        NationStore nations = mock(NationStore.class);
        when(nations.findById(NATION_ID)).thenAnswer(call -> Optional.of(
                Nation.fromSnapshot(current.get().snapshot(), rules.rules())));
        var ledger = new InMemoryNationCapitalRelocationStore();
        TargetOperationStore operations = mock(TargetOperationStore.class);
        when(operations.pendingTargetIds(any())).thenReturn(List.of("capital_center"));
        WarScoreBoard currentWar = mock(WarScoreBoard.class);
        when(currentWar.targetNationId()).thenReturn(NATION_ID);
        WarStore wars = mock(WarStore.class);
        when(wars.findLatest()).thenReturn(Optional.of(currentWar));
        WorldRepository world = mock(WorldRepository.class);
        when(world.cityAt(any(Coord.class))).thenReturn(Optional.empty());
        var service = new NationCapitalRelocationService(nations, rules, ledger,
                new InMemoryTargetOccupationStore(), operations, mock(MarchRepository.class), wars,
                configs, world, mock(PlayerRepository.class), new InJvmPlayerLock(), new TimeService(() -> NOW));
        Coord destination = firstFreeCapital(configs);

        assertThatThrownBy(() -> service.relocate(KING_ID, NATION_ID, "combat-blocked-1",
                destination.x(), destination.y())).isInstanceOf(com.ironoath.common.BizException.class);
        assertThat(ledger.findByNationId(NATION_ID)).isEmpty();
        assertThat(current.get().capitalX()).isEqualTo(100);
        assertThat(current.get().capitalY()).isEqualTo(200);
    }

    @Test
    void unsettledAttackMarchToOldCapitalRejectsMigrationBeforeWritingIntent() {
        ConfigRegistry configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        NationRulesAssembler rules = new NationRulesAssembler(configs);
        Nation initial = Nation.found(NATION_ID, "旧都来袭测试国", KING_ID, "relocation-alliance",
                100, 200, NOW, rules.rules());
        AtomicReference<Nation> current = new AtomicReference<>(Nation.fromSnapshot(initial.snapshot(), rules.rules()));
        NationStore nations = mock(NationStore.class);
        when(nations.findById(NATION_ID)).thenAnswer(call -> Optional.of(
                Nation.fromSnapshot(current.get().snapshot(), rules.rules())));
        var inbound = new March("old-capital-inbound", "attacker", Coord.of(10, 10), Coord.of(100, 200),
                NOW - 20, NOW + 80, Map.of("unit_infantry_t4", 1L), List.of(), 10, 1,
                March.TargetType.STRATEGIC_TARGET, "capital_center", March.Action.ATTACK);
        inbound.attachToRally("old-capital-inbound-rally");
        assertThat(inbound.status()).isEqualTo(March.Status.MARCHING);
        MarchRepository marches = mock(MarchRepository.class);
        when(marches.findByTargetId("capital_center")).thenReturn(List.of(inbound));
        WarScoreBoard currentWar = mock(WarScoreBoard.class);
        when(currentWar.targetNationId()).thenReturn(NATION_ID);
        WarStore wars = mock(WarStore.class);
        when(wars.findLatest()).thenReturn(Optional.of(currentWar));
        WorldRepository world = mock(WorldRepository.class);
        when(world.cityAt(any(Coord.class))).thenReturn(Optional.empty());
        var ledger = new InMemoryNationCapitalRelocationStore();
        var service = new NationCapitalRelocationService(nations, rules, ledger,
                new InMemoryTargetOccupationStore(), new InMemoryTargetOperationStore(), marches, wars,
                configs, world, mock(PlayerRepository.class), new InJvmPlayerLock(), new TimeService(() -> NOW));
        Coord destination = firstFreeCapital(configs);

        assertThatThrownBy(() -> service.relocate(KING_ID, NATION_ID, "inbound-blocked-1",
                destination.x(), destination.y())).isInstanceOf(com.ironoath.common.BizException.class);
        assertThat(ledger.findByNationId(NATION_ID)).isEmpty();
        assertThat(current.get().capitalX()).isEqualTo(100);
        assertThat(current.get().capitalY()).isEqualTo(200);
    }

    private static Coord firstFreeCapitalExcept(ConfigRegistry configs, Coord excluded) {
        var targets = WorldTargets.from(configs);
        int size = Math.toIntExact(configs.longParam("WORLD_SIZE"));
        for (int x = 1; x < size - 1; x++) {
            for (int y = 1; y < size - 1; y++) {
                Coord candidate = Coord.of(x, y);
                if (!candidate.equals(excluded) && !targets.reserved(candidate)) return candidate;
            }
        }
        throw new AssertionError("test config has no second free world coordinate");
    }

    private static Coord firstFreeCapital(ConfigRegistry configs) {
        var targets = WorldTargets.from(configs);
        int size = Math.toIntExact(configs.longParam("WORLD_SIZE"));
        for (int x = 1; x < size - 1; x++) {
            for (int y = 1; y < size - 1; y++) {
                Coord candidate = Coord.of(x, y);
                if (!targets.reserved(candidate)) return candidate;
            }
        }
        throw new AssertionError("test config has no free world coordinate");
    }
}
