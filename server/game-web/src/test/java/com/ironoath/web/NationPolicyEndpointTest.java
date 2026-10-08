package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.time.TimeService;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.NationPolicyCfg;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.web.bot.BotRegistry;
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
 * <p><b>投票段与生效段在这里也验了</b>：用 {@code @TestConfiguration} 把 {@code TimeService}
 * 换成可拨的（投票窗 24 小时，真链路不可能等）。这不是后门端点也不跳过任何门禁 ——
 * {@code TimeService} 本来就是 {@link java.util.function.LongSupplier} 注入的
 * （铁律 5 / C00 公理四·五：只有它读系统时钟），拨时间就是让同一套
 * {@code voteBlock} / {@code settlePolicy} 在更晚的时刻上跑一遍。
 * <b>为什么要覆盖 bean 而不是加一个改时间的接口</b>：仓库明写「不许有跳门槛的端点」，
 * 一个能拨钟的 dev 端点会被当成跳门槛的通道；覆盖 bean 只存在于测试作用域。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(NationPolicyEndpointTest.MovableClock.class)
class NationPolicyEndpointTest {

    /**
     * 可拨的时钟（投票窗 24 小时，真链路不可能等）。
     *
     * <p>拨时间 = 让同一套 {@code voteBlock} / {@code settlePolicy} 在更晚的时刻上跑一遍，
     * **不跳过任何门禁**；覆盖 bean 而不是加一个改时间的接口，是因为仓库明写
     * 「不许有跳门槛的端点」，而覆盖只存在于测试作用域。
     */
    @TestConfiguration
    static class MovableClock {
        static final long T0 = 1_900_000_000_000L;
        static final AtomicLong NOW = new AtomicLong(T0);
        static final long HOUR = 3_600_000L;

        @Bean
        @Primary
        TimeService testTimeService() {
            return new TimeService(NOW::get, 10L * 365 * 24 * 3600 * 1000);
        }
    }

    private static final String PLAYER_HEADER = "X-Player-Id";
    /** 表里 8 行，数值全部来自 B21 §五④。这一条按 configs 现读，不写死 8。 */
    @Autowired private ConfigRegistry configs;
    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private SocialStore socialStore;
    @Autowired private InMemoryNationStore nationStore;
    @Autowired private BotRegistry botRegistry;
    @Autowired private com.ironoath.web.nation.NationPolicyBonuses policyBonuses;

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
    @DisplayName("同一毫秒内的两条提案不能是同一个 id：领域层按 id 往 Map 里 put，撞号就是把第一条挤掉（台账 #818）")
    void proposalIdsDoNotCollideWithinTheSameMillisecond() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            assertThat(ids.add(com.ironoath.web.service.NationAppService
                    .proposalIdOf("p_same", 1700000000000L)))
                    .as("第 " + (i + 1) + " 次生成的提案 id 与前面重复 —— 只带毫秒的 id 挡不住同一毫秒内的两次提案")
                    .isTrue();
        }
        assertThat(ids).hasSize(50);
    }

    @Test
    @DisplayName("同一个提案人连提两条不同的国策，公示上两条都要在（#818：原先第二条 put 掉第一条）")
    void twoDifferentPoliciesFromTheSameProposerBothStayOnTheBoard() throws Exception {
        Fixture f = nation();
        JsonNode first = post200("/nation/policy/propose", f.minister,
                new NationPolicyProposeReq(newRequestId(), "np_harvest"));
        JsonNode second = post200("/nation/policy/propose", f.minister,
                new NationPolicyProposeReq(newRequestId(), "np_fortress"));

        assertThat(second.get("proposalId").asText())
                .as("两次请求必须各自拿到一个 id").isNotEqualTo(first.get("proposalId").asText());
        assertThat(get200("/nation/policy", f.king).get("proposals"))
                .as("公示上两条都在 —— id 唯一是这条的前提而不是结论")
                .hasSize(2);
    }

    @Test
    @DisplayName("13018 的回执里不出现内部 id：红线「不把内部 id 印给玩家」，而静态黑话门看不见插值里那一个（台账 #820）")
    void theRejectionMessageDoesNotPrintTheProposalId() throws Exception {
        Fixture f = nation();
        String existing = post200("/nation/policy/propose", f.king,
                new NationPolicyProposeReq(newRequestId(), "np_harvest"))
                .get("proposalId").asText();

        JsonNode twice = postRaw("/nation/policy/propose", f.minister,
                new NationPolicyProposeReq(newRequestId(), "np_harvest"));
        assertThat(twice.get("code").asInt())
                .isEqualTo(ErrorCode.NATION_POLICY_ALREADY_PROPOSED.code());
        assertThat(twice.toString())
                .as("整份回执都不许带上那条提案的内部 id（旧文案是「本轮已经提过这一条国策（提案 np_xxx_毫秒）」）")
                .doesNotContain(existing).doesNotContain("np_");
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

    // ---------- 投票段（拨钟 25 小时后进去） ----------

    @Test
    @DisplayName("投票段：开票后能投、票数与两份名单一次给全、同一票不能投第二次")
    void votingWindowTalliesAndRefusesADoubleVote() throws Exception {
        Fixture f = nation();
        JsonNode proposed = post200("/nation/policy/propose", f.king,
                new NationPolicyProposeReq(newRequestId(), "np_harvest"));
        String proposalId = proposed.get("proposalId").asText();

        // 开窗时刻 = 提案时刻 + 一个提案段（24h）。拨 25 小时 ⇒ 落在投票段里而不是边界上。
        MovableClock.NOW.addAndGet(25 * MovableClock.HOUR);

        JsonNode voting = get200("/nation/policy", f.king);
        assertThat(voting.get("phase").asText()).as("开窗之后是投票段").isEqualTo("VOTING");
        assertThat(voting.get("canVote").asBoolean()).isTrue();
        assertThat(voting.get("voteBlockReason").asText()).isEqualTo("NONE");
        long windowIn = voting.get("voteEndsAt").asLong() - voting.get("serverNow").asLong();
        assertThat(windowIn).as("投票窗还剩不到 24 小时（拨了 25 小时进去）")
                .isPositive().isLessThanOrEqualTo(24 * MovableClock.HOUR);

        JsonNode yes = post200("/nation/policy/vote", f.plain,
                new NationPolicyVoteReq(newRequestId(), proposalId, true));
        assertThat(yes.get("round").get("phase").asText()).isEqualTo("VOTING");

        JsonNode row = policyProposal(get200("/nation/policy", f.king), proposalId);
        assertThat(row.get("yes").asLong()).as("一票赞成").isEqualTo(1L);
        assertThat(row.get("no").asLong()).isZero();
        assertThat(row.get("supporters")).as("赞成名单一次给全").hasSize(1);
        assertThat(row.get("supporters").get(0).get("name").asText())
                .as("名单里是玩家名而不是 playerId（公示要能被看懂）").isNotBlank();
        assertThat(row.get("supporters").get(0).get("playerId").asText())
                .as("id 也在，但只用于服务端核对").isEqualTo(f.plain);
        assertThat(row.get("opponents")).as("反对名单是空数组而不是 null").isEmpty();

        JsonNode mine = get200("/nation/policy", f.plain).get("myVotes");
        assertThat(mine).as("我这一票要能认出来（客户端据此把键灰掉）").hasSize(1);
        assertThat(mine.get(0).get("support").asBoolean()).isTrue();

        JsonNode twice = postRaw("/nation/policy/vote", f.plain,
                new NationPolicyVoteReq(newRequestId(), proposalId, false));
        assertThat(twice.get("code").asInt())
                .as("同一票不能投第二次：否则票数与名单立刻对不上")
                .isEqualTo(ErrorCode.NATION_POLICY_ALREADY_VOTED.code());
    }

    @Test
    @DisplayName("投票段：Bot 不能投票 —— 门禁在服务端，客户端灰键只是提示")
    void botsMayNotVote() throws Exception {
        Fixture f = nation();
        post200("/nation/policy/propose", f.king,
                new NationPolicyProposeReq(newRequestId(), "np_conquest"));
        MovableClock.NOW.addAndGet(25 * MovableClock.HOUR);

        // **必须先把那个 id 注册进 BotRegistry**：闸门是 `if (!isBot) return true;`，
        // 而 `isBot` 只认注册过的 id。第一版直接问一个没注册过的 "bot-1"，
        // 于是 isBot=false、闸门放行 —— 断言红的是夹具，不是实现。
        // 这条钉的是「注册过的 Bot 一定被挡住」，对照组是同一个注册表放行真人。
        String botId = "bot-policy-" + seq;
        registerBot(botId);

        assertThat(botRegistry.mayTakeNationalPolicyAction(botId))
                .as("Bot 不得参与国策（裁决 A8）").isFalse();
        assertThat(botRegistry.mayTakeNationalPolicyAction(f.plain))
                .as("同一个注册表放行真人 —— 上面那条不是「一律 false」").isTrue();
        assertThat(botRegistry.profileOf(botId))
                .as("对照组的前置：那个 id 确实注册上了").isNotNull();
    }

    /** 注册一个 Bot 画像。构造参数照 `BotEventChatTest` 的同一份（数值对这一条无意义）。 */
    private void registerBot(String botId) {
        java.util.List<Integer> hours = new java.util.ArrayList<>();
        for (int h = 0; h < 24; h++) {
            hours.add(h);
        }
        botRegistry.register(new BotProfile(botId, "bot_linju",
                new BotProfile.AiProfile(FixedPoint.parse("0.30"), FixedPoint.parse("0.50"),
                        FixedPoint.parse("1.00"), FixedPoint.parse("1.0")),
                new BotProfile.Persona(42L, 7L, 99L, hours, 3L, 30L, FixedPoint.parse("0.10")),
                FixedPoint.parse("1.0")));
    }

    // ---------- 生效段（再拨 25 小时） ----------

    @Test
    @DisplayName("生效段：过窗即结算，胜出的那条进生效列表且真被战斗装配读到")
    void afterTheWindowTheWinnerBecomesActiveAndReachesTheBattleAssembly() throws Exception {
        Fixture f = nation();
        JsonNode proposed = post200("/nation/policy/propose", f.king,
                new NationPolicyProposeReq(newRequestId(), "np_harvest"));
        String proposalId = proposed.get("proposalId").asText();

        MovableClock.NOW.addAndGet(25 * MovableClock.HOUR);
        post200("/nation/policy/vote", f.plain,
                new NationPolicyVoteReq(newRequestId(), proposalId, true));

        // 再拨一整个投票窗：窗口关闭，惰性结算应当发生
        MovableClock.NOW.addAndGet(25 * MovableClock.HOUR);

        JsonNode active = get200("/nation/policy", f.king);
        assertThat(active.get("phase").asText()).as("过了投票窗且已结算，进入生效段").isEqualTo("ACTIVE");
        JsonNode activeList = active.get("active");
        assertThat(activeList).as("过窗即生效").hasSize(1);
        assertThat(activeList.get(0).get("policy").get("policyId").asText()).isEqualTo("np_harvest");
        assertThat(activeList.get(0).get("policy").get("name").asText()).isNotBlank();
        assertThat(activeList.get(0).get("activeUntil").asLong())
                .as("生效段是有限的一段（到期才需要下一轮）")
                .isGreaterThan(active.get("serverNow").asLong());
        assertThat(active.get("proposals")).as("本轮提案公示清空，不留在屏上冒充生效中的").isEmpty();
        assertThat(active.get("nextVoteAt").asLong())
                .as("下一轮开票时刻由最早到期那一刻推出来，不是固定加 24h")
                .isGreaterThanOrEqualTo(active.get("serverNow").asLong());

        // **装配真的读到了它**：投票期那条 OUTPUT 此刻给 +10% 产出
        assertThat(policyBonuses.outputPercent(f.plain))
                .as("国策在生效段要真的落到成员身上（不落 = 这一格只是界面上的一句话）")
                .isEqualTo(1_000L);
    }

    @Test
    @DisplayName("生效段到期后自动开下一轮：提案段回来，旧的从生效列表里掉出去")
    void anExpiredPolicyOpensTheNextRound() throws Exception {
        Fixture f = nation();
        JsonNode proposed = post200("/nation/policy/propose", f.king,
                new NationPolicyProposeReq(newRequestId(), "np_harvest"));
        MovableClock.NOW.addAndGet(25 * MovableClock.HOUR);
        post200("/nation/policy/vote", f.plain,
                new NationPolicyVoteReq(newRequestId(), proposed.get("proposalId").asText(), true));
        MovableClock.NOW.addAndGet(25 * MovableClock.HOUR);
        assertThat(get200("/nation/policy", f.king).get("phase").asText()).isEqualTo("ACTIVE");

        // 再拨一个完整的生效段：到期 ⇒ 下一轮开窗
        MovableClock.NOW.addAndGet(25 * MovableClock.HOUR);
        JsonNode next = get200("/nation/policy", f.king);
        assertThat(next.get("phase").asText())
                .as("惰性自循环：到期就开下一轮，不需要任何定时器").isEqualTo("PROPOSING");
        assertThat(next.get("active")).as("旧的那条已到期，不再占生效位").isEmpty();
        assertThat(policyBonuses.outputPercent(f.plain))
                .as("到期即失效 —— 留着加成就是白送资源").isZero();
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


    private JsonNode policyProposal(JsonNode view, String proposalId) {
        for (JsonNode node : view.get("proposals")) {
            if (proposalId.equals(node.get("proposalId").asText())) {
                return node;
            }
        }
        throw new AssertionError("本轮提案里没有这一条: " + proposalId);
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
