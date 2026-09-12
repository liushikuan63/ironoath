package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import com.ironoath.core.gacha.GachaLogStore;
import com.ironoath.core.gacha.Tier;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryGachaLogStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：抽卡记录查询的合规验证（B15 §三：概率公示 + 抽取记录查询（最近 50 次）+ 日志保留 90 天）。
 * 依赖：Spring Boot Test + MockMvc；test profile（内存存储）。
 *
 * <p><b>这条端点是监管要求而不是产品功能</b>：公示了概率却查不到自己的记录，
 * 等于让玩家只能相信而无法验证 —— 而「无法验证的公示」在监管口径里等同于没有公示。
 * 所以本类的断言重点不是「有没有返回数据」，而是三件容易被省略的事：
 * <ol>
 *   <li><b>倒序</b>：存储的返回顺序不该被当成契约。一旦它改成倒序返回而这里不排序，
 *       就会把最旧的 50 条当成「最近 50 条」下发 —— 玩家看到的全是三个月前的记录</li>
 *   <li><b>窗口截断</b>：超过 50 条时只给最近 50 条，且被截掉的是最旧的</li>
 *   <li><b>isPity 透传</b>：公示里写了保底，玩家就要能在自己的记录里看到保底确实生效过</li>
 * </ol>
 *
 * <p><b>夹具直接写日志而不走抽卡</b>：这条端点的职责是查询与整形，不是抽取。
 * 走抽取的话，用例的成败会同时取决于卡池配置、货币余额与随机结果，
 * 而我要验的只是「有 60 条记录时返回哪 50 条」。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GachaComplianceEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final String HISTORY_URL = "/gacha/history";
    private static final String POOL = "gacha_pool_newbie";

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private ConfigRegistry configs;
    @Autowired private PlayerRepository players;
    @Autowired private GachaLogStore gachaLogs;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryGachaLogStore) gachaLogs).clear();
    }

    @Test
    @DisplayName("返回本人的抽取记录：倒序、带 isPity、并下发保留天数让合规口径可被玩家核对")
    void historyReturnsOwnRecordsNewestFirst() throws Exception {
        String playerId = newPlayer();
        long now = System.currentTimeMillis();
        writeLogs(playerId, 5, now);

        JsonNode data = get200(HISTORY_URL, playerId);

        assertThat(data.get("retentionDays").asInt())
                .as("保留天数下发是为了合规可核：监管问「你们保留多久」时，答案应当能在产品里被看到")
                .isEqualTo((int) configs.longParam("GACHA_LOG_RETENTION_DAYS"));
        assertThat(data.get("serverNow").asLong()).isPositive();

        JsonNode records = data.get("records");
        assertThat(records.size()).isEqualTo(5);
        assertThat(records.get(0).get("time").asLong())
                .as("最新的在前").isEqualTo(now);
        assertThat(records.get(4).get("time").asLong())
                .as("最旧的在后").isEqualTo(now - 4L);
        assertThat(records.get(0).get("poolId").asText()).isEqualTo(POOL);
        assertThat(records.get(0).get("heroId").asText()).isNotBlank();
        assertThat(records.get(0).get("isPity").asBoolean())
                .as("夹具把最新一条标成保底触发，它必须原样透传").isTrue();
        assertThat(records.get(1).get("isPity").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("超过窗口时只返回最近 N 条，被截掉的是最旧的（N 来自配置，不是写死的 50）")
    void historyIsTruncatedToTheConfiguredWindow() throws Exception {
        String playerId = newPlayer();
        int limit = (int) configs.longParam("GACHA_HISTORY_LIMIT");
        long now = System.currentTimeMillis();
        writeLogs(playerId, limit + 10, now);

        JsonNode records = get200(HISTORY_URL, playerId).get("records");

        assertThat(records.size()).as("上限来自 global.GACHA_HISTORY_LIMIT").isEqualTo(limit);
        assertThat(records.get(0).get("time").asLong()).isEqualTo(now);
        assertThat(records.get(limit - 1).get("time").asLong())
                .as("留下的应当是最近的 %d 条", limit).isEqualTo(now - (limit - 1L));
    }

    @Test
    @DisplayName("只能查到自己的记录：别人的抽取记录能反推对方的氪金量与保底进度")
    void historyDoesNotLeakOtherPlayers() throws Exception {
        String mine = newPlayer();
        String other = newPlayer();
        long now = System.currentTimeMillis();
        writeLogs(mine, 3, now);
        writeLogs(other, 7, now);

        JsonNode records = get200(HISTORY_URL, mine).get("records");
        assertThat(records.size()).as("只看到自己的 3 条").isEqualTo(3);
    }

    @Test
    @DisplayName("没有抽取记录时返回空列表而不是报错：新玩家也会打开这个面板")
    void emptyHistoryIsNotAnError() throws Exception {
        JsonNode data = get200(HISTORY_URL, newPlayer());
        assertThat(data.get("records").size()).isZero();
        assertThat(data.get("retentionDays").asInt()).isPositive();
    }

    // ---------- 夹具 ----------

    /** 写 count 条抽取记录，drawnAt 从 now 逐条往前 1 毫秒；最新一条标成保底触发。 */
    private void writeLogs(String playerId, int count, long now) {
        List<GachaLogStore.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new GachaLogStore.Entry(playerId, POOL, now - i, "req-" + UUID.randomUUID(),
                    1234L + i, i, "hero_r_0" + (i % 9 + 1), Tier.R, i == 0, i % 3 == 0, 0L));
        }
        gachaLogs.appendAll(entries);
    }

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "合规测试", 1_700_000_000_000L))
                .playerId();
    }

    private JsonNode get200(String url, String playerId) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header(PLAYER_HEADER, playerId))
                .andExpect(status().isOk()).andReturn();
        // MockMvc 默认按 ISO-8859-1 解码响应体，中文提示会变乱码
        JsonNode root = JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(root.get("code").asInt())
                .as("业务码必须为 0，实际响应=%s", root).isZero();
        return root.get("data");
    }
}
