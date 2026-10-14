package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import com.ironoath.web.bot.BotAttackLedgerStore;
import com.ironoath.web.bot.BotAttackLedgerStore.Attack;
import com.ironoath.web.store.memory.InMemoryBotAttackLedgerStore;
import com.ironoath.web.store.mongo.MongoBotAttackLedgerStore;

class BotAttackLedgerStoreTest {
    private interface Check { void run(BotAttackLedgerStore store) throws Exception; }
    private void both(Check check) throws Exception {
        check.run(new InMemoryBotAttackLedgerStore());
        try(TestMongo db=TestMongo.tryOpen()) {
            assertThat(db).as("真实Mongo缺前提不得跳过").isNotNull();
            check.run(new MongoBotAttackLedgerStore(db.template()));
        }
    }
    @Test void expiredSourceMetadataIsClearedUsingTheSameServerTimeAsFacts() throws Exception {
        both(store->{
            store.record(new Attack("source-a","victim-a",1_000));
            store.evict(1_000+BotAttackLedgerStore.WINDOW_MILLIS);
            org.assertj.core.api.Assertions.assertThatCode(()->
                    assertThat(store.record(new Attack("source-a","victim-b",1_000+BotAttackLedgerStore.WINDOW_MILLIS))).isTrue())
                    .as("旧事实过期并清理后，两实现都释放历史来源归属；不使用真实墙钟干扰服务时间夹具").doesNotThrowAnyException();
        });
    }
    @Test void distinctSourcesCompeteAtomicallyForOneRemainingSlot() throws Exception {
        both(store->{
            store.record(new Attack("prior-1","victim-a",1_000));store.record(new Attack("prior-2","victim-a",1_000));
            var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(8);
            try {
                var jobs=new ArrayList<java.util.concurrent.Future<BotAttackLedgerStore.Admission>>();
                for(int i=0;i<16;i++) {String source="different-source-"+i;jobs.add(pool.submit(()->{start.await();return store.reserve(new Attack(source,"victim-a",1_010),3);}));}
                start.countDown();int accepted=0;for(var job:jobs) if(job.get(15,TimeUnit.SECONDS)==BotAttackLedgerStore.Admission.ACQUIRED) accepted++;
                assertThat(accepted).isEqualTo(1);assertThat(store.count("victim-a",1_020)).isEqualTo(2);
                assertThat(store.occupied("victim-a",1_020)).isEqualTo(3);
            } finally {pool.shutdownNow();}
        });
    }
    @Test void knownNotStartedReleaseAndConfirmedFactHaveDifferentLifetimes() throws Exception {
        both(store->{
            var a=new Attack("source-a","victim-a",1_000);
            assertThat(store.reserve(a,1)).isEqualTo(BotAttackLedgerStore.Admission.ACQUIRED);
            assertThat(store.reserve(a,1)).isEqualTo(BotAttackLedgerStore.Admission.ALREADY_PRESENT);
            assertThat(store.count("victim-a",1_010)).isZero();assertThat(store.occupied("victim-a",1_010)).isEqualTo(1);
            store.release(a);assertThat(store.occupied("victim-a",1_010)).isZero();
            assertThat(store.reserve(a,1)).isEqualTo(BotAttackLedgerStore.Admission.ACQUIRED);
            assertThat(store.record(new Attack("source-a","victim-a",9_000))).isTrue();
            store.release(a);assertThat(store.count("victim-a",1_010)).isEqualTo(1);
            assertThat(store.count("victim-a",1_000+BotAttackLedgerStore.WINDOW_MILLIS)).isZero();
        });
    }
    @Test void uncertainPendingAdmissionDoesNotExpireAndFreshMongoReaderSeesIt() throws Exception {
        both(store->{
            var a=new Attack("pending-source","victim-a",1_000);store.reserve(a,1);
            store.evict(1_000+2*BotAttackLedgerStore.WINDOW_MILLIS);
            assertThat(store.occupied("victim-a",1_000+2*BotAttackLedgerStore.WINDOW_MILLIS)).isEqualTo(1);
            assertThat(store.reserve(new Attack("other","victim-a",1_000+2*BotAttackLedgerStore.WINDOW_MILLIS),1)).isEqualTo(BotAttackLedgerStore.Admission.QUOTA_FULL);
            store.record(a);assertThat(store.count("victim-a",1_010)).isEqualTo(1);
        });
        try(TestMongo db=TestMongo.tryOpen()) {
            assertThat(db).isNotNull();var first=new MongoBotAttackLedgerStore(db.template());var second=new MongoBotAttackLedgerStore(db.template());
            var a=new Attack("source-a","victim-a",1_000);first.reserve(a,1);
            assertThat(second.occupied("victim-a",1_010)).isEqualTo(1);
            assertThat(second.reserve(new Attack("source-b","victim-a",1_010),1)).isEqualTo(BotAttackLedgerStore.Admission.QUOTA_FULL);
            assertThat(second.record(a)).isTrue();assertThat(first.count("victim-a",1_010)).isEqualTo(1);
        }
    }
    @Test void rollingWindowExpiresExactlyAt24HoursAndKeepsOtherVictimSeparate() throws Exception {
        both(store->{
            store.record(new Attack("source-a","victim-a",1_000));store.record(new Attack("source-b","victim-a",1_001));
            store.record(new Attack("source-c","victim-b",1_000));
            assertThat(store.count("victim-a",1_010)).isEqualTo(2);
            assertThat(store.count("victim-b",1_010)).isEqualTo(1);
            assertThat(store.count("victim-a",1_000+BotAttackLedgerStore.WINDOW_MILLIS)).isEqualTo(1);
            store.evict(1_000+BotAttackLedgerStore.WINDOW_MILLIS);
            assertThat(store.count("victim-a",1_000+BotAttackLedgerStore.WINDOW_MILLIS)).isEqualTo(1);
            assertThat(store.count("victim-b",1_000+BotAttackLedgerStore.WINDOW_MILLIS)).isZero();
            assertThat(store.count("victim-a",1_001+BotAttackLedgerStore.WINDOW_MILLIS)).isZero();
        });
    }
    @Test void repeatedStableSourceKeepsOriginalTimestampAndRejectsVictimMutation() throws Exception {
        both(store->{
            assertThat(store.record(new Attack("same-source","victim-a",1_000))).isTrue();
            assertThat(store.record(new Attack("same-source","victim-a",9_000))).isFalse();
            assertThat(store.count("victim-a",1_000+BotAttackLedgerStore.WINDOW_MILLIS)).as("重试不延长原窗口").isZero();
            assertThatThrownBy(()->store.record(new Attack("same-source","victim-b",1_000))).isInstanceOf(IllegalStateException.class);
            assertThat(store.count("victim-b",1_010)).isZero();
        });
    }
    @Test void concurrentRepeatedSourceAddsExactlyOneFact() throws Exception {
        both(store->{
            var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(4);
            try {
                var jobs=new ArrayList<java.util.concurrent.Future<Boolean>>();
                for(int i=0;i<16;i++) jobs.add(pool.submit(()->{ start.await();return store.record(new Attack("same-source","victim-a",1_000)); }));
                start.countDown();int accepted=0;for(var job:jobs) if(job.get(10,TimeUnit.SECONDS)) accepted++;
                assertThat(accepted).isEqualTo(1);assertThat(store.count("victim-a",1_010)).isEqualTo(1);
            } finally { pool.shutdownNow(); }
        });
    }
    @Test void freshMongoReaderAndEvictionUseTheSameLedger() {
        try(TestMongo db=TestMongo.tryOpen()) {
            assertThat(db).isNotNull();var first=new MongoBotAttackLedgerStore(db.template());var second=new MongoBotAttackLedgerStore(db.template());
            first.record(new Attack("source-a","victim-a",1_000));assertThat(second.count("victim-a",1_010)).isEqualTo(1);
            first.evict(1_000+BotAttackLedgerStore.WINDOW_MILLIS);assertThat(second.count("victim-a",1_000+BotAttackLedgerStore.WINDOW_MILLIS)).isZero();
            first.record(new Attack("source-b","victim-a",99_000_000));first.clear();assertThat(second.count("victim-a",99_000_010)).isZero();
        }
    }
}
