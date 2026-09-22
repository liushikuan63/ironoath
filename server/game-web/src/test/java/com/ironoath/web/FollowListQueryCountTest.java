package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
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
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.FollowReq;
import com.ironoath.web.dto.generated.FriendListView;
import com.ironoath.web.dto.generated.FriendView;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：打开<b>关注列表</b>到底发几次存储往返（收口清单 #428 未做栏的第一项，同族 N+1）。
 * 依赖：test profile（内存存储）+ 一个按方法名计数的 {@link PlayerRepository} 代理。
 *
 * <p><b>改之前是"每关注一个人读一次整份存档"</b>：上限 {@code global.SOCIAL_FOLLOW_MAX}（50），
 * 于是每次打开社交面板最多 50 趟整档读取，而这一行用到的只有昵称与最近活跃时刻两项。
 * 联盟成员装配那处（#425）用的是同一个形状的循环，这条是它的同族。
 *
 * <p><b>判据是「次数」不是「结果」</b>：批量与逐个点查给出的列表一字不差（{@code SocialEndpointTest}
 * 再跑一遍也抓不到），能抓住的只有计数。计数键是<b>方法名字符串</b>，所以批量口没接上时这条判据
 * 照样编译、照样先跑出红（{@code AllianceMemberQueryCountTest} 同一手法）。
 *
 * <p><b>为什么允许那"剩下的一次"点查</b>：{@code requirePlayer} 要验的是<b>调用方自己</b>存不存在，
 * 与关注数无关。判据因此写成"恰好一次"而不是"零次" —— 它同样会随关注人数增长而红，只是基线是 1。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FollowListQueryCountTest {

    private static final String PLAYER_HEADER = "X-Player-Id";

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private SocialStore socialStore;
    @Autowired private SocialAppService social;
    @Autowired private QueryCounter counter;

    /**
     * 玩家仓储套一层计数代理，以 {@code @Primary} 交给 {@code SocialAppService}。
     *
     * <p>{@code SocialStore} 不包：它在这一条读路径上只有 {@code followedPlayers} 一次调用，
     * 包住反而会把"名单本身读一次"混进判据里。
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
    @DisplayName("关注 3 人再涨到 6 人：整份存档只批量读一次，点查次数一次都不许多")
    void followListReadsFolloweesInOneBatchNotOnePerFollowee() {
        String me = newPlayer("我自己");
        String first = newPlayer("关注甲");
        String second = newPlayer("关注乙");
        String third = newPlayer("关注丙");
        follow(me, first);
        follow(me, second);
        follow(me, third);

        social.follows(me);   // 暖一次，把任何"首次读才有"的往返排除在窗口外
        counter.reset();
        FriendListView three = social.follows(me);

        // ---- 判据①：点查必须与关注数脱钩。改之前这里是 1 + 3 = 4 次 ----
        int pointReads = counter.countOf("findByPlayerId");
        assertThat(pointReads)
                .as("整份存档的逐人点查：只剩 requirePlayer 验调用方自己那一次")
                .isEqualTo(1);
        // ---- 判据②：正向断言，批量口真的接上了 ----
        assertThat(counter.countOf("findByPlayerIds"))
                .as("关注对象的存档一次批量读回")
                .isEqualTo(1);

        // ---- 判据③：内容一字不变（顺序 = 最近关注的在前）----
        assertThat(three.friends()).extracting(FriendView::playerId)
                .as("名单与顺序不许因为批量而变").containsExactly(third, second, first);
        assertThat(three.friends()).extracting(FriendView::name)
                .containsExactly("关注丙", "关注乙", "关注甲");
        for (FriendView row : three.friends()) {
            PlayerSave save = players.findByPlayerId(row.playerId()).orElseThrow();
            assertThat(row.online()).as("%s 没有 websocket 连接，在线必须是 false", row.playerId()).isFalse();
            assertThat(row.lastSeenAt())
                    .as("离线的行回的是存档里的最近登录时刻")
                    .isEqualTo(save.lastLoginAt());
        }

        String fourth = newPlayer("关注丁");
        String fifth = newPlayer("关注戊");
        String sixth = newPlayer("关注己");
        follow(me, fourth);
        follow(me, fifth);
        follow(me, sixth);

        social.follows(me);
        counter.reset();
        FriendListView six = social.follows(me);

        assertThat(counter.countOf("findByPlayerId"))
                .as("关注数从 3 涨到 6，点查次数跟着涨就是 N+1 又活了")
                .isEqualTo(pointReads);
        assertThat(counter.countOf("findByPlayerIds")).isEqualTo(1);
        assertThat(six.friends()).as("六个人一个不少").hasSize(6);
        assertThat(six.friends()).extracting(FriendView::name)
                .containsExactly("关注己", "关注戊", "关注丁", "关注丙", "关注乙", "关注甲");
    }

    @Test
    @DisplayName("谁的都没关注：整份存档一次都不读（批量口不许为空名单空跑）")
    void anEmptyFollowListIssuesNoBatchedReadAtAll() {
        String me = newPlayer("独行者");

        social.follows(me);
        counter.reset();
        assertThat(social.follows(me).friends()).isEmpty();

        assertThat(counter.countOf("findByPlayerIds"))
                .as("新号第一次打开社交面板就是空名单，那不该是一次存储往返")
                .isZero();
        assertThat(counter.countOf("findByPlayerId"))
                .as("仍然只有验调用方自己那一次")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("关注名单里有个已经查不到存档的人：昵称退回 id、活跃时刻 0，与改之前同一条兜底")
    void aFolloweeWithoutSaveStillFallsBackToIdOnBothFields() {
        String me = newPlayer("我自己");
        // 直接写进存储：服务层那条关注口会校验目标存在，而"有关系没档案"是删号之后才会出现的状态
        socialStore.follow(me, "P-gone");

        counter.reset();
        FriendListView view = social.follows(me);

        assertThat(view.friends()).hasSize(1);
        assertThat(view.friends().get(0).playerId()).isEqualTo("P-gone");
        assertThat(view.friends().get(0).name())
                .as("读不到存档就回 id，而不是回 null 把面板画成空行")
                .isEqualTo("P-gone");
        assertThat(view.friends().get(0).lastSeenAt()).isZero();
        assertThat(counter.countOf("findByPlayerId"))
                .as("缺失口径不是多点查一遍的理由")
                .isEqualTo(1);
    }

    // ---------- 夹具 ----------

    private String newPlayer(String nickName) {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), nickName.trim(),
                1_700_000_000_000L, "")).playerId();
    }

    private void follow(String playerId, String targetPlayerId) {
        post200("/social/follow", playerId,
                new FollowReq("req-" + UUID.randomUUID(), targetPlayerId));
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

    /** 按方法名累计调用次数（批量口在改之前不存在，所以键只能是字符串）。 */
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
