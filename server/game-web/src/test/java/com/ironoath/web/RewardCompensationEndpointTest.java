package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.core.reward.RewardType;
import com.ironoath.web.dto.generated.CompensationResolveReq;
import com.ironoath.web.ops.OpsTokenGuard;
import com.ironoath.web.reward.RewardCompensationStore;

/**
 * 职责：补偿台账两条运维端点的端到端断言（B04 验收 7 的出口那一半）。
 * 依赖：Spring 上下文（memory 存储模式）、MockMvc。
 *
 * <p><b>夹具一律走生产入口</b>：种子数据是 {@code RewardPorts.Compensation.record(...)} ——
 * 也就是发放器与商店在失败当场调的那一个方法，而不是直接往存储里塞一条现成的记录。
 * 直接塞会测到一个生产不再产生的形状：id 由谁生成、reason 为 null 时写成什么、
 * sourceRef 缺省时是不是空串，全都绕过去了（收口清单里记过不止一次的教训）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RewardCompensationEndpointTest {

    /** 与 {@code application-test.yml} 的 {@code ironoath.ops.token} 一致。 */
    private static final String OPS_TOKEN = "test-ops-token";
    private static final String LEDGER_URL = "/ops/reward/compensation";
    private static final String RESOLVE_URL = "/ops/reward/compensation/resolve";

    @Autowired private MockMvc mockMvc;
    @Autowired private RewardPorts.Compensation compensation;
    @Autowired private RewardCompensationStore ledger;

    /** 台账是进程内共享的内存版，不清理会把上一条用例的欠账算进这一条的总数。 */
    @BeforeEach
    void emptyLedger() {
        ledger.clear();
    }

    @Test
    @DisplayName("发奖失败当场就查得到：记的那几件、记的那个人、记的原因，一条都不许在端点上丢掉")
    void recordedFailureIsReadableImmediately() throws Exception {
        String id = record("P-read", "shop", List.of(
                new RewardItem(RewardType.ITEM, "potion_big", 3L),
                new RewardItem(RewardType.STAMINA, "STAMINA", 20L)), "背包已满");

        JsonNode data = okData(getRoot(LEDGER_URL + "?limit=20", OPS_TOKEN));
        assertThat(data.get("pendingCount").asLong()).isEqualTo(1L);
        assertThat(data.get("listed").asInt()).isEqualTo(1);
        JsonNode row = data.get("rows").get(0);
        assertThat(row.get("compensationId").asText()).isEqualTo(id);
        assertThat(row.get("playerId").asText()).isEqualTo("P-read");
        assertThat(row.get("source").asText()).as("风控归因靠这一列，丢了就只能猜是哪个系统发的")
                .isEqualTo("shop");
        assertThat(row.get("traceId").asText()).isNotBlank();
        assertThat(row.get("reason").asText()).isEqualTo("背包已满");
        assertThat(row.get("createdAt").asLong()).isPositive();
        assertThat(row.get("items")).hasSize(2);
        assertThat(row.get("items").get(0).get("type").asText()).isEqualTo("ITEM");
        assertThat(row.get("items").get(1).get("count").asLong())
                .as("数量全程 64 位整数，浮点会在尾数上漂移").isEqualTo(20L);
    }

    @Test
    @DisplayName("只读端点也要令牌：不带与带错都回 OPS_UNAUTHORIZED（对照组是带对的能读到）")
    void readoutRequiresOpsToken() throws Exception {
        record("P-token", "quest", List.of(new RewardItem(RewardType.ITEM, "potion", 1L)), "");

        assertThat(codeOf(getRoot(LEDGER_URL, null)))
                .as("空手来问台账，等于任何人都能看玩家欠账").isEqualTo(ErrorCode.OPS_UNAUTHORIZED.code());
        assertThat(codeOf(getRoot(LEDGER_URL, "wrong-token")))
                .as("令牌错了不能退化成回一份空台账 —— 那会被读成「没有欠账」")
                .isEqualTo(ErrorCode.OPS_UNAUTHORIZED.code());
        assertThat(okData(getRoot(LEDGER_URL, OPS_TOKEN)).get("pendingCount").asLong())
                .as("对照组：同一个请求带上令牌就读得到，说明上面两红不是端点坏了").isEqualTo(1L);
    }

    @Test
    @DisplayName("limit 夹住的是明细条数，不是总数：没列全这件事必须在响应里看得见")
    void limitCapsRowsButNotTheCount() throws Exception {
        for (int i = 0; i < 3; i++) {
            record("P-limit-" + i, "battle", List.of(new RewardItem(RewardType.ITEM, "potion", 1L)), "");
        }
        JsonNode data = okData(getRoot(LEDGER_URL + "?limit=1", OPS_TOKEN));
        assertThat(data.get("rows")).as("limit=1 只该带出一行").hasSize(1);
        assertThat(data.get("listed").asInt()).isEqualTo(1);
        assertThat(data.get("pendingCount").asLong())
                .as("总数不受 limit 影响：合并成一个数运维就会以为看到的就是全部").isEqualTo(3L);

        for (int i = 0; i < 60; i++) {
            record("P-cap-" + i, "battle", List.of(new RewardItem(RewardType.ITEM, "potion", 1L)), "");
        }
        JsonNode greedy = okData(getRoot(LEDGER_URL + "?limit=99999", OPS_TOKEN));
        assertThat(greedy.get("listed").asInt())
                .as("limit 必须由服务端夹住：这条路径挂在不需要玩家身份的 /ops 下，"
                        + "透传 limit 等于任何人（令牌泄露时）都能要求服务端把整张台账捞一遍")
                .isEqualTo(50);
        assertThat(greedy.get("pendingCount").asLong()).as("夹住的是明细，不是总数").isEqualTo(63L);
    }

    @Test
    @DisplayName("销账：第一次真翻转、第二次拿 false，账上的待处理数随之少一")
    void resolveFlipsExactlyOnce() throws Exception {
        String id = record("P-resolve", "mail", List.of(new RewardItem(RewardType.ITEM, "potion", 1L)), "");

        JsonNode first = postJson(RESOLVE_URL, new CompensationResolveReq(id, "客服甲", "mail_777"));
        assertThat(first.get("resolved").asBoolean()).isTrue();
        assertThat(first.get("pendingCount").asLong()).isZero();

        JsonNode second = postJson(RESOLVE_URL, new CompensationResolveReq(id, "客服乙", "mail_888"));
        assertThat(second.get("resolved").asBoolean())
                .as("第二个人拿到 false，才知道自己那封补发邮件是重复的").isFalse();

        assertThat(okData(getRoot(LEDGER_URL + "?limit=20", OPS_TOKEN)).get("rows")).isEmpty();
        assertThat(ledger.findById(id).orElseThrow().resolvedBy())
                .as("留下的是先处理那位，后一次的说明不许覆盖上去").isEqualTo("客服甲");
    }

    @Test
    @DisplayName("销账的三种脏输入都响亮失败，且不动台账：抄错的号不能伪装成「已被别人处理」")
    void dirtyResolveIsRefusedAndLeavesTheLedgerAlone() throws Exception {
        String id = record("P-dirty", "activity", List.of(new RewardItem(RewardType.ITEM, "potion", 1L)), "");

        assertThat(codeOf(postRoot(RESOLVE_URL, new CompensationResolveReq("comp_不存在", "客服甲", "mail_1"))))
                .as("查无此号要说「不存在」，与「已被处理」是两种下一步动作")
                .isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(codeOf(postRoot(RESOLVE_URL, new CompensationResolveReq(id, " ", "mail_1"))))
                .as("谁销的账不许留空").isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(codeOf(postRoot(RESOLVE_URL, new CompensationResolveReq(id, "客服甲", "  "))))
                .as("没有凭证的销账等于把欠账悄悄抹平").isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(codeOf(postRoot(RESOLVE_URL, new CompensationResolveReq(" ", "客服甲", "mail_1"))))
                .isEqualTo(ErrorCode.PARAM_INVALID.code());

        assertThat(ledger.findById(id).orElseThrow().pending())
                .as("四次被拒的销账一次也没把这条欠账动过").isTrue();
        assertThat(okData(getRoot(LEDGER_URL + "?limit=20", OPS_TOKEN)).get("pendingCount").asLong())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("销账不带令牌时先被令牌挡下，不会把「谁在处理」写成 null")
    void resolveRequiresTokenBeforeTouchingTheLedger() throws Exception {
        String id = record("P-auth", "quest", List.of(new RewardItem(RewardType.ITEM, "potion", 1L)), "");

        MvcResult result = mockMvc.perform(post(RESOLVE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtils.toJson(new CompensationResolveReq(id, "客服甲", "mail_1"))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(codeOf(JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))))
                .isEqualTo(ErrorCode.OPS_UNAUTHORIZED.code());
        assertThat(ledger.findById(id).orElseThrow().pending())
                .as("被闸门挡下的请求必须在改台账之前").isTrue();
    }

    // ---------- 夹具 ----------

    /** 走生产入口记一笔：与发放器/商店在失败当场调的是同一个方法。 */
    private String record(String playerId, String source, List<RewardItem> failed, String reason) {
        return compensation.record(playerId, failed,
                RewardContext.toMail(source, "endpoint_fixture", "trace_" + playerId),
                reason.isEmpty() ? null : new IllegalStateException(reason));
    }

    private JsonNode okData(JsonNode root) {
        assertThat(codeOf(root)).as("这条请求本该成功").isZero();
        return root.get("data");
    }

    private JsonNode getRoot(String url, String opsToken) throws Exception {
        MockHttpServletRequestBuilder builder = get(url);
        if (opsToken != null) {
            builder = builder.header(OpsTokenGuard.HEADER, opsToken);
        }
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode postJson(String url, Object req) throws Exception {
        return okData(postRoot(url, req));
    }

    private JsonNode postRoot(String url, Object req) throws Exception {
        MvcResult result = mockMvc.perform(post(url)
                        .header(OpsTokenGuard.HEADER, OPS_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtils.toJson(req)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static int codeOf(JsonNode root) {
        assertThat(root.has("code"))
                .as("所有 /ops 响应都该是 Result 信封；没有 code 就是空响应体或被别的过滤器截走：%s",
                        root.toString())
                .isTrue();
        return root.get("code").asInt();
    }
}
