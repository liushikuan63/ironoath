package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.CollectionOptions;
import org.springframework.data.mongodb.core.query.Collation;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.mongo.MongoPlayerStore;
import com.ironoath.web.store.mongo.PlayerDocument;

/** Actual collection metadata and actual point-query ID conversion, without player data reads. */
class PlayerLockAddressingTest {
    private TestMongo db;
    private MongoPlayerStore store;
    @BeforeEach void open() {
        db=TestMongo.tryOpen();assertThat(db).as("真实Mongo地址判定不可跳过").isNotNull();
        store=new MongoPlayerStore(db.template());
    }
    @AfterEach void close() { if(db!=null)db.close(); }

    @Test void independentStringKeysAndRepeatedIdenticalLocksAreSafe() {
        assertThat(store.supportsIndependentPlayerLocks(List.of("P1","P2"))).isTrue();
        assertThat(store.supportsIndependentPlayerLocks(List.of("P1","P1"))).isTrue();
    }
    @Test void objectIdCaseAliasesAreNotIndependent() {
        assertThat(store.supportsIndependentPlayerLocks(List.of("abcdef0123456789abcdef01","ABCDEF0123456789ABCDEF01"))).isFalse();
    }
    @Test void collectionCollationIsRereadWithoutCaching() {
        assertThat(store.supportsIndependentPlayerLocks(List.of("P1","p1"))).isTrue();
        db.template().dropCollection(PlayerDocument.COLLECTION);
        db.template().createCollection(PlayerDocument.COLLECTION,CollectionOptions.empty().collation(Collation.of("en").strength(2)));
        assertThat(store.supportsIndependentPlayerLocks(List.of("P1","p1"))).isFalse();
        db.template().dropCollection(PlayerDocument.COLLECTION);
        db.template().createCollection(PlayerDocument.COLLECTION);
        assertThat(store.supportsIndependentPlayerLocks(List.of("P1","p1"))).isTrue();
    }
    @Test void unknownProviderDefaultsToSerialAndMemoryUsesRawKeys() {
        PlayerRepository unknown=mock(PlayerRepository.class,CALLS_REAL_METHODS);
        assertThat(unknown.supportsIndependentPlayerLocks(List.of("P1","P2"))).isFalse();
        assertThat(new InMemoryPlayerStore().supportsIndependentPlayerLocks(List.of("P1","p1"))).isTrue();
    }
    @Test void invalidUnicodeAndUnknownCollectionAreConservative() {
        assertThat(store.supportsIndependentPlayerLocks(List.of("P1","\uD800"))).isFalse();
        db.template().dropCollection(PlayerDocument.COLLECTION);
        assertThat(store.supportsIndependentPlayerLocks(List.of("P1","P2"))).isFalse();
    }

    @Test void customPropertyConverterMustUseActualPointReadAddressing() {
        assertThat(new MongoPlayerStore(db.template()).insertIfAbsent(PlayerStoreContractTest.newSave("physical-owned","device-owned"))).isTrue();
        var context=new org.springframework.data.mongodb.core.mapping.MongoMappingContext();
        var converter=new org.springframework.data.mongodb.core.convert.MappingMongoConverter(
                org.springframework.data.mongodb.core.convert.NoOpDbRefResolver.INSTANCE,context);
        var propertyConverter=new org.springframework.data.convert.PropertyValueConverter<
                Object,Object,org.springframework.data.mongodb.core.convert.MongoConversionContext>() {
            @Override public Object write(Object value,org.springframework.data.mongodb.core.convert.MongoConversionContext ignored) {
                if("broken".equals(value))throw new IllegalStateException("owned mapper failure");
                return "physical-owned";
            }
            @Override public Object read(Object value,org.springframework.data.mongodb.core.convert.MongoConversionContext ignored) { return value; }
        };
        converter.setCustomConversions(org.springframework.data.mongodb.core.convert.MongoCustomConversions.create(
                adapter->adapter.configurePropertyConversions(registrar->registrar.registerConverter(
                        PlayerDocument.class,"playerId",propertyConverter))));
        converter.afterPropertiesSet();
        var custom=new MongoPlayerStore(new org.springframework.data.mongodb.core.MongoTemplate(db.template().getMongoDatabaseFactory(),converter));
        assertThat(custom.findByPlayerId("raw-a").orElseThrow().playerId()).isEqualTo("physical-owned");
        assertThat(custom.findByPlayerId("raw-b").orElseThrow().playerId()).isEqualTo("physical-owned");
        assertThat(custom.supportsIndependentPlayerLocks(List.of("raw-a","raw-b"))).isFalse();
        assertThat(custom.supportsIndependentPlayerLocks(List.of("raw-a","broken"))).as("未支持的自定义ID规则回退串行，不预执行规则").isFalse();
    }

    @Test void metadataAuthorizationFallsBackButAuthenticationAndOtherErrorsPropagate() {
        var template=spy(db.template());var checked=new MongoPlayerStore(template);
        var unauthorized=commandFailure(13);doThrow(unauthorized).when(template).getDb();
        assertThat(checked.supportsIndependentPlayerLocks(List.of("P1","P2"))).isFalse();
        var authentication=commandFailure(18);doThrow(authentication).when(template).getDb();
        assertThatThrownBy(()->checked.supportsIndependentPlayerLocks(List.of("P1","P2"))).isSameAs(authentication);
        var network=new IllegalStateException("owned connection failure");doThrow(network).when(template).getDb();
        assertThatThrownBy(()->checked.supportsIndependentPlayerLocks(List.of("P1","P2"))).isSameAs(network);
        var conversion=new IllegalStateException("owned converter access failure");doThrow(conversion).when(template).getConverter();
        assertThatThrownBy(()->checked.supportsIndependentPlayerLocks(List.of("P1","P2"))).isSameAs(conversion);
    }

    @Test void resourceBoundContextNeverUsesTheParallelCapabilityProbe() {
        var template=mock(org.springframework.data.mongodb.core.MongoTemplate.class);
        var checked=new MongoPlayerStore(template);
        org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(this,"owned resource");
        try { assertThat(checked.supportsIndependentPlayerLocks(List.of("P1","P2"))).isFalse();verifyNoInteractions(template); }
        finally { org.springframework.transaction.support.TransactionSynchronizationManager.unbindResource(this); }
    }

    private static com.mongodb.MongoCommandException commandFailure(int code) {
        return new com.mongodb.MongoCommandException(new org.bson.BsonDocument("ok",new org.bson.BsonDouble(0))
                .append("code",new org.bson.BsonInt32(code)).append("errmsg",new org.bson.BsonString("owned metadata error")),
                new com.mongodb.ServerAddress());
    }

    @Test void customIdPropertyRulesAreUnknownEvenWhenThisProbeProducesDistinctKeys() {
        var context=new org.springframework.data.mongodb.core.mapping.MongoMappingContext();
        var converter=new org.springframework.data.mongodb.core.convert.MappingMongoConverter(
                org.springframework.data.mongodb.core.convert.NoOpDbRefResolver.INSTANCE,context);
        var identity=new org.springframework.data.convert.PropertyValueConverter<
                Object,Object,org.springframework.data.mongodb.core.convert.MongoConversionContext>() {
            @Override public Object write(Object value,org.springframework.data.mongodb.core.convert.MongoConversionContext ignored) { return value; }
            @Override public Object read(Object value,org.springframework.data.mongodb.core.convert.MongoConversionContext ignored) { return value; }
        };
        converter.setCustomConversions(org.springframework.data.mongodb.core.convert.MongoCustomConversions.create(
                adapter->adapter.configurePropertyConversions(registrar->registrar.registerConverter(PlayerDocument.class,"playerId",identity))));
        converter.afterPropertiesSet();
        var custom=new MongoPlayerStore(new org.springframework.data.mongodb.core.MongoTemplate(db.template().getMongoDatabaseFactory(),converter));
        assertThat(custom.supportsIndependentPlayerLocks(List.of("P1","P2"))).isFalse();
    }

    @Test void nestedDocumentCodecCanCollapseKeysEvenWhenOuterCodecClassesAreStandard() {
        assertThat(store.insertIfAbsent(PlayerStoreContractTest.newSave("p1","owned-nested-codec"))).isTrue();
        var lowerStrings=new org.bson.codecs.Codec<String>() {
            @Override public Class<String> getEncoderClass() { return String.class; }
            @Override public void encode(org.bson.BsonWriter writer,String value,org.bson.codecs.EncoderContext context) {
                writer.writeString(value.toLowerCase(java.util.Locale.ROOT));
            }
            @Override public String decode(org.bson.BsonReader reader,org.bson.codecs.DecoderContext context) { return reader.readString(); }
        };
        var defaults=com.mongodb.MongoClientSettings.getDefaultCodecRegistry();
        var inner=org.bson.codecs.configuration.CodecRegistries.fromRegistries(
                org.bson.codecs.configuration.CodecRegistries.fromCodecs(lowerStrings),defaults);
        var outer=org.bson.codecs.configuration.CodecRegistries.fromRegistries(
                org.bson.codecs.configuration.CodecRegistries.fromCodecs(new org.bson.codecs.DocumentCodec(inner)),defaults);
        assertThat(outer.get(org.bson.Document.class).getClass()).isEqualTo(org.bson.codecs.DocumentCodec.class);
        assertThat(outer.get(String.class).getClass()).isEqualTo(org.bson.codecs.StringCodec.class);
        assertThat(outer.get(org.bson.types.ObjectId.class).getClass()).isEqualTo(org.bson.codecs.ObjectIdCodec.class);
        var settings=com.mongodb.MongoClientSettings.builder().applyConnectionString(new com.mongodb.ConnectionString(TestMongo.uri())).codecRegistry(outer).build();
        try(var client=com.mongodb.client.MongoClients.create(settings)) {
            var custom=spy(new MongoPlayerStore(new org.springframework.data.mongodb.core.MongoTemplate(client,db.template().getDb().getName())));
            assertThat(custom.findByPlayerId("P1").orElseThrow().playerId()).isEqualTo("p1");
            assertThat(custom.findByPlayerId("p1").orElseThrow().playerId()).isEqualTo("p1");
            assertThat(custom.supportsIndependentPlayerLocks(List.of("P1","p1"))).isFalse();
            var rules=new com.ironoath.web.bot.BotRulesAssembler(com.ironoath.config.ConfigRegistry.loadFromDirectory(java.nio.file.Path.of("contract/config")));
            var registry=new com.ironoath.web.bot.BotRegistry(rules,new com.ironoath.web.store.memory.InMemoryBotProfileStore());
            for(String label:List.of("P1","p1"))registry.register(rules.archetypes().get(0).spawnProfile(label,7,com.ironoath.common.rng.Rng.of(1)));
            var spawner=mock(com.ironoath.web.bot.BotSpawnService.class);when(spawner.humanAverageMatchPower()).thenReturn(10_000L);
            var service=new com.ironoath.web.bot.BotCalibrationService(registry,rules,custom,spawner,
                    new com.ironoath.web.lock.InJvmPlayerLock(),new com.ironoath.web.bot.BotCalibrationBatch(2));
            assertThatCode(()->service.calibrateInParallelIfNewDay(1_760_000_000_000L)).doesNotThrowAnyException();
            verify(custom,times(1)).save(any(com.ironoath.core.player.PlayerSave.class));
        }
    }

    @Test void codecProducingInvalidUnicodeCannotCertifyIndependentKeys() {
        var invalidStrings=new org.bson.codecs.Codec<String>() {
            @Override public Class<String> getEncoderClass() { return String.class; }
            @Override public void encode(org.bson.BsonWriter writer,String value,org.bson.codecs.EncoderContext context) { writer.writeString("\uD800"); }
            @Override public String decode(org.bson.BsonReader reader,org.bson.codecs.DecoderContext context) { return reader.readString(); }
        };
        var defaults=com.mongodb.MongoClientSettings.getDefaultCodecRegistry();
        var inner=org.bson.codecs.configuration.CodecRegistries.fromRegistries(org.bson.codecs.configuration.CodecRegistries.fromCodecs(invalidStrings),defaults);
        var outer=org.bson.codecs.configuration.CodecRegistries.fromRegistries(org.bson.codecs.configuration.CodecRegistries.fromCodecs(new org.bson.codecs.DocumentCodec(inner)),defaults);
        var template=spy(db.template());var collection=mock(com.mongodb.client.MongoCollection.class);
        when(collection.getCodecRegistry()).thenReturn(outer);doReturn(collection).when(template).getCollection(PlayerDocument.COLLECTION);
        assertThat(new MongoPlayerStore(template).supportsIndependentPlayerLocks(List.of("P1","P2"))).isFalse();
    }
}
