package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.ApplicantView;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：读一页<b>待审入盟申请</b>到底发几次存储往返（同族收口的最后一处有界高频点）。
 * 依赖：test profile（内存存储）+ 一个按「方法名 + 第一个入参」计数的 {@link PlayerRepository} 代理。
 *
 * <p><b>改之前是一行两趟</b>：{@code nickname(applicantId)} 与 {@code cityLevelOf(applicantId)} 各自
 * 点查一次同一个申请人的整份存档，而这一页的长度是
 * {@code min(global.ALLIANCE_APPLICATION_LIST_LIMIT, 申请数)}（该配置实测 50）⇒ 盟主每打开一次
 * 审核面板最多 100 趟整档读取，而这一行只用昵称与主城等级两个字段。
 *
 * <p><b>为什么计数键要带第一个入参</b>：同一条读路径上 {@code requireAllianceOf} 还合法地读
 * <b>盟主自己</b>的存档；只按方法名计数会把两件事混成一锅，判据就退化成"采样两次比大小"。
 * 带上入参之后可以精确钉住"申请人的整档点查 = 0"。
 *
 * <p><b>判据是次数不是结果</b>：批量与逐个点查给出的行一字不差，端点测试抓不到它
 * （与 {@code AllianceMemberQueryCountTest} / {@code FollowListQueryCountTest} 同一族）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AllianceApplicationQueryCountTest {

    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final long NOW = 1_800_000_000_000L;

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private SocialStore socialStore;
    @Autowired private SocialAppService social;
    @Autowired private QueryCounter counter;

    /**
     * 玩家仓储换成"包一层计数代理、背后自己 new 一份内存实现"的 {@code @Primary} bean。
     *
     * <p>不包住容器那颗而是自己 new：这些 {@code @Bean} 工厂方法的返回类型声明成端口接口，
     * 按实现类注入不保证拿得到（{@code MongoStorageGuard} 的注释里写着这条）。
     */
    @TestConfiguration
    static class CountingBeans {

        @Bean
        QueryCounter queryCounter() {
            return new QueryCounter();
        }

        @Bean
        @Primary
        PlayerRepository countedPlayers(QueryCounter counter) {
            return counter.wrap(PlayerRepository.class, new InMemoryPlayerStore());
        }
    }

    @BeforeEach
    void resetStores() {
        counter.playerStore().clear();
        socialStore.clear();
        counter.reset();
    }

    @Test
    @DisplayName("一页申请：对申请人一次点查都不许有，列表投影只批量读一次；行内容一字不变")
    void onePageOfApplicantsIsOneBatchedReadNotTwoPointReadsEach() {
        String leader = newPlayer("盟主甲", 16);
        String mate = newPlayer("元老乙", 16);
        String allianceId = createAlliance(leader, mate);
        String first = newPlayer("申请甲", 7);
        String second = newPlayer("申请乙", 12);
        apply(first, allianceId);
        apply(second, allianceId);

        social.allianceApplications(leader, NOW);   // 暖一次，把任何"首次读才有"的往返排除在窗口外
        counter.reset();
        var resp = social.allianceApplications(leader, NOW);

        // ---- 判据①：申请人的点查必须归零。改之前这里是每人 2 次 = 4 次 ----
        assertThat(counter.readsOf("findByPlayerId", first))
                .as("昵称与城等各点查一次同一个申请人 —— 两个字段来自同一份存档")
                .isZero();
        assertThat(counter.readsOf("findByPlayerId", second)).isZero();
        assertThat(counter.countOf("findByPlayerId"))
                .as("整条路径上仍合法地读盟主自己那一份（requireAllianceOf），所以这里只钉申请人")
                .isEqualTo(counter.readsOf("findByPlayerId", leader));
        // ---- 判据②：整档那一趟必须不再发生。往返计数看不见"省了字节"，只能钉这个维度----
        assertThat(counter.countOf("findByPlayerIds"))
                .as("这一屏只用昵称与主城等级两列，不许再为它们反序列化整份存档"
                        + "（投影口开出来后，列表类调用方回到整档读就是白搬）")
                .isZero();
        // ---- 判据②′：正向断言，投影批量口真的接上了（只查"坏东西不存在"会在两个口都没接上时 also 全绿）----
        assertThat(counter.countOf("findBriefs"))
                .as("这一页申请人的昵称与城等一次批量投影读回")
                .isEqualTo(1);

        // ---- 判据③：内容一字不变（顺序是"按申请人 id 升序"那条可复现口径，不靠随机 id 猜先后）----
        List<String> ids = resp.applicants().stream().map(ApplicantView::playerId).toList();
        assertThat(ids).as("本页顺序仍是申请人 id 升序").isEqualTo(ids.stream().sorted().toList());
        Map<String, ApplicantView> byId = new HashMap<>();
        resp.applicants().forEach(row -> byId.put(row.playerId(), row));
        assertThat(byId.get(first).nickname()).isEqualTo("申请甲");
        assertThat(byId.get(first).mainCityLevel())
                .as("城等接错档（拿甲的等级配乙的行）会当场红").isEqualTo(7);
        assertThat(byId.get(second).nickname()).isEqualTo("申请乙");
        assertThat(byId.get(second).mainCityLevel()).isEqualTo(12);
        assertThat(resp.total()).as("总数是申请数，不是本页条数").isEqualTo(2);
    }

    @Test
    @DisplayName("申请数从 2 涨到 5：整档读取仍是一趟，申请人点查仍为零")
    void theReadCountDoesNotGrowWithTheNumberOfApplications() {
        String leader = newPlayer("盟主甲", 16);
        String mate = newPlayer("元老乙", 16);
        String allianceId = createAlliance(leader, mate);
        String first = newPlayer("申请甲", 7);
        String second = newPlayer("申请乙", 12);
        apply(first, allianceId);
        apply(second, allianceId);

        social.allianceApplications(leader, NOW);
        counter.reset();
        social.allianceApplications(leader, NOW);
        int withTwo = counter.countOf("findBriefs");
        assertThat(counter.readsOf("findByPlayerId", first) + counter.readsOf("findByPlayerId", second))
                .as("夹具前提：两名申请人的点查在改之前各是 2 次").isZero();

        String third = newPlayer("申请丙", 3);
        String fourth = newPlayer("申请丁", 9);
        String fifth = newPlayer("申请戊", 15);
        apply(third, allianceId);
        apply(fourth, allianceId);
        apply(fifth, allianceId);

        social.allianceApplications(leader, NOW);
        counter.reset();
        var resp = social.allianceApplications(leader, NOW);

        assertThat(counter.countOf("findBriefs"))
                .as("申请数 2 → 5 就把趟数跟着涨上去，正是 N+1 的定义")
                .isEqualTo(withTwo);
        assertThat(counter.readsOf("findByPlayerId", third)
                + counter.readsOf("findByPlayerId", fourth)
                + counter.readsOf("findByPlayerId", fifth))
                .as("新增的三名申请人同样一次点查都不该有")
                .isZero();
        assertThat(resp.applicants()).as("五个人一个不少").hasSize(5);
    }

    @Test
    @DisplayName("没有待审申请：一次存档读都不发（批量口不许为空名单空跑）")
    void anEmptyPageIssuesNoReadAtAll() {
        String leader = newPlayer("光杆盟主", 16);
        createAlliance(leader);

        social.allianceApplications(leader, NOW);
        counter.reset();
        assertThat(social.allianceApplications(leader, NOW).applicants()).isEmpty();

        assertThat(counter.countOf("findBriefs"))
                .as("空页不该去库里捞一趟")
                .isZero();
    }

    @Test
    @DisplayName("申请还挂着但查不到那个人：昵称回 id、城等回 0 —— 与改之前那两个助手逐字同口径")
    void anApplicantWithoutSaveStillFallsBackOnBothFields() {
        String leader = newPlayer("盟主甲", 16);
        String mate = newPlayer("元老乙", 16);
        String allianceId = createAlliance(leader, mate);
        // 直接往存储里塞一条"有申请没档案"的：删号不清申请，正是这条兜底存在的理由
        socialStore.addApplication(allianceId, "P-gone");

        counter.reset();
        var resp = social.allianceApplications(leader, NOW);

        assertThat(resp.applicants()).hasSize(1);
        assertThat(resp.applicants().get(0).nickname())
                .as("读不到档就回 id，而不是回 null 把这一行整个画没")
                .isEqualTo("P-gone");
        assertThat(resp.applicants().get(0).mainCityLevel()).isZero();
        assertThat(counter.countOf("findBriefs"))
                .as("缺失口径不是多点查一遍的理由：仍然只有那一次批量")
                .isEqualTo(1);
    }

    // ---------- 夹具 ----------

    private String createAlliance(String leader, String... mates) {
        String allianceId = post200("/alliance/create", leader,
                new AllianceCreateReq(newRequestId(), "审核盟" + UUID.randomUUID().toString().substring(0, 6),
                        "A" + UUID.randomUUID().toString().substring(0, 5)))
                .get("alliance").get("id").asText();
        for (String mate : mates) {
            apply(mate, allianceId);
            post200("/alliance/review", leader, new AllianceReviewReq(newRequestId(), mate, true));
        }
        return allianceId;
    }

    private void apply(String applicant, String allianceId) {
        post200("/alliance/apply", applicant, new AllianceIdReq(newRequestId(), allianceId));
    }

    /** 建号并补足主城等级与金币（建盟要 10 级 + 500 金币，都是真校验）。 */
    private String newPlayer(String nickName, int cityLevel) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), nickName,
                1_700_000_000_000L, "")).playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(cityLevel);
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
     * 按「方法名 + 第一个入参」累计调用次数。
     *
     * <p>键是字符串而不是符号引用：批量口在改之前不存在，写成符号引用会让整条判据编译不过，
     * "先跑出红"这一步就没法做（台账要的就是那个红）。
     */
    static final class QueryCounter {

        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final Map<Class<?>, Object> delegates = new ConcurrentHashMap<>();

        int countOf(String methodName) {
            AtomicInteger seen = calls.get(methodName);
            return seen == null ? 0 : seen.get();
        }

        int readsOf(String methodName, String firstArg) {
            AtomicInteger seen = calls.get(methodName + "(" + firstArg + ")");
            return seen == null ? 0 : seen.get();
        }

        void reset() {
            calls.clear();
        }

        /** 计数代理背后那份真实内存实现，只给夹具清空与删档用。 */
        InMemoryPlayerStore playerStore() {
            return (InMemoryPlayerStore) delegates.get(PlayerRepository.class);
        }

        <T> T wrap(Class<T> port, T delegate) {
            delegates.put(port, delegate);
            InvocationHandler counting = (proxy, method, args) -> {
                String firstArg = args == null || args.length == 0 || args[0] == null
                        ? "-" : String.valueOf(args[0]);
                calls.computeIfAbsent(method.getName() + "(" + firstArg + ")",
                        key -> new AtomicInteger()).incrementAndGet();
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
