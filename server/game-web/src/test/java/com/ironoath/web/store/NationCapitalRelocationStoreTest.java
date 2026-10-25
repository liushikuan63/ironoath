package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.ironoath.web.nation.NationCapitalRelocationStore;
import com.ironoath.web.store.memory.InMemoryNationCapitalRelocationStore;
import com.ironoath.web.store.mongo.MongoNationCapitalRelocationStore;
import org.springframework.data.mongodb.core.query.Query;

class NationCapitalRelocationStoreTest {
    enum Backend { MEMORY, MONGO }
    private static TestMongo db;

    @BeforeAll static void open(){db=TestMongo.tryOpen();}
    @AfterAll static void close(){if(db!=null)db.close();}
    @BeforeEach void clear(){if(db!=null)db.template().remove(new Query(),"nation_capital_relocation");}

    private NationCapitalRelocationStore store(Backend backend) {
        return backend==Backend.MEMORY?new InMemoryNationCapitalRelocationStore()
                :new MongoNationCapitalRelocationStore(db.template());
    }
    private static NationCapitalRelocationStore.Snapshot plan(String requestId) {
        return new NationCapitalRelocationStore.Snapshot("N-1","capital-move:N-1:"+requestId,requestId,
                1L,NationCapitalRelocationStore.Stage.PREPARED,7L,256,216,300,280,
                null,-1L,null,null,null,null,null,-1L);
    }
    private static NationCapitalRelocationStore.Snapshot plan(String requestId,List<NationCapitalRelocationStore.RequestReceipt> history) {
        return new NationCapitalRelocationStore.Snapshot("N-1","capital-move:N-1:"+requestId,requestId,
                1L,NationCapitalRelocationStore.Stage.PREPARED,7L,256,216,300,280,
                null,-1L,null,null,null,null,null,null,-1L,history);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void progressIsVersionedReplayableAndOnlyOneMoveCanBePending(Backend backend) {
        if(backend==Backend.MONGO)Assumptions.assumeTrue(db!=null,"local MongoDB is required for Mongo backend");
        var store=store(backend);var prepared=plan("req-1");
        assertThat(store.compareAndSet(prepared,0L)).isTrue();
        assertThat(store.compareAndSet(prepared,0L)).isTrue();
        assertThat(store.findByNationId("N-1")).contains(prepared);
        var next=prepared.advance(NationCapitalRelocationStore.Stage.GARRISON_MOVED);
        assertThat(store.compareAndSet(next,0L)).isFalse();
        assertThat(store.compareAndSet(next,1L)).isTrue();
        if(backend==Backend.MONGO)store=new MongoNationCapitalRelocationStore(db.template());
        assertThat(store.findByNationId("N-1")).contains(next);
        assertThat(store.findPending(10)).containsExactly(next);
        var skipped=next.advance(NationCapitalRelocationStore.Stage.NATION_MOVED);
        assertThat(store.compareAndSet(skipped,next.version())).isFalse();
        assertThat(store.compareAndSet(next,1L)).isTrue();
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void completedIntentCanBeReplacedButPendingIntentCannot(Backend backend) {
        if(backend==Backend.MONGO)Assumptions.assumeTrue(db!=null,"local MongoDB is required for Mongo backend");
        var store=store(backend);var current=plan("req-1");assertThat(store.compareAndSet(current,0L)).isTrue();
        var other=plan("req-2").withVersion(current.version()+1);
        assertThat(store.compareAndSet(other,current.version())).isFalse();
        for(var stage:NationCapitalRelocationStore.Stage.values()) {
            if(stage==NationCapitalRelocationStore.Stage.PREPARED)continue;
            var next=current.advance(stage);
            assertThat(store.compareAndSet(next,current.version())).isTrue();current=next;
        }
        var replacement=plan("req-2",List.of(new NationCapitalRelocationStore.RequestReceipt("req-1",300,280),
                new NationCapitalRelocationStore.RequestReceipt("req-2",300,280))).withVersion(current.version()+1);
        assertThat(store.compareAndSet(replacement,current.version())).isTrue();
        assertThat(store.findByNationId("N-1")).contains(replacement);
        assertThat(store.findPending(10)).containsExactly(replacement);
    }
}
