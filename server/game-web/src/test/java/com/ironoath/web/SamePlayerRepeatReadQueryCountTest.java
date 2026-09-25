package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
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
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.AllianceRole;
import com.ironoath.web.dto.generated.AllianceRoleReq;
import com.ironoath.web.dto.generated.HelpResp;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：通知文案里<b>同一个人的昵称被读了几次整档</b>（收口清单 #430：循环内重复调 {@code nickname(...)}）。
 * 依赖：test profile（内存存储）+ 一个按「方法名 + 第一个入参」计数的 {@link PlayerRepository} 代理。
 *
 * <p><b>这一族与 #425 / #428 / #429 的差别</b>：那几处是"每人一次"，乘数随榜长 / 城数 / 关注数走；
 * 这里循环内每次读的都是<b>同一个人</b> —— 结果完全正确，只是同一份存档被读了 N 遍，
 * 所以既不会被任何结果断言抓到，也不会被"只按方法名计数"的代理抓到（旁边的循环体还合法地
 * 读别人的档）。计数键因此多带一个入参：只数"读了谁的档"。
 *
 * <p><b>两处落点</b>：① 入盟申请通知（乘数 = 有权限被通知的官员数，一盟最多 150 人）；
 * ② 一键帮助（曾经的乘数 = 本次真正帮到的条数 —— 每条帮助都把活动锚点算一遍，而锚点只要建档时刻
 * 却走 {@code findByPlayerId} 搬整档；台账 #447 用窄读口 {@code findCreatedAt} 把它归零）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SamePlayerRepeatReadQueryCountTest {

    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final long NOW = 1_800_000_000_000L;

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private SocialStore socialStore;
    @Autowired private SocialAppService social;
    @Autowired private QueryCounter counter;

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
    @DisplayName("入盟申请通知：申请人那份存档的读次数与「要通知几个官员」无关，且每个官员都仍收到带昵称的那条")
    void applicantSaveIsReadOnceNotOncePerNotifiedOfficer() {
        String leader = newPlayer("盟主甲");
        String elder = newPlayer("长老乙");
        String officer = newPlayer("官员丙");
        String allianceId = createAlliance(leader, officer, elder);

        String firstApplicant = newPlayer("申请一人");
        counter.reset();
        post200("/alliance/apply", firstApplicant, new AllianceIdReq(newRequestId(), allianceId));
        int withLeaderOnly = counter.readsOf("findByPlayerId", firstApplicant);
        assertThat(withLeaderOnly).as("夹具前提：这条路径确实读了申请人（通知 + 回执摘要）").isPositive();

        // 把两名成员提成官员与长老：他们都要收通知 ⇒ 通知人数从 1 变 3
        post200("/alliance/setRole", leader,
                new AllianceRoleReq(newRequestId(), officer, AllianceRole.OFFICER));
        post200("/alliance/setRole", leader,
                new AllianceRoleReq(newRequestId(), elder, AllianceRole.ELDER));

        String secondApplicant = newPlayer("申请二人");
        counter.reset();
        post200("/alliance/apply", secondApplicant, new AllianceIdReq(newRequestId(), allianceId));
        int withThreeOfficers = counter.readsOf("findByPlayerId", secondApplicant);

        assertThat(withThreeOfficers)
                .as("改之前这里是「每个要通知的人一次申请人整档」：1 人时读 1 次、3 人时读 3 次")
                .isEqualTo(withLeaderOnly);

        // 正向：昵称仍然读得到（文案是昵称，不是 id），三个人一条都不少
        assertThat(titleOf(leader)).contains("申请一人 申请加入联盟");
        for (String officerId : List.of(leader, officer, elder)) {
            assertThat(titleOf(officerId))
                    .as("提了职位之后这个人也要收到第二条申请的通知")
                    .contains("申请二人 申请加入联盟");
        }
    }

    @Test
    @DisplayName("一键帮助：整条路径上本人存档只读一次（昵称）；帮 2 条时曾是 3 次")
    void helperSaveIsReadOnceForTheNicknameNotOncePerHelpedRequest() {
        String helper = newPlayer("帮助者");
        String a = newPlayer("被帮甲");
        String b = newPlayer("被帮乙");
        seedHelp(a, "bld_a");
        seedHelp(b, "bld_b");

        counter.reset();
        HelpResp two = social.helpAll(helper, NOW);
        assertThat(two.helped()).as("夹具前提：两条都帮到了").isEqualTo(2);
        // 改之前这里是 1 + 条数：昵称一次，外加每条帮助把活动锚点算一遍 —— 而锚点只要建档时刻，
        // 却走 findByPlayerId 把整份存档搬回来（台账 #447：窄读口 findCreatedAt 之后这一项归零）。
        // 写成绝对值而不是"1 + 条数"：留解释式的式子等于把那个缺陷当规格供起来。
        assertThat(counter.readsOf("findByPlayerId", helper))
                .as("帮 2 条也只读昵称那一次：活动锚点走 findCreatedAt，不再搬整档")
                .isEqualTo(1);

        String c = newPlayer("被帮丙");
        seedHelp(c, "bld_c");
        counter.reset();
        HelpResp oneMore = social.helpAll(helper, NOW);
        assertThat(oneMore.helped()).as("夹具前提：第三条也帮到了").isEqualTo(1);
        assertThat(counter.readsOf("findByPlayerId", helper))
                .as("与上面那条合起来才说明这一项既不随条数长、也不是靠夹具侥幸")
                .isEqualTo(1);

        assertThat(titleOf(a)).contains("帮助者 帮助了你：");
        assertThat(titleOf(b)).contains("帮助者 帮助了你：");
        assertThat(titleOf(c)).contains("帮助者 帮助了你：");
    }

    @Test
    @DisplayName("一条都帮不上（全部跳过）：本人存档一次都不读 —— 昵称提到循环外时必须保持这一条")
    void anAllSkippedBatchStillReadsNothingForTheHelper() {
        String helper = newPlayer("帮助者");
        String target = newPlayer("被帮甲");
        seedHelp(target, "bld_a");
        social.help(helper, "H-" + "bld_a", NOW);   // 先帮掉一次，同一条目标再帮就是跳过

        counter.reset();
        HelpResp resp = social.help(helper, "H-bld_a", NOW);

        assertThat(resp.helped()).as("夹具前提：重复帮助算跳过").isZero();
        assertThat(counter.readsOf("findByPlayerId", helper))
                .as("全部跳过的批次不该为了拼一句没人收到的文案去读整份存档")
                .isZero();
    }

    // ---------- 夹具 ----------

    private String createAlliance(String leader, String... mates) {
        String allianceId = post200("/alliance/create", leader,
                new AllianceCreateReq(newRequestId(), "通知盟" + UUID.randomUUID().toString().substring(0, 6),
                        "N" + UUID.randomUUID().toString().substring(0, 5)))
                .get("alliance").get("id").asText();
        for (String mate : mates) {
            post200("/alliance/apply", mate, new AllianceIdReq(newRequestId(), allianceId));
            post200("/alliance/review", leader, new AllianceReviewReq(newRequestId(), mate, true));
        }
        return allianceId;
    }

    private void seedHelp(String fromPlayerId, String targetKey) {
        socialStore.putHelpRequest(new SocialStore.HelpRequest("H-" + targetKey, fromPlayerId,
                "被帮的人", "BUILDING", targetKey, "兵营", NOW + 3_600_000L, 0));
    }

    /** 某人最新一条社交事件的标题（本类的夹具里每人每次只推一条，够用且失败信息直白）。 */
    private String titleOf(String playerId) {
        List<SocialStore.SocialEvent> events = socialStore.unreadEvents(playerId);
        assertThat(events).as("%s 至少要收到一条事件", playerId).isNotEmpty();
        StringBuilder all = new StringBuilder();
        for (SocialStore.SocialEvent event : events) {
            all.append(event.title()).append('\n');
        }
        return all.toString();
    }

    private String newPlayer(String nickName) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), nickName,
                1_700_000_000_000L, "")).playerId();
        com.ironoath.core.player.PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(16);   // 建盟要 10 级、任命要盟主在盟
        // 建盟真扣 500 金币（不是绕过校验：余额够才会真的扣）
        com.ironoath.core.player.PlayerResourceState gold = save.resources().get("GOLD");
        if (gold != null) {
            save.putResource("GOLD", new com.ironoath.core.player.PlayerResourceState(
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
                            .content(req == null ? "{}" : JsonUtils.toJson(req)))
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
     * <p>比 {@code AllianceMemberQueryCountTest} 那套多带一个入参：本格的循环体里合法地也要读
     * <b>别人</b>的存档，只按方法名计数会把它们混进来，判据就退化成"两次采样比大小"。
     */
    static final class QueryCounter {

        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final Map<Class<?>, Object> delegates = new ConcurrentHashMap<>();

        int readsOf(String methodName, String firstArg) {
            AtomicInteger seen = calls.get(methodName + "(" + firstArg + ")");
            return seen == null ? 0 : seen.get();
        }

        void reset() {
            calls.clear();
        }

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
