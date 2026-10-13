package com.ironoath.web.store;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.bson.Document;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bag.*;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.web.store.memory.InMemoryInventoryStore;
import com.ironoath.web.store.mongo.MongoInventoryStore;
class InventoryItemClaimEquivalenceTest {
    private Path configs() {var p=Path.of("").toAbsolutePath();while(p!=null){if(Files.isDirectory(p.resolve("contract/config")))return p.resolve("contract/config");p=p.getParent();}throw new IllegalStateException();}
    @Test void memoryAndRealMongoPreserveReceiptAndUidIsolationAcrossReloads() {
        try(var db=TestMongo.tryOpen()) {assertThat(db).as("Real isolated Mongo is required").isNotNull();
            for(InventoryRepository store:List.of(new InMemoryInventoryStore(),new MongoInventoryStore(db.template(),ConfigRegistry.loadFromDirectory(configs())))){
                String uid="monthly-items-"+UUID.randomUUID();store.insertIfAbsent(uid,Inventory.empty(1));
                var bag=store.findByPlayerId(uid).orElseThrow();var ctx=new RewardContext("paid_item","order1","daily1",true);
                var items=new LinkedHashMap<String,Long>();items.put("item_speedup_build_1h",1L);items.put("item_res_wood_10k",1L);
                var receipt=bag.claimItemsOnce(items,Map.of("item_speedup_build_1h",999L,"item_res_wood_10k",999L),ctx,1000);
                store.save(uid,bag,store.versionOf(uid));bag=store.findByPlayerId(uid).orElseThrow();
                assertThat(bag.itemClaims()).containsEntry("daily1",receipt);assertThat(bag.snapshot()).hasSize(1);
                bag.completeItemOverflow("daily1","mail1");store.save(uid,bag,store.versionOf(uid));
                assertThat(store.findByPlayerId(uid).orElseThrow().itemClaims().get("daily1").overflowMailId()).isEqualTo("mail1");
                String other=uid+"other";store.insertIfAbsent(other,Inventory.empty(2));assertThat(store.findByPlayerId(other).orElseThrow().itemClaims()).isEmpty();
            }
        }
    }
    @Test void actualOlderMongoDocumentWithoutItemClaimsOrEquipmentSequenceKeepsCountsAndEquipment() {
        try(var db=TestMongo.tryOpen()){assertThat(db).isNotNull();var store=new MongoInventoryStore(db.template(),ConfigRegistry.loadFromDirectory(configs()));
            String uid="monthly-old-"+UUID.randomUUID();var bag=Inventory.empty(20);bag.add("item_res_wood_10k",3,999);bag.mintEquips("eq_iron_sword",1);store.insertIfAbsent(uid,bag);
            db.template().getCollection("inventory").updateOne(new Document("_id",uid),new Document("$unset",new Document("itemClaims",1).append("nextEquipUid",1)));
            var restored=store.findByPlayerId(uid).orElseThrow();assertThat(restored.countOf("item_res_wood_10k")).isEqualTo(3);assertThat(restored.itemClaims()).isEmpty();
            assertThat(restored.equipSnapshot()).isEqualTo(bag.equipSnapshot());assertThat(restored.nextEquipUid()).isEqualTo(2);
            assertThat(restored.mintEquips("eq_iron_sword",1)).containsExactly("e2");
            store.save(uid,restored,store.versionOf(uid));restored=store.findByPlayerId(uid).orElseThrow();
            assertThat(restored.countOf("eq_iron_sword")).isEqualTo(2);assertThat(restored.nextEquipUid()).isEqualTo(3);assertThat(restored.itemClaims()).isEmpty();
        }
    }
}
