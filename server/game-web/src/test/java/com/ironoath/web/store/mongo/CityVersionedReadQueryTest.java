package com.ironoath.web.store.mongo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class CityVersionedReadQueryTest {
    @Test void oneTypedFindReturnsBothPayloadAndVersionWithoutASecondRead() {
        var mongo=mock(MongoTemplate.class);when(mongo.findById("owned",CityDocument.class,CityDocument.COLLECTION))
                .thenReturn(new CityDocument("owned",42,List.of(),1));
        var loaded=new MongoCityStore(mongo).findVersionedByPlayerId("owned").orElseThrow();
        assertThat(loaded.version()).isEqualTo(42);assertThat(loaded.state().extraQueues()).isEqualTo(1);
        verify(mongo).findById("owned",CityDocument.class,CityDocument.COLLECTION);verifyNoMoreInteractions(mongo);
    }
    @Test void nullNeverQueriesAndMissingDoesNotInventAnInitialVersion() {
        var mongo=mock(MongoTemplate.class);var store=new MongoCityStore(mongo);
        assertThat(store.findVersionedByPlayerId(null)).isEmpty();verifyNoInteractions(mongo);
        assertThat(store.findVersionedByPlayerId("missing")).isEmpty();
        verify(mongo).findById("missing",CityDocument.class,CityDocument.COLLECTION);verifyNoMoreInteractions(mongo);
    }
    @Test void storageErrorsPropagateWithoutSynthesizingAnEmptyPair() {
        var mongo=mock(MongoTemplate.class);var failure=new IllegalStateException("owned city read failure");
        when(mongo.findById("owned",CityDocument.class,CityDocument.COLLECTION)).thenThrow(failure);
        assertThatThrownBy(()->new MongoCityStore(mongo).findVersionedByPlayerId("owned")).isSameAs(failure);
    }
}
