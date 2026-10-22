package com.ironoath.web;

import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.social.Rally;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.web.dto.generated.MarchIdReq;
import com.ironoath.web.service.MarchAppService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.world.TargetOccupationStore;
import com.ironoath.web.world.TargetOperationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 生产recall→processDue→refundOnce集成；独立上下文/命名夹具，不冒充真实胜战。 */
@SpringBootTest(properties="ironoath.test.fixture=strategic-withdrawal-2368")
@ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class StrategicWithdrawalEndpointFlowTest {
    @Autowired MarchAppService service;
    @Autowired MarchRepository marches;
    @Autowired ArmyRepository armies;
    @Autowired TargetOccupationStore owners;
    @Autowired SocialStore social;
    @SpyBean TargetOperationStore operations;
    private March fixture(){
        String suffix=UUID.randomUUID().toString();String player="owned-withdraw-"+suffix;
        var m=new March("owned-march-"+suffix,player,Coord.of(255,216),Coord.of(256,216),100,101,
                Map.of("unit_infantry_t4",2L),List.of(),100,1,March.TargetType.STRATEGIC_TARGET,"gate_north",March.Action.ATTACK);
        m.attachToRally("owned-rally-"+suffix);m.arrive(101);m.holdStrategicVictory(Map.of(player,m.units()));
        var rally=Rally.restore(m.rallyId(),Rally.Scope.NATION,"N-"+suffix,player,2,1,100,101,
                Map.of(player,new Rally.Participant(player,m.units(),List.of())),Rally.Status.PREPARING,null,256,216,"STRATEGIC_TARGET",1);
        rally.depart(101);rally.arrive();social.saveRally(rally,0);armies.insertIfAbsent(player,new ArmyState());marches.insertIfAbsent(m);
        var prior=owners.findByTargetIds(List.of("gate_north")).get("gate_north");long version=prior==null?0:prior.version();
        assertThat(owners.compareAndSet(new TargetOccupationStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,256,216,
                "N-"+suffix,"撤防验收国",100,"fixture-capture-"+suffix,version+1,TargetOccupationStore.Garrison.stationed(m.id())),version)).isTrue();
        return m;
    }

    @Test void sameRequestReplaysBeforeAcquireAndDueScanThenReturnsTroopsOnce(){
        var m=fixture();var request=new MarchIdReq(UUID.randomUUID().toString(),m.id());
        var first=service.recall(m.playerId(),request);
        var replay=service.recall(m.playerId(),request);
        assertThat(replay.returnArriveAt()).isEqualTo(first.returnArriveAt());
        assertThat(marches.findById(m.id())).isPresent();assertThat(armies.findByPlayerId(m.playerId()).orElseThrow().totalTroops()).isZero();
        service.processDue(m.playerId(),first.returnArriveAt()+100);
        assertThat(marches.findById(m.id())).isEmpty();var army=armies.findByPlayerId(m.playerId()).orElseThrow();
        assertThat(army.countOf("unit_infantry_t4")).isEqualTo(2L);assertThat(army.snapshot().rallyRefunds()).hasSize(1);
        assertThat(service.recall(m.playerId(),request).returnArriveAt()).isEqualTo(first.returnArriveAt());
        service.processDue(m.playerId(),first.returnArriveAt()+200);
        assertThat(armies.findByPlayerId(m.playerId()).orElseThrow().totalTroops()).isEqualTo(2L);
    }

    @Test void incompleteSlotPreventsActualRefundAndMarchDeletionAfterReturnTime(){
        var m=fixture();var failure=new IllegalStateException("owned completion remains unavailable");
        doAnswer(inv->{TargetOperationStore.Snapshot next=inv.getArgument(0);if(next.completed())throw failure;return inv.callRealMethod();})
                .when(operations).compareAndSet(any(),anyLong());
        assertThatThrownBy(()->service.recall(m.playerId(),new MarchIdReq(UUID.randomUUID().toString(),m.id()))).isSameAs(failure);
        var slot=operations.findByTargetId("gate_north").orElseThrow();long at=slot.plan().returning().returnArriveAt();
        service.processDue(m.playerId(),at+100);
        assertThat(armies.findByPlayerId(m.playerId()).orElseThrow().totalTroops()).isZero();
        assertThat(marches.findById(m.id())).isPresent();assertThat(operations.findByTargetId("gate_north").orElseThrow().completed()).isFalse();
        doCallRealMethod().when(operations).compareAndSet(any(),anyLong());
        service.processDue(m.playerId(),at+200);
        assertThat(marches.findById(m.id())).isEmpty();assertThat(armies.findByPlayerId(m.playerId()).orElseThrow().totalTroops()).isEqualTo(2L);
    }
}
