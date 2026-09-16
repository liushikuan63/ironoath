package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.activity.ActivityCondition;
import com.ironoath.core.activity.ActivityProgress;
import com.ironoath.core.activity.ActivityType;
import com.ironoath.web.activity.ActivityProgressStore;
import com.ironoath.web.store.memory.InMemoryActivityProgressStore;
import com.ironoath.web.store.mongo.MongoActivityProgressStore;

/**
 * 职责：活动存储在<b>内存与 Mongo 上必须给出同一个结果</b>（B17 验收 9）。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）。
 *
 * <p><b>这份测试真正防的是"连续签到看着在记、重启之后从第 1 天重数"</b>：活动进度既有累加型
 * （这轮打了 30 只怪）也有连续型（连了 5 天），两种都<b>无法从当前状态反推</b>。
 * 嵌套字段少落一个（比如 {@code windowStart} 没存）的症状不是报错，而是重启后整轮进度被当成
 * "上一轮"而标成 EXPIRED —— 面板看起来正常，只是奖永远领不到。
 *
 * <p>三条容易被各自的实现理解错的语义各有一条：写侧键校验两边都拒、未知玩家读到 empty、
 * 存档里缺的行由 {@code restore} 补齐（表里加一行，老号也看得见）。
 */
class ActivityStoreEquivalenceTest {

    private static TestMongo db;

    @BeforeAll
    static void connect() {
        db = TestMongo.tryOpen();
    }

    @BeforeEach
    void clearActivity() {
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
    @DisplayName("逐字段往返：windowStart / value / claimed / lastLoginAt 一个都不能少（两套实现给出同一份）")
    void roundTripIsIdenticalAcrossStores() {
        ActivityProgressStore.State state = new ActivityProgressStore.State("P-rt", List.of(
                new ActivityProgress.Entry("activity_login_7d", 1_800_000_000_000L, 5L, false, 1_800_000_400_000L),
                new ActivityProgress.Entry("activity_monster_hunt", 1_700_000_000_000L, 30L, true, 0L)),
                1_700_000_000_000L);
        for (ActivityProgressStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.save("P-rt", state);

            ActivityProgressStore.State back = store.load("P-rt").orElseThrow();
            assertThat(back.serverOpenMs()).as("%s 的开服锚也是存档的一部分（诊断要看它）", label)
                    .isEqualTo(1_700_000_000_000L);
            assertThat(back.entries()).as("%s 逐字段一致", label).isEqualTo(state.entries());
            ActivityProgress.Entry login = back.entries().get(0);
            assertThat(login.lastLoginAt()).as("%s lastLoginAt 少落了 → 重启后同日会重复计数", label)
                    .isEqualTo(1_800_000_400_000L);
            assertThat(login.windowStart()).as("%s windowStart 少落了 → 重启后整轮被读成 EXPIRED", label)
                    .isEqualTo(1_800_000_000_000L);
            assertThat(back.entries().get(1).claimed()).as("%s claimed 少落了 → 已领的奖能再领一次", label)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("覆盖保存：同一玩家第二次 save 就是新状态（没有版本号，前提是同玩家串行写）")
    void saveOverwritesThePlayersState() {
        for (ActivityProgressStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.save("P-ow", state("P-ow", 1L));
            store.save("P-ow", state("P-ow", 9L));
            assertThat(store.load("P-ow").orElseThrow().entries().get(0).value())
                    .as("%s 第二次保存必须覆盖第一次", label).isEqualTo(9L);
            assertThat(store.load("P-other")).as("%s 别把别人的存档写坏", label).isEmpty();
        }
    }

    @Test
    @DisplayName("未知玩家读到 empty（不是空 State）：新号第一次打开活动页走的是 open 那条路")
    void unknownPlayerIsEmpty() {
        for (ActivityProgressStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            assertThat(store.load("P-never")).as("%s 空对象会让服务以为「这个人有存档」", label).isEmpty();
            assertThat(store.load(null)).as("%s null 玩家不能抛，回 empty", label).isEmpty();
        }
    }

    @Test
    @DisplayName("写侧键校验两套实现都拒：playerId 与 state 不一致会在库里留下永远读不到的行")
    void inconsistentKeyIsRejectedByBothStores() {
        for (ActivityProgressStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            assertThatThrownBy(() -> store.save("P-a", state("P-b", 1L)))
                    .as("%s 必须拒掉不一致的键", label)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不一致");
            assertThatThrownBy(() -> store.save("P-a", null))
                    .as("%s null 状态必须拒", label)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("表里新加一行时，老存档也看得见它：restore 按当前表补齐缺的行（验收 1 的一半）")
    void restoreAddsRowsTheOldSaveDoesNotHave() {
        List<ActivityProgress.Def> defs = List.of(
                new ActivityProgress.Def("activity_login_7d", ActivityType.LOGIN_STREAK,
                        ActivityCondition.LOGIN_DAYS, 7L, 7L),
                new ActivityProgress.Def("activity_new_row", ActivityType.KILL_MONSTER,
                        ActivityCondition.KILL_MONSTER_TOTAL, 50L, 3L));
        // 老存档：只有登录那一行（新行是后来加进表的）
        ActivityProgressStore.State old = new ActivityProgressStore.State("P-old", List.of(
                new ActivityProgress.Entry("activity_login_7d", 1_800_000_000_000L, 3L, false, 0L)),
                1_700_000_000_000L);
        for (ActivityProgressStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.save("P-old", old);
            ActivityProgressStore.State stored = store.load("P-old").orElseThrow();
            ActivityProgress progress = ActivityProgress.restore("P-old", defs, stored.entries(),
                    stored.serverOpenMs());
            assertThat(progress.entry("activity_new_row").value())
                    .as("%s 表里新加的活动对老号必须立刻可见", label).isZero();
            assertThat(progress.entry("activity_login_7d").value())
                    .as("%s 老存档里的进度不能被补齐动作冲掉", label).isEqualTo(3L);
        }
    }

    private static ActivityProgressStore.State state(String playerId, long value) {
        return new ActivityProgressStore.State(playerId,
                List.of(new ActivityProgress.Entry("activity_monster_hunt", 1_800_000_000_000L,
                        value, false, 0L)),
                1_700_000_000_000L);
    }

    private List<ActivityProgressStore> bothStores() {
        requireMongo();
        return List.of(new InMemoryActivityProgressStore(), newMongoStore());
    }

    private static MongoActivityProgressStore newMongoStore() {
        requireMongo();
        return new MongoActivityProgressStore(db.template());
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 见「跳过即未验证」那条");
    }
}
