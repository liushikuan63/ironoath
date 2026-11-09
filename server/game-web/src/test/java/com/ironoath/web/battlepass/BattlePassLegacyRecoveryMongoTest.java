package com.ironoath.web.battlepass;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.mongodb.client.MongoClients;
import com.ironoath.web.store.TestMongo;
/** Production ports, new random test database; never real player storage. */
class BattlePassLegacyRecoveryMongoTest extends BattlePassLegacyRecoveryTest {
    static final String DATABASE="ironoath_bp_recovery_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void mongo(DynamicPropertyRegistry p){p.add("ironoath.storage",()->"mongo");p.add("spring.data.mongodb.uri",()->TestMongo.uriForDatabase(DATABASE));}
    @AfterAll static void closeOwned(){try(var client=MongoClients.create(TestMongo.uri())){client.getDatabase(DATABASE).drop();}}
}
