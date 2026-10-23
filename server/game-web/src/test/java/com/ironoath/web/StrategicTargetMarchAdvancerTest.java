package com.ironoath.web;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.march.March;
import com.ironoath.core.social.Alliance;
import com.ironoath.core.social.Rally;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.web.lock.InJvmPlayerLock;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.service.StrategicTargetMarchAdvancer;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemoryNeutralTargetGarrisonStore;
import com.ironoath.web.store.memory.InMemoryTargetOccupationStore;
import com.ironoath.web.store.memory.InMemoryTargetOperationStore;
import com.ironoath.web.world.StrategicGarrisonWithdrawal;
import com.ironoath.web.world.StrategicTargetDefender;
import com.ironoath.web.world.TargetOccupationStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategicTargetMarchAdvancerTest {
    @Test
    void playerGarrisonRallyIsLockedBeforeSharedTargetOperationLock() {
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var marches=new InMemoryMarchStore(32);var occupations=new InMemoryTargetOccupationStore();
        var neutral=new InMemoryNeutralTargetGarrisonStore();var socialStore=mock(SocialStore.class);
        var defenderRally=rally("owned-defender-rally","owned-defender","owned-defender-nation");
        when(socialStore.rallyOf(defenderRally.rallyId())).thenReturn(Optional.of(defenderRally));
        var defenderMarch=march("owned-defender-march","owned-defender",defenderRally.rallyId());
        defenderMarch.arrive(120);
        defenderMarch.holdStrategicVictory(Map.of("owned-defender",defenderMarch.units()));
        assertThat(marches.insertIfAbsent(defenderMarch)).isTrue();
        var owner=new TargetOccupationStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,256,216,
                "owned-defender-nation","Defender Nation",120,"battle:prior",1,
                TargetOccupationStore.Garrison.stationed(defenderMarch.id()));
        assertThat(occupations.compareAndSet(owner,0)).isTrue();
        var readers=new StrategicTargetDefender(configs,occupations,marches,socialStore,neutral);

        var attacker=march("owned-attacker-march","owned-attacker","owned-attacker-rally");
        assertThat(marches.insertIfAbsent(attacker)).isTrue();
        var attackRally=mock(SocialAppService.class);var order=new CopyOnWriteArrayList<String>();
        when(attackRally.runRalliesDepartureLocked(anyCollection(),any())).thenAnswer(call->{
            var ids=(List<String>)call.getArgument(0);order.add("nation+rallies:"+String.join(",",ids));
            return ((Supplier<?>)call.getArgument(1)).get();
        });
        var delegateLock=new InJvmPlayerLock();
        PlayerLock lock=new PlayerLock() {
            @Override public <T> T runLocked(String key,long timeout,Supplier<T> action) {
                order.add(key);return delegateLock.runLocked(key,timeout,action);
            }
        };
        var targetOperations=new StrategicGarrisonWithdrawal(configs,new InMemoryTargetOperationStore(),occupations,
                marches,mock(com.ironoath.core.march.MarchDueQueue.class),readers,attackRally,lock);

        var advancer=new StrategicTargetMarchAdvancer(configs,marches,readers,attackRally,targetOperations);
        assertThat(advancer.advance(attacker,120,()->true)).isTrue();
        assertThat(order).containsExactly("nation+rallies:owned-attacker-rally,owned-defender-rally",
                "@strategic-target:gate_north");
        assertThat(delegateLock.lockCount()).isEqualTo(1);
        verify(attackRally,never()).runRallyDepartureLocked(anyString(),any());
        verify(socialStore,org.mockito.Mockito.times(2)).rallyOf("owned-defender-rally");
    }

    @Test
    void changedDefenderContextReleasesOldLockSetAndRedispatchesBeforeTargetWork() {
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var marches=new InMemoryMarchStore(32);var occupations=new InMemoryTargetOccupationStore();
        var neutral=new InMemoryNeutralTargetGarrisonStore();var socialStore=mock(SocialStore.class);
        var oldRally=rally("owned-old-defender-rally","owned-old-defender","owned-old-nation");
        var newRally=rally("owned-new-defender-rally","owned-new-defender","owned-new-nation");
        when(socialStore.rallyOf(oldRally.rallyId())).thenReturn(Optional.of(oldRally));
        when(socialStore.rallyOf(newRally.rallyId())).thenReturn(Optional.of(newRally));
        var oldDefender=march("owned-old-defender-march","owned-old-defender",oldRally.rallyId());
        oldDefender.arrive(120);oldDefender.holdStrategicVictory(Map.of(oldDefender.playerId(),oldDefender.units()));
        var newDefender=march("owned-new-defender-march","owned-new-defender",newRally.rallyId());
        newDefender.arrive(120);newDefender.holdStrategicVictory(Map.of(newDefender.playerId(),newDefender.units()));
        assertThat(marches.insertIfAbsent(oldDefender)).isTrue();assertThat(marches.insertIfAbsent(newDefender)).isTrue();
        var oldOwner=owner(oldDefender,"owned-old-nation","old-owner",1);
        var newOwner=owner(newDefender,"owned-new-nation","new-owner",2);
        assertThat(occupations.compareAndSet(oldOwner,0)).isTrue();
        var defenderReader=new StrategicTargetDefender(configs,occupations,marches,socialStore,neutral);
        var attacker=march("owned-retry-attacker-march","owned-retry-attacker","owned-retry-attacker-rally");
        assertThat(marches.insertIfAbsent(attacker)).isTrue();
        var social=mock(SocialAppService.class);var lockSets=new CopyOnWriteArrayList<String>();var changed=new AtomicBoolean();
        when(social.runRalliesDepartureLocked(anyCollection(),any())).thenAnswer(call->{
            var ids=(List<String>)call.getArgument(0);lockSets.add(String.join(",",ids));
            if(changed.compareAndSet(false,true))assertThat(occupations.compareAndSet(newOwner,1)).isTrue();
            return ((Supplier<?>)call.getArgument(1)).get();
        });
        var lock=new InJvmPlayerLock();var operations=new StrategicGarrisonWithdrawal(configs,
                new InMemoryTargetOperationStore(),occupations,marches,mock(com.ironoath.core.march.MarchDueQueue.class),
                defenderReader,social,lock);
        var advancer=new StrategicTargetMarchAdvancer(configs,marches,defenderReader,social,operations);

        assertThat(advancer.advance(attacker,120,()->true)).isTrue();

        assertThat(lockSets).containsExactly(
                "owned-old-defender-rally,owned-retry-attacker-rally",
                "owned-new-defender-rally,owned-retry-attacker-rally");
    }

    @Test
    void neutralDefenderDoesNotInventASecondRallyLock() {
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var marches=new InMemoryMarchStore(32);var occupations=new InMemoryTargetOccupationStore();
        var socialStore=mock(SocialStore.class);var neutral=new InMemoryNeutralTargetGarrisonStore();
        var defenderReader=new StrategicTargetDefender(configs,occupations,marches,socialStore,neutral);
        var attacker=march("owned-npc-attacker-march","owned-npc-attacker","owned-npc-attacker-rally");
        assertThat(marches.insertIfAbsent(attacker)).isTrue();var social=mock(SocialAppService.class);
        when(social.runRalliesDepartureLocked(anyCollection(),any())).thenAnswer(call->{
            var ids=(List<String>)call.getArgument(0);assertThat(ids).containsExactly("owned-npc-attacker-rally");
            return ((Supplier<?>)call.getArgument(1)).get();
        });
        var operations=new StrategicGarrisonWithdrawal(configs,new InMemoryTargetOperationStore(),occupations,
                marches,mock(com.ironoath.core.march.MarchDueQueue.class),defenderReader,social,new InJvmPlayerLock());
        var advancer=new StrategicTargetMarchAdvancer(configs,marches,defenderReader,social,operations);
        assertThat(advancer.advance(attacker,120,()->true)).isTrue();
        verify(social).runRalliesDepartureLocked(anyCollection(),any());
    }

    private static Rally rally(String id,String player,String nation) {
        var troops=Map.of("unit_infantry_t4",1L);
        var rally=Rally.restore(id,Rally.Scope.NATION,nation,player,2,1,100,110,
                Map.of(player,new Rally.Participant(player,troops,List.of())),Rally.Status.PREPARING,null,
                256,216,"STRATEGIC_TARGET",1);
        rally.depart(110);return rally;
    }
    private static March march(String id,String player,String rallyId) {
        var march=new March(id,player,Coord.of(10,10),Coord.of(256,216),110,120,
                Map.of("unit_infantry_t4",1L),List.of(),100,1,March.TargetType.STRATEGIC_TARGET,
                "gate_north",March.Action.ATTACK);
        march.attachToRally(rallyId);return march;
    }
    private static TargetOccupationStore.Snapshot owner(March march,String nation,String operation,long version) {
        return new TargetOccupationStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,256,216,
                nation,"Owner",120,operation,version,TargetOccupationStore.Garrison.stationed(march.id()));
    }
}
