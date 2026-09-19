package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.activity.ActivityProgress;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.quest.GoalType;
import com.ironoath.web.activity.ActivityProgressStore;
import com.ironoath.web.dto.generated.ActivityClaimReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.quest.QuestEvents;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：活动端点的端到端验收（B17 验收 1/2/3/4/5/6/8 的 HTTP 面）。
 * 依赖：Spring 测试上下文（内存存储）。
 *
 * <p><b>七类条件各有一条"真事件 → 进度"的用例</b>：登录那条走的是<b>真生产者</b>
 * （POST /player/init，服务端在登录成功后自己发 LOGIN_DAY），其余六类通过
 * {@link QuestEvents} 发 —— 那正是各业务服务（城建、行军、社交、捐献、战斗）在写路径上调的同一个入口，
 * 不是另开的一条测试专用通道。事件在总线上的派发与实际生产完全一致。
 *
 * <p><b>"窗口过期"用夹具造</b>：跨轮要等真实时间，所以直接把存储里的 windowStart 改成上一轮
 * —— 那正是"昨天没领、今天才打开"在库里的样子（验收 4 的判据面），而不是绕过业务路径。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class ActivityEndpointTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private ActivityProgressStore store;
    @Autowired private QuestEvents events;
    @Autowired private ConfigRegistry configs;
    @Autowired private com.ironoath.web.battlepass.BattlePassService battlePass;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        store.clear();
    }

    // ---------- 验收 1：8 行定义全部来自表 ----------

    @Test
    @DisplayName("验收 1：列表的 8 行、名称、目标都来自 activity.json（顺序 = 表序，服务端不另排）")
    void listComesFromTheTable() throws Exception {
        String playerId = newPlayer();

        JsonNode activities = list(playerId).get("activities");
        assertThat(activities).hasSize(8);
        List<String> fromApi = new java.util.ArrayList<>();
        for (JsonNode row : activities) {
            fromApi.add(row.get("id").asText());
            assertThat(row.get("goal").asLong())
                    .as("目标必须等于表里的 conditionValue：" + row.get("id").asText())
                    .isEqualTo(goalOf(row.get("id").asText()));
            assertThat(row.get("name").asText()).as("名字只在表里存一份").isNotBlank();
            assertThat(row.get("state").asText()).as("新号：没达标才可能是 RUNNING").isEqualTo("RUNNING");
        }
        assertThat(fromApi).as("顺序必须与 activity.json 的行序一致（服务端不另行排序）")
                .isEqualTo(List.of("activity_login_7d", "activity_login_30d", "activity_monster_hunt",
                        "activity_rally_week", "activity_donate_week", "activity_pvp_win",
                        "activity_build_sprint", "activity_squad_help"));
        assertThat(list(playerId).get("claimableCount").asInt())
                .as("一件都没做：可领数必须是 0（红点别乱亮）").isZero();
    }

    // ---------- 验收 2：七类条件各一条真事件 ----------

    @Test
    @DisplayName("验收 2 + 5：登录走真生产者（POST /player/init）→ 七日登临进度 1，同日重复登录仍是 1")
    void loginAdvancesTheStreakThroughItsRealProducer() throws Exception {
        String playerId = newPlayer();
        assertThat(valueOf(playerId, "activity_login_7d"))
                .as("建档本身就是第一天：这里必须是 1，否则「连续七天」要等第二天才开始").isEqualTo(1L);

        // 同一自然日再登录一次（真端点）：仍然只算一天
        playerInitService.init(new PlayerInitReq("req-" + UUID.randomUUID(), deviceOf(playerId),
                "活动测试", 1_700_000_000_000L, ""));
        assertThat(valueOf(playerId, "activity_login_7d"))
                .as("同一自然日重复登录只算一天（幂等由活动域负责，不在登录路径上判）").isEqualTo(1L);
    }

    @Test
    @DisplayName("验收 2：另外六类各自订阅自己那一类事件，互不串号")
    void eachConditionTypeAdvancesItsOwnRow() throws Exception {
        String playerId = newPlayer();
        long now = System.currentTimeMillis();
        events.progress(playerId, GoalType.KILL_MONSTER, "m1", 50L, now);
        events.progress(playerId, GoalType.JOIN_RALLY, null, 5L, now);
        events.progress(playerId, GoalType.ALLIANCE_DONATE, null, 2000L, now);
        events.progress(playerId, GoalType.PVP_WIN, null, 10L, now);
        events.progress(playerId, GoalType.UPGRADE_BUILDING, null, 10L, now);
        events.progress(playerId, GoalType.HELP_SQUAD, null, 30L, now);

        assertThat(valueOf(playerId, "activity_monster_hunt")).isEqualTo(50L);
        assertThat(valueOf(playerId, "activity_rally_week")).isEqualTo(5L);
        assertThat(valueOf(playerId, "activity_donate_week")).isEqualTo(2000L);
        assertThat(valueOf(playerId, "activity_pvp_win")).isEqualTo(10L);
        assertThat(valueOf(playerId, "activity_build_sprint")).isEqualTo(10L);
        assertThat(valueOf(playerId, "activity_squad_help")).isEqualTo(30L);
        assertThat(valueOf(playerId, "activity_login_7d")).as("没发登录事件，那一行不许动").isEqualTo(1L);
    }

    // ---------- 验收 3：领取幂等 ----------

    @Test
    @DisplayName("验收 3：达标后领到金币与道具；同 requestId 重投被拒（1002），换 requestId 再领被拒（12006）")
    void claimIsIdempotent() throws Exception {
        String playerId = newPlayer();
        long now = System.currentTimeMillis();
        events.progress(playerId, GoalType.KILL_MONSTER, "m1", 50L, now);
        long goldBefore = goldOf(playerId);
        long pointsBefore = battlePass.status(playerId).points();

        String requestId = "req-" + UUID.randomUUID();
        JsonNode first = claim(playerId, "activity_monster_hunt", requestId);
        assertThat(codeOf(first)).isZero();
        assertThat(first.get("data").get("claimed").asBoolean()).isTrue();
        assertThat(first.get("data").get("state").asText())
                .as("领完同轮就该是 RUNNING（红点同轮熄灭靠它）").isEqualTo("RUNNING");
        long goldAfter = goldOf(playerId);
        assertThat(goldAfter).as("500 金币必须真的到账").isEqualTo(goldBefore + 500L);
        assertThat(first.get("data").get("rewards")).isNotEmpty();
        long pointsAfter = battlePass.status(playerId).points();
        assertThat(pointsAfter - pointsBefore)
                .as("B24 S-d-c：领一次活动就给战令加分，加多少由 activity 表那一列给")
                .isEqualTo(configs.get(com.ironoath.config.cfg.ActivityCfg.class, "activity_monster_hunt").battlePassPoints());

        // 同 requestId 重投：拒，且不再发货
        assertThat(codeOf(claim(playerId, "activity_monster_hunt", requestId))).isEqualTo(1002);
        assertThat(goldOf(playerId)).as("重投不能再发一次奖").isEqualTo(goldAfter);
        assertThat(battlePass.status(playerId).points()).as("重投也不能再加一次分").isEqualTo(pointsAfter);

        // 换 requestId 再领：同窗口重复领取被拒（12006），detail 说清是"已领过"
        JsonNode again = claim(playerId, "activity_monster_hunt", "req-" + UUID.randomUUID());
        assertThat(codeOf(again)).isEqualTo(12006);
        assertThat(again.get("msg").asText()).isEqualTo("该活动当前不可领取");
        assertThat(again.get("detail").asText()).as("三种拒绝靠 detail 区分").contains("已经领过");
        assertThat(goldOf(playerId)).as("被拒的领取不许发货").isEqualTo(goldAfter);
    }

    @Test
    @DisplayName("验收 3：未达标领取被拒（12006，detail 带当前进度与目标）；活动不存在是另一个码（12005）")
    void claimRejectionsAreDistinct() throws Exception {
        String playerId = newPlayer();

        JsonNode notReached = claim(playerId, "activity_monster_hunt", "req-" + UUID.randomUUID());
        assertThat(codeOf(notReached)).isEqualTo(12006);
        assertThat(notReached.get("detail").asText())
                .as("玩家要看到差多少 —— detail 是三种拒绝唯一能区分的地方").contains("还没达标");

        JsonNode missing = claim(playerId, "activity_no_such_row", "req-" + UUID.randomUUID());
        assertThat(codeOf(missing)).as("活动不在表里与「现在领不了」是两个码").isEqualTo(12005);
    }

    // ---------- 验收 4：窗口过期 ----------

    @Test
    @DisplayName("验收 4：上一轮有进展但没领的行标 EXPIRED、领不了，记录还在（不清记录）")
    void expiredRowIsVisibleButNotClaimable() throws Exception {
        String playerId = newPlayer();
        long now = System.currentTimeMillis();
        events.progress(playerId, GoalType.KILL_MONSTER, "m1", 30L, now);

        // 夹具：把这一行改回「上一轮的窗口」—— 昨天做过但没领，今天才打开面板（在库里的真实样子）
        ActivityProgressStore.State stored = store.load(playerId).orElseThrow();
        List<ActivityProgress.Entry> entries = new java.util.ArrayList<>();
        for (ActivityProgress.Entry entry : stored.entries()) {
            entries.add(entry.activityId().equals("activity_monster_hunt")
                    ? new ActivityProgress.Entry(entry.activityId(), 1_700_000_000_000L,
                            entry.value(), entry.claimed(), entry.lastLoginAt())
                    : entry);
        }
        store.save(playerId, new ActivityProgressStore.State(playerId, entries, stored.serverOpenMs()));

        JsonNode row = rowOf(playerId, "activity_monster_hunt");
        assertThat(row.get("state").asText()).isEqualTo("EXPIRED");
        assertThat(row.get("progress").asLong()).as("记录不删：上一轮的 30 还看得见").isEqualTo(30L);
        assertThat(list(playerId).get("claimableCount").asInt())
                .as("不可领的行不许让红点亮").isZero();

        JsonNode claim = claim(playerId, "activity_monster_hunt", "req-" + UUID.randomUUID());
        assertThat(codeOf(claim)).isEqualTo(12006);
        assertThat(claim.get("detail").asText()).contains("上一轮");
    }

    // ---------- 验收 8：红点 ----------

    @Test
    @DisplayName("验收 8：达标时红点叶 activity/claimable 亮，领完同轮熄灭")
    void reddotLeafFollowsClaimableCount() throws Exception {
        String playerId = newPlayer();
        long now = System.currentTimeMillis();

        assertThat(activityDot(playerId)).as("什么都没做时不亮").isFalse();
        events.progress(playerId, GoalType.PVP_WIN, null, 10L, now);
        assertThat(activityDot(playerId)).as("有可领的奖励就该亮").isTrue();

        assertThat(codeOf(claim(playerId, "activity_pvp_win", "req-" + UUID.randomUUID()))).isZero();
        assertThat(activityDot(playerId)).as("领完同轮熄灭（判定与列表同源，不是各算一遍）").isFalse();
    }

    // ---------- 夹具与工具 ----------

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq("req-" + UUID.randomUUID(),
                "dev-" + UUID.randomUUID(), "活动测试", 1_700_000_000_000L, "")).playerId();
    }

    private String deviceOf(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow().deviceId();
    }

    /** 列表响应里的某一行（读进度与状态都从这里取，判据与客户端看到的是同一份）。 */
    private JsonNode rowOf(String playerId, String activityId) throws Exception {
        for (JsonNode row : dataOf(request(get("/activity/list").header("X-Player-Id", playerId)))
                .get("activities")) {
            if (row.get("id").asText().equals(activityId)) {
                return row;
            }
        }
        throw new AssertionError("列表里没有 " + activityId);
    }

    private long valueOf(String playerId, String activityId) throws Exception {
        return rowOf(playerId, activityId).get("progress").asLong();
    }

    private long goalOf(String activityId) {
        return configs.all(com.ironoath.config.cfg.ActivityCfg.class).stream()
                .filter(row -> row.id().equals(activityId))
                .map(com.ironoath.config.cfg.ActivityCfg::conditionValue)
                .findFirst().orElseThrow();
    }

    private long goldOf(String playerId) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        return save.resource("GOLD").current();
    }

    private boolean activityDot(String playerId) throws Exception {
        JsonNode nodes = dataOf(request(get("/social/reddot").header("X-Player-Id", playerId)))
                .get("nodes");
        for (JsonNode top : nodes) {
            if (top.get("key").asText().equals("activity")) {
                return top.get("lit").asBoolean();
            }
        }
        throw new AssertionError("红点里没有 activity 分支：叶子没注册上？nodes=" + nodes);
    }

    private JsonNode claim(String playerId, String activityId, String requestId) throws Exception {
        return request(post("/activity/claim").header("X-Player-Id", playerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(new ActivityClaimReq(activityId, requestId))));
    }

    private JsonNode list(String playerId) throws Exception {
        return dataOf(request(get("/activity/list").header("X-Player-Id", playerId)));
    }

    private JsonNode request(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 响应里的 data 段（统一 Result 包装；错误时它可能为 null）。 */
    private static JsonNode dataOf(JsonNode root) {
        return root.get("data");
    }

    private static int codeOf(JsonNode root) {
        return root.get("code").asInt();
    }
}
