package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ironoath.common.rng.Rng;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.bot.BotCalibrationBatch;
import com.ironoath.web.bot.BotCalibrationService;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.bot.BotRulesAssembler;
import com.ironoath.web.bot.BotSpawnService;
import com.ironoath.web.lock.InJvmPlayerLock;
import com.ironoath.web.store.mongo.MongoBotProfileStore;
import com.ironoath.web.store.mongo.MongoPlayerStore;
import com.ironoath.web.store.mongo.PlayerDocument;

/** Real BSON aliases may be different lock strings but address the same player document. */
class BotCalibrationAliasSafetyTest {
    private static final long NOW=1_760_000_000_000L;
    private TestMongo db;
    private MongoPlayerStore players;
    private BotCalibrationService service;
    private String lower,upper;

    @BeforeEach void seedOwnedLegacyRows() {
        db=TestMongo.tryOpen();assertThat(db).as("真实Mongo别名验收不可跳过").isNotNull();
        players=spy(new MongoPlayerStore(db.template()));
        lower="a"+new ObjectId().toHexString().substring(1);upper=lower.toUpperCase(Locale.ROOT);
        assertThat(players.insertIfAbsent(PlayerStoreContractTest.newSave(lower,"alias-"+lower))).isTrue();
        assertThat(db.template().getCollection(PlayerDocument.COLLECTION).find(new Document("_id",new ObjectId(lower))).first()).isNotNull();
        var assembler=new BotRulesAssembler(ConfigRegistry.loadFromDirectory(Path.of("contract/config")));
        var profiles=new MongoBotProfileStore(db.template());
        BotProfile canonical=assembler.archetypes().get(0).spawnProfile(lower,7,Rng.of(1));
        profiles.put(canonical);
        BotProfile alias=assembler.archetypes().get(0).spawnProfile(upper,7,Rng.of(1));
        Document rawProfile=new Document();db.template().getConverter().write(alias,rawProfile);
        db.template().getCollection(MongoBotProfileStore.COLLECTION).insertOne(new Document("_id",upper).append("profile",rawProfile));
        var registry=new BotRegistry(assembler,profiles);
        assertThat(registry.botIds()).containsExactlyInAnyOrder(lower,upper);
        assertThat(players.findByPlayerId(upper).orElseThrow().playerId()).isEqualTo(players.findByPlayerId(lower).orElseThrow().playerId());
        var spawner=mock(BotSpawnService.class);when(spawner.humanAverageMatchPower()).thenReturn(10_000L);
        service=new BotCalibrationService(registry,assembler,players,spawner,new InJvmPlayerLock(),new BotCalibrationBatch(2));
    }

    @AfterEach void close() { if(db!=null)db.close(); }

    private AtomicInteger alignInitialVersionReads() {
        CountDownLatch read=new CountDownLatch(2);AtomicInteger saves=new AtomicInteger();
        doAnswer(call->{
            Optional<PlayerSave> result=(Optional<PlayerSave>)call.callRealMethod();
            if(result.isPresent() && result.get().lastBotCalibrationAt()==null) {
                read.countDown();read.await(1,TimeUnit.SECONDS); // serial control remains valid; parallel aligns stale versions
            }
            return result;
        }).when(players).findByPlayerId(anyString());
        doAnswer(call->{saves.incrementAndGet();return call.callRealMethod();}).when(players).save(any(PlayerSave.class));
        return saves;
    }

    @Test void legacySerialCalibrationAcceptsTheAliasesOnlyOnce() {
        AtomicInteger saves=alignInitialVersionReads();
        assertThatCode(()->service.calibrateIfNewDay(NOW)).doesNotThrowAnyException();
        assertThat(saves.get()).isEqualTo(1);
        assertThat(players.findByPlayerId(lower).orElseThrow().lastBotCalibrationAt()).isEqualTo(NOW);
    }

    @Test void topLevelCalibrationMustNotIntroduceACasFailureForTheSamePhysicalPlayer() {
        AtomicInteger saves=alignInitialVersionReads();
        assertThatCode(()->service.calibrateInParallelIfNewDay(NOW)).doesNotThrowAnyException();
        assertThat(saves.get()).isEqualTo(1);
        assertThat(players.findByPlayerId(lower).orElseThrow().lastBotCalibrationAt()).isEqualTo(NOW);
    }
}
