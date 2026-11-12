package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;

/**
 * 职责：把「单个接口响应 ≤ {@code PERF_PAYLOAD_MAX_BYTES}」从**探针事后量**变成**每次 CI 都会报警的不变式**。
 *
 * <p><b>为什么现在要这条</b>：2026-11-13 用真产物 + 真后端跑 {@code tools/verify-perf-runtime.mjs}
 * （120s 挂机，日志 {@code D:\Java\maven\logs\perf-20261113-013246.log}）量到
 * <b>{@code /stage/list} = 17971B</b>，而预算 {@code PERF_PAYLOAD_MAX_BYTES} = 20480B
 * ⇒ <b>只剩 2509B（12%）</b>。这 2.5KB 的余量没有任何东西守着：
 * 关表里多加两章、每章多挂一条奖励描述，探针不会在 CI 里跑（要产物 + 活后端 + playwright），
 * 于是下一次发现越线的人是在真机上。
 *
 * <p><b>断言的是哪一份数</b>：读的是表里那一条预算，不是写死的 20480 ——
 * 写死就等于给同一个事实造了第二个家（铁律 1），改表时这里不会跟着动，反而会把合法调参判红。
 *
 * <p><b>为什么要挑这六条</b>：这不是「全部 147 条都覆盖了」。探针那轮量到的 21 条里体积最大的六条是
 * {@code /stage/list} 17971B、{@code /army/list} 6746B、{@code /quest/list} 6217B、
 * {@code /city/list} 2872B、{@code /resource/detail} 2808B、{@code /guide/script} 2066B；
 * 剩下 128 条属写接口或需要前置状态的读接口，仍只在探针里按需量。
 * <b>不要把这个类的存在读成「payload 覆盖面已经补齐」</b> —— 它守的是最接近天花板的那几条。
 *
 * <p><b>两条反空转</b>：① 每条先断言 {@code code == 0}，因为错误信封本身也很小，
 * 拿它量体积等于永远绿；② 六条里最大的那条必须大于 1KB，否则「建了一个空号、六条全返回空壳」
 * 也能让上面那条体积断言通过。
 *
 * <p><b>负控</b>：{@link #theBudgetAssertionCanActuallyFail()} 把同一段断言按「超一个字节」跑一遍，
 * 必须抛 {@link AssertionError} —— 没有这一条，上面几条在写错比较方向（{@code isGreaterThan}）时仍会全绿。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PerfPayloadBudgetHttpTest {

    private static final String PLAYER_HEADER = "X-Player-Id";

    /**
     * 探针那一轮量到的六条最重读接口。
     *
     * <p>顺序按实测体积从大到小，方便失败时一眼看出是哪条顶到天花板；体积本身在用例里重新量，
     * 这里不抄探针读数（抄了就变成断言「和探针说的一致」，而不是断言「没超预算」）。
     */
    private static final List<String> HEAVIEST_READS = List.of(
            "/stage/list",
            "/army/list",
            "/quest/list",
            "/city/list",
            "/resource/detail",
            "/guide/script");

    @Autowired private MockMvc mockMvc;
    @Autowired private ConfigRegistry configs;
    @Autowired private PlayerInitService playerInitService;

    @Test
    @DisplayName("六条最重的读接口：真实响应体都 ≤ 表里的 PERF_PAYLOAD_MAX_BYTES，且量到的是真数据")
    void heaviestReadsStayWithinTheGlobalPayloadBudget() throws Exception {
        String playerId = newPlayer();
        long budget = configs.longParam("PERF_PAYLOAD_MAX_BYTES");

        int heaviestBytes = 0;
        String heaviestPath = null;
        for (String path : HEAVIEST_READS) {
            byte[] body = getBody(path, playerId);
            JsonNode root = JsonUtils.readTree(new String(body, StandardCharsets.UTF_8));
            assertThat(root.path("code").asInt())
                    .as("%s 必须成功：错误信封也能很小，拿它量体积等于这条用例永远绿（实际响应=%s）", path, root)
                    .isZero();
            assertWithinBudget(path, body.length, budget);
            if (body.length > heaviestBytes) {
                heaviestBytes = body.length;
                heaviestPath = path;
            }
        }

        assertThat(heaviestBytes)
                .as("六条里最大的一条只有 %d 字节（%s）：夹具没建出真实数据，这几条体积断言在空转",
                        heaviestBytes, heaviestPath)
                .isGreaterThan(1024);
    }

    @Test
    @DisplayName("/stage/list 带的是整份章节表而不是空壳：它就是探针里那条 17971B/20480B 的接口")
    void stageListCarriesTheWholeChapterList() throws Exception {
        String playerId = newPlayer();
        JsonNode data = JsonUtils.readTree(new String(getBody("/stage/list", playerId), StandardCharsets.UTF_8))
                .path("data");

        // 字段名以生成的 StageListResp 为准：`stages` 是**平铺的全量关卡**（每关自带 chapterId），不是按章分组的嵌套结构。
        // 我第一版按 `chapters` 写，红在「章节列表为空」—— 那正是这条反空转用例存在的意义：空响应也能过体积断言。
        assertThat(data.path("stages"))
                .as("关卡列表必须给全量关卡：空数组同样能通过体积断言，那说明夹具看的不是玩家真实看到的那份")
                .isNotEmpty();
        assertThat(data.path("stages").get(0).path("stageId").asText())
                .as("每关必须带自己的 id，客户端据此下发挑战请求")
                .isNotEmpty();
    }

    @Test
    @DisplayName("负控：把预算压到「实测体积 − 1」，同一段断言必须判红——否则它守不住任何东西")
    void theBudgetAssertionCanActuallyFail() {
        assertThatThrownBy(() -> assertWithinBudget("/fixture", 5001, 5000L))
                .as("超预算一个字节必须判红")
                .isInstanceOf(AssertionError.class);
        // 恰好在边界上必须放行：这里刻意用 assertThatCode 而不是 assertThatThrownBy(...).doesNotThrowAnyException()
        // —— 后者的语义是「必须先抛一个异常」，用它验「不抛」会把自己判红（第一版就红在这一行）。
        assertThatCode(() -> assertWithinBudget("/fixture", 5000, 5000L))
                .as("恰好等于预算必须放行：把边界判红会让每次调参都撞在假红上")
                .doesNotThrowAnyException();
    }

    // ---------- 辅助 ----------

    /**
     * 体积判定本身抽成一个方法，是为了让负控跑**同一段代码**。
     *
     * <p>负控另写一份「超预算就抛」的同义实现，就只能证明那份副本会抛 —— 而真正在 CI 里跑的是这一份。
     */
    private static void assertWithinBudget(String path, int bytes, long budget) {
        assertThat((long) bytes)
                .as("%s 的响应体 %dB 超了 PERF_PAYLOAD_MAX_BYTES=%dB（余量 %dB）。"
                        + "这条接口已经贴着天花板，加内容前先想想能不能少下发一点，"
                        + "或者按 B16 的口径把重的内容拆到第二个接口", path, bytes, budget, budget - bytes)
                .isLessThanOrEqualTo(budget);
    }

    private byte[] getBody(String path, String playerId) throws Exception {
        MvcResult result = mockMvc.perform(get(path).header(PLAYER_HEADER, playerId)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("%s 的 HTTP 状态：本类量的是成功响应，5xx 的体积极小，不是同一条东西", path)
                .isEqualTo(200);
        return result.getResponse().getContentAsByteArray();
    }

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "体积预算测试", 1_700_000_000_000L, ""))
                .playerId();
    }
}
