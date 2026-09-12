package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
import com.ironoath.core.nation.Nation;
import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.store.memory.InMemoryNationStore;
import com.ironoath.web.store.mongo.MongoNationStore;
import com.ironoath.web.store.mongo.NationDocument;

/**
 * 职责：国家存储在<b>内存与 Mongo 上必须给出同一个结果</b>（收口清单 #16 的国家档）。
 * 依赖：真实配置表（规则来自 {@code nation_config}）+ 本机 MongoDB（见 {@link TestMongo}）。
 *
 * <p><b>这一档最特殊的地方是"读返回副本"这件事本轮才成立</b>：内存版原先直接持有调用方那个实例，
 * 于是①"改了不 save"在 dev 下完全看不出来，②{@code NationAppService} 顺手依赖了这个别名 ——
 * 周税由存储层改在册对象，而服务把<b>结算前</b>那份副本 save 回去就会把刚入账的钱整笔覆盖掉。
 * 所以本类里有一条专门的用例 {@code staleSaveAfterSettleLosesTheTaxOnBothStores}：
 * 它断言的是"错误顺序确实会丢钱，而且两套实现丢得一模一样"。
 * 这条断言不是为了证明实现好，是为了让"必须重读"这件事<b>能被测试看到</b>而不是靠注释。
 */
class NationStoreEquivalenceTest {

    private static final long T0 = 1_800_000_000_000L;
    private static final long WEEK = 7L * 24 * 3_600_000L;
    private static ConfigRegistry configs;
    private static NationRulesAssembler rules;
    private static TestMongo db;

    @BeforeAll
    static void setUp() {
        configs = ConfigRegistry.loadFromDirectory(locateConfigDir());
        rules = new NationRulesAssembler(configs);
        db = TestMongo.tryOpen();
    }

    @BeforeEach
    void clearNations() {
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
        for (NationStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfAbsent(nation("N-copy", "铁誓"));
            store.findById("N-copy").orElseThrow().annexProvince("prov_should_not_persist");

            Nation back = store.findById("N-copy").orElseThrow();
            assertThat(back.provinces())
                    .as("%s 内存版返回活对象时这条永远测不出来，换 Mongo 就是「改了没存也不报错」", label)
                    .isEmpty();
        }
    }

    /**
     * 本轮改动的靶心：加了版本号之后，"结算 → 改手里那份旧副本 → save"不再能吞掉那笔钱 ——
     * 第二次写会被版本挡住。
     *
     * <p>原先这条断言的是"确实会吞掉，所以服务层必须重读"，等于把正确顺序写成测试；
     * 现在存储层自己拒了 —— 同一个错误从"要靠人记得"变成"发生了就响"。
     */
    @Test
    @DisplayName("结算之后拿旧副本 save 必须被版本挡住：钱不许被静默盖掉")
    void staleSaveAfterSettleIsRefusedOnBothStores() {
        for (NationStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfAbsent(nation("N-stale", "旧账"));
            Nation staleCopy = store.findById("N-stale").orElseThrow();
            long before = staleCopy.treasury();

            long credited = store.settleWeeklyTax("N-stale", T0);
            assertThat(credited).as("%s 前置条件：这一周确实收到了钱", label).isPositive();
            staleCopy.appoint("K-N-stale", "P-min", "AL-N-stale", Nation.Office.MINISTER);
            assertThatThrownBy(() -> store.save(staleCopy, staleCopy.version()))
                    .as("%s 带着结算前那份旧副本写回来必须撞锁，而不是把钱盖掉", label)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("乐观锁冲突");

            Nation after = store.findById("N-stale").orElseThrow();
            assertThat(after.treasury())
                    .as("%s 被拒的那一次不许动到国库（也不许留下半个写入）", label)
                    .isEqualTo(before + credited);
            assertThat(after.holdersOf(Nation.Office.MINISTER))
                    .as("%s 被拒的任命也不许一半生效", label).isEmpty();
        }
    }

    @Test
    @DisplayName("版本每次成功写入恰好前进一格，且不动调用方手里的对象")
    void versionAdvancesOncePerSuccessfulWrite() {
        for (NationStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfAbsent(nation("N-ver", "版本国"));
            long v0 = store.findById("N-ver").orElseThrow().version();

            Nation first = store.findById("N-ver").orElseThrow();
            first.annexProvince("prov_1");
            long returned = store.save(first, first.version());
            assertThat(returned).as("%s save 返回的就是新版本", label).isEqualTo(v0 + 1L);
            assertThat(store.findById("N-ver").orElseThrow().version())
                    .as("%s 库里前进一格", label).isEqualTo(v0 + 1L);
            assertThat(first.version())
                    .as("%s 不动调用方的对象（否则同一个对象连写两次会静默成功）", label).isEqualTo(v0);

            assertThatThrownBy(() -> store.save(first, v0))
                    .as("%s 拿已经写掉的那一版再写一次必须被拒", label)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("乐观锁冲突");
        }
    }

    @Test
    @DisplayName("并发建档只有一个赢家：另一家拿到 false，而不是插出两份档")
    void concurrentInsertHasExactlyOneWinner() throws Exception {
        for (NationStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            int racers = 8;
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            List<Future<Boolean>> results = new ArrayList<>();
            try {
                List<Callable<Boolean>> jobs = new ArrayList<>();
                for (int i = 0; i < racers; i++) {
                    jobs.add(() -> store.insertIfAbsent(nation("N-race", "并发国")));
                }
                for (Callable<Boolean> job : jobs) {
                    results.add(pool.submit(job));
                }
            } finally {
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("并发建档跑不完").isTrue();
            }
            long winners = 0L;
            for (Future<Boolean> future : results) {
                winners += Boolean.TRUE.equals(future.get()) ? 1L : 0L;
            }
            assertThat(winners)
                    .as("%s 两份国家档意味着官职、国库与外交各算一套（先查后插就是这个下场）", label)
                    .isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("正确顺序（先结算、重读、再改、最后 save）两笔都不丢")
    void settleThenReloadThenMutateKeepsBothChanges() {
        for (NationStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfAbsent(nation("N-good", "新账"));
            long before = store.findById("N-good").orElseThrow().treasury();
            long credited = store.settleWeeklyTax("N-good", T0);

            Nation settled = store.findById("N-good").orElseThrow();
            assertThat(settled.treasury()).as("%s 重读之后就能看到这笔入账", label)
                    .isEqualTo(before + credited);
            settled.appoint("K-N-good", "P-gen", "AL-N-good", Nation.Office.GENERAL);
            store.save(settled, settled.version());

            Nation back = store.findById("N-good").orElseThrow();
            assertThat(back.treasury()).as("%s 周税还在", label).isEqualTo(before + credited);
            assertThat(back.holdersOf(Nation.Office.GENERAL)).as("%s 任命也在", label)
                    .containsExactly("P-gen");
        }
    }

    // ---------- 周税 ----------

    @Test
    @DisplayName("八线程同时结算同一周：只入账一次，两套实现给出的总额相同")
    void weeklyTaxIsCollectedExactlyOnceUnderConcurrency() throws Exception {
        for (NationStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfAbsent(nation("N-tax", "公账"));
            int racers = 8;
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            List<Future<Long>> results = new ArrayList<>();
            try {
                List<Callable<Long>> jobs = new ArrayList<>();
                for (int i = 0; i < racers; i++) {
                    jobs.add(() -> store.settleWeeklyTax("N-tax", T0));
                }
                for (Callable<Long> job : jobs) {
                    results.add(pool.submit(job));
                }
            } finally {
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("并发结算跑不完").isTrue();
            }
            long total = 0L;
            for (Future<Long> future : results) {
                total += future.get();
            }
            long oneWeek = store.findById("N-tax").orElseThrow().treasury();
            assertThat(total).as("%s 七个并发里最多一个能真的收到钱", label).isEqualTo(oneWeek);
            assertThat(oneWeek).as("%s 一周的税额本身必须是正数（否则上面那条断言恒真）", label)
                    .isPositive();
            assertThat(store.settleWeeklyTax("N-tax", T0)).as("%s 同一周再来一次必须是 0", label).isZero();
            assertThat(store.settleWeeklyTax("N-tax", T0 + WEEK))
                    .as("%s 换到下一周就该再收一次", label).isPositive();
        }
    }

    @Test
    @DisplayName("结算不存在与已收过的国家都必须回 0，不许抛")
    void settleOfUnknownOrSettledNationReturnsZero() {
        for (NationStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            assertThat(store.settleWeeklyTax("N-none", T0)).as("%s 缺国家回 0", label).isZero();
        }
    }

    // ---------- 逐字段 ----------

    @Test
    @DisplayName("整档逐字段落得回去：官职席位与顺序、外交、冷却、省份、国库日志、周键")
    void everyFieldSurvivesTheRoundTrip() {
        List<String> described = new ArrayList<>();
        for (NationStore store : bothStores()) {
            store.insertIfAbsent(richNation("N-rich"));
            described.add(describe(store.findById("N-rich").orElseThrow()));
        }
        assertThat(described.get(1)).as("Mongo 读回来的一份必须与内存读回来的一份逐字段相同")
                .isEqualTo(described.get(0));

        // 再对一次"根本没进过存储"的原件：两套实现一起漏同一个字段时，上面那条断言照样绿
        assertThat(described.get(0)).isEqualTo(describe(richNation("N-rich")));

        Nation back = bothStores().get(1).findById("N-rich").orElseThrow();
        assertThat(back.holdersOf(Nation.Office.PRIME_MINISTER)).as("首相席位").containsExactly("P-pm");
        assertThat(back.holdersOf(Nation.Office.GENERAL))
                .as("只剩 P-g1：另一位所属的联盟在夹具最后被 removeAlliance 收回了官职"
                        + "（这件事本身单独立一条用例，见 officeReclaimSurvivesARestart）")
                .containsExactly("P-g1");
        assertThat(back.officeOf("P-g2")).as("被收回官职之后查不到席位").isNull();
        assertThat(back.officeOf("P-g1")).as("反向索引：某人担任什么官职").isEqualTo(Nation.Office.GENERAL);
        assertThat(back.diplomacyWith("N-foreign")).as("外交关系直接决定谁能打谁")
                .isEqualTo(Nation.Diplomacy.HOSTILE);
        assertThat(back.joinCooldownUntil("AL-out"))
                .as("开除留下的入籍冷却（B13 验收 2）").isPositive();
        assertThat(back.provinces()).containsExactly("prov_a", "prov_b");
        assertThat(back.treasuryLogs()).as("入账、支出、周税各一行：少一行就对不起余额").hasSize(3);
        assertThat(back.treasuryLogs().get(0).amount()).as("入账记正数规模").isEqualTo(5_000L);
        assertThat(back.treasuryLogs().get(2).balanceAfter()).as("最后一行的余额必须等于国库余额")
                .isEqualTo(back.treasury());
        assertThat(back.memberAllianceCount())
                .as("周税按成员联盟数算，所以这一列错了下一周的入账额就错").isEqualTo(1);
        assertThat(back.snapshot().lastTaxWeekKey()).as("周税幂等键").isEqualTo(1L);
    }

    /**
     * 这条是 {@code holderAlliance} 必须进快照的理由：收回官职靠的是「这个持有者属于哪个联盟」，
     * 而那层映射平时没有任何地方读它。不持久化的表现不是当场报错，而是<b>重启之后某个联盟退国
     * 或被开除时收不回它代表们的官职</b> —— 一个已经不属于本国的玩家继续握着权力位，
     * 还能被 {@code APPOINT_OFFICE} 那条权限链用上。
     */
    @Test
    @DisplayName("退盟收回官职要能跨重启存活（靠的是 holderAlliance 进了快照）")
    void officeReclaimSurvivesARestart() {
        for (NationStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            Nation fresh = nation("N-reclaim", "收回国");
            fresh.admitAlliance("AL-out", T0 + 10L);
            fresh.appoint("K-N-reclaim", "P-g1", "AL-N-reclaim", Nation.Office.GENERAL);
            fresh.appoint("K-N-reclaim", "P-g2", "AL-out", Nation.Office.GENERAL);
            store.insertIfAbsent(fresh);

            Nation loaded = store.findById("N-reclaim").orElseThrow();
            assertThat(loaded.holdersOf(Nation.Office.GENERAL))
                    .as("%s 前置条件：从存储读回来的一份就已经带着两位大将军与他们的联盟归属", label)
                    .containsExactly("P-g1", "P-g2");
            loaded.removeAlliance("AL-out", true, T0 + 40L);
            store.save(loaded, loaded.version());

            Nation back = store.findById("N-reclaim").orElseThrow();
            assertThat(back.holdersOf(Nation.Office.GENERAL))
                    .as("%s 没收回就是把权力留在了已不在本国的人手里", label).containsExactly("P-g1");
            assertThat(back.joinCooldownUntil("AL-out")).as("%s 开除要留下入籍冷却", label).isPositive();
        }
    }

    @Test
    @DisplayName("联盟索引跟着成员关系走：退国之后按联盟查必须查不到")
    void allianceLookupFollowsMembership() {
        for (NationStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfAbsent(nation("N-index", "索引国"));
            assertThat(store.findByAlliance("AL-N-index")).as("%s 在册时按联盟查得到", label).isPresent();

            Nation left = store.findById("N-index").orElseThrow();
            left.removeAlliance("AL-N-index", false, T0 + 1_000L);
            store.save(left, left.version());

            assertThat(store.findByAlliance("AL-N-index"))
                    .as("%s 退国之后还查得到，就会出现在「你已经不在本国了却还在被算成员」的状态", label)
                    .isEmpty();
            assertThat(store.findByAlliance("AL-never")).as("%s 没入过籍的联盟本来就不该有", label).isEmpty();
        }
    }

    @Test
    @DisplayName("两个国家不能共用一个国名：两侧都在 save 就拒，且不许留下半个写入")
    void duplicateNationNameIsRejectedByBothStores() {
        for (NationStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            store.insertIfAbsent(nation("N-name-1", "同名国"));
            assertThatThrownBy(() -> store.insertIfAbsent(nation("N-name-2", "同名国")))
                    .as("%s 名字索引被后写的占掉，等于前一个国家从「按名字查」这条路上消失且不报错", label)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("国名已被其它国家占用");
            assertThat(store.findById("N-name-2")).as("%s 被拒的那笔不许留下文档", label).isEmpty();
            assertThat(store.findByName("同名国").orElseThrow().id())
                    .as("%s 名字仍然指向先注册的那一个", label).isEqualTo("N-name-1");
        }
    }

    @Test
    @DisplayName("已解散的国家要能以「已解散」的状态读回来：记录留着供审计，但不能继续操作")
    void disbandedNationComesBackDisbanded() {
        for (NationStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            Nation doomed = nation("N-dead", "散国");
            doomed.disband("K-N-dead", T0 + 500L);
            store.insertIfAbsent(doomed);

            Nation back = store.findById("N-dead").orElseThrow();
            assertThat(back.isDisbanded()).as("%s 解散状态必须落库", label).isTrue();
            assertThatThrownBy(() -> back.appoint("K-N-dead", "P-x", "AL-N-dead", Nation.Office.MINISTER))
                    .as("%s 已解散的国家不能再任命（重建时走的是快照而不是 found，所以这条要单独验）", label)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("国家两条索引必须存在：name 唯一是「拦住静默重名」的那一道")
    void nationHasUniqueNameAndAllianceIndexes() {
        requireMongo();
        List<String> shapes = new ArrayList<>();
        List<String> uniques = new ArrayList<>();
        db.template().getCollection(NationDocument.COLLECTION).listIndexes().forEach(info -> {
            shapes.add(info.get("key").toString());
            if (Boolean.TRUE.equals(info.get("unique"))) {
                uniques.add(info.get("key").toString());
            }
        });
        assertThat(shapes).as("现有索引：" + shapes)
                .anySatisfy(shape -> assertThat(shape).contains("memberAllianceIds"));
        assertThat(uniques).as("name 索引必须真的是唯一索引：%s", shapes)
                .anySatisfy(shape -> assertThat(shape).contains("name"));
    }

    @Test
    @DisplayName("Mongo 必须真的可达：否则「国家账目落得住」这句话今天没有被验证过")
    void mongoMustBeReachableOrTheClaimIsUnverified() {
        Assumptions.assumeTrue(db != null,
                "跳过即未验证：国家存储的内存/Mongo 等价性没有被检查。"
                        + "补跑方式：起一个本地 MongoDB，或 -Dironoath.test.mongo.uri=... 指向一台");
    }

    /**
     * 读侧的 null 键：两侧都必须"当成查不到"，而不是一侧回 0、另一侧让驱动抛。
     * 这类差异不会在任何一档自己的用例里红 —— 它只在换存储那天炸。
     */
    @Test
    @DisplayName("null 国家 id：两侧都回「查不到 / 0」，不许一种静默一种抛")
    void nullNationIdBehavesIdenticallyOnBothStores() {
        for (NationStore store : bothStores()) {
            String label = store.getClass().getSimpleName();
            assertThat(store.settleWeeklyTax(null, T0))
                    .as("%s 空国家结算回 0", label).isZero();
            assertThat(store.findById(null)).as("%s 空 id 读成 empty", label).isEmpty();
            assertThat(store.findByName(null)).as("%s 空国名读成 empty", label).isEmpty();
            assertThat(store.findByAlliance(null)).as("%s 空联盟读成 empty", label).isEmpty();
            assertThatThrownBy(() -> store.save(null, 0L))
                    .as("%s 写 null 必须被拒（不是 NPE）", label)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.insertIfAbsent(null))
                    .as("%s 建 null 档同样", label).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---------- 夹具 ----------

    private List<NationStore> bothStores() {
        requireMongo();
        return List.of(new InMemoryNationStore(rules), newMongoStore());
    }

    private static MongoNationStore newMongoStore() {
        requireMongo();
        return new MongoNationStore(db.template(), rules);
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 见「跳过即未验证」那条");
    }

    private static Nation nation(String id, String name) {
        return Nation.found(id, name, "K-" + id, "AL-" + id, 100L, 200L, T0, rules.rules());
    }

    /** 每一类状态都塞一点非默认值：默认值会掩盖漏字段。 */
    private static Nation richNation(String id) {
        Nation nation = nation(id, "富国");
        nation.admitAlliance("AL-out", T0 + 10L);
        nation.appoint("K-" + id, "P-pm", "AL-" + id, Nation.Office.PRIME_MINISTER);
        nation.appoint("K-" + id, "P-g1", "AL-" + id, Nation.Office.GENERAL);
        nation.appoint("K-" + id, "P-g2", "AL-out", Nation.Office.GENERAL);
        nation.setDiplomacy("N-foreign", Nation.Diplomacy.HOSTILE);
        nation.deposit("K-" + id, "war_loot", 5_000L, "国战战利品", T0 + 20L);
        nation.setClock(T0 + 24L);
        nation.spend("K-" + id, Nation.Payee.toSink(Nation.Payee.Sink.NATIONAL_TECH), 1_000L,
                "研究国家科技·攻击");
        nation.collectTax(1L, T0 + 25L);
        nation.annexProvince("prov_a");
        nation.annexProvince("prov_b");
        nation.removeAlliance("AL-out", true, T0 + 30L);
        return nation;
    }

    /** 逐字段描述。新增状态字段时必须在这里出现，否则"快照少带一个字段"没人能发现。 */
    private static String describe(Nation nation) {
        Nation.Snapshot s = nation.snapshot();
        StringBuilder b = new StringBuilder();
        b.append(s.id()).append('#').append(s.name()).append('#').append(s.kingId())
                .append('#').append(s.capitalX()).append(',').append(s.capitalY())
                .append('#').append(s.level()).append('#').append(s.treasury())
                .append('#').append(s.lastTaxWeekKey()).append('#').append(s.disbandedAt())
                .append("#members=").append(s.memberAlliances())
                .append("#diplomacy=").append(s.diplomacy())
                .append("#cooldown=").append(s.joinCooldownUntil())
                .append("#provinces=").append(s.provinces())
                .append("#holders=").append(s.holderAlliance())
                .append("#logs=").append(s.treasuryLogs().size());
        for (Nation.TreasuryLog log : s.treasuryLogs()) {
            b.append('|').append(log.at()).append(',').append(log.operatorId()).append(',')
                    .append(log.payee()).append(',').append(log.amount()).append(',')
                    .append(log.reason()).append(',').append(log.balanceAfter());
        }
        b.append("#offices=");
        for (Nation.Office office : Nation.Office.values()) {
            b.append(office).append('=').append(s.offices().get(office)).append(';');
        }
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
