package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.dto.generated.ChatChannel;
import com.ironoath.web.dto.generated.ChatSendReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.SocialEventView;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.ws.GameWebSocketHandler;

/**
 * 职责：把「推送与离线补偿共用同一结构」（B10 验收 5 / 12 的明文要求）变成一条能失败的判据。
 * 依赖：Spring Boot Test + {@code @MockBean PushGateway}（捕获推出去的对象）。
 *
 * <p><b>为什么需要这个类</b>：两条路此前各自都"看起来对" ——
 * 离线那条走 {@code toEventView}（{@code SocialEventView}：{@code coord} 对象 + {@code expired} 布尔），
 * 推送那条直接发存储记录 {@code SocialStore.SocialEvent}（{@code coordX}/{@code coordY} 两个可空数 + {@code expireAt}）。
 * 两个 record 的字段名不同、序列化出来的键集也不同，而**没有任何用例同时看这两边**，
 * 所以形状分叉不会让任何测试变红。
 *
 * <p>第一个把两路事件放进同一个列表的消费者是 B22 的聊天页签（未读账）：
 * 它按 {@code relatedId} 分组、按 {@code occurredAt} 排序。字段一旦缺席，
 * 表现不是报错而是"某一路的事件读不出来"，且只在在线那条路上发生 —— 离线的用例全绿也发现不了。
 *
 * <p>本类换掉真实 WebSocket 网关（{@code @MockBean}）：这里要证的是「推出去的是什么」，
 * 不是「推得到不到」；真实网关那一半由 WS 运行时探针覆盖。
 *
 * <p>断言刻意分成两半：<b>载荷是 {@code SocialEventView} 实例</b>（防"改成别的形状"）
 * 与<b>键集与离线那条路逐键相等</b>（防"两边一起改错"）。只断言后一半的话，
 * 两边同时退化成存储记录也会全绿。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SocialPushShapeTest {

    private static final String PLAYER_HEADER = "X-Player-Id";

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private SocialStore socialStore;

    /**
     * 换掉真实网关，用来捕获"推出去的是什么"。
     *
     * <p>桩打在**实现类**而不是 {@code PushGateway} 端口上：{@code WebSocketConfig} 按具体类型
     * 注入 {@link GameWebSocketHandler}，只替换端口会让应用上下文起不来
     * （表现得像"测试环境坏了"，实际是注入点有两个）。
     */
    @MockBean private GameWebSocketHandler pushGateway;

    /** 捕获到的推送。推送走异步线程池，所以断言前要等一下（见 {@link #awaitPush}）。 */
    private final List<CapturedPush> pushes = new ArrayList<>();

    private record CapturedPush(String playerId, String type, Object payload) {
    }

    @BeforeEach
    void setUp() {
        ((InMemoryPlayerStore) players).clear();
        socialStore.clear();
        pushes.clear();
        when(pushGateway.isOnline(anyString())).thenReturn(true);
        doAnswer(invocation -> {
            pushes.add(new CapturedPush(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2)));
            return true;
        }).when(pushGateway).pushToPlayer(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("私聊推送的载荷与离线补偿的事件同形：都是 SocialEventView（coord + expired，没有 expireAt）")
    void privateMessagePushHasTheSameShapeAsOfflineEvent() throws Exception {
        String sender = newPlayer();
        String recipient = newPlayer();

        postChat(sender, new ChatSendReq("req-" + UUID.randomUUID(),
                ChatChannel.PRIVATE, "在吗", recipient));
        CapturedPush push = awaitPush("PRIVATE_MESSAGE", recipient);

        // 1) 推出去的对象本身就是视图（而不是存储记录）—— 这是"两路同结构"最直接的证据
        assertThat(push.payload())
                .as("推送载荷必须是 SocialEventView；发存储记录会让客户端读到 undefined 的 expired/coord")
                .isInstanceOf(SocialEventView.class);
        SocialEventView pushed = (SocialEventView) push.payload();
        assertThat(pushed.type().name()).isEqualTo("PRIVATE_MESSAGE");
        assertThat(pushed.relatedId()).as("relatedId 就是发信人 id —— 客户端的未读账按它分组")
                .isEqualTo(sender);

        // 2) 与离线那条路（/social/summary 的事件）字段集一致：同结构不是"看起来像"，是可以逐键比较的
        JsonNode offline = offlineEventOfType(recipient, "PRIVATE_MESSAGE");
        assertThat(keysOf(JsonUtils.toJson(pushed)))
                .as("推送与离线补偿的键集必须一致（B10 验收 5/12）")
                .isEqualTo(keysOf(offline));
        assertThat(keysOf(offline)).contains("relatedId", "occurredAt", "expired");
        assertThat(keysOf(offline)).as("存储记录的字段名不该漏进任何一路")
                .doesNotContain("expireAt", "coordX", "coordY");
    }

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq("req-" + UUID.randomUUID(),
                "dev-" + UUID.randomUUID(), "推送形状测试", 1_700_000_000_000L, "")).playerId();
    }

    private CapturedPush awaitPush(String type, String playerId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline) {
            for (CapturedPush push : List.copyOf(pushes)) {
                if (type.equals(push.type()) && playerId.equals(push.playerId())) {
                    return push;
                }
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("3 秒内没有捕获到 " + type + " 推送给 " + playerId
                + "：已捕获 " + pushes);
    }

    private JsonNode offlineEventOfType(String playerId, String type) throws Exception {
        MvcResult result = mockMvc.perform(get("/social/summary").header(PLAYER_HEADER, playerId))
                .andExpect(status().isOk()).andReturn();
        JsonNode root = JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        for (JsonNode event : root.path("data").path("events")) {
            if (type.equals(event.path("type").asText())) {
                return event;
            }
        }
        throw new AssertionError("离线补偿里没有 " + type + " 事件：" + root.path("data").path("events"));
    }

    private static Set<String> keysOf(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    private static Set<String> keysOf(String json) throws Exception {
        return keysOf(JsonUtils.readTree(json));
    }

    private void postChat(String playerId, ChatSendReq body) throws Exception {
        MvcResult result = mockMvc.perform(post("/chat/send")
                        .header(PLAYER_HEADER, playerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtils.toJson(body)))
                .andExpect(status().isOk()).andReturn();
        JsonNode root = JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(root.path("code").asInt())
                .as("chat/send 失败：" + result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isZero();
    }
}
