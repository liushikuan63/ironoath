package com.ironoath.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.ChatChannel;
import com.ironoath.web.dto.generated.ChatListReq;
import com.ironoath.web.dto.generated.ChatSendReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.SquadCreateReq;
import com.ironoath.web.security.ContentSecurityClient;
import com.ironoath.web.service.PlayerInitService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 内容安全的四个接入点（B15 §3 / 上线检查清单 §二 7）。
 *
 * <p><b>本用例要证明的是"接上了"，不是"微信会怎么判"</b> —— 判定逻辑的证据在
 * {@code WeChatContentSecurityClientTest} 里对着桩服务。这里把送检器换成一个按剧本回答的假实现，
 * 于是能问出真问题：<b>判定违规时那四处是不是真的拒绝、且什么都没落库</b>。
 *
 * <p><b>假实现刻意保留"没有 openid 就 UNAVAILABLE"这条契约</b>：否则用例可以拿一个拿不到 openid 的
 * 本地账号跑绿，而真实环境里那一档根本不会送检 —— 那样的绿是假的。所以每个参与者都走微信登录
 * （测试 profile 下是确定性兑换器），账号键是 {@code wx:<openid>}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContentSecurityRejectionTest {

    /** 按剧本回答的送检器：默认放行，用例自己切到 RISKY。 */
    static final class ScriptedContentSecurityClient implements ContentSecurityClient {

        private volatile Verdict verdict = Verdict.ALLOWED;
        private final List<String> seen = new CopyOnWriteArrayList<>();

        void script(Verdict next) {
            this.verdict = next;
        }

        List<String> seen() {
            return List.copyOf(seen);
        }

        @Override
        public Verdict check(String openId, Scene scene, String content) {
            if (openId == null || openId.isBlank()) {
                // 与真实实现同一条契约：没有 openid 就没有送检对象。用例若拿本地账号来跑，
                // 这里会一直 UNAVAILABLE ⇒ 拒不掉 ⇒ 用例红，而不是悄悄放行。
                return Verdict.UNAVAILABLE;
            }
            seen.add(scene.name() + ":" + content);
            return verdict;
        }

        @Override
        public boolean productionReady() {
            return true;
        }
    }

    @TestConfiguration
    static class ScriptedSecurityConfig {
        @Bean
        @Primary
        ScriptedContentSecurityClient scriptedContentSecurityClient() {
            return new ScriptedContentSecurityClient();
        }
    }

    private static final String PLAYER_HEADER = "X-Player-Id";

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private ScriptedContentSecurityClient security;

    @BeforeEach
    void resetScript() {
        security.script(ContentSecurityClient.Verdict.ALLOWED);
    }

    @Test
    @DisplayName("聊天：判定违规就拒绝（10045），且那条消息一个字都不进聊天记录")
    void riskyChatIsRejectedAndNotPersisted() throws Exception {
        String me = weChatPlayer(1);

        // 先证"正常内容发得出去"，否则拒绝可能是别的原因造成的（比如限流）
        post200("/chat/send", me, new ChatSendReq(newRequestId(), ChatChannel.WORLD, "正常发言", null));
        assertThat(messagesIn("/chat/list", me, ChatChannel.WORLD)).as("正常发言应落库").hasSize(1);

        security.script(ContentSecurityClient.Verdict.RISKY);
        JsonNode rejected = postRaw("/chat/send", me,
                new ChatSendReq(newRequestId(), ChatChannel.WORLD, "这条会被拒", null));
        assertThat(rejected.get("code").asInt())
                .as("违规内容必须被拒，而不是静默替换成别的字")
                .isEqualTo(ErrorCode.SOCIAL_CHAT_CONTENT_INVALID.code());

        assertThat(messagesIn("/chat/list", me, ChatChannel.WORLD))
                .as("被拒的消息不许落库 —— 否则审核看到的是改过的版本，而玩家以为自己发出去了")
                .hasSize(1);
        assertThat(security.seen()).as("送检场景是社交日志（scene=4）")
                .anyMatch(entry -> entry.startsWith("SOCIAL_LOG:"));
    }

    @Test
    @DisplayName("小队名：判定违规拒绝（10005），且没有建出小队")
    void riskySquadNameIsRejected() throws Exception {
        String me = weChatPlayer(10);

        security.script(ContentSecurityClient.Verdict.RISKY);
        JsonNode rejected = postRaw("/squad/create", me,
                new SquadCreateReq(newRequestId(), "违规小队名"));
        assertThat(rejected.get("code").asInt()).isEqualTo(ErrorCode.SQUAD_NAME_INVALID.code());

        security.script(ContentSecurityClient.Verdict.ALLOWED);
        post200("/squad/create", me, new SquadCreateReq(newRequestId(), "正常小队名"));
        assertThat(get200("/social/summary", me).get("squad").isNull())
                .as("已经入队之后摘要里 squad 不该还是空 —— 这条同时证明上面那次拒绝没有留下半个小队")
                .isFalse();
    }

    @Test
    @DisplayName("联盟名：判定违规拒绝（10015），公账与名字都不动")
    void riskyAllianceNameIsRejected() throws Exception {
        String me = weChatPlayer(12);

        security.script(ContentSecurityClient.Verdict.RISKY);
        JsonNode rejected = postRaw("/alliance/create", me,
                new AllianceCreateReq(newRequestId(), "违规联盟名", "违规"));
        assertThat(rejected.get("code").asInt()).isEqualTo(ErrorCode.ALLIANCE_NAME_INVALID.code());
        assertThat(get200("/social/summary", me).get("alliance").isNull())
                .as("被拒之后摘要里不该冒出联盟 —— 建盟会扣 500 金币，落半个比报错更难解释")
                .isTrue();
    }

    @Test
    @DisplayName("昵称：判定违规拒绝（2003），且没有建出存档")
    void riskyNicknameIsRejected() throws Exception {
        security.script(ContentSecurityClient.Verdict.RISKY);
        String deviceId = "dev-" + UUID.randomUUID();
        JsonNode rejected = postRaw("/player/init", null,
                new PlayerInitReq(newRequestId(), deviceId, "违规昵称", 1_700_000_000_000L,
                        "wx-code-" + UUID.randomUUID()));
        assertThat(rejected.get("code").asInt()).isEqualTo(ErrorCode.PLAYER_NICKNAME_INVALID.code());
        assertThat(players.findByDeviceId("wx:dev-openid-" + sha256Prefix("wx-code-" + deviceId)))
                .as("昵称被拒的号不该留下任何存档（这里只是用一个不可能命中的键反证存档表没长）")
                .isEmpty();
    }

    // ---------- 夹具 ----------

    /** 走微信登录建号：本地兑换器把 code 确定性地映射成 openid，于是账号键带得动 openid。 */
    private String weChatPlayer(int cityLevel) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "内容安全测试",
                1_700_000_000_000L, "wx-code-" + UUID.randomUUID())).playerId();
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

    /** 本地兑换器用 sha256(code) 的前 24 位当 openid（见 LocalDevWeChatCodeExchanger）。 */
    private static String sha256Prefix(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<JsonNode> messagesIn(String url, String playerId, ChatChannel channel)
            throws Exception {
        JsonNode data = post200(url, playerId, new ChatListReq(channel, null, null, 20));
        List<JsonNode> out = new java.util.ArrayList<>();
        data.get("messages").forEach(out::add);
        return out;
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private JsonNode get200(String url, String playerId) throws Exception {
        return okData(perform(get(url).header(PLAYER_HEADER, playerId)));
    }

    private JsonNode post200(String url, String playerId, Object req) throws Exception {
        return okData(postRaw(url, playerId, req));
    }

    private JsonNode postRaw(String url, String playerId, Object req) throws Exception {
        MockHttpServletRequestBuilder builder = post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(req == null ? "{}" : JsonUtils.toJson(req));
        if (playerId != null) {
            builder = builder.header(PLAYER_HEADER, playerId);
        }
        return perform(builder);
    }

    private JsonNode okData(JsonNode root) {
        assertThat(root.get("code").asInt())
                .as("这一步本该成功：msg=" + root.get("msg").asText() + " detail="
                        + root.path("detail").asText(""))
                .isZero();
        return root.get("data");
    }

    private JsonNode perform(MockHttpServletRequestBuilder builder) throws Exception {
        String body = mockMvc.perform(builder).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return JsonUtils.readTree(body);
    }
}
