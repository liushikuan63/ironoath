package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import com.ironoath.core.activity.*;
import com.ironoath.core.reward.*;
import com.ironoath.web.activity.*;
import com.ironoath.web.battlepass.BattlePassStore;
import com.ironoath.web.dto.generated.BattlePassTrack;
import com.ironoath.web.store.memory.*;
import com.ironoath.web.store.mongo.*;

class ActivityDeliveryStoreTest {
    private ActivityInstance instance(){return new ActivityInstance(new ActivityProgress.Def("hunt",ActivityType.KILL_MONSTER,ActivityCondition.KILL_MONSTER_TOTAL,50,3),"旧讨伐",List.of(new RewardItem(RewardType.RESOURCE,"GOLD",500),new RewardItem(RewardType.ITEM,"iron",2)),50,1_800_000_000_000L,1_800_259_200_000L);}
    private ActivityProgressStore.State state(){var i=instance();return new ActivityProgressStore.State("owner",List.of(new ActivityProgress.Entry("hunt",i.startAt(),50,true,0)),i.startAt(),List.of(i),List.of(new ActivityClaimPlan(ActivityClaimPlan.keyOf(i),i,"old-season",i.startAt()+1)));}
    @Test void acceptedQualificationAndFrozenPlanRoundTripAndRemainIsolated(){
        try(var db=TestMongo.tryOpen()){
            assertThat(db).isNotNull();var snapshot=state();
            for(ActivityProgressStore store:List.of(new InMemoryActivityProgressStore(),new MongoActivityProgressStore(db.template()))){
                store.save("owner",snapshot);assertThat(store.load("owner")).contains(snapshot);assertThat(store.load("other")).isEmpty();
                assertThatThrownBy(()->store.save("other",snapshot)).isInstanceOf(IllegalArgumentException.class);
            }
            var rebuilt=new MongoActivityProgressStore(db.template());assertThat(rebuilt.load("owner")).contains(snapshot);
            var done=new ActivityProgressStore.State("owner",snapshot.entries(),snapshot.serverOpenMs(),snapshot.instances(),List.of());rebuilt.save("owner",done);
            assertThat(new MongoActivityProgressStore(db.template()).load("owner")).contains(done);
        }
    }
    @Test void pendingPlanCannotExistWithoutItsAcceptedOriginalWindow(){
        var s=state();assertThatThrownBy(()->new ActivityProgressStore.State("owner",List.of(),s.serverOpenMs(),s.instances(),s.pendingClaims())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ActivityProgressStore.State("owner",s.entries(),s.serverOpenMs(),s.instances(),List.of(s.pendingClaims().get(0),s.pendingClaims().get(0)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ActivityClaimPlan("other-key",instance(),"old-season",instance().startAt()+1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void legacyBattlePassMissingPointClaimsIsEmptyAndOtherUpdatesKeepNewReceipts(){
        try(var db=TestMongo.tryOpen()){
            assertThat(db).isNotNull();db.template().getCollection("battle_pass").insertOne(new Document("_id","old-season:owner").append("seasonId","old-season").append("playerId","owner").append("points",100L).append("claimed",List.of()).append("paidUnlocked",false).append("version",0L));
            var mongo=new MongoBattlePassStore(db.template());assertThat(mongo.load("old-season","owner").pointClaims()).isEmpty();
            for(BattlePassStore store:List.of(new InMemoryBattlePassStore(),mongo)){
                long before=store.load("old-season","owner").points();store.update("old-season","owner",p->p.withPointsOnce("activity_claim:hunt:1",50));
                store.update("old-season","owner",p->p.withTierMailWindow("mail-tier-1",1_800_000_000_000L,1_802_592_000_000L));
                store.update("old-season","owner",p->p.withPaidUnlocked().withClaimed(1,BattlePassTrack.FREE).withPoints(3));
                store.update("old-season","owner",p->p.withPointsOnce("activity_claim:hunt:1",50));
                assertThat(store.load("old-season","owner").points()).isEqualTo(before+53);
                assertThat(store.load("old-season","owner").pointClaims()).containsExactlyEntriesOf(Map.of("activity_claim:hunt:1",50L));
                store.update("old-season","owner",p->p.withTierMailWindow("mail-tier-1",1_900_000_000_000L,1_902_592_000_000L));
                assertThat(store.load("old-season","owner").tierMailWindows()).containsExactlyEntriesOf(Map.of("mail-tier-1",new BattlePassStore.TierMailWindow(1_800_000_000_000L,1_802_592_000_000L)));
                assertThat(store.load("new-season","owner").points()).isZero();assertThat(store.load("old-season","other").points()).isZero();
                assertThatThrownBy(()->store.update("old-season","owner",p->p.withPointsOnce("activity_claim:hunt:1",51))).isInstanceOf(IllegalStateException.class);
            }
            assertThat(new MongoBattlePassStore(db.template()).load("old-season","owner").pointClaims()).hasSize(1);
            assertThat(new MongoBattlePassStore(db.template()).load("old-season","owner").tierMailWindows()).hasSize(1);
        }
    }
    @Test void concurrentSameWindowAcceptsPointsExactlyOnceInMongo(){
        try(var db=TestMongo.tryOpen()){
            assertThat(db).isNotNull();var store=new MongoBattlePassStore(db.template());var pool=Executors.newFixedThreadPool(4);
            try{var calls=new ArrayList<Callable<BattlePassStore.Progress>>();for(int n=0;n<8;n++)calls.add(()->store.update("old-season","owner",p->p.withPointsOnce("activity_claim:hunt:1",50)));
                for(var result:pool.invokeAll(calls))assertThat(result.get().points()).isEqualTo(50);
                assertThat(store.load("old-season","owner").points()).isEqualTo(50);assertThat(store.load("old-season","owner").pointClaims()).hasSize(1);
            }catch(Exception e){throw new AssertionError(e);}finally{pool.shutdownNow();}
        }
    }
}
