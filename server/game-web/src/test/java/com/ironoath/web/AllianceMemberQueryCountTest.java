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
import com.ironoath.web.dto.generated.AllianceMember;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.AllianceRole;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.SquadCreateReq;
import com.ironoath.web.dto.generated.SquadIdReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemorySocialStore;

/**
 * 职责：一次联盟成员装配<b>到底发几次存储往返</b>（收口清单 #425，`/alliance/sync` 的 N+1）。
 * 依赖：test profile（内存存储）+ 两个按方法名计数的端口代理。
 *
 * <p><b>为什么判据是「次数」而不是「结果」</b>：N+1 在结果上是完全正确的 —— 逐人点查与一次批量
 * 查回来的成员行一字不差，所以《SocialEndpointTest》那 74 项再跑一遍也抓不到它。能抓住它的只有
 * 往返计数：改之前 4 名成员 = 8 次点查，联盟上限 150 人 = 300 次。
 *
 * <p><b>计数用 {@link Proxy} 而不是 Mockito spy</b>：两份实现都是 {@code final class}，spy 造不出来；
 * 而端口方法的名字在这里是<b>字符串</b>不是符号 —— 于是同一条判据在「批量口还不存在」的版本上
 * 照样编译，能先跑出红（台账要的就是那个红），改完再跑绿。
 *
 * <p><b>代理持有自己的内存实现</b>，而不是包住容器里那颗 bean：{@code @Bean} 工厂方法的返回类型
 * 常常声明成端口接口（{@code MongoStorageGuard} 的注释里就写着这条），按实现类注入不保证拿得到。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AllianceMemberQueryCountTest {

    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final long NOW = 1_800_000_000_000L;

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private SocialStore socialStore;
    @Autowired private SocialAppService social;
    @Autowired private QueryCounter counter;

    /**
     * 两个存储端口各套一层计数代理，以 {@code @Primary} 交给 {@code SocialAppService}。
     *
     * <p>{@code MongoStorageGuard} 只在 {@code ironoath.storage=mongo} 下核对实现来源，
     * test profile 是 memory，所以这里不会被它判成"生产在用内存实现"。
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

        @Bean
        @Primary
        SocialStore countedSocialStore(QueryCounter counter) {
            return counter.wrap(SocialStore.class, new InMemorySocialStore());
        }
    }

    @BeforeEach
    void resetStores() {
        counter.playerStore().clear();
        socialStore.clear();
        counter.reset();
    }

    @Test
    @DisplayName("4 人联盟（两人同队、一人另队、一人无队）：装配只发两次批量往返，一次点查都不许有")
    void memberAssemblyIssuesTwoRoundTripsNotEight() {
        String leader = newPlayer("盟主甲");
        post200("/alliance/create", leader, new AllianceCreateReq(newRequestId(), "批量盟", "BATCH"));
        String mateA = newPlayer("队员乙");
        String mateB = newPlayer("队员丙");
        String lone = newPlayer("队员丁");
        String allianceId = socialStore.allianceOf(leader).orElseThrow().id();
        for (String applicant : List.of(mateA, mateB, lone)) {
            post200("/alliance/apply", applicant, new AllianceIdReq(newRequestId(), allianceId));
            post200("/alliance/review", leader, new AllianceReviewReq(newRequestId(), applicant, true));
        }
        String squadA = post200("/squad/create", leader, new SquadCreateReq(newRequestId(), "甲队"))
                .get("squad").get("id").asText();
        post200("/squad/join", mateA, new SquadIdReq(newRequestId(), squadA));
        String squadB = post200("/squad/create", mateB, new SquadCreateReq(newRequestId(), "乙队"))
                .get("squad").get("id").asText();

        // 先把"每人各自的答案"抄下来：批量口最容易错的是把某个人的行接错档，
        // 而这件事拿常量断言抄不住（四个人的城等一样），只能拿它自己改之前的读数当基准。
        Map<String, PlayerSave> before = new HashMap<>();
        for (String id : List.of(leader, mateA, mateB, lone)) {
            before.put(id, players.findByPlayerId(id).orElseThrow());
        }

        counter.reset();
        List<AllianceMember> rows = social.allianceMembers(leader, NOW);

        // ---- 判据①：往返次数。改之前这里是 4+4=8 次点查 ----
        assertThat(counter.countOf("findByPlayerId"))
                .as("昵称/战力/活跃时刻要一次批量取回，逐个 findByPlayerId 就是每人读一次整份存档")
                .isZero();
        assertThat(counter.countOf("squadOf"))
                .as("小队同理：逐个 squadOf 是每人一次索引点查")
                .isZero();
        assertThat(counter.countOf("findByPlayerId") + counter.countOf("squadOf"))
                .as("装配成员行的点查总次数：批量口接上后它与成员数无关，回到 2N 就是 N+1 又活了")
                .isLessThanOrEqualTo(2);
        // 省的是字节那一维（往返计数看不见）：150 人的名单不为三列标量搬 150 份整档
        assertThat(counter.countOf("findByPlayerIds"))
                .as("成员行只点三项，整档那一趟（资源表 / PVP 账本 / 科技 / 头像框集合）必须不再发生")
                .isZero();
        // ---- 判据②：正向断言。只查"坏东西不存在"会在批量口整个没接上时 also 全绿 ----
        assertThat(counter.countOf("findBriefs"))
                .as("成员行要的三项（昵称 / 展示战力 / 最近活跃）一次批量投影读回")
                .isEqualTo(1);
        assertThat(counter.countOf("squadsOf"))
                .as("小队一次批量")
                .isEqualTo(1);

        // ---- 判据③：内容一字不变 ----
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(AllianceMember::id)
                .as("成员集合本身不许因为批量而少一个人或多一个人")
                .containsExactlyInAnyOrder(leader, mateA, mateB, lone);
        Map<String, AllianceMember> byId = new HashMap<>();
        rows.forEach(row -> byId.put(row.id(), row));

        assertThat(byId.get(leader).name()).isEqualTo("盟主甲");
        assertThat(byId.get(mateA).name()).isEqualTo("队员乙");
        assertThat(byId.get(mateB).name()).isEqualTo("队员丙");
        assertThat(byId.get(lone).name()).isEqualTo("队员丁");
        assertThat(byId.get(leader).role()).isEqualTo(AllianceRole.LEADER);
        assertThat(byId.get(mateA).role()).isEqualTo(AllianceRole.MEMBER);
        assertThat(byId.get(leader).squadId()).isEqualTo(squadA);
        assertThat(byId.get(leader).squadName()).isEqualTo("甲队");
        assertThat(byId.get(mateA).squadId()).as("同队的两人拿到同一支小队").isEqualTo(squadA);
        assertThat(byId.get(mateA).squadName()).isEqualTo("甲队");
        assertThat(byId.get(mateB).squadId()).isEqualTo(squadB);
        assertThat(byId.get(mateB).squadName()).isEqualTo("乙队");
        assertThat(byId.get(lone).squadId()).as("无小队：两个字段一起 null").isNull();
        assertThat(byId.get(lone).squadName()).isNull();

        for (AllianceMember row : rows) {
            PlayerSave save = before.get(row.id());
            assertThat(row.name()).as("%s 的昵称不许退回 id（那是存档没读到的兜底）", row.id())
                    .isNotEqualTo(row.id());
            assertThat(row.power()).isEqualTo(save.power().displayPower());
            assertThat(row.lastActiveAt()).isEqualTo(save.lastLoginAt());
        }
    }

    @Test
    @DisplayName("不在任何联盟的玩家：直接回空，一次玩家/小队查询都不发")
    void playerWithoutAllianceIssuesNoQueryAtAll() {
        String stranger = newPlayer("散人戊");

        counter.reset();
        assertThat(social.allianceMembers(stranger, NOW)).isEmpty();

        assertThat(counter.countOf("findByPlayerId") + counter.countOf("squadOf")
                + counter.countOf("findBriefs") + counter.countOf("squadsOf"))
                .as("连盟都没有就没有成员可装配，批量口也不该空跑一次")
                .isZero();
    }

    // ---------- 夹具 ----------

    /** 建号并补足主城等级与金币；昵称由用例给，四行成员必须互相区分得开。 */
    private String newPlayer(String nickName) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), nickName,
                1_700_000_000_000L, "")).playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        // 小队要主城 5 级、联盟要 10 级
        save.setCityLevel(10);
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
     * 按方法名累计调用次数。
     *
     * <p>键是<b>方法名字符串</b>：批量口在改之前根本不存在，写成符号引用会让这条判据编译不过，
     * 于是"先跑出红"这一步就没法做。
     */
    static final class QueryCounter {

        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final Map<Class<?>, Object> delegates = new ConcurrentHashMap<>();

        int countOf(String methodName) {
            AtomicInteger seen = calls.get(methodName);
            return seen == null ? 0 : seen.get();
        }

        void reset() {
            calls.clear();
        }

        /** 计数代理背后那份真实内存实现，只给夹具清空用。 */
        InMemoryPlayerStore playerStore() {
            return (InMemoryPlayerStore) delegates.get(PlayerRepository.class);
        }

        <T> T wrap(Class<T> port, T delegate) {
            delegates.put(port, delegate);
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
