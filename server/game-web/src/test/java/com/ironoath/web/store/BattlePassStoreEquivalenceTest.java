package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.web.battlepass.BattlePassStore;
import com.ironoath.web.dto.generated.BattlePassTrack;
import com.ironoath.web.store.memory.InMemoryBattlePassStore;
import com.ironoath.web.store.mongo.MongoBattlePassStore;

/**
 * 职责：战令进度在<b>内存与 Mongo 上必须给出同一个结果</b>（B24 块②）。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）；连不上时明确报"跳过即未验证"。
 *
 * <p><b>三条最值得钉的</b>：① 每个字段都要往返（{@code points} / {@code paidUnlocked} / {@code claimed}
 * —— Mongo 的 {@code $set} 白名单漏一个字段时，内存版全绿而生产上每次重读都会丢掉它）；
 * ② 并发的加积分不能丢分（乐观锁重试那条路）；③ 换一个实例（= 重启一次）之后
 * <b>内存版会把已领过的档位重新变成可领</b>，Mongo 版不会 —— 这是"生产必须 mongo"的可核对证据。
 */
class BattlePassStoreEquivalenceTest {

    private static final String SEASON = "season_01";
    private static final String OTHER = "season_02";
    private static TestMongo db;

    @BeforeAll
    static void connect() {
        db = TestMongo.tryOpen();
    }

    @BeforeEach
    void clear() {
        if (db != null) {
            new MongoBattlePassStore(db.template()).clear();
        }
    }

    @AfterAll
    static void release() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    private List<BattlePassStore> bothStores() {
        List<BattlePassStore> stores = new ArrayList<>();
        stores.add(new InMemoryBattlePassStore());
        if (db != null) {
            stores.add(new MongoBattlePassStore(db.template()));
        }
        return stores;
    }

    @Test
    @DisplayName("没有记录时读出来是空进度（不是 null、也不是抛）：没打过战令是正常状态")
    void missingRecordReadsAsEmptyProgress() {
        for (BattlePassStore store : bothStores()) {
            BattlePassStore.Progress progress = store.load(SEASON, "P-none");
            assertThat(progress.points()).as("%s 没记录就是 0 分", store.getClass().getSimpleName()).isZero();
            assertThat(progress.paidUnlocked()).isFalse();
            assertThat(progress.claimed()).isEmpty();
        }
    }

    @Test
    @DisplayName("三个字段都要往返：积分 / 付费解锁位 / 已领档位（$set 白名单漏一个就会在这里红）")
    void everyFieldSurvivesARoundTrip() {
        for (BattlePassStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.update(SEASON, "P-1", cur -> cur.withPoints(300L));
            store.update(SEASON, "P-1", BattlePassStore.Progress::withPaidUnlocked);
            store.update(SEASON, "P-1", cur -> cur.withClaimed(1, BattlePassTrack.FREE));
            store.update(SEASON, "P-1", cur -> cur.withClaimed(2, BattlePassTrack.PAID));

            // 重新读一次（Mongo 侧走的是真库，内存侧走的是同一个 map）
            BattlePassStore.Progress readBack = store.load(SEASON, "P-1");
            assertThat(readBack.points()).as("%s 积分", label).isEqualTo(300L);
            assertThat(readBack.paidUnlocked()).as("%s 付费解锁位", label).isTrue();
            assertThat(readBack.claimed(1, BattlePassTrack.FREE)).as("%s 免费线第 1 档", label).isTrue();
            assertThat(readBack.claimed(1, BattlePassTrack.PAID)).as("%s 付费线没领过", label).isFalse();
            assertThat(readBack.claimed(2, BattlePassTrack.PAID)).as("%s 付费线第 2 档", label).isTrue();
        }
    }

    @Test
    @DisplayName("赛季之间互不影响：S1 的积分与领取不会漏进 S2")
    void seasonsDoNotLeakIntoEachOther() {
        for (BattlePassStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.update(SEASON, "P-1", cur -> cur.withPoints(450L).withClaimed(1, BattlePassTrack.FREE));
            BattlePassStore.Progress other = store.load(OTHER, "P-1");
            assertThat(other.points()).as("%s 新赛季从 0 分开始", label).isZero();
            assertThat(other.claimed(1, BattlePassTrack.FREE)).as("%s 新赛季第 1 档可以重新领", label).isFalse();
        }
    }

    @Test
    @DisplayName("并发的加积分一分都不许丢（乐观锁重试那条路）")
    void concurrentPointAdditionsDoNotLoseAny() throws Exception {
        for (BattlePassStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            int threads = 8;
            int perThread = 10;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                jobs.add(() -> {
                    for (int j = 0; j < perThread; j++) {
                        store.update(SEASON, "P-concurrent", cur -> cur.withPoints(15L));
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = pool.invokeAll(jobs);
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();

            assertThat(store.load(SEASON, "P-concurrent").points())
                    .as("%s %d 线程各加 %d 次 15 分，总数必须是 %d", label, threads, perThread,
                            (long) threads * perThread * 15L)
                    .isEqualTo((long) threads * perThread * 15L);
        }
    }

    /**
     * 断言的是两套实现在"重启"上的真实差别：内存版换实例就把"哪些档领过"忘了，
     * 于是那些档重新变成可领 —— 那一发就是重复发奖。与赛季账本那条同源。
     */
    @Test
    @DisplayName("换一个实例（= 重启一次）：内存版把已领的档位忘掉，Mongo 版记着")
    void aFreshInstanceAfterRestartForgetsClaimsOnlyInMemory() {
        InMemoryBattlePassStore memory = new InMemoryBattlePassStore();
        memory.update(SEASON, "P-restart", cur -> cur.withPoints(150L).withClaimed(1, BattlePassTrack.FREE));
        assertThat(new InMemoryBattlePassStore().load(SEASON, "P-restart").claimed(1, BattlePassTrack.FREE))
                .as("内存版换实例 = 重启：已领标记没了，第 1 档会重新变成可领（所以生产必须 mongo）")
                .isFalse();

        if (db == null) {
            return;
        }
        MongoBattlePassStore mongo = new MongoBattlePassStore(db.template());
        mongo.update(SEASON, "P-restart", cur -> cur.withPoints(150L).withClaimed(1, BattlePassTrack.FREE));
        assertThat(new MongoBattlePassStore(db.template()).load(SEASON, "P-restart")
                .claimed(1, BattlePassTrack.FREE))
                .as("Mongo 版换实例仍然记着（重启不会让奖励重发）")
                .isTrue();
    }

    @Test
    @DisplayName("空键当场报错，不静默写一条没有赛季/玩家的记录")
    void blankKeysAreRefused() {
        for (BattlePassStore store : bothStores()) {
            assertThatThrownBy(() -> store.update("", "P-1", cur -> cur.withPoints(10L)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.update(SEASON, null, cur -> cur.withPoints(10L)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
