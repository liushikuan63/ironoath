package com.ironoath.web.store;

import com.ironoath.config.ConfigRegistry;
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
import com.ironoath.web.world.StrategicGarrisonWithdrawal;
import com.ironoath.web.world.StrategicTargetDefender;
import com.ironoath.web.world.TargetOperationStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
