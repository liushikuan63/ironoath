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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.Coord;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.RallyJoinReq;
import com.ironoath.web.dto.generated.RallyTroop;
import com.ironoath.web.dto.generated.SocialCoord;
import com.ironoath.web.dto.generated.SocialTargetType;
import com.ironoath.web.dto.generated.SquadCreateReq;
import com.ironoath.web.dto.generated.SquadIdReq;
import com.ironoath.web.dto.generated.SquadRallyReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.WorldAppService;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：B10 §5 集结的端到端验证 —— 承诺即锁定、退出/取消原路退回、权限位、组织边界。
 * 依赖：Spring Boot Test + MockMvc；test profile（内存存储）。
 *
 * <p><b>本类盯的核心是「兵不会被吞」</b>：集结的每一步都在动玩家的真实兵力
 * （加入时从城内军队扣除，退出/取消时退回）。这条链上任何一个分支漏了退款，
 * 玩家就会看到「兵少了但集结里没有我」—— 而这类问题不会让任何数值测试变红，
 * 只会变成一条客服工单。所以每条用例都同时断言集结状态<b>和</b>城内兵力。
 *
 * <p><b>出发与返程分兵不在本类</b>：那半条链路（合并行军、按承诺比例把幸存兵力分回各人）
 * 由 {@code RallyDepartureTest} 覆盖。本类只管组织与承诺这一侧 ——
 * 谁能动兵、动了之后还能不能退回来。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RallyEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final String UNIT = "unit_infantry_t1";

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private WorldAppService worldAppService;
    @Autowired private PlayerRepository players;
    @Autowired private ArmyRepository armies;
    @Autowired private SocialStore socialStore;
    @Autowired private com.ironoath.web.service.PowerRefreshService powerRefreshService;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryArmyStore) armies).clear();
        socialStore.clear();
    }

    // ---------- 发起与承诺 ----------

    @Test
    @DisplayName("发起小队集结：承诺的兵力当场从城内军队扣除，响应里带上了总兵力与准备截止")
    void initiatingLocksTheInitiatorsTroops() throws Exception {
        Squad squad = twoMemberSquad();
        giveTroops(squad.leader, 1_000L);
        long before = troopsOf(squad.leader);

        JsonNode rally = post200("/rally/squad", squad.leader,
                new SquadRallyReq(newRequestId(), coordOf(squad.leader),
                        SocialTargetType.PLAYER_CITY, List.of(new RallyTroop(UNIT, 300L)), java.util.List.of()))
                .get("rally");

        assertThat(rally.get("scope").asText()).isEqualTo("SQUAD");
        assertThat(rally.get("groupId").asText()).isEqualTo(squad.squadId);
        assertThat(rally.get("initiatorId").asText()).isEqualTo(squad.leader);
        assertThat(rally.get("joinedCount").asInt()).as("发起人自己就是第一个参与者").isEqualTo(1);
        assertThat(rally.get("totalTroops").asLong()).isEqualTo(300L);
        assertThat(rally.get("status").asText()).isEqualTo("PREPARING");
        assertThat(rally.get("prepareUntil").asLong()).as("准备窗口必须有截止时刻").isPositive();
        assertThat(rally.get("members").size()).as("成员按加入顺序").isEqualTo(1);

        assertThat(troopsOf(squad.leader))
                .as("承诺即锁定：300 个兵必须已经从城内军队里扣走")
                .isEqualTo(before - 300L);
    }

    @Test
    @DisplayName("队员加入：人数与总兵力都累加，加入者的兵同样被锁定")
    void joiningAddsTroopsAndCounts() throws Exception {
        Squad squad = twoMemberSquad();
        giveTroops(squad.leader, 1_000L);
        giveTroops(squad.mate, 800L);
        String rallyId = post200("/rally/squad", squad.leader,
                new SquadRallyReq(newRequestId(), coordOf(squad.leader),
                        SocialTargetType.PLAYER_CITY, List.of(new RallyTroop(UNIT, 300L)), java.util.List.of()))
                .get("rally").get("rallyId").asText();
        long mateBefore = troopsOf(squad.mate);

        JsonNode rally = post200("/rally/join", squad.mate,
                new RallyJoinReq(newRequestId(), rallyId, List.of(new RallyTroop(UNIT, 200L)), java.util.List.of()))
                .get("rally");

        assertThat(rally.get("joinedCount").asInt()).isEqualTo(2);
        assertThat(rally.get("totalTroops").asLong()).as("300 + 200").isEqualTo(500L);
        assertThat(rally.get("members").size()).isEqualTo(2);
        assertThat(rally.get("members").get(0).asText()).as("按加入顺序，发起人在前").isEqualTo(squad.leader);
        assertThat(rally.get("members").get(1).asText()).isEqualTo(squad.mate);
        assertThat(troopsOf(squad.mate)).as("加入者的兵也被锁定").isEqualTo(mateBefore - 200L);
    }

    // ---------- 退款 ----------

    @Test
    @DisplayName("退出集结：承诺的兵力原路退回，一个不少")
    void quittingRefundsTheTroops() throws Exception {
        Squad squad = twoMemberSquad();
        giveTroops(squad.leader, 1_000L);
        giveTroops(squad.mate, 800L);
        String rallyId = initiate(squad);
        post200("/rally/join", squad.mate,
                new RallyJoinReq(newRequestId(), rallyId, List.of(new RallyTroop(UNIT, 200L)), java.util.List.of()));
        long mateAfterJoin = troopsOf(squad.mate);

        JsonNode rally = post200("/rally/quit", squad.mate,
                new RallyJoinReq(newRequestId(), rallyId, List.of(), java.util.List.of())).get("rally");

        assertThat(rally.get("joinedCount").asInt()).as("退出后只剩发起人").isEqualTo(1);
        assertThat(rally.get("totalTroops").asLong()).isEqualTo(300L);
        assertThat(troopsOf(squad.mate)).as("退回 200").isEqualTo(mateAfterJoin + 200L);
    }

    @Test
    @DisplayName("发起人取消：所有参与者的兵力都退回，不只是发起人自己的")
    void cancellingRefundsEveryone() throws Exception {
        Squad squad = twoMemberSquad();
        giveTroops(squad.leader, 1_000L);
        giveTroops(squad.mate, 800L);
        String rallyId = initiate(squad);
        post200("/rally/join", squad.mate,
                new RallyJoinReq(newRequestId(), rallyId, List.of(new RallyTroop(UNIT, 200L)), java.util.List.of()));
        long leaderAfter = troopsOf(squad.leader);
        long mateAfter = troopsOf(squad.mate);

        post200("/rally/cancel", squad.leader, new RallyJoinReq(newRequestId(), rallyId, List.of(), java.util.List.of()));

        assertThat(troopsOf(squad.leader)).as("发起人的 300 退回").isEqualTo(leaderAfter + 300L);
        assertThat(troopsOf(squad.mate)).as("成员的 200 也必须退回，取消不是没收").isEqualTo(mateAfter + 200L);

        JsonNode root = getRaw("/rally", squad.mate, "rallyId=" + rallyId);
        assertThat(root.get("code").asInt())
                .as("取消后的集结不该再出现在详情里").isEqualTo(ErrorCode.RALLY_NOT_FOUND.code());
    }

    @Test
    @DisplayName("发起人退出等于取消：没有人能替他指出兵，所以全体成员的兵都退回")
    void initiatorQuittingCancelsForEveryone() throws Exception {
        Squad squad = twoMemberSquad();
        giveTroops(squad.leader, 1_000L);
        giveTroops(squad.mate, 800L);
        String rallyId = initiate(squad);
        post200("/rally/join", squad.mate,
                new RallyJoinReq(newRequestId(), rallyId, List.of(new RallyTroop(UNIT, 200L)), java.util.List.of()));
        long mateAfter = troopsOf(squad.mate);

        post200("/rally/quit", squad.leader, new RallyJoinReq(newRequestId(), rallyId, List.of(), java.util.List.of()));

        assertThat(troopsOf(squad.mate)).as("成员不能因为发起人退出就被没收兵力").isEqualTo(mateAfter + 200L);
    }

    // ---------- 权限与边界 ----------

    @Test
    @DisplayName("权限位来自 role_permission 表：普通队员发起小队集结被拒，错误里点明缺哪个权限位")
    void memberCannotInitiateSquadRally() throws Exception {
        Squad squad = twoMemberSquad();
        giveTroops(squad.mate, 500L);
        long before = troopsOf(squad.mate);

        JsonNode root = postRaw("/rally/squad", squad.mate,
                new SquadRallyReq(newRequestId(), coordOf(squad.mate),
                        SocialTargetType.PLAYER_CITY, List.of(new RallyTroop(UNIT, 100L)), java.util.List.of()));

        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.code());
        assertThat(root.path("detail").asText()).as("要说清缺的是哪个权限位").contains("START_RALLY");
        assertThat(troopsOf(squad.mate)).as("被拒时不能扣兵").isEqualTo(before);
    }

    @Test
    @DisplayName("组织边界：不在同一个小队里的人不能加入，否则任何人构造一个 rallyId 就能把兵塞进别人的集结")
    void outsiderCannotJoin() throws Exception {
        Squad squad = twoMemberSquad();
        giveTroops(squad.leader, 1_000L);
        String rallyId = initiate(squad);

        String outsider = newPlayer(10);
        giveTroops(outsider, 500L);
        long before = troopsOf(outsider);

        JsonNode root = postRaw("/rally/join", outsider,
                new RallyJoinReq(newRequestId(), rallyId, List.of(new RallyTroop(UNIT, 100L)), java.util.List.of()));

        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.RALLY_NOT_FOUND.code());
        assertThat(troopsOf(outsider)).as("被拒时不能扣兵").isEqualTo(before);
    }

    @Test
    @DisplayName("承诺超过持有量时被拒且一分不扣：校验必须发生在第一次扣之前")
    void overCommittingIsRejectedWithoutPartialDeduction() throws Exception {
        Squad squad = twoMemberSquad();
        giveTroops(squad.leader, 1_000L);
        long before = troopsOf(squad.leader);

        JsonNode root = postRaw("/rally/squad", squad.leader,
                new SquadRallyReq(newRequestId(), coordOf(squad.leader), SocialTargetType.PLAYER_CITY,
                        // 第一种兵够、第二种兵不够：若实现是「边扣边校验」，第一种就已经被扣走了
                        List.of(new RallyTroop(UNIT, 100L), new RallyTroop("unit_infantry_t2", 99_999L)), java.util.List.of()));

        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.RALLY_NO_TROOP.code());
        assertThat(troopsOf(squad.leader)).as("先全量校验再扣，所以一个兵都不该少").isEqualTo(before);
    }

    @Test
    @DisplayName("列表只给进行中的集结，且带服务端时间戳（倒计时不得用客户端本地时钟）")
    void listReturnsPreparingRalliesWithServerNow() throws Exception {
        Squad squad = twoMemberSquad();
        giveTroops(squad.leader, 1_000L);
        giveTroops(squad.mate, 800L);
        String rallyId = initiate(squad);

        JsonNode data = get200("/rally/list", squad.mate);
        assertThat(data.get("serverNow").asLong()).isPositive();
        assertThat(data.get("rallies").size()).isEqualTo(1);
        assertThat(data.get("rallies").get(0).get("rallyId").asText()).isEqualTo(rallyId);

        post200("/rally/cancel", squad.leader, new RallyJoinReq(newRequestId(), rallyId, List.of(), java.util.List.of()));
        assertThat(get200("/rally/list", squad.mate).get("rallies").size())
                .as("取消后不该再出现在列表里").isZero();
    }

    // ---------- 夹具 ----------

    private record Squad(String squadId, String leader, String mate) {
    }

    /** 一个两人小队：队长 + 一名队员（都 10 级城、解除新手保护、各有一支军队）。 */
    // ---------- B26 S13：发起集结的政策读口（GET /rally/policy） ----------

    @Test
    @DisplayName("谁都不在的时候两份政策都说不行，而且理由是人话：不出现权限码与字段名")
    void policyExplainsWhyNobodyCanStart() throws Exception {
        String lonely = newPlayer(10);
        JsonNode policy = get200("/rally/policy", lonely);

        assertThat(policy.path("squad").path("canStart").asBoolean()).isFalse();
        assertThat(policy.path("alliance").path("canStart").asBoolean()).isFalse();
        String reasons = policy.path("squad").path("reason").asText()
                + " / " + policy.path("alliance").path("reason").asText();
        assertThat(reasons).as("两句都得有内容").doesNotContain("null");
        assertThat(reasons).as("权限码与字段名不许上屏（同族见 #268 / #288）")
                .doesNotContain("START_RALLY").doesNotContain("canStart").doesNotContain("maxMembers");
    }

    @Test
    @DisplayName("两个人的小队：canStart 为真，且 maxMembers 是「配置上限」与「实际人数」的小值")
    void policyCapsMembersAtTheRealHeadcount() throws Exception {
        Squad squad = twoMemberSquad();
        JsonNode view = get200("/rally/policy", squad.leader).path("squad");

        assertThat(view.path("canStart").asBoolean())
                .as("两个人刚好够最低档").isTrue();
        assertThat(view.path("minMembers").asInt()).isEqualTo(2);
        assertThat(view.path("maxMembers").asInt())
                .as("配置是 5，但这个小队只有 2 个人 —— 给 5 就是让滑条显示一个必然被夹掉的上限")
                .isEqualTo(2);
        assertThat(view.path("minPrepareMinutes").asInt())
                .as("时长区间来自 global.RALLY_PREPARE_*").isEqualTo(10);
        assertThat(view.path("maxPrepareMinutes").asInt()).isEqualTo(30);
        assertThat(view.path("defaultPrepareMinutes").asInt())
                .as("起始值由服务端给（最长那一档），客户端不自己挑数").isEqualTo(30);

        giveTroops(squad.leader, 500L);
        JsonNode rally = post200("/rally/squad", squad.leader,
                new SquadRallyReq(newRequestId(), coordOf(squad.leader),
                        SocialTargetType.PLAYER_CITY, List.of(new RallyTroop(UNIT, 300L)),
                        java.util.List.of()));
        assertThat(rally.path("rally").path("maxMembers").asInt())
                .as("按读口给的数发起，写口就不会再把它夹掉（读口与写口同一条式子）")
                .isEqualTo(view.path("maxMembers").asInt());
    }

    @Test
    @DisplayName("一个人的小队：人数凑不满最低档，政策说不行并给出那句人数原因（而不是让人去调滑条）")
    void policyRefusesASquadTooSmallToRally() throws Exception {
        String leader = newPlayer(10);
        post200("/squad/create", leader, new SquadCreateReq(newRequestId(), "光杆队"));
        JsonNode view = get200("/rally/policy", leader).path("squad");

        assertThat(view.path("canStart").asBoolean()).isFalse();
        assertThat(view.path("reason").asText()).contains("1 个人");
    }

    @Test
    @DisplayName("普通队员看到的 canStart 是 false：读口与写口同一条权限门，不是只有发起时才被拒")
    void policyGatesTheMemberTheSameWayTheWritePathDoes() throws Exception {
        Squad squad = twoMemberSquad();
        JsonNode view = get200("/rally/policy", squad.mate).path("squad");

        assertThat(view.path("canStart").asBoolean()).isFalse();
        assertThat(view.path("reason").asText()).contains("职位");
        giveTroops(squad.mate, 500L);
        JsonNode rejected = postRaw("/rally/squad", squad.mate,
                new SquadRallyReq(newRequestId(), coordOf(squad.mate),
                        SocialTargetType.PLAYER_CITY, List.of(new RallyTroop(UNIT, 100L)),
                        java.util.List.of()));
        assertThat(rejected.get("code").asInt())
                .as("读口说不能，写口就真的会拒 —— 两边不一致时界面上那个数字是假的")
                .isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.code());
    }
    private Squad twoMemberSquad() throws Exception {
        String leader = newPlayer(10);
        String mate = newPlayer(10);
        String squadId = post200("/squad/create", leader, new SquadCreateReq(newRequestId(), "集结小队"))
                .get("squad").get("id").asText();
        post200("/squad/join", mate, new SquadIdReq(newRequestId(), squadId));
        return new Squad(squadId, leader, mate);
    }

    private String initiate(Squad squad) throws Exception {
        return post200("/rally/squad", squad.leader,
                new SquadRallyReq(newRequestId(), coordOf(squad.leader),
                        SocialTargetType.PLAYER_CITY, List.of(new RallyTroop(UNIT, 300L)), java.util.List.of()))
                .get("rally").get("rallyId").asText();
    }

    /**
     * 集结目标用发起人自己的城：圈层校验（{@code guardRally}）需要一个真实目标，
     * 而自己打自己的战力比恰好是 1.0，稳定落在 [0.5, 2.0] 区间内。
     * 集结的组织流程与「打谁」无关，所以这个选择不影响用例要验的东西。
     */
    private SocialCoord coordOf(String playerId) {
        Coord home = worldAppService.homeOf(playerId);
        return new SocialCoord(home.x(), home.y());
    }

    private long troopsOf(String playerId) {
        return armies.findByPlayerId(playerId).map(ArmyState::totalTroops).orElse(0L);
    }

    private void giveTroops(String playerId, long count) {
        ArmyState army = armies.findByPlayerId(playerId).orElse(null);
        if (army == null) {
            armies.insertIfAbsent(playerId, new ArmyState());
            army = armies.findByPlayerId(playerId).orElseThrow();
        }
        long version = armies.versionOf(playerId);
        army.add(UNIT, count);
        armies.save(playerId, army, version);
        // **给兵之后必须重算战力**：圈层校验读的是存档里的匹配战力，
        // 而 giveTroops 只改了军队没刷新存档，于是存档里还是初始的「只有建筑」那 80 点，
        // 集结目标（本夹具用发起人自己的城）会被判成「对方实力远弱于你」而拒绝出发。
        // 这不是产品逻辑的问题，是夹具漏了一步 —— 真实玩家给兵之后打开面板就会触发重算
        powerRefreshService.refresh(playerId);
    }

    private void liftProtection(String playerId) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setProtectUntil(null);
        players.save(save);
    }

    private String newPlayer(int cityLevel) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "集结测试", 1_700_000_000_000L, ""))
                .playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(cityLevel);
        PlayerResourceState gold = save.resources().get("GOLD");
        if (gold != null) {
            save.putResource("GOLD", new PlayerResourceState(
                    100_000L, gold.cap(), gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        }
        players.save(save);
        liftProtection(playerId);
        return playerId;
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private JsonNode post200(String url, String playerId, Object req) throws Exception {
        return okData(postRoot(url, playerId, req));
    }

    private JsonNode postRaw(String url, String playerId, Object req) throws Exception {
        return postRoot(url, playerId, req);
    }

    private JsonNode get200(String url, String playerId) throws Exception {
        return okData(getRaw(url, playerId, null));
    }

    private JsonNode getRaw(String url, String playerId, String query) throws Exception {
        MockHttpServletRequestBuilder builder = get(query == null ? url : url + "?" + query)
                .header(PLAYER_HEADER, playerId);
        return perform(builder);
    }

    private JsonNode postRoot(String url, String playerId, Object req) throws Exception {
        return perform(post(url).header(PLAYER_HEADER, playerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(req)));
    }

    private JsonNode perform(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        // MockMvc 默认按 ISO-8859-1 解码响应体，中文提示会变乱码
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode okData(JsonNode root) {
        assertThat(root.get("code").asInt())
                .as("业务码必须为 0，实际响应=%s", root).isZero();
        return root.get("data");
    }
}
