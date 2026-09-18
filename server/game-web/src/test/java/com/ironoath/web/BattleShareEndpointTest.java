package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.battle.BattleReport;
import com.ironoath.web.battle.BattleReportService;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.AllianceSelfReq;
import com.ironoath.web.dto.generated.ChatChannel;
import com.ironoath.web.dto.generated.ChatListReq;
import com.ironoath.web.dto.generated.ChallengeStageReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.ReportShareReq;
import com.ironoath.web.dto.generated.ShareChannel;
import com.ironoath.web.dto.generated.StageUnit;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：B22 §一 2「战报分享到聊天」的端到端验证 —— 验收 4 的三条（只能分享自己的、
 * 频道成员收得到且可点开回放、内容安全送检）+ 两条自己长出来的边界（非成员打不开、退盟即失效）。
 * 依赖：Spring Boot Test + MockMvc + 内存存储。
 *
 * <p><b>为什么用关卡战报做夹具</b>：它是本仓库里最便宜的一条"真的产生一份战报"的路
 * （不用世界地图、不用行军等待），而夹具必须走生产入口 —— 手搓一份 BattleReport 就是在测
 * 一个生产不再产生的形状。
 *
 * <p><b>分享的消息按正文里的 [report:id] 认</b>：这是服务端与客户端的约定
 * （`BattleReportService.shareText` ↔ 客户端 `ChatPanel.parseSharedReport`），
 * 用例断言的正是"收得到的那条里带得出被分享的战报 id"。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BattleShareEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final String STAGE_1 = "stage_01_01";
    private static final String UNIT = "unit_infantry_t1";

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private ArmyRepository armies;
    @Autowired private BattleReportService battleReports;
    @Autowired private com.ironoath.web.battle.BattleReportStore reports;
    @Autowired private SocialStore socialStore;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryArmyStore) armies).clear();
        socialStore.clear();
    }

    @Test
    @DisplayName("只能分享自己的战报：别人的一律回 REPORT_NOT_OWNED，且一条消息都不落频道")
    void onlyOwnReportsCanBeShared() throws Exception {
        String owner = newPlayer(10);
        String reportId = challengeOnce(owner);
        String stranger = newPlayer(10);
        post200("/alliance/create", stranger, new AllianceCreateReq(newRequestId(), "看客联盟", "WATCH"));

        JsonNode root = postRaw("/battle/share", stranger,
                new ReportShareReq(newRequestId(), reportId, ShareChannel.ALLIANCE));

        assertThat(root.get("code").asInt())
                .as("替别人分享等于替别人公开兵力构成与坐标")
                .isEqualTo(ErrorCode.REPORT_NOT_OWNED.code());
        assertThat(messagesIn(stranger, ChatChannel.ALLIANCE, null))
                .as("被拒的分享不许在频道里留下任何痕迹")
                .isEmpty();
    }

    @Test
    @DisplayName("未入盟的人分享到联盟频道：错误码与 /chat/send 完全一致（不另立一套资格判定）")
    void unaffiliatedPlayerGetsTheSameChannelErrorAsChatSend() throws Exception {
        String loner = newPlayer(10);
        String reportId = challengeOnce(loner);

        JsonNode share = postRaw("/battle/share", loner,
                new ReportShareReq(newRequestId(), reportId, ShareChannel.ALLIANCE));
        JsonNode chat = postRaw("/chat/send", loner,
                new com.ironoath.web.dto.generated.ChatSendReq(newRequestId(), ChatChannel.ALLIANCE, "喂", null));

        assertThat(share.get("code").asInt()).isEqualTo(ErrorCode.SOCIAL_CHAT_CHANNEL_INVALID.code());
        assertThat(share.get("code").asInt())
                .as("两处资格判定必须同一个码：分享走的就是聊天那条路")
                .isEqualTo(chat.get("code").asInt());
    }

    @Test
    @DisplayName("验收 4：分享落进联盟频道，频道成员读得到那条、且点得开回放")
    void shareLandsInTheChannelAndMembersCanOpenTheReplay() throws Exception {
        String leader = newPlayer(10);
        String member = newPlayer(10);
        String allianceId = post200("/alliance/create", leader,
                new AllianceCreateReq(newRequestId(), "铁誓同盟", "IRON")).get("alliance").get("id").asText();
        post200("/alliance/apply", member, new AllianceIdReq(newRequestId(), allianceId));
        post200("/alliance/review", leader, new AllianceReviewReq(newRequestId(), member, true));

        String reportId = challengeOnce(leader);
        JsonNode resp = post200("/battle/share", leader,
                new ReportShareReq(newRequestId(), reportId, ShareChannel.ALLIANCE));
        assertThat(resp.get("reportId").asText()).isEqualTo(reportId);
        assertThat(resp.get("channel").asText()).isEqualTo("ALLIANCE");
        assertThat(resp.get("messageId").asText()).isNotEmpty();

        // 1) 频道里真的有那条（成员视角读）—— 正文以 [report:<id>] 结尾，客户端据此认出可点开
        List<JsonNode> messages = messagesIn(member, ChatChannel.ALLIANCE, null);
        JsonNode shared = messages.stream()
                .filter(m -> m.path("content").asText().contains("[report:" + reportId + "]"))
                .findFirst().orElse(null);
        assertThat(shared)
                .as("成员必须读得到那条分享：读不到就等于分享没发出去")
                .isNotNull();
        assertThat(shared.get("senderId").asText()).isEqualTo(leader);

        // 2) 成员点得开回放（不是本人也能开 —— 这正是"分享"要的效果）
        JsonNode replay = get200("/battle/report?reportId=" + reportId, member);
        assertThat(replay.get("reportId").asText()).isEqualTo(reportId);
        assertThat(replay.get("result").get("rounds"))
                .as("回放要真有逐回合数据，而不是一个空壳")
                .isNotEmpty();
    }

    @Test
    @DisplayName("分享不等于公开：不在那个频道的人拿着同一个 id 也打不开")
    void nonMembersCannotOpenSharedReport() throws Exception {
        String leader = newPlayer(10);
        String stranger = newPlayer(10);
        post200("/alliance/create", leader, new AllianceCreateReq(newRequestId(), "铁誓同盟", "IRON"));
        String reportId = challengeOnce(leader);
        post200("/battle/share", leader, new ReportShareReq(newRequestId(), reportId, ShareChannel.ALLIANCE));

        JsonNode root = getRaw("/battle/report?reportId=" + reportId, stranger);
        assertThat(root.get("code").asInt())
                .as("可见范围跟着频道走：不是成员就与没分享过一样")
                .isEqualTo(ErrorCode.BATTLE_REPORT_NOT_FOUND.code());
    }

    @Test
    @DisplayName("退盟即失效：分享的可见范围按「此刻在不在那个频道」算")
    void leavingTheAllianceRevokesAccess() throws Exception {
        String leader = newPlayer(10);
        String member = newPlayer(10);
        String allianceId = post200("/alliance/create", leader,
                new AllianceCreateReq(newRequestId(), "铁誓同盟", "IRON")).get("alliance").get("id").asText();
        post200("/alliance/apply", member, new AllianceIdReq(newRequestId(), allianceId));
        post200("/alliance/review", leader, new AllianceReviewReq(newRequestId(), member, true));

        String reportId = challengeOnce(leader);
        post200("/battle/share", leader, new ReportShareReq(newRequestId(), reportId, ShareChannel.ALLIANCE));
        assertThat(get200("/battle/report?reportId=" + reportId, member).get("reportId").asText())
                .as("退盟之前看得到").isEqualTo(reportId);

        post200("/alliance/leave", member, new AllianceSelfReq(newRequestId()));

        assertThat(getRaw("/battle/report?reportId=" + reportId, member).get("code").asInt())
                .as("离开频道之后就读不到了 —— 与 /chat/list 同一条口径（拉不到那个频道的消息）")
                .isEqualTo(ErrorCode.BATTLE_REPORT_NOT_FOUND.code());
    }

    @Test
    @DisplayName("分享走聊天限流：同一条战报 10 秒内第 4 次被拒（禁止项：不许为分享新开绕限流的通道）")
    void shareReusesTheChatRateLimiter() throws Exception {
        String leader = newPlayer(10);
        post200("/alliance/create", leader, new AllianceCreateReq(newRequestId(), "铁誓同盟", "IRON"));
        String reportId = challengeOnce(leader);

        for (int i = 0; i < 3; i++) {
            post200("/battle/share", leader, new ReportShareReq(newRequestId(), reportId, ShareChannel.ALLIANCE));
        }
        JsonNode fourth = postRaw("/battle/share", leader,
                new ReportShareReq(newRequestId(), reportId, ShareChannel.ALLIANCE));

        assertThat(fourth.get("code").asInt())
                .as("同一内容限流窗口内只能发 3 次（global.CHAT_RATE_LIMIT_*）——"
                        + "分享若能绕开它，就等于给了一条刷屏通道")
                .isEqualTo(ErrorCode.SOCIAL_CHAT_RATE_LIMITED.code());
        assertThat(messagesIn(leader, ChatChannel.ALLIANCE, null))
                .as("被限流那次不许落库")
                .hasSize(3);
    }

    @Test
    @DisplayName("过期战报不能分享：说清楚是过期，而不是往频道里贴一条点开就报错的链接")
    void expiredReportCannotBeShared() throws Exception {
        String leader = newPlayer(10);
        post200("/alliance/create", leader, new AllianceCreateReq(newRequestId(), "铁誓同盟", "IRON"));
        String freshId = challengeOnce(leader);
        // 领域记录从存储端口读（列表端点给的是展示视图，构造夹具要的是那条真实记录）
        BattleReport fresh = reports.reportsOf(leader).get(0);

        // 造一份"一生下来就过期"的战报：字段与生产产出的一模一样，只有过期时刻不同
        // （同 MonsterHuntEndpointTest 那条惰性清理用例的手法：过期是时间流逝的自然结果，
        // 服务端没有、也不该有"写一份过期战报"的入口，所以直接落存储端口）
        BattleReport expired = new BattleReport("battle_share_expired", fresh.ownerId(),
                fresh.attackerId(), fresh.defenderId(), fresh.defenderName(), fresh.attackerName(),
                fresh.battleType(), fresh.attackerHeroIds(), fresh.defenderHeroIds(),
                fresh.result(), fresh.createdAt(), fresh.createdAt());
        reports.save(expired);

        assertThat(freshId).isNotBlank();
        JsonNode root = postRaw("/battle/share", leader,
                new ReportShareReq(newRequestId(), expired.reportId(), ShareChannel.ALLIANCE));
        assertThat(root.get("code").asInt())
                .as("与「回放已过期」同一个码：分享一条点开就报错的链接不如当场说清楚")
                .isEqualTo(ErrorCode.BATTLE_REPORT_EXPIRED.code());
        assertThat(messagesIn(leader, ChatChannel.ALLIANCE, null)).isEmpty();
    }

    // ---------- 夹具 ----------

    /** 一个达到联盟门槛（10 级）的玩家，余额备足建盟要花的金币。 */
    private String newPlayer(int cityLevel) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "分享测试", 1_700_000_000_000L, ""))
                .playerId();
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

    /** 打一关拿一份真实战报（1 个兵必输，但输赢都会留下可回放的记录）。 */
    private String challengeOnce(String playerId) throws Exception {
        if (armies.findByPlayerId(playerId).isEmpty()) {
            armies.insertIfAbsent(playerId, new ArmyState());
        }
        ArmyState army = armies.findByPlayerId(playerId).orElseThrow();
        long version = armies.versionOf(playerId);
        army.add(UNIT, 1L);
        armies.save(playerId, army, version);
        return post200("/stage/challenge", playerId,
                new ChallengeStageReq(newRequestId(), STAGE_1, List.of(new StageUnit(UNIT, 1L)), List.of()))
                .get("reportId").asText();
    }

    private List<JsonNode> messagesIn(String playerId, ChatChannel channel, String toPlayerId)
            throws Exception {
        JsonNode data = post200("/chat/list", playerId,
                new ChatListReq(channel, toPlayerId, null, 50));
        List<JsonNode> out = new ArrayList<>();
        data.get("messages").forEach(out::add);
        return out;
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private JsonNode get200(String url, String playerId) throws Exception {
        return okData(perform(get(url).header(PLAYER_HEADER, playerId)));
    }

    private JsonNode getRaw(String url, String playerId) throws Exception {
        return perform(get(url).header(PLAYER_HEADER, playerId));
    }

    private JsonNode post200(String url, String playerId, Object req) throws Exception {
        return okData(perform(post(url)
                .header(PLAYER_HEADER, playerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(req))));
    }

    private JsonNode postRaw(String url, String playerId, Object req) throws Exception {
        return perform(post(url)
                .header(PLAYER_HEADER, playerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(req)));
    }

    private JsonNode perform(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode okData(JsonNode root) {
        assertThat(root.get("code").asInt())
                .as("期望成功，实际：" + root).isZero();
        return root.get("data");
    }
}
