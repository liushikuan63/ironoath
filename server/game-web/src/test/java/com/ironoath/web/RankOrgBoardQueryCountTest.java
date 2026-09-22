package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.season.SeasonSettlement;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.NationFoundReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.RankListResp;
import com.ironoath.web.dto.generated.RankType;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.rank.RankBoardService;
import com.ironoath.web.season.SeasonBoardStore;
import com.ironoath.web.season.SeasonRulesAssembler;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：组织榜投影<b>一次请求发几次存储往返</b>（收口清单本格：联盟榜 / 国家榜的 N+1）。
 * 依赖：test profile（内存存储）+ 三个按方法名计数的端口代理。
 *
 * <p><b>为什么这一处比联盟成员装配更重</b>：{@code projectOrgBoard} 的循环长度是<b>整张 POWER 榜</b>
 * —— 那张榜不截断，成员越多、榜越长，而它挂在 {@code /rank/list} 这条人人都要拉的读路径上。
 * 改之前每个榜上成员一次 {@code allianceOf}（国家榜再加一次 {@code findByAlliance}），
 * 一万人上榜就是一万人两次点查，每次请求。
 *
 * <p><b>判据是「次数」不是「结果」</b>：逐人点查与一次批量读回来的榜一字不差（投影本身是对的），
 * {@code RankEndpointTest} 那 20 多项再跑一遍也抓不到 N+1。
 *
 * <p><b>计数用 {@link Proxy} + 方法名字符串</b>：批量口在改之前不存在，写成符号引用会让这条判据
 * 编译不过，于是"先跑出红"这步就没法做（与 {@code AllianceMemberQueryCountTest} 同一手法）。
 *
 * <p><b>为什么自己 new 一个 {@link RankBoardService}</b>：容器那一份构造时拿到的是未包装的 bean，
 * 换不动。这里把容器里的 store <b>原地</b>包一层代理再交给新实例 —— 底层还是同一个库存，
 * 所以夹具用 MockMvc 建的盟与国，计数代理照样看得见，不需要 {@code @Primary} 置换全局 bean。
 *
 * <p><b>计数窗口</b>：{@code list} 第一次会惰性补拍当日快照（同一份投影多跑一遍），所以每个用例
 * 先暖一次再 {@code reset}，量到的是"榜已经在手"的那次读。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RankOrgBoardQueryCountTest {

    private static final String PLAYER_HEADER = "X-Player-Id";

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private SocialStore socialStore;
    @Autowired private NationStore nationStore;
    @Autowired private SeasonBoardStore boards;
    @Autowired private BotRegistry bots;
    @Autowired private ConfigRegistry configs;
    @Autowired private SeasonRulesAssembler assembler;
    @Autowired private TimeService timeService;
    @Autowired private QueryCounter counter;

    /** 端口与容器同一份库存，只是每一次调用都记账。 */
    private RankBoardService ranks;
    private String seasonId;

    @TestConfiguration
    static class CountingBeans {

        @Bean
        QueryCounter queryCounter() {
            return new QueryCounter();
        }
    }

    @BeforeEach
    void setUp() {
        ((com.ironoath.web.store.memory.InMemoryPlayerStore) players).clear();
        socialStore.clear();
        nationStore.clear();
        boards.clear();
        bots.clear();
        counter.reset();
        seasonId = assembler.timelineRules().seasonId();
        assertThat(seasonId).as("夹具前提：seasonId 来自 season 表首行，恒非空").isNotBlank();
        ranks = new RankBoardService(boards,
                counter.wrap(SocialStore.class, socialStore),
                counter.wrap(NationStore.class, nationStore),
                counter.wrap(PlayerRepository.class, players),
                bots, configs, assembler, timeService);
    }

    @Test
    @DisplayName("联盟榜：整榜成员的组织归属只能一次批量读；按 id 查盟只许按页计（每页 2 行 ⇒ 最多 2 次）")
    void allianceBoardProjectsTheWholePowerBoardWithBatchedReads() {
        Fixture fixture = fixture();

        // 暖一次：把当日快照的惰性补拍（会重跑同一份投影）排除在计数窗口之外
        ranks.list(fixture.leaderA, RankType.ALLIANCE, 1, 2);
        counter.reset();
        RankListResp resp = ranks.list(fixture.leaderA, RankType.ALLIANCE, 1, 2);

        // ---- 判据①：点查必须归零。改之前这里是 6 次（整榜每人一次）+ 1 次（我的名次那一趟）----
        assertThat(counter.countOf("allianceOf"))
                .as("逐个 allianceOf 的往返数与整张 POWER 榜等长，与页大小无关")
                .isZero();
        assertThat(counter.countOf("allianceById"))
                .as("按 id 查盟只剩「这一页每行的缩写」那一处，所以它按页计而不是按盟数计")
                .isLessThanOrEqualTo(2);
        // ---- 判据②：正向断言。只查"坏东西不存在"会在批量口整个没接上时 also 全绿 ----
        assertThat(counter.countOf("alliancesOf"))
                .as("组织归属一次批量读回，而不是根本没读")
                .isPositive();
        assertThat(counter.countOf("findByPlayerId"))
                .as("组织榜的名字来自盟档，不需要任何一份玩家存档")
                .isZero();

        // ---- 判据③：内容一字不变（名字仍是"盟名[缩写]"，分仍是成员合计）----
        assertThat(resp.entries()).as("两盟三成员，投影成两行").hasSize(2);
        assertThat(resp.entries().get(0).id()).isEqualTo(fixture.allianceA);
        assertThat(resp.entries().get(0).name()).isEqualTo("甲盟[JIA]");
        assertThat(resp.entries().get(0).value()).isEqualTo(13_000L);
        assertThat(resp.entries().get(0).tag()).isEqualTo("JIA");
        assertThat(resp.entries().get(1).id()).isEqualTo(fixture.allianceB);
        assertThat(resp.entries().get(1).name()).isEqualTo("乙盟[YI]");
        assertThat(resp.entries().get(1).value())
                .as("2000 + 4000 + 500 = 6500，不是任何一个人的分").isEqualTo(6_500L);
        assertThat(resp.myRank()).as("盟主在第一名那一行").isEqualTo(1);
    }

    @Test
    @DisplayName("国家榜：成员 → 联盟 → 国家这条链每一跳都批量；点查 allianceOf / findByAlliance / findById 全为零")
    void nationBoardResolvesMembersToNationsWithBatchedReads() {
        Fixture fixture = fixture();

        ranks.list(fixture.leaderA, RankType.NATION, 1, 2);
        counter.reset();
        RankListResp resp = ranks.list(fixture.leaderA, RankType.NATION, 1, 2);

        assertThat(counter.countOf("allianceOf"))
                .as("改之前整榜每人一次，与 POWER 榜等长")
                .isZero();
        assertThat(counter.countOf("findByAlliance"))
                .as("「这个盟在哪个国」同样不许逐个人问一遍")
                .isZero();
        assertThat(counter.countOf("findById"))
                .as("国名来自那次批量读，不再按国家数点查（NationStore 唯一的 findById 就是这个键）")
                .isZero();
        assertThat(counter.countOf("allianceById"))
                .as("国家榜没有缩写列，这一趟连按页的点查都不该有")
                .isZero();
        assertThat(counter.countOf("nationsByAlliance"))
                .as("联盟 → 国家一次批量")
                .isPositive();

        assertThat(resp.entries()).as("只有甲盟入籍，乙盟的成员不进国家榜").hasSize(1);
        assertThat(resp.entries().get(0).id()).isEqualTo(fixture.nationId);
        assertThat(resp.entries().get(0).name()).isEqualTo("铁誓王国");
        assertThat(resp.entries().get(0).value())
                .as("13,000 = 9,000 + 3,000 + 1,000，乙盟那 6,500 不算进来").isEqualTo(13_000L);
    }

    // ---------- 夹具 ----------

    /** 两个三人盟 + 一个盟建的国家；六个人的战力各不相同，接错档立刻看得见。 */
    private record Fixture(String leaderA, String allianceA, String allianceB, String nationId) {
    }

    private Fixture fixture() {
        String leaderA = newPlayer("盟主甲");
        String mateA1 = newPlayer("成员甲二");
        String mateA2 = newPlayer("成员甲三");
        String leaderB = newPlayer("盟主乙");
        String mateB1 = newPlayer("成员乙二");
        String mateB2 = newPlayer("成员乙三");

        String allianceA = createAlliance(leaderA, "甲盟", "JIA", mateA1, mateA2);
        String allianceB = createAlliance(leaderB, "乙盟", "YI", mateB1, mateB2);

        post200("/nation/found", leaderA, new NationFoundReq(newRequestId(), "铁誓王国", 100L, 200L));
        // 计数窗口之外读一次，只为了拿到国家 id 当期望值
        String nationId = nationStore.findByAlliance(allianceA).orElseThrow().id();

        report(leaderA, 9_000L);
        report(mateA1, 3_000L);
        report(mateA2, 1_000L);
        report(leaderB, 2_000L);
        report(mateB1, 4_000L);
        report(mateB2, 500L);
        return new Fixture(leaderA, allianceA, allianceB, nationId);
    }

    /** 走赛季榜那条生产写口（线上由 PowerRefreshService 驱动）。 */
    private void report(String playerId, long score) {
        boards.report(seasonId, SeasonSettlement.Board.POWER,
                new SeasonSettlement.Entry(playerId, playerId, score));
    }

    private String createAlliance(String leader, String name, String tag, String... mates) {
        String allianceId = post200("/alliance/create", leader,
                new AllianceCreateReq(newRequestId(), name, tag))
                .get("alliance").get("id").asText();
        for (String mate : mates) {
            post200("/alliance/apply", mate, new AllianceIdReq(newRequestId(), allianceId));
            post200("/alliance/review", leader, new AllianceReviewReq(newRequestId(), mate, true));
        }
        return allianceId;
    }

    /** 建号并补足主城等级与金币（建盟要 10 级 + 500 金币，都是真校验）。 */
    private String newPlayer(String nickName) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), nickName,
                1_700_000_000_000L, "")).playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(16);
        PlayerResourceState gold = save.resources().get("GOLD");
        if (gold != null) {
            save.putResource("GOLD", new PlayerResourceState(
                    100_000L, gold.cap(), gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        }
        players.save(save);
        return playerId;
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private JsonNode post200(String url, String playerId, Object req) {
        try {
            String body = mockMvc.perform(MockMvcRequestBuilders.post(url)
                            .header(PLAYER_HEADER, playerId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JsonUtils.toJson(req)))
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            JsonNode parsed = JsonUtils.readTree(body);
            assertThat(parsed.get("code").asInt())
                    .as("业务码必须为 0，实际响应=%s", parsed).isZero();
            return parsed.get("data");
        } catch (Exception e) {
            throw new IllegalStateException("请求 " + url + " 失败", e);
        }
    }

    /**
     * 按方法名累计调用次数。
     *
     * <p><b>键是方法名字符串、三个端口共用一张表</b>：这里刻意不区分队，因为被点的三个名字
     * （{@code allianceOf} / {@code findByAlliance} / {@code findById}）在 {@code SocialStore}、
     * {@code NationStore}、{@code PlayerRepository} 之间不重名。加端口时若引入同名的第三方方法，
     * 要把计数键改成「端口 + 方法名」。
     */
    static final class QueryCounter {

        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        int countOf(String methodName) {
            AtomicInteger seen = calls.get(methodName);
            return seen == null ? 0 : seen.get();
        }

        void reset() {
            calls.clear();
        }

        <T> T wrap(Class<T> port, T delegate) {
            InvocationHandler counting = (proxy, method, args) -> {
                calls.computeIfAbsent(method.getName(), key -> new AtomicInteger()).incrementAndGet();
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            return port.cast(Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[] {port}, counting));
        }
    }
}
