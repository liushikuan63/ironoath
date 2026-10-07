package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.NationAppointReq;
import com.ironoath.web.dto.generated.NationFoundReq;
import com.ironoath.web.dto.generated.NationOffice;
import com.ironoath.web.dto.generated.NationRallyReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.RallyJoinReq;
import com.ironoath.web.dto.generated.RallyTroop;
import com.ironoath.web.dto.generated.SocialCoord;
import com.ironoath.web.dto.generated.SocialTargetType;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：V22-a 国家层集结的服务端通路 —— 发起、加入、列表、政策读口，四条都真跑。
 * 依赖：Spring Boot Test + MockMvc；test profile（内存存储）。
 *
 * <p><b>本类盯的核心是「界面上亮着的数与服务端夹出来的数是同一个数」</b>。国家层的人数上限
 * 不是一个常量而是一个折叠值（{@code min(配置上限, 本国实有人数)}），V24 还要在这个点上继续叠
 * 科技与已购永久格。所以这里的断言全部<b>先读 {@code /rally/policy} 拿 cap、再拿 cap±1 去打写口</b>，
 * 一条都不写死 49/50 —— 写死数字的用例在 V24 落地那天会变成一批假红，把"上限会抬"这件事
 * 钉成"上限坏了"。
 *
 * <p><b>与 {@code RallyEndpointTest} 的一处夹具差异</b>：那一族把集结目标选成发起人自己的城
 * （圈层校验要一个真实目标，而自己打自己的战力比恰是 1.0）；国家层<b>不能</b>这样选，
 * 因为攻击闸门按「同国联盟不得互相攻击」把本国目标判掉了（理由见 {@link #request(int)}）。
 * 本类改用野怪格 —— 空地与野怪不构成 PVP，直接放行，而组织流程与"打谁"无关。
 *
 * <p><b>三条硬关各有一条点名的用例</b>（它们都是"不报错但功能不存在"的形状，全靠现跑才抓得到）：
 * ① {@code requireMembership} 的 NATION 支原先写死 false ⇒ {@link #memberCanJoinTheNationalRally}；
 * ② {@code preparingRallies} 原先只有小队与联盟两支 ⇒ {@link #listShowsTheNationalRally}；
 * ③ {@code initiateRally} 原先两分支分派 ⇒ {@link #nationalRallyUsesTheNationalRulesNotTheAllianceOnes}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NationRallyEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final String UNIT = "unit_infantry_t1";
    /** 集结目标格：世界 512×512 里的一处野怪格，与 {@code BotSocialRaidTest} 用的是同一处。 */
    private static final int TARGET_X = 400;
    private static final int TARGET_Y = 400;

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private ArmyRepository armies;
    @Autowired private SocialStore socialStore;
    @Autowired private com.ironoath.web.nation.NationStore nationStore;
    @Autowired private com.ironoath.web.service.PowerRefreshService powerRefreshService;

    /** 联盟名/标签与国名的序号：同一轮里要建好几个组织，重名会在 NAME_TAKEN 上报错而测不到逻辑。 */
    private int seq;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryArmyStore) armies).clear();
        socialStore.clear();
        nationStore.clear();
        seq = 0;
    }

    // ---------- 读口与写口同源 ----------

    @Test
    @DisplayName("同刻比对：/rally/policy 的 nation.maxMembers 与发起后 RallyView.maxMembers 相等")
    void policyAndWritePathReportTheSameNationalCap() throws Exception {
        Nation nation = nation(4);
        giveTroops(nation.king, 1_000L);

        JsonNode policy = get200("/rally/policy", nation.king).get("nation");
        int cap = policy.get("maxMembers").asInt();

        // 故意越界上报：写口必须夹到读口刚说出来的那个数，而不是别的东西
        JsonNode rally = post200("/rally/nation", nation.king,
                request(cap + 9)).get("rally");

        assertThat(rally.get("maxMembers").asInt())
                .as("写口夹出来的上限必须与读口刚报的是同一个数（写口绕开折叠点自己算就会红）")
                .isEqualTo(cap);
        assertThat(policy.get("canStart").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("国家层的 cap 是「配置上限与本国实有人数的小值」：人少的国拿不到配置原值")
    void capIsFoldedWithTheRealHeadcountNotTheConfiguredCeiling() throws Exception {
        Nation small = nation(3);
        JsonNode smallPolicy = get200("/rally/policy", small.king).get("nation");
        assertThat(smallPolicy.get("maxMembers").asInt())
                .as("3 个人的国，上限就是 3 —— 不是表里那个更大的数")
                .isEqualTo(3);

        Nation bigger = nation(5);
        JsonNode biggerPolicy = get200("/rally/policy", bigger.king).get("nation");
        assertThat(biggerPolicy.get("maxMembers").asInt())
                .as("换成人更多的国，cap 跟着涨 ⇒ 证明这个数真的来自人数折叠，不是常量")
                .isEqualTo(5);
    }

    // ---------- 边界：界值来自 policy，不写死 ----------

    @Test
    @DisplayName("上限两侧各一条：cap-1 原样成立、cap+1 被夹回 cap（两侧都从 policy 现读）")
    void boundaryOnBothSidesOfTheFoldedCap() throws Exception {
        Nation nation = nation(4);
        giveTroops(nation.king, 2_000L);
        int cap = get200("/rally/policy", nation.king).get("nation").get("maxMembers").asInt();

        JsonNode below = post200("/rally/nation", nation.king,
                request(cap - 1)).get("rally");
        assertThat(below.get("maxMembers").asInt())
                .as("上限之下（cap-1）服务端不改动发起人填的数")
                .isEqualTo(cap - 1);

        JsonNode above = post200("/rally/nation", nation.king,
                request(cap + 1)).get("rally");
        assertThat(above.get("maxMembers").asInt())
                .as("上限之上（cap+1）夹到 cap 而不是拒绝：越界拒绝会让玩家以为集结功能坏了")
                .isEqualTo(cap);
    }

    // ---------- 装配：国家那一档不能静默用联盟的 ----------

    @Test
    @DisplayName("国家集结按国家那一档装配：scope=NATION、groupId=国家 id，且不会被当成联盟层")
    void nationalRallyUsesTheNationalRulesNotTheAllianceOnes() throws Exception {
        Nation nation = nation(4);
        giveTroops(nation.king, 1_000L);

        JsonNode rally = post200("/rally/nation", nation.king,
                request(4)).get("rally");

        assertThat(rally.get("scope").asText()).isEqualTo("NATION");
        assertThat(rally.get("groupId").asText())
                .as("groupId 必须是国家 id：写成联盟 id 的话，requireMembership 与面板列表都会查错组织")
                .isEqualTo(nation.nationId);
        assertThat(rally.get("groupId").asText()).isNotEqualTo(nation.allianceId);
        assertThat(rally.get("initiatorId").asText()).isEqualTo(nation.king);
        assertThat(rally.get("joinedCount").asInt()).isEqualTo(1);
        assertThat(rally.get("status").asText()).isEqualTo("PREPARING");
    }

    // ---------- 加入 ----------

    @Test
    @DisplayName("本国成员能加入国家集结（requireMembership 的 NATION 支）")
    void memberCanJoinTheNationalRally() throws Exception {
        Nation nation = nation(4);
        giveTroops(nation.king, 1_000L);
        giveTroops(nation.mates.get(0), 800L);
        String rallyId = post200("/rally/nation", nation.king,
                request(4)).get("rally").get("rallyId").asText();
        long before = troopsOf(nation.mates.get(0));

        JsonNode after = post200("/rally/join", nation.mates.get(0),
                new RallyJoinReq(newRequestId(), rallyId,
                        List.of(new RallyTroop(UNIT, 200L)), List.of())).get("rally");

        assertThat(after.get("joinedCount").asInt())
                .as("加入成功：这一支原先写死 false，任何人都加不进去")
                .isEqualTo(2);
        assertThat(after.get("members").get(1).asText()).isEqualTo(nation.mates.get(0));
        assertThat(troopsOf(nation.mates.get(0)))
                .as("承诺即锁定：加入者的兵必须当场扣走")
                .isEqualTo(before - 200L);
    }

    @Test
    @DisplayName("别国的人加不进来：归属按国家 id 判，不是按联盟判")
    void outsiderFromAnotherNationCannotJoin() throws Exception {
        Nation nation = nation(4);
        giveTroops(nation.king, 1_000L);
        Nation other = nation(3);
        giveTroops(other.king, 1_000L);
        String rallyId = post200("/rally/nation", nation.king,
                request(4)).get("rally").get("rallyId").asText();
        long before = troopsOf(other.king);

        JsonNode root = postRaw("/rally/join", other.king,
                new RallyJoinReq(newRequestId(), rallyId,
                        List.of(new RallyTroop(UNIT, 200L)), List.of()));

        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.RALLY_NOT_FOUND.code());
        assertThat(root.get("detail").asText())
                .as("说清是「组织不对」，而不是让人去查集结还在不在")
                .contains("另一个组织");
        assertThat(troopsOf(other.king))
                .as("被拒时一个兵都不该动")
                .isEqualTo(before);
    }

    // ---------- 面板列表 ----------

    @Test
    @DisplayName("面板列表读得到国家集结（preparingRallies 的第三支）")
    void listShowsTheNationalRally() throws Exception {
        Nation nation = nation(4);
        giveTroops(nation.king, 1_000L);
        String rallyId = post200("/rally/nation", nation.king,
                request(4)).get("rally").get("rallyId").asText();

        JsonNode rallies = get200("/rally/list", nation.king).get("rallies");
        List<String> ids = new ArrayList<>();
        List<String> scopes = new ArrayList<>();
        rallies.forEach(node -> {
            ids.add(node.get("rallyId").asText());
            scopes.add(node.get("scope").asText());
        });

        assertThat(ids)
                .as("原先只有小队与联盟两支 ⇒ 症状是「发得出去、面板永远看不到」")
                .contains(rallyId);
        assertThat(scopes).contains("NATION");
    }

    // ---------- 权限位 ----------

    @Test
    @DisplayName("MEMBER 档被拒：msg 不含职位主张、缺的那一位放在 detail 里（读口同样灰键）")
    void memberTierCannotStartANationalRallyAndTheReasonIsReadable() throws Exception {
        Nation nation = nation(4);
        String mate = nation.mates.get(0);
        giveTroops(mate, 1_000L);

        JsonNode policy = get200("/rally/policy", mate).get("nation");
        assertThat(policy.get("canStart").asBoolean())
                .as("读口与写口看同一张 role_permission")
                .isFalse();
        assertThat(policy.get("reason").asText()).as("给玩家的是一句人话，不出现权限码与字段名")
                .doesNotContain("START_RALLY").doesNotContain("NATION");

        JsonNode root = postRaw("/rally/nation", mate, request(4));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.code());
        assertThat(root.get("msg").asText())
                .as("msg 是通用的那句：把「需要什么职位」写死在这里等于在代码里抄一份权限表")
                .isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.msg())
                .doesNotContain("国王").doesNotContain("官职");
        assertThat(root.get("detail").asText())
                .as("缺哪个权限位放进 detail，那里是查表得出的")
                .contains("START_RALLY");
    }

    @Test
    @DisplayName("OFFICER 档（大将军）可发起：权限位来自表而不是写死的职位清单")
    void officerTierCanStartANationalRally() throws Exception {
        Nation nation = nation(4);
        String general = nation.mates.get(0);
        giveTroops(general, 1_000L);
        post200("/nation/appoint", nation.king,
                new NationAppointReq(newRequestId(), general, NationOffice.GENERAL));

        assertThat(get200("/rally/policy", general).get("nation").get("canStart").asBoolean())
                .as("B13 §46 把「调动集结」给了大将军 ⇒ 表里 OFFICER 档开，读口就必须跟着开")
                .isTrue();

        JsonNode rally = post200("/rally/nation", general, request(4)).get("rally");
        assertThat(rally.get("scope").asText()).isEqualTo("NATION");
        assertThat(rally.get("initiatorId").asText()).isEqualTo(general);
        assertThat(rally.get("groupId").asText()).isEqualTo(nation.nationId);
    }

    // ---------- 夹具 ----------

    /**
     * 一次国家集结的发起请求。武将留空：本类测的是组织与人数上限，不是编成。
     *
     * <p><b>目标用野怪格而不是 {@code RallyEndpointTest} 那样打发起人自己的城</b>：
     * 国家集结打本国成员会被攻击闸门按「同一个国家的联盟之间不能互相攻击」（13009）挡掉，
     * 而那条规则本身是对的（你不能把自己的国民当成集结目标）。空地与野怪不构成 PVP、
     * 直接放行，所以这里要测的组织流程与"打谁"仍然无关。
     */
    private NationRallyReq request(int maxMembers) {
        return new NationRallyReq(newRequestId(), new SocialCoord(TARGET_X, TARGET_Y),
                SocialTargetType.MONSTER, maxMembers, 10,
                List.of(new RallyTroop(UNIT, 300L)), List.of());
    }

    /**
     * 造一个已建国的联盟：国王 + (memberCount-1) 名普通国民。
     *
     * <p>人数是这一族用例的自变量 —— 上限是折叠出来的，"3 个人的国"与"5 个人的国"
     * 必须能造得出来，用例才不必写死数字。
     */
    private Nation nation(int memberCount) throws Exception {
        String king = newPlayer(16);
        List<String> mates = new ArrayList<>();
        seq++;
        String allianceId = post200("/alliance/create", king,
                new AllianceCreateReq(newRequestId(), "国家集结盟" + seq,
                        String.format("R%03d", seq % 1000)))
                .get("alliance").get("id").asText();
        for (int i = 1; i < memberCount; i++) {
            String mate = newPlayer(16);
            post200("/alliance/apply", mate, new AllianceIdReq(newRequestId(), allianceId));
            post200("/alliance/review", king, new AllianceReviewReq(newRequestId(), mate, true));
            mates.add(mate);
        }
        String nationId = post200("/nation/found", king,
                new NationFoundReq(newRequestId(), "集结国" + seq, 100L, 200L))
                .get("nation").get("nationId").asText();
        return new Nation(king, mates, allianceId, nationId);
    }

    private record Nation(String king, List<String> mates, String allianceId, String nationId) {
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
        // 给兵之后必须重算战力：圈层校验读的是存档里的匹配战力，只改军队不刷新存档
        // 会让"打自己的城"被判成实力悬殊而拒 —— 那是夹具漏了一步，不是产品逻辑
        powerRefreshService.refresh(playerId);
    }

    /** 建国要主城 16 级、建盟要金币，两样都在这一处备好；新手保护期也要先解掉。 */
    private String newPlayer(int cityLevel) {
        String playerId = playerInitService.init(new PlayerInitReq(
                newRequestId(), "dev-" + UUID.randomUUID(), "国家集结测试", 1_700_000_000_000L, ""))
                .playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(cityLevel);
        PlayerResourceState gold = save.resources().get("GOLD");
        if (gold != null) {
            save.putResource("GOLD", new PlayerResourceState(
                    100_000L, gold.cap(), gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        }
        save.setProtectUntil(null);
        players.save(save);
        return playerId;
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private JsonNode post200(String url, String playerId, Object req) throws Exception {
        return okData(postRaw(url, playerId, req));
    }

    private JsonNode postRaw(String url, String playerId, Object req) throws Exception {
        return perform(post(url).header(PLAYER_HEADER, playerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(req)));
    }

    private JsonNode get200(String url, String playerId) throws Exception {
        return okData(perform(get(url).header(PLAYER_HEADER, playerId)));
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
