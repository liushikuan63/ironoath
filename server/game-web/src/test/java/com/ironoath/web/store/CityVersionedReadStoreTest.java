package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import com.ironoath.core.city.*;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.mongo.*;
import org.springframework.data.mongodb.core.MongoTemplate;

class CityVersionedReadStoreTest {
    static TestMongo db;
    @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).as("隔离真实Mongo必须可用").isNotNull();}
    @AfterAll static void close(){if(db!=null)db.close();}
    @Test void missingAndOwnedCopyPairsHaveEquivalentMemoryAndMongoSemantics() {
        for(CityRepository store:List.of(new InMemoryCityStore(),new MongoCityStore(db.template()))) {
            String id="owned-pair-"+UUID.randomUUID();
            assertThat(store.findVersionedByPlayerId(null)).isEmpty();assertThat(store.findVersionedByPlayerId("")).isEmpty();
            assertThat(store.findVersionedByPlayerId(id)).isEmpty();assertThat(store.insertIfAbsent(id,new CityState())).isTrue();
            var loaded=store.findVersionedByPlayerId(id).orElseThrow();assertThat(loaded.version()).isZero();
            loaded.state().restoreBuilding(new BuildingInstance("owned-main","main_city",1,3,3));
            assertThat(store.findVersionedByPlayerId(id).orElseThrow().state().buildings()).isEmpty();
            assertThat(store.save(id,loaded.state(),loaded.version())).isEqualTo(1);
            var next=store.findVersionedByPlayerId(id).orElseThrow();assertThat(next.version()).isEqualTo(1);
            assertThat(next.state().buildings()).hasSize(1);
            assertThatThrownBy(()->store.save(id,new CityState(),loaded.version())).isInstanceOf(IllegalStateException.class);
            assertThat(store.findVersionedByPlayerId(id).orElseThrow().state().buildings()).hasSize(1);
        }
    }
    @Test void realMongoPointReadKeepsOldStateAndOldVersionWhenPeerWritesBeforeReturn() {
        String id="owned-peer-"+UUID.randomUUID();MongoTemplate template=spy(db.template());
        var store=new MongoCityStore(template);var peer=new MongoCityStore(db.template());assertThat(store.insertIfAbsent(id,new CityState())).isTrue();
        AtomicBoolean armed=new AtomicBoolean(true);
        doAnswer(call->{
            CityDocument old=(CityDocument)call.callRealMethod();
            if(old!=null && armed.compareAndSet(true,false)) {
                var changed=CityState.fromSnapshot(new CityState.Snapshot(old.buildings(),1));
                assertThat(peer.save(id,changed,old.version())).isEqualTo(1);
            }
            return old;
        }).when(template).findById(id,CityDocument.class,CityDocument.COLLECTION);
        var loaded=store.findVersionedByPlayerId(id).orElseThrow();assertThat(armed).isFalse();
        assertThat(loaded.version()).isZero();assertThat(loaded.state().extraQueues()).isZero();
        verify(template).findById(id,CityDocument.class,CityDocument.COLLECTION);
        assertThatThrownBy(()->store.save(id,loaded.state(),loaded.version())).isInstanceOf(IllegalStateException.class);
        var actual=peer.findVersionedByPlayerId(id).orElseThrow();assertThat(actual.version()).isEqualTo(1);assertThat(actual.state().extraQueues()).isEqualTo(1);
    }
}
