package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import com.ironoath.core.reward.*;
import com.ironoath.web.battlepass.*;
import com.ironoath.web.dto.generated.BattlePassTrack;
import com.ironoath.web.store.memory.InMemoryBattlePassStore;
import com.ironoath.web.store.mongo.*;

/** Same accepted outbox contract on memory and a uniquely owned synthetic Mongo database. */
class BattlePassDeliveryStoreTest {
    static TestMongo db;
    @BeforeAll static void open(){db=TestMongo.tryOpen();}
    @AfterAll static void close(){if(db!=null)db.close();}
    @BeforeEach void clear(){if(db!=null)new MongoBattlePassStore(db.template()).clear();}
    List<BattlePassStore> both(){
        var result=new ArrayList<BattlePassStore>();result.add(new InMemoryBattlePassStore());
        if(db!=null)result.add(new MongoBattlePassStore(db.template()));return result;
    }
    static BattlePassDeliveryPlan plan(String season,BattlePassTrack track,BattlePassDeliveryPlan.Kind kind,int tier){
        var reward=new RewardItem(RewardType.ITEM,"item_res_wood_10k",1);
        var line=new BattlePassDeliveryPlan.Tier(tier,10,reward,"木材箱",new FrozenMailReward(reward,false,999));
        var lines=List.of(line);
        return new BattlePassDeliveryPlan(BattlePassDeliveryPlan.keyOf(season,track,kind,lines),season,track,kind,lines,
                1700000000000L,1700000864000L,"战令奖励","原季恢复夹具");
    }
    @Test void qualificationAndPlanRoundTripTogetherAndSurviveEveryProgressMutation(){
        for(var store:both()){
            var accepted=plan("s-old",BattlePassTrack.FREE,BattlePassDeliveryPlan.Kind.DIRECT,1);
            store.update("s-old","P-one",p->p.withPoints(10).withDeliveryPlan(accepted));
            var initial=store.load("s-old","P-one");assertThat(initial.claimed(1,BattlePassTrack.FREE)).isTrue();
            assertThat(initial.pendingPlans()).containsExactlyEntriesOf(Map.of(accepted.key(),accepted));
            store.update("s-old","P-one",p->p.withPoints(1).withPaidUnlocked().withClaimed(2,BattlePassTrack.PAID)
                    .withPointsOnce("quest_claim:once",3).withTierMailWindow("other-window",1700000000000L,1700000001000L));
            var read=store.load("s-old","P-one");assertThat(read.pendingPlans()).containsEntry(accepted.key(),accepted);
            assertThat(read.points()).isEqualTo(14);assertThat(read.paidUnlocked()).isTrue();
            assertThat(read.pointClaims()).containsEntry("quest_claim:once",3L);
            assertThat(store.pendingSeasonsOf("P-one")).containsExactly("s-old");
            assertThat(store.pendingSeasonsOf("P-other")).isEmpty();
            assertThat(store.load("s-new","P-one").pendingPlans()).isEmpty();
            store.update("s-old","P-one",p->p.completeDelivery(accepted.key()));
            var finished=store.load("s-old","P-one");assertThat(finished.claimed(1,BattlePassTrack.FREE)).isTrue();
            assertThat(finished.pendingPlans()).isEmpty();assertThat(store.pendingSeasonsOf("P-one")).isEmpty();
        }
    }
    @Test void bothTracksAndOriginalSeasonsRemainIndependentAfterStoreRebuild(){
        for(var store:both()){
            var free=plan("s-old",BattlePassTrack.FREE,BattlePassDeliveryPlan.Kind.SEASON_MAIL,1);
            var paid=plan("s-old",BattlePassTrack.PAID,BattlePassDeliveryPlan.Kind.SEASON_MAIL,1);
            var current=plan("s-new",BattlePassTrack.FREE,BattlePassDeliveryPlan.Kind.DIRECT,1);
            store.update("s-old","P-one",p->p.withPoints(10).withPaidUnlocked().withDeliveryPlan(free).withDeliveryPlan(paid));
            store.update("s-new","P-one",p->p.withPoints(10).withDeliveryPlan(current));
            var reader=store instanceof MongoBattlePassStore?new MongoBattlePassStore(db.template()):store;
            assertThat(reader.pendingSeasonsOf("P-one")).containsExactly("s-new","s-old");
            assertThat(reader.load("s-old","P-one").pendingPlans()).hasSize(2);
            reader.update("s-old","P-one",p->p.completeMailDelivery(free.key(),1700000001000L,1700000865000L));
            assertThat(reader.load("s-old","P-one").pendingPlans()).containsExactlyEntriesOf(Map.of(paid.key(),paid));
            assertThat(reader.load("s-old","P-one").tierMailWindows()).containsKey(free.key());
            assertThatThrownBy(()->store.update("s-other","P-one",p->p.withDeliveryPlan(free))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void competingModesReserveOnlyOneQualificationInTheActualStoreCas() throws Exception {
        for(var store:both()){
            store.update("s-race","P-one",p->p.withPoints(10));
            var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(3);
            try{
                var futures=new ArrayList<Future<?>>();
                for(var kind:BattlePassDeliveryPlan.Kind.values())futures.add(pool.submit(()->{
                    var candidate=plan("s-race",BattlePassTrack.FREE,kind,1);
                    try{start.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
                    store.update("s-race","P-one",p->p.claimed(1,BattlePassTrack.FREE)?p:p.withDeliveryPlan(candidate));
                }));
                start.countDown();for(var future:futures)future.get(15,TimeUnit.SECONDS);
                var accepted=store.load("s-race","P-one");assertThat(accepted.claimed()).containsExactly("1:FREE");
                assertThat(accepted.pendingPlans()).hasSize(1);
            }finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
        }
    }
    @Test void reservationCannotPretendMailWasDeliveredAndConflictingProofKeepsPending(){
        for(var store:both()){
            var mail=plan("s-proof",BattlePassTrack.FREE,BattlePassDeliveryPlan.Kind.SEASON_MAIL,1);
            store.update("s-proof","P-one",p->p.withPoints(10).withDeliveryPlan(mail));
            assertThat(store.load("s-proof","P-one").tierMailWindows()).doesNotContainKey(mail.key());
            assertThatThrownBy(()->store.update("s-proof","P-one",p->p.completeMailDelivery(mail.key(),1700000001000L,1700000002000L))).isInstanceOf(IllegalStateException.class);
            assertThat(store.load("s-proof","P-one").pendingPlans()).containsKey(mail.key());
            store.update("s-proof","P-one",p->p.withTierMailWindow(mail.key(),mail.createdAt(),mail.expireAt()));
            assertThatThrownBy(()->store.update("s-proof","P-one",p->p.completeMailDelivery(mail.key(),1700000001000L,1700000865000L))).isInstanceOf(IllegalStateException.class);
            assertThat(store.load("s-proof","P-one").pendingPlans()).containsKey(mail.key());
        }
    }
    @Test void oldBsonWithoutOutboxRemainsClaimedAndNeverInventsAPlan(){
        Assumptions.assumeTrue(db!=null,"Mongo unavailable: compatibility was not verified");
        var document=new org.bson.Document("_id","s-legacy:P-old").append("seasonId","s-legacy").append("playerId","P-old")
                .append("points",10L).append("paidUnlocked",false).append("claimed",List.of("1:FREE")).append("version",0L);
        db.template().getCollection(BattlePassDocument.COLLECTION).insertOne(document);
        var store=new MongoBattlePassStore(db.template());var read=store.load("s-legacy","P-old");
        assertThat(read.claimed(1,BattlePassTrack.FREE)).isTrue();assertThat(read.pendingPlans()).isEmpty();
        assertThat(store.pendingSeasonsOf("P-old")).isEmpty();
        store.update("s-legacy","P-old",p->p.withPointsOnce("quest_claim:late",2));
        assertThat(store.load("s-legacy","P-old").claimed(1,BattlePassTrack.FREE)).isTrue();
        assertThat(store.load("s-legacy","P-old").pendingPlans()).isEmpty();
    }
}
