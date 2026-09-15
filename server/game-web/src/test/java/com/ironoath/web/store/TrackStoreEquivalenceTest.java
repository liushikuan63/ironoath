package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.web.ops.TrackEventStore;
import com.ironoath.web.ops.TrackEventStore.CrashRecord;
import com.ironoath.web.ops.TrackEventStore.TrackRecord;
import com.ironoath.web.store.memory.InMemoryTrackStore;
import com.ironoath.web.store.mongo.MongoTrackStore;
import com.ironoath.web.store.mongo.TrackCrashDocument;
import com.ironoath.web.store.mongo.TrackEventDocument;

/**
 * 职责：埋点与崩溃存储在<b>内存与 Mongo 上必须给出同一个结果</b>（收口清单 #16 的最后一档之一）。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）；连不上时明确报"跳过即未验证"。
 *
 * <p><b>这一档要防的形状很特别：它不会因为写错而报错，只会让看板上那一格永远是 0</b>。
 * 埋点没落库，玩家照样玩、接口照样 200，唯一的证据是三天后 D30 算不出来 ——
 * 那时已经无法区分"没人来"与"来了但没记"。所以这里逐条比对的三件事都是"少一条就少一个口径"：
 * 批量写入的实际条数、按玩家取最近 N 条的边界、以及保留期清理的条数。
 */
class TrackStoreEquivalenceTest {

    private static final long T0 = 1_800_000_000_000L;
    private static TestMongo db;

    @BeforeAll
    static void connect() {
        db = TestMongo.tryOpen();
    }

    @BeforeEach
    void clearStores() {
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
    @DisplayName("批量写入：返回的是真实写入条数，null 槽位不占数也不抛")
    void saveBatchReportsWhatItActuallyWrote() {
        for (TrackEventStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            assertThat(store.saveBatch(List.of())).as("%s 空批是 0", label).isZero();
            assertThat(store.saveBatch(null)).as("%s null 批也是 0 而不是抛", label).isZero();

            List<TrackRecord> batch = new ArrayList<>();
            batch.add(event("battle_start", "P-1", T0 + 1L));
            batch.add(null);
            batch.add(event("battle_end", "P-1", T0 + 2L));
            assertThat(store.saveBatch(batch)).as("%s 三条里有一条是 null，就该报写了两条", label).isEqualTo(2);
            assertThat(store.eventCount()).as("%s 总数与写入数一致", label).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("最近 N 条：按服务端时刻倒序、limit 必须生效、别人的事件不许混进来")
    void recentOfIsNewestFirstAndBounded() {
        List<List<String>> listings = new ArrayList<>();
        for (TrackEventStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.saveBatch(List.of(
                    event("e1", "P-list", T0 + 10L),
                    event("e2", "P-list", T0 + 30L),
                    event("e3", "P-list", T0 + 20L),
                    event("e4", "P-other", T0 + 40L)));

            List<String> names = new ArrayList<>();
            store.recentOf("P-list", 2).forEach(r -> names.add(r.name()));
            listings.add(names);
            assertThat(names).as("%s 最新的在前，且只回 limit 条", label).containsExactly("e2", "e3");
            assertThat(store.recentOf("P-list", 99)).as("%s limit 大于存量时不许补 null", label).hasSize(3);
            assertThat(store.recentOf("P-none", 5)).as("%s 没事件的人回空表", label).isEmpty();
            assertThat(store.recentOf(null, 5)).as("%s null 玩家不许抛", label).isEmpty();
            assertThat(store.recentOf("P-list", 0)).as("%s limit<1 是拿不到东西，不是全量返回", label).isEmpty();
        }
        assertThat(listings.get(1)).as("两侧顺序逐位一致（同一毫秒并列不承诺顺序，故本用例用不同时刻）")
                .isEqualTo(listings.get(0));
    }

    @Test
    @DisplayName("事件的参数表与空玩家 id 都要原样回来：登录前那段漏斗全靠 playerId 为空的条目")
    void paramsAndAnonymousEventsSurvive() {
        for (TrackEventStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.saveBatch(List.of(
                    new TrackRecord("app_launch", null, T0, T0 + 1L, "tr-a", Map.of("device", "ios")),
                    new TrackRecord("pay_click", "P-params", T0, T0 + 2L, "tr-b",
                            Map.of("productId", "monthly_card", "cents", "3000"))));

            List<TrackRecord> mine = store.recentOf("P-params", 10);
            assertThat(mine).as("%s 具名事件按玩家查得到", label).hasSize(1);
            assertThat(mine.get(0).params())
                    .as("%s 参数表逐条回来（看板按 productId 分组就靠它）", label)
                    .containsEntry("productId", "monthly_card")
                    .containsEntry("cents", "3000");
            assertThat(mine.get(0).traceId()).as("%s traceId 是把玩家抱怨还原成链路的唯一线索", label)
                    .isEqualTo("tr-b");
            assertThat(store.eventCount())
                    .as("%s 没有身份的事件也必须落库（否则「进都没进就走」那一段漏斗永远是 0）", label)
                    .isEqualTo(2);
            assertThat(store.recentOf("P-launch", 5)).as("%s 匿名事件不许挂到任何人名下", label).isEmpty();
        }
    }

    @Test
    @DisplayName("同一 traceId 的崩溃补报只算一次，第二条返回 false 且内容不被覆盖")
    void crashReportsAreIdempotentByTraceId() {
        for (TrackEventStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            assertThat(store.saveCrash(crash("tr-dup", "java.lang.IllegalStateException")))
                    .as("%s 第一次收下", label).isTrue();
            assertThat(store.saveCrash(crash("tr-dup", "被补报覆盖掉的摘要")))
                    .as("%s 补报必须返回 false（幂等），否则一次崩溃会被算成两次", label).isFalse();

            CrashRecord kept = store.findCrash("tr-dup").orElseThrow();
            assertThat(kept.message()).as("%s 先到那条是真实崩溃，补报不许把它改掉", label)
                    .isEqualTo("java.lang.IllegalStateException");
            assertThat(kept.stack()).as("%s 完整堆栈（验收 9）").contains("at com.ironoath");
            assertThat(kept.sceneName()).as("%s 所在场景要原样回来（分场景看崩溃率）", label)
                    .isEqualTo("SceneMain");
            assertThat(store.saveCrash(new CrashRecord("tr-noscene", "m", "s", "0.1.0", null,
                    T0, T0 + 3L))).as("%s 崩在场景切换之间是合法情况", label).isTrue();
            assertThat(store.findCrash("tr-noscene").orElseThrow().sceneName())
                    .as("%s 没有场景时是 null，而不是空串或某个默认场景", label).isNull();
            assertThat(store.findCrash("tr-none")).as("%s 查不到回 empty", label).isEmpty();
        }
    }

    @Test
    @DisplayName("保留期清理两侧同一条边界：等于截止时刻的那条还算没过期，重复清理报 0")
    void retentionPurgeHasTheSameBoundary() {
        for (TrackEventStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.saveBatch(List.of(
                    event("old", "P-purge", T0), event("edge", "P-purge", T0 + 100L)));
            store.saveCrash(new CrashRecord("tr-old", "m", "s", "0.1.0", null, T0, T0));

            assertThat(store.purgeOlderThan(T0 + 100L))
                    .as("%s 严格小于才删（与战报、订单同一条边界）", label).isEqualTo(1);
            assertThat(store.eventCount()).as("%s 边界那条还在", label).isEqualTo(1);
            assertThat(store.purgeOlderThan(T0 + 100L)).as("%s 再清一次必须是 0 条", label).isZero();
            assertThat(store.purgeCrashesOlderThan(T0 + 1L))
                    .as("%s 崩溃表按同一条边界清理", label).isEqualTo(1);
            assertThat(store.findCrash("tr-old")).as("%s 清掉的崩溃不该还在", label).isEmpty();
        }
    }

    /**
     * 断言的是两套实现在"重启"上的真实差别：内存版把埋点与崩溃证据全丢掉，Mongo 版留着。
     * 这条不是为了夸 Mongo，是为了让"上线必须 storage=mongo"有一句可核对的证据 ——
     * 而这一档的丢失<b>没有任何运行期症状</b>：接口全 200，只是三天后 D30 那一格是空的。
     */
    @Test
    @DisplayName("换一个实例（= 重启）：内存版证据全没，Mongo 版事件与崩溃都还在")
    void aFreshInstanceAfterRestartKeepsEvidenceOnlyOnMongo() {
        InMemoryTrackStore memory = new InMemoryTrackStore(100);
        memory.saveBatch(List.of(event("survive", "P-restart", T0)));
        memory.saveCrash(crash("tr-restart", "m"));
        InMemoryTrackStore afterRestart = new InMemoryTrackStore(100);
        assertThat(afterRestart.eventCount())
                .as("内存版重启后事件全空 —— 而看板不会报错，只会显示 0").isZero();
        assertThat(afterRestart.findCrash("tr-restart")).isEmpty();

        requireMongo();
        MongoTrackStore mongo = newMongoStore();
        mongo.saveBatch(List.of(event("survive", "P-restart", T0)));
        mongo.saveCrash(crash("tr-restart", "m"));
        assertThat(newMongoStore().eventCount()).as("Mongo 版换实例仍然有这一条").isEqualTo(1);
        assertThat(newMongoStore().findCrash("tr-restart")).as("崩溃证据也不依赖进程").isPresent();
        assertThat(newMongoStore().saveCrash(crash("tr-restart", "补报")))
                .as("幂等也跨实例成立（否则重启后一次补报就把一次崩溃算成两次）").isFalse();
    }

    @Test
    @DisplayName("看板读侧两侧同一个数：分组口径、窗口边界，以及「limit 只砍明细、不砍聚合」")
    void dashboardReadsMatchAcrossImplementations() {
        for (TrackEventStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.saveBatch(List.of(
                    startup("9.9.9", T0 + 1L), startup("9.9.9", T0 + 2L), startup("1.0.0", T0 + 3L),
                    // 没带版本参数的一条：必须进空串这一组，而不是被静默丢掉（丢了等于缩小分母）
                    startup(null, T0 + 4L),
                    // 早于窗口起点的一条：不该计入
                    startup("9.9.9", T0 - 500L),
                    // 同名参数的别的事件：不该进 startup 的分母
                    new TrackRecord("login", "P-dash", T0, T0 + 5L, "tr-login",
                            Map.of("clientVersion", "9.9.9"))));

            Map<String, Long> startups = store.countEventsByParam(T0, "startup", "clientVersion");
            assertThat(startups).as("%s startup 按版本分组，只数 startup", label)
                    .containsEntry("9.9.9", 2L).containsEntry("1.0.0", 1L).containsEntry("", 1L)
                    .hasSize(3);

            store.saveCrash(new CrashRecord("tr-c1", "m", "s", "9.9.9", "SceneMain", T0, T0 + 1L));
            store.saveCrash(new CrashRecord("tr-c2", "m", "s", "9.9.9", null, T0, T0 + 2L));
            store.saveCrash(new CrashRecord("tr-c3", "m", "s", null, null, T0, T0 + 3L));
            store.saveCrash(new CrashRecord("tr-c4", "m", "s", "1.0.0", "SceneMain", T0, T0 - 500L));

            assertThat(store.crashCountByVersion(T0)).as("%s 崩溃分版本，窗口外那条不计", label)
                    .containsEntry("9.9.9", 2L).containsEntry("", 1L).hasSize(2);
            assertThat(store.crashCount()).as("%s 总数是全部存量，与窗口无关", label).isEqualTo(4);

            // 这条是整段最要紧的：明细被 limit 截断，聚合数不受影响。
            // 反过来说，如果哪天有人图省事拿 recentCrashes(limit) 去数分版本崩溃，
            // 症状就是"某个版本明明在批量崩，看板上看不出来"。
            List<CrashRecord> page = store.recentCrashes(2);
            assertThat(page).as("%s 明细按 serverTs 倒序且只回 limit 条", label)
                    .extracting(CrashRecord::traceId).containsExactly("tr-c3", "tr-c2");
            assertThat(store.crashCountByVersion(T0).values().stream().mapToLong(Long::longValue).sum())
                    .as("%s 翻了第一页不等于看到全部 —— 聚合必须仍是全量", label).isEqualTo(3L);
            assertThat(store.recentCrashes(0)).as("%s limit<1 是拿不到东西，不是全量返回", label).isEmpty();
        }
    }

    /** 一条带（或不带）版本参数的启动事件；窗口判定看 serverTs。 */
    private static TrackRecord startup(String clientVersion, long serverTs) {
        Map<String, String> params = clientVersion == null
                ? Map.of("deviceId", "dev-1") : Map.of("clientVersion", clientVersion);
        return new TrackRecord("startup", null, serverTs - 5L, serverTs, "tr-startup-" + serverTs, params);
    }

    @Test
    @DisplayName("三处索引必须存在：这张表按 30 天算是全服最大的一张，没索引每次清理都是整集合扫")
    void trackingCollectionsHaveTheirIndexes() {
        requireMongo();
        List<String> eventKeys = new ArrayList<>();
        db.template().getCollection(TrackEventDocument.COLLECTION).listIndexes()
                .forEach(info -> eventKeys.add(info.get("key").toString()));
        assertThat(eventKeys).as("track_event 现有索引：" + eventKeys)
                .anySatisfy(key -> assertThat(key).contains("playerId").contains("serverTs"))
                .anySatisfy(key -> assertThat(key).contains("serverTs"));

        List<String> crashKeys = new ArrayList<>();
        db.template().getCollection(TrackCrashDocument.COLLECTION).listIndexes()
                .forEach(info -> crashKeys.add(info.get("key").toString()));
        assertThat(crashKeys).as("track_crash 现有索引：" + crashKeys)
                .anySatisfy(key -> assertThat(key).contains("serverTs"));
    }

    @Test
    @DisplayName("Mongo 必须真的可达：否则「埋点落得住」这句话今天没有被验证过")
    void mongoMustBeReachableOrTheClaimIsUnverified() {
        Assumptions.assumeTrue(db != null,
                "跳过即未验证：埋点存储的内存/Mongo 等价性没有被检查。"
                        + "补跑方式：起一个本地 MongoDB，或 -Dironoath.test.mongo.uri=... 指向一台");
    }

    // ---------- 夹具 ----------

    private List<TrackEventStore> bothStores() {
        requireMongo();
        return List.of(new InMemoryTrackStore(1000), newMongoStore());
    }

    private static MongoTrackStore newMongoStore() {
        requireMongo();
        return new MongoTrackStore(db.template());
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 见「跳过即未验证」那条");
    }

    private static TrackRecord event(String name, String playerId, long serverTs) {
        return new TrackRecord(name, playerId, serverTs - 5L, serverTs, "tr-" + name + serverTs,
                Map.of("scene", "world"));
    }

    private static CrashRecord crash(String traceId, String message) {
        return new CrashRecord(traceId, message,
                "at com.ironoath.web.service.SomeService.method(SomeService.java:42)",
                "0.1.0", "SceneMain", T0, T0 + 3L);
    }
}
