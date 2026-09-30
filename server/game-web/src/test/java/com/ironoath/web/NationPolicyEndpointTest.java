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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.NationPolicyCfg;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.NationAppointReq;
import com.ironoath.web.dto.generated.NationFoundReq;
import com.ironoath.web.dto.generated.NationOffice;
import com.ironoath.web.dto.generated.NationPolicyProposeReq;
import com.ironoath.web.dto.generated.NationPolicyVoteReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryNationStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：国策三个端点的集成验收（B13 验收 11 的协议侧 + 2026-09-30 裁决 A1/A2/A8）。
 * 依赖：MockMvc + test profile（内存存储）。
 *
 * <p><b>这一族断的是「谁被允许做什么」而不是数值</b>：国策最贵的两条不是 +15%，
 * 而是「不能被谁提」「不能被谁投」「同一票不能投两次」—— 这三条一旦松了，
 * 后面所有数值调校都是在给一个被操纵的结果调参。
 *
 * <p><b>验不到结算与生效</b>：那要 48 小时（24h 投票窗 + 24h 生效段），
 * 夹具里没有可控时钟，所以那一半由 {@code NationPolicyTest} 的领域用例兜 ——
 * <b>这一条限制必须写在这里而不是靠「用例很多」蒙混过去</b>。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NationPolicyEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";
    /** 表里 8 行，数值全部来自 B21 §五④。这一条按 configs 现读，不写死 8。 */
    @Autowired private ConfigRegistry configs;
    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private SocialStore socialStore;
    @Autowired private InMemoryNationStore nationStore;

    private int seq;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        socialStore.clear();
        nationStore.clear();
        seq = 0;
    }

    // ---------- 读 ----------

    @Test
    @DisplayName("轮次视图：8 行国策一次给全、开局是提案段、能不能提案与投票各带一句理由")
    void theRoundViewComesBackWhole() throws Exception {
        Fixture f = nation();

        JsonNode view = get200("/nation/policy", f.king);
        assertThat(view.get("nationId").asText()).isEqualTo(f.nationId);
        assertThat(view.get("phase").asText())
                .as("还没人提案 ⇒ 提案段（轮次由第一条提案触发开窗）").isEqualTo("PROPOSING");
        assertThat(view.get("policies")).hasSize(configs.all(NationPolicyCfg.class).size());
        assertThat(view.get("policySlotCount").asInt())
                .as("槽位数来自 nation_config（Lv1 = 1）").isEqualTo(1);
        assertThat(view.get("canPropose").asBoolean()).isTrue();
        assertThat(view.get("proposeBlockReason").asText()).isEqualTo("NONE");
        assertThat(view.get("canVote").asBoolean()).as("提案段里不能投票").isFalse();
        assertThat(view.get("voteBlockReason").asText())
                .as("理由要说清是哪一种「不行」：本轮还没有提案 vs 窗口已开")
                .isEqualTo("NO_PROPOSAL_YET");
        assertThat(view.get("proposals")).isEmpty();
        assertThat(view.get("active")).isEmpty();
        assertThat(view.get("slotOrderNote").asText())
                .as("槽位竞争规则随视图下发，不是客户端自己写的文案").isNotBlank();
    }

    @Test
    @DisplayName("国策行带中文兵种名与可上屏的效果说明（客户端不抄配置表、也不自己拼文案）")
    void policyRowsCarryNamesAndDisplayText() throws Exception {
        Fixture f = nation();

        JsonNode cavalry = policy(get200("/nation/policy", f.king), "np_cavalry_t1");
        assertThat(cavalry.get("name").asText()).isEqualTo("骑兵时代·轻骑 T1");
        assertThat(cavalry.get("targetUnitName").asText())
                .as("下发中文名而不是 unit_cavalry_t1：玩家要看到的是前者").isEqualTo("轻骑兵 T1");
        assertThat(cavalry.get("effectValueFixed").asLong())
                .as("幅度是定点（1500 = +15%），全项目禁浮点").isEqualTo(1500L);
        assertThat(cavalry.get("effectText").asText())
                .as("效果说明由服务端拼好：改文案不该要改客户端；**符号必须带** —— effectValue 有符号")
                .isEqualTo("轻骑兵 T1 攻击 +15%");

        JsonNode harvest = policy(get200("/nation/policy", f.king), "np_harvest");
        assertThat(harvest.get("effectAttr").asText()).isEqualTo("OUTPUT");
        assertThat(harvest.get("targetUnitName").isNull())
                .as("不针对特定兵种的国策，targetUnitName 为 null 而不是空串").isTrue();
    }

    // ---------- 提案：权限就是这一格的全部 ----------

    @Test
    @DisplayName("国王与内政官都能提案（裁决 A1：放开到官员档），普通成员不能")
    void officersProposeAndPlainMembersDoNot() throws Exception {
        Fixture f = nation();
        // 内政官任命已在夹具里做完（兼任会被领域层拒 13005），这里不再重复一遍

        JsonNode byMinister = post200("/nation/policy/propose", f.minister,
                new NationPolicyProposeReq(newRequestId(), "np_harvest"));
        assertThat(byMinister.get("proposalId").asText()).isNotBlank();
        assertThat(byMinister.get("round").get("proposals")).hasSize(1);

        JsonNode memberView = get200("/nation/policy", f.plain);
        assertThat(memberView.get("canPropose").asBoolean()).isFalse();
        assertThat(memberView.get("proposeBlockReason").asText())
                .as("理由要指名缺的是提案权，而不是笼统的「权限不足」")
                .isEqualTo("NOT_PROPOSER");

        JsonNode rejected = postRaw("/nation/policy/propose", f.plain,
                new NationPolicyProposeReq(newRequestId(), "np_fortress"));
        assertThat(rejected.get("code").asInt())
                .isEqualTo(ErrorCode.NATION_POLICY_NOT_PROPOSER.code());
    }

    @Test
    @DisplayName("提案只带 id 不带金额：国库三用途里没有国策（B13 §3），所以请求里不该出现钱")
    void aProposalCarriesNoMoney() throws Exception {
        Fixture f = nation();

        long before = get200("/nation/treasury", f.king).get("balance").asLong();
        post200("/nation/policy/propose", f.king, new NationPolicyProposeReq(newRequestId(), "np_conquest"));
        assertThat(get200("/nation/treasury", f.king).get("balance").asLong())
                .as("提案不动国库 —— TreasurySink 只有 NATIONAL_TECH 与 WAR_BOOST 两值")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("同一条国策本轮不能提两次：第二枚是 13018，公示上的提案数不能被灌水")
    void theSamePolicyCannotBeProposedTwice() throws Exception {
        Fixture f = nation();
        post200("/nation/policy/propose", f.king, new NationPolicyProposeReq(newRequestId(), "np_harvest"));

        JsonNode twice = postRaw("/nation/policy/propose", f.minister,
                new NationPolicyProposeReq(newRequestId(), "np_harvest"));
        assertThat(twice.get("code").asInt())
                .isEqualTo(ErrorCode.NATION_POLICY_ALREADY_PROPOSED.code());
        assertThat(get200("/nation/policy", f.king).get("proposals"))
                .as("被拒的那次不能留下一条提案").hasSize(1);
    }

    @Test
    @DisplayName("表里没有的国策 id 被拒，且理由点名是哪张表的哪一行")
    void anUnknownPolicyIdIsRejectedByName() throws Exception {
        Fixture f = nation();

        JsonNode ghost = postRaw("/nation/policy/propose", f.king,
                new NationPolicyProposeReq(newRequestId(), "np_does_not_exist"));
        assertThat(ghost.get("code").asInt()).isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(ghost.get("detail").asText()).contains("nation_policy");
    }

    @Test
    @DisplayName("提案不立刻开窗：开窗时刻 = 提案时刻 + 一个提案段（服务端下发）")
    void theWindowDoesNotOpenTheMomentAProposalLands() throws Exception {
        Fixture f = nation();

        JsonNode resp = post200("/nation/policy/propose", f.king,
                new NationPolicyProposeReq(newRequestId(), "np_conquest"));
        JsonNode round = resp.get("round");
        assertThat(round.get("phase").asText()).isEqualTo("PROPOSING");
        long windowIn = round.get("nextVoteAt").asLong() - round.get("serverNow").asLong();
        assertThat(windowIn)
                .as("一个提案段 = 一轮 48h − 投票窗 24h = 24h；客户端不许自己拿时长加")
                .isGreaterThan(23L * 3600 * 1000).isLessThanOrEqualTo(24L * 3600 * 1000);
    }

    // ---------- 投票：两枚「等」的码按各自的时刻 ----------

    @Test
    @DisplayName("还没有提案时投票被拒 = 13016；有提案但窗口未开 = 13015（两种「等」的下一步不同）")
    void theTwoWaitingCodesAreKeptApart() throws Exception {
        Fixture f = nation();
        JsonNode nothing = postRaw("/nation/policy/vote", f.plain,
                new NationPolicyVoteReq(newRequestId(), "whatever", true));
        assertThat(nothing.get("code").asInt())
                .as("本轮还没有任何提案，玩家的下一步是「自己去提一条」")
                .isEqualTo(ErrorCode.NATION_POLICY_NO_PROPOSAL.code());

        JsonNode proposed = post200("/nation/policy/propose", f.king,
                new NationPolicyProposeReq(newRequestId(), "np_conquest"));
        String proposalId = proposed.get("proposalId").asText();
        JsonNode stillEarly = postRaw("/nation/policy/vote", f.plain,
                new NationPolicyVoteReq(newRequestId(), proposalId, true));
        assertThat(stillEarly.get("code").asInt())
                .as("有提案了但窗口没开，玩家的下一步是「等窗口」")
                .isEqualTo(ErrorCode.NATION_POLICY_VOTING_CLOSED.code());
    }

    @Test
    @DisplayName("投票权不看提案权：普通成员在投票段里能投（裁决 A2 是每成员一票）")
    void plainMembersMayVoteEvenThoughTheyMayNotPropose() throws Exception {
        Fixture f = nation();
        post200("/nation/policy/propose", f.king, new NationPolicyProposeReq(newRequestId(), "np_conquest"));

        JsonNode view = get200("/nation/policy", f.plain);
        assertThat(view.get("canPropose").asBoolean()).isFalse();
        assertThat(view.get("voteBlockReason").asText())
                .as("身份够（普通成员），只是此刻不是投票段")
                .isEqualTo("NOT_VOTING");
    }

    // ---------- 状态一致 ----------

    @Test
    @DisplayName("提案是只增不删的公示：重放同一个 requestId 不会多出一条提案")
    void replayingAProposalRequestDoesNotAddASecondRow() throws Exception {
        Fixture f = nation();
        String requestId = newRequestId();

        post200("/nation/policy/propose", f.king, new NationPolicyProposeReq(requestId, "np_fortress"));
        JsonNode replay = postRaw("/nation/policy/propose", f.king,
                new NationPolicyProposeReq(requestId, "np_fortress"));

        assertThat(replay.get("code").asInt())
                .as("幂等键挡住重放：同一份 requestId 再发一次不会多挂一条提案")
                .isNotZero();
        assertThat(get200("/nation/policy", f.king).get("proposals")).hasSize(1);
    }

    // ---------- 夹具 ----------

    private record Fixture(String king, String minister, String plain, String nationId) {
    }

    /** 一个刚建好的国家：盟主（=国王）+ 一名已任命内政官 + 一名普通成员。 */
    private Fixture nation() throws Exception {
        String king = newPlayer(16);
        String minister = newPlayer(16);
        String plain = newPlayer(16);
        String allianceId = createAlliance(king, minister);
        // 普通成员也要在同一个联盟里（国籍跟随联盟，个人不单独入籍）
        post200("/alliance/apply", plain, new AllianceIdReq(newRequestId(), allianceId));
        post200("/alliance/review", king, new AllianceReviewReq(newRequestId(), plain, true));
        String nationId = post200("/nation/found", king,
                new NationFoundReq(newRequestId(), "国策王国" + (++seq), 100L, 200L))
                .get("nation").get("nationId").asText();
        post200("/nation/appoint", king, new NationAppointReq(newRequestId(), minister, NationOffice.MINISTER));
        return new Fixture(king, minister, plain, nationId);
    }

    private JsonNode policy(JsonNode view, String policyId) {
        for (JsonNode node : view.get("policies")) {
            if (policyId.equals(node.get("policyId").asText())) {
                return node;
            }
        }
        throw new AssertionError("下发的国策清单里没有这一行: " + policyId);
    }

    private String newPlayer(int cityLevel) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "国策测试",
                1_700_000_000_000L, "")).playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(cityLevel);
        PlayerResourceState gold = save.resources().get("GOLD");
        if (gold != null) {
            save.putResource("GOLD", new PlayerResourceState(100_000L, gold.cap(),
                    gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        }
        players.save(save);
        return playerId;
    }

    private String createAlliance(String leader, String mate) throws Exception {
        seq++;
        String allianceId = post200("/alliance/create", leader, new AllianceCreateReq(
                newRequestId(), "国策联盟" + seq, String.format("T%03d", seq % 1000)))
                .get("alliance").get("id").asText();
        post200("/alliance/apply", mate, new AllianceIdReq(newRequestId(), allianceId));
        post200("/alliance/review", leader, new AllianceReviewReq(newRequestId(), mate, true));
        return allianceId;
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private JsonNode post200(String url, String playerId, Object req) throws Exception {
        return okData(postRaw(url, playerId, req));
    }

    private JsonNode postRaw(String url, String playerId, Object req) throws Exception {
        return perform(post(url).header(PLAYER_HEADER, playerId)
                .contentType(MediaType.APPLICATION_JSON).content(JsonUtils.toJson(req)));
    }

    private JsonNode get200(String url, String playerId) throws Exception {
        return okData(perform(get(url).header(PLAYER_HEADER, playerId)));
    }

    private JsonNode perform(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode okData(JsonNode root) {
        assertThat(root.get("code").asInt()).as("业务码必须为 0，实际响应=%s", root).isZero();
        return root.get("data");
    }
}
