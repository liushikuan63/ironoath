package com.ironoath.web.store;

import com.ironoath.core.world.WorldGenerator;
import com.ironoath.web.world.TargetOccupationStore;
import com.ironoath.web.store.memory.InMemoryTargetOccupationStore;
import com.ironoath.web.store.mongo.MongoTargetOccupationStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 同组断言覆盖内存/真实Mongo，Mongo重建仓储后继续读；全部使用TestMongo自有临时库。 */
class TargetOccupationStoreTest {
    enum Backend { MEMORY, MONGO }
    private static TestMongo db;
    @BeforeAll static void open() { db=TestMongo.tryOpen(); assertThat(db).isNotNull(); }
    @AfterAll static void close() { if(db!=null)db.close(); }

    private TargetOccupationStore store(Backend backend) {
        if(backend==Backend.MEMORY)return new InMemoryTargetOccupationStore();
        db.template().remove(new org.springframework.data.mongodb.core.query.Query(),"world_target_occupation");
        return new MongoTargetOccupationStore(db.template());
    }
    private TargetOccupationStore.Snapshot state(String nation, long version, String op) {
        return new TargetOccupationStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,256,216,
                nation,"国家"+nation,100L,op,version);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void compareAndSetIsIdempotentAndNeverOverwritesNewOwner(Backend backend) {
        var store=store(backend); var first=state("N1",1L,"battle-1");
        assertThat(store.findByTargetIds(List.of("gate_north"))).isEmpty();
        assertThat(store.compareAndSet(first,0L)).isTrue();
        assertThat(store.compareAndSet(first,0L)).as("丢回执后原样重放").isTrue();
        assertThat(store.compareAndSet(state("N2",1L,"battle-1"),0L)).as("同操作号异内容不可回放").isFalse();
        assertThat(store.compareAndSet(state("N2",2L,"battle-1"),1L))
                .as("同操作号也不能换新期望版本掩盖异内容").isFalse();
        assertThat(store.compareAndSet(state("N2",1L,"battle-2"),0L)).isFalse();
        var second=state("N2",2L,"battle-2");
        assertThat(store.compareAndSet(second,1L)).isTrue();
        assertThat(store.compareAndSet(first,0L)).as("旧战斗不能把新归属改回去").isFalse();
        if(backend==Backend.MONGO)store=new MongoTargetOccupationStore(db.template());
        assertThat(store.findByTargetIds(List.of("gate_north","not-present")))
                .containsOnlyKeys("gate_north").containsEntry("gate_north",second);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void invalidVersionsAndBoundedReadsCannotMutateOwners(Backend backend) {
        var store=store(backend);
        assertThatThrownBy(()->store.compareAndSet(state("N1",2,"bad"),0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.findAll(1)).isEmpty();
        assertThat(store.compareAndSet(state("N1",1,"good"),0)).isTrue();
        var capital=new TargetOccupationStore.Snapshot("capital_center",WorldGenerator.EntityType.CAPITAL,
                256,256,"N1","国家N1",200,"capital-battle",1);
        assertThat(store.compareAndSet(capital,0)).isTrue();
        assertThat(store.findAll(1)).hasSize(1);
        assertThat(store.findAll(3)).hasSize(2);
        assertThatThrownBy(()->store.findAll(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void lostInsertAndUpdateReceiptsAreConfirmedThroughIndependentReadback() {
        var real=db.template();
        real.remove(new org.springframework.data.mongodb.core.query.Query(),"world_target_occupation");
        var uncertain=spy(real);
        doAnswer(inv->{inv.callRealMethod();throw new IllegalStateException("owned lost insert receipt");})
                .when(uncertain).insert(any(com.ironoath.web.store.mongo.TargetOccupationDocument.class),
                        eq("world_target_occupation"));
        var store=new MongoTargetOccupationStore(uncertain);
        assertThat(store.compareAndSet(state("N1",1,"insert"),0)).isTrue();
        doAnswer(inv->{inv.callRealMethod();throw new IllegalStateException("owned lost update receipt");})
                .when(uncertain).updateFirst(any(org.springframework.data.mongodb.core.query.Query.class),
                        any(org.springframework.data.mongodb.core.query.UpdateDefinition.class),
                        eq(com.ironoath.web.store.mongo.TargetOccupationDocument.class),eq("world_target_occupation"));
        var next=state("N2",2,"update");
        assertThat(store.compareAndSet(next,1)).isTrue();
        assertThat(new MongoTargetOccupationStore(real).findByTargetIds(List.of("gate_north")))
                .containsEntry("gate_north",next);
    }

    @Test void unconfirmedWriteFailureIsPropagatedWithReadFailurePreserved() {
        var broken=mock(org.springframework.data.mongodb.core.MongoTemplate.class);
        var write=new IllegalStateException("owned no write");
        var read=new IllegalStateException("owned no readback");
        doThrow(write).when(broken).insert(any(com.ironoath.web.store.mongo.TargetOccupationDocument.class),
                eq("world_target_occupation"));
        doThrow(read).when(broken).find(any(org.springframework.data.mongodb.core.query.Query.class),
                eq(com.ironoath.web.store.mongo.TargetOccupationDocument.class),eq("world_target_occupation"));
        assertThatThrownBy(()->new MongoTargetOccupationStore(broken).compareAndSet(state("N1",1,"unknown"),0))
                .isSameAs(write).hasSuppressedException(read);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void concurrentFirstClaimsHaveOneWinner(Backend backend) throws Exception {
        var a=store(backend);
        TargetOccupationStore b=backend==Backend.MONGO?new MongoTargetOccupationStore(db.template()):a;
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->{start.await();return a.compareAndSet(state("N1",1,"b1"),0);});
            var two=pool.submit(()->{start.await();return b.compareAndSet(state("N2",1,"b2"),0);});
            start.countDown();
            assertThat(List.of(one.get(5,TimeUnit.SECONDS),two.get(5,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
            assertThat(a.findByTargetIds(List.of("gate_north"))).hasSize(1);
        } finally { pool.shutdownNow(); }
    }
}
