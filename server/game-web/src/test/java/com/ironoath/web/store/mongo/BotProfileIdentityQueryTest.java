package com.ironoath.web.store.mongo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class BotProfileIdentityQueryTest {
    @Test void enumerateProjectsOnlyIdsAndKeepsExistingSort() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        when(mongo.find(any(Query.class),eq(Document.class),eq(MongoBotProfileStore.COLLECTION)))
                .thenReturn(List.of(new Document("profile",new Document("botId","507F1F77BCF86CD799439012")),
                        new Document("profile",new Document("botId","b"))));
        assertThat(new MongoBotProfileStore(mongo).ids()).containsExactly("507F1F77BCF86CD799439012","b");
        ArgumentCaptor<Query> query=ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(query.capture(),eq(Document.class),eq(MongoBotProfileStore.COLLECTION));
        assertThat(query.getValue().getFieldsObject()).isEqualTo(new Document("profile.botId",1).append("_id",0));
        assertThat(query.getValue().getSortObject()).isEqualTo(new Document("_id",1));
        assertThat(query.getValue().getQueryObject()).isEmpty();
        verifyNoMoreInteractions(mongo);
    }
    @Test void countNeverRetrievesDocuments() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        when(mongo.count(any(Query.class),eq(MongoBotProfileStore.COLLECTION))).thenReturn(5000L);
        assertThat(new MongoBotProfileStore(mongo).size()).isEqualTo(5000);
        ArgumentCaptor<Query> query=ArgumentCaptor.forClass(Query.class);
        verify(mongo).count(query.capture(),eq(MongoBotProfileStore.COLLECTION));
        assertThat(query.getValue().getQueryObject()).isEmpty();
        verifyNoMoreInteractions(mongo);
    }
    @Test void identityQueryErrorsPropagateAndCountDoesNotOverflowSilently() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        when(mongo.find(any(Query.class),eq(Document.class),eq(MongoBotProfileStore.COLLECTION)))
                .thenThrow(new IllegalStateException("identity unavailable"));
        assertThatThrownBy(()->new MongoBotProfileStore(mongo).ids()).hasMessage("identity unavailable");
        when(mongo.count(any(Query.class),eq(MongoBotProfileStore.COLLECTION))).thenReturn(1L+Integer.MAX_VALUE);
        assertThatThrownBy(()->new MongoBotProfileStore(mongo).size()).isInstanceOf(ArithmeticException.class);
    }
}
