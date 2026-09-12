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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.season.SeasonTier;
import com.ironoath.web.season.SeasonLedgerStore;
import com.ironoath.web.season.SeasonLedgerStore.Record;
import com.ironoath.web.store.memory.InMemorySeasonLedger;
import com.ironoath.web.store.mongo.MongoSeasonLedger;
import com.ironoath.web.store.mongo.SeasonLedgerDocument;

/**
 * 职责：赛季账本在<b>内存与 Mongo 上必须给出同一个结果</b>（收口清单 #16 的赛季账本档）。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）；连不上时明确报"跳过即未验证"。
 *
 * <p><b>这份测试真正的价值不在"能不能存取"，在那条 {@code 换一个实例} 的用例</b>：
 * 账本是"这一季这个人的钱已经发过"的唯一凭据，而内存版换一个实例（= 重启一次）就答不出来，
 * 于是运维再点一次结算就把金币重复发出去。内存版答不出来是<b>事实</b>不是 bug ——
 * 所以把它断言出来，让"必须用 mongo 模式"这件事有一条会红的证据，而不是靠一段注释。
 */
class SeasonLedgerStoreEquivalenceTest {

    private static final String SEASON = "season_20260101";
    private static final String OTHER = "season_20260201";
    private static TestMongo db;

    @BeforeAll
    static void connect() {
        db = TestMongo.tryOpen();
    }

    @BeforeEach
    void clearLedger() {
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

    @Test
    @DisplayName("同一季同一人第二次记账必须返回 false，且不许覆盖第一条")
    void secondRecordIsRefusedAndCannotOverwriteTheFirst() {
        for (SeasonLedgerStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            assertThat(store.recordIfAbsent(SEASON, record("P-1", 1, SeasonTier.Tier.KING, 500L, 250L)))
                    .as("%s 第一条必须记上（调用方据此才发奖）", label).isTrue();
            assertThat(store.recordIfAbsent(SEASON, record("P-1", 9, SeasonTier.Tier.BRONZE, 0L, 0L)))
                    .as("%s 重复记必须 false（false 就是付款闸门）", label).isFalse();

            Record kept = store.find(SEASON, "P-1");
            assertThat(kept.rank()).as("%s 被拒的那条不许改掉原记录（那是申诉还原的依据）", label).isEqualTo(1);
            assertThat(kept.tier()).isEqualTo(SeasonTier.Tier.KING);
            assertThat(kept.seasonCoin()).isEqualTo(500L);
        }
    }

    /**
     * 断言的是<b>两套实现在"重启"这件事上的真实差别</b>：内存版换实例就答不出"已经付过"，
     * 于是第二次结算会再发一遍钱；Mongo 版换实例仍然答得出来。
     * 这条不是为了证明实现好，是为了让"生产必须 mongo"有一句可核对的证据。
     */
    @Test
    @DisplayName("换一个实例（= 重启一次）：内存版会重新付钱，Mongo 版仍然拒绝")
    void aFreshInstanceAfterRestartStillRefusesTheSecondPayment() {
        SeasonLedgerStore memory = new InMemorySeasonLedger();
        assertThat(memory.recordIfAbsent(SEASON, record("P-restart", 1, SeasonTier.Tier.GOLD, 300L, 150L)))
                .isTrue();
        assertThat(new InMemorySeasonLedger().recordIfAbsent(SEASON, record("P-restart", 1,
                        SeasonTier.Tier.GOLD, 300L, 150L)))
                .as("内存版重启后答不出「已经付过」—— 这就是重复发钱的入口")
                .isTrue();

        requireMongo();
        MongoSeasonLedger first = newMongoStore();
        assertThat(first.recordIfAbsent(SEASON, record("P-restart", 1, SeasonTier.Tier.GOLD, 300L, 150L)))
                .isTrue();
        assertThat(newMongoStore().recordIfAbsent(SEASON, record("P-restart", 1,
                        SeasonTier.Tier.GOLD, 300L, 150L)))
                .as("Mongo 版换一个实例仍然知道付过：闸门不依赖进程")
                .isFalse();
        assertThat(newMongoStore().find(SEASON, "P-restart").gold())
                .as("第一轮发出去的金币数额还得留着，否则对不上账").isEqualTo(150L);
    }

    @Test
    @DisplayName("八线程同时记同一个人：只有一个赢家，其余全部拿到 false")
    void concurrentRecordsHaveExactlyOneWinner() throws Exception {
        for (SeasonLedgerStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            int racers = 8;
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            List<Future<Boolean>> results = new ArrayList<>();
            try {
                List<Callable<Boolean>> jobs = new ArrayList<>();
                for (int i = 0; i < racers; i++) {
                    jobs.add(() -> store.recordIfAbsent(SEASON,
                            record("P-race", 3, SeasonTier.Tier.SILVER, 200L, 100L)));
                }
                for (Callable<Boolean> job : jobs) {
                    results.add(pool.submit(job));
                }
            } finally {
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("并发记账跑不完，先查实现").isTrue();
            }
            long winners = 0L;
            for (Future<Boolean> future : results) {
                winners += Boolean.TRUE.equals(unwrap(future)) ? 1L : 0L;
            }
            assertThat(winners)
                    .as("%s 多个赢家就是多发几份钱（并发跑在结算的线程池里，不是一定串行）", label)
                    .isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("派生读两侧同一条：没记过的人是 null 与 0，跨季荣耀按最好段位算")
    void derivedReadsAgreeAcrossStores() {
        for (SeasonLedgerStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            assertThat(store.find(SEASON, "P-none")).as("%s 没记过必须回 null", label).isNull();
            assertThat(store.seasonCoinBalance(SEASON, "P-none"))
                    .as("%s 「没记录」与「余额 0」是两件事，但余额读数的口径要一致", label).isZero();

            store.recordIfAbsent(SEASON, record("P-glory", 7, SeasonTier.Tier.GOLD, 300L, 150L));
            store.recordIfAbsent(OTHER, record("P-glory", 2, SeasonTier.Tier.DIAMOND, 100L, 50L));
            assertThat(store.seasonCoinBalance(SEASON, "P-glory")).isEqualTo(300L);

            com.ironoath.core.player.PlayerGlory glory = store.gloryOf("P-glory");
            assertThat(glory.gloryLevel()).as("%s 荣耀等级 = 参与过的赛季数", label).isEqualTo(2);
            assertThat(glory.highestTier())
                    .as("%s 历史最高取各季最好的一次（不是最近一次）", label)
                    .isEqualTo(SeasonTier.Tier.DIAMOND);
            assertThat(glory.badges()).as("%s 徽章 = 参与过的赛季集合", label)
                    .containsExactlyInAnyOrder(SEASON, OTHER);
            // 中途才参战的人不该拿到他没打过的那些季的章 —— 旧实现直接把 seasonIds() 全发出去
            store.recordIfAbsent(SEASON, record("P-late", 9, SeasonTier.Tier.SILVER, 10L, 10L));
            assertThat(store.gloryOf("P-late").badges())
                    .as("%s 只打一季就只有一枚徽章", label).containsExactly(SEASON);
            assertThat(store.gloryOf("P-late").gloryLevel())
                    .as("%s 荣耀等级跟着是 1", label).isEqualTo(1);
            assertThat(store.gloryOf("P-never").gloryLevel())
                    .as("%s 没参与过的人等级是 0 而不是抛", label).isZero();
        }
    }

    @Test
    @DisplayName("归档读给出稳定顺序且不可改：同一份数据两次读出的顺序不许不同")
    void archiveListingIsStableAndReadOnly() {
        for (SeasonLedgerStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.recordIfAbsent(SEASON, record("P-c", 1, SeasonTier.Tier.KING, 1L, 1L));
            store.recordIfAbsent(SEASON, record("P-a", 2, SeasonTier.Tier.GOLD, 1L, 1L));
            store.recordIfAbsent(SEASON, record("P-b", 3, SeasonTier.Tier.SILVER, 1L, 1L));
            store.recordIfAbsent(SEASON, record("P-z", 1, SeasonTier.Tier.KING, 1L, 1L));

            assertThat(store.seasonRecords(SEASON).keySet())
                    .as("%s 按 playerId 稳定排序（并发 map 的迭代顺序不定的话，归档列表每次刷新都在变）", label)
                    .containsExactly("P-a", "P-b", "P-c", "P-z");
            assertThatThrownBy(() -> store.seasonRecords(SEASON).remove("P-a"))
                    .as("%s 归档视图必须是只读的", label)
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(store.seasonRecords("season_missing")).as("%s 空季回空表", label).isEmpty();
            assertThat(store.seasonIds()).as("%s 季 id 集合", label).containsExactly(SEASON);
        }
    }

    @Test
    @DisplayName("overwrite 只给人工改账用：它能覆盖，但改完闸门仍然拒绝重复发钱")
    void overwriteReplacesYetTheGateStillHolds() {
        for (SeasonLedgerStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.overwrite(SEASON, record("P-fix", 5, SeasonTier.Tier.GOLD, 100L, 50L));
            store.overwrite(SEASON, record("P-fix", 5, SeasonTier.Tier.KING, 800L, 400L));
            assertThat(store.find(SEASON, "P-fix").seasonCoin())
                    .as("%s 覆盖是真的覆盖", label).isEqualTo(800L);
            assertThat(store.recordIfAbsent(SEASON, record("P-fix", 1, SeasonTier.Tier.KING, 0L, 0L)))
                    .as("%s 覆盖之后仍然算「已付过」，闸门不许被绕开", label).isFalse();
        }
    }

    @Test
    @DisplayName("账本必须带 (seasonId, playerId) 索引：归档与 distinct 季都靠它")
    void ledgerHasTheSeasonAndPlayerIndex() {
        requireMongo();
        List<String> shapes = new ArrayList<>();
        db.template().getCollection(SeasonLedgerDocument.COLLECTION).listIndexes()
                .forEach(info -> shapes.add(info.get("key").toString()));
        assertThat(shapes).as("现有索引：" + shapes)
                .anySatisfy(shape -> assertThat(shape).contains("seasonId").contains("playerId"));
    }

    @Test
    @DisplayName("Mongo 必须真的可达：否则「重启后不会重复发钱」这句话今天没有被验证过")
    void mongoMustBeReachableOrTheClaimIsUnverified() {
        Assumptions.assumeTrue(db != null,
                "跳过即未验证：赛季账本的内存/Mongo 等价性没有被检查。"
                        + "补跑方式：起一个本地 MongoDB，或 -Dironoath.test.mongo.uri=... 指向一台");
    }

    /**
     * 非法入参两侧必须**同一个异常、同一句话**。这句话现在只写在端口的一处静态方法里
     * （{@code SeasonLedgerStore.requireWriteKey}），两份实现共用 —— 把文案抄两份就等着它漂移，
     * 而"同一个非法调用在 dev 与生产报出不同的话"正是本族存储反复在防的形状。
     *
     * <p>最后一条只有真库能证明：被拒的写入**不许留下一条 {@code _id="null:null"} 的垃圾档**。
     * Mongo 侧原先会把 null 直接拼进主键安静写入 —— 既查不出来，也没人知道要删它。
     */
    @Test
    @DisplayName("非法入参：两侧同一个拒绝、同一句话，且被拒的写入一条记录都不留")
    void illegalWriteInputsAreRefusedIdenticallyAndLeaveNothing() {
        requireMongo();
        Record good = record("P-legal", 1, SeasonTier.Tier.GOLD, 10L, 5L);
        List<String> messages = new ArrayList<>();

        for (SeasonLedgerStore store : List.of(new InMemorySeasonLedger(), newMongoStore())) {
            String label = store.getClass().getSimpleName();
            assertThatThrownBy(() -> store.recordIfAbsent(null, good))
                    .as("%s 空 seasonId 必须被拒", label).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.recordIfAbsent(SEASON, null))
                    .as("%s null 记录必须被拒", label).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.recordIfAbsent(SEASON,
                    record("  ", 1, SeasonTier.Tier.GOLD, 0L, 0L)))
                    .as("%s 空 playerId 必须被拒", label).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.overwrite("", good))
                    .as("%s overwrite 走同一条校验，不是只有 recordIfAbsent 管", label)
                    .isInstanceOf(IllegalArgumentException.class);
            messages.add(captureFailure(() -> store.recordIfAbsent(null, good)));

            // 读侧宽容：查不到就是查不到，不抛
            assertThat(store.find(null, "P-legal")).as("%s null 键读成 null", label).isNull();
            assertThat(store.seasonRecords(null)).as("%s 空季读成空表", label).isEmpty();
            assertThat(store.seasonCoinBalance(null, "P-legal")).as("%s 余额同理是 0", label).isZero();
        }

        assertThat(messages.get(1)).as("两侧异常类型与文案必须逐字相同").isEqualTo(messages.get(0));
        SeasonLedgerStore mongo = newMongoStore();
        assertThat(mongo.find(SEASON, "P-legal")).as("被拒的写入不许有一半生效").isNull();
        assertThat(mongo.seasonIds())
                .as("也不许建出一个「看不见的季」（null 一旦被拼进 _id 就会留在这里）").isEmpty();
    }

    private static String captureFailure(Runnable call) {
        try {
            call.run();
            return "<没有抛异常>";
        } catch (RuntimeException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    // ---------- 夹具 ----------

    private List<SeasonLedgerStore> bothStores() {
        requireMongo();
        return List.of(new InMemorySeasonLedger(), newMongoStore());
    }

    private static MongoSeasonLedger newMongoStore() {
        requireMongo();
        return new MongoSeasonLedger(db.template());
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 见「跳过即未验证」那条");
    }

    private static Record record(String playerId, int rank, SeasonTier.Tier tier,
                                 long seasonCoin, long gold) {
        return new Record(playerId, rank, tier, seasonCoin, gold);
    }

    private static Boolean unwrap(Future<Boolean> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("并发记账被中断", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AssertionError("并发记账抛异常了：闸门的前提是重复者拿到 false，不是异常", e.getCause());
        }
    }
}
