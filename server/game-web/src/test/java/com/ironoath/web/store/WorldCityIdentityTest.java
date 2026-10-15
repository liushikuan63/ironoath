package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.store.memory.InMemoryWorldStore;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.mongo.MongoWorldStore;
import com.ironoath.web.store.mongo.WorldCityDocument;

class WorldCityIdentityTest {
    static TestMongo db;
    @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).as("真实Mongo必须可用").isNotNull();}
    @AfterAll static void close(){if(db!=null)db.close();}
    private MongoWorldStore mongo(){return new MongoWorldStore(db.template(),new InMemoryMarchStore(16),16);}
    private void clear(WorldRepository store){
        if(store instanceof InMemoryWorldStore memory)memory.clear();
        else ((MongoWorldStore)store).clear();
    }

    @Test void idsAreImmutableSnapshotsAndSeePeerPlacementsMovesAndClear() {
        for(WorldRepository store:List.of(new InMemoryWorldStore(new InMemoryMarchStore(16),16),mongo())) {
            clear(store);WorldRepository peer=store instanceof MongoWorldStore?mongo():store;
            assertThat(store.cityPlayerIds()).isEmpty();
            assertThat(store.placeCity("first",Coord.of(1,1))).isTrue();
            Set<String> first=store.cityPlayerIds();assertThat(first).containsExactly("first");
            assertThatThrownBy(()->first.add("intruder")).isInstanceOf(UnsupportedOperationException.class);
            assertThat(peer.placeCity("second",Coord.of(2,2))).isTrue();
            assertThat(store.cityPlayerIds()).containsExactlyInAnyOrder("first","second");
            assertThat(first).containsExactly("first");
            assertThat(peer.moveCity("first",Coord.of(3,3))).isTrue();
            assertThat(store.cityPlayerIds()).isEqualTo(store.allCities().keySet());
            clear(peer);assertThat(store.cityPlayerIds()).isEmpty();assertThat(first).containsExactly("first");
        }
    }
    @Test void projectionPreservesTheExistingFullMapIdConversionAndDeduplication() {
        var store=mongo();store.clear();String lower="507f1f77bcf86cd799439012",upper=lower.toUpperCase();
        db.template().getCollection(WorldCityDocument.COLLECTION).insertMany(List.of(
                city(new ObjectId(lower),1),city(lower,2),city(upper,3),city("P-normal",4)));
        assertThat(store.allCities()).hasSize(3);
        assertThat(store.cityPlayerIds()).isEqualTo(store.allCities().keySet()).containsExactlyInAnyOrder(lower,upper,"P-normal");
    }
    private Document city(Object id,int x){return new Document("_id",id).append("coordKey",Coord.of(x,1).storageKey()).append("x",(long)x).append("y",1L);}
}
