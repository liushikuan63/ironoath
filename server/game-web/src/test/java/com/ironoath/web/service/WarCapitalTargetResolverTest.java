package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.lock.InJvmPlayerLock;
import com.ironoath.web.nation.NationOperationLock;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.nation.WarStore;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class WarCapitalTargetResolverTest {
    @Test
    void stableTargetScopeRecoversBeforeLockThenResolvesWithoutNestedRecovery() {
        NationCapitalRelocationService relocations = mock(NationCapitalRelocationService.class);
        when(relocations.withCapitalTargetLock(any())).thenAnswer(call -> ((Supplier<?>)call.getArgument(0)).get());
        NationStore nations = mock(NationStore.class);
        WarStore wars = mock(WarStore.class);
        WorldRepository world = mock(WorldRepository.class);
        when(wars.findLatest()).thenReturn(Optional.empty());
        ConfigRegistry configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var resolver = new WarCapitalTargetResolver(nations, wars, world, relocations);

        resolver.withStableTarget(()->resolver.resolve(configs));

        InOrder order=inOrder(relocations,wars);
        order.verify(relocations).recoverPending();
        order.verify(relocations).withCapitalTargetLock(any());
        order.verify(relocations).hasPendingRelocations();
        order.verify(wars).findLatest();
        org.mockito.Mockito.verify(relocations,org.mockito.Mockito.times(1)).recoverPending();
    }

    @Test
    void afterRecoveryStableTargetScopeDoesNotAcquireAnotherNationLock() {
        var playerLocks=new InJvmPlayerLock();
        NationCapitalRelocationService relocations=mock(NationCapitalRelocationService.class);
        when(relocations.withCapitalTargetLock(any())).thenAnswer(call -> ((Supplier<?>)call.getArgument(0)).get());
        when(relocations.hasPendingRelocations()).thenReturn(false);
        var resolver=new WarCapitalTargetResolver(mock(NationStore.class),mock(WarStore.class),
                mock(WorldRepository.class),relocations);

        String result=new NationOperationLock(playerLocks).runLocked("rally-nation",
                ()->resolver.withStableTargetAfterRecovery(()->"stable"));

        assertThat(result).isEqualTo("stable");
        verify(relocations,never()).recoverPending();
    }

    @Test
    void afterRecoveryStableTargetFailsClosedWhenRelocationStillPending() {
        NationCapitalRelocationService relocations=mock(NationCapitalRelocationService.class);
        when(relocations.withCapitalTargetLock(any())).thenAnswer(call -> ((Supplier<?>)call.getArgument(0)).get());
        when(relocations.hasPendingRelocations()).thenReturn(true);
        var resolver=new WarCapitalTargetResolver(mock(NationStore.class),mock(WarStore.class),
                mock(WorldRepository.class),relocations);
        var called=new java.util.concurrent.atomic.AtomicBoolean();

        assertThatThrownBy(()->resolver.withStableTargetAfterRecovery(()->{called.set(true);return null;}))
                .isInstanceOf(com.ironoath.common.BizException.class);
        assertThat(called.get()).isFalse();
        verify(relocations,never()).recoverPending();
    }

    @Test
    void recoversPendingRelocationBeforeResolvingDynamicCapitalTarget() {
        NationCapitalRelocationService relocations = mock(NationCapitalRelocationService.class);
        NationStore nations = mock(NationStore.class);
        WarStore wars = mock(WarStore.class);
        WorldRepository world = mock(WorldRepository.class);
        when(wars.findLatest()).thenReturn(Optional.empty());
        ConfigRegistry configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));

        new WarCapitalTargetResolver(nations, wars, world, relocations).resolve(configs);

        InOrder order = inOrder(relocations, wars);
        order.verify(relocations).recoverPending();
        order.verify(wars).findLatest();
    }
}
