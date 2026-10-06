package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
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

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.nation.WarStore;
import com.ironoath.web.store.memory.InMemoryWarStore;
import com.ironoath.web.store.mongo.MongoWarStore;

/**
 * 职责：国战存储在<b>内存与真实 Mongo 上必须给出同一个结果</b>（B21 验收 9 的那一档）。
 * 依赖：真实配置表（规则来自 global 的 WAR_* 那九行）+ 本机 MongoDB（见 {@link TestMongo}）。
 *
 * <p><b>本类真正的靶心是「快照少带一项」</b>：{@code WarScoreBoard} 的落盘形状是新加的
 * （此前该类在 core 之外零引用，{@code restore(...)} 十个参数没有任何调用者，等于从没被测过往返）。
 * 少带一项的表现不是报错，而是<b>复活出一个假状态</b>，逐条对应下面的具名用例：
 * 少 {@code fatigue} 就是「疲劳上限每次重启清零」（验收 7 作废），
 * 少 {@code goalClaimed} 就是「重启就能再领一次」（验收 10 作废，且是刷金入口），
 * 少 {@code capitalHeldSince} 就是同一段占领被重新累积一遍（占领分翻倍或归零）。
 * 每条断言都写成<b>能从正反两面失败</b>：把对应字段从 {@code Snapshot} 里删掉，那一条必须变红。
 *
 * <p>另一族是<b>两套实现同源</b>：{@code findLatest} 若内存版按插入顺序取最后一条、Mongo 按
 * {@code startedAt} 排序，dev 全绿而生产给出另一场仗 —— {@link #findLatestPicksNewestStartedNotLastInserted}
 * 专拦这一条。
 *
 * <p><b>切片 2a 起本类还守第二条不变量：全服同时只有一场未结束的仗</b>
 * （{@link #onlyOneOpenWarSurvivesTheRaceOnBothStores}）。它是<b>多线程</b>用例：
 * 单线程的那几条挡不住「先查后插」这种写法 —— 两个国王各自拿着一把按玩家分的锁，
 * 同一秒宣战会插出两场平行账，而两份击杀与占领分互不可见且不报错。
 */
class WarStoreEquivalenceTest {

    private static final long T0 = 1_800_000_000_000L;
    private static final long MINUTE = 60_000L;

    private static ConfigRegistry configs;
    private static WarRulesAssembler rules;
    private static TestMongo db;

    @BeforeAll
    static void setUp() {
        configs = ConfigRegistry.loadFromDirectory(locateConfigDir());
        rules = new WarRulesAssembler(configs);
        db = TestMongo.tryOpen();
    }

    @BeforeEach
    void clearWars() {
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

    // ---------- 副本语义 ----------

    @Test
    @DisplayName("读出来的是副本：改了不 save，两套实现都必须看不到那笔改动")
    void readReturnsADetachedCopyOnBothStores() {
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfAbsent(richBoard(T0));
            // 直接在内核对象上做一件会改状态的事：击杀 + 疲劳
            store.findLatest().orElseThrow().recordKill("n1", 1L);
            store.findLatest().orElseThrow().addFatigue("P1", 1L, 0L);

            WarScoreBoard back = store.findLatest().orElseThrow();
            assertThat(back.totalKills())
                    .as("%s 没有写回就不该看见这次击杀（内存版返回活对象时这条永远测不出来）", label)
                    .isEqualTo(60_120L);
            assertThat(back.fatigueOf("P1"))
                    .as("%s 疲劳同理：改了不 save 就不生效，dev 与生产必须同一条", label)
                    .isEqualTo(80L);
        }
    }

    // ---------- 往返完整性（本类的靶心）----------

    @Test
    @DisplayName("完整快照往返：九项状态逐字段相同，内存与 Mongo 给出同一份描述")
    void fullStateSurvivesRoundTripOnBothStores() {
        WarScoreBoard source = richBoard(T0);
        String expected = describe(source);
        assertThat(expected).as("夹具本身要够丰富，否则这条断言是空的").contains("n1=0,60000,100");

        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfAbsent(source);

            WarScoreBoard back = store.findLatest().orElseThrow();
            assertThat(describe(back))
                    .as("%s 落盘再读回必须与写进去的那一份逐字段相同 —— 少带任一项都是「复活出一个假状态」", label)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("占领起点必须活过落盘：重启后结算占领分不能被重算或清零（验收 6 的防偷家机制）")
    void occupationStartSurvivesRoundTripOnBothStores() {
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfAbsent(richBoard(T0));   // n1 从 T0+7min 起占着王城

            WarScoreBoard back = store.findLatest().orElseThrow();
            // 换手的瞬间会结算上一段：从 T0+7min 到 T0+8min 正好 1 分钟 ⇒ 占领分 +1×10
            back.captureCapital("n2", T0 + 8 * MINUTE);
            WarScoreBoard.Score n1 = back.snapshot().get("n1");
            assertThat(n1.occupyScore())
                    .as("%s 占领起点丢了会算出一个巨大的分钟数（落回 0），挪到 startedAt 会算成 8 分钟（80 分），"
                            + "两者都该红而不该静默", label)
                    .isEqualTo(10L);
        }
    }

    @Test
    @DisplayName("疲劳表必须活过落盘：到顶的人重启后仍然不能行军（验收 7）")
    void fatigueCeilingSurvivesRoundTripOnBothStores() {
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfAbsent(richBoard(T0));   // P2 = 25 次行军 → 125 被夹到上限 100

            WarScoreBoard back = store.findLatest().orElseThrow();
            assertThat(back.fatigueOf("P2"))
                    .as("%s 疲劳表没落盘的话读回来是 0，上限形同不存在", label).isEqualTo(100L);
            assertThat(back.canMarch("P2"))
                    .as("%s 到顶的人必须读回来仍然不能行军", label).isFalse();
            assertThat(back.canMarch("P1"))
                    .as("%s 没到顶的人不该被误锁（P1=80/100）", label).isTrue();
        }
    }

    @Test
    @DisplayName("领取名单必须活过落盘：已领过的人重启后再领一次要失败（验收 10，防刷金）")
    void goalClaimedListSurvivesRoundTripOnBothStores() {
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfAbsent(richBoard(T0));   // 已达成目标，P1 与 P2 都已领过

            WarScoreBoard back = store.findLatest().orElseThrow();
            assertThat(back.serverGoalReached())
                    .as("%s 前置：目标确实已达成（击杀 60120 ≥ 50000）", label).isTrue();
            assertThat(back.claimServerGoal("P1"))
                    .as("%s 名单没落盘就等于「重启就能再领一次」—— 那是国战期间的刷金入口", label).isFalse();
            assertThat(back.claimServerGoal("P3"))
                    .as("%s 没领过的人必须还能领，否则上一条会退化成「谁都领不到」的假绿", label).isTrue();
            assertThat(back.serverGoalClaimed())
                    .as("%s 领取人数 = 名单落回的那两人 + 新领的一人", label).isEqualTo(3);
        }
    }

    // ---------- 单场不变量（切片 2a 宣战的存储侧）----------

    @Test
    @DisplayName("未结束的仗只允许一场：第二场必须 false，且不许动第一份")
    void insertIfNoneActiveKeepsTheOneOpenWarOnBothStores() {
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            assertThat(store.insertIfNoneActive(richBoard(T0)))
                    .as("%s 第一场仗应当开起来", label).isTrue();

            assertThat(store.insertIfNoneActive(richBoard(T0 + 30 * MINUTE)))
                    .as("%s 已有未结束的一场时，第二场必须开不起来", label).isFalse();

            WarScoreBoard back = store.findLatest().orElseThrow();
            assertThat(back.startedAt())
                    .as("%s 被拒的那一次不许留下半个写入，也不许把当前这场挪走", label)
                    .isEqualTo(T0);
            assertThat(describe(back))
                    .as("%s 第一场的积分与名单必须一字不变", label)
                    .isEqualTo(describe(richBoard(T0)));
        }
    }

    @Test
    @DisplayName("结算之后才允许开下一场：历史那一场留着，不当成当前仗")
    void settledWarOpensTheDoorToTheNextOneOnBothStores() {
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfNoneActive(richBoard(T0));
            WarScoreBoard first = store.findLatest().orElseThrow();
            first.settle(T0 + 10 * MINUTE);
            store.save(first);

            assertThat(store.insertIfNoneActive(richBoard(T0 + 20 * MINUTE)))
                    .as("%s 上一场已经 SETTLED，下一场就该开得起来", label).isTrue();
            assertThat(store.findLatest().orElseThrow().startedAt())
                    .as("%s 读端点给的必须是新那一场，而不是结算完的历史", label)
                    .isEqualTo(T0 + 20 * MINUTE);
        }
    }

    /**
     * 两个国王在同一秒各自宣战。<b>这条才是"单场"这句话的真判据</b>：
     * 上面两条都是单线程，把 {@code InMemoryWarStore.insertIfNoneActive} 的 {@code synchronized} 去掉、
     * 或把 Mongo 版写成「先 findLatest 判空再 insert」，它们照样全绿 ——
     * 而生产上那两次请求真的会并发（{@code PlayerLock} 是按玩家的，两把锁互不相干）。
     *
     * <p><b>为什么要跑 15 轮</b>：单次竞态的窗口太窄，本地 mongod 上一次「查 + 插」只要几百微秒，
     * 一条只跑一次的用例会因为"没撞上"而假绿。轮数不是越多越好 —— 这里是把<b>判据换成可统计的量</b>：
     * 记下"两个都 true"的轮数并要求它为 0，撞上一次就红，而不是靠某一次的运气。
     * （实测：只跑一轮时，去掉两处临界区保护的本子照样 12 条全绿 —— 那条判据当时是假的。）
     */
    @Test
    @DisplayName("同一秒两个人抢着开战：15 轮里每轮恰好一个赢（内存与 Mongo 同一条）")
    void onlyOneOpenWarSurvivesTheRaceOnBothStores() throws Exception {
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                int bothWon = 0;
                for (int round = 0; round < 15; round++) {
                    store.clear();
                    final long firstStartedAt = T0 + round;
                    final long secondStartedAt = T0 + 1_000L + round;
                    CountDownLatch go = new CountDownLatch(1);
                    Future<Boolean> a = pool.submit(() -> {
                        go.await();
                        return store.insertIfNoneActive(richBoard(firstStartedAt));
                    });
                    Future<Boolean> b = pool.submit(() -> {
                        go.await();
                        return store.insertIfNoneActive(richBoard(secondStartedAt));
                    });
                    go.countDown();
                    boolean wonA = a.get(10, TimeUnit.SECONDS);
                    boolean wonB = b.get(10, TimeUnit.SECONDS);
                    assertThat(wonA || wonB)
                            .as("%s 第 %d 轮两个都 false，仗根本开不起来", label, round)
                            .isTrue();
                    if (wonA && wonB) {
                        bothWon++;
                    }
                }
                assertThat(bothWon)
                        .as("%s 出现「两个都 true」的轮数必须为 0 —— 大于 0 就是「查」与「插」没收在同一个临界区里，"
                                + "全服会开出两场平行账，各自的击杀与占领分互不可见且不报错", label)
                        .isZero();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // ---------- 惰性结算（切片 2c：「到点结束」终于有了执行者，而服务端不许常驻定时器）----------

    /**
     * <b>本类里唯一一条把「时刻」当参数的用例</b>：{@code settleIfExpired(now)} 不读时钟，
     * 所以到期、未到期、到点这三档都能精确摆位，不需要 sleep（也不需要谁去拨系统时间）。
     *
     * <p>三条判据分别对应三种真实缺陷形状，缺一不可：未到期就动 = 最后一段占领分被提前定格
     * （防偷家机制作废）；到期后第二次读再动 = 撞内核 {@code settle()} 的护栏，玩家侧是面板 500；
     * 只改手里那份副本不落盘 = 下一次读看到一场还在打的仗，而面板已经显示结束了。
     */
    @Test
    @DisplayName("到期判定：差一秒不动、到点定格一次、第二次读不许再动任何分")
    void lazySettleRunsExactlyOnceOnBothStores() {
        long duration = rules.rules().durationMillis();
        assertThat(duration)
                .as("前置：本用例的边界数字全按 WAR_DURATION_HOURS 算，先钉住它是 3 小时。"
                        + "配置改了这条会红，那是提醒重新算数，不是缺陷")
                .isEqualTo(3L * 60L * MINUTE);

        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfNoneActive(richBoard(T0));   // n1 从 T0+7min 起占着王城
            String beforeSettle = describe(richBoard(T0));

            // 第一档：差一秒没到期 —— 一分都不许动
            WarStore.Settlement notYetS = store.settleIfExpired(T0 + duration - 1_000L).orElseThrow();
            WarScoreBoard notYet = notYetS.board();
            assertThat(notYetS.settledNow())
                    .as("%s 未到期不能报 settledNow=true：发奖就挂在这个旗标上，报早一次就发早一次", label)
                    .isFalse();
            assertThat(notYet.phase())
                    .as("%s 未到期就结算，等于把最后不足一分钟的占领分提前定格", label)
                    .isEqualTo(WarScoreBoard.Phase.SIEGE);
            assertThat(describe(notYet))
                    .as("%s 未到期那一次读必须一字不动（动了就是提前结算，而全链路不报错）", label)
                    .isEqualTo(beforeSettle);

            // 第二档：正好到点 —— 用 >= 而不是 >，否则面板会停在 remainingSec=0 而 phase=SIEGE
            WarStore.Settlement atS = store.settleIfExpired(T0 + duration).orElseThrow();
            WarScoreBoard at = atS.board();
            assertThat(atS.settledNow())
                    .as("%s 到点那一次必须报 true —— 赛季分就发在这一次，漏报等于这一场永远没人进 WAR 榜", label)
                    .isTrue();
            assertThat(at.phase())
                    .as("%s 到点这一刻必须当场定格", label)
                    .isEqualTo(WarScoreBoard.Phase.SETTLED);
            assertThat(at.remainingSeconds(T0 + duration))
                    .as("%s 已结束的仗剩余秒数为 0（验收 6 的面板读数）", label).isZero();
            String settled = describe(at);
            // n1 从 T0+7min 占到 T0+180min = 173 分钟 × WAR_SCORE_OCCUPY_PER_MINUTE(10)
            assertThat(settled)
                    .as("%s 结算要把最后一段占领分结进去：这一项在结算前恒为 0，"
                            + "是最能分辨「到底结没结」的量（击杀与建筑分结算前后一样）", label)
                    .contains("n1=1730,60000,100");

            // 第三档：时间再走 4 小时再读一次 —— 不许再动，也不许敲内核那条「重复结算」的护栏
            WarStore.Settlement afterS = store.settleIfExpired(T0 + duration + 4L * 60L * MINUTE).orElseThrow();
            WarScoreBoard after = afterS.board();
            assertThat(afterS.settledNow())
                    .as("%s 第二次读的 settledNow 必须为 false：旗标说的是「这一句结掉了它」，不是「它现在是结算态」", label)
                    .isFalse();
            assertThat(describe(after))
                    .as("%s 第二次读必须与第一次一字不差。再结一次会撞内核 settle() 的护栏（抛到端点上就是 500），"
                            + "而绕过它去 catch 则会把占领分算两遍 —— 两个都不许发生", label)
                    .isEqualTo(settled);

            // 落盘判据：findLatest 只读不动，所以它看到的 SETTLED 只能来自上一次真的写回
            assertThat(describe(store.findLatest().orElseThrow()))
                    .as("%s 结算没有 save 的话，下一次读会看到一场还在打的仗，而玩家面板已经显示结束了", label)
                    .isEqualTo(settled);
        }
    }

    /**
     * 判据③的存储侧：<b>结算完才允许开下一场</b>，且历史那一场留在<b>原主键</b>上。
     *
     * <p>与 {@link #settledWarOpensTheDoorToTheNextOneOnBothStores} 的区别不是重复：那一条是
     * 测试<b>手动</b> {@code settle()} + {@code save()}，等于假设了结算这件事有人做；这一条全程
     * 只调惰性推进口，所以它钉的是「2c 交付的那一跳确实把仗结掉了」——把 {@code settleIfExpired}
     * 整个方法体删掉直接 return，这条会红而那一条照样绿。
     *
     * <p>历史留档用 {@code insertIfAbsent} <b>撞键</b>来证，而不是数文档条数：内存版没有条数概念
     * （两套实现都得有同一条断言才叫等价）。{@code documentIdOf} 按 {@code startedAt} 推导，
     * 撞键即证明那一场还在<b>原来那个键</b>上 —— 结算若写成「删旧插新」，这一句会返回 true。
     */
    @Test
    @DisplayName("惰性结算后：历史仍在原档上（撞键可证），下一场开得起来，且新那一场也读得到")
    void settledHistoryStaysPutAndTheNextWarOpensOnBothStores() {
        long duration = rules.rules().durationMillis();
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfNoneActive(richBoard(T0));
            assertThat(store.settleIfExpired(T0 + duration).orElseThrow().board().phase())
                    .as("%s 前置：第一场确实被这一句结掉了", label)
                    .isEqualTo(WarScoreBoard.Phase.SETTLED);

            assertThat(store.insertIfAbsent(richBoard(T0)))
                    .as("%s 历史那一场必须还留在原主键上：结完就查无此仗，V18 的赛季榜与运维复盘双双失去依据", label)
                    .isFalse();

            assertThat(store.insertIfNoneActive(richBoard(T0 + 2 * duration)))
                    .as("%s 单场不变量只看未结束的那几场，结完了就该放行", label).isTrue();
            assertThat(store.findLatest().orElseThrow().startedAt())
                    .as("%s 读端点给的必须是新那一场，而不是刚结完的历史", label)
                    .isEqualTo(T0 + 2 * duration);

            assertThat(store.settleIfExpired(T0 + 3 * duration).orElseThrow().board().phase())
                    .as("%s 结算口不是只对第一场生效的一次性代码", label)
                    .isEqualTo(WarScoreBoard.Phase.SETTLED);
        }
    }

    /**
     * 结完的那一场不许再被记分 —— 这是惰性结算的另一半。{@link WarScoreBoard} 的类注释写着
     * 「国战已结算，不能再改积分」，而 {@code requireSiegeOrPrep} 只在有人直接调内核时生效；
     * 存储层的 {@code recordKills} 自己也判一次 phase，这一条钉的就是那一次判定<b>接的是惰性结完之后</b>。
     *
     * <p>与 {@link #lazySettleRunsExactlyOnceOnBothStores} 分成两条不是冗余：那一条管"结算本身只跑一次"，
     * 这一条管"结完之后别的写路径也被挡住"，两处 phase 判定在不同方法里，合成一条会红了不知道该怪谁。
     */
    @Test
    @DisplayName("结算之后再来击杀：两套实现都给 NO_ACTIVE_WAR，定格的那份一分不动")
    void killsAfterTheLazySettleAreRefusedOnBothStores() {
        long duration = rules.rules().durationMillis();
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfNoneActive(richBoard(T0));
            store.settleIfExpired(T0 + duration);
            String frozen = describe(store.findLatest().orElseThrow());

            assertThat(store.recordKills("n1", "P9", 500L))
                    .as("%s 已经结完的仗不是现役仗，再记一笔等于往历史账本上续写", label)
                    .isEqualTo(WarStore.KillResult.NO_ACTIVE_WAR);
            assertThat(describe(store.findLatest().orElseThrow()))
                    .as("%s 被拒的这一次国家分、全服数与 P9 的个人账都不许动", label)
                    .isEqualTo(frozen);
        }
    }

    /**
     * <b>结算与击杀共用同一把锁，这一条才是那句话的可失败证据</b>。
     *
     * <p>为什么单靠上面三条不够：把 {@code MongoWarStore.settleIfExpired} 的 {@code synchronized}
     * 拆掉，上面三条<b>实测全绿</b>（本会话植过一次，见开发包 §四 那一格的「未做」列）——
     * 它们全是单线程用例，而锁只在两个线程真的错开时才可见。
     *
     * <p><b>判据写成"线性"而不是"数值"</b>：一次结算与一次击杀同时发生，合法结果只有两种
     * （击杀先落 ⇒ 板子上是 {@code SETTLED} 且带着那 10 个；结算先落 ⇒ 击杀被 {@code NO_ACTIVE_WAR} 拒掉）。
     * 所以断言挂在<b>击杀自己的返回值</b>上：它说 APPLIED，最终档里就必须有那 10 个。
     * 拆锁后会出现第三种形状 —— 结算拿着<b>加 10 之前</b>的副本最后写回，把那一笔整档盖掉，
     * 而全链路一个错都不报（症状只是"全服进度好像少涨了一点"）。
     *
     * <p><b>这条判据不会假红</b>：两种合法形状都被容纳，加锁的实现无论怎么调度都绿。
     * 只有被植坏的那一份有运气成分，所以跑 30 轮并按<b>违规轮数</b>断言 0，与
     * {@link #onlyOneOpenWarSurvivesTheRaceOnBothStores} 同一条量具设计。
     */
    @Test
    @DisplayName("结算与击杀抢同一把锁：30 轮里 APPLIED 的那一笔永远不能被盖掉")
    void settleAndKillsShareTheCriticalSectionOnBothStores() throws Exception {
        long duration = rules.rules().durationMillis();
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                int lostKills = 0;
                int resurrected = 0;
                for (int round = 0; round < 30; round++) {
                    store.clear();
                    store.insertIfAbsent(richBoard(T0));   // totalKills = 60_120
                    CountDownLatch go = new CountDownLatch(1);
                    Future<WarStore.KillResult> kill = pool.submit(() -> {
                        go.await();
                        return store.recordKills("n1", "P1", 10L);
                    });
                    Future<Optional<WarStore.Settlement>> settle = pool.submit(() -> {
                        go.await();
                        return store.settleIfExpired(T0 + duration);
                    });
                    go.countDown();
                    WarStore.KillResult kr = kill.get(10, TimeUnit.SECONDS);
                    settle.get(10, TimeUnit.SECONDS);

                    WarScoreBoard back = store.findLatest().orElseThrow();
                    if (back.phase() != WarScoreBoard.Phase.SETTLED) {
                        resurrected++;
                    }
                    if (kr == WarStore.KillResult.APPLIED && back.totalKills() != 60_130L) {
                        lostKills++;
                    }
                }
                assertThat(lostKills)
                        .as("%s 出现「击杀返回 APPLIED 却不在最终档里」的轮数必须为 0 —— 大于 0 就是结算与击杀"
                                + "各自拿着旧副本整档互盖，那笔击杀静默消失", label)
                        .isZero();
                assertThat(resurrected)
                        .as("%s 出现「两个动作都跑完之后板子又不是 SETTLED」的轮数必须为 0 —— "
                                + "那是击杀把已结算的那一场写回了未结束状态", label)
                        .isZero();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // ---------- 宣战冷却的查询口（切片 3a：warCooldownHours 第一次有消费者）----------

    /**
     * <b>这一条防的是 Mongo 那一侧的数组查询退化</b>：把「两方都在」写成
     * {@code in(nationA, nationB)}，语义就变成「有一个元素等于 a <b>或</b> b」，
     * 于是只跟 n1 打过的 n3 会被算进 n1-n2 那一档 —— 内存版照样给出 empty，
     * 两边不一致，而症状是「明明没跟这国打过，冷却却挡着不让宣战」。
     *
     * <p>{@code (n2, n3)} 那一句是这个退化唯一能被抓到的地方，别把它当冗余删掉。
     */
    @Test
    @DisplayName("findLatestBetween 只认「两方都在」的档、取最近一场、顺序无关；两套实现同一条")
    void latestBetweenPairsIsIdenticalOnBothStores() {
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            // n1-n2 打过两场（一旧一新），n1-n3 打过一场，n2-n3 从来没有
            store.insertIfAbsent(pair(T0, "n1", "n2"));
            store.insertIfAbsent(pair(T0 + 30 * MINUTE, "n1", "n2"));
            store.insertIfAbsent(pair(T0 + 10 * MINUTE, "n1", "n3"));

            assertThat(store.findLatestBetween("n1", "n2").orElseThrow().startedAt())
                    .as("%s 同一对打过两场，取的必须是最近那一场（冷却从最近一次算起）", label)
                    .isEqualTo(T0 + 30 * MINUTE);
            assertThat(store.findLatestBetween("n2", "n1").orElseThrow().startedAt())
                    .as("%s 顺序无关：冷却按「这一对」算，攻防互换同样要等", label)
                    .isEqualTo(T0 + 30 * MINUTE);
            assertThat(store.findLatestBetween("n1", "n3").orElseThrow().startedAt())
                    .as("%s 换一对就换一档去查，不许把别的档算进来", label)
                    .isEqualTo(T0 + 10 * MINUTE);
            assertThat(store.findLatestBetween("n2", "n3"))
                    .as("%s 这两国从没打过必须是 empty —— 写成 in(a,b) 时这一句会捞出 n1-n2 那一档", label)
                    .isEmpty();
        }
    }

    // ---------- 击杀归属（切片 2b：内核的 recordKill 第一次有了生产写路径）----------

    @Test
    @DisplayName("击杀归属的四种结果两套实现一字不差；没参战的人只加全服进度与个人账")
    void killAttributionIsIdenticalOnBothStores() {
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfNoneActive(richBoard(T0));
            WarScoreBoard before = store.findLatest().orElseThrow();
            long killsBefore = before.totalKills();
            long n1KillBefore = before.snapshot().get("n1").killScore();

            assertThat(store.recordKills("n1", "P1", 10L))
                    .as("%s 参战国的人应当走 APPLIED", label).isEqualTo(WarStore.KillResult.APPLIED);
            assertThat(store.recordKills("n-outside", "P9", 5L))
                    .as("%s 仗在打但这个人所属的国家没参战：SERVER_ONLY —— B13 §7 明写不打国战的人也算进全服目标", label)
                    .isEqualTo(WarStore.KillResult.SERVER_ONLY);
            assertThat(store.recordKills(null, "P9", 3L))
                    .as("%s 连国籍都没有（没联盟或联盟没入籍）：同样 SERVER_ONLY，不许抛", label)
                    .isEqualTo(WarStore.KillResult.SERVER_ONLY);
            assertThat(store.recordKills("n1", "P1", 0L))
                    .as("%s 零击杀：一次写入都不该发生", label).isEqualTo(WarStore.KillResult.SKIPPED);

            WarScoreBoard back = store.findLatest().orElseThrow();
            assertThat(back.totalKills()).as("%s 全服进度 = 三笔计入之和（10+5+3）", label)
                    .isEqualTo(killsBefore + 18L);
            assertThat(back.snapshot().get("n1").killScore())
                    .as("%s 国家击杀分只涨被点名的那一国（10 兵 × 1 分/兵）", label)
                    .isEqualTo(n1KillBefore + 10L);
            assertThat(back.snapshot().get("n2").killScore())
                    .as("%s 没被点名的参战国一分不涨（否则两个国家会因为一个人的战斗同时加分）", label)
                    .isEqualTo(120L);
            assertThat(back.killsBy("P9"))
                    .as("%s 个人账必须留着 —— 赛季分按人发就靠这一列，少了它 3b 只能回头翻会过期的战报", label)
                    .isEqualTo(8L);

            back.settle(T0 + 40 * MINUTE);
            store.save(back);
            assertThat(store.recordKills("n1", "P1", 1L))
                    .as("%s 已结算的历史不许再被记分（否则重启后 findLatest 读到的那份历史会一直涨）", label)
                    .isEqualTo(WarStore.KillResult.NO_ACTIVE_WAR);
            assertThat(store.findLatest().orElseThrow().totalKills())
                    .as("%s 被拒的那一次不许动到定格的分", label).isEqualTo(killsBefore + 18L);
        }
    }

    @Test
    @DisplayName("没有仗的时候归属是纯 no-op：战斗本身不受影响，也不凭空开出一场")
    void killsWithoutAnyWarAreNoOpOnBothStores() {
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            assertThat(store.recordKills("n1", "P1", 5L))
                    .as("%s 空存储必须回 NO_ACTIVE_WAR", label)
                    .isEqualTo(WarStore.KillResult.NO_ACTIVE_WAR);
            assertThat(store.findLatest()).as("%s 不许因为一次归属就开出仗来", label).isEmpty();
        }
    }

    /**
     * 八个人同时各结算五次击杀。<b>这条是"归属必须长在存储层"的直接证据</b>：
     * 服务层写「读板子 → 加 → 落盘」的话，这里会少涨几条而全链路不报错
     * （内存版是后写覆盖前写，Mongo 版是整档替换互相盖），症状只是"进度条好像少涨了一点"。
     */
    @Test
    @DisplayName("并发结算一条击杀都不许丢：总账与每个人的账都要精确（内存与 Mongo 同一条）")
    void concurrentKillsAreNotLostOnBothStores() throws Exception {
        final int threads = 8;
        final int each = 5;
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfNoneActive(richBoard(T0));
            long baseline = store.findLatest().orElseThrow().totalKills();

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                // 起跑线必须是 CountDownLatch(1)：latch 的初值是"要 countDown 几次才放行"，
                // 写成 (threads) 又只 countDown 一次，八条线程会永远等在起跑线上 ——
                // 表现为 Future.get 超时且任务一条日志都不打，看着像实现死锁，其实是用例写错（本轮真踩过）
                CountDownLatch startLine = new CountDownLatch(1);
                List<Future<Void>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    final String playerId = "C" + t;
                    futures.add(pool.submit(() -> {
                        startLine.await();
                        for (int i = 0; i < each; i++) {
                            store.recordKills("n1", playerId, 1L);
                        }
                        return null;
                    }));
                }
                startLine.countDown();
                for (Future<Void> f : futures) {
                    f.get(20, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            WarScoreBoard back = store.findLatest().orElseThrow();
            assertThat(back.totalKills())
                    .as("%s 少了就是读-改-写没进同一个临界区（丢的那几条不会有任何地方报错）", label)
                    .isEqualTo(baseline + (long) threads * each);
            for (int t = 0; t < threads; t++) {
                assertThat(back.killsBy("C" + t))
                        .as("%s 第 %d 个人的个人账被并发挤掉了（全服总数对不上个人账，赛季分就会发错人）", label, t)
                        .isEqualTo(each);
            }
        }
    }

    // ---------- 建档与落盘 ----------

    @Test
    @DisplayName("insertIfAbsent 只认第一个赢家：第二份同开场的档不许盖掉第一份")
    void insertIfAbsentKeepsTheFirstBoardOnBothStores() {
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            WarScoreBoard first = richBoard(T0);
            assertThat(store.insertIfAbsent(first))
                    .as("%s 首次建档应当成功", label).isTrue();

            WarScoreBoard rival = new WarScoreBoard(rules.rules(), T0);
            rival.registerNation("n9");
            rival.recordKill("n9", 1L);
            assertThat(store.insertIfAbsent(rival))
                    .as("%s 同一场（同 startedAt）第二次建档必须返回 false，而不是静默插出第二份", label)
                    .isFalse();

            WarScoreBoard back = store.findLatest().orElseThrow();
            assertThat(back.totalKills())
                    .as("%s 输家那份不许留下任何痕迹（击杀还是第一份的 60120）", label)
                    .isEqualTo(60_120L);
            assertThat(back.registeredNations()).doesNotContain("n9");
        }
    }

    @Test
    @DisplayName("没建过档就 save 必须响亮拒绝：静默插入会插出两份各自算各自的积分")
    void saveBeforeInsertFailsLoudlyOnBothStores() {
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            WarScoreBoard board = richBoard(T0);
            assertThatThrownBy(() -> store.save(board))
                    .as("%s 走错方法的后果必须当场响，而不是变成两份平行档", label)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("建档请走 insertIfAbsent");
            assertThat(store.findLatest())
                    .as("%s 被拒的那一次不许留下半个写入", label).isEmpty();
        }
    }

    @Test
    @DisplayName("findLatest 按 startedAt 取最近一场，而不是按落盘顺序取最后一条")
    void findLatestPicksNewestStartedNotLastInsertedOnBothStores() {
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            // 先建「新」的一场，再补档「旧」的一场（结算迟到、运维补录都可能造成这个顺序）
            store.insertIfAbsent(richBoard(T0 + 20 * MINUTE));
            store.insertIfAbsent(richBoard(T0 + 10 * MINUTE));

            WarScoreBoard back = store.findLatest().orElseThrow();
            assertThat(back.startedAt())
                    .as("%s 内存版按插入顺序取末条、Mongo 按 startedAt 排序 —— 不钉住就会 dev 全绿而生产给出另一场", label)
                    .isEqualTo(T0 + 20 * MINUTE);
        }
    }

    @Test
    @DisplayName("save 是整档替换且后写的赢：两套实现同一条（端口 javadoc 里那条未关闭的窗口）")
    void lastWriteWinsOnBothStores() {
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfAbsent(richBoard(T0));

            WarScoreBoard stale = store.findLatest().orElseThrow();
            WarScoreBoard fresh = store.findLatest().orElseThrow();
            fresh.recordKill("n1", 1_000L);
            store.save(fresh);
            store.save(stale);   // 拿着旧副本后写 —— 覆盖掉刚才那一千击杀

            WarScoreBoard back = store.findLatest().orElseThrow();
            assertThat(back.totalKills())
                    .as("%s 这条断言不是为了证明实现好：端口没有乐观锁版本，"
                            + "「中途 flush」一旦成为写形状就会盖数据，此处把窗口写成可失败的证据。"
                            + "下一切片若给端口加版本，这一条必须改写成断言被拒", label)
                    .isEqualTo(60_120L);
        }
    }

    // ---------- 夹具 ----------

    private List<WarStore> bothStores() {
        requireMongo();
        return List.of(new InMemoryWarStore(rules), newMongoStore());
    }

    private static MongoWarStore newMongoStore() {
        requireMongo();
        return new MongoWarStore(db.template(), rules);
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 见「跳过即未验证」那条");
    }

    /** 只登记两国的最小一档（冷却那一族只读 {@code nations} 与 {@code startedAt}，不需要别的状态）。 */
    private static WarScoreBoard pair(long startedAt, String nationA, String nationB) {
        WarScoreBoard board = new WarScoreBoard(rules.rules(), startedAt);
        board.registerNation(nationA);
        board.registerNation(nationB);
        return board;
    }

    @Test
    @DisplayName("settleIfExpired 把内核那一份 Result 带出来：有胜者时是那一国，且与 settledNow 互为条件")
    void settlementCarriesTheKernelResultOnBothStores() {
        long duration = rules.rules().durationMillis();
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            // n1 占城且 60000 击杀、n2 只有 120 击杀 ⇒ n1 必然是胜者（不是平分那一支）
            store.insertIfNoneActive(richBoard(T0));

            WarStore.Settlement notYet = store.settleIfExpired(T0 + duration - 1_000L).orElseThrow();
            assertThat(notYet.result())
                    .as("%s 没有结掉这一场就没有终局：带出一份 Result 等于造出「没结算却已有胜负」这种假状态", label)
                    .isNull();

            WarStore.Settlement at = store.settleIfExpired(T0 + duration).orElseThrow();
            assertThat(at.settledNow()).as("%s 到点那一次报 true", label).isTrue();
            assertThat(at.result())
                    .as("%s settledNow=true 却没带 Result：内核 settle() 只有一次机会算出 winnerId，" +
                            "带不出来的话发奖那一侧就只剩两条错路（再调一次 settle 撞护栏，或自己比一遍积分）", label)
                    .isNotNull();
            assertThat(at.result().winnerId())
                    .as("%s 胜者是积分高的那一国 —— Result 从这一格起有了消费者（#753 剩下的那一半）", label)
                    .isEqualTo("n1");
            assertThat(at.result().totalKills())
                    .as("%s Result 里的全服击杀与板子现读的一致（两份数字不该来自两次遍历）", label)
                    .isEqualTo(store.findLatest().orElseThrow().totalKills());

            // 再读一次：旗标 false，Result 也必须 null —— 发奖只挂在第一次那一次转换上
            WarStore.Settlement again = store.settleIfExpired(T0 + duration + 60_000L).orElseThrow();
            assertThat(again.settledNow()).as("%s 第二次读旗标为 false", label).isFalse();
            assertThat(again.result())
                    .as("%s 第二次读不许再带出一份终局：赛季分是累加语义，带出来就有人可能再发一遍", label)
                    .isNull();
        }
    }

    /**
     * 平分时内核给 null，存储层必须原样带出来（发奖那一侧怎么用它，见
     * {@code RankEndpointTest#tiedWarPaysNoWinnerBonus}）。这里钉的是「存储层不替玩家挑赢家」。
     */
    @Test
    @DisplayName("平分那一场在两套实现上 winnerId 都是 null，而终局积分两份都还在")
    void tiedSettlementCarriesNullWinnerOnBothStores() {
        long duration = rules.rules().durationMillis();
        for (WarStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            WarScoreBoard board = new WarScoreBoard(rules.rules(), T0, "n1");
            board.registerNation("n1");
            board.registerNation("n2");
            board.recordKill("n1", "P1", 500L);
            board.recordKill("n2", "P2", 500L);   // 两边同分
            store.insertIfNoneActive(board);

            WarStore.Settlement at = store.settleIfExpired(T0 + duration).orElseThrow();
            assertThat(at.result().winnerId())
                    .as("%s 同分时存储层不得自己补一个赢家出来", label).isNull();
            assertThat(at.result().scores()).as("%s 但两份终局积分仍然都在（面板要画）", label)
                    .hasSize(2);
        }
    }
    /**
     * 一份把九项状态都填过的板子（数值全用真实配置的增量，便于把断言写成具体数字）。
     *
     * <p>积分与击杀刻意各不相等、疲劳刻意一个到顶一个没到顶、领取名单刻意两个人 ——
     * 三项对称的值会让「读串了行」这类错误看不见。
     */
    private static WarScoreBoard richBoard(long startedAt) {
        // 第三个参数是发起国：3b-2 之后它是快照的一部分（发发起加成只认这一项）。
        // 夹具不带它上去，等于「新字段在两套实现上都没被往返过」
        WarScoreBoard board = new WarScoreBoard(rules.rules(), startedAt, "n1");
        board.registerNation("n1");
        board.registerNation("n2");
        board.captureGate("n1", "gate_1");
        board.captureGate("n2", "gate_2");
        board.beginSiege(startedAt + MINUTE);
        board.captureCapital("n1", startedAt + 7 * MINUTE);
        board.recordKill("n1", "P1", 60_000L);
        board.recordKill("n2", "P2", 120L);
        board.addFatigue("P1", 15L, 5L);   // 15×5 + 5×1 = 80
        board.addFatigue("P2", 25L, 0L);   // 125 → 夹到上限 100
        board.claimServerGoal("P1");
        board.claimServerGoal("P2");
        return board;
    }

    /**
     * 逐字段描述。<b>新增状态字段时必须在这里出现</b>，否则「快照少带一个字段」没人能发现。
     *
     * <p>三条刻意的取舍：
     * <ul>
     *   <li>{@code capitalHeldSince} 不在这里 —— 内核没暴露它，也不该为量具开一个 getter，
     *       由 {@link #occupationStartSurvivesRoundTripOnBothStores} 从行为侧钉住；</li>
     *   <li>领取名单只输出<b>人数</b>：逐个成员要调用 {@code claimServerGoal}，那是写动作
     *       （会把人加进名单），量具不许有副作用。名单成员的正确性由
     *       {@link #goalClaimedListSurvivesRoundTripOnBothStores} 单独钉；</li>
     *   <li>参战方的行序<b>保持原序</b>输出（{@code settle()} 的平分判定依赖它），
     *       而疲劳那几列按固定 key 顺序输出 —— 顺序不是协议的一部分。</li>
     * </ul>
     */
    private static String describe(WarScoreBoard board) {
        StringBuilder b = new StringBuilder();
        b.append(board.startedAt()).append('|').append(board.phase())
                .append('|').append(board.totalKills())
                .append('|').append(board.capitalHolder())
                .append('|').append(board.serverGoalReached())
                // 发起国必须在描述里：它是 3b-2 新加进快照的那一项。少持久化它的症状不是报错，
                // 而是「内存板上明明有、落盘读回来就没了」⇒ 生产上发起方永远领不到发起加成
                .append("|initiator=").append(board.initiatorNationId())
                .append("|nations=");
        for (String nationId : board.registeredNations()) {
            WarScoreBoard.Score score = board.snapshot().get(nationId);
            b.append(nationId).append('=').append(score.occupyScore()).append(',')
                    .append(score.killScore()).append(',').append(score.buildingScore())
                    .append(",gates=").append(board.gateCount(nationId))
                    .append(',').append(board.isQualified(nationId)).append(';');
        }
        b.append("|fatigue=");
        for (String playerId : List.of("P1", "P2", "P3")) {
            b.append(playerId).append('=').append(board.fatigueOf(playerId)).append(',');
        }
        // 每人击杀账（V18 的赛季分输入）。这一列必须在描述里：少持久化它的症状是
        // "全服进度对了、国家分对了，但某个人的赛季榜一动不动" —— 上面任何一列都看不出来
        b.append("|playerKills=");
        for (String playerId : List.of("P1", "P2", "P3", "P9")) {
            b.append(playerId).append('=').append(board.killsBy(playerId)).append(',');
        }
        b.append("|claimedCount=").append(board.serverGoalClaimed());
        return b.toString();
    }

    private static Path locateConfigDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("contract/config");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 contract/config 目录");
    }
}
