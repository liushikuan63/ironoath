package com.ironoath.web.mail;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.mongodb.client.MongoClients;
import com.ironoath.web.store.TestMongo;

/** Same real fault cases using production Mongo ports; only owns this random test database. */
class MailGrantRecoveryMongoTest extends MailGrantRecoveryTest {
    private static final String DATABASE="ironoath_mail_recovery_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void mongo(DynamicPropertyRegistry properties){
        properties.add("ironoath.storage",()->"mongo");
        properties.add("spring.data.mongodb.uri",()->TestMongo.uriForDatabase(DATABASE));
    }
    @AfterAll static void closeOwnedDatabase(){
        try(var client=MongoClients.create(TestMongo.uri())){client.getDatabase(DATABASE).drop();}
    }
}
