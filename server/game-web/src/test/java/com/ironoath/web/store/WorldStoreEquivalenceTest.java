package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.scout.ScoutReport;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.FogOfWar;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemoryWorldStore;
import com.ironoath.web.store.mongo.MongoWorldStore;
import com.ironoath.web.store.mongo.ScoutReportDocument;
import com.ironoath.web.store.mongo.WorldCityDocument;

/**
 * 职责：世界状态在<b>内存与 Mongo 上必须给出同一个结果</b>（收口清单 #16 的世界档）。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）；连不上时明确报"跳过即未验证"。
 *
 * <p><b>为什么这一档必须逐条比对而不是各写一份契约</b>：世界的两份索引（玩家↔格）与
 * 三个键（坐标键、chunk 键、版本键）只要两侧算得不一样，症状就不是报错而是
 * <b>"我的城不见了"、"采空的资源点复活了"、"地图上一块永远不刷新"</b> ——
 * 这三件事都发生在玩家眼里，服务端一行日志都没有。
 *
 * <p>{@code chunkSize} 两侧都从 {@code WORLD_CHUNK_SIZE} 来；这里用 32（与表里同一条数量级）
 * 并且刻意造一对 {@code x=2} 与 {@code x=10} 的格子 —— 字典序会把 10 排在 2 前面，
 * 数值序不会，这正是"两侧顺序必须逐位一致"能被测出来的地方。
 */
class WorldStoreEquivalenceTest {

    private static final int CHUNK = 32;
    private static final long T0 = 1_800_000_000_000L;
    private static TestMongo db;

    @BeforeAll
    static void connect() {
        db = TestMongo.tryOpen();
    }

    @BeforeEach
    void clearWorld() {
        if (db != null) {
            newMongoStore().clear();
        }
    }

    @AfterAll
    static void release() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    // ---------- 落位与迁城 ----------

    @Test
    @DisplayName("一格只能有一家：第二个人落同一格返回 false，格子仍属于第一个")
    void twoPlayersCannotOccupyOneCell() {
        for (WorldRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            Coord cell = Coord.of(64, 96);
            assertThat(store.placeCity("P-a", cell)).as("%s 先到的应当成功", label).isTrue();
            assertThat(store.placeCity("P-b", cell)).as("%s 后到的必须失败", label).isFalse();
            assertThat(store.cityAt(cell)).as("%s 归属仍是先到的人", label).contains("P-a");
            assertThat(store.cityOf("P-b")).as("%s 失败的那次不许留下半个绑定", label).isEmpty();
            assertThat(store.allCities()).as("%s 全量表里只有一座城", label).hasSize(1);
        }
    }

    @Test
    @DisplayName("已落位的人重复 placeCity 必须响，而不是静默造出一座幽灵城")
    void placeCityOnAnAlreadyPlacedPlayerIsRefused() {
        for (WorldRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            Coord first = Coord.of(32, 32);
            store.placeCity("P-ghost", first);

            assertThatThrownBy(() -> store.placeCity("P-ghost", Coord.of(64, 64)))
                    .as("%s 内存版原先会把新格也绑上而不清旧格：旧格那座从此点不到人", label)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已落位");
            assertThat(store.cityAt(first)).as("%s 原来的绑定不许被动过", label).contains("P-ghost");
            assertThat(store.cityAt(Coord.of(64, 64))).as("%s 更不许凭空多出一格", label).isEmpty();
        }
    }

    @Test
    @DisplayName("迁城必须解绑旧格，并且新旧两个 chunk 的版本都要变新")
    void moveCityReleasesTheOldCellAndBumpsBothChunks() {
        for (WorldRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            Coord from = Coord.of(10, 10);
            Coord to = Coord.of(200, 200);
            store.placeCity("P-move", from);
            long fromChunkBefore = store.chunkVersion(from.chunkKey(CHUNK));

            assertThat(store.moveCity("P-move", to)).as("%s 迁到空格应当成功", label).isTrue();

            assertThat(store.cityOf("P-move")).as("%s 新坐标生效", label).contains(to);
            assertThat(store.cityAt(from)).as("%s 旧格必须解绑（否则就是一座谁也找不到的幽灵城）", label)
                    .isEmpty();
            assertThat(store.cityAt(to)).as("%s 新格归属正确", label).contains("P-move");
            assertThat(store.chunkVersion(from.chunkKey(CHUNK)))
                    .as("%s 旧块的版本必须前进：客户端手里的旧块不然永远不会被重新拉", label)
                    .isGreaterThan(fromChunkBefore);
            assertThat(store.chunkVersion(to.chunkKey(CHUNK)))
                    .as("%s 新块同理", label).isGreaterThan(0L);
        }
    }

    @Test
    @DisplayName("迁到已被占用的格子：返回 false 且原地不动，别人的格子也不动")
    void moveCityOntoAnOccupiedCellKeepsEverythingAsItWas() {
        for (WorldRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            Coord aHome = Coord.of(0, 0);
            Coord bHome = Coord.of(5, 5);
            store.placeCity("P-mover", aHome);
            store.placeCity("P-other", bHome);

            assertThat(store.moveCity("P-mover", bHome)).as("%s 占不到格就必须失败", label).isFalse();
            assertThat(store.cityOf("P-mover")).as("%s 失败的人必须还在原处", label).contains(aHome);
            assertThat(store.cityAt(bHome)).as("%s 别人的格子不许被抢走", label).contains("P-other");
            assertThat(store.cityAt(aHome)).as("%s 原格也不许被解绑成空", label).contains("P-mover");
        }
    }

    @Test
    @DisplayName("原地迁城返回 false 且不推进任何 chunk 版本")
    void moveCityToTheSameCellIsANoOp() {
        for (WorldRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            Coord here = Coord.of(7, 9);
            store.placeCity("P-same", here);
            long version = store.chunkVersion(here.chunkKey(CHUNK));

            assertThat(store.moveCity("P-same", here)).as("%s 原地迁城不是成功", label).isFalse();
            assertThat(store.chunkVersion(here.chunkKey(CHUNK)))
                    .as("%s 既然什么都没变，就不该让全服的这一块都失效一次", label).isEqualTo(version);
        }
    }

    // ---------- 消耗格与键格式 ----------

    @Test
    @DisplayName("消耗格按 chunk 列出且顺序两侧逐位一致（x=2 必须排在 x=10 前面）")
    void consumedCellsAreListedByChunkInTheSameOrderOnBothStores() {
        List<List<String>> listings = new ArrayList<>();
        for (WorldRepository store : bothStores()) {
            store.markConsumed(Coord.of(2, 3), T0);
            store.markConsumed(Coord.of(10, 3), T0 + 1L);
            store.markConsumed(Coord.of(6, 30), T0 + 2L);
            store.markConsumed(Coord.of(500, 500), T0 + 3L);   // 别的 chunk

            assertThat(store.isConsumed(Coord.of(2, 3))).as("已消耗").isTrue();
            assertThat(store.isConsumed(Coord.of(2, 4))).as("没消耗过的格子必须读成 false").isFalse();

            List<String> listed = new ArrayList<>();
            store.consumedInChunk(Coord.of(2, 3).chunkKey(CHUNK), CHUNK)
                    .forEach(c -> listed.add(c.x() + "," + c.y()));
            listings.add(listed);
        }
        assertThat(listings.get(1)).as("两侧顺序必须逐位相同（视图重建全靠这份列表挖格子）")
                .isEqualTo(listings.get(0));
        assertThat(listings.get(0))
                .as("按 x、y 排，不是按存储键的字典序（\"10:3\" 字典序在 \"2:3\" 前面）")
                .containsExactly("2,3", "6,30", "10,3");
    }

    /**
     * 内存版"本来能"按任意尺寸算范围，Mongo 版的 chunkKey 是一列按装配尺寸算出来的派生值。
     * 放任差异的结果是 dev 全绿、生产第一次跨尺寸调用就抛 —— 所以两侧必须同一条拒绝、同一句话。
     */
    @Test
    @DisplayName("用别的 chunk 尺寸问消耗格：两套实现都拒绝，且报错文案一样")
    void mismatchedChunkSizeIsRefusedIdentically() {
        for (WorldRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.markConsumed(Coord.of(4, 4), T0);
            assertThatThrownBy(() -> store.consumedInChunk(Coord.of(4, 4).chunkKey(CHUNK), CHUNK * 2))
                    .as("%s 跨尺寸查询不该在一侧静默成功、另一侧抛", label)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("与存储装配用的 chunkSize=" + CHUNK + " 不一致");
            // 正确尺寸照常可用（否则这条拒绝会变成一条永远为真的空检查）
            assertThat(store.consumedInChunk(Coord.of(4, 4).chunkKey(CHUNK), CHUNK))
                    .as("%s 装配尺寸下必须仍查得到", label)
                    .containsExactly(Coord.of(4, 4));
        }
    }

    /**
     * 带版本写一个还不存在的档：两侧都必须拒（而不是"顺手建一个"），且报错文案一致 ——
     * 一致到能被断言钉住的文案才是契约，否则只是两处巧合。
     */
    @Test
    @DisplayName("没建过档却带版本写迷雾：两侧都拒绝、文案一致、也不留下半个档")
    void versionedFogWriteWithoutARecordIsRefusedOnBothStores() {
        for (WorldRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            FogOfWar fresh = new FogOfWar();
            fresh.restore(new LinkedHashSet<>(List.of("7:7")), 3L);
            assertThatThrownBy(() -> store.saveFog("P-nocreate", fresh, 3L))
                    .as("%s 静默建档等于把别人的写入顺序当成自己的", label)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("迷雾档不存在，无法按版本更新");
            assertThat(store.fogOf("P-nocreate").chunks())
                    .as("%s 被拒的写入不许留下一个只有三块的档", label).isEmpty();
        }
    }

    /** 没落位过的人"迁城"等价于落位 —— 这条分支此前两侧都没测过。 */
    @Test
    @DisplayName("未落位的玩家 moveCity：两侧都按落位处理并占住那一格")
    void moveCityForAnUnplacedPlayerPlacesHim() {
        for (WorldRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            Coord to = Coord.of(300, 320);
            assertThat(store.cityOf("P-unplaced")).as("%s 前置条件：本来没有城", label).isEmpty();
            assertThat(store.moveCity("P-unplaced", to))
                    .as("%s 没有城的人迁过来等价于落位", label).isTrue();
            assertThat(store.cityOf("P-unplaced")).as("%s 新格生效", label).contains(to);
            assertThat(store.cityAt(to)).as("%s 那一格归他", label).contains("P-unplaced");
            assertThat(store.chunkVersion(to.chunkKey(CHUNK)))
                    .as("%s 所在块必须变新，否则别人的客户端不会重拉这块", label).isPositive();
            assertThat(store.moveCity("P-unplaced", to))
                    .as("%s 原地再迁一次仍是 false（两侧同一条）", label).isFalse();
        }
    }

    @Test
    @DisplayName("换一个实例（= 重启）：内存版消耗格会复活，Mongo 版不会")
    void consumedCellsSurviveARestartOnlyOnMongo() {
        InMemoryWorldStore memory = new InMemoryWorldStore(marches(), CHUNK);
        memory.markConsumed(Coord.of(40, 40), T0);
        assertThat(memory.isConsumed(Coord.of(40, 40))).isTrue();
        assertThat(new InMemoryWorldStore(marches(), CHUNK).isConsumed(Coord.of(40, 40)))
                .as("内存版重启后玩家会看到「刚采空的资源点又回来了」，而刷新本该是显式运营行为")
                .isFalse();

        requireMongo();
        newMongoStore().markConsumed(Coord.of(40, 40), T0);
        assertThat(newMongoStore().isConsumed(Coord.of(40, 40)))
                .as("Mongo 版换实例仍然记得这一格是空的").isTrue();
    }

    // ---------- chunk 版本 ----------

    @Test
    @DisplayName("同一块的版本自增八次并发也不能吃掉：终值必须是 8")
    void chunkVersionIncrementsAreAtomicUnderConcurrency() throws Exception {
        for (WorldRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            assertThat(store.chunkVersion("1:1")).as("%s 从未变动过就是 0", label).isZero();
            assertThat(store.bumpChunkVersion("1:1")).as("%s 第一次自增返回 1", label).isEqualTo(1L);
            assertThat(store.chunkVersion("2:2")).as("%s 别的块不受影响", label).isZero();

            int racers = 8;
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            List<Future<Long>> results = new ArrayList<>();
            try {
                List<Callable<Long>> jobs = new ArrayList<>();
                for (int i = 0; i < racers; i++) {
                    jobs.add(() -> store.bumpChunkVersion("3:3"));
                }
                for (Callable<Long> job : jobs) {
                    results.add(pool.submit(job));
                }
            } finally {
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("并发自增跑不完，先查实现").isTrue();
            }
            List<Long> returned = new ArrayList<>();
            for (Future<Long> future : results) {
                returned.add(unwrap(future));
            }
            assertThat(returned).as("%s 八次自增必须给出八个互不相同的值", label)
                    .doesNotHaveDuplicates();
            assertThat(store.chunkVersion("3:3"))
                    .as("%s 少一次就是「那次变化的实体客户端永远不会再来拉」——地图上一块永久静止", label)
                    .isEqualTo(8L);
        }
    }

    // ---------- 迷雾 ----------

    @Test
    @DisplayName("迷雾读回来的是副本：改了不 saveFog 不许影响库里那份")
    void fogComesBackAsADetachedCopy() {
        for (WorldRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            FogOfWar initial = new FogOfWar();
            initial.restore(new LinkedHashSet<>(List.of("1:1", "2:2")));
            store.saveFog("P-fog", initial, initial.version());

            FogOfWar read = store.fogOf("P-fog");
            assertThat(new ArrayList<>(read.chunks()))
                    .as("%s 存进去的两块都要读回来", label).containsExactlyInAnyOrder("1:1", "2:2");
            assertThat(read.version())
                    .as("%s 版本要一起读回来，否则调用方没法带它写回", label).isPositive();

            FogOfWar mutated = store.fogOf("P-fog");
            mutated.explore(Coord.of(600, 600), CHUNK);   // 只改本地这份，不 saveFog
            assertThat(store.fogOf("P-fog").chunks())
                    .as("%s 没 saveFog 的改动不许进库（原先内存版返回的就是内部集合，那条旁路已封）", label)
                    .hasSize(2);

            assertThat(store.fogOf("P-new")).as("%s 新号是空迷雾而不是抛错", label).isNotNull();
            assertThat(store.fogOf("P-new").chunks()).as("%s 没见过任何一块", label).isEmpty();
        }
    }

    /**
     * 迷雾是整份覆盖的，所以"两条请求各自读→探索→写回"必须有一条被拒；
     * 不拒的话后写的那份会把先写的块重新盖黑（玩家看到"我刚走过的地方又黑了"，服务端不报错）。
     */
    @Test
    @DisplayName("带旧版本的迷雾写回必须被拒，且不许动库里那份块表")
    void staleFogSaveIsRefusedOnBothStores() {
        for (WorldRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            FogOfWar base = new FogOfWar();
            base.restore(new LinkedHashSet<>(List.of("0:0")));
            store.saveFog("P-stale", base, 0L);

            FogOfWar loser = store.fogOf("P-stale");
            loser.exploreChunk("9:9");
            FogOfWar winner = store.fogOf("P-stale");
            winner.exploreChunk("8:8");
            store.saveFog("P-stale", winner, winner.version());

            assertThatThrownBy(() -> store.saveFog("P-stale", loser, loser.version()))
                    .as("%s 拿着被超过的版本写回必须撞锁", label)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("乐观锁冲突");
            assertThat(new ArrayList<>(store.fogOf("P-stale").chunks()))
                    .as("%s 被拒的那次不许留下半个写入，也不许把赢家的块盖掉", label)
                    .containsExactlyInAnyOrder("0:0", "8:8");
        }
    }

    @Test
    @DisplayName("八个线程同时按建档写迷雾：只有一个赢家，其余全部撞锁")
    void concurrentFogCreateHasExactlyOneWinner() throws Exception {
        for (WorldRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            int racers = 8;
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            List<Future<Boolean>> results = new ArrayList<>();
            try {
                List<Callable<Boolean>> jobs = new ArrayList<>();
                for (int i = 0; i < racers; i++) {
                    jobs.add(() -> {
                        FogOfWar fresh = new FogOfWar();
                        fresh.restore(new LinkedHashSet<>(List.of("5:5")));
                        try {
                            store.saveFog("P-race-fog", fresh, 0L);
                            return true;
                        } catch (IllegalStateException e) {
                            return false;
                        }
                    });
                }
                for (Callable<Boolean> job : jobs) {
                    results.add(pool.submit(job));
                }
            } finally {
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("并发建档跑不完").isTrue();
            }
            long winners = 0L;
            for (Future<Boolean> future : results) {
                winners += Boolean.TRUE.equals(future.get()) ? 1L : 0L;
            }
            assertThat(winners)
                    .as("%s 两个「建档成功」意味着其中一份探索被另一份整档盖掉", label).isEqualTo(1L);
        }
    }

    // ---------- 侦查报告 ----------

    @Test
    @DisplayName("报告列表倒序 + 同刻按 id 定序，且同一份重存不许出现两次")
    void reportsAreListedNewestFirstAndADuplicateSaveDoesNotDoubleThem() {
        List<List<String>> orders = new ArrayList<>();
        for (WorldRepository store : bothStores()) {
            store.saveReport(report("R-2", "P-rep", T0 + 100L, T0 + 10_000L));
            store.saveReport(report("R-1", "P-rep", T0 + 100L, T0 + 10_000L));   // 同一时刻
            store.saveReport(report("R-3", "P-rep", T0 + 900L, T0 + 10_000L));
            store.saveReport(report("R-9", "P-other", T0 + 900L, T0 + 10_000L));
            // 同一份报告被重放两次（原实现会往 id 索引里追加一次 ⇒ 列表里出现两条）
            store.saveReport(report("R-3", "P-rep", T0 + 900L, T0 + 10_000L));

            List<String> ids = new ArrayList<>();
            store.reportsOf("P-rep").forEach(r -> ids.add(r.reportId()));
            orders.add(ids);
            assertThat(store.findReport("R-1")).as("按 id 查得到").isPresent();
            assertThat(store.findReport("R-none")).as("不存在回 empty").isEmpty();
        }
        assertThat(orders.get(1)).as("两侧顺序逐位一致").isEqualTo(orders.get(0));
        assertThat(orders.get(0)).as("最新的在前，同刻按 id；重放不许让 R-3 出现两次")
                .containsExactly("R-3", "R-1", "R-2");
    }

    @Test
    @DisplayName("过期边界两侧同一条：expiresAt == cutoff 还算没过期")
    void reportExpiryBoundaryIsIdentical() {
        for (WorldRepository store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.saveReport(report("R-edge", "P-edge", T0, T0 + 500L));
            store.saveReport(report("R-keep", "P-edge", T0, T0 + 501L));

            assertThat(store.purgeExpiredReports(T0 + 500L))
                    .as("%s 严格小于才算过期，边界那条要留着", label).isZero();
            assertThat(store.purgeExpiredReports(T0 + 501L))
                    .as("%s 越过边界的那一条被清掉", label).isEqualTo(1);
            assertThat(store.findReport("R-edge")).as("%s 被清掉的不能还在", label).isEmpty();
            assertThat(store.findReport("R-keep")).as("%s 另一条不受影响", label).isPresent();
            assertThat(store.purgeExpiredReports(T0 + 999L))
                    .as("%s 重复清理报本次真实条数", label).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("坐标存储键两侧同一个格式，且能原样解析回来")
    void coordinateStorageKeysAreSharedAndReversible() {
        // 用多位数坐标：这既是"字典序 ≠ 数值序"的现场（"10:3" 排在 "2:3" 前面），
        // 也是两侧必须逐字节一致的那个键
        assertThat(Coord.of(10, 3).storageKey()).isEqualTo("10:3");
        assertThat(Coord.parseStorageKey("10:3")).isEqualTo(Coord.of(10, 3));
        assertThatThrownBy(() -> Coord.parseStorageKey("3-7"))
                .as("格式不对是数据被写坏，必须响而不是回一个默认坐标")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Coord.parseStorageKey(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Coord.of(-3, 7))
                .as("负坐标在构造层就被拒绝，所以存储键不需要处理它")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("世界三处索引必须存在：coordKey 唯一是「一玩家一文档」成立的前提")
    void worldCollectionsHaveTheirIndexes() {
        requireMongo();
        List<String> cityKeys = new ArrayList<>();
        List<Boolean> cityUnique = new ArrayList<>();
        db.template().getCollection(WorldCityDocument.COLLECTION).listIndexes().forEach(info -> {
            cityKeys.add(info.get("key").toString());
            cityUnique.add(Boolean.TRUE.equals(info.get("unique")));
        });
        assertThat(cityKeys).as("world_city 现有索引：" + cityKeys)
                .anySatisfy(key -> assertThat(key).contains("coordKey"));
        assertThat(cityUnique).as("coordKey 必须真的是唯一索引，否则两家人可以叠在一格")
                .contains(true);

        List<String> reportKeys = new ArrayList<>();
        db.template().getCollection(ScoutReportDocument.COLLECTION).listIndexes()
                .forEach(info -> reportKeys.add(info.get("key").toString()));
        assertThat(reportKeys).as("scout_report 现有索引：" + reportKeys)
                .anySatisfy(key -> assertThat(key).contains("scoutPlayerId")
                        .contains(ScoutReportDocument.FIELD_CREATED_AT))
                .anySatisfy(key -> assertThat(key).contains(ScoutReportDocument.FIELD_EXPIRES_AT));
    }

    @Test
    @DisplayName("Mongo 必须真的可达：否则「世界状态落得住」这句话今天没有被验证过")
    void mongoMustBeReachableOrTheClaimIsUnverified() {
        Assumptions.assumeTrue(db != null,
                "跳过即未验证：世界状态的内存/Mongo 等价性没有被检查。"
                        + "补跑方式：起一个本地 MongoDB，或 -Dironoath.test.mongo.uri=... 指向一台");
    }

    // ---------- 夹具 ----------

    private List<WorldRepository> bothStores() {
        requireMongo();
        return List.of(new InMemoryWorldStore(marches(), CHUNK), newMongoStore());
    }

    /** 行军不是本档的主题（另有一份 MarchStoreEquivalenceTest），这里只需要一个能转发的空仓储。 */
    private static MarchRepository marches() {
        return new InMemoryMarchStore(CHUNK);
    }

    private static MongoWorldStore newMongoStore() {
        requireMongo();
        return new MongoWorldStore(db.template(), marches(), CHUNK);
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 见「跳过即未验证」那条");
    }

    private static ScoutReport.Report report(String reportId, String scoutPlayerId,
                                             long createdAt, long expiresAt) {
        return new ScoutReport.Report(reportId, scoutPlayerId, Coord.of(100, 100),
                "monster_01_02", 3, createdAt, expiresAt, Map.of("infantry", 500L), 10_000L, 42L);
    }

    private static Long unwrap(Future<Long> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("并发自增被中断", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AssertionError("并发自增抛异常了：版本自增的前提是谁都不许失败", e.getCause());
        }
    }
}
