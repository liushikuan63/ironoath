package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.stage.StageProgress;
import com.ironoath.core.stage.StageProgressRepository;
import com.ironoath.web.store.mongo.MongoStageProgressStore;

/**
 * 职责：章节进度的 Mongo 实现跑同一份版本化契约，外加"星级/首通时刻/扫荡数都落得住"的往返断言。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）。
 */
class MongoStageProgressStoreContractTest extends VersionedStoreContractTest<StageProgress> {

    private static final String STAGE = "stage_01_01";
    private static final String OTHER = "stage_01_02";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static TestMongo db;

    private MongoStageProgressStore store;
    private String playerId = "P-stage";

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
        return "MongoStageProgressStore";
    }

    @Override protected void freshStore() {
        requireMongo();
        store = new MongoStageProgressStore(db.template());
        // 每条用例换 playerId 而不是清表：库在整个类里共享，清表会把并发插入那条用例的其他 racer 一起清掉
        playerId = "P-stage-" + SEQ.incrementAndGet();
    }

    @Override protected boolean insertInitialState() {
        return store.insertIfAbsent(playerId, new StageProgress());
    }

    @Override protected StoreHandle<StageProgress> read() {
        StageProgress progress = store.findByPlayerId(playerId).orElseThrow(
                () -> new AssertionError("契约前提被破坏：插入之后读不到进度"));
        return new StoreHandle<>(progress, store.versionOf(playerId));
    }

    @Override protected long observe(StageProgress progress) {
        return progress.all().size();
    }

    @Override protected void bump(StageProgress progress) {
        progress.recordResult(STAGE, true, true, true, 6, 1_800_000_000_000L);
    }

    @Override protected void persist(StoreHandle<StageProgress> handle) {
        store.save(playerId, handle.state(), handle.readVersion());
    }

    @Override protected long storedVersion() {
        return store.versionOf(playerId);
    }

    /**
     * 与 {@code StageStoreContractTest.nullKeyIsAnsweredPolitely} 同名配对：
     * 同一个非法调用（null playerId）在两侧必须给出同样的答案，而不是内存版静默、Mongo 版抛。
     */
    @Test
    @DisplayName("null playerId：版本回 0、读回 empty（与内存版同一条）")
    void nullKeyIsAnsweredPolitely() {
        requireMongo();
        freshStore();
        assertThat(store.versionOf(null)).as("没有存档就是第 0 版").isZero();
        assertThat(store.findByPlayerId(null)).as("null 键读成 empty 而不是让驱动抛").isEmpty();
        assertThat(store.versionOf(playerId)).as("这个 playerId 本来就没有档").isZero();
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 见「跳过即未验证」那条");
    }

    /**
     * 契约的可观察量只有"挑战过几关"，而这一档最容易丢的恰好是数量不变、内容变了的那些字段：
     * 三星、历史最少回合、首次通关时刻、累计扫荡次数。少落一个都不会让上面四条变红。
     */
    @Test
    @DisplayName("落库往返不丢内容：三星、历史最少回合、首通时刻、扫荡数、多关记录都要原样回来")
    void starsRecordsAndSweepCountSurviveTheRoundTrip() {
        requireMongo();
        freshStore();
        insertInitialState();

        StoreHandle<StageProgress> handle = read();
        handle.state().recordResult(STAGE, true, true, true, 6, 1_800_000_001_000L);
        handle.state().recordResult(OTHER, true, false, false, 9, 1_800_000_002_000L);
        handle.state().addSweeps(STAGE, 12L);
        persist(handle);

        StageProgress back = read().state();
        StageProgress.Record first = back.of(STAGE);
        assertThat(first.cleared()).as("首通（首通奖励的唯一判据）").isTrue();
        assertThat(first.stars()).as("三颗星一颗都不能在落库路上被抹平").isEqualTo(3);
        assertThat(first.noLoss()).as("历史最好的无损，不是最近一次").isTrue();
        assertThat(first.withinRounds()).isTrue();
        assertThat(first.bestRounds()).as("历史最少回合").isEqualTo(6);
        assertThat(first.clearedAt()).as("首通时刻是对账与客服取证的凭据").isEqualTo(1_800_000_001_000L);
        assertThat(first.sweepCount()).as("扫荡次数是玩家消耗过的体力口径").isEqualTo(12L);
        assertThat(back.of(OTHER).stars()).as("另一关只拿到首通那一星").isEqualTo(1);
        assertThat(back.all()).as("两关记录都得在，Map 的键不能塌成一个").containsKeys(STAGE, OTHER);
        assertThat(back.cleared("stage_99_99")).as("没打过的关必须读成 false 而不是抛错").isFalse();
    }

    /** 星级只升不降是领域规则，但前提是历史值能读回来 —— 读不回来的话这条规则每天重置一次。 */
    @Test
    @DisplayName("读回来之后重试打得更差，历史星数不许掉")
    void worseRetryDoesNotDropHistoricalStars() {
        requireMongo();
        freshStore();
        insertInitialState();
        StoreHandle<StageProgress> handle = read();
        handle.state().recordResult(STAGE, true, true, true, 6, 1_800_000_001_000L);
        persist(handle);

        StoreHandle<StageProgress> retry = read();
        retry.state().recordResult(STAGE, true, false, false, 20, 1_800_000_009_000L);
        persist(retry);

        StageProgress.Record back = read().state().of(STAGE);
        assertThat(back.stars()).as("重试打差了不该扣星（扣星会让人不敢重试）").isEqualTo(3);
        assertThat(back.bestRounds()).as("历史最少回合也不该被更差的一次覆盖").isEqualTo(6);
        assertThat(back.clearedAt()).as("首通时刻只能写一次").isEqualTo(1_800_000_001_000L);
    }

    /**
     * 与内存版那条反向配对，且用的是同一个调用形状（{@code save(playerId, p, p.version())}，
     * 即 {@code StageAppService.saveProgress} 的写法）：这里第一次 save 之后对象仍停在旧版本，
     * 所以第二次提交带的是过期版本，必须撞锁。
     */
    @Test
    @DisplayName("按服务的调用形状连写两次：Mongo 必须拒掉第二次（内存版会静默放行）")
    void secondSaveWithTheSameObjectIsRejected() {
        requireMongo();
        freshStore();
        insertInitialState();
        StoreHandle<StageProgress> handle = read();
        bump(handle.state());
        long versionAtRead = handle.state().version();
        store.save(playerId, handle.state(), handle.state().version());

        assertThat(handle.state().version())
                .as("Mongo 版不动调用方的对象，所以它始终带着读到的那个版本")
                .isEqualTo(versionAtRead);
        bump(handle.state());
        assertThatThrownBy(() -> store.save(playerId, handle.state(), handle.state().version()))
                .as("第二次提交带的还是旧版本 —— 忘记重读的 bug 在这里会响")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("乐观锁冲突");
    }

    @Test
    @DisplayName("save 一个没插过的玩家：报「进度不存在」而不是「乐观锁冲突」")
    void saveWithoutInsertSaysNotFoundNotConflict() {
        requireMongo();
        freshStore();
        assertThatThrownBy(() -> store.save(playerId, new StageProgress(), 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("进度不存在")
                .hasMessageNotContaining("乐观锁");
        assertThat(store.versionOf(playerId)).as("缺档时版本读成 0，与内存版同口径").isZero();
    }

    @Test
    @DisplayName("Mongo 必须真的可达：否则「语义等价」这条承诺今天没有被验证过")
    void mongoMustBeReachableOrTheClaimIsUnverified() {
        Assumptions.assumeTrue(db != null,
                "跳过即未验证：章节进度的内存/Mongo 等价性没有被检查。"
                        + "补跑方式：起一个本地 MongoDB，或 -Dironoath.test.mongo.uri=... 指向一台");
    }
}
