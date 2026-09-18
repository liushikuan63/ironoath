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

import com.ironoath.core.season.SeasonSettlement;
import com.ironoath.core.season.SeasonSettlement.Board;
import com.ironoath.web.season.SeasonBoardStore;
import com.ironoath.web.store.memory.InMemorySeasonBoardStore;
import com.ironoath.web.store.mongo.MongoSeasonBoardStore;

/**
 * 职责：赛季榜与快照在<b>内存与 Mongo 上必须给出同一个结果</b>（收口清单 #18 的榜与快照档）。
 * 依赖：本机 MongoDB（见 {@link TestMongo}）；连不上时明确报「跳过即未验证」。
 *
 * <p><b>这一档防的是「按一张冷榜结算」</b>：榜与快照原先都在进程里，重启后再点一次结算
 * （换一个 requestId 就能再跑）会从空榜拍一张快照 —— 而快照不可重拍、账本又把这次发奖记成
 * 「已经付过」，于是名次算错的那批人永久拿不到本该属于他的奖励。
 *
 * <p>与账本那份等价测试同一条纪律：真正的价值在「换一个实例」的那条用例 ——
 * 内存版换实例答不出来是<b>事实</b>（不是 bug），把它断言出来，
 * 让「生产必须用 mongo 模式」这句话有一条会红的证据，而不是靠一段注释。
 */
class SeasonBoardStoreEquivalenceTest {

    private static final String SEASON = "season_20260101";
    private static final String OTHER = "season_20260201";
    private static TestMongo db;

    @BeforeAll
    static void connect() {
        db = TestMongo.tryOpen();
    }

    @BeforeEach
    void clearBoard() {
        if (db != null) {
            newMongoStore().clear();
        }
    }

    @AfterAll
    static void disconnect() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("累加型上报两套实现同一条：增量叠加、没有这一行时以增量为初值、负增量被拒")
    void accumulateAddsUpTheSameWayOnBothStores() {
        Assumptions.assumeTrue(db != null,
                "本机连不上 MongoDB（" + TestMongo.uri() + "）：这条等价性今天没被验证，别当成通过");

        for (SeasonBoardStore store : List.of(newMemoryStore(), newMongoStore())) {
            String who = store.getClass().getSimpleName();
            store.accumulate(SEASON, Board.KILL, entry("P-1", "老王", 10), 10);
            store.accumulate(SEASON, Board.KILL, entry("P-1", "老王", 5), 5);
            store.accumulate(SEASON, Board.KILL, entry("P-2", "小李", 99), 99);

            List<SeasonSettlement.Entry> rows = store.board(SEASON, Board.KILL);
            assertThat(rows).as(who + " 两个人各一行").hasSize(2);
            assertThat(rows.get(0).id()).as(who + " 99 > 15，降序").isEqualTo("P-2");
            assertThat(rows.get(0).score()).as(who + " 没有这一行时以增量为初值").isEqualTo(99L);
            assertThat(rows.get(1).score()).as(who + " 10 + 5 = 15").isEqualTo(15L);

            // 负增量是调用方的 bug，不是"扣分"：报错而不是静默减分。
            // 没有这条断言的话，"实现里漏了 delta < 0 的检查"会以「扣了一次分」的形态悄悄上线
            assertThatThrownBy(() -> store.accumulate(SEASON, Board.KILL, entry("P-1", "老王", -1), -1))
                    .as(who + " 负增量必须被拒").isInstanceOf(IllegalArgumentException.class);
            assertThat(store.board(SEASON, Board.KILL).get(1).score())
                    .as(who + " 被拒的那次没有扣到数").isEqualTo(15L);

            // 与 report 是两种语义：report 覆盖、accumulate 叠加 —— 同同名次下发奖依赖这条
            store.report(SEASON, Board.KILL, entry("P-3", "老张", 7));
            store.report(SEASON, Board.KILL, entry("P-3", "老张", 3));
            assertThat(store.board(SEASON, Board.KILL).stream()
                    .filter(e -> e.id().equals("P-3")).findFirst().orElseThrow().score())
                    .as(who + " report 是覆盖（3 而不是 10）").isEqualTo(3L);
        }
    }

    @Test
    @DisplayName("两个实现：上报同一份榜得到同一个顺序、同一个名次（同分按 id 升序）")
    void bothImplementationsAgreeOnOrderAndRank() {
        Assumptions.assumeTrue(db != null,
                "本机连不上 MongoDB（" + TestMongo.uri() + "）：这条等价性今天没被验证，别当成通过");

        for (SeasonBoardStore store : List.of(newMemoryStore(), newMongoStore())) {
            String who = store.getClass().getSimpleName();
            store.report(SEASON, Board.POWER, entry("c", "丙", 100));
            store.report(SEASON, Board.POWER, entry("a", "甲", 300));
            store.report(SEASON, Board.POWER, entry("b", "乙", 100));
            // 重复上报只覆盖自己那一行（分数是累计量，不累加）
            store.report(SEASON, Board.POWER, entry("c", "丙", 250));
            // 另一季的人不该混进来
            store.report(OTHER, Board.POWER, entry("z", "别季", 9999));
            store.report(SEASON, Board.KILL, entry("k", "击杀榜", 9999));

            assertThat(store.board(SEASON, Board.POWER)).as(who + " 的榜顺序")
                    .extracting(SeasonSettlement.Entry::id).containsExactly("a", "c", "b");
            assertThat(store.rankOf(SEASON, Board.POWER, "a")).as(who + " 的第一名").isEqualTo(1);
            assertThat(store.rankOf(SEASON, Board.POWER, "c")).as(who + " 的同分排序").isEqualTo(2);
            assertThat(store.rankOf(SEASON, Board.POWER, "b")).as(who + " 的第三名").isEqualTo(3);
            assertThat(store.rankOf(SEASON, Board.POWER, "z"))
                    .as(who + " 的别季玩家不在本季榜上").isZero();
            assertThat(store.rankOf(SEASON, Board.POWER, "nobody"))
                    .as(who + " 的未上榜玩家").isZero();
        }
    }

    @Test
    @DisplayName("两个实现：快照只能存一份，第二份被拒绝（快照不可更改）")
    void snapshotIsWriteOnceInBothImplementations() {
        Assumptions.assumeTrue(db != null,
                "本机连不上 MongoDB（" + TestMongo.uri() + "）：这条等价性今天没被验证，别当成通过");

        for (SeasonBoardStore store : List.of(newMemoryStore(), newMongoStore())) {
            String who = store.getClass().getSimpleName();
            SeasonSettlement.Snapshot first = new SeasonSettlement.Snapshot(Board.POWER, 111L,
                    List.of(entry("a", "甲", 300)));
            SeasonSettlement.Snapshot second = new SeasonSettlement.Snapshot(Board.POWER, 222L,
                    List.of(entry("b", "乙", 999)));

            assertThat(store.saveSnapshotIfAbsent(SEASON, first)).as(who + " 第一份").isTrue();
            assertThat(store.saveSnapshotIfAbsent(SEASON, second)).as(who + " 第二份必须被拒绝").isFalse();
            assertThat(store.snapshot(SEASON, Board.POWER).snapshotAt())
                    .as(who + " 读回来的仍是第一份").isEqualTo(111L);
            assertThat(store.snapshot(SEASON, Board.POWER).rankOf("a")).isEqualTo(1);
            assertThat(store.snapshot(OTHER, Board.POWER)).as(who + " 别季没有快照").isNull();
        }
    }

    @Test
    @DisplayName("归档清理：删一季要连榜带快照一起删，而另一季一条都不能少")
    void purgeSeasonRemovesBoardAndSnapshotButLeavesOtherSeasons() {
        Assumptions.assumeTrue(db != null,
                "本机连不上 MongoDB（" + TestMongo.uri() + "）：这条等价性今天没被验证，别当成通过");

        for (SeasonBoardStore store : List.of(newMemoryStore(), newMongoStore())) {
            String who = store.getClass().getSimpleName();
            store.report(SEASON, Board.POWER, entry("a", "甲", 300));
            store.report(SEASON, Board.KILL, entry("k", "击杀", 7));
            store.saveSnapshotIfAbsent(SEASON,
                    new SeasonSettlement.Snapshot(Board.POWER, 123L, List.of(entry("a", "甲", 300))));
            // 另一季必须有内容：少了这一行，"实现按前缀删、把邻季一起删了"这种错法照样全绿
            store.report(OTHER, Board.POWER, entry("z", "别季", 9999));
            store.saveSnapshotIfAbsent(OTHER,
                    new SeasonSettlement.Snapshot(Board.POWER, 456L, List.of(entry("z", "别季", 9999))));

            assertThat(store.purgeSeason(SEASON))
                    .as(who + " 要报告删了多少（榜 2 条 + 快照 1 份）").isEqualTo(3);
            assertThat(store.board(SEASON, Board.POWER)).as(who + " 的榜已空").isEmpty();
            assertThat(store.board(SEASON, Board.KILL)).as(who + " 的另一张榜也要一起删").isEmpty();
            assertThat(store.snapshot(SEASON, Board.POWER))
                    .as(who + " 的快照不能留在库里，否则申诉时还能查出一个「榜已经没了的赛季」")
                    .isNull();
            assertThat(store.board(OTHER, Board.POWER)).as(who + " 不许碰别的赛季")
                    .extracting(SeasonSettlement.Entry::id).containsExactly("z");
            assertThat(store.snapshot(OTHER, Board.POWER)).as(who + " 的邻季快照也要原样在").isNotNull();
            assertThat(store.purgeSeason(SEASON)).as(who + " 重复清理必须是 0").isZero();
        }
    }

    @Test
    @DisplayName("换一个实例：Mongo 版答得出榜与快照，内存版答不出来 —— 生产必须用 mongo 的那条证据")
    void mongoSurvivesANewInstanceWhileMemoryDoesNot() {
        Assumptions.assumeTrue(db != null,
                "本机连不上 MongoDB（" + TestMongo.uri() + "）：这条等价性今天没被验证，别当成通过");

        MongoSeasonBoardStore first = newMongoStore();
        first.report(SEASON, Board.POWER, entry("a", "甲", 300));
        first.saveSnapshotIfAbsent(SEASON, new SeasonSettlement.Snapshot(Board.POWER, 111L,
                List.of(entry("a", "甲", 300))));

        MongoSeasonBoardStore reopened = newMongoStore();
        assertThat(reopened.rankOf(SEASON, Board.POWER, "a")).as("重启后榜还在").isEqualTo(1);
        assertThat(reopened.snapshot(SEASON, Board.POWER)).as("重启后快照还在（结算依据不重拍）")
                .isNotNull();

        // 内存版换实例就是重启：两样都答不出来。断言它是为了把「必须 mongo」写成会红的判据
        InMemorySeasonBoardStore memory = newMemoryStore();
        memory.report(SEASON, Board.POWER, entry("a", "甲", 300));
        InMemorySeasonBoardStore restarted = new InMemorySeasonBoardStore();
        assertThat(restarted.board(SEASON, Board.POWER)).as("内存版重启即空（事实，不是 bug）").isEmpty();
        assertThat(restarted.snapshot(SEASON, Board.POWER)).isNull();
    }

    @Test
    @DisplayName("空键必须当场拒绝：否则会在存储里造出永远命不中的孤儿行")
    void blankKeysAreRejected() {
        Assumptions.assumeTrue(db != null,
                "本机连不上 MongoDB（" + TestMongo.uri() + "）：这条等价性今天没被验证，别当成通过");

        for (SeasonBoardStore store : List.of(newMemoryStore(), newMongoStore())) {
            String who = store.getClass().getSimpleName();
            assertThatThrownBy(() -> store.report("", Board.POWER, entry("a", "甲", 1)))
                    .as(who + " 空 seasonId").isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.report(SEASON, null, entry("a", "甲", 1)))
                    .as(who + " 空 board").isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.board(SEASON, null))
                    .as(who + " 读也拒绝空 board").isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---------- 夹具 ----------

    private static InMemorySeasonBoardStore newMemoryStore() {
        return new InMemorySeasonBoardStore();
    }

    private static MongoSeasonBoardStore newMongoStore() {
        return new MongoSeasonBoardStore(db.template());
    }

    private static SeasonSettlement.Entry entry(String id, String name, long score) {
        return new SeasonSettlement.Entry(id, name, score);
    }
}
