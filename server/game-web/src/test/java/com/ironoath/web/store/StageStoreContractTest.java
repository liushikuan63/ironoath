package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.stage.StageProgress;
import com.ironoath.core.stage.StageProgressRepository;
import com.ironoath.web.store.memory.InMemoryStageProgressStore;

/** 章节进度仓储必须满足的版本化契约（内存实现）。 */
class StageStoreContractTest extends VersionedStoreContractTest<StageProgress> {

    static final String PLAYER = "P-stage";
    static final String STAGE = "stage_01_01";

    private StageProgressRepository store = new InMemoryStageProgressStore();

    @Override protected String storeName() {
        return "InMemoryStageProgressStore";
    }

    @Override protected void freshStore() {
        store = new InMemoryStageProgressStore();
    }

    @Override protected boolean insertInitialState() {
        return store.insertIfAbsent(PLAYER, new StageProgress());
    }

    @Override protected StoreHandle<StageProgress> read() {
        StageProgress progress = store.findByPlayerId(PLAYER).orElseThrow(
                () -> new AssertionError("契约前提被破坏：插入之后读不到进度"));
        return new StoreHandle<>(progress, store.versionOf(PLAYER));
    }

    @Override protected long observe(StageProgress progress) {
        return progress.all().size();
    }

    @Override protected void bump(StageProgress progress) {
        progress.recordResult(STAGE, true, true, true, 6, 1_800_000_000_000L);
    }

    @Override protected void persist(StoreHandle<StageProgress> handle) {
        store.save(PLAYER, handle.state(), handle.readVersion());
    }

    @Override protected long storedVersion() {
        return store.versionOf(PLAYER);
    }

    /**
     * null 键必须"当成没有存档"，而不是从 {@code ConcurrentHashMap.get(null)} 抛 NPE。
     * Mongo 侧那一档断言同样两条 —— 同一个非法调用两侧不能一种是静默、一种是异常。
     */
    @Test
    @DisplayName("null playerId：版本回 0、读回 empty，而不是抛一个没头没尾的 NPE")
    void nullKeyIsAnsweredPolitely() {
        freshStore();
        assertThat(store.versionOf(null)).as("没有存档就是第 0 版").isZero();
        assertThat(store.findByPlayerId(null)).as("null 键读成 empty 而不是抛").isEmpty();
        assertThat(store.findByPlayerId(PLAYER)).as("前置条件：本来没有档").isEmpty();
        assertThat(store.versionOf(PLAYER)).as("不存在的玩家也回 0").isZero();
    }

    /**
     * 按 {@code StageAppService.saveProgress} 的调用形状（{@code save(playerId, p, p.version())}）
     * 连写两次：第二次必须撞锁。内存版曾经把调用方对象的版本也推进，于是这里会静默成功 ——
     * 那条宽松行为已经去掉，因为 Mongo 版不这样做，而"两套实现语义等价"正是本契约的存在理由。
     * 与 {@code MongoStageProgressStoreContractTest} 的同名断言配对。
     */
    @Test
    @DisplayName("同一个对象连写两次必须撞锁（内存与 Mongo 同口径）")
    void secondSaveWithTheSameObjectIsRejected() {
        freshStore();
        insertInitialState();
        StoreHandle<StageProgress> handle = read();
        bump(handle.state());
        long versionAtRead = handle.state().version();
        store.save(PLAYER, handle.state(), handle.state().version());

        assertThat(handle.state().version())
                .as("save 不许推进调用方对象的版本，否则第二次提交会带着一个「恰好正确」的版本，"
                        + "乐观锁就形同虚设")
                .isEqualTo(versionAtRead);
        bump(handle.state());
        assertThatThrownBy(() -> store.save(PLAYER, handle.state(), handle.state().version()))
                .as("忘记重读的第二次写入要响，不要静默覆盖")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("乐观锁冲突");
    }
}
