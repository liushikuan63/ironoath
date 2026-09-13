package com.ironoath.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.security.AuthSessionService;
import com.ironoath.web.security.LocalDevIdentityVerifier;
import com.ironoath.web.security.LocalDevWeChatCodeExchanger;
import com.ironoath.web.security.PlayerIdentityVerifier;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 职责：微信登录链路（B15 §三）的落地验证 —— code → openid → 账号键 → 会话票据。
 * 依赖：Spring Boot Test + MockMvc；test profile 下用 {@link LocalDevWeChatCodeExchanger}，
 * 不联网、不依赖 AppSecret 就能验证整条链路的形状与不变量。
 *
 * <p><b>这里验的是"同一微信 = 同一存档"这条产品承诺</b>（换手机能找回号），
 * 以及票据本身不能被伪造/越权。真机上的 {code2session} 由部署时的
 * {@code WeChatSessionCodeExchanger} 负责，它没被本测试覆盖 —— 那需要真实 AppSecret，
 * 属于上线前必须单独跑一次的联调项。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WeChatLoginTest {

    private static final String INIT_URL = "/player/init";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlayerInitService playerInitService;

    @Autowired
    private com.ironoath.core.player.PlayerRepository players;

    /**
     * 测试自建一份票据服务，而不是注入应用上下文里的那个：
     * 密钥与 TTL 都是本类的输入，断言才不会随部署环境漂移。
     * 用的密钥与 {@code SecurityBeansConfig.DEV_SESSION_SECRET} 相同 —— 因为
     * {@code init()} 走的是真实 HTTP 链路，票据由上下文里的那个服务签发。
     */
    private final AuthSessionService sessions =
            new AuthSessionService(com.ironoath.web.config.SecurityBeansConfig.DEV_SESSION_SECRET);

    @BeforeEach
    void resetStore() {
        ((InMemoryPlayerStore) players).clear();
    }

    @Test
    @DisplayName("同一个 wx code 登录两次：拿到同一份存档，而不是建两个号")
    void sameWxCodeReturnsSamePlayer() throws Exception {
        String code = "wx-code-" + UUID.randomUUID();
        String deviceId = "dev-" + UUID.randomUUID();

        JsonNode first = init(code, deviceId);
        // 第二次故意换 requestId 与 deviceId：模拟"换了台手机但同一个微信"。
        // 账号键必须是 openid 而不是 deviceId，否则这条会各建一个号
        JsonNode second = init(code, "dev-" + UUID.randomUUID());

        assertThat(second.get("playerId").asText())
                .as("同一个微信账号必须回到同一份存档（换手机找回号的前提）")
                .isEqualTo(first.get("playerId").asText());
    }

    @Test
    @DisplayName("登录响应带会话票据；票据与 playerId 绑定，换一个 id 就不通过")
    void loginIssuesSessionTokenBoundToPlayer() throws Exception {
        String code = "wx-code-" + UUID.randomUUID();
        JsonNode data = init(code, "dev-" + UUID.randomUUID());
        String playerId = data.get("playerId").asText();
        String token = data.get("authToken").asText();

        assertThat(token).as("登录必须下发会话票据，否则严格身份模式下客户端寸步难行")
                .isNotBlank();
        assertThat(sessions.verify(new PlayerIdentityVerifier.Claim(playerId, token, "/city/list"),
                1_700_000_000_500L).allowed())
                .as("票据与签发它的 playerId 必须互相匹配")
                .isTrue();
        assertThat(sessions.verify(new PlayerIdentityVerifier.Claim("someone-else", token, "/city/list"),
                1_700_000_000_500L).allowed())
                .as("拿自己的票据冒充别人的 playerId 必须被拒（这是 B15 要堵的洞）")
                .isFalse();
    }

    @Test
    @DisplayName("票据过期与伪造签名都被拒，且失败原因是可读文案")
    void tokenExpiryAndForgeryAreRejected() {
        String issued = sessions.issue("player-1", 1_000L);
        long afterExpiry = 1_000L + AuthSessionService.DEFAULT_TTL_MS + 1;

        var expired = sessions.verify(
                new PlayerIdentityVerifier.Claim("player-1", issued, "/city/list"), afterExpiry);
        assertThat(expired.allowed()).isFalse();
        assertThat(expired.reason()).contains("过期");

        String forged = issued.substring(0, issued.length() - 2) + "zz";
        var rejected = sessions.verify(
                new PlayerIdentityVerifier.Claim("player-1", forged, "/city/list"), 2_000L);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.reason()).contains("无效");

        var missing = sessions.verify(
                new PlayerIdentityVerifier.Claim("player-1", null, "/city/list"), 2_000L);
        assertThat(missing.allowed()).isFalse();
        assertThat(missing.reason()).contains("重新登录");
    }

    @Test
    @DisplayName("不同 wx code 是不同账号；不传 code 时退回 deviceId 建档（本地与旧客户端不变）")
    void differentCodesAreDifferentAccountsAndDeviceIdStillWorks() throws Exception {
        String first = init("wx-code-" + UUID.randomUUID(), "dev-" + UUID.randomUUID())
                .get("playerId").asText();
        String second = init("wx-code-" + UUID.randomUUID(), "dev-" + UUID.randomUUID())
                .get("playerId").asText();
        assertThat(second).isNotEqualTo(first);

        String deviceId = "dev-" + UUID.randomUUID();
        var firstByDevice = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), deviceId, "浏览器玩家", 1_700_000_000_000L, ""));
        var againByDevice = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), deviceId, "浏览器玩家", 1_700_000_000_500L, ""));
        assertThat(againByDevice.playerId()).isEqualTo(firstByDevice.playerId());
    }

    @Test
    @DisplayName("本地兑换器是确定性的：同一个 code 永远映射同一个 openid")
    void localExchangerIsDeterministic() {
        LocalDevWeChatCodeExchanger exchanger = new LocalDevWeChatCodeExchanger();
        String code = "wx-code-" + UUID.randomUUID();
        assertThat(exchanger.exchange(code).openId())
                .isEqualTo(exchanger.exchange(code).openId());
        assertThat(exchanger.productionReady())
                .as("本地兑换器绝不能被当成可上生产的实现")
                .isFalse();
        assertThat(new LocalDevIdentityVerifier().productionReady()).isFalse();
    }

    private JsonNode init(String wxCode, String deviceId) throws Exception {
        String body = """
                {"requestId":"req-%s","deviceId":"%s","nickName":"微信玩家","clientTime":1700000000000,"wxCode":"%s"}
                """.formatted(UUID.randomUUID(), deviceId, wxCode);
        String response = mockMvc.perform(post(INIT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return JsonUtils.readTree(response).get("data");
    }
}
