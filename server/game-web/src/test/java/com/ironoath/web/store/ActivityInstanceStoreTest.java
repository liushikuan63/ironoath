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
                ActivityCfg.ConditionType.KILL_MONSTER_TOTAL,50,3,500,"iron",2,50,false);
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
}
