package com.ironoath.web.mail;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.mongodb.client.MongoClients;

/** Same real fault cases using production Mongo ports; only owns this random test database. */
class MailGrantRecoveryMongoTest extends MailGrantRecoveryTest {
    private static final String DATABASE="ironoath_mail_recovery_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void mongo(DynamicPropertyRegistry properties){
        properties.add("ironoath.storage",()->"mongo");
        properties.add("spring.data.mongodb.uri",()->"mongodb://127.0.0.1:27017/"+DATABASE+"?serverSelectionTimeoutMS=3000");
    }
    @AfterAll static void closeOwnedDatabase(){
        try(var client=MongoClients.create("mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=3000")){client.getDatabase(DATABASE).drop();}
    }
}
