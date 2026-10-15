package com.ironoath.web.store.mongo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.DbCallback;
import org.springframework.data.mongodb.core.CollectionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.data.mongodb.core.ExecutableFindOperation.*;
import org.springframework.data.mongodb.core.query.Query;

class BotProfileIdentityQueryTest {
    private record IdentityQueryMongo(MongoTemplate mongo, FindWithQuery<Document> query, TerminatingFind<Document> result, MongoDatabaseFactory factory) { }
    private Document metadata(String locale) {
        var options=new Document();if(locale!=null)options.put("collation",new Document("locale",locale));
        return new Document("name",MongoBotProfileStore.COLLECTION).append("type","collection").append("options",options);
    }
    private IdentityQueryMongo identityMongo() {
        var context=new org.springframework.data.mongodb.core.mapping.MongoMappingContext();
        var converter=new org.springframework.data.mongodb.core.convert.MappingMongoConverter(
                org.springframework.data.mongodb.core.convert.NoOpDbRefResolver.INSTANCE,context);
        converter.afterPropertiesSet();
        var mongo=mock(MongoTemplate.class);when(mongo.getConverter()).thenReturn(converter);
        var factory=mock(MongoDatabaseFactory.class);when(mongo.getMongoDatabaseFactory()).thenReturn(factory);
        when(mongo.execute(org.mockito.ArgumentMatchers.<DbCallback<Document>>any())).thenReturn(metadata(null));
        ExecutableFind<MongoBotProfileStore.ProfileDocument> domain=mock(ExecutableFind.class);
        FindWithProjection<MongoBotProfileStore.ProfileDocument> collection=mock(FindWithProjection.class);
        FindWithQuery<Document> projection=mock(FindWithQuery.class);
        TerminatingFind<Document> result=mock(TerminatingFind.class);
        when(mongo.query(MongoBotProfileStore.ProfileDocument.class)).thenReturn(domain);
        when(domain.inCollection(MongoBotProfileStore.COLLECTION)).thenReturn(collection);
        when(collection.as(Document.class)).thenReturn(projection);
        when(projection.matching(any(Query.class))).thenReturn(result);
        return new IdentityQueryMongo(mongo,projection,result,factory);
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
        verify(mongo).getConverter();verify(mongo).getMongoDatabaseFactory();
        verify(mongo).execute(org.mockito.ArgumentMatchers.<DbCallback<Document>>any());
        verify(mongo).query(MongoBotProfileStore.ProfileDocument.class);verifyNoMoreInteractions(mongo);
    }
    private RuntimeException translated(int code) {
        var command=new com.mongodb.MongoCommandException(new org.bson.BsonDocument("ok",new org.bson.BsonDouble(0))
                .append("code",new org.bson.BsonInt32(code)).append("errmsg",new org.bson.BsonString("owned metadata error")),new com.mongodb.ServerAddress());
        return new org.springframework.data.mongodb.core.MongoExceptionTranslator().translateExceptionIfPossible(command);
    }
    private void aggregateResult(IdentityQueryMongo fixture,Document row) {
        when(fixture.mongo().execute(eq(MongoBotProfileStore.COLLECTION),org.mockito.ArgumentMatchers.<CollectionCallback<Document>>any())).thenReturn(row);
    }
    @Test void nonSimpleCollationUsesOneProjectedBatchAndServerMatchedRequestIndices() throws Exception {
        var f=identityMongo();when(f.mongo().execute(org.mockito.ArgumentMatchers.<DbCallback<Document>>any())).thenReturn(metadata("en"));
        com.mongodb.client.MongoCollection<Document> collection=mock(com.mongodb.client.MongoCollection.class);
        com.mongodb.client.AggregateIterable<Document> rows=mock(com.mongodb.client.AggregateIterable.class);
        when(collection.aggregate(anyList())).thenReturn(rows);when(rows.first()).thenReturn(new Document("indices",List.of(0,1)));
        when(f.mongo().execute(eq(MongoBotProfileStore.COLLECTION),org.mockito.ArgumentMatchers.<CollectionCallback<Document>>any()))
                .thenAnswer(call->call.<CollectionCallback<Document>>getArgument(1).doInCollection(collection));
        assertThat(new MongoBotProfileStore(f.mongo()).existingIds(List.of("owned-User","owned-user","human","owned-user")))
                .containsExactlyInAnyOrder("owned-User","owned-user");
        ArgumentCaptor<List> pipeline=ArgumentCaptor.forClass(List.class);verify(collection).aggregate(pipeline.capture());verify(rows).first();
        var stages=(List<Document>)pipeline.getValue();
        assertThat(stages.get(0)).isEqualTo(new Document("$match",new Document("_id",new Document("$in",List.of("owned-User","owned-user","human")))));
        assertThat(stages.get(1)).isEqualTo(new Document("$project",new Document("_id",1)));
        assertThat(stages.get(stages.size()-1).get("$project",Document.class)).containsOnlyKeys("_id","indices");
        verify(f.mongo(),never()).query(any());
    }
    @Test void metadataIsRefreshedAndUnknownMetadataNeverAssumesSimple() {
        var f=identityMongo();when(f.mongo().execute(org.mockito.ArgumentMatchers.<DbCallback<Document>>any()))
                .thenReturn(metadata(null),metadata("en"),metadata("simple"));
        when(f.result().all()).thenReturn(List.of(new Document("_id","owned-User")));aggregateResult(f,new Document("indices",List.of(0)));
        var store=new MongoBotProfileStore(f.mongo());
        assertThat(store.existingIds(List.of("owned-user"))).isEmpty();
        assertThat(store.existingIds(List.of("owned-user"))).containsExactly("owned-user");
        assertThat(store.existingIds(List.of("owned-user"))).isEmpty();
        verify(f.mongo(),times(3)).execute(org.mockito.ArgumentMatchers.<DbCallback<Document>>any());verify(f.result(),times(2)).all();
        for(Document unknown:java.util.Arrays.asList(null,new Document(),metadata(null).append("type","view"),
                metadata(null).append("options",null),metadata(null).append("name","other"))) {
            var fixture=identityMongo();when(fixture.mongo().execute(org.mockito.ArgumentMatchers.<DbCallback<Document>>any())).thenReturn(unknown);
            aggregateResult(fixture,new Document("indices",List.of(0)));
            assertThat(new MongoBotProfileStore(fixture.mongo()).existingIds(List.of("owned-user"))).containsExactly("owned-user");
            verify(fixture.mongo(),never()).query(any());
        }
    }
    @Test void onlyMetadataUnauthorized13FallsBackAndOtherFailuresPropagate() {
        var unauthorized=translated(13);assertThat(unauthorized).isInstanceOf(org.springframework.data.mongodb.UncategorizedMongoDbException.class);
        var f=identityMongo();when(f.mongo().execute(org.mockito.ArgumentMatchers.<DbCallback<Document>>any())).thenThrow(unauthorized);
        aggregateResult(f,new Document("indices",List.of(0)));
        assertThat(new MongoBotProfileStore(f.mongo()).existingIds(List.of("owned-user"))).containsExactly("owned-user");
        for(RuntimeException failure:List.of(translated(18),new org.springframework.dao.DataAccessResourceFailureException("socket unavailable"),
                new org.springframework.dao.QueryTimeoutException("metadata timed out"),new IllegalStateException("unknown metadata failure"))) {
            var broken=identityMongo();when(broken.mongo().execute(org.mockito.ArgumentMatchers.<DbCallback<Document>>any())).thenThrow(failure);
            assertThatThrownBy(()->new MongoBotProfileStore(broken.mongo()).existingIds(List.of("owned-user"))).isSameAs(failure);
            verify(broken.mongo(),never()).execute(eq(MongoBotProfileStore.COLLECTION),org.mockito.ArgumentMatchers.<CollectionCallback<Document>>any());
        }
    }
    @Test void aggregateErrorsNeverBecomeHumanOrRetryTheProjection() {
        var f=identityMongo();when(f.mongo().execute(org.mockito.ArgumentMatchers.<DbCallback<Document>>any())).thenReturn(metadata("en"));
        var failure=new IllegalStateException("aggregate unavailable");
        when(f.mongo().execute(eq(MongoBotProfileStore.COLLECTION),org.mockito.ArgumentMatchers.<CollectionCallback<Document>>any())).thenThrow(failure);
        assertThatThrownBy(()->new MongoBotProfileStore(f.mongo()).existingIds(List.of("owned-user"))).isSameAs(failure);
        verify(f.mongo(),never()).query(any());
    }
    @Test void activeMongoTransactionAndSpringSynchronizationSkipMetadata() {
        var f=identityMongo();when(f.factory().isTransactionActive()).thenReturn(true);aggregateResult(f,new Document("indices",List.of(0)));
        assertThat(new MongoBotProfileStore(f.mongo()).existingIds(List.of("owned-user"))).containsExactly("owned-user");
        verify(f.mongo(),never()).execute(org.mockito.ArgumentMatchers.<DbCallback<Document>>any());
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        var sync=identityMongo();aggregateResult(sync,new Document("indices",List.of(0)));
        TransactionSynchronizationManager.initSynchronization();
        try { assertThat(new MongoBotProfileStore(sync.mongo()).existingIds(List.of("owned-user"))).containsExactly("owned-user"); }
        finally { TransactionSynchronizationManager.clearSynchronization(); }
        verify(sync.mongo(),never()).execute(org.mockito.ArgumentMatchers.<DbCallback<Document>>any());
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
