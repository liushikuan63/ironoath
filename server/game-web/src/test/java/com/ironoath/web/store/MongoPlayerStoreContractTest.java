package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.store.mongo.MongoPlayerStore;

/**
 * 职责：把同一份版本化契约跑在 <b>Mongo 实现</b>上 —— 「内存与 Mongo 语义等价」这句话
 * 第一次被机械检查，而不是写在 {@code DEVELOPMENT.md} §四 里当承诺。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）。
 *
 * <p><b>连不上时报「跳过」而不是「失败」，但跳过本身被做成一条看得见的断言</b>：
 * CI 机器上通常没有 Mongo，硬失败会让人在流水线上直接给整类加白名单；
 * 而"全绿"里混着四条被悄悄跳过的等价性检查，比红更糟。
 */
class MongoPlayerStoreContractTest extends VersionedStoreContractTest<PlayerSave> {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static TestMongo db;

    private PlayerRepository store;
    private String playerId = "P-mongo";

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
        return "MongoPlayerStore";
    }

    @Override protected void freshStore() {
        requireMongo();
        store = new MongoPlayerStore(db.template());
        playerId = "P-mongo-" + SEQ.incrementAndGet();
    }

    @Override protected boolean insertInitialState() {
        return store.insertIfAbsent(
                PlayerStoreContractTest.newSave(playerId, "dev-" + playerId));
    }

    @Override protected StoreHandle<PlayerSave> read() {
        PlayerSave save = store.findByPlayerId(playerId).orElseThrow(
                () -> new AssertionError("契约前提被破坏：插入之后读不到存档"));
        // 玩家仓储的版本随存档本身走（没有独立的 versionOf），所以读的那一刻就是它
        return new StoreHandle<>(save, save.version());
    }

    @Override protected long observe(PlayerSave state) {
        return state.cityLevel();
    }

    @Override protected void bump(PlayerSave state) {
        state.setCityLevel(state.cityLevel() + 1);
    }

    @Override protected void persist(StoreHandle<PlayerSave> handle) {
        store.save(handle.state());
    }

    @Override protected long storedVersion() {
        return read().readVersion();
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 见下面那条「跳过即未验证」用例");
    }

    /**
     * 这条不测仓储，只测「上面四条到底跑没跑」。
     *
     * <p>没有它的话，一次"全绿"里可能藏着四条被跳过的等价性检查 —— 而本项目最怕的就是
     * "看起来过了"（同类的失真已经在这份清单里记过两次）。
     */
    @Test
    @DisplayName("Mongo 必须真的可达：否则「语义等价」这条承诺今天没有被验证过")
    void mongoMustBeReachableOrTheClaimIsUnverified() {
        Assumptions.assumeTrue(db != null,
                "跳过即未验证：内存与 Mongo 的语义等价没有被检查。"
                        + "补跑方式：起一个本地 MongoDB，或 -Dironoath.test.mongo.uri=... 指向一台");
    }

    /**
     * 礼包弹窗那一位（弹出记账 + **当日限购账本**）必须整份落库。
     *
     * <p>抓的是「`save` 是 $set 白名单、而内存实现直接存对象」这个形状：
     * 漏一行不会让任何一条用例变红（内存版照样绿），只有真 Mongo 上会每次重读都归零 ——
     * 症状是「内存 dev 里每日限购买一次就灰、生产里同一天可以一直买」。
     * 同一族的机械检查是 {@code scripts/check-mongo-set-coverage.sh}。
     */
    @Test
    @DisplayName("礼包弹窗与限购账本整份落库：弹出时刻、触发时刻、当日已购次数一个都不能丢")
    void giftPopupLedgerSurvivesTheRoundTrip() {
        requireMongo();
        freshStore();
        insertInitialState();

        PlayerSave save = read().state();
        save.setGiftPopup(save.giftPopup()
                .withShown("gift_first_charge", 1_700_000_000_000L)
                .withTriggered("FIRST_CHARGE", 1_700_000_000_500L)
                .withPurchased("gift_first_charge", "2026-09-19")
                .withPurchased("gift_first_charge", "2026-09-19"));
        persist(new StoreHandle<>(save, save.version()));

        var back = read().state().giftPopup();
        assertThat(back.lastShowAt()).as("最近一次弹出时刻").isEqualTo(1_700_000_000_000L);
        assertThat(back.showsByGift().get("gift_first_charge"))
                .as("每个礼包各自的弹出时刻").containsExactly(1_700_000_000_000L);
        assertThat(back.triggeredAt().get("FIRST_CHARGE")).isEqualTo(1_700_000_000_500L);
        assertThat(back.purchaseDayKey())
                .as("限购账本所属的自然日：丢了它，「今天买过几次」就永远读成 0").isEqualTo("2026-09-19");
        assertThat(back.purchasedTodayOf("gift_first_charge", "2026-09-19"))
                .as("当日已购次数").isEqualTo(2L);
    }
}
