package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.ironoath.config.cfg.ActivityCfg;
import com.ironoath.core.activity.ActivityCondition;
import com.ironoath.core.activity.ActivityProgress;
import com.ironoath.core.activity.ActivityType;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardType;
import com.ironoath.web.activity.ActivityInstance;
import com.ironoath.web.activity.ActivityProgressStore;
import com.ironoath.web.activity.ActivityRuleHistoryStore;
import com.ironoath.web.store.memory.InMemoryActivityProgressStore;
import com.ironoath.web.store.memory.InMemoryActivityRuleHistoryStore;
import com.ironoath.web.store.mongo.MongoActivityProgressStore;
import com.ironoath.web.store.mongo.MongoActivityRuleHistoryStore;

/** 真实随机Mongo库与内存对照；不使用原验收数据库。缺Mongo直接失败，不算通过。 */
class ActivityInstanceStoreTest {
    private static TestMongo db;
    @BeforeAll static void open() { db=TestMongo.tryOpen(); assertThat(db).as("本机Mongo必须可用").isNotNull(); }
    @AfterAll static void close() { if(db!=null) db.close(); }
    private ActivityInstance instance() {
        return new ActivityInstance(new ActivityProgress.Def("hunt",ActivityType.KILL_MONSTER,
                ActivityCondition.KILL_MONSTER_TOTAL,50,3),"旧剿匪令",
                List.of(new RewardItem(RewardType.RESOURCE,"GOLD",500),new RewardItem(RewardType.ITEM,"iron",2)),
                50,1_800_000_000_000L,1_800_259_200_000L);
    }
    @Test void rulesRewardsAndWindowRoundTripIncludingZeroProgress() {
        var snapshot=instance();
        var state=new ActivityProgressStore.State("roundtrip",List.of(new ActivityProgress.Entry("hunt",snapshot.startAt(),0,false,0)),
                snapshot.startAt(),List.of(snapshot));
        for (ActivityProgressStore store:List.of(new InMemoryActivityProgressStore(),new MongoActivityProgressStore(db.template()))) {
            store.save("roundtrip",state);
            assertThat(store.load("roundtrip")).contains(state);
        }
        assertThat(new MongoActivityProgressStore(db.template()).load("roundtrip")).contains(state);
    }
    @Test void realLegacyDocumentWithoutNewFieldsPreservesProgress() {
        db.template().getCollection("activity_progress").insertOne(new Document("_id","legacy")
                .append("serverOpenMs",1_800_000_000_000L).append("entries",List.of(new Document("activityId","hunt")
                .append("windowStart",1_800_000_000_000L).append("value",7L).append("claimed",false).append("lastLoginAt",0L))));
        var store=new MongoActivityProgressStore(db.template()); var old=store.load("legacy").orElseThrow();
        assertThat(old.instances()).isEmpty(); assertThat(old.entries().get(0).value()).isEqualTo(7);
        store.save("legacy",new ActivityProgressStore.State("legacy",old.entries(),old.serverOpenMs(),List.of(instance())));
        assertThat(store.load("legacy").orElseThrow().instances()).containsExactly(instance());
    }
    @Test void ruleHistorySurvivesNewStoreAndRejectsStaleVersion() {
        ActivityCfg row=new ActivityCfg("hunt","旧剿匪令",ActivityCfg.ActivityType.KILL_MONSTER,
                ActivityCfg.ConditionType.KILL_MONSTER_TOTAL,50,3,500,"iron",2L,50,false,null,null,null,null);
        var first=new ActivityRuleHistoryStore.Revision(1,List.of(row));
        var second=new ActivityRuleHistoryStore.Revision(1_800_000_000_000L,List.of(row));
        for (ActivityRuleHistoryStore store:List.of(new InMemoryActivityRuleHistoryStore(),new MongoActivityRuleHistoryStore(db.template()))) {
            assertThat(store.saveIfVersion(0,List.of(first))).isTrue();
            assertThat(store.saveIfVersion(0,List.of(second))).isFalse();
            assertThat(store.saveIfVersion(1,List.of(first,second))).isTrue();
            assertThat(store.saveIfVersion(1,List.of(first,second))).isFalse();
            assertThat(store.load()).containsExactly(first,second);
        }
        assertThat(new MongoActivityRuleHistoryStore(db.template()).load()).containsExactly(first,second);
    }

    @Test void existingSnapshotMissingNewFactFlagAndFactSetStillRestoresOldRules() {
        var definition=new Document("activityId","hunt").append("type","KILL_MONSTER").append("condition","KILL_MONSTER_TOTAL")
                .append("goalValue",50L).append("durationDays",3L).append("retired",false);
        var snapshot=new Document("def",definition).append("name","旧剿匪令").append("battlePassPoints",50L)
                .append("startAt",1_800_000_000_000L).append("endAt",1_800_259_200_000L)
                .append("rewards",List.of(new Document("type","RESOURCE").append("_id","GOLD").append("count",500L)));
        db.template().getCollection("activity_progress").insertOne(new Document("_id","old-snapshot")
                .append("serverOpenMs",1_800_000_000_000L).append("instances",List.of(snapshot))
                .append("entries",List.of(new Document("activityId","hunt").append("windowStart",1_800_000_000_000L)
                        .append("value",7L).append("claimed",false).append("lastLoginAt",0L))));
        var old=new MongoActivityProgressStore(db.template()).load("old-snapshot").orElseThrow();
        assertThat(old.instances().get(0).def().requiresFact()).isFalse();
        assertThat(old.entries().get(0).facts()).isEmpty();
        assertThat(old.entries().get(0).value()).isEqualTo(7);
        assertThat(old.instances().get(0).rewards().get(0).count()).isEqualTo(500);
    }

    @Test void acceptedCampaignFactsSurviveMongoAndMemoryRoundTrip() {
        var snapshot=new ActivityInstance(new ActivityProgress.Def("theme",ActivityType.KILL_MONSTER,
                ActivityCondition.KILL_MONSTER_TOTAL,40,14,false,true),"铁誓远征",
                List.of(new RewardItem(RewardType.RESOURCE,"GOLD",960)),0,1_800_000_000_000L,1_801_209_600_000L);
        var facts=java.util.Set.of("solo-monster:battle-one","solo-monster:battle-two");
        var state=new ActivityProgressStore.State("fact-roundtrip",List.of(new ActivityProgress.Entry("theme",snapshot.startAt(),2,false,0,facts)),
                snapshot.startAt(),List.of(snapshot));
        for (ActivityProgressStore store:List.of(new InMemoryActivityProgressStore(),new MongoActivityProgressStore(db.template()))) {
            store.save("fact-roundtrip",state);
            assertThat(store.load("fact-roundtrip")).contains(state);
        }
    }
}
