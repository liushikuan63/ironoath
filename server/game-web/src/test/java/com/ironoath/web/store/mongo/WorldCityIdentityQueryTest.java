package com.ironoath.web.store.mongo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.web.store.memory.InMemoryWorldStore;
import com.ironoath.core.world.Coord;

class WorldCityIdentityQueryTest {
    @Test void mongoIdsNeverLoadCoordinateDocuments() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        var store=new MongoWorldStore(mongo,mock(MarchRepository.class),16);
        assertThat(store.cityPlayerIds()).isEmpty();
        verify(mongo,never()).findAll(WorldCityDocument.class,WorldCityDocument.COLLECTION);
        ArgumentCaptor<Query> query=ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(query.capture(),any(),eq(WorldCityDocument.COLLECTION));
        assertThat(query.getValue().getFieldsObject()).isEqualTo(new Document("_id",1));
        assertThat(query.getValue().getQueryObject()).isEmpty();
        verifyNoMoreInteractions(mongo);
    }
    @Test void mongoIdentityFailuresAreNotCachedOrConvertedToEmptySets() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        doThrow(new IllegalStateException("city ids unavailable")).when(mongo)
                .find(any(Query.class),any(),eq(WorldCityDocument.COLLECTION));
        var store=new MongoWorldStore(mongo,mock(MarchRepository.class),16);
        assertThatThrownBy(store::cityPlayerIds).hasMessage("city ids unavailable");
    }
    @Test void memoryIdentityReadDoesNotCopyCoordinates() {
        var store=spy(new InMemoryWorldStore(mock(MarchRepository.class),16));
        store.placeCity("owner",Coord.of(1,1));
        assertThat(store.cityPlayerIds()).containsExactly("owner");
        verify(store,never()).allCities();
    }
}
