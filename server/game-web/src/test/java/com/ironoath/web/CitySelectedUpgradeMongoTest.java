package com.ironoath.web;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.mongodb.client.MongoClients;

/** Same real service/error/competition cases, using only this marked Mongo fixture namespace. */
class CitySelectedUpgradeMongoTest extends CitySelectedUpgradeTest {
    private static final String OWNER=UUID.randomUUID().toString().replace("-","");
    private static final String DATABASE="ironoath_city_selected_"+OWNER;
    private static final String URI="mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=3000";
    @DynamicPropertySource static void mongo(DynamicPropertyRegistry properties) {
        properties.add("ironoath.storage",()->"mongo");
        properties.add("spring.data.mongodb.uri",()->"mongodb://127.0.0.1:27017/"+DATABASE+"?serverSelectionTimeoutMS=3000");
    }
    @BeforeAll static void markOwned() {
        try(var client=MongoClients.create(URI)){client.getDatabase(DATABASE).getCollection("__owned_run").insertOne(new Document("_id",OWNER));}
    }
    @AfterAll static void dropOwned() {
        assertThat(DATABASE).isEqualTo("ironoath_city_selected_"+OWNER);
        try(var client=MongoClients.create(URI)) {
            var db=client.getDatabase(DATABASE);assertThat(db.getCollection("__owned_run").countDocuments(new Document("_id",OWNER))).isEqualTo(1);
            assertThat(((Number)db.runCommand(new Document("dropDatabase",1)).get("ok")).doubleValue()).isEqualTo(1);
            var found=client.getDatabase("admin").runCommand(new Document("listDatabases",1).append("filter",new Document("name",DATABASE)).append("nameOnly",true));
            assertThat(found.getList("databases",Document.class)).isEmpty();
        }
    }
}
