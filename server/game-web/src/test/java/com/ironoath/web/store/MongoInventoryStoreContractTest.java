package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.web.store.mongo.MongoInventoryStore;

/**
 * 职责：背包的 Mongo 实现跑同一份版本化契约，外加一条"堆叠数与容量都落得住"的往返断言。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）。
 */
class MongoInventoryStoreContractTest extends VersionedStoreContractTest<Inventory> {

    private static final String ITEM = "item_speedup_build_1h";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static TestMongo db;

    private InventoryRepository store;
    private String playerId = "P-bag";

    @BeforeAll
    static void connect() {
        db = TestMongo.tryOpen();
    }

    @AfterAll
    static void release() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    @Override protected String storeName() {
        return "MongoInventoryStore";
    }

    @Override protected void freshStore() {
        requireMongo();
        store = new MongoInventoryStore(db.template());
        playerId = "P-bag-" + SEQ.incrementAndGet();
    }

    @Override protected boolean insertInitialState() {
        return store.insertIfAbsent(playerId, Inventory.empty(60));
    }

    @Override protected StoreHandle<Inventory> read() {
        Inventory inventory = store.findByPlayerId(playerId).orElseThrow(
                () -> new AssertionError("契约前提被破坏：插入之后读不到背包"));
        return new StoreHandle<>(inventory, store.versionOf(playerId));
    }

    @Override protected long observe(Inventory inventory) {
        return inventory.countOf(ITEM);
    }

    @Override protected void bump(Inventory inventory) {
        inventory.add(ITEM, 1L, 99L);
    }

    @Override protected void persist(StoreHandle<Inventory> handle) {
        store.save(playerId, handle.state(), handle.readVersion());
    }

    @Override protected long storedVersion() {
        return store.versionOf(playerId);
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 见「跳过即未验证」那条");
    }

    /** 契约的可观察量只有一个数，所以补一条整档往返：多种道具各自的数量、以及容量都要原样回来。 */
    @Test
    @DisplayName("落库往返不丢内容：每种道具的堆叠数与扩容后的容量都要原样回来")
    void countsAndCapacitySurviveTheRoundTrip() {
        requireMongo();
        freshStore();
        insertInitialState();

        StoreHandle<Inventory> handle = read();
        handle.state().add(ITEM, 7L, 99L);
        handle.state().add("item_res_wood_10k", 3L, 99L);
        handle.state().setCapacityMax(80);
        persist(handle);

        Inventory back = read().state();
        assertThat(back.countOf(ITEM)).as("第一种道具的堆叠数").isEqualTo(7L);
        assertThat(back.countOf("item_res_wood_10k")).as("第二种道具不能因为只存了一份映射而丢").isEqualTo(3L);
        assertThat(back.capacityMax()).as("容量是随扩容增长的存档状态，不能从配置反推").isEqualTo(80);
        assertThat(back.countOf("item_not_there")).as("没见过的道具必须是 0 而不是抛错").isZero();
    }

    @Test
    @DisplayName("Mongo 必须真的可达：否则「语义等价」这条承诺今天没有被验证过")
    void mongoMustBeReachableOrTheClaimIsUnverified() {
        Assumptions.assumeTrue(db != null,
                "跳过即未验证：内存与 Mongo 的语义等价没有被检查。"
                        + "补跑方式：起一个本地 MongoDB，或 -Dironoath.test.mongo.uri=... 指向一台");
    }
}
