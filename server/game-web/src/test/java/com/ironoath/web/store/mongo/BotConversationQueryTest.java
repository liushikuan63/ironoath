package com.ironoath.web.store.mongo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.stream.IntStream;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class BotConversationQueryTest {
    @Test void lazyIdentitySupplierIsNotEvaluatedBeforeAnEmptyProbe() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        assertThat(new MongoBotConversationStore(mongo).dueForKnownBots(123_000,
                ()->{throw new AssertionError("No work must not read all identities");},50)).isEmpty();
        verify(mongo).exists(any(Query.class),eq(MongoBotConversationStore.Slot.class),eq(MongoBotConversationStore.COLLECTION));
        verifyNoMoreInteractions(mongo);
    }

    @Test void lazyIdentityReadHappensOnceAfterTheProbeAndBeforeFilteredSelection() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        when(mongo.exists(any(Query.class),eq(MongoBotConversationStore.Slot.class),eq(MongoBotConversationStore.COLLECTION))).thenReturn(true);
        when(mongo.find(any(Query.class),eq(MongoBotConversationStore.Slot.class),eq(MongoBotConversationStore.COLLECTION))).thenReturn(List.of());
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        new MongoBotConversationStore(mongo).dueForKnownBots(123_000,()->{
            verify(mongo).exists(any(Query.class),eq(MongoBotConversationStore.Slot.class),eq(MongoBotConversationStore.COLLECTION));
            assertThat(reads.incrementAndGet()).isEqualTo(1);return List.of("known");
        },50);
        ArgumentCaptor<Query> query=ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(query.capture(),eq(MongoBotConversationStore.Slot.class),eq(MongoBotConversationStore.COLLECTION));
        assertThat(reads.get()).isEqualTo(1);
        assertThat(query.getValue().getQueryObject()).isEqualTo(new Document("_id",new Document("$in",List.of("known"))).append("pending.dueAt",new Document("$lte",123_000L)));
        assertThat(query.getValue().getSortObject()).isEqualTo(new Document("pending.dueAt",1).append("_id",1));
        assertThat(query.getValue().getLimit()).isEqualTo(50);
    }

    @Test void lazyProbeFailureDoesNotInvokeIdentitySupplier() {
        MongoTemplate mongo=mock(MongoTemplate.class);var failure=new IllegalStateException("probe unavailable");
        when(mongo.exists(any(Query.class),eq(MongoBotConversationStore.Slot.class),eq(MongoBotConversationStore.COLLECTION))).thenThrow(failure);
        assertThatThrownBy(()->new MongoBotConversationStore(mongo).dueForKnownBots(123_000,
                ()->{throw new AssertionError("Probe must run first");},50)).isSameAs(failure);
        verify(mongo,never()).find(any(Query.class),eq(MongoBotConversationStore.Slot.class),anyString());
    }

    @Test void lazyIdentityFailurePropagatesWithoutSelection() {
        MongoTemplate mongo=mock(MongoTemplate.class);var failure=new IllegalStateException("identity unavailable");
        when(mongo.exists(any(Query.class),eq(MongoBotConversationStore.Slot.class),eq(MongoBotConversationStore.COLLECTION))).thenReturn(true);
        assertThatThrownBy(()->new MongoBotConversationStore(mongo).dueForKnownBots(123_000,()->{throw failure;},50)).isSameAs(failure);
        verify(mongo,never()).find(any(Query.class),eq(MongoBotConversationStore.Slot.class),anyString());
    }

    @Test void lazyEmptyIdentityResultNeedsNoSelectionButNullIsRejected() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        when(mongo.exists(any(Query.class),eq(MongoBotConversationStore.Slot.class),eq(MongoBotConversationStore.COLLECTION))).thenReturn(true);
        var store=new MongoBotConversationStore(mongo);
        assertThat(store.dueForKnownBots(123_000,List::of,50)).isEmpty();
        assertThatNullPointerException().isThrownBy(()->store.dueForKnownBots(123_000,()->null,50));
        verify(mongo,never()).find(any(Query.class),eq(MongoBotConversationStore.Slot.class),anyString());
        assertThatNullPointerException().isThrownBy(()->store.dueForKnownBots(123_000,null,50));
    }

    @Test void lazySelectionFailurePropagatesRatherThanDroppingWork() {
        MongoTemplate mongo=mock(MongoTemplate.class);var failure=new IllegalStateException("selection unavailable");
        when(mongo.exists(any(Query.class),eq(MongoBotConversationStore.Slot.class),eq(MongoBotConversationStore.COLLECTION))).thenReturn(true);
        when(mongo.find(any(Query.class),eq(MongoBotConversationStore.Slot.class),eq(MongoBotConversationStore.COLLECTION))).thenThrow(failure);
        assertThatThrownBy(()->new MongoBotConversationStore(mongo).dueForKnownBots(123_000,()->List.of("known"),50)).isSameAs(failure);
    }

    @Test void defaultLazyEntryKeepsLegacyImplementationsCompatible() {
        var legacy=mock(com.ironoath.web.bot.BotConversationStore.class,CALLS_REAL_METHODS);
        var task=new com.ironoath.web.bot.BotConversationStore.Pending("known","peer","WORLD","WORLD","source","内容",1,2,null);
        when(legacy.dueFor(10,List.of("known"),50)).thenReturn(List.of(task));
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        assertThat(legacy.dueForKnownBots(10,()->{reads.incrementAndGet();return List.of("known");},50)).containsExactly(task);
        assertThat(reads.get()).isEqualTo(1);verify(legacy).dueFor(10,List.of("known"),50);
        var failure=new IllegalStateException("legacy identity failure");
        assertThatThrownBy(()->legacy.dueForKnownBots(10,()->{throw failure;},50)).isSameAs(failure);
        assertThatNullPointerException().isThrownBy(()->legacy.dueForKnownBots(10,null,50));
        assertThatNullPointerException().isThrownBy(()->legacy.dueForKnownBots(10,()->null,50));
    }
    @Test void emptyDueQueueNeverSubmitsTheLargeIdentitySet() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        var ids=IntStream.range(0,5000).mapToObj(i->"bot"+i).toList();
        assertThat(new MongoBotConversationStore(mongo).dueFor(123_000,ids,50)).isEmpty();
        ArgumentCaptor<Query> probe=ArgumentCaptor.forClass(Query.class);
        verify(mongo).exists(probe.capture(),eq(MongoBotConversationStore.Slot.class),eq(MongoBotConversationStore.COLLECTION));
        assertThat(probe.getValue().getQueryObject()).isEqualTo(new Document("pending.dueAt",new Document("$lte",123_000L)));
        verifyNoMoreInteractions(mongo);
    }

    @Test void dueWorkStillFiltersKnownIdsBeforeSortingAndLimiting() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        when(mongo.exists(any(Query.class),eq(MongoBotConversationStore.Slot.class),eq(MongoBotConversationStore.COLLECTION))).thenReturn(true);
        when(mongo.find(any(Query.class),eq(MongoBotConversationStore.Slot.class),eq(MongoBotConversationStore.COLLECTION))).thenReturn(List.of());
        new MongoBotConversationStore(mongo).dueFor(123_000,List.of("known"),50);
        ArgumentCaptor<Query> selection=ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(selection.capture(),eq(MongoBotConversationStore.Slot.class),eq(MongoBotConversationStore.COLLECTION));
        assertThat(selection.getValue().getQueryObject()).isEqualTo(new Document("_id",new Document("$in",List.of("known")))
                .append("pending.dueAt",new Document("$lte",123_000L)));
        assertThat(selection.getValue().getSortObject()).isEqualTo(new Document("pending.dueAt",1).append("_id",1));
        assertThat(selection.getValue().getLimit()).isEqualTo(50);
    }

    @Test void probeFailuresPropagateInsteadOfPretendingQueueIsEmpty() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        when(mongo.exists(any(Query.class),eq(MongoBotConversationStore.Slot.class),eq(MongoBotConversationStore.COLLECTION)))
                .thenThrow(new IllegalStateException("pending query unavailable"));
        assertThatThrownBy(()->new MongoBotConversationStore(mongo).dueFor(123_000,List.of("known"),50))
                .hasMessage("pending query unavailable");
        verify(mongo,never()).find(any(Query.class),eq(MongoBotConversationStore.Slot.class),anyString());
    }

    @Test void noKnownBotsNeedsNoDatabaseQuery() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        assertThat(new MongoBotConversationStore(mongo).dueFor(123_000,List.of(),50)).isEmpty();
        verifyNoInteractions(mongo);
    }
}
