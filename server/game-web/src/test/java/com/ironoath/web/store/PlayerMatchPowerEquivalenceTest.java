package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.ironoath.core.player.*;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.mongo.*;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;

class PlayerMatchPowerEquivalenceTest {
    static TestMongo db;
    @BeforeAll static void connect() {
        db=TestMongo.tryOpen();
        assertThat(db).as("真实Mongo等价用例不能跳过").isNotNull();
    }
    @AfterAll static void close() { if(db!=null)db.close(); }
    private PlayerSave save(String id,long matching) {
        return PlayerSave.createNew(id,"device-"+id,"投影测试",1,1_760_000_000_000L,1,
                Map.of("WOOD",new PlayerResourceState(5000,10000,0,100,1_760_000_000_000L)),
                new PlayerPower(10,matching,10000),null);
    }
    @Test void bothImplementationsReturnOnlyCurrentMatchingPowerAndIgnoreMissingIds() {
        for(PlayerRepository store:List.of(new InMemoryPlayerStore(),new MongoPlayerStore(db.template()))) {
            String id="P-match-"+UUID.randomUUID();
            assertThat(store.insertIfAbsent(save(id,321))).isTrue();
            assertThat(store.findMatchPower(id)).hasValue(321);
            PlayerSave changed=store.findByPlayerId(id).orElseThrow();
            changed.setPower(new PlayerPower(20,654,20000));store.save(changed);
            PlayerRepository peer=store instanceof MongoPlayerStore?new MongoPlayerStore(db.template()):store;
            assertThat(peer.findMatchPower(id)).hasValue(654);
            assertThat(peer.findByPlayerId(id).orElseThrow().power().matchPower()).isEqualTo(654);
            assertThat(peer.findMatchPower("missing-"+id)).isEmpty();
            assertThat(peer.findMatchPower(null)).isEmpty();
        }
    }
    @Test void legacyMongoWithoutPowerMatchesFullDomainZero() {
        String id="P-legacy-match-"+UUID.randomUUID();PlayerRepository store=new MongoPlayerStore(db.template());
        assertThat(store.insertIfAbsent(save(id,999))).isTrue();
        db.template().updateFirst(Query.query(Criteria.where("_id").is(id)),new Update().unset("power"),PlayerDocument.class,PlayerDocument.COLLECTION);
        assertThat(store.findByPlayerId(id).orElseThrow().power().matchPower()).isZero();
        assertThat(store.findMatchPower(id)).hasValue(0);
    }
    @Test void mongodbReadFailurePropagatesWithoutZeroOrFullDocumentFallback() {
        MongoTemplate unavailable=mock(MongoTemplate.class);IllegalStateException failure=new IllegalStateException("match projection unavailable");
        doThrow(failure).when(unavailable).findOne(any(Query.class),eq(PlayerMatchPowerDocument.class),eq(PlayerDocument.COLLECTION));
        assertThatThrownBy(()->new MongoPlayerStore(unavailable).findMatchPower("P-failed")).isSameAs(failure);
        verify(unavailable,never()).findById(any(),eq(PlayerDocument.class),eq(PlayerDocument.COLLECTION));
    }
    @Test void defaultPortAdapterKeepsExistingImplementationsCompatible() {
        PlayerRepository legacy=mock(PlayerRepository.class,CALLS_REAL_METHODS);
        when(legacy.findByPlayerId("present")).thenReturn(Optional.of(save("present",876)));
        when(legacy.findByPlayerId("missing")).thenReturn(Optional.empty());
        assertThat(legacy.findMatchPower("present")).hasValue(876);
        assertThat(legacy.findMatchPower("missing")).isEmpty();
    }
}
