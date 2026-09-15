package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.web.mail.MailStore;
import com.ironoath.web.mail.MailStore.MailRecord;
import com.ironoath.web.mail.MailStore.RewardLine;
import com.ironoath.web.store.memory.InMemoryMailStore;
import com.ironoath.web.store.mongo.MailDocument;
import com.ironoath.web.store.mongo.MongoMailStore;

/**
 * 职责：邮件存储在<b>内存与 Mongo 上必须给出同一个结果</b>（B12 §2 的存储档）。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）；连不上时明确报「跳过即未验证」。
 *
 * <p><b>这一档两侧最容易走岔的是「领取」</b>：内存版一把进程内锁就够，Mongo 版要靠条件更新。
 * 两侧只要有一侧的语义理解错，症状都不是报错而是<b>重复发奖</b>或<b>领了却还能再领</b>。
 * 所以这里逐条钉：只有第一次 claim 返回 true、release 之后可以再来、
 * 过期那封两侧都拒、已读过再标两侧都回 true、排序 tie-break 同一条、清理边界同为
 * {@code expireAt <= now}，以及异常文本两侧一致（读日志的人不该看到两种说法）。
 */
class MailStoreEquivalenceTest {

    private static final long T0 = 1_800_000_000_000L;
    private static final long DAY = 86_400_000L;
    private static TestMongo db;

    @BeforeAll
    static void connect() {
        db = TestMongo.tryOpen();
    }

    @BeforeEach
    void clearMails() {
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
    @DisplayName("领取是恰好一次：第二次 claim 两侧都回 false，release 之后又回 true")
    void claimIsExactlyOnceOnBothStores() {
        for (MailStore store : bothStores()) {
            String label = label(store);
            store.save(mail("m-once", "P-1", gold(5L)));

            assertThat(store.claim("P-1", "m-once", T0)).as("%s 第一次必须赢下领取权", label).isTrue();
            assertThat(store.claim("P-1", "m-once", T0)).as("%s 第二次不能再发一遍", label).isFalse();
            assertThat(store.findById("P-1", "m-once").orElseThrow().claimedAt())
                    .as("%s 领过要留时刻而不是删记录", label).isEqualTo(T0);

            assertThat(store.releaseClaim("P-1", "m-once")).as("%s 退回成功", label).isTrue();
            assertThat(store.claim("P-1", "m-once", T0 + 1L)).as("%s 退回后可以再领", label).isTrue();
            assertThat(store.releaseClaim("P-1", "m-none")).as("%s 没这一封就退回失败", label).isFalse();
        }
    }

    @Test
    @DisplayName("过期或没有附件的邮件不许被领走；别人的邮件不是我的（两侧同一条）")
    void nothingClaimableIsClaimedOnBothStores() {
        for (MailStore store : bothStores()) {
            String label = label(store);
            store.save(new MailRecord("m-expired", "P-1", "SYSTEM", "过期那封", "正文",
                    List.of(gold(1L)), "test", T0 - DAY, T0, null, null));
            store.save(new MailRecord("m-noattach", "P-1", "SYSTEM", "纯公告", "正文",
                    List.of(), "test", T0 - DAY, T0 + DAY, null, null));
            store.save(mail("m-other", "P-2", gold(1L)));

            assertThat(store.claim("P-1", "m-expired", T0)).as("%s 过期不能领", label).isFalse();
            assertThat(store.claim("P-1", "m-noattach", T0))
                    .as("%s 没附件也没什么可领（它不该进 failed，也不该被标已领）", label).isFalse();
            assertThat(store.claim("P-1", "m-other", T0)).as("%s 跨玩家不能领", label).isFalse();
            assertThat(store.claim("P-1", "m-absent", T0)).as("%s 不存在同样回 false", label).isFalse();
            assertThat(store.findById("P-1", "m-other")).as("%s 按玩家查不到别人的", label).isEmpty();
        }
    }

    @Test
    @DisplayName("已读：第一次盖上时刻、重复标仍回 true 且时刻不动、不存在回 false（两侧同一条）")
    void markReadMatchesOnBothStores() {
        for (MailStore store : bothStores()) {
            String label = label(store);
            store.clear();
            store.save(mail("m-read", "P-1", gold(1L)));

            assertThat(store.markRead("P-1", "m-read", T0 + 10L)).as("%s 第一次标成功", label).isTrue();
            assertThat(store.findById("P-1", "m-read").orElseThrow().readAt())
                    .as("%s 盖上的是那一刻", label).isEqualTo(T0 + 10L);
            assertThat(store.markRead("P-1", "m-read", T0 + 99L)).as("%s 重复标仍算成功", label).isTrue();
            assertThat(store.findById("P-1", "m-read").orElseThrow().readAt())
                    .as("%s 但已读时刻不许被第二次点击刷新（红点计数靠它）", label).isEqualTo(T0 + 10L);
            assertThat(store.markRead("P-1", "m-none", T0)).as("%s 没这一封回 false", label).isFalse();
            assertThat(store.markRead("P-2", "m-read", T0)).as("%s 别人不能替我读", label).isFalse();
        }
    }

    @Test
    @DisplayName("排序与清理两侧逐键一致：createdAt 倒序、同刻按 mailId、边界含整点")
    void orderingAndPurgeMatchOnBothStores() {
        for (MailStore store : bothStores()) {
            String label = label(store);
            store.clear();
            store.save(mailAt("m-b", "P-1", T0 - DAY, T0 + DAY));
            store.save(mailAt("m-a", "P-1", T0 - DAY, T0 + 2 * DAY));      // 与 m-b 同一 createdAt
            store.save(mailAt("m-old", "P-1", T0 - 9 * DAY, T0));          // 正好到期 → 算过期
            store.save(mailAt("m-edge", "P-1", T0 - 9 * DAY, T0 + 1L));

            assertThat(store.listOf("P-1", T0)).as("%s 新的在前，同刻按 mailId 升序定序", label)
                    .extracting(MailRecord::mailId)
                    .containsExactly("m-a", "m-b", "m-edge");
            assertThat(store.purgeExpired(T0)).as("%s 清掉正好到期那一封", label).isEqualTo(1);
            assertThat(store.listOf("P-1", T0)).as("%s 剩三封且差一毫秒的那封还在", label).hasSize(3);
            assertThat(store.count()).as("%s 库里总数跟着降", label).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("同一封重复写入是幂等而不是覆盖；两套实现都保留第一份")
    void duplicateMailIdKeepsTheFirstOnBothStores() {
        for (MailStore store : bothStores()) {
            String label = label(store);
            store.clear();
            store.save(mail("m-dup", "P-1", gold(1L)));
            store.save(new MailRecord("m-dup", "P-1", "SYSTEM", "第二次的标题", "改了正文",
                    List.of(gold(999L)), "test", T0, T0 + DAY, null, null));

            MailRecord kept = store.findById("P-1", "m-dup").orElseThrow();
            assertThat(kept.title()).as("%s 覆盖等于把已经承诺过的那封改掉", label).isEqualTo("测试邮件");
            assertThat(kept.rewards()).as("%s 附件也必须还是第一份", label)
                    .singleElement().extracting(RewardLine::count).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("脏数据两侧的拒绝文本逐字一致：读日志的人不该为同一件事看到两种说法")
    void invalidRecordsFailTheSameWayOnBothStores() {
        for (MailStore store : bothStores()) {
            String label = label(store);
            assertThatThrownBy(() -> store.save(new MailRecord("m-bad", "P-1", "SYSTEM", "标题",
                    "正文", List.of(), "test", T0, T0 - 1L, null, null)))
                    .as("%s 过期早于生成必须被拒", label)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expireAt 必须晚于 createdAt");
            assertThatThrownBy(() -> store.save(new MailRecord("m-bad", " ", "SYSTEM", "标题",
                    "正文", List.of(), "test", T0, T0 + DAY, null, null)))
                    .as("%s 空玩家 id 也要拒，而且文本与 Mongo 侧同一个构造器来的", label)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("playerId 不得为空");
            assertThatThrownBy(() -> store.save(mail("m-zero", "P-1",
                    new RewardLine("RESOURCE", "GOLD", 0L, "金币"))))
                    .as("%s 0 数量在入库存的时候就该拒", label)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("附件 count 必须为正");
        }
    }

    @Test
    @DisplayName("三处判据要走的索引必须存在：收件箱是每次进面板都发的查询")
    void mailCollectionsHaveTheirIndexes() {
        requireMongo();
        newMongoStore().clear();
        List<String> keys = new java.util.ArrayList<>();
        db.template().getCollection(MailDocument.COLLECTION).listIndexes()
                .forEach(info -> keys.add(info.get("key").toString()));
        assertThat(keys).as("mail 现有索引：" + keys)
                .anySatisfy(key -> assertThat(key).contains("playerId").contains("mail.createdAt"))
                .anySatisfy(key -> assertThat(key).contains("expireAt"));
    }

    // ---------- 夹具 ----------

    private static String label(MailStore store) {
        return store.getClass().getSimpleName();
    }

    private List<MailStore> bothStores() {
        requireMongo();
        return List.of(new InMemoryMailStore(), newMongoStore());
    }

    private static MongoMailStore newMongoStore() {
        requireMongo();
        return new MongoMailStore(db.template());
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 跳过即未验证："
                        + "邮件存储的内存/Mongo 等价性没有被检查");
    }

    private static RewardLine gold(long count) {
        return new RewardLine("RESOURCE", "GOLD", count, "金币");
    }

    private static MailRecord mail(String mailId, String playerId, RewardLine... rewards) {
        return new MailRecord(mailId, playerId, "SYSTEM", "测试邮件", "正文",
                List.of(rewards), "test:" + mailId, T0, T0 + 30 * DAY, null, null);
    }

    private static MailRecord mailAt(String mailId, String playerId, long createdAt, long expireAt) {
        return new MailRecord(mailId, playerId, "SYSTEM", "测试邮件", "正文",
                List.of(gold(1L)), "test:" + mailId + UUID.randomUUID().toString().substring(0, 4),
                createdAt, expireAt, null, null);
    }
}
