package com.ironoath.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.security.AuthSessionService;
import com.ironoath.web.security.LocalDevIdentityVerifier;
import com.ironoath.web.security.LocalDevWeChatCodeExchanger;
import com.ironoath.web.security.PlayerIdentityVerifier;
import com.ironoath.web.security.WeChatCodeExchanger;
import com.ironoath.web.security.WeChatLoginException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 职责：微信登录链路（B15 §三）的落地验证 —— code → openid → 账号键 → 会话票据。
 * 依赖：Spring Boot Test + MockMvc；test profile 下用 {@link LocalDevWeChatCodeExchanger}，
 * 不联网、不依赖 AppSecret 就能验证整条链路的形状与不变量。
 *
 * <p>本地HTTP验证同设备重新登录保留存档及票据不能被伪造/越权；
 * 不把本地设备身份当作跨设备微信身份。真机上的 {code2session} 由部署时的
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
    @DisplayName("本地同设备重新登录使用不同临时code，仍回到同一存档")
    void newWxCodeOnSameLocalDeviceReturnsSamePlayer() throws Exception {
        String code = "wx-code-" + UUID.randomUUID();
        String deviceId = "dev-" + UUID.randomUUID();

        JsonNode first = init(code, deviceId);
        var save = players.findByPlayerId(first.get("playerId").asText()).orElseThrow();
        save.setCityLevel(3);
        players.save(save);
        // 本地没有真实code2session，不能把一次性code作为账号键。
        JsonNode second = init("wx-code-" + UUID.randomUUID(), deviceId);

        assertThat(second.get("playerId").asText())
                .as("本地重开游戏必须回到同一份存档，不能把新的临时code当新账号")
                .isEqualTo(first.get("playerId").asText());
        assertThat(players.findByPlayerId(second.get("playerId").asText()).orElseThrow().cityLevel())
                .as("重新登录不能重置已有主城进度").isEqualTo(3);
    }

    @Test
    @DisplayName("本地即使code相同也不合并不同设备存档")
    void localDevicesStaySeparate() throws Exception {
        String code = "wx-code-" + UUID.randomUUID();
        assertThat(init(code, "dev-" + UUID.randomUUID()).get("playerId").asText())
                .isNotEqualTo(init(code, "dev-" + UUID.randomUUID()).get("playerId").asText());
    }

    @Test
    @DisplayName("正式兑换默认仍只信code2session，不受客户端设备号影响")
    void realExchangeIgnoresDeviceHint() {
        WeChatCodeExchanger exchanger = code -> new WeChatCodeExchanger.Identity("same-wechat-user", null, "key");
        assertThat(exchanger.exchange("fresh-code-1", "phone-1"))
                .isEqualTo(exchanger.exchange("fresh-code-2", "phone-2"));
    }

    @Test
    @DisplayName("本地稳定身份拒绝缺失code和持久设备号")
    void localStableIdentityRequiresCodeAndDevice() {
        LocalDevWeChatCodeExchanger exchanger = new LocalDevWeChatCodeExchanger();
        assertThatThrownBy(() -> exchanger.exchange(" ", "device"))
                .isInstanceOf(WeChatLoginException.class);
        assertThatThrownBy(() -> exchanger.exchange("code", " "))
                .isInstanceOf(WeChatLoginException.class);
        assertThat(exchanger.exchange("code-1", "device").openId())
                .isEqualTo(exchanger.exchange("code-2", "device").openId());
        assertThat(exchanger.productionReady()).isFalse();
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
