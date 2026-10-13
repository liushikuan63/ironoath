package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.web.reward.PlayerBag;
import com.ironoath.web.store.memory.InMemoryInventoryStore;
import com.ironoath.web.store.mongo.MongoInventoryStore;

class ItemClaimStoreEquivalenceTest {
    private static TestMongo db;
    private static final long NOW = 1_791_850_000_000L;
    private static final RewardContext CTX = RewardContext.toMail("paid_items", "material-period", "card:order:0");
    private static final String SPEED="item_speedup_build_1h", WOOD="item_res_wood_10k";
    private static final Map<String,Long> PACK = new LinkedHashMap<>();
    static { PACK.put(SPEED,1L);PACK.put(WOOD,1L); }
    private final ConfigRegistry configs = configs();
    @BeforeAll static void connect() {
        db = TestMongo.tryOpen();
        assertThat(db).as("隔离Mongo必须可用，不以跳过代替通过").isNotNull();
    }
    @AfterAll static void close() { if (db != null) db.close(); }
    private InventoryRepository store(String kind) {
        return "mongo".equals(kind) ? new MongoInventoryStore(db.template(), configs) : new InMemoryInventoryStore();
    }
    private String player(InventoryRepository store, int capacity) {
        String id = "P-items-" + UUID.randomUUID();
        assertThat(store.insertIfAbsent(id, Inventory.empty(capacity))).isTrue();
        return id;
    }

    @ParameterizedTest @ValueSource(strings = {"memory", "mongo"})
    void freshAdaptersAndUnrelatedSaveKeepBothItemsAndSnapshot(String kind) {
        var store = store(kind); String id = player(store, 60);
        var first = new PlayerBag(store, configs).grantItemsOnce(id, PACK, CTX, NOW);
        var unrelated = store.findByPlayerId(id).orElseThrow();
        unrelated.setCapacityMax(70); store.save(id, unrelated, store.versionOf(id));
        var replay = new PlayerBag(store, configs).grantItemsOnce(id, Map.of(SPEED,10L,WOOD,10L), CTX, NOW + 1);
        assertThat(first.creditedNow()).isEqualTo(PACK);
        assertThat(replay.creditedNow()).isEmpty();
        assertThat(replay.receipt().requested()).isEqualTo(PACK);
        assertThat(store.findByPlayerId(id).orElseThrow().countOf(SPEED)).isEqualTo(1);
        assertThat(store.findByPlayerId(id).orElseThrow().countOf(WOOD)).isEqualTo(1);
        assertThat(store.findByPlayerId(id).orElseThrow().itemClaims()).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings = {"memory", "mongo"})
    void committedReplyLossLeavesBothItemsAndReceiptForRetry(String kind) {
        InventoryRepository store = spy(store(kind)); String id = player(store, 60);
        var once = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (once.getAndSet(false)) throw new IllegalStateException("fixture: committed reply lost");
            return result;
        }).when(store).save(anyString(), any(Inventory.class), anyLong());
        assertThatThrownBy(() -> new PlayerBag(store, configs).grantItemsOnce(id, PACK, CTX, NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(new PlayerBag(store, configs).grantItemsOnce(id, PACK, CTX, NOW).creditedNow()).isEmpty();
        assertThat(store.findByPlayerId(id).orElseThrow().countOf(SPEED)).isEqualTo(1);
        assertThat(store.findByPlayerId(id).orElseThrow().countOf(WOOD)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"memory", "mongo"})
    void precommitFailureLeavesNoPartOfPackOrReceipt(String kind) {
        InventoryRepository store = spy(store(kind)); String id = player(store, 60);
        doAnswer(call -> { throw new IllegalStateException("fixture: before commit"); })
                .when(store).save(anyString(), any(Inventory.class), anyLong());
        assertThatThrownBy(() -> new PlayerBag(store, configs).grantItemsOnce(id, PACK, CTX, NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(store.findByPlayerId(id).orElseThrow().snapshot()).isEmpty();
        assertThat(store.findByPlayerId(id).orElseThrow().itemClaims()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"memory", "mongo"})
    void capacityOverflowAndCompletedMailSurviveAnotherStoreRead(String kind) {
        var store = store(kind); String id = player(store, 1);
        var bag = new PlayerBag(store, configs);
        var accepted = bag.grantItemsOnce(id, PACK, CTX, NOW);
        assertThat(accepted.receipt().credited()).containsExactlyEntriesOf(Map.of(SPEED,1L));
        assertThat(accepted.receipt().overflow()).containsExactlyEntriesOf(Map.of(WOOD,1L));
        assertThat(bag.pendingItemClaims(id)).hasSize(1);
        bag.completeItemOverflow(id, CTX.traceId(), "mail-stable");
        var replay = new PlayerBag(store, configs).grantItemsOnce(id, PACK, CTX, NOW + 1);
        assertThat(replay.receipt().overflowMailId()).isEqualTo("mail-stable");
        assertThat(bag.pendingItemClaims(id)).isEmpty();
        assertThatThrownBy(() -> bag.completeItemOverflow(id, CTX.traceId(), "mail-other")).isInstanceOf(IllegalStateException.class);
    }

    @Test void oldMongoInventoryWithoutReceiptsPreservesCountsAndReadsEmptyLedger() {
        String id = "P-old-items-" + UUID.randomUUID();
        db.template().getCollection("inventory").insertOne(new Document("_id", id).append("version", 0L)
                .append("capacityMax", 60).append("counts", new Document("item_res_wood_10k", 7L)));
        var old = new MongoInventoryStore(db.template(), configs).findByPlayerId(id).orElseThrow();
        assertThat(old.countOf("item_res_wood_10k")).isEqualTo(7);
        assertThat(old.itemClaims()).isEmpty();
    }

    private static ConfigRegistry configs() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("contract/config");
            if (Files.isDirectory(candidate)) return ConfigRegistry.loadFromDirectory(candidate);
            dir = dir.getParent();
        }
        throw new IllegalStateException("contract/config not found");
    }
}
