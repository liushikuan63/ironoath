package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.city.BuildingInstance;
import com.ironoath.core.city.BuildingStatus;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.web.store.mongo.MongoCityStore;

/**
 * 职责：城建仓储的 Mongo 实现跑同一份契约，外加一条<b>逐字段</b>的落库往返断言。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）。
 *
 * <p><b>为什么契约四条不够，还要那条逐字段断言</b>：契约对城能看到的可观察量只有"建筑数"，
 * 而这次新增的持久化风险恰恰是<b>字段级</b>的 —— 少存一个 {@code lastMovedAt} 或把
 * {@code status} 落成 {@code IDLE}，建筑数照样对，四条契约全绿，
 * 但玩家重启后"正在升级的队列"变成"空闲"、迁城冷却凭空清零。
 * 那正是"不报错、只有玩家发现少了东西"的一类。
 */
class MongoCityStoreContractTest extends VersionedStoreContractTest<CityState> {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static TestMongo db;

    private CityRepository store;
    private String playerId = "P-city";

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
        return "MongoCityStore";
    }

    @Override protected void freshStore() {
        requireMongo();
        store = new MongoCityStore(db.template());
        playerId = "P-city-" + SEQ.incrementAndGet();
    }

    @Override protected boolean insertInitialState() {
        return store.insertIfAbsent(playerId, new CityState());
    }

    @Override protected StoreHandle<CityState> read() {
        CityState state = store.findByPlayerId(playerId).orElseThrow(
                () -> new AssertionError("契约前提被破坏：插入之后读不到城建存档"));
        return new StoreHandle<>(state, store.versionOf(playerId));
    }

    @Override protected long observe(CityState state) {
        return state.buildings().size();
    }

    @Override protected void bump(CityState state) {
        state.restoreBuilding(new BuildingInstance("b" + state.buildings().size(),
                "farm", 1, 4 + state.buildings().size(), 4));
    }

    @Override protected void persist(StoreHandle<CityState> handle) {
        store.save(playerId, handle.state(), handle.readVersion());
    }

    @Override protected long storedVersion() {
        return store.versionOf(playerId);
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 见「跳过即未验证」那条");
    }

    @Test
    @DisplayName("落库往返不丢字段：升级中状态、时间戳、迁城时刻、额外队列都要原样回来")
    void everyFieldSurvivesTheRoundTrip() {
        requireMongo();
        freshStore();
        insertInitialState();

        // 必须持有同一个 handle：read() 每次给的都是新副本，
        // 写成 persist(read()) 会落一份"刚读出来的、没被我改过"的状态，改动全丢且不报错
        StoreHandle<CityState> handle = read();
        CityState writing = handle.state();
        BuildingInstance upgrading = new BuildingInstance("b-up", "house", 1, 11, 12);
        upgrading.restore(4, 11, 12, BuildingStatus.UPGRADING, 9_000L, 8_000L,
                1_200L, 1_500L, 3, 7_777L, 6_666L);
        writing.restoreBuilding(upgrading);
        writing.restoreBuilding(new BuildingInstance("b-idle", "farm", 2, 13, 14));
        writing.addExtraQueue(2);
        persist(handle);

        CityState back = read().state();
        BuildingInstance restored = back.building("b-up");
        assertThat(restored).as("b-up 没回来，后面的字段断言无从谈起").isNotNull();
        assertThat(restored.configId()).isEqualTo("house");
        assertThat(restored.level()).as("等级（不是构造时的 1，而是 restore 后的 4）").isEqualTo(4);
        assertThat(restored.gridX()).isEqualTo(11);
        assertThat(restored.gridY()).isEqualTo(12);
        assertThat(restored.status()).as("升级中不能被落成 IDLE").isEqualTo(BuildingStatus.UPGRADING);
        assertThat(restored.upgradeFinishAt()).isEqualTo(9_000L);
        assertThat(restored.upgradeStartedAt()).isEqualTo(8_000L);
        assertThat(restored.upgradeTotalSeconds()).isEqualTo(1_200L);
        assertThat(restored.upgradeOriginalSeconds()).isEqualTo(1_500L);
        assertThat(restored.helpCount()).as("联盟帮助次数").isEqualTo(3);
        assertThat(restored.lastMovedAt()).as("迁城时刻（丢了就等于冷却清零）").isEqualTo(7_777L);
        assertThat(restored.lastFinishedAt()).isEqualTo(6_666L);

        assertThat(back.building("b-idle").level()).as("另一座建筑的等级").isEqualTo(2);
        assertThat(back.extraQueues()).as("付费额外队列").isEqualTo(2);
        assertThat(back.isGridFree(11, 12))
                .as("网格占用是派生索引，必须由 restoreBuilding 按坐标重建").isFalse();
        assertThat(back.isGridFree(11, 13))
                .as("没被占的格子不许因为重建而被连带占掉").isTrue();
    }

    @Test
    @DisplayName("Mongo 必须真的可达：否则「语义等价」这条承诺今天没有被验证过")
    void mongoMustBeReachableOrTheClaimIsUnverified() {
        Assumptions.assumeTrue(db != null,
                "跳过即未验证：内存与 Mongo 的语义等价没有被检查。"
                        + "补跑方式：起一个本地 MongoDB，或 -Dironoath.test.mongo.uri=... 指向一台");
    }
}
