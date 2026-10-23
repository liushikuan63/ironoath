package com.ironoath.web.store;

import com.ironoath.core.world.WorldGenerator;
import com.ironoath.web.world.NeutralTargetGarrisonStore;
import com.ironoath.web.store.memory.InMemoryNeutralTargetGarrisonStore;
import com.ironoath.web.store.mongo.MongoNeutralTargetGarrisonStore;
import com.ironoath.web.store.mongo.NeutralTargetGarrisonDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 自有Mongo临时库；持久余兵不代表真实攻占已接线。 */
class NeutralTargetGarrisonStoreTest {
    enum Backend { MEMORY, MONGO }
    static TestMongo db;
    static final String UNIT="unit_infantry_t4";
    @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();}
    @AfterAll static void close(){if(db!=null)db.close();}
    NeutralTargetGarrisonStore store(Backend b){
        if(b==Backend.MEMORY)return new InMemoryNeutralTargetGarrisonStore();
        db.template().remove(new org.springframework.data.mongodb.core.query.Query(),NeutralTargetGarrisonDocument.COLLECTION);
        return new MongoNeutralTargetGarrisonStore(db.template());
    }
    NeutralTargetGarrisonStore.Snapshot state(long troops,long version,String op){
        return new NeutralTargetGarrisonStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,256,216,
                Map.of(UNIT,100L),troops==0?Map.of():Map.of(UNIT,troops),NeutralTargetGarrisonStore.Phase.NEUTRAL,100+version,op,version);
    }
    @ParameterizedTest @EnumSource(Backend.class)
    void survivorsEmptyAndRetirementPersistWithoutRefill(Backend b){
        var repo=store(b);assertThat(repo.findByTargetIds(List.of("gate_north"))).isEmpty();
        var first=state(3,1,"loss-one");assertThat(repo.compareAndSet(first,0)).isTrue();
        assertThat(repo.compareAndSet(first,0)).isTrue();
        assertThat(repo.compareAndSet(state(2,1,"loss-one"),0)).isFalse();
        assertThat(repo.compareAndSet(state(2,2,"loss-one"),1)).isFalse();
        assertThat(repo.compareAndSet(state(2,1,"other"),0)).isFalse();
        var empty=state(0,2,"loss-two");assertThat(repo.compareAndSet(empty,1)).isTrue();
        if(b==Backend.MONGO)repo=new MongoNeutralTargetGarrisonStore(db.template());
        assertThat(repo.findByTargetIds(List.of("gate_north")).get("gate_north")).isEqualTo(empty);
        var retired=new NeutralTargetGarrisonStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,256,216,
                empty.initialTroops(),Map.of(),NeutralTargetGarrisonStore.Phase.RETIRED,103,"capture",3);
        assertThat(repo.compareAndSet(retired,2)).isTrue();assertThat(repo.compareAndSet(retired,2)).isTrue();
        assertThat(repo.compareAndSet(state(0,4,"revive"),3)).isFalse();
        assertThat(repo.compareAndSet(first,0)).isFalse();
    }
    @ParameterizedTest @EnumSource(Backend.class)
    void capsAnchorsAndSurvivorsCannotBeChangedByLaterOperations(Backend b){
        var repo=store(b);repo.compareAndSet(state(3,1,"first"),0);
        assertThat(repo.compareAndSet(state(4,2,"healed"),1)).isFalse();
        var cap=new NeutralTargetGarrisonStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,256,216,
                Map.of(UNIT,101L),Map.of(UNIT,2L),NeutralTargetGarrisonStore.Phase.NEUTRAL,102,"cap",2);
        assertThat(repo.compareAndSet(cap,1)).isFalse();
        var move=new NeutralTargetGarrisonStore.Snapshot("gate_north",WorldGenerator.EntityType.FORTRESS,250,216,
                Map.of(UNIT,100L),Map.of(UNIT,2L),NeutralTargetGarrisonStore.Phase.NEUTRAL,102,"move",2);
        assertThat(repo.compareAndSet(move,1)).isFalse();
        var before=new NeutralTargetGarrisonStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,256,216,
                Map.of(UNIT,100L),Map.of(UNIT,2L),NeutralTargetGarrisonStore.Phase.NEUTRAL,100,"old-time",2);
        assertThat(repo.compareAndSet(before,1)).isFalse();
        assertThat(repo.findAll(1).get("gate_north")).isEqualTo(state(3,1,"first"));
        var same=repo;assertThatThrownBy(()->same.compareAndSet(state(2,3,"bad-version"),1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->same.findAll(0)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @EnumSource(Backend.class)
    void concurrentSameVersionHasExactlyOneWinner(Backend b)throws Exception{
        var repo=store(b);repo.compareAndSet(state(3,1,"first"),0);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{
            var a=pool.submit(()->{start.await();return repo.compareAndSet(state(2,2,"second-a"),1);});
            var z=pool.submit(()->{start.await();return repo.compareAndSet(state(1,2,"second-b"),1);});
            start.countDown();assertThat(List.of(a.get(10,TimeUnit.SECONDS),z.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(repo.findAll(1).get("gate_north").version()).isEqualTo(2);
        }finally{pool.shutdownNow();}
    }
    @Test void copiesAndValidationRejectCorruption(){
        var initial=new LinkedHashMap<>(Map.of(UNIT,100L));var remaining=new LinkedHashMap<>(Map.of(UNIT,2L));
        var s=new NeutralTargetGarrisonStore.Snapshot("gate_north",WorldGenerator.EntityType.GATE,256,216,initial,remaining,NeutralTargetGarrisonStore.Phase.NEUTRAL,1,"one",1);
        initial.clear();remaining.clear();assertThat(s.troops()).containsEntry(UNIT,2L);
        assertThatThrownBy(()->s.troops().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->new NeutralTargetGarrisonStore.Snapshot("x",WorldGenerator.EntityType.GATE,1,1,Map.of(UNIT,2L),Map.of(UNIT,3L),NeutralTargetGarrisonStore.Phase.NEUTRAL,1,"x",1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new NeutralTargetGarrisonStore.Snapshot("x",WorldGenerator.EntityType.GATE,1,1,Map.of(UNIT,2L),Map.of(UNIT,1L),NeutralTargetGarrisonStore.Phase.RETIRED,1,"x",1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new NeutralTargetGarrisonStore.Snapshot("x",WorldGenerator.EntityType.GATE,1,1,Map.of(UNIT,Long.MAX_VALUE,"other",1L),Map.of(),NeutralTargetGarrisonStore.Phase.NEUTRAL,1,"x",1)).isInstanceOf(ArithmeticException.class);
    }
    @Test void uncertainWritesRequireExactIndependentReadback(){
        store(Backend.MONGO);var template=spy(db.template());
        doAnswer(inv->{inv.callRealMethod();throw new IllegalStateException("owned lost insert");}).when(template).insert(any(NeutralTargetGarrisonDocument.class),eq(NeutralTargetGarrisonDocument.COLLECTION));
        var repo=new MongoNeutralTargetGarrisonStore(template);assertThat(repo.compareAndSet(state(3,1,"first"),0)).isTrue();
        doAnswer(inv->{inv.callRealMethod();throw new IllegalStateException("owned lost update");}).when(template).updateFirst(any(org.springframework.data.mongodb.core.query.Query.class),any(org.springframework.data.mongodb.core.query.UpdateDefinition.class),eq(NeutralTargetGarrisonDocument.class),eq(NeutralTargetGarrisonDocument.COLLECTION));
        assertThat(repo.compareAndSet(state(2,2,"second"),1)).isTrue();
        assertThat(new MongoNeutralTargetGarrisonStore(db.template()).findAll(1).get("gate_north")).isEqualTo(state(2,2,"second"));
    }
    @Test void failedWriteAndReadbackKeepBothErrors(){
        var broken=mock(org.springframework.data.mongodb.core.MongoTemplate.class);var write=new IllegalStateException("owned write failure");var read=new IllegalStateException("owned read failure");
        doThrow(write).when(broken).insert(any(NeutralTargetGarrisonDocument.class),eq(NeutralTargetGarrisonDocument.COLLECTION));
        doThrow(read).when(broken).find(any(org.springframework.data.mongodb.core.query.Query.class),eq(NeutralTargetGarrisonDocument.class),eq(NeutralTargetGarrisonDocument.COLLECTION));
        assertThatThrownBy(()->new MongoNeutralTargetGarrisonStore(broken).compareAndSet(state(2,1,"first"),0)).isSameAs(write).hasSuppressedException(read);
    }
    @Test void corruptStoredStateIsNeverPristine(){
        var repo=store(Backend.MONGO);repo.compareAndSet(state(3,1,"first"),0);
        var collection=db.template().getCollection(NeutralTargetGarrisonDocument.COLLECTION);
        collection.updateOne(new org.bson.Document("_id","gate_north"),new org.bson.Document("$set",new org.bson.Document("state.phase","UNKNOWN_OWNED_FIXTURE")));
        var raw=collection.find().first();assertThatThrownBy(()->repo.findAll(2)).isInstanceOf(RuntimeException.class);assertThat(collection.find().first()).isEqualTo(raw);
    }
    @ParameterizedTest @ValueSource(strings={"missing-state","mismatched-id","missing-troops"})
    void brokenStoredShapeCannotDisappearAsAnUnfoughtTarget(String damage){
        var repo=store(Backend.MONGO);repo.compareAndSet(state(3,1,"first"),0);var collection=db.template().getCollection(NeutralTargetGarrisonDocument.COLLECTION);
        var update=switch(damage){
            case "missing-state"->new org.bson.Document("$unset",new org.bson.Document("state",""));
            case "mismatched-id"->new org.bson.Document("$set",new org.bson.Document("state.targetId","other"));
            default->new org.bson.Document("$unset",new org.bson.Document("state.troops",""));
        };
        collection.updateOne(new org.bson.Document("_id","gate_north"),update);var raw=collection.find().first();
        assertThatThrownBy(()->repo.findByTargetIds(List.of("gate_north"))).isInstanceOf(RuntimeException.class);
        assertThat(collection.find().first()).isEqualTo(raw);
    }
    @Test void differentReadbackCannotConfirmFailedWrite(){
        var template=mock(org.springframework.data.mongodb.core.MongoTemplate.class);var failure=new IllegalStateException("owned uncommitted insert");
        doThrow(failure).when(template).insert(any(NeutralTargetGarrisonDocument.class),eq(NeutralTargetGarrisonDocument.COLLECTION));
        when(template.find(any(org.springframework.data.mongodb.core.query.Query.class),eq(NeutralTargetGarrisonDocument.class),eq(NeutralTargetGarrisonDocument.COLLECTION)))
                .thenReturn(List.of(new NeutralTargetGarrisonDocument("gate_north",state(2,1,"other-op"))));
        assertThatThrownBy(()->new MongoNeutralTargetGarrisonStore(template).compareAndSet(state(3,1,"first"),0)).isSameAs(failure);
    }
}
