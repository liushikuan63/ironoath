package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.dto.generated.FollowReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：B22 §一 4「关注」的端到端验证 —— 单向、幂等、上限、以及列表里那三个字段。
 * 依赖：Spring Boot Test + MockMvc + 内存存储。
 *
 * <p><b>关注是这一批里唯一"没有验收条"的一块</b>（§一 4 明写"可选，最后做"），所以本类钉的是
 * 它自己的三条承诺：**单向**（对方不需要做任何事、也收不到通知）、**有上限**（列表每次打开都要读，
 * 无界增长会把端点变成全服扫描）、**在线状态来自 WS 网关**（而不是客户端猜）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FollowEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private SocialStore socialStore;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        socialStore.clear();
    }

    @Test
    @DisplayName("单向且幂等：我关注他两次只有一条，而他那边什么都不需要做、也看不到")
    void followIsOneWayAndIdempotent() throws Exception {
        String me = newPlayer("我");
        String other = newPlayer("他");

        JsonNode first = post200("/social/follow", me, new FollowReq(newRequestId(), other));
        post200("/social/follow", me, new FollowReq(newRequestId(), other));

        assertThat(first.get("friends")).hasSize(1);
        JsonNode row = first.get("friends").get(0);
        assertThat(row.get("playerId").asText()).isEqualTo(other);
        assertThat(row.get("name").asText()).isEqualTo("他");
        assertThat(row.get("online").asBoolean())
                .as("本用例没有真的 WS 连接 ⇒ 网关快照必然是离线；为 true 的那一半要靠真连接")
                .isFalse();
        assertThat(row.get("lastSeenAt").asLong()).isPositive();

        assertThat(get200("/social/follows", other).get("friends"))
                .as("单向：对方那边空着 —— 关注不是互加好友的半步申请")
                .isEmpty();

        // 取关（幂等）之后列表空
        post200("/social/unfollow", me, new FollowReq(newRequestId(), other));
        post200("/social/unfollow", me, new FollowReq(newRequestId(), other));
        assertThat(get200("/social/follows", me).get("friends")).isEmpty();
    }

    @Test
    @DisplayName("上限生效：到顶之后再关注会被拒，且那一条不会进列表")
    void followLimitIsEnforced() throws Exception {
        String me = newPlayer("我");
        long cap = 50L;
        for (int i = 0; i < cap; i++) {
            post200("/social/follow", me, new FollowReq(newRequestId(), newPlayer("目标" + i)));
        }
        assertThat(get200("/social/follows", me).get("friends")).hasSize((int) cap);

        String extra = newPlayer("第 51 个");
        JsonNode rejected = postRaw("/social/follow", me, new FollowReq(newRequestId(), extra));
        assertThat(rejected.get("code").asInt())
                .as("到顶要说清是容量问题（先取关几个），不是权限问题")
                .isEqualTo(ErrorCode.SOCIAL_FOLLOW_LIMIT.code());
        assertThat(rejected.path("detail").asText()).contains("取关");

        // 已经关注过的人再点一次不受上限影响（幂等优先于容量）
        String first = get200("/social/follows", me).get("friends").get(0).get("playerId").asText();
        post200("/social/follow", me, new FollowReq(newRequestId(), first));
        assertThat(get200("/social/follows", me).get("friends")).hasSize((int) cap);
    }

    @Test
    @DisplayName("不能关注自己，也不能漏参数（两条都回参数错误而不是静默成功）")
    void followRejectsSelfAndMissingTarget() throws Exception {
        String me = newPlayer("我");
        assertThat(postRaw("/social/follow", me, new FollowReq(newRequestId(), me)).get("code").asInt())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(postRaw("/social/follow", me, new FollowReq(newRequestId(), "  ")).get("code").asInt())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());
    }

    // ---------- 夹具 ----------

    private String newPlayer(String nickName) {
        return playerInitService.init(new PlayerInitReq("req-" + UUID.randomUUID(),
                "dev-" + UUID.randomUUID(), nickName, 1_700_000_000_000L, "")).playerId();
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
        MvcResult result = mockMvc.perform(post(url)
                        .header(PLAYER_HEADER, playerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtils.toJson(req)))
                .andExpect(status().isOk()).andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode okData(JsonNode root) {
        assertThat(root.get("code").asInt())
                .as("这一步本该成功：msg=" + root.path("msg").asText()
                        + " detail=" + root.path("detail").asText(""))
                .isZero();
        return root.get("data");
    }
}
