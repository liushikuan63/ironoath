package com.ironoath.web.store;

import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.web.battle.StrategicWoundSettlement;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.mongo.MongoArmyStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实存储异常反证；自有TestMongo临时库，不改玩家库、不模拟真实争夺成功。 */
class StrategicWoundSettlementTest {
    enum Backend { MEMORY, MONGO }
    private static TestMongo db;
    @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();}
    @AfterAll static void close(){if(db!=null)db.close();}
    private ArmyRepository store(Backend b){return b==Backend.MEMORY?new InMemoryArmyStore():new MongoArmyStore(db.template());}
    private String insert(ArmyRepository store){String p="owned-wounds-"+UUID.randomUUID();var a=new ArmyState();a.add("unit_infantry_t4",40);assertThat(store.insertIfAbsent(p,a)).isTrue();return p;}
    private ArmyState.StrategicWoundReceipt apply(ArmyRepository store,String player){return StrategicWoundSettlement.admit(store,player,"gate_north",1,"owned-battle-one",Map.of("unit_infantry_t4",5L),3);}

    @ParameterizedTest @EnumSource(Backend.class)
    void rebuildAndTreatmentNeverReplayWounds(Backend b){
        var repo=store(b);String player=insert(repo);var first=apply(repo,player);
        assertThat(first.overflowDead()).isEqualTo(2);
        if(b==Backend.MONGO)repo=new MongoArmyStore(db.template());
        assertThat(apply(repo,player)).isEqualTo(first);
        var army=repo.findByPlayerId(player).orElseThrow();army.startTreatment(1,Map.of(),100);army.collectTreated(1100);
        repo.save(player,army,repo.versionOf(player));
        assertThat(apply(repo,player)).isEqualTo(first);
        var finalState=repo.findByPlayerId(player).orElseThrow();assertThat(finalState.totalWounded()).isZero();assertThat(finalState.totalTroops()).isEqualTo(43);
        assertThat(finalState.snapshot().strategicWounds()).hasSize(1);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void lostSaveReceiptIsConfirmedWithoutSecondAdmission(Backend b){
        var real=store(b);String player=insert(real);var uncertain=spy(real);
        doAnswer(inv->{inv.callRealMethod();throw new IllegalStateException("owned lost wound write receipt");})
                .when(uncertain).save(eq(player),any(ArmyState.class),anyLong());
        var receipt=apply(uncertain,player);
        assertThat(receipt.admitted()).containsEntry("unit_infantry_t4",3L);
        assertThat(real.findByPlayerId(player).orElseThrow().totalWounded()).isEqualTo(3);
        verify(uncertain,times(1)).save(eq(player),any(ArmyState.class),anyLong());
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void unconfirmedWriteKeepsOriginalAndReadFailure(Backend b){
        var real=store(b);String player=insert(real);var broken=spy(real);var write=new IllegalStateException("owned write before commit");var read=new IllegalStateException("owned readback unavailable");
        doThrow(write).when(broken).save(eq(player),any(ArmyState.class),anyLong());
        var reads=new AtomicInteger();doAnswer(inv->{if(reads.incrementAndGet()>1)throw read;return inv.callRealMethod();}).when(broken).findByPlayerId(player);
        assertThatThrownBy(()->apply(broken,player)).isSameAs(write).hasSuppressedException(read);
        assertThat(real.findByPlayerId(player).orElseThrow().totalWounded()).isZero();
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void staleSaveRetriesFromFreshArmyWithoutOverwritingPeer(Backend b){
        var real=store(b);String player=insert(real);var contender=spy(real);var calls=new AtomicInteger();
        doAnswer(inv->{if(calls.incrementAndGet()==1){var peer=real.findByPlayerId(player).orElseThrow();long v=real.versionOf(player);peer.add("unit_infantry_t4",1);real.save(player,peer,v);throw new com.ironoath.core.army.ArmyVersionConflictException("owned peer wins first CAS");}return inv.callRealMethod();})
                .when(contender).save(eq(player),any(ArmyState.class),anyLong());
        apply(contender,player);var state=real.findByPlayerId(player).orElseThrow();
        assertThat(state.totalTroops()).isEqualTo(41);assertThat(state.totalWounded()).isEqualTo(3);assertThat(calls).hasValue(2);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void missingArmyIsNotInventedAndOldMongoColumnIsCompatible(Backend b){
        var repo=store(b);String missing="missing-owned-"+UUID.randomUUID();
        assertThatThrownBy(()->apply(repo,missing)).isInstanceOf(IllegalStateException.class);assertThat(repo.findByPlayerId(missing)).isEmpty();
        String player=insert(repo);
        if(b==Backend.MONGO)db.template().updateFirst(org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(player)),new org.springframework.data.mongodb.core.query.Update().unset("strategicWounds"),"army");
        assertThat(repo.findByPlayerId(player).orElseThrow().snapshot().strategicWounds()).isEmpty();
        assertThat(apply(repo,player).admitted()).containsEntry("unit_infantry_t4",3L);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void persistedAdmissionOrderAndNewWatermarkCannotBeReplacedByOldPlan(Backend b){
        var repo=store(b);String player=insert(repo);Map<String,Long> first=new java.util.LinkedHashMap<>();
        first.put("unit_infantry_t4",6L);first.put("unit_cavalry_t4",4L);
        var original=StrategicWoundSettlement.admit(repo,player,"gate_north",1,"ordered-first",first,7);
        assertThat(original.admitted()).containsEntry("unit_infantry_t4",6L).containsEntry("unit_cavalry_t4",1L);
        if(b==Backend.MONGO)repo=new MongoArmyStore(db.template());
        assertThat(StrategicWoundSettlement.admit(repo,player,"gate_north",1,"ordered-first",first,7)).isEqualTo(original);
        var restarted=repo;Map<String,Long> reversed=new java.util.LinkedHashMap<>();reversed.put("unit_cavalry_t4",4L);reversed.put("unit_infantry_t4",6L);
        assertThatThrownBy(()->StrategicWoundSettlement.admit(restarted,player,"gate_north",1,"ordered-first",reversed,7)).isInstanceOf(IllegalStateException.class);
        StrategicWoundSettlement.admit(repo,player,"gate_north",3,"next-battle",Map.of(),7);
        var before=repo.findByPlayerId(player).orElseThrow().snapshot();
        assertThatThrownBy(()->StrategicWoundSettlement.admit(restarted,player,"gate_north",1,"ordered-first",first,7)).isInstanceOf(IllegalStateException.class);
        assertThat(repo.findByPlayerId(player).orElseThrow().snapshot()).isEqualTo(before);
    }
}
