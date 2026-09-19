package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardType;
import com.ironoath.web.reward.RewardCompensationStore;
import com.ironoath.web.reward.RewardCompensationStore.Entry;
import com.ironoath.web.store.memory.InMemoryRewardCompensationStore;
import com.ironoath.web.store.mongo.MongoRewardCompensationStore;

/**
 * 职责：补偿台账在<b>内存版与 Mongo 上必须给出同一个结果</b>，重点是「重启之后还查得到」这一件。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）；连不上时明确报"跳过即未验证"。
 *
 * <p><b>为什么这张表必须有等价测试</b>：它的全部内容就是"将来有人要拿着它补发"。
 * 内存版把同一实例直接交回去，所以"字段没落库""顺序反了""已被处理的又被盖回未处理"这三件事
 * <b>在内存版一条都测不出来</b>，而它们在 Mongo 上的表现是运维面板上一行读不出明细的空记录。
 * 所以本类所有读都重新走一遍 {@code store.pending(...)} / {@code findById(...)}，
 * 绝不复用写进去的那个对象；而 {@link #aSecondInstanceSeesWhatTheFirstWroteOnlyOnMongo}
 * 故意把两侧的不对称<b>断言出来</b> —— 一份两边都恒绿的等价测试等于没测。
 */
class RewardCompensationStoreEquivalenceTest {

    private static final long T0 = 1_800_000_000_000L;
    private static TestMongo db;

    @BeforeAll
    static void connect() {
        db = TestMongo.tryOpen();
    }

    /** Mongo 库整个类共享、内存版每条用例新建 —— 不清理会把上一条用例的欠账算进待处理总数。 */
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
    @DisplayName("待处理列表：两侧同为最旧在前、同一条 limit 语义、同一个总数")
    void pendingListIsOrderedAndCappedIdentically() {
        for (RewardCompensationStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.save(entry("comp_c", "P-c", T0 + 20L));
            store.save(entry("comp_a", "P-a", T0));
            store.save(entry("comp_b", "P-b", T0 + 10L));

            List<Entry> all = store.pending(10);
            assertThat(all.stream().map(Entry::compensationId).toList())
                    .as("%s 最旧的在前（先欠先处理）", label).containsExactly("comp_a", "comp_b", "comp_c");
            assertThat(store.pending(2).stream().map(Entry::compensationId).toList())
                    .as("%s limit 截的是最旧的那几笔，不是随便两笔", label).containsExactly("comp_a", "comp_b");
            assertThat(store.countPending()).as("%s 总数不受 limit 影响", label).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("明细与枚举在 Mongo 上原样读回：type/id/count 一项不少、顺序不变")
    void failedItemsSurviveTheRoundTrip() {
        for (RewardCompensationStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.save(new Entry("comp_items", "P-items",
                    List.of(new RewardItem(RewardType.ITEM, "potion_big", 3L),
                            new RewardItem(RewardType.RESOURCE, "GOLD", 1_200_000L),
                            new RewardItem(RewardType.HERO_FRAGMENT, "hero_ssr_01", 10L)),
                    "quest", "quest_main_07", "trace-items", "背包已满", T0, null, null, null));

            Entry read = store.findById("comp_items").orElseThrow();
            assertThat(read.failed()).as("%s 三项欠账都在", label).hasSize(3);
            assertThat(read.failed().get(1)).as("%s 数量是 64 位整数，聚合不回小数", label)
                    .isEqualTo(new RewardItem(RewardType.RESOURCE, "GOLD", 1_200_000L));
            assertThat(read.failed().get(2).type()).as("%s 枚举读回仍是枚举", label)
                    .isEqualTo(RewardType.HERO_FRAGMENT);
            assertThat(read.sourceRef()).as("%s 来源引用跟着记录走", label).isEqualTo("quest_main_07");
        }
    }

    @Test
    @DisplayName("销账只有第一次算成功，重复处理不覆盖先写的那份说明")
    void resolveIsWonOnlyByTheFirstCaller() {
        for (RewardCompensationStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.save(entry("comp_once", "P-once", T0));

            assertThat(store.resolve("comp_once", "客服甲", "mail_111", T0 + 60_000L))
                    .as("%s 第一次翻成已处理", label).isTrue();
            assertThat(store.resolve("comp_once", "客服乙", "mail_222", T0 + 90_000L))
                    .as("%s 第二个人拿 false，才知道自己那封补发邮件是重复的", label).isFalse();

            Entry read = store.findById("comp_once").orElseThrow();
            assertThat(read.resolvedBy()).as("%s 处理人是最先那位，不被后一次覆盖", label).isEqualTo("客服甲");
            assertThat(read.resolution()).as("%s 凭证是最先那封邮件", label).isEqualTo("mail_111");
            assertThat(read.pending()).as("%s 已处理不再是待处理", label).isFalse();
            assertThat(store.countPending()).as("%s 销完待处理归零", label).isZero();
            assertThat(store.pending(10)).as("%s 待处理列表里不许还挂着它", label).isEmpty();
        }
    }

    @Test
    @DisplayName("同一 id 重复记账不覆盖：已销的那条不会被一次重投盖回未处理")
    void reSavingAnExistingIdNeverRewritesIt() {
        for (RewardCompensationStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.save(entry("comp_dup", "P-dup", T0));
            assertThat(store.resolve("comp_dup", "客服甲", "mail_111", T0 + 60_000L))
                    .as("%s 先销账", label).isTrue();

            store.save(entry("comp_dup", "P-dup", T0));

            Entry read = store.findById("comp_dup").orElseThrow();
            assertThat(read.resolvedAt()).as("%s 重投把已处理的记录盖回未处理 = 悄悄抹掉别人的处理记录", label)
                    .isNotNull();
            assertThat(store.countPending()).as("%s 台账不会被重投重新填满", label).isZero();
        }
    }

    @Test
    @DisplayName("换一个实例（= 重启）：内存版复活成空表、Mongo 版仍读得到同一笔欠账")
    void aSecondInstanceSeesWhatTheFirstWroteOnlyOnMongo() {
        requireMongo();

        InMemoryRewardCompensationStore memory = new InMemoryRewardCompensationStore();
        memory.save(entry("comp_restart_mem", "P-restart-mem", T0));
        assertThat(new InMemoryRewardCompensationStore().countPending())
                .as("内存版换一个实例就是空表 —— 这条断言是故意把两侧不对称写出来，"
                        + "它同时证明本类的 Mongo 那半不是靠共享对象蒙对的")
                .isZero();
        assertThat(memory.countPending()).as("原实例自己当然还看得见").isEqualTo(1);

        MongoRewardCompensationStore first = newMongoStore();
        first.save(entry("comp_restart", "P-restart", T0));
        RewardCompensationStore second = newMongoStore();
        assertThat(second.pending(10).stream().map(Entry::compensationId).toList())
                .as("重启后运维仍然查得到这笔欠账 —— 整档改造的全部目的")
                .containsExactly("comp_restart");
        assertThat(second.findById("comp_restart").orElseThrow().failed())
                .as("明细也回来了，不剩一个空壳")
                .isNotEmpty();
    }

    @Test
    @DisplayName("空明细与空处理人：两侧同一个异常同一句话（同一次错误不许有两种说法）")
    void malformedWritesAreRejectedIdentically() {
        for (RewardCompensationStore store : bothStores()) {
            String label = store.getClass().getSimpleName();

            assertThatThrownBy(() -> new Entry("comp_empty", "P-empty", List.of(),
                    "quest", "", "trace-empty", "", T0, null, null, null))
                    .as("%s 欠 0 件的记录建不出来", label)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("欠 0 件");

            assertThatThrownBy(() -> new Entry("comp_no_who", "P-no-who",
                    List.of(new RewardItem(RewardType.ITEM, "potion", 1L)),
                    "quest", "", "trace-no-who", "", T0, T0 + 1L, " ", null))
                    .as("%s 已处理却没写处理人：建不出来", label)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("处理人");

            store.save(entry("comp_actor", "P-actor", T0));
            assertThatThrownBy(() -> store.resolve("comp_actor", "  ", "mail_1", T0 + 1L))
                    .as("%s 销账时不写处理人一律拒绝", label)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("处理人");
            assertThat(store.findById("comp_actor").orElseThrow().pending())
                    .as("%s 被拒的销账不许留下半处理状态", label).isTrue();
        }
    }

    // ---------- 夹具 ----------

    private List<RewardCompensationStore> bothStores() {
        requireMongo();
        List<RewardCompensationStore> both = new ArrayList<>();
        both.add(new InMemoryRewardCompensationStore());
        both.add(newMongoStore());
        return both;
    }

    private static MongoRewardCompensationStore newMongoStore() {
        return new MongoRewardCompensationStore(db.template());
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "跳过即未验证：补偿台账的内存/Mongo 等价性没有被检查。"
                        + "补跑方式：起一个本地 MongoDB，或 -Dironoath.test.mongo.uri=... 指向一台");
    }

    private static Entry entry(String compensationId, String playerId, long createdAt) {
        return new Entry(compensationId, playerId,
                List.of(new RewardItem(RewardType.ITEM, "potion_big", 2L)),
                "quest", "quest_main_02", "trace_" + compensationId, "背包已满", createdAt, null, null, null);
    }
}
