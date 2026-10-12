package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.ironoath.core.event.GameEvent;
import com.ironoath.core.quest.GoalType;
import com.ironoath.core.quest.QuestProgress;
import com.ironoath.web.quest.QuestProgressStore;
import com.ironoath.web.store.memory.InMemoryQuestProgressStore;
import com.ironoath.web.store.mongo.MongoQuestProgressStore;
import com.ironoath.web.store.mongo.QuestProgressDocument;

/** 持久事实集合与旧记录兼容在真实隔离Mongo和内存验证，不连接原生验收数据库。 */
class QuestPermanentStoreEquivalenceTest {
    private static TestMongo db;
    @BeforeAll static void connect() { db = TestMongo.tryOpen(); assertThat(db).isNotNull(); }
    @AfterAll static void close() { if (db != null) db.close(); }
    private QuestProgressStore store(String kind) {
        return "mongo".equals(kind) ? new MongoQuestProgressStore(db.template()) : new InMemoryQuestProgressStore();
    }
    @ParameterizedTest @ValueSource(strings = {"memory", "mongo"})
    void reloadedFactsPreventReplaysAcrossDayAndWeek(String kind) {
        String id = "P-quest-facts-" + UUID.randomUUID();
        var progress = QuestProgress.open(id, List.of(new QuestProgress.Def("permanent", QuestProgress.QuestType.ACHIEVEMENT,
                GoalType.UPGRADE_BUILDING, null, 10, null, true)), "20261013", "2026W42");
        var fact = GameEvent.progressOnce(id, GoalType.UPGRADE_BUILDING, "main_city", 1, 1_800_000_000_000L, "build:instance:2");
        progress.onEvent(fact);
        var store = store(kind);
        store.save(id, new QuestProgressStore.State(id, progress.entries(), progress.dayKey(), progress.weekKey()));
        var snapshot = store.load(id).orElseThrow();
        var reloaded = QuestProgress.restore(id, snapshot.entries(), snapshot.dayKey(), snapshot.weekKey());
        reloaded.rollover("20261101", "2026W44");
        reloaded.onEvent(fact);
        assertThat(reloaded.entry("permanent").current()).isEqualTo(1);
        assertThat(reloaded.entry("permanent").factIds()).containsExactly("build:instance:2");
    }
    @Test void missingFactFieldsInAnOldMongoDocumentDefaultToLegacyBehavior() {
        String id = "P-old-quest-" + UUID.randomUUID();
        var entry = new Document("questId", "legacy").append("type", "MAIN").append("goalType", "UPGRADE_BUILDING")
                .append("goalTarget", "main_city").append("goalValue", 2L).append("preQuestId", null)
                .append("current", 1L).append("claimed", false);
        db.template().getCollection(QuestProgressDocument.COLLECTION).insertOne(new Document("_id", id)
                .append("entries", List.of(entry)).append("dayKey", "20261013").append("weekKey", "2026W42"));
        var snapshot = new MongoQuestProgressStore(db.template()).load(id).orElseThrow();
        var progress = QuestProgress.restore(id, snapshot.entries(), snapshot.dayKey(), snapshot.weekKey());
        assertThat(progress.entry("legacy").requiresFact()).isFalse();
        assertThat(progress.entry("legacy").factIds()).isEmpty();
        progress.onEvent(GameEvent.progress(id, GoalType.UPGRADE_BUILDING, "main_city", 1, 1_800_000_000_000L));
        assertThat(progress.entry("legacy").complete()).isTrue();
    }
}
