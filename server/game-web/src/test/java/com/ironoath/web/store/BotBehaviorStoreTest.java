package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import com.ironoath.web.bot.BotBehaviorStore;
import com.ironoath.web.store.memory.InMemoryBotBehaviorStore;
import com.ironoath.web.store.mongo.MongoBotBehaviorStore;

class BotBehaviorStoreTest {
    private interface Check { void run(BotBehaviorStore store) throws Exception; }
    private void both(Check check) throws Exception {
        check.run(new InMemoryBotBehaviorStore());
        try(TestMongo db=TestMongo.tryOpen()) {
            assertThat(db).as("真实Mongo缺前提不得跳过").isNotNull();
            check.run(new MongoBotBehaviorStore(db.template()));
        }
    }
    @Test void monotonicWatermarkPreservesOtherBehaviorFields() throws Exception {
        both(store->{
            assertThat(store.find("owned-bot")).isEqualTo(BotBehaviorStore.State.EMPTY);
            assertThat(store.advanceReaction("owned-bot",100)).isTrue();
            store.recordGreeting("owned-bot","owned-alliance");
            assertThat(store.trySeekAlliance("owned-bot",5_000,10)).isTrue();
            assertThat(store.advanceReaction("owned-bot",99)).isFalse();
            assertThat(store.advanceReaction("owned-bot",100)).isFalse();
            assertThat(store.advanceReaction("owned-bot",200)).isTrue();
            assertThat(store.find("owned-bot")).isEqualTo(new BotBehaviorStore.State(200L,"owned-alliance",5_000L));
        });
    }
    @Test void seekCooldownUsesExactOriginalBoundaryAndDoesNotAdvanceOnRejectedAttempt() throws Exception {
        both(store->{
            assertThat(store.trySeekAlliance("owned-bot",5_000,10)).isTrue();
            assertThat(store.trySeekAlliance("owned-bot",5_009,10)).isFalse();
            assertThat(store.find("owned-bot").lastSeekAllianceAt()).isEqualTo(5_000);
            assertThat(store.trySeekAlliance("owned-bot",4_999,10)).isFalse();
            assertThat(store.trySeekAlliance("owned-bot",5_010,10)).isTrue();
            assertThat(store.find("owned-bot").lastSeekAllianceAt()).isEqualTo(5_010);
        });
    }
    @Test void concurrentSameHitHasExactlyOneResponseOwner() throws Exception {
        both(store->{
            var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(4);
            try {
                var jobs=new ArrayList<java.util.concurrent.Future<Boolean>>();
                for(int i=0;i<16;i++) jobs.add(pool.submit(()->{ start.await();return store.advanceReaction("owned-bot",500); }));
                start.countDown();int winners=0;
                for(var job:jobs) if(job.get(10,TimeUnit.SECONDS)) winners++;
                assertThat(winners).isEqualTo(1);assertThat(store.find("owned-bot").reactedUpTo()).isEqualTo(500);
            } finally { pool.shutdownNow(); }
        });
    }
    @Test void concurrentDifferentFieldsDoNotOverwriteEachOther() throws Exception {
        both(store->{
            var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(3);
            try {
                Callable<Void> react=()->{ start.await();store.advanceReaction("owned-bot",500);return null; };
                Callable<Void> greet=()->{ start.await();store.recordGreeting("owned-bot","owned-alliance");return null; };
                Callable<Void> seek=()->{ start.await();store.trySeekAlliance("owned-bot",5_000,10);return null; };
                var a=pool.submit(react);var b=pool.submit(greet);var c=pool.submit(seek);start.countDown();
                a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);c.get(10,TimeUnit.SECONDS);
                assertThat(store.find("owned-bot")).isEqualTo(new BotBehaviorStore.State(500L,"owned-alliance",5_000L));
            } finally { pool.shutdownNow(); }
        });
    }
    @Test void freshMongoReaderSeesAllFieldsWithoutReloadOrRegistration() {
        try(TestMongo db=TestMongo.tryOpen()) {
            assertThat(db).isNotNull();var first=new MongoBotBehaviorStore(db.template());var second=new MongoBotBehaviorStore(db.template());
            first.advanceReaction("owned-bot",500);first.recordGreeting("owned-bot","owned-alliance");first.trySeekAlliance("owned-bot",5_000,10);
            assertThat(second.find("owned-bot")).isEqualTo(new BotBehaviorStore.State(500L,"owned-alliance",5_000L));
            first.clear();assertThat(second.find("owned-bot")).isEqualTo(BotBehaviorStore.State.EMPTY);
        }
    }
}
