package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ironoath.web.bot.BotConversationStore;
import com.ironoath.web.bot.BotConversationStore.Pending;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryBotConversationStore;
import com.ironoath.web.store.memory.InMemorySocialStore;
import com.ironoath.web.store.mongo.MongoBotConversationStore;
import com.ironoath.web.store.mongo.MongoSocialStore;
import com.ironoath.web.social.SocialRulesAssembler;
import com.ironoath.config.ConfigRegistry;
import java.nio.file.Path;

/** 真Mongo必须可用；水位/原子追加/历史裁剪与重建都可观察，不以零执行或跳过验收。 */
class BotConversationStoreTest {
    static TestMongo db;
    static SocialRulesAssembler rules;
    @BeforeAll static void open() {
        db=TestMongo.tryOpen();
        assertThat(db).as("真实Mongo必须可用").isNotNull();
        Path cfg=Path.of("../../contract/config");
        if (!cfg.toFile().isDirectory()) cfg=Path.of("contract/config");
        rules=new SocialRulesAssembler(ConfigRegistry.loadFromDirectory(cfg));
    }
    @AfterAll static void close() { if(db!=null) db.close(); }
    private List<BotConversationStore> stores() {
        db.template().dropCollection(MongoBotConversationStore.COLLECTION);
        return List.of(new InMemoryBotConversationStore(),new MongoBotConversationStore(db.template()));
    }
    private List<SocialStore> chats() {
        var mongo=new MongoSocialStore(db.template(),rules);
        mongo.clear();
        return List.of(new InMemorySocialStore(),mongo);
    }
    private Pending task(String bot,long sequence) {
        return new Pending(bot,"human","WORLD","WORLD","m"+sequence,"@真人 应战",sequence,sequence+3_000,null);
    }
    private SocialStore.ChatMessage message(String id,long now) {
        return new SocialStore.ChatMessage(id,"WORLD","bot","守卫","内容"+id,now);
    }

    @Test void pendingBusyCooldownAndOldSourceReplaysAreCoalesced() {
        for(var store:stores()) {
            assertThat(store.offer(task("bot",100_000),10_000)).isTrue();
            assertThat(store.offer(task("bot",100_001),10_000)).isFalse();
            assertThat(store.due(102_999,10)).isEmpty();
            assertThat(store.due(103_000,10)).singleElement().extracting(Pending::sequence).isEqualTo(100_000L);
            assertThat(store.complete("bot",100_001,103_000,10_000)).isFalse();
            assertThat(store.complete("bot",100_000,103_000,10_000)).isTrue();
            assertThat(store.offer(task("bot",100_001),10_000)).isFalse();
            assertThat(store.offer(task("bot",110_000),10_000)).isFalse();
            assertThat(store.offer(task("bot",113_000),10_000)).isTrue();
        }
    }
    @Test void pendingRecoversAcrossMongoStoreReconstruction() {
        var before=new MongoBotConversationStore(db.template()); before.clear();
        assertThat(before.offer(task("recover",200_000),10_000)).isTrue();
        var after=new MongoBotConversationStore(db.template());
        assertThat(after.due(203_000,10)).containsExactly(task("recover",200_000));
        assertThat(after.complete("recover",200_000,203_000,10_000)).isTrue();
        assertThat(before.due(999_000,10)).isEmpty();
    }
    @Test void deliveredReceiptSurvivesHistoryClippingAndCompletionFailure() {
        for(var store:chats()) {
            assertThat(store.appendChatOrdered("WORLD",message("first",100_000),3,100_000)).isTrue();
            for(int i=0;i<210;i++) store.appendChat("WORLD",message("clip"+i,100_001+i),3);
            assertThat(store.chat("WORLD",null,10)).hasSize(3).noneMatch(m -> m.messageId().equals("first"));
            assertThat(store.appendChatOrdered("WORLD",message("retry",300_000),3,100_000)).isFalse();
            assertThat(store.chatDeliverySequence("WORLD","bot")).isEqualTo(100_000);
            assertThat(store.appendChatOrdered("WORLD",message("new",300_001),3,300_001)).isTrue();
            assertThat(store.appendChatOrdered("WORLD",message("lateold",300_002),3,100_000)).isFalse();
        }
    }
    @Test void simultaneousSameSequenceCanAppendExactlyOnce() throws Exception {
        for(var store:chats()) {
            var executor=Executors.newFixedThreadPool(8);
            try {
                List<Future<Boolean>> results=new ArrayList<>();
                for(int i=0;i<16;i++) {
                    int index=i;
                    results.add(executor.submit(() -> store.appendChatOrdered("WORLD",message("c"+index,100_000),3,100_000)));
                }
                int accepted=0;
                for(var result:results) if(result.get()) accepted++;
                assertThat(accepted).isEqualTo(1);
                assertThat(store.chat("WORLD",null,10)).hasSize(1);
            } finally { executor.shutdownNow(); }
        }
    }
    @Test void simultaneousOfferCanOccupyOnlyOneBotSlot() throws Exception {
        for(var store:stores()) {
            var executor=Executors.newFixedThreadPool(8);
            try {
                List<Future<Boolean>> results=new ArrayList<>();
                for(int i=0;i<16;i++) {
                    long sequence=100_000+i;
                    results.add(executor.submit(() -> store.offer(task("bot",sequence),10_000)));
                }
                int accepted=0;
                for(var result:results) if(result.get()) accepted++;
                assertThat(accepted).isEqualTo(1);
                assertThat(store.due(999_000,10)).hasSize(1);
            } finally { executor.shutdownNow(); }
        }
    }

    @Test void knownBotFilteringHappensBeforeTheBatchLimitOnBothStores() {
        for(var store:stores()) {
            for(int i=0;i<60;i++) assertThat(store.offer(task("unknown"+i,100_000+i),10_000)).isTrue();
            assertThat(store.offer(task("known",200_000),10_000)).isTrue();
            assertThat(store.due(999_000,50)).noneMatch(p -> p.botId().equals("known"));
            assertThat(store.dueFor(999_000,List.of("known"),50)).containsExactly(task("known",200_000));
            assertThat(store.dueFor(999_000,List.of(),50)).isEmpty();
        }
    }

    @Test void emptyDueQueueDoesNotHideLaterOffersOrFutureWorkOnEitherStore() {
        for(var store:stores()) {
            assertThat(store.dueFor(103_000,List.of("known"),50)).isEmpty();
            assertThat(store.offer(task("known",100_001),10_000)).isTrue();
            assertThat(store.dueFor(103_000,List.of("known"),50)).isEmpty();
            assertThat(store.dueFor(103_001,List.of("known"),50)).containsExactly(task("known",100_001));
            assertThat(store.complete("known",100_001,103_001,10_000)).isTrue();
            assertThat(store.dueFor(999_000,List.of("known"),50)).isEmpty();
        }
    }

    @Test void dueProbeUsesProductionIndexForManyFutureSlotsAndIndexCreationIsIdempotent() {
        var mongo=db.template();
        mongo.dropCollection(MongoBotConversationStore.COLLECTION);
        com.ironoath.web.store.mongo.MongoIndexes.ensure(mongo);
        var collection=mongo.getCollection(MongoBotConversationStore.COLLECTION);
        var documents=java.util.stream.IntStream.range(0,5000)
                .mapToObj(i->new org.bson.Document("_id","future"+i)
                        .append("pending",new org.bson.Document("dueAt",999_000L)))
                .toList();
        collection.insertMany(documents);
        var indexes=collection.listIndexes().into(new ArrayList<org.bson.Document>());
        assertThat(indexes).anySatisfy(index->{
            assertThat(index.getString("name")).isEqualTo("idx_bot_conversation_due");
            assertThat(index.get("key")).isEqualTo(new org.bson.Document("pending.dueAt",1).append("_id",1));
        });
        com.ironoath.web.store.mongo.MongoIndexes.ensure(mongo);
        assertThat(collection.listIndexes().into(new ArrayList<org.bson.Document>())).hasSize(indexes.size());
        var explain=mongo.getDb().runCommand(new org.bson.Document("explain",
                new org.bson.Document("find",MongoBotConversationStore.COLLECTION)
                        .append("filter",new org.bson.Document("pending.dueAt",new org.bson.Document("$lte",123_000L)))
                        .append("limit",1)).append("verbosity","executionStats"));
        var stats=explain.get("executionStats",org.bson.Document.class);
        assertThat(((Number)stats.get("nReturned")).longValue()).isZero();
        assertThat(((Number)stats.get("totalDocsExamined")).longValue()).isZero();
        assertThat(new MongoBotConversationStore(mongo).dueFor(123_000,List.of("future0"),50)).isEmpty();
    }
}
