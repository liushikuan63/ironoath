package com.ironoath.web;

import com.ironoath.core.march.March;
import com.ironoath.core.social.Rally;
import com.ironoath.core.world.Coord;
import com.ironoath.web.service.MarchAppService;
import com.ironoath.web.social.SocialStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Until COMBAT publication/recovery exists, strategic arrival must preserve durable fighting custody. */
class StrategicBattleArrivalDispatchTest {
    @Test
    void strategicAttackDoesNotSilentlyTurnIntoAnUnfoughtReturn() {
        String player="owned-strategic-attacker",rallyId="owned-strategic-rally";
        var troops=Map.of("unit_infantry_t4",10L);
        var rally=Rally.restore(rallyId,Rally.Scope.NATION,"owned-nation",player,2,1,100,110,
                Map.of(player,new Rally.Participant(player,troops,List.of())),Rally.Status.PREPARING,null,
                256,216,"STRATEGIC_TARGET",1);
        rally.depart(110);
        var march=new March("owned-strategic-march",player,Coord.of(10,10),Coord.of(256,216),110,120,
                troops,List.of(),100,1,March.TargetType.STRATEGIC_TARGET,"gate_north",March.Action.ATTACK);
        march.attachToRally(rallyId);march.arrive(120);
        var social=mock(SocialStore.class);when(social.rallyOf(rallyId)).thenReturn(Optional.of(rally));
        var service=mock(MarchAppService.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service,"socialStore",social);

        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service,"resolveAttack",march,120L,1L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("COMBAT");

        assertThat(march.status()).as("无 COMBAT 计划前不能把战略行军默默退回去").isEqualTo(March.Status.FIGHTING);
    }

    @Test
    void actualArrivalPersistsFightingAndRetainsDueEventBeforeUnsupportedCombatStops() {
        var march=new March("owned-strategic-due","owned-attacker",Coord.of(10,10),Coord.of(256,216),110,120,
                Map.of("unit_infantry_t4",10L),List.of(),100,1,March.TargetType.STRATEGIC_TARGET,
                "gate_north",March.Action.ATTACK);
        march.attachToRally("owned-strategic-due-rally");
        var marches=new com.ironoath.web.store.memory.InMemoryMarchStore(16);
        marches.insertIfAbsent(march);
        var due=new com.ironoath.web.store.memory.SortedMarchDueQueue();
        due.schedule(march.id(),120L);
        var social=mock(SocialStore.class);
        var service=mock(MarchAppService.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service,"marches",marches);
        ReflectionTestUtils.setField(service,"dueQueue",due);
        ReflectionTestUtils.setField(service,"socialStore",social);
        var configs=mock(com.ironoath.config.ConfigRegistry.class);
        when(configs.longParam("WORLD_CHUNK_SIZE")).thenReturn(16L);
        ReflectionTestUtils.setField(service,"configs",configs);
        ReflectionTestUtils.setField(service,"world",mock(com.ironoath.core.world.WorldRepository.class));
        ReflectionTestUtils.setField(service,"questEvents",mock(com.ironoath.web.quest.QuestEvents.class));

        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service,"advanceDueMarchLocked",march.id(),120L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("COMBAT");

        var frozen=marches.findById(march.id()).orElseThrow();
        assertThat(frozen.status()).isEqualTo(March.Status.FIGHTING);
        assertThat(frozen.units()).isEqualTo(march.units());
        assertThat(frozen.returnArriveAt()).isNull();
        assertThat(marches.versionOf(march.id())).isEqualTo(1L);
        assertThat(due.dueBefore(120L,10)).containsExactly(march.id());

        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service,"advanceDueMarchLocked",march.id(),121L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("COMBAT");
        assertThat(marches.findById(march.id()).orElseThrow().snapshot()).isEqualTo(frozen.snapshot());
        assertThat(marches.versionOf(march.id())).isEqualTo(1L);
        assertThat(due.dueBefore(121L,10)).containsExactly(march.id());
    }
}
