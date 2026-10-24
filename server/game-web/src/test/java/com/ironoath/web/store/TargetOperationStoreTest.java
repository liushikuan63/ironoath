package com.ironoath.web.store;

import com.ironoath.core.march.March;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.web.world.TargetOccupationStore;
import com.ironoath.web.world.TargetOperationStore;
import com.ironoath.web.store.memory.InMemoryTargetOperationStore;
import com.ironoath.web.store.mongo.MongoTargetOperationStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 单目标只保当前计划；所有成员/队列步骤确认完成前不得推进代次。 */
class TargetOperationStoreTest {
    enum Backend { MEMORY, MONGO }
    private static TestMongo db;
    @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();}
    @AfterAll static void close(){if(db!=null)db.close();}
    private TargetOperationStore store(Backend b){
        if(b==Backend.MEMORY)return new InMemoryTargetOperationStore();
        db.template().remove(new org.springframework.data.mongodb.core.query.Query(),"world_target_operation");
        return new MongoTargetOperationStore(db.template());
    }
    static March stationed(String id){
        var m=new March(id,"owned-leader",Coord.of(255,216),Coord.of(256,216),100,110,
                Map.of("unit_infantry_t4",2L),List.of(),100,1,March.TargetType.STRATEGIC_TARGET,"gate_north",March.Action.ATTACK);
        m.attachToRally("owned-rally-"+id);m.arrive(110);m.holdStrategicVictory(Map.of(m.playerId(),m.units()));return m;
    }
    static TargetOccupationStore.Snapshot owner(March m,long version){
        return new TargetOccupationStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,256,216,
                "N-owned","验收国",100,"capture-"+m.id(),version,TargetOccupationStore.Garrison.stationed(m.id()));
    }
    static TargetOperationStore.WithdrawalPlan plan(String id,long sequence,long at){
        var m=stationed(id);return TargetOperationStore.WithdrawalPlan.create(owner(m,sequence*2-1),m,0,sequence,at);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void pendingPlanMustCompleteBeforeNextFrozenSequence(Backend b){
        var store=store(b);var first=plan("first",1,120);
        var active=new TargetOperationStore.Snapshot("gate_north",1,false,first);
        assertThat(store.compareAndSet(active,0)).isTrue();assertThat(store.compareAndSet(active,0)).isTrue();
        assertThat(store.pendingTargetIds(List.of("gate_north","gate_south"))).containsExactly("gate_north");
        var changed=new TargetOperationStore.Snapshot("gate_north",1,false,plan("first",1,121));
        assertThat(store.compareAndSet(changed,0)).as("原操作不得重算返程时刻").isFalse();
        assertThat(store.compareAndSet(new TargetOperationStore.Snapshot("gate_north",2,false,plan("second",2,120)),1)).isFalse();
        var done=new TargetOperationStore.Snapshot("gate_north",2,true,first);
        assertThat(store.compareAndSet(done,1)).isTrue();assertThat(store.compareAndSet(done,1)).isTrue();
        assertThat(store.pendingTargetIds(List.of("gate_north"))).isEmpty();
        assertThat(store.compareAndSet(new TargetOperationStore.Snapshot("gate_north",3,false,plan("first",2,120)),2)).as("当前操作不能取新代次重发").isFalse();
        assertThat(store.compareAndSet(new TargetOperationStore.Snapshot("gate_north",3,false,plan("second",4,120)),2)).as("不能跳代次").isFalse();
        var next=new TargetOperationStore.Snapshot("gate_north",3,false,plan("second",2,120));
        assertThat(store.compareAndSet(next,2)).isTrue();assertThat(store.compareAndSet(done,1)).isFalse();
        if(b==Backend.MONGO)store=new MongoTargetOperationStore(db.template());
        assertThat(store.findByTargetId("gate_north")).contains(next);
        assertThat(store.findByTargetIds(List.of("gate_north","gate_south")))
                .containsEntry("gate_north",next).doesNotContainKey("gate_south");
        assertThat(store.findByOperationId(next.plan().operationId())).contains(next);
        assertThat(store.findByOperationId(first.operationId())).isEmpty();
        assertThat(store.findAllTargetIds(2)).containsExactly("gate_north");
    }

    @Test void planFreezesExactRecallAndRejectsWrongOwnerOrMarch(){
        var m=stationed("frozen");var p=TargetOperationStore.WithdrawalPlan.create(owner(m,1),m,0,1,120);
        m.beginGarrisonDefense();m.applyLosses(Map.of("unit_infantry_t4",1L));
        assertThat(p.stationed().units()).containsEntry("unit_infantry_t4",2L);
        assertThat(p.returning().returnArriveAt()).isEqualTo(130L);
        assertThat(p.after().nationId()).isEqualTo(p.before().nationId());assertThat(p.after().capturedAt()).isEqualTo(p.before().capturedAt());
        assertThat(p.after().garrison()).isEqualTo(TargetOccupationStore.Garrison.empty());
        assertThat(p.after().operationId()).isEqualTo("withdraw:frozen");
        assertThatThrownBy(()->p.returning().units().put("unit_infantry_t4",4L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->TargetOperationStore.WithdrawalPlan.create(owner(stationed("other"),1),stationed("wrong"),0,1,120)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TargetOperationStore.Snapshot("gate_south",1,false,p)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TargetOperationStore.WithdrawalPlan(p.operationId(),p.sequence(),p.happenedAt(),p.before(),p.before(),p.marchVersion(),p.stationed(),p.returning())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void lostWriteReplyConfirmsExactPlanButUnreadableReplyRetainsOriginalFailure(){
        var real=store(Backend.MONGO);var mongo=spy(db.template());
        doAnswer(inv->{inv.callRealMethod();throw new IllegalStateException("owned journal insert reply lost");})
                .when(mongo).insert(any(com.ironoath.web.store.mongo.TargetOperationDocument.class),eq("world_target_operation"));
        var state=new TargetOperationStore.Snapshot("gate_north",1,false,plan("lost",1,120));
        assertThat(new MongoTargetOperationStore(mongo).compareAndSet(state,0)).isTrue();
        var failed=mock(org.springframework.data.mongodb.core.MongoTemplate.class);var write=new IllegalStateException("owned no write");var read=new IllegalStateException("owned no read");
        doThrow(write).when(failed).insert(any(com.ironoath.web.store.mongo.TargetOperationDocument.class),eq("world_target_operation"));
        doThrow(read).when(failed).findById(eq("gate_north"),eq(com.ironoath.web.store.mongo.TargetOperationDocument.class),eq("world_target_operation"));
        assertThatThrownBy(()->new MongoTargetOperationStore(failed).compareAndSet(state,0)).isSameAs(write).hasSuppressedException(read);
        assertThat(real.findByTargetId("gate_north")).contains(state);
    }

    @Test void pendingDiscoveryDoesNotDeserializeOneBrokenPlanOrStarveOtherCatalogTargets(){
        var store=store(Backend.MONGO);var state=new TargetOperationStore.Snapshot("gate_north",1,false,plan("broken",1,120));
        store.compareAndSet(state,0);
        db.template().updateFirst(new org.springframework.data.mongodb.core.query.Query(),new org.springframework.data.mongodb.core.query.Update().unset("state.plan"),"world_target_operation");
        assertThat(store.pendingTargetIds(List.of("gate_north","gate_south"))).containsExactly("gate_north");
        assertThatThrownBy(()->store.findByTargetId("gate_north")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->store.findByTargetIds(List.of("gate_north"))).isInstanceOf(RuntimeException.class);
    }
}
