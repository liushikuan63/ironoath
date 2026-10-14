package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.mongo.MongoPlayerStore;
import com.ironoath.web.store.mongo.PlayerDocument;

class BotCalibrationStorageEquivalenceTest {
    private static final long NOW=1_760_000_000_000L;
    private static TestMongo db;
    @BeforeAll static void connect() {
        db=TestMongo.tryOpen();
        assertThat(db).as("本次真实Mongo校准兼容验收不能跳过").isNotNull();
    }
    @AfterAll static void close() { if(db!=null)db.close(); }
    private PlayerRepository store(String kind) {
        return kind.equals("mongo")?new MongoPlayerStore(db.template()):new InMemoryPlayerStore();
    }
    private String seed(PlayerRepository store) {
        String id="P-calibration-"+UUID.randomUUID();
        assertThat(store.insertIfAbsent(PlayerStoreContractTest.newSave(id,"dev-"+id))).isTrue();
        return id;
    }
    @ParameterizedTest @ValueSource(strings={"memory","mongo"})
    void powerLoginAndCalibrationMarkerSurviveSnapshotsAndUnrelatedWrites(String kind) {
        PlayerRepository store=store(kind);String id=seed(store);
        var save=store.findByPlayerId(id).orElseThrow();save.setPower(new PlayerPower(500,500,500));
        save.touchLogin(NOW+1000);save.recordBotCalibration(NOW+1000);store.save(save);
        var reread=store.findByPlayerId(id).orElseThrow();
        assertThat(reread.lastBotCalibrationAt()).isEqualTo(NOW+1000);
        assertThat(reread.lastLoginAt()).isEqualTo(NOW+1000);assertThat(reread.power().matchPower()).isEqualTo(500);
        assertThat(reread.copy().lastBotCalibrationAt()).isEqualTo(NOW+1000);
        reread.setCityLevel(2);store.save(reread);
        assertThat(store.findByPlayerId(id).orElseThrow().lastBotCalibrationAt()).isEqualTo(NOW+1000);
        if(kind.equals("mongo")) assertThat(new MongoPlayerStore(db.template()).findByPlayerId(id).orElseThrow().lastBotCalibrationAt()).isEqualTo(NOW+1000);
    }
    @ParameterizedTest @ValueSource(strings={"memory","mongo"})
    void staleSaveCannotSplitTheAcceptedCalibrationAndPower(String kind) {
        PlayerRepository store=store(kind);String id=seed(store);
        var first=store.findByPlayerId(id).orElseThrow();var stale=store.findByPlayerId(id).orElseThrow();
        first.setPower(new PlayerPower(500,500,500));first.recordBotCalibration(NOW+1000);store.save(first);
        stale.setPower(new PlayerPower(900,900,900));stale.recordBotCalibration(NOW+2000);
        assertThatThrownBy(()->store.save(stale)).isInstanceOf(RuntimeException.class);
        var reread=store.findByPlayerId(id).orElseThrow();
        assertThat(reread.lastBotCalibrationAt()).isEqualTo(NOW+1000);assertThat(reread.power().matchPower()).isEqualTo(500);
    }
    @ParameterizedTest @ValueSource(strings={"memory","mongo"})
    void oldMissingMarkerMeansNeverCalibratedAndCanBeAdded(String kind) {
        PlayerRepository store=store(kind);String id=seed(store);
        if(kind.equals("mongo")) db.template().getCollection(PlayerDocument.COLLECTION)
                .updateOne(new org.bson.Document("_id",id),new org.bson.Document("$unset",new org.bson.Document("lastBotCalibrationAt","")));
        var old=store.findByPlayerId(id).orElseThrow();assertThat(old.lastBotCalibrationAt()).isNull();
        old.recordBotCalibration(NOW+1000);store.save(old);
        assertThat(store.findByPlayerId(id).orElseThrow().lastBotCalibrationAt()).isEqualTo(NOW+1000);
    }
    @Test void mongoStoresCalibrationAsAnInternalNumericField() {
        PlayerRepository store=store("mongo");String id=seed(store);var save=store.findByPlayerId(id).orElseThrow();
        save.recordBotCalibration(NOW+1000);store.save(save);
        var raw=db.template().getCollection(PlayerDocument.COLLECTION).find(new org.bson.Document("_id",id)).first();
        assertThat(raw).isNotNull();assertThat(raw.getLong("lastBotCalibrationAt")).isEqualTo(NOW+1000);
    }
}
