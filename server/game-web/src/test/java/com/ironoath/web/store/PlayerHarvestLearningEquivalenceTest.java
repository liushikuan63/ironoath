package com.ironoath.web.store;

import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.mongo.MongoPlayerStore;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** 实际Mongo更新和换仓储实例重读；缺Mongo是失败，不能把跳过算通过。 */
class PlayerHarvestLearningEquivalenceTest {
    private PlayerSave player(String id) {
        return PlayerSave.createNew(id, "dev-" + id, "采集持久化", 1, 1_790_000_000_000L, 1,
                Map.of("WOOD", new PlayerResourceState(5000, 20000, 0, 200, 1_790_000_000_000L),
                        "STONE", new PlayerResourceState(5000, 20000, 0, 200, 1_790_000_000_000L)),
                PlayerPower.zero(), null);
    }

    @Test void mongoUpdateAndNewStoreKeepManualProgressAndPendingOutput() {
        try (TestMongo db = TestMongo.tryOpen()) {
            assertThat(db).as("本机Mongo必须可连接，不能跳过持久化验收").isNotNull();
            var store = new MongoPlayerStore(db.template());
            var save = player("P-harvest-restart"); store.insertIfAbsent(save);
            save = store.findByPlayerId(save.playerId()).orElseThrow();
            save.recordProducedOutput(Map.of("WOOD", 12L, "STONE", 7L));
            assertThat(save.confirmManualOutput("WOOD")).isTrue(); store.save(save);
            var reread = new MongoPlayerStore(db.template()).findByPlayerId(save.playerId()).orElseThrow();
            assertThat(reread.manuallyCollected()).containsExactly("WOOD");
            assertThat(reread.pendingManualOutput()).containsExactlyEntriesOf(Map.of("STONE", 7L));
            assertThat(reread.confirmManualOutput("WOOD")).isFalse();
        }
    }

    @Test void inMemorySnapshotDoesNotShareMutableLearningState() {
        var store = new InMemoryPlayerStore(); var save = player("P-harvest-copy");
        save.recordProducedOutput(Map.of("WOOD", 8L)); store.insertIfAbsent(save);
        var loaded = store.findByPlayerId(save.playerId()).orElseThrow();
        loaded.confirmManualOutput("WOOD");
        assertThat(store.findByPlayerId(save.playerId()).orElseThrow().manuallyCollected()).isEmpty();
        store.save(loaded);
        assertThat(store.findByPlayerId(save.playerId()).orElseThrow().manuallyCollected()).containsExactly("WOOD");
    }
}
