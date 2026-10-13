package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bag.Inventory;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardType;
import com.ironoath.web.reward.PlayerBag;
import com.ironoath.web.store.mongo.MongoInventoryStore;

/** Read both historical BSON families without replaying accepted items or rewriting their identities. */
class InventoryItemClaimCompatibilityTest {
    private static final String SPEED="item_speedup_build_1h",WOOD="item_res_wood_10k";
    private static final long NOW=1_791_850_000_000L;
    private static final long LARGE=9_007_199_254_740_993L;
    private ConfigRegistry configs() {
        Path p=Path.of("").toAbsolutePath();
        while(p!=null){if(Files.exists(p.resolve("contract/config")))return ConfigRegistry.loadFromDirectory(p.resolve("contract/config"));p=p.getParent();}
        throw new IllegalStateException("contract/config not found");
    }
    private Document legacy(TestMongo db,String key) {
        var receipt=new com.ironoath.core.reward.ItemClaimReceipt(key,"paid_items","old-order",
                List.of(new RewardItem(RewardType.ITEM,SPEED,LARGE),new RewardItem(RewardType.ITEM,WOOD,1)),
                List.of(new RewardItem(RewardType.ITEM,SPEED,1),new RewardItem(RewardType.ITEM,WOOD,1)),NOW,"old-stable-mail");
        var raw=new Document();db.template().getConverter().write(receipt,raw);return raw;
    }
    private Document current(TestMongo db,String key) {
        var receipt=new com.ironoath.core.bag.ItemClaimReceipt(key,"paid_item","new-order",
                Map.of(SPEED,1L,WOOD,1L),Map.of(SPEED,1L,WOOD,1L),NOW,Set.of("monthly-day:1"),null);
        var raw=new Document();db.template().getConverter().write(receipt,raw);return raw;
    }
    private String insert(TestMongo db,Document receipts) {
        String uid="item-compat-"+UUID.randomUUID();
        db.template().getCollection("inventory").insertOne(new Document("_id",uid).append("version",0L)
                .append("capacityMax",60).append("counts",new Document(SPEED,2L).append(WOOD,2L)).append("itemClaims",receipts));
        return uid;
    }
    private Document raw(TestMongo db,String uid) {return db.template().getCollection("inventory").find(new Document("_id",uid)).first();}
    @Test void twoActualSerializedFamiliesKeepIdentityPrecisionAndFactsAcrossUnrelatedSave() {
        try(var db=TestMongo.tryOpen()) {
            assertThat(db).as("Real isolated Mongo is required").isNotNull();
            Document old=legacy(db,"old-key"),fresh=current(db,"new-key");
            assertThat(((Document)((List<?>)old.get("requested")).get(0)).getString("_id")).isEqualTo(SPEED);
            assertThat(fresh.get("requested")).isInstanceOf(Document.class);
            String uid=insert(db,new Document("old-key",old).append("new-key",fresh));
            var store=new MongoInventoryStore(db.template(),configs());var bag=store.findByPlayerId(uid).orElseThrow();
            var legacy=bag.itemClaims().get("old-key");assertThat(legacy.source()).isEqualTo("paid_items");
            assertThat(legacy.requested().get(SPEED)).isEqualTo(LARGE);assertThat(legacy.facts()).isEmpty();
            assertThat(legacy.overflowMailId()).isEqualTo("old-stable-mail");
            assertThat(bag.itemClaims().get("new-key").facts()).containsExactly("monthly-day:1");
            bag.remove(SPEED,2);bag.setCapacityMax(70);store.save(uid,bag,store.versionOf(uid));
            var saved=raw(db,uid).get("itemClaims",Document.class);
            assertThat(saved.get("old-key",Document.class).get("requested")).isInstanceOf(List.class);
            assertThat(saved.get("new-key",Document.class).get("requested")).isInstanceOf(Document.class);
            var again=store.findByPlayerId(uid).orElseThrow();assertThat(again.itemClaims()).isEqualTo(bag.itemClaims());
            var replay=new PlayerBag(store,configs()).grantItemsOnce(uid,Map.of("removed-item-config",1L),
                    RewardContext.toMail("paid_items","old-order","old-key"),NOW+1);
            assertThat(replay.creditedNow()).isEmpty();assertThat(replay.receipt()).isEqualTo(legacy);
            assertThat(store.findByPlayerId(uid).orElseThrow().countOf(SPEED)).isZero();
            assertThat(new MongoInventoryStore(db.template(),configs()).findByPlayerId(uid).orElseThrow().countOf(WOOD)).isEqualTo(2);
            String other=insert(db,new Document());assertThat(store.findByPlayerId(other).orElseThrow().itemClaims()).isEmpty();
        }
    }
    @Test void legacyReceiptWithoutTypeHintAcceptsConsistentIdAliasButNeverInventsFacts() {
        try(var db=TestMongo.tryOpen()) {
            assertThat(db).isNotNull();var row=legacy(db,"old-key");row.remove("_class");
            for(String field:List.of("requested","credited"))for(Object item:(List<?>)row.get(field)) {
                Document value=(Document)item;value.put("id",value.remove("_id"));
            }
            var store=new MongoInventoryStore(db.template(),configs());var bag=store.findByPlayerId(insert(db,new Document("old-key",row))).orElseThrow();
            assertThat(bag.itemClaims().get("old-key").requested().get(SPEED)).isEqualTo(LARGE);
            assertThat(bag.itemClaims().get("old-key").facts()).isEmpty();
        }
    }
    @Test void freeActivityItemsKeepFreeSourceAndTheirWindowFactsAcrossMongoSave(){
        try(var db=TestMongo.tryOpen()){
            assertThat(db).isNotNull();String uid=insert(db,new Document());var store=new MongoInventoryStore(db.template(),configs());
            var ctx=new RewardContext("free_item","hunt","activity_claim:hunt:1:items",true,Set.of("activity-window:1"));
            var bag=new PlayerBag(store,configs());var accepted=bag.grantItemsOnce(uid,Map.of(SPEED,1L,WOOD,1L),ctx,NOW);
            var after=store.findByPlayerId(uid).orElseThrow();after.remove(SPEED,1);store.save(uid,after,store.versionOf(uid));
            var replay=new PlayerBag(new MongoInventoryStore(db.template(),configs()),configs()).grantItemsOnce(uid,Map.of(SPEED,1L,WOOD,1L),ctx,NOW+1);
            assertThat(replay.creditedNow()).isEmpty();assertThat(replay.receipt()).isEqualTo(accepted.receipt());
            assertThat(replay.receipt().source()).isEqualTo("free_item");assertThat(replay.receipt().facts()).containsExactly("activity-window:1");
            assertThat(store.findByPlayerId(uid).orElseThrow().countOf(SPEED)).isEqualTo(2);
        }
    }
    @ParameterizedTest @ValueSource(strings={"key","source","class","double","id","duplicate","facts"})
    void invalidHistoricalRowsFailWithoutErasingOrChangingTheirOriginalBson(String corruption) {
        try(var db=TestMongo.tryOpen()) {
            assertThat(db).isNotNull();var row=legacy(db,"old-key");
            switch(corruption) {
                case "key" -> row.put("businessKey","other-key");
                case "source" -> row.put("source","unknown-items");
                case "class" -> row.put("_class","unknown.LegacyReceipt");
                case "double" -> ((Document)((List<?>)row.get("requested")).get(0)).put("count",1.5d);
                case "id" -> ((Document)((List<?>)row.get("requested")).get(0)).put("id","other-item");
                case "duplicate" -> {var list=new ArrayList<Object>((List<?>)row.get("requested"));list.add(list.get(0));row.put("requested",list);}
                case "facts" -> row.put("facts",List.of("monthly-day:30"));
            }
            String uid=insert(db,new Document("old-key",row));Document before=raw(db,uid);
            var store=new MongoInventoryStore(db.template(),configs());
            assertThatThrownBy(()->store.findByPlayerId(uid)).isInstanceOf(RuntimeException.class);
            assertThat(store.versionOf(uid)).isZero();assertThat(raw(db,uid)).isEqualTo(before);
        }
    }
}
