package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

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
import com.ironoath.web.dto.generated.SeasonSettleReq;
import com.ironoath.web.ops.OpsTokenGuard;

/**
 * 职责：{@code POST /season/settle} 的运维令牌闸门（B14 §5 与收口清单 #8b 的遗留项）。
 * 依赖：Spring Boot Test + MockMvc；test profile（{@code ironoath.ops.token} 已配假值）。
 *
 * <p><b>为什么这个端点单独要一道身份闸门</b>：它是「外部调度系统按时钟打一次」的全服写入口，
 * 一次调用会改动每一个人的赛季账目。以前只靠两道业务闸门兜着（没到结算期就抛、期内重复调用幂等），
 * 那是业务规则在替身份规则补课 —— 结算期一放宽、或幂等键的算法一换，
 * 这个端点就悄悄变成谁都能写的口子，而且不会有任何地方报错。
 *
 * <p><b>本类不断言「带对令牌就能结算成功」</b>：test profile 刻意不配 {@code SEASON_START_AT}
 * （锚点是全服状态，配进 profile 会让每个测试的时间假设互相污染，见 SeasonSettlementTest 的类注释），
 * 所以带对令牌之后必然撞在阶段门上。这里要钉的是<b>鉴权先把住第一道</b>：
 * 令牌不对时错误码必须是 {@code OPS_UNAUTHORIZED}，而不是让人从「阶段不允许」里误读成鉴权过了。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SeasonSettleAuthTest {

    /**
     * 与 {@code application-test.yml} 里 {@code ironoath.ops.token} 的假值一致。
     * 两处必须同步 —— 改 yml 忘了改这里，本类会以「对令牌的请求也被拒」失败，
     * 而那正好是会让人误判成「闸门坏了」的形状，所以把它写在注释里。
     */
    private static final String OPS_TOKEN = "test-ops-token";

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("不带运维令牌调用结算：OPS_UNAUTHORIZED，而不是撞在阶段门上")
    void missingTokenIsRejected() throws Exception {
        assertThat(code(settle(null))).isEqualTo(ErrorCode.OPS_UNAUTHORIZED.code());
        assertThat(code(settle(""))).as("带了个头但值是空的，等同于没带")
                .isEqualTo(ErrorCode.OPS_UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("错误令牌被拒：「头带了」不等于「令牌对」")
    void wrongTokenIsRejected() throws Exception {
        assertThat(code(settle("not-the-configured-token")))
                .isEqualTo(ErrorCode.OPS_UNAUTHORIZED.code());
        assertThat(code(settle(OPS_TOKEN.toUpperCase())))
                .as("比较是逐字节的，不是忽略大小写的：把令牌做成大小写不敏感等于把熵砍一半")
                .isEqualTo(ErrorCode.OPS_UNAUTHORIZED.code());
    }

    /**
     * 判别性 —— 前三条如果只写成「怎么调都被拒」，那么一个恒抛异常的假闸门也能让它们全绿。
     * 这条钉的是闸门真的在比较这个头。
     *
     * <p>注意本条<b>不断言结算成功</b>：test profile 刻意不配 SEASON_START_AT（理由见类注释），
     * 所以过了鉴权必然撞业务闸门。这里要的是「错误码从鉴权错误变成别的错误」。
     */
    @Test
    @DisplayName("带对令牌能过鉴权：错误码不再是 OPS_UNAUTHORIZED")
    void correctTokenPassesAuthentication() throws Exception {
        assertThat(code(settle(OPS_TOKEN)))
                .as("鉴权已过，剩下的失败只能来自业务闸门")
                .isNotEqualTo(ErrorCode.OPS_UNAUTHORIZED.code());
    }

    // ---------- 夹具 ----------

    /**
     * @param token 要带的运维令牌；{@code null} 表示<b>根本不带这个头</b>（与「带一个空值头」是两种请求）
     */
    private JsonNode settle(String token) throws Exception {
        var builder = post("/season/settle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(new SeasonSettleReq("req-" + UUID.randomUUID(), null)));
        if (token != null) {
            builder = builder.header(OpsTokenGuard.HEADER, token);
        }
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static int code(JsonNode root) {
        return root.get("code").asInt();
    }
}
