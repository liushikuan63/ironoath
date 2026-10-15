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
