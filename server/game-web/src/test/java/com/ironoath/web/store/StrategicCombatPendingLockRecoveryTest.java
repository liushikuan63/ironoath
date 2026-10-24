package com.ironoath.web.store;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyState;
import com.ironoath.common.BizException;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.web.lock.InJvmPlayerLock;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.service.StrategicTargetMarchAdvancer;
import com.ironoath.web.store.memory.InMemoryTargetOccupationStore;
import com.ironoath.web.store.memory.InMemoryTargetOperationStore;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryWarStore;
import com.ironoath.web.store.memory.SortedMarchDueQueue;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.web.battle.StrategicRallyCasualties;
import com.ironoath.web.battle.StrategicWoundSettlement;
import com.ironoath.web.world.StrategicGarrisonWithdrawal;
import com.ironoath.web.world.StrategicCombatRecovery;
import com.ironoath.web.world.StrategicTargetDefender;
import com.ironoath.web.world.TargetOperationStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pending COMBAT retries must use frozen battle rallies before reading partially written target state. */
class StrategicCombatPendingLockRecoveryTest {
    @Test void pendingCombatRecoveryMustPersistEveryMembersHospitalReceiptBeforeAnyLaterEffect() {
        var battle=StrategicCombatSlotTest.battle(2399,true,true);var plan=TargetOperationStore.CombatPlan.create(battle,1);
        var operations=new InMemoryTargetOperationStore();assertThat(operations.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,plan),0)).isTrue();
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var armies=new InMemoryArmyStore();
        for(var side:List.of(battle.attacker(),battle.defender()))for(String player:side.currentMembers().keySet()) {
            var army=new ArmyState();army.add("unit_infantry_t4",100);assertThat(armies.insertIfAbsent(player,army)).isTrue();
        }
        var rules=new WarRulesAssembler(configs);var wars=new InMemoryWarStore(rules);
        var board=new WarScoreBoard(rules.rules(),battle.happenedAt()-10,"owned-N",battle.happenedAt()+1000);
        board.registerNation("owned-N");board.registerNation(battle.defender().rally().nationId());assertThat(wars.insertIfNoneActive(board)).isTrue();
        var members=new ArrayList<WarScoreBoard.BattleMember>();
        addMembers(members,battle.attacker(),battle.result().atkKillsByMember());addMembers(members,battle.defender(),battle.result().defKillsByMember());
        assertThat(wars.reserveBattle(battle.target().targetId(),1,plan.operationId(),battle.happenedAt(),members)).isPresent();

        var marches=new InMemoryMarchStore(32);var marchSnapshots=new LinkedHashMap<String,March.Snapshot>();var marchVersions=new LinkedHashMap<String,Long>();
        for(var side:List.of(battle.attacker(),battle.defender())) {
            var march=March.fromSnapshot(side.march());assertThat(marches.insertIfAbsent(march)).isTrue();
            while(marches.versionOf(march.id())<side.marchVersion())marches.save(march,marches.versionOf(march.id()));
            marchSnapshots.put(march.id(),march.snapshot());marchVersions.put(march.id(),marches.versionOf(march.id()));
        }
        var defenders=mock(StrategicTargetDefender.class);
        when(defenders.read(any())).thenThrow(new AssertionError("combat recovery must use the frozen plan"));
        var social=mock(SocialAppService.class);when(social.runRalliesDepartureLocked(anyCollection(),any())).thenAnswer(call->((Supplier<?>)call.getArgument(1)).get());
        var owners=new InMemoryTargetOccupationStore();var due=new SortedMarchDueQueue();
        var withdrawal=new StrategicGarrisonWithdrawal(configs,operations,owners,marches,
                due,defenders,social,new InJvmPlayerLock(),new StrategicCombatRecovery(operations,armies,marches));

        var advancer=new StrategicTargetMarchAdvancer(configs,marches,defenders,social,withdrawal,operations);
        var attackMarch=March.fromSnapshot(battle.attacker().march());var advanced=new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(()->advancer.advance(attackMarch,battle.happenedAt(),()->{advanced.incrementAndGet();return true;}))
                .isInstanceOf(BizException.class).hasMessageContaining("目标操作尚未确认完成");
        assertThat(advanced.get()).isZero();
        assertThat(owners.findByTargetIds(List.of(battle.target().targetId()))).doesNotContainKey(battle.target().targetId());
        assertThat(due.dueBefore(battle.happenedAt()+10_000,16)).isEmpty();

        var firstReceipts=new LinkedHashMap<String,ArmyState.StrategicWoundReceipt>();
        for(var side:List.of(battle.attacker(),battle.defender()))for(String player:side.currentMembers().keySet()) {
            var army=armies.findByPlayerId(player).orElseThrow();var receipt=army.snapshot().strategicWounds().get(battle.target().targetId());
            assertThat(receipt)
                    .as("every actual member gets one persistent hospital receipt before recovery advances")
                    .isNotNull();firstReceipts.put(player,receipt);
        }
        for(String marchId:marchSnapshots.keySet()) {
            assertThat(marches.findById(marchId).orElseThrow().snapshot()).isEqualTo(marchSnapshots.get(marchId));
            assertThat(marches.versionOf(marchId)).isEqualTo(marchVersions.get(marchId));
        }
        assertThat(operations.findByTargetId(battle.target().targetId())).contains(TargetOperationStore.Snapshot.combat(1,false,plan));
        assertThat(wars.pendingBattleReservation(battle.happenedAt()-10,plan.operationId())).isPresent();

        withdrawal.recoverPending();
        assertThatThrownBy(()->advancer.advance(attackMarch,battle.happenedAt(),()->{advanced.incrementAndGet();return true;}))
                .isInstanceOf(BizException.class).hasMessageContaining("目标操作尚未确认完成");
        assertThat(advanced.get()).isZero();
        firstReceipts.forEach((player,receipt)->{
            var army=armies.findByPlayerId(player).orElseThrow();
            assertThat(army.snapshot().strategicWounds().get(battle.target().targetId())).isEqualTo(receipt);
            assertThat(army.totalWounded()).isEqualTo(receipt.admitted().values().stream().mapToLong(Long::longValue).sum());
        });
        assertThat(operations.findByTargetId(battle.target().targetId())).contains(TargetOperationStore.Snapshot.combat(1,false,plan));
        assertThat(wars.pendingBattleReservation(battle.happenedAt()-10,plan.operationId())).isPresent();
    }

    private static void addMembers(List<WarScoreBoard.BattleMember> out,StrategicRallyCasualties side,Map<String,Long> kills) {
        side.currentMembers().keySet().forEach(player->out.add(new WarScoreBoard.BattleMember(player,side.rally().nationId(),kills.get(player))));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void pendingCombatUsesFrozenRalliesAndStopsAtUnsupportedRecoveryBarrier(boolean playerDefender) {
        var battle=StrategicCombatSlotTest.battle(2391,playerDefender);
        var plan=TargetOperationStore.CombatPlan.create(battle,1);
        var slot=TargetOperationStore.Snapshot.combat(1,false,plan);
        var operations=new InMemoryTargetOperationStore();assertThat(operations.compareAndSet(slot,0)).isTrue();
        var attacker=March.fromSnapshot(battle.attacker().march());
        var marches=mock(MarchRepository.class);when(marches.findById(attacker.id())).thenReturn(Optional.of(attacker));
        var defenders=mock(StrategicTargetDefender.class);
        when(defenders.read(any())).thenThrow(new AssertionError("must recover frozen COMBAT before live defender read"));
        var social=mock(SocialAppService.class);var lockSets=new ArrayList<List<String>>();
        when(social.runRalliesDepartureLocked(anyCollection(),any())).thenAnswer(call->{
            lockSets.add(List.copyOf((List<String>)call.getArgument(0)));
            return ((Supplier<?>)call.getArgument(1)).get();
        });
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        PlayerLock lock=new InJvmPlayerLock();
        var withdrawal=new StrategicGarrisonWithdrawal(configs,operations,new InMemoryTargetOccupationStore(),
                marches,mock(MarchDueQueue.class),defenders,social,lock);
        var advancer=new StrategicTargetMarchAdvancer(configs,marches,defenders,social,withdrawal,operations);

        assertThatThrownBy(()->advancer.advance(attacker,battle.happenedAt(),()->true))
                .isInstanceOf(BizException.class).hasMessageContaining("战略战斗操作尚未确认恢复");
        var expected=new ArrayList<String>();expected.add(battle.attacker().march().rallyId());
        if(playerDefender)expected.add(battle.defender().march().rallyId());
        assertThat(lockSets).containsExactly(expected.stream().sorted().toList());
        assertThat(operations.findByTargetId(slot.targetId())).contains(slot);
        verify(defenders,never()).read(any());
    }
}
