package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.web.bot.BotProfileStore;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.bot.BotRulesAssembler;
import com.ironoath.web.store.memory.InMemoryBotProfileStore;
import com.ironoath.web.store.mongo.MongoBotProfileStore;

class BotProfileStoreTest {
    static TestMongo db;
    static BotRulesAssembler rules;
    @BeforeAll static void open() {
        db=TestMongo.tryOpen();assertThat(db).as("真实Mongo必须可用").isNotNull();
        Path config=Path.of("../../contract/config");if(!config.toFile().isDirectory())config=Path.of("contract/config");
        rules=new BotRulesAssembler(ConfigRegistry.loadFromDirectory(config));
    }
    @AfterAll static void close() { if(db!=null)db.close(); }
    private List<BotProfileStore> stores() {
        var mongo=new MongoBotProfileStore(db.template());mongo.clear();
        return List.of(new InMemoryBotProfileStore(),mongo);
    }
    private BotProfile profile(String id,long aggression) {
        return new BotProfile(id,"bot_jielue",new BotProfile.AiProfile(aggression,4_000,2_000,6_000),
                new BotProfile.Persona(42,7,99,List.of(0,12,23),3,27,1_000),9_000);
    }
    @Test void completeFieldsAtomicReplaceDeleteAndClearHaveEquivalentSemantics() {
        for(var store:stores()) {
            var original=profile("owned-bot",8_000);var changed=profile("owned-bot",3_000);
            assertThat(store.put(original)).isNull();assertThat(store.find(original.botId())).contains(original);
            assertThat(store.put(changed)).isEqualTo(original);assertThat(store.all()).containsExactly(changed);
            assertThat(store.remove(null)).isFalse();assertThat(store.remove(original.botId())).isTrue();
            assertThat(store.find(original.botId())).isEmpty();assertThat(store.remove(original.botId())).isFalse();
            store.put(original);store.clear();assertThat(store.all()).isEmpty();
        }
    }
    @Test void independentReadersImmediatelySeeIdentityRegistrationAndRetirement() {
        for(var store:stores()) {
            BotProfileStore peer=store instanceof MongoBotProfileStore?new MongoBotProfileStore(db.template()):store;
            var a=new BotRegistry(rules,store);var b=new BotRegistry(rules,peer);
            a.register(profile("owned-bot",8_000));
            assertThat(b.isBot("owned-bot")).isTrue();assertThat(b.mayHoldOffice("owned-bot","NATION",false,true)).isFalse();
            assertThat(b.humansIn(List.of("owned-bot","owned-human"))).containsExactly("owned-human");
            a.unregister("owned-bot");assertThat(b.isBot("owned-bot")).isFalse();
        }
    }
    @Test void identityStoreFailureCannotDefaultToHumanOrSilentlyRegister() {
        BotProfileStore broken=org.mockito.Mockito.mock(BotProfileStore.class);
        org.mockito.Mockito.when(broken.find("owned-bot")).thenThrow(new IllegalStateException("temporary storage failure"));
        var registry=new BotRegistry(rules,broken);
        assertThatThrownBy(()->registry.mayHoldOffice("owned-bot","NATION",false,true)).isInstanceOf(IllegalStateException.class);
    }
    @Test void requestedIdentityProjectionPreservesScopeAndImmediatePeerChanges() {
        for(var store:stores()) {
            BotProfileStore peer=store instanceof MongoBotProfileStore?new MongoBotProfileStore(db.template()):store;
            store.put(profile("owned-a",8000));store.put(profile("outside",4000));
            assertThat(store.existingIds(null)).isEmpty();assertThat(store.existingIds(List.of())).isEmpty();
            var requested=java.util.Arrays.asList("owned-a",null,"owned-human","owned-a");
            assertThat(peer.existingIds(requested)).containsExactly("owned-a");
            store.remove("owned-a");assertThat(peer.existingIds(requested)).isEmpty();
            store.put(profile("owned-human",3000));
            assertThat(peer.existingIds(requested)).containsExactly("owned-human");
            assertThat(new BotRegistry(rules,peer).humansIn(requested)).containsExactly("owned-a","owned-a");
        }
    }
    @Test void identityProjectionFailureCannotTreatEveryoneAsHuman() {
        BotProfileStore broken=org.mockito.Mockito.mock(BotProfileStore.class);
        org.mockito.Mockito.when(broken.existingIds(org.mockito.ArgumentMatchers.anyCollection()))
                .thenThrow(new IllegalStateException("temporary identity projection failure"));
        assertThatThrownBy(()->new BotRegistry(rules,broken).humansIn(List.of("owned-bot","owned-human")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("identity projection");
    }
    @Test void identityEnumerationAndCountStaySortedAndObservePeerWrites() {
        for(var store:stores()) {
            BotProfileStore peer=store instanceof MongoBotProfileStore?new MongoBotProfileStore(db.template()):store;
            assertThat(peer.ids()).isEmpty();assertThat(peer.size()).isZero();
            store.put(profile("owned-z",8000));store.put(profile("owned-a",3000));
            assertThat(peer.ids()).containsExactly("owned-a","owned-z");assertThat(peer.size()).isEqualTo(2);
            store.put(profile("owned-a",4000));
            assertThat(peer.ids()).containsExactly("owned-a","owned-z");assertThat(peer.size()).isEqualTo(2);
            store.remove("owned-a");assertThat(peer.ids()).containsExactly("owned-z");assertThat(peer.size()).isEqualTo(1);
            store.clear();assertThat(peer.ids()).isEmpty();assertThat(peer.size()).isZero();
        }
    }
    @Test void identityEnumerationPreservesHexStringIdsAndCaseThroughMongoObjectIdMapping() {
        for(var store:stores()) {
            store.put(profile("507f1f77bcf86cd799439011",8000));
            store.put(profile("507F1F77BCF86CD799439012",3000));
            store.put(profile("owned-non-hex",4000));
            assertThat(store.ids()).containsExactlyElementsOf(store.all().stream().map(BotProfile::botId).toList());
            assertThat(store.ids()).contains("507f1f77bcf86cd799439011","507F1F77BCF86CD799439012");
            assertThat(store.size()).isEqualTo(3);
        }
    }
    @Test void batchIdentityMatchesPointAuthorityForObjectIdsAndRequestedCaseAliases() {
        var store=new MongoBotProfileStore(db.template());store.clear();
        var peer=new MongoBotProfileStore(db.template());
        String lower="507f1f77bcf86cd799439011",upper="507F1F77BCF86CD799439012";
        store.put(profile(lower,8000));store.put(profile(upper,3000));
        var requested=java.util.Arrays.asList(lower,lower.toUpperCase(java.util.Locale.ROOT),upper,
                upper.toLowerCase(java.util.Locale.ROOT),null,"human-z","human-a","human-z",lower);
        var expected=requested.stream().filter(java.util.Objects::nonNull).filter(id->peer.find(id).isPresent())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(expected).containsExactlyInAnyOrder(lower,lower.toUpperCase(java.util.Locale.ROOT),upper,
                upper.toLowerCase(java.util.Locale.ROOT));
        assertThat(peer.existingIds(requested)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(new BotRegistry(rules,peer).humansIn(requested)).containsExactly("human-z","human-a","human-z");
        store.remove(lower);store.put(profile("human-z",4000));
        assertThat(peer.existingIds(requested)).containsExactlyInAnyOrder(upper,upper.toLowerCase(java.util.Locale.ROOT),"human-z");
        assertThat(new BotRegistry(rules,peer).humansIn(requested)).containsExactly(lower,
                lower.toUpperCase(java.util.Locale.ROOT),"human-a",lower);
    }
    @Test void batchIdentityPreservesRawStringKeysWithoutInventingObjectIdAliases() {
        var store=new MongoBotProfileStore(db.template());store.clear();
        String raw="507F1F77BCF86CD799439013",nonHex="owned-MixedCase";
        var converter=db.template().getConverter();
        var document=new org.bson.Document();converter.write(new MongoBotProfileStore.ProfileDocument(raw,profile(raw,8000)),document);
        document.put("_id",raw);db.template().getCollection(MongoBotProfileStore.COLLECTION).insertOne(document);
        store.put(profile(nonHex,3000));
        var requested=java.util.Arrays.asList(raw,raw.toLowerCase(java.util.Locale.ROOT),nonHex,"owned-mixedcase",null,"",nonHex);
        var expected=requested.stream().filter(java.util.Objects::nonNull).filter(id->store.find(id).isPresent())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(expected).containsExactly(nonHex);
        assertThat(store.existingIds(requested)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(store.existingIds(java.util.Arrays.asList(null,null))).isEmpty();
        store.put(profile(raw,4000)); // Same text can coexist physically as BSON ObjectId and String.
        assertThat(db.template().getCollection(MongoBotProfileStore.COLLECTION).countDocuments()).isEqualTo(3);
        assertThat(store.find(raw)).isPresent();
        assertThat(store.existingIds(requested)).containsExactlyInAnyOrder(raw,raw.toLowerCase(java.util.Locale.ROOT),nonHex);
        assertThat(store.remove(raw)).isTrue();
        assertThat(db.template().getCollection(MongoBotProfileStore.COLLECTION).countDocuments()).isEqualTo(2);
        assertThat(store.find(raw)).isEmpty();
        assertThat(store.existingIds(requested)).containsExactly(nonHex);
    }

    @Test void batchIdentityUsesSamePropertyConverterAsPointAuthorityWithoutSecondMapping() {
        for (String physicalId : List.of("key:owned-a", "507f1f77bcf86cd799439099")) {
            db.template().getCollection(MongoBotProfileStore.COLLECTION).deleteMany(new org.bson.Document());
            var expectedProfile = profile("owned-a", 8000);
            var row = new org.bson.Document();
            db.template().getConverter().write(
                    new MongoBotProfileStore.ProfileDocument("owned-a", expectedProfile), row);
            row.put("_id", physicalId); // Deliberate physical BSON String, including the valid-hex case.
            db.template().getCollection(MongoBotProfileStore.COLLECTION).insertOne(row);
            var context = new org.springframework.data.mongodb.core.mapping.MongoMappingContext();
            var converter = new org.springframework.data.mongodb.core.convert.MappingMongoConverter(
                    org.springframework.data.mongodb.core.convert.NoOpDbRefResolver.INSTANCE, context);
            var propertyConverter = new org.springframework.data.convert.PropertyValueConverter<
                    Object, Object, org.springframework.data.mongodb.core.convert.MongoConversionContext>() {
                private String physical(Object value) {
                    return "owned-a".equals(value) ? physicalId : "key:" + value;
                }
                @Override public Object write(Object value,
                        org.springframework.data.mongodb.core.convert.MongoConversionContext conversionContext) {
                    if (value instanceof org.bson.Document condition && condition.containsKey("$in")) {
                        return new org.bson.Document("$in", ((java.util.Collection<?>) condition.get("$in"))
                                .stream().map(this::physical).toList());
                    }
                    return physical(value);
                }
                @Override public Object read(Object value,
                        org.springframework.data.mongodb.core.convert.MongoConversionContext conversionContext) {
                    if (physicalId.equals(value)) return "owned-a";
                    return value instanceof String text && text.startsWith("key:") ? text.substring(4) : value;
                }
            };
            converter.setCustomConversions(org.springframework.data.mongodb.core.convert.MongoCustomConversions.create(
                    adapter -> adapter.configurePropertyConversions(registrar -> registrar.registerConverter(
                            MongoBotProfileStore.ProfileDocument.class, "id", propertyConverter))));
            converter.afterPropertiesSet();
            var template = new org.springframework.data.mongodb.core.MongoTemplate(
                    db.template().getMongoDatabaseFactory(), converter);
            var store = new MongoBotProfileStore(template);
            assertThat(store.find("owned-a")).contains(expectedProfile); // Authority must be proven first.
            assertThat(store.find("human")).isEmpty();
            assertThat(store.existingIds(java.util.Arrays.asList("owned-a", "human", null, "owned-a")))
                    .containsExactly("owned-a");
            assertThat(new BotRegistry(rules, store).humansIn(List.of("human", "owned-a", "human")))
                    .containsExactly("human", "human");
            assertThat(db.template().getCollection(MongoBotProfileStore.COLLECTION)
                    .find(new org.bson.Document("_id", physicalId)).first().get("_id"))
                    .isInstanceOf(String.class).isEqualTo(physicalId);
        }
    }
}
