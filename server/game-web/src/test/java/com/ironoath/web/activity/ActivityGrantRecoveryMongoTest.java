package com.ironoath.web.activity;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.mongodb.client.MongoClients;

/** Same fault scenarios through production Mongo ports in an owned random database. */
class ActivityGrantRecoveryMongoTest extends ActivityGrantRecoveryTest {
    private static final String DATABASE="ironoath_contract_recovery_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void mongo(DynamicPropertyRegistry properties){
        properties.add("ironoath.storage",()->"mongo");
        properties.add("spring.data.mongodb.uri",()->"mongodb://127.0.0.1:27017/"+DATABASE+"?serverSelectionTimeoutMS=3000");
    }
    @AfterAll static void closeOwnedDatabase(){
        try(var client=MongoClients.create("mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=3000")){
            client.getDatabase(DATABASE).drop();
        }
    }
}
