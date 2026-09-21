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
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.battle.BattleReportService;
import com.ironoath.web.dto.generated.BlockReq;
import com.ironoath.web.dto.generated.ChatChannel;
import com.ironoath.web.dto.generated.ChatListReq;
import com.ironoath.web.dto.generated.ChatSendReq;
import com.ironoath.web.dto.generated.MarchAction;
import com.ironoath.web.dto.generated.MarchUnit;
import com.ironoath.web.dto.generated.MarchReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.ReportReason;
import com.ironoath.web.dto.generated.ReportReq;
import com.ironoath.web.service.MarchAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemoryWorldStore;
import com.ironoath.web.store.memory.SortedMarchDueQueue;

/**
 * 职责：B22 §一 3「举报 / 拉黑」的端到端验证 —— 验收 5（举报留痕可查）、6（拉黑只在交流层生效）、
 * 7（双实现等价，见 {@code SocialStoreEquivalenceTest}）、8 的举报那一半（内容安全送检）。
 * 依赖：Spring Boot Test + MockMvc + 内存存储 + 本机不需要 Mongo。
 *
 * <p><b>为什么有"拉黑不挡战斗"这条用例</b>：拉黑最容易做过头 —— 顺手在攻击判定里也查一次黑名单
 * 看起来"更安全"，实际是把私人关系凌驾在国家与联盟的冲突优先级之上（B13：国家 &gt; 联盟 &gt; 小队），
 * 而且被拉黑的人会变成"打不到也打不着"的幽灵。这条用例就是那个越界实现的判别器：
 * 拉黑之后，对方打我照样打得着。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportBlockEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final String OPS_HEADER = "X-Ops-Token";
    /** 与 application-test.yml 里 ironoath.ops.token 的假值一致。 */
    private static final String OPS_TOKEN = "test-ops-token";
    private static final String UNIT = "unit_infantry_t1";

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private ArmyRepository armies;
    @Autowired private CityRepository cities;
    @Autowired private WorldRepository world;
    @Autowired private MarchRepository marches;
    @Autowired private MarchDueQueue dueQueue;
    @Autowired private MarchAppService marchAppService;
    @Autowired private BattleReportService battleReports;
    @Autowired private SocialStore socialStore;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
        ((InMemoryArmyStore) armies).clear();
        ((InMemoryWorldStore) world).clear();
        ((InMemoryMarchStore) marches).clear();
        ((SortedMarchDueQueue) dueQueue).clear();
        socialStore.clear();
    }

    @Test
    @DisplayName("验收 5：举报落进留痕表，运维只读出口查得到那一整行（谁/谁/哪条/原因/何时）")
    void reportLandsInTheLedgerAndOpsCanReadIt() throws Exception {
        String reporter = newPlayer(1, 0, 0);
        String target = newPlayer(1, 40, 40);

        JsonNode receipt = post200("/social/report", reporter, new ReportReq(
                newRequestId(), target, "msg_abc", ReportReason.ABUSE, "连续辱骂了三句"));
        assertThat(receipt.get("reportId").asText()).startsWith("report_");
        assertThat(receipt.get("serverNow").asLong()).isPositive();

        JsonNode ops = get200("/ops/report/recent", OPS_TOKEN, OPS_HEADER);
        assertThat(ops.get("total").asLong()).as("窗口内共一条").isEqualTo(1);
        JsonNode row = ops.get("rows").get(0);
        assertThat(row.get("reportId").asText()).isEqualTo(receipt.get("reportId").asText());
        assertThat(row.get("reporterId").asText()).isEqualTo(reporter);
        assertThat(row.get("targetPlayerId").asText()).isEqualTo(target);
        assertThat(row.get("messageId").asText())
                .as("带上消息 id，运营才看得到被举报的原话").isEqualTo("msg_abc");
        assertThat(row.get("reason").asText()).isEqualTo("ABUSE");
        assertThat(row.get("detail").asText()).isEqualTo("连续辱骂了三句");
        assertThat(row.get("createdAt").asLong()).isPositive();

        // 对照：不给令牌读不到（留痕里是玩家之间的指控，不是公开数据）
        JsonNode noToken = getRaw("/ops/report/recent", null, null);
        assertThat(noToken.get("code").asInt()).as("没有运维令牌必须被拒").isNotZero();
    }

    @Test
    @DisplayName("举报防刷：同一目标 24 小时内第 4 次被拒，且不会多出留痕")
    void reportRateLimitStopsTheFourthOne() throws Exception {
        String reporter = newPlayer(1, 0, 0);
        String target = newPlayer(1, 40, 40);
        for (int i = 0; i < 3; i++) {
            post200("/social/report", reporter, new ReportReq(
                    newRequestId(), target, null, ReportReason.SPAM, null));
        }
        JsonNode fourth = postRaw("/social/report", reporter, new ReportReq(
                newRequestId(), target, null, ReportReason.SPAM, null));
        assertThat(fourth.get("code").asInt())
                .as("同一个人 24 小时内最多举报 %s 次（global.SOCIAL_REPORT_DAILY_LIMIT）",
                        "SOCIAL_REPORT_DAILY_LIMIT 的值")
                .isEqualTo(ErrorCode.SOCIAL_REPORT_DUPLICATE.code());

        JsonNode ops = get200("/ops/report/recent", OPS_TOKEN, OPS_HEADER);
        assertThat(ops.get("total").asLong())
                .as("被拒的那次不许留痕 —— 否则防刷闸只是把噪声推迟到运营那边")
                .isEqualTo(3);
        // 换个人举报同一个人不受影响：额度是按（谁, 谁）算的
        post200("/social/report", newPlayer(1, 80, 80),
                new ReportReq(newRequestId(), target, null, ReportReason.CHEAT_SUSPECT, null));
        assertThat(get200("/ops/report/recent", OPS_TOKEN, OPS_HEADER).get("total").asLong())
                .isEqualTo(4);
    }

    @Test
    @DisplayName("验收 6：私聊双向拒收，且错误里说清是哪一个方向")
    void blockedPrivateMessagingIsRejectedBothWaysWithDirection() throws Exception {
        String blocker = newPlayer(1, 0, 0);
        String blocked = newPlayer(1, 40, 40);
        post200("/social/block", blocker, new BlockReq(newRequestId(), blocked));

        JsonNode toBlocker = postRaw("/chat/send", blocked, new ChatSendReq(
                newRequestId(), ChatChannel.PRIVATE, "在吗", blocker));
        assertThat(toBlocker.get("code").asInt()).isEqualTo(ErrorCode.SOCIAL_BLOCKED.code());
        assertThat(toBlocker.path("detail").asText())
                .as("被对方拉黑：下一步只能等（或换别的途径），说成「你已拉黑对方」会让他去找解除按钮")
                .contains("对方已将你拉黑");

        JsonNode fromBlocker = postRaw("/chat/send", blocker, new ChatSendReq(
                newRequestId(), ChatChannel.PRIVATE, "我不想回你", blocked));
        assertThat(fromBlocker.get("code").asInt()).isEqualTo(ErrorCode.SOCIAL_BLOCKED.code());
        assertThat(fromBlocker.path("detail").asText())
                .as("自己拉黑的：下一步是去名单里解除")
                .contains("你已拉黑对方");

        // 两条都没落库：私聊历史里一条都不该有
        assertThat(messagesIn(blocker, ChatChannel.PRIVATE, blocked)).isEmpty();

        // 解除之后又发得出去（拉黑是可逆的，不是封禁）
        post200("/social/unblock", blocker, new BlockReq(newRequestId(), blocked));
        post200("/chat/send", blocked, new ChatSendReq(
                newRequestId(), ChatChannel.PRIVATE, "现在能发了吗", blocker));
        assertThat(messagesIn(blocker, ChatChannel.PRIVATE, blocked)).hasSize(1);
    }

    @Test
    @DisplayName("验收 6：频道里我不再看见被拉黑者的消息，但别人照常看得见")
    void blockHidesTheSendersMessagesFromMeOnly() throws Exception {
        String blocker = newPlayer(1, 0, 0);
        String blocked = newPlayer(1, 40, 40);
        String bystander = newPlayer(1, 80, 80);
        post200("/chat/send", blocked, new ChatSendReq(
                newRequestId(), ChatChannel.WORLD, "世界上第一条", null));

        post200("/social/block", blocker, new BlockReq(newRequestId(), blocked));

        assertThat(messagesIn(blocker, ChatChannel.WORLD, null))
                .as("我拉黑的人说的话我看不到").isEmpty();
        assertThat(messagesIn(bystander, ChatChannel.WORLD, null))
                .as("拉黑是观察者自己的过滤：别人还看得到原话（所以举报才有证据）")
                .hasSize(1);
        // 名单能在界面上看到（加/删都要看得见自己拉黑了谁）
        // 2026-09-22（#322）：名单从"一串 id"改成对象列表 —— 界面上要显示名字，
        // 而客户端不查表，所以名字必须由服务端解析好下发。
        JsonNode mine = get200("/social/blocks", blocker, PLAYER_HEADER);
        assertThat(mine.get("blocked").get(0).get("playerId").asText()).isEqualTo(blocked);
        String blockedName = players.findByPlayerId(blocked).orElseThrow().nickName();
        assertThat(mine.get("blocked").get(0).get("name").asText())
                .as("显示名就是那个人的昵称，服务端解析、客户端不查表").isEqualTo(blockedName);
        assertThat(mine.get("blocked").get(0).get("name").asText())
                .as("名字不许等于 id —— 那正是 #322 的形态").isNotEqualTo(blocked);
    }

    @Test
    @DisplayName("验收 6 的判别性用例：拉黑不挡战斗 —— 被拉黑的人照样打得着我")
    void blockDoesNotStopCombat() throws Exception {
        String defender = newPlayer(1, 100, 100);
        String attacker = newPlayer(1, 120, 120);
        // 兵力要落在战力圈层（B08）允许的区间里：守方太弱会被 AttackGuard 直接拦下，
        // 那样这条用例就变成"在测圈层"，而不是"在测拉黑不影响战斗"（记忆里的 1300 vs 1800 同一条）
        giveTroops(attacker, 4_000L);
        giveTroops(defender, 2_500L);
        post200("/social/block", defender, new BlockReq(newRequestId(), attacker));

        String marchId = sendAttack(attacker, Coord.of(100, 100));
        arriveAndProcess(marchId);

        assertThat(battleReports.list(defender).reports())
                .as("拉黑只切断交流：被打的人照样收到战报（B13 冲突优先级里私人关系不凌驾于国战之上）")
                .isNotEmpty();
        assertThat(battleReports.list(attacker).reports())
                .as("攻方也照样拿到自己的那份战报").isNotEmpty();
    }

    // ---------- 夹具 ----------

    /** 一个城在世界坐标 (x,y)、保护已解除的玩家。 */
    private String newPlayer(int cityLevel, int x, int y) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(),
                "举报测试" + UUID.randomUUID().toString().substring(0, 6),
                1_700_000_000_000L, "")).playerId();
        assertThat(world.placeCity(playerId, Coord.of(x, y)))
                .as("夹具必须能把城放到 (%d,%d)，占位说明坐标与别的用例撞了", x, y).isTrue();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(cityLevel);
        save.setProtectUntil(null);
        players.save(save);
        return playerId;
    }

    private void giveTroops(String playerId, long count) {
        if (armies.findByPlayerId(playerId).isEmpty()) {
            armies.insertIfAbsent(playerId, new ArmyState());
        }
        ArmyState army = armies.findByPlayerId(playerId).orElseThrow();
        long version = armies.versionOf(playerId);
        army.add(UNIT, count);
        armies.save(playerId, army, version);
    }

    private String sendAttack(String attackerId, Coord target) {
        List<MarchUnit> marched = new ArrayList<>();
        marched.add(new MarchUnit(UNIT, 4_000L));
        return marchAppService.send(attackerId, new MarchReq("req-" + UUID.randomUUID(),
                target.x(), target.y(), marched, List.of(), MarchAction.ATTACK)).march().marchId();
    }

    /** 把行军推到"该到了"，再跑一次到期处理（与 BotAttackQuotaTest 同一手法）。 */
    private void arriveAndProcess(String marchId) {
        var march = marches.findById(marchId).orElseThrow();
        long version = marches.versionOf(marchId);
        march.restore(march.startAt(), march.returnArriveAt(), march.returnStartAt(),
                march.returnFrom(), march.load(), march.status(), march.gatherStartAt(),
                march.units());
        marches.save(march, version);
        dueQueue.reschedule(marchId, march.startAt());
        marchAppService.processDue(march.playerId(), System.currentTimeMillis());
    }

    private List<JsonNode> messagesIn(String playerId, ChatChannel channel, String toPlayerId)
            throws Exception {
        JsonNode data = post200("/chat/list", playerId, new ChatListReq(channel, toPlayerId, null, 50));
        List<JsonNode> out = new ArrayList<>();
        data.get("messages").forEach(out::add);
        return out;
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private JsonNode get200(String url, String playerId, String header) throws Exception {
        return okData(perform(get(url).header(header, playerId)));
    }

    private JsonNode getRaw(String url, String playerId, String header) throws Exception {
        MockHttpServletRequestBuilder builder = get(url);
        if (playerId != null && header != null) {
            builder = builder.header(header, playerId);
        }
        return perform(builder);
    }

    private JsonNode post200(String url, String playerId, Object req) throws Exception {
        return okData(postRaw(url, playerId, req));
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

    private JsonNode okData(JsonNode root) {
        assertThat(root.get("code").asInt())
                .as("这一步本该成功：msg=" + root.path("msg").asText()
                        + " detail=" + root.path("detail").asText(""))
                .isZero();
        return root.get("data");
    }
}
