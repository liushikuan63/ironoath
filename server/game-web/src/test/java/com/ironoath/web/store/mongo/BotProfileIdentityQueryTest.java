package com.ironoath.web.store.mongo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.ExecutableFindOperation.*;
import org.springframework.data.mongodb.core.query.Query;

class BotProfileIdentityQueryTest {
    private record IdentityQueryMongo(MongoTemplate mongo, FindWithQuery<Document> query, TerminatingFind<Document> result) { }
    private IdentityQueryMongo identityMongo() {
        var context=new org.springframework.data.mongodb.core.mapping.MongoMappingContext();
        var converter=new org.springframework.data.mongodb.core.convert.MappingMongoConverter(
                org.springframework.data.mongodb.core.convert.NoOpDbRefResolver.INSTANCE,context);
        converter.afterPropertiesSet();
        var mongo=mock(MongoTemplate.class);when(mongo.getConverter()).thenReturn(converter);
        ExecutableFind<MongoBotProfileStore.ProfileDocument> domain=mock(ExecutableFind.class);
        FindWithProjection<MongoBotProfileStore.ProfileDocument> collection=mock(FindWithProjection.class);
        FindWithQuery<Document> projection=mock(FindWithQuery.class);
        TerminatingFind<Document> result=mock(TerminatingFind.class);
        when(mongo.query(MongoBotProfileStore.ProfileDocument.class)).thenReturn(domain);
        when(domain.inCollection(MongoBotProfileStore.COLLECTION)).thenReturn(collection);
        when(collection.as(Document.class)).thenReturn(projection);
        when(projection.matching(any(Query.class))).thenReturn(result);
        return new IdentityQueryMongo(mongo,projection,result);
    }
    @Test void batchUsesOneIdProjectionAndReturnsEveryRequestedAliasWithoutPointReads() {
        var fixture=identityMongo();var mongo=fixture.mongo();
        String lower="507f1f77bcf86cd799439011",upper=lower.toUpperCase(java.util.Locale.ROOT);
        when(fixture.result().all())
                .thenReturn(List.of(new Document("_id",new org.bson.types.ObjectId(lower)),new Document("_id","owned-a")));
        assertThat(new MongoBotProfileStore(mongo).existingIds(java.util.Arrays.asList(lower,upper,null,"owned-a","human",upper)))
                .containsExactlyInAnyOrder(lower,upper,"owned-a");
        ArgumentCaptor<Query> query=ArgumentCaptor.forClass(Query.class);
        verify(fixture.query()).matching(query.capture());verify(fixture.result()).all();
        assertThat(query.getValue().getFieldsObject()).isEqualTo(new Document("_id",1));
        assertThat(query.getValue().getSortObject()).isEmpty();
        assertThat(query.getValue().getQueryObject()).containsOnlyKeys("_id");
        assertThat((java.util.Collection<?>)query.getValue().getQueryObject().get("_id",Document.class).get("$in"))
                .extracting(Object::toString).containsExactly(lower,upper,"owned-a","human");
        verify(mongo).getConverter();verify(mongo).query(MongoBotProfileStore.ProfileDocument.class);verifyNoMoreInteractions(mongo);
    }
    @Test void batchEmptyInputsNeverQueryAndStorageFailuresPropagate() {
        MongoTemplate empty=mock(MongoTemplate.class);var store=new MongoBotProfileStore(empty);
        assertThat(store.existingIds(null)).isEmpty();assertThat(store.existingIds(List.of())).isEmpty();
        assertThat(store.existingIds(java.util.Arrays.asList(null,null))).isEmpty();verifyNoInteractions(empty);
        var broken=identityMongo();
        when(broken.result().all())
                .thenThrow(new IllegalStateException("batch unavailable"));
        assertThatThrownBy(()->new MongoBotProfileStore(broken.mongo()).existingIds(List.of("owned-a")))
                .isInstanceOf(IllegalStateException.class).hasMessage("batch unavailable");
    }
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
