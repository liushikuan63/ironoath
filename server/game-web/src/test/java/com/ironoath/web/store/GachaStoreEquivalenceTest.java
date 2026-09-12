package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.gacha.GachaLogStore;
import com.ironoath.core.gacha.GachaState;
import com.ironoath.core.gacha.GachaStateRepository;
import com.ironoath.core.gacha.Tier;
import com.ironoath.web.store.memory.InMemoryGachaLogStore;
import com.ironoath.web.store.memory.InMemoryGachaStateStore;
import com.ironoath.web.store.mongo.GachaLogBatchDocument;
import com.ironoath.web.store.mongo.MongoGachaLogStore;
import com.ironoath.web.store.mongo.MongoGachaStateStore;

/**
 * 职责：抽卡两类状态（保底进度 + 抽卡日志）在<b>内存与 Mongo 两套实现上必须给出同一个结果</b>。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）；连不上时明确报"跳过即未验证"。
 *
 * <p><b>为什么写成"两套实现互相比对"而不是各跑一份抽象契约</b>：端口注释能说的只是
 * "一批全写、按时间升序、清理返回条数"，而真正会出事的是两边各自理解错一句话——
 * 各自跑各自的抽象契约时两边可以<b>同时错得一致地不明显</b>（例如都按插入序返回，
 * 只有监管来查那年才发现 Mongo 的无序返回）。直接比对两套实现的实际输出，
 * 错不开就红，不需要先把每条语义都写成断言。
 *
 * <p>为什么这两类值得单独一份而不是塞进 #34 的版本化契约：<b>它们没有乐观锁</b>
 * （抽卡整段在玩家锁内，计数字段单调），所以版本化那四条在这儿一条都套不上；
 * 而它们真正的难点是"一批原子"和"清理要报条数"——都是合规口径，不是并发口径。
 */
class GachaStoreEquivalenceTest {

    private static final long T0 = 1_800_000_000_000L;
    private static TestMongo db;

    @BeforeAll
    static void connect() {
        db = TestMongo.tryOpen();
    }

    /**
     * 每条用例前清空两个集合的<b>文档</b>（不删集合，否则索引也跟着没了）。
     *
     * <p>不带这句就会交叉污染：{@code purgeBefore} 是全局口径、返回的是"删了多少条"，
     * 而内存实现每条用例都是新实例、Mongo 库却在整个类里共享 —— 于是同一条断言在两边
     * 数出不同的数（这不是实现的差异，是测试自己的前提不成立）。
     */
    @BeforeEach
    void clearCollections() {
        if (db != null) {
            db.template().remove(new org.springframework.data.mongodb.core.query.Query(),
                    GachaLogBatchDocument.COLLECTION);
            db.template().remove(new org.springframework.data.mongodb.core.query.Query(),
                    com.ironoath.web.store.mongo.GachaStateDocument.COLLECTION);
        }
    }

    @AfterAll
    static void release() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    // ---------- 抽卡日志 ----------

    @Test
    @DisplayName("一次十连：两套实现都按时间升序给回同样的 10 条")
    void tenDrawBatchIsReturnedInAscendingOrderByBothImplementations() {
        List<GachaLogStore.Entry> batch = batch("P-ten", "req-ten", 10);

        List<GachaLogStore.Entry> fromMemory = replayWith(new InMemoryGachaLogStore(), batch);
        List<GachaLogStore.Entry> fromMongo = replayWith(newMongoLogStore(), batch);

        assertThat(describe(fromMemory)).containsExactlyElementsOf(describe(fromMongo));
        assertThat(fromMemory).as("十连必须 10 条都在").hasSize(10);
        assertThat(fromMemory).as("必须按时间升序（客服与监管都按发生顺序看）")
                .isSortedAccordingTo((a, b) -> Long.compare(a.drawnAt(), b.drawnAt()));
    }

    @Test
    @DisplayName("since 之后才起的日志被过滤掉，两套实现给出同一个边界")
    void sinceFilterAgreesAcrossImplementations() {
        List<GachaLogStore.Entry> batch = batch("P-since", "req-since", 10);
        GachaLogStore memory = new InMemoryGachaLogStore();
        memory.appendAll(batch);
        GachaLogStore mongo = newMongoLogStore();
        mongo.appendAll(batch);

        // 边界取第 5 条：含等于（>=），两套实现必须在"含不含第 5 条"上取同一边
        long since = batch.get(4).drawnAt();
        List<GachaLogStore.Entry> fromMemory = memory.query("P-since", since);
        List<GachaLogStore.Entry> fromMongo = mongo.query("P-since", since);

        assertThat(describe(fromMemory)).as("第 5 条本身要被包含（端口写的是「起始时间戳（含）」）")
                .containsExactlyElementsOf(describe(fromMongo));
        assertThat(fromMemory).hasSize(6);
    }

    /** 清理要报"条数"，不能报"删了几个批次"——审计问的是前者。 */
    @Test
    @DisplayName("purgeBefore 报的是删掉的条数，且新条目一条不少")
    void purgeReportsRemovedEntriesNotRemovedBatches() {
        List<GachaLogStore.Entry> old = batch("P-purge", "req-old", 4, T0);
        List<GachaLogStore.Entry> recent = batch("P-purge", "req-new", 3, T0 + 10_000L);

        GachaLogStore memory = new InMemoryGachaLogStore();
        memory.appendAll(old);
        memory.appendAll(recent);
        GachaLogStore mongo = newMongoLogStore();
        mongo.appendAll(old);
        mongo.appendAll(recent);

        long cutoff = T0 + 5_000L;
        int removedMemory = memory.purgeBefore(cutoff);
        int removedMongo = mongo.purgeBefore(cutoff);

        assertThat(removedMemory).as("删掉的是 4 条旧日志，不是 1 个批次").isEqualTo(4);
        assertThat(removedMongo).isEqualTo(removedMemory);
        assertThat(memory.query("P-purge", 0L)).as("新日志一条都不许被连带删掉").hasSize(3);
        assertThat(mongo.query("P-purge", 0L)).hasSize(3);
        assertThat(mongo.purgeBefore(cutoff)).as("重复清理必须是 0，不能把已经删掉的再报一次").isZero();
    }

    /** 一批混进两个玩家必须在写入之前就拒绝 —— 写一半等于某一方的日志凭空少几条。 */
    @Test
    @DisplayName("混入第二个玩家的批次被拒绝，且一条都不落库")
    void mixedPlayerBatchIsRejectedWithoutPartialWrite() {
        List<GachaLogStore.Entry> mixed = new ArrayList<>(batch("P-mix", "req-mix", 2));
        mixed.add(entry("P-other", "req-mix", "hero_ssr_01", T0 + 99L, false));

        for (GachaLogStore store : List.of(new InMemoryGachaLogStore(), newMongoLogStore())) {
            assertThatThrownBy(() -> store.appendAll(mixed))
                    .as("%s 必须拒绝混玩家的批次", store.getClass().getSimpleName())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("同一个玩家");
            assertThat(store.query("P-mix", 0L)).as("拒绝的那一批不许留下任何一条")
                    .isEmpty();
            assertThat(store.query("P-other", 0L)).isEmpty();
        }
    }

    @Test
    @DisplayName("空批次是合法的 no-op（两套实现都不因此建出空记录）")
    void emptyBatchIsANoOp() {
        for (GachaLogStore store : List.of(new InMemoryGachaLogStore(), newMongoLogStore())) {
            store.appendAll(List.of());
            store.appendAll(null);
            assertThat(store.query("P-empty", 0L)).isEmpty();
        }
    }

    /** isPity 是合规必需字段：丢了它就等于"公示里写了保底，玩家记录里看不到保底生效过"。 */
    @Test
    @DisplayName("isPity 与碎片数、种子这些字段一条都不能在落库路上被抹平")
    void complianceFieldsSurviveTheRoundTrip() {
        List<GachaLogStore.Entry> batch = List.of(
                entry("P-fields", "req-f", "hero_ssr_07", T0, true),
                entry("P-fields", "req-f", "hero_sr_03", T0 + 1L, false));

        GachaLogStore memory = new InMemoryGachaLogStore();
        memory.appendAll(batch);
        GachaLogStore mongo = newMongoLogStore();
        mongo.appendAll(batch);

        assertThat(describe(memory.query("P-fields", 0L)))
                .isEqualTo(describe(mongo.query("P-fields", 0L)));
        GachaLogStore.Entry back = mongo.query("P-fields", 0L).get(0);
        assertThat(back.isPity()).as("保底标记（缺失即不合规）").isTrue();
        assertThat(back.seed()).as("种子必须可查，否则「凭 seed 复算」这条验收在存储上就断了")
                .isNotZero();
        assertThat(back.heroId()).isEqualTo("hero_ssr_07");
        assertThat(back.tier()).isEqualTo(Tier.SSR);
    }

    /** 端口 javadoc 明确要求 Mongo 版带 (playerId, drawnAt) 复合索引；这里检查它真的在。 */
    @Test
    @DisplayName("抽卡日志的 (playerId, drawnAt) 复合索引必须存在：拿不出记录等于没有记录")
    void gachaLogHasThePlayerAndTimeCompoundIndex() {
        requireMongo();
        List<String> shapes = new ArrayList<>();
        db.template().getCollection(GachaLogBatchDocument.COLLECTION).listIndexes()
                .forEach(info -> shapes.add(info.get("key").toString()));
        assertThat(shapes).as("现有索引：" + shapes)
                .anySatisfy(shape -> assertThat(shape).contains("playerId").contains("lastDrawnAt"));
    }

    // ---------- 保底进度 ----------

    @Test
    @DisplayName("保底进度：首次读为空、保存后可读、重复保存覆盖而不是插第二份")
    void countersAreUpsertedPerPoolByBothImplementations() {
        for (GachaStateRepository store : List.of(new InMemoryGachaStateStore(), newMongoStateStore())) {
            String label = store.getClass().getSimpleName();
            assertThat(store.find("P-state", "pool_newbie")).as("%s 首次应为空", label).isEmpty();

            store.save(new GachaState("P-state", "pool_newbie", 59L, 2L, 1L, 120L));
            GachaState saved = store.find("P-state", "pool_newbie").orElseThrow();
            assertThat(saved.ssrCounter()).as("%s 距 SSR 计数要原样回来（59 是玩家最在意的那个数）", label)
                    .isEqualTo(59L);
            assertThat(saved.lifetimeDraws()).isEqualTo(120L);

            store.save(new GachaState("P-state", "pool_newbie", 3L, 0L, 0L, 121L));
            assertThat(store.find("P-state", "pool_newbie").orElseThrow().ssrCounter())
                    .as("%s 第二次保存必须是覆盖，插出第二份就等于保底计数分裂", label).isEqualTo(3L);
        }
    }

    /** 保底按池分桶：塞进同一份存档会让"抽一次卡"和"升一次武将"争同一个版本号（端口注释的理由）。 */
    @Test
    @DisplayName("两个卡池的保底进度互不影响")
    void poolsHaveIndependentCounters() {
        for (GachaStateRepository store : List.of(new InMemoryGachaStateStore(), newMongoStateStore())) {
            store.save(GachaState.fresh("P-two", "pool_a"));
            store.save(new GachaState("P-two", "pool_b", 88L, 0L, 0L, 88L));

            Optional<GachaState> a = store.find("P-two", "pool_a");
            assertThat(a).isPresent();
            assertThat(a.orElseThrow().ssrCounter()).as("A 池不该被 B 池带高").isZero();
            assertThat(store.find("P-two", "pool_b").orElseThrow().ssrCounter()).isEqualTo(88L);
        }
    }

    @Test
    @DisplayName("Mongo 必须真的可达：否则「两套实现等价」今天没有被验证过")
    void mongoMustBeReachableOrTheClaimIsUnverified() {
        Assumptions.assumeTrue(db != null,
                "跳过即未验证：抽卡两类状态的内存/Mongo 等价性没有被检查。"
                        + "补跑方式：起一个本地 MongoDB，或 -Dironoath.test.mongo.uri=... 指向一台");
    }

    // ---------- 夹具 ----------

    /** mongo 与内存跑同一段脚本，返回查出来的结果，便于逐条比对。 */
    private List<GachaLogStore.Entry> replayWith(GachaLogStore store, List<GachaLogStore.Entry> batch) {
        store.appendAll(batch);
        return store.query(batch.get(0).playerId(), 0L);
    }

    private static List<String> describe(List<GachaLogStore.Entry> entries) {
        List<String> out = new ArrayList<>();
        for (GachaLogStore.Entry e : entries) {
            out.add(e.drawnAt() + "|" + e.heroId() + "|" + e.tier() + "|pity=" + e.isPity()
                    + "|new=" + e.isNew() + "|" + e.fragments());
        }
        return out;
    }

    private static List<GachaLogStore.Entry> batch(String playerId, String requestId, int count) {
        return batch(playerId, requestId, count, T0);
    }

    private static List<GachaLogStore.Entry> batch(String playerId, String requestId, int count, long from) {
        List<GachaLogStore.Entry> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(entry(playerId, requestId, i % 3 == 0 ? "hero_ssr_01" : "hero_r_09",
                    from + i * 1_000L, i % 4 == 0));
        }
        return out;
    }

    private static GachaLogStore.Entry entry(String playerId, String requestId, String heroId,
                                             long drawnAt, boolean isPity) {
        return new GachaLogStore.Entry(playerId, "pool_normal", drawnAt, requestId,
                9_000_000L + drawnAt, (int) (drawnAt % 10), heroId,
                isPity ? Tier.SSR : Tier.R, isPity, !isPity, isPity ? 0L : 30L);
    }

    private static GachaLogStore newMongoLogStore() {
        requireMongo();
        return new MongoGachaLogStore(db.template());
    }

    private static GachaStateRepository newMongoStateStore() {
        requireMongo();
        return new MongoGachaStateStore(db.template());
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 见「跳过即未验证」那条");
    }
}
