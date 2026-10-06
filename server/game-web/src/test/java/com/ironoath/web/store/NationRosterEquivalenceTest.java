package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.social.Alliance;
import com.ironoath.core.social.AllianceRole;
import com.ironoath.web.nation.NationMembership;
import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.social.SocialRulesAssembler;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryNationStore;
import com.ironoath.web.store.memory.InMemorySocialStore;
import com.ironoath.web.store.mongo.MongoNationStore;
import com.ironoath.web.store.mongo.MongoSocialStore;

/**
 * 职责：国家花名册那一跳（{@code NationMembership#playerIdsOf}）在<b>内存与真实 Mongo 上给出同一份名单</b>
 * —— B13 承载 3b-2 的等价判据。
 * 依赖：真实配置表（{@code nation_config} / {@code alliance_config} 现取）+ 本机 MongoDB（见 {@link TestMongo}）。
 *
 * <p><b>为什么这一格值得单独一份等价测试</b>：{@code playerIdsOf} 的形状是「<b>一次</b>
 * {@code allAlliances()} 取回全世界，再在 Java 侧按国家的成员联盟表筛」。两套实现在这一跳上有两处
 * 天生可能不一样的地方，而"只测一边"都会放过它：
 * <ul>
 *   <li><b>顺序</b>：内存版 {@code allAlliances()} 在 Java 侧 {@code Comparator.comparing(Alliance::id)}，
 *       Mongo 版在查询里 {@code Sort.by(ASC, "_id")} —— <b>机制不同、结果同</b>，正是等价测试该钉的那一族。
 *       而名单真正的顺序来自国家的成员联盟<b>登记</b>顺序，那一列在 Mongo 侧是文档里的一个子文档，
 *       键序是否被保住只有真跑才知道（同族前例：
 *       {@code WarStoreEquivalenceTest#findLatestPicksNewestStartedNotLastInserted}）；</li>
 *   <li><b>缺档</b>：解散联盟时内存版是 map 里删键、Mongo 版是整档删除。国家表里若还留着那一行，
 *       两边都必须给出"这一盟没有带来任何人"，而不是一边少一截名单。</li>
 * </ul>
 *
 * <p><b>为什么这里不发 HTTP</b>：这一格要比的是<b>存储读形状</b>，不是入籍流程（流程那一侧
 * {@code RankEndpointTest} 已经在真端点上跑过）。直接 {@code restore} 富状态反而能让
 * 「同一人挂在两个盟里」这种脏档进得来 —— 走端点它根本建不出来，而这一格要的判据之一正是<b>去重</b>：
 * 它的下游是发钱，同一个人出现两次就是同一场仗领两份赛季分。
 */
class NationRosterEquivalenceTest {

    private static final long T0 = 1_800_000_000_000L;
    private static final String NATION = "N-roster";

    private static ConfigRegistry configs;
    private static SocialRulesAssembler socialRules;
    private static NationRulesAssembler nationRules;
    private static TestMongo db;

    /**
     * 内存版的一份库存<b>整个用例只有一份</b>。
     *
     * <p>这不是风格问题：每个助手都 {@code new InMemorySocialStore()} 的话，写进去的盟与读花名册的
     * 是<b>两个不同的对象</b>，于是三条用例都会因为"两边都读到空"而<b>一起绿</b> —— 那是假绿最干净的形状。
     */
    private SocialStore memorySocial;
    private NationStore memoryNations;

    @BeforeAll
    static void setUp() {
        configs = ConfigRegistry.loadFromDirectory(locateConfigDir());
        socialRules = new SocialRulesAssembler(configs);
        nationRules = new NationRulesAssembler(configs);
        db = TestMongo.tryOpen();
    }

    @BeforeEach
    void clearStores() {
        memorySocial = new InMemorySocialStore();
        memoryNations = new InMemoryNationStore(nationRules);
        if (db != null) {
            newMongoSocial().clear();
            newMongoNation().clear();
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
    @DisplayName("花名册：两套实现给出同一串 id（顺序按入籍登记，而不是按盟 id 的字典序）")
    void rosterIsTheSameListOnBothStores() {
        // 入籍顺序：AL-zh（建国那一盟）在前、AL-ab 在后 —— 而 id 升序恰好是反的。
        // 名单应当把 AL-zh 的人放在前面，这才叫"读的是国家的成员表"而不是"读全世界再按 id 排"
        seedAlliance("AL-zh", "先入籍的盟", "ZH1", "P-zh1", "P-zh2");
        seedAlliance("AL-ab", "后入籍的盟", "AB2", "P-ab1", "P-ab2");
        seedAlliance("AL-out", "别国的盟", "OUT", "P-out");   // 不在这一国：它的成员一个都不该进名单
        seedNation("AL-zh", "AL-ab");

        List<String> memory = roster(memorySocial, memoryNations);
        List<String> mongo = roster(newMongoSocial(), newMongoNation());

        assertThat(memory)
                .as("内存版：登记顺序优先、别国那一盟不算")
                .containsExactly("P-zh1", "P-zh2", "P-ab1", "P-ab2");
        assertThat(mongo)
                .as("Mongo 版与内存版<b>逐位</b>相同 —— 只比集合会把顺序分叉读成「两边都对」")
                .containsExactlyElementsOf(memory);
    }

    @Test
    @DisplayName("成员联盟的档被删掉：两边都只是「这一盟没有带来任何人」，不是抛、也不是留下一串查无此人")
    void missingAllianceDocumentContributesNobodyOnBothStores() {
        seedAlliance("AL-zh", "先入籍的盟", "ZH1", "P-zh1");
        seedAlliance("AL-ab", "后入籍的盟", "AB2", "P-ab1");
        seedNation("AL-zh", "AL-ab");

        // 解散：两套实现都是"档没了"（内存删 map 键，Mongo 整档删除），而国家的成员联盟行
        // 不一定在同一条流程里被清 —— 这一格测的就是真撞上时两边的名单是不是同一截
        Alliance leaving = Alliance.create("AL-ab", "后入籍的盟", "AB2", "P-ab1", 1_000L,
                socialRules.allianceRules());
        memorySocial.removeAlliance(leaving);
        newMongoSocial().removeAlliance(leaving);

        List<String> memory = roster(memorySocial, memoryNations);
        List<String> mongo = roster(newMongoSocial(), newMongoNation());

        assertThat(memory).as("档没了就不该有人进名单").containsExactly("P-zh1");
        assertThat(mongo).as("Mongo 版同一条").containsExactlyElementsOf(memory);
    }

    @Test
    @DisplayName("同一人挂在两个成员盟里（脏档）：两边都只给一次 —— 它的下游是发钱，重复就是领两份")
    void duplicatedMembershipIsCollapsedOnBothStores() {
        // 只有 restore 造得出这种档：域上一个人只在一个盟里（join 走反查索引会挡），
        // 而发奖必须扛住脏档
        seedShared("AL-zh", "先入籍的盟", "ZH1", "P-shared", "P-zh2");
        seedShared("AL-ab", "后入籍的盟", "AB2", "P-ab1", "P-shared");
        seedNation("AL-zh", "AL-ab");

        List<String> memory = roster(memorySocial, memoryNations);
        List<String> mongo = roster(newMongoSocial(), newMongoNation());

        assertThat(memory).as("P-shared 只出现在第一次那一位上")
                .containsExactly("P-shared", "P-zh2", "P-ab1");
        assertThat(memory).as("去重这一手不是顺手写的，是一条能失败的判据")
                .doesNotHaveDuplicates();
        assertThat(mongo).as("Mongo 版同一条").containsExactlyElementsOf(memory);
    }

    @Test
    @DisplayName("成员联盟表为空的国家给出空名单（解散后的国家就是这一状）")
    void nationWithoutMemberAlliancesGivesEmptyRosterOnBothStores() {
        Nation disbanded = Nation.found(NATION, "已经亡了", "K-" + NATION, "AL-gone", 10L, 20L, T0,
                nationRules.rules());
        // tearDown 会把成员表整行清掉：这就是"亡国没有成员"在数据层的唯一形状
        disbanded.disband("K-" + NATION, T0 + 1L);

        assertThat(rosterOf(memorySocial, memoryNations, disbanded))
                .as("内存版空名单").isEmpty();
        assertThat(rosterOf(newMongoSocial(), newMongoNation(), disbanded))
                .as("Mongo 版同一条").isEmpty();
    }

    // ---------- 夹具 ----------

    /** 同一份数据写进两套实现（同一批 id、同一批成员）。 */
    private void seedAlliance(String id, String name, String tag, String... memberIds) {
        Alliance alliance = Alliance.create(id, name, tag, memberIds[0], 1_000L,
                socialRules.allianceRules());
        for (int i = 1; i < memberIds.length; i++) {
            alliance.join(memberIds[i]);
        }
        memorySocial.saveAlliance(alliance, 0L);
        newMongoSocial().saveAlliance(alliance, 0L);
    }

    /**
     * 同上，但成员表用 {@code restore} 直接给 —— 这样「同一人挂在两个盟」才建得出来。
     *
     * <p>顺序仍然由 {@code members} 的插入顺序决定（{@code Alliance.memberIds()} 读的就是那一列），
     * 所以"第一次出现的那一位"是可控的。
     */
    private void seedShared(String id, String name, String tag, String first, String shared) {
        Map<String, AllianceRole> members = new LinkedHashMap<>();
        members.put(first, AllianceRole.LEADER);
        members.put(shared, AllianceRole.MEMBER);
        Alliance alliance = Alliance.restore(id, name, tag, first, socialRules.allianceRules(), members,
                Map.of(), Map.of(), Map.of(), 1, 0L, 1_000L, 0, 0, 0L, 0L);
        memorySocial.saveAlliance(alliance, 0L);
        newMongoSocial().saveAlliance(alliance, 0L);
    }

    private void seedNation(String foundingAlliance, String... laterAlliances) {
        Nation nation = Nation.found(NATION, "花名册国", "K-" + NATION, foundingAlliance,
                100L, 200L, T0, nationRules.rules());
        long offset = 1L;
        for (String allianceId : laterAlliances) {
            nation.admitAlliance(allianceId, T0 + offset++);
        }
        memoryNations.insertIfAbsent(nation);
        newMongoNation().insertIfAbsent(nation);
    }

    /** 名单来自<b>库里读出来的那一份国家</b>，不是夹具手里的那份 —— 否则比的是同一个内存对象，等于没比。 */
    private List<String> roster(SocialStore social, NationStore nations) {
        return rosterOf(social, nations, nations.findById(NATION).orElseThrow());
    }

    private List<String> rosterOf(SocialStore social, NationStore nations, Nation nation) {
        return new NationMembership(social, nations).playerIdsOf(nation);
    }

    private SocialStore newMongoSocial() {
        requireMongo();
        return new MongoSocialStore(db.template(), socialRules);
    }

    private NationStore newMongoNation() {
        requireMongo();
        return new MongoNationStore(db.template(), nationRules);
    }

    private static void requireMongo() {
        Assumptions.assumeTrue(db != null,
                "本机没有可用的 MongoDB（" + TestMongo.uri() + "）—— 花名册在 Mongo 上的等价性今天没有被验证");
    }

    /** 从当前工作目录往上找 contract/config（surefire 的 cwd 是模块目录，不是仓库根）。 */
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
