package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.web.store.mongo.MongoArmyStore;

/**
 * 职责：军队存档的 Mongo 实现跑同一份版本化契约，外加一条逐字段落库往返。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）。
 *
 * <p>为什么必须补那条逐字段断言：契约只看"总兵力"这一个数，而军队存档里最容易丢的三样
 * —— 训练队列（每个兵种各一条任务及其四个时间字段）、治疗进度、额外治疗位 ——
 * <b>都不改变总兵力</b>。少存一个字段不会让任何测试变红，只会让玩家发现
 * "我明明在训练 T5，重启后队列空了但资源没退"。
 */
class MongoArmyStoreContractTest extends VersionedStoreContractTest<ArmyState> {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static TestMongo db;

    private ArmyRepository store;
    private String playerId = "P-army";

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
        return "MongoArmyStore";
    }

    @Override protected void freshStore() {
        requireMongo();
        store = new MongoArmyStore(db.template());
        playerId = "P-army-" + SEQ.incrementAndGet();
    }

    @Override protected boolean insertInitialState() {
        return store.insertIfAbsent(playerId, new ArmyState());
    }

    @Override protected StoreHandle<ArmyState> read() {
        ArmyState army = store.findByPlayerId(playerId).orElseThrow(
                () -> new AssertionError("契约前提被破坏：插入之后读不到军队存档"));
        return new StoreHandle<>(army, store.versionOf(playerId));
    }

    @Override protected long observe(ArmyState army) {
        return army.totalTroops();
    }

    @Override protected void bump(ArmyState army) {
        army.add("unit_infantry_t1", 10L);
    }

    @Override protected void persist(StoreHandle<ArmyState> handle) {
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
    @DisplayName("落库往返不丢训练与治疗：队列四个时间字段、治疗进度与资源明细、额外治疗位都要原样回来")
    void trainingQueueAndTreatmentProgressSurviveTheRoundTrip() {
        requireMongo();
        freshStore();
        insertInitialState();

        StoreHandle<ArmyState> handle = read();
        Map<String, Long> troops = new LinkedHashMap<>();
        troops.put("unit_infantry_t1", 1_200L);
        troops.put("unit_cavalry_t3", 80L);
        Map<String, ArmyState.TrainingTask> queue = new LinkedHashMap<>();
        // 注意 originalSeconds != totalSeconds：这正是"用过加速"的样子，两个值都必须各自存住
        queue.put("unit_siege_t2", new ArmyState.TrainingTask(
                "unit_siege_t2", 6L, 8_000L, 14_000L, 3_000L, 4_200L));
        Map<String, Long> wounded = new LinkedHashMap<>();
        wounded.put("unit_infantry_t1", 45L);
        Map<String, Long> treatCost = new LinkedHashMap<>();
        treatCost.put("FOOD", 900L);
        treatCost.put("GOLD", 120L);
        handle.state().restore(troops, queue, wounded, 20_000L, 3_000L, 4_200L, treatCost, 2);
        persist(handle);

        ArmyState back = read().state();
        assertThat(back.countOf("unit_infantry_t1")).isEqualTo(1_200L);
        assertThat(back.countOf("unit_cavalry_t3")).isEqualTo(80L);
        assertThat(back.totalTroops()).isEqualTo(1_280L);

        ArmyState.TrainingTask task = back.queue().get("unit_siege_t2");
        assertThat(task).as("训练队列不能整条丢").isNotNull();
        assertThat(task.count()).isEqualTo(6L);
        assertThat(task.startedAt()).isEqualTo(8_000L);
        assertThat(task.finishAt()).isEqualTo(14_000L);
        assertThat(task.totalSeconds()).as("剩余时长（用过加速后的值）").isEqualTo(3_000L);
        assertThat(task.originalSeconds()).as("原始时长（加速不改它，帮助百分比以它为基数）").isEqualTo(4_200L);

        assertThat(back.totalWounded()).as("伤兵").isEqualTo(45L);
        assertThat(back.treatFinishAt()).as("治疗完成时刻").isEqualTo(20_000L);
        assertThat(back.treatTotalSeconds()).isEqualTo(3_000L);
        assertThat(back.treatOriginalSeconds()).isEqualTo(4_200L);
        assertThat(back.treatCost()).as("取消治疗要按这份明细退剩余比例，丢了就退不出资源")
                .containsExactlyInAnyOrderEntriesOf(treatCost);
        assertThat(back.extraSlots()).as("付费额外治疗位").isEqualTo(2);
    }

    @Test
    @DisplayName("Mongo 必须真的可达：否则「语义等价」这条承诺今天没有被验证过")
    void mongoMustBeReachableOrTheClaimIsUnverified() {
        Assumptions.assumeTrue(db != null,
                "跳过即未验证：内存与 Mongo 的语义等价没有被检查。"
                        + "补跑方式：起一个本地 MongoDB，或 -Dironoath.test.mongo.uri=... 指向一台");
    }
}
