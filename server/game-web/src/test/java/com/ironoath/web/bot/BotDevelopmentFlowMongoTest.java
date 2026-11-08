package com.ironoath.web.bot;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.mongodb.client.MongoClients;
import com.ironoath.web.store.TestMongo;

/** The same real development service cases use production Mongo ports in this owned test database. */
class BotDevelopmentFlowMongoTest extends BotDevelopmentFlowTest {
    private static final String DATABASE="ironoath_bot_growth_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void mongo(DynamicPropertyRegistry properties){
        properties.add("ironoath.storage",()->"mongo");
        properties.add("spring.data.mongodb.uri",()->TestMongo.uriForDatabase(DATABASE));
    }
    @AfterAll static void closeOwnedDatabase(){
        try(var client=MongoClients.create(TestMongo.uri())){client.getDatabase(DATABASE).drop();}
    }
}
