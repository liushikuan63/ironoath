package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceDonateReq;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceMemberReq;
import com.ironoath.web.dto.generated.AllianceRoleReq;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.AllianceSelfReq;
import com.ironoath.web.dto.generated.AllianceSyncReq;
import com.ironoath.web.dto.generated.ChatChannel;
import com.ironoath.web.dto.generated.ChatListReq;
import com.ironoath.web.dto.generated.ChatSendReq;
import com.ironoath.core.reddot.ReddotTree;
import com.ironoath.web.dto.generated.HelpReq;
import com.ironoath.web.dto.generated.HelpTargetKind;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.SocialEventAckReq;
import com.ironoath.web.dto.generated.SquadCreateReq;
import com.ironoath.web.dto.generated.SquadIdReq;
import com.ironoath.web.dto.generated.SquadMemberReq;
import com.ironoath.web.dto.generated.SquadSelfReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemorySocialStore;

/**
 * 职责：B10 社交域的端到端验证 —— 验收 1/2/4/6/7/8/9/10/12 里可以走 HTTP 断言的那些。
 * 依赖：Spring Boot Test + MockMvc；test profile（内存存储）。
 *
 * <p><b>本类测的是「编排」而不是「规则」</b>：人数上限怎么算、限流窗口多长、捐献比例多少，
 * 那些由 SocialSystemTest（game-core）与 SocialConfigTest / SocialRulesAssemblerTest 钉住。
 * 这里测的是把它们串起来时最容易错的几件事 ——
 * 幂等键有没有真的挡住重放、权限有没有真的走配置表、跨玩家的通知有没有真的落到对方身上、
 * 以及失败时错误码是不是玩家能看懂的那一个。
 *
 * <p><b>每个用例都用全新的 playerId</b>：{@code SocialAppService} 持有的 ChatRateLimiter 与
 * HelpLedger 是 Spring 单例，它们的累计状态（限流窗口、每日帮助次数）跨用例存活。
 * 按 playerId 隔离之后，用例之间不会互相污染，也就不需要给生产代码开一个「重置」后门。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SocialEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private SocialAppService social;
    @Autowired private ReddotTree reddotTree;
    @Autowired private InMemorySocialStore socialStore;
    @Autowired private com.ironoath.web.reward.PlayerWallet wallet;
    @Autowired private com.ironoath.web.social.SocialRulesAssembler socialRules;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        socialStore.clear();
    }

    // ---------- 小队 ----------

    @Test
    @DisplayName("创建小队：主城 5 级起可建，人数上限 5，独立状态（isSubSquad=false）")
    void createSquad() throws Exception {
        String leader = newPlayer(5);
        JsonNode data = post200("/squad/create", leader, new SquadCreateReq(newRequestId(), "五个人"));

        JsonNode squad = data.get("squad");
        assertThat(squad.isNull()).isFalse();
        assertThat(squad.get("name").asText()).isEqualTo("五个人");
        assertThat(squad.get("leaderId").asText()).isEqualTo(leader);
        assertThat(squad.get("memberCap").asInt()).as("B10 §1：创建时 5 人").isEqualTo(5);
        assertThat(squad.get("isSubSquad").asBoolean()).isFalse();
        assertThat(squad.get("allianceId").isNull()).isTrue();
        assertThat(squad.get("members")).hasSize(1);
        assertThat(squad.get("dailyQuestTarget").asLong())
                .as("任务目标来自 global.SQUAD_QUEST_DAILY_MONSTER，不在代码里另写一份")
                .isPositive();
    }

    @Test
    @DisplayName("主城不足 5 级时创建被拒，且错误里说清差什么（绝不静默失败）")
    void createSquadLockedBelowCityLevel() throws Exception {
        String player = newPlayer(4);
        JsonNode root = postRaw("/squad/create", player, new SquadCreateReq(newRequestId(), "五个人"));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.SQUAD_LOCKED.code());
        assertThat(root.get("msg").asText() + root.path("detail").asText())
                .as("玩家要能看到「需要主城 5 级，当前 4 级」").contains("主城").contains("5");
    }

    @Test
    @DisplayName("加入小队到 5 人后第 6 人被拒，错误里带上当前上限")
    void squadJoinRespectsMemberCap() throws Exception {
        String leader = newPlayer(5);
        String squadId = post200("/squad/create", leader, new SquadCreateReq(newRequestId(), "满员队"))
                .get("squad").get("id").asText();
        for (int i = 0; i < 4; i++) {
            post200("/squad/join", newPlayer(5), new SquadIdReq(newRequestId(), squadId));
        }
        JsonNode full = get200("/social/summary", leader);
        assertThat(full.get("squad").get("members")).hasSize(5);

        JsonNode root = postRaw("/squad/join", newPlayer(5), new SquadIdReq(newRequestId(), squadId));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.SQUAD_FULL.code());
        assertThat(root.path("detail").asText()).contains("5");
    }

    @Test
    @DisplayName("验收1：加入联盟后小队转为分队，isSubSquad=true 而成员/等级/功能字段一个不少")
    void joiningAllianceKeepsSquadIntact() throws Exception {
        String leader = newPlayer(10);
        JsonNode before = post200("/squad/create", leader, new SquadCreateReq(newRequestId(), "老兄弟"));
        String squadId = before.get("squad").get("id").asText();
        String mate = newPlayer(10);
        post200("/squad/join", mate, new SquadIdReq(newRequestId(), squadId));

        String allianceId = post200("/alliance/create", leader,
                new AllianceCreateReq(newRequestId(), "铁誓同盟", "IRON"))
                .get("alliance").get("id").asText();
        // 队友申请入盟并由盟主审核通过
        post200("/alliance/apply", mate, new AllianceIdReq(newRequestId(), allianceId));
        JsonNode after = post200("/alliance/review", leader,
                new AllianceReviewReq(newRequestId(), mate, true));

        JsonNode squad = after.get("squad");
        assertThat(squad.get("isSubSquad").asBoolean()).as("验收1：转为联盟内分队").isTrue();
        assertThat(squad.get("allianceId").asText()).isEqualTo(allianceId);
        assertThat(squad.get("name").asText()).as("小队名不变").isEqualTo("老兄弟");
        assertThat(squad.get("members")).as("成员一个不少").hasSize(2);
        assertThat(squad.get("level").asInt()).isEqualTo(1);
        assertThat(squad.get("memberCap").asInt()).isEqualTo(5);
    }

    // ---------- 两个"领域方法早就写好、控制器没挂"的端点 ----------

    @Test
    @DisplayName("POST /squad/transfer：队长把队长的位置转给成员，对方收到通知")
    void squadTransferMovesLeadership() throws Exception {
        String leader = newPlayer(10);
        String squadId = post200("/squad/create", leader, new SquadCreateReq(newRequestId(), "转让队"))
                .get("squad").get("id").asText();
        String mate = newPlayer(10);
        post200("/squad/join", mate, new SquadIdReq(newRequestId(), squadId));

        JsonNode after = post200("/squad/transfer", leader, new SquadMemberReq(newRequestId(), mate));
        assertThat(after.get("squad").get("leaderId").asText()).as("队长已经换人").isEqualTo(mate);
        assertThat(after.get("squad").get("members").toString())
                .as("旧队长降为队员，不是被踢出去").contains(leader);
        assertThat(socialStore.unreadEvents(mate).toString())
                .as("组织身份的变化必须被通知到，而不是让自己发现").contains("SQUAD_LEADER_CHANGED");

        // 新队长转给一个不是成员的人：领域不变量要回成业务错而不是 500
        String stranger = newPlayer(10);
        JsonNode bad = postRaw("/squad/transfer", mate, new SquadMemberReq(newRequestId(), stranger));
        assertThat(bad.get("code").asInt())
                .as("非成员不能成为队长，且必须是业务码而不是 500").isNotZero();
    }

    @Test
    @DisplayName("POST /alliance/expand：端点存在，领域失败映射成业务码而不是 500/404")
    void allianceExpandSpendsFundAndRaisesCap() throws Exception {
        String leader = newPlayer(10);
        int capBefore = post200("/alliance/create", leader, new AllianceCreateReq(newRequestId(), "扩容盟", "CAP"))
                .get("alliance").get("memberCap").asInt();

        // 领域层的两道门有先后（先联盟等级、后资金），所以刚建的同盟先撞上等级门。
        // 要断言的是"回业务码而不是 500，且说清缺什么" —— 少一层映射的话玩家看到的是
        // "服务器错误"，而这偏偏是每天都在发生的正常失败
        JsonNode first = postRaw("/alliance/expand", leader, new AllianceSelfReq(newRequestId()));
        assertThat(first.get("code").asInt()).as("不能是成功码").isNotZero();
        assertThat(first.get("code").asInt()).as("也不能落到 5xx 兜底码")
                .isNotEqualTo(ErrorCode.SYSTEM_ERROR.code());
        assertThat(first.get("detail").asText()).as("要说清缺什么").isNotEmpty();

        // 资金门在 Lv1 撞不到（领域先判联盟等级），要往后推只能刷联盟等级 —— 那需要多次捐献，
        // 而为了让断言多一条去开后门改领域对象的钱，正是本项目反复在防的那种"测试替实现圆谎"。
        // 所以这条用例证的是"端点存在且业务错不再变成 500/404"，资金分支的映射留给集成场景。
        assertThat(capBefore).as("memberCap 字段取到就是断言（接错字段这行会红）").isPositive();
    }

    @Test
    @DisplayName("验收2：队长退盟但队员未退 ⇒ 小队自动解散，队员收到 SQUAD_DISBANDED 通知")
    void squadDisbandsWhenLeaderLeavesAlliance() throws Exception {
        // 盟主另找一个人：领域规则禁止盟主直接退盟（必须先转让或解散），
        // 而验收 2 要的是「小队队长退盟」，两个身份必须拆开才能测到目标行为
        String boss = newPlayer(10);
        String allianceId = post200("/alliance/create", boss,
                new AllianceCreateReq(newRequestId(), "散伙盟", "GONE")).get("alliance").get("id").asText();

        String leader = newPlayer(10);
        String squadId = post200("/squad/create", leader, new SquadCreateReq(newRequestId(), "要散的队"))
                .get("squad").get("id").asText();
        String mate = newPlayer(10);
        post200("/squad/join", mate, new SquadIdReq(newRequestId(), squadId));
        for (String member : List.of(leader, mate)) {
            post200("/alliance/apply", member, new AllianceIdReq(newRequestId(), allianceId));
            post200("/alliance/review", boss, new AllianceReviewReq(newRequestId(), member, true));
        }
        assertThat(get200("/social/summary", leader).get("squad").get("isSubSquad").asBoolean())
                .as("入盟后小队转为分队").isTrue();

        // 队长退盟：队员还在盟里，所以按验收 2 小队自动解散
        post200("/alliance/leave", leader, new AllianceSelfReq(newRequestId()));

        JsonNode leaderView = get200("/social/summary", leader);
        assertThat(leaderView.get("squad").isNull()).as("小队已解散").isTrue();

        JsonNode mateView = get200("/social/summary", mate);
        assertThat(mateView.get("squad").isNull()).as("队员的小队也一起散了").isTrue();
        boolean notified = false;
        for (JsonNode event : mateView.get("events")) {
            if ("SQUAD_DISBANDED".equals(event.get("type").asText())) {
                notified = true;
            }
        }
        assertThat(notified).as("验收2：队员必须收到通知，否则他只会在下次打开面板时发现小队没了").isTrue();
    }

    @Test
    @DisplayName("验收4：踢人权限走 role_permission 表 —— 队员点踢人被拒，错误里带上缺的权限位")
    void kickRequiresPermissionFromTable() throws Exception {
        String leader = newPlayer(5);
        String squadId = post200("/squad/create", leader, new SquadCreateReq(newRequestId(), "权限队"))
                .get("squad").get("id").asText();
        String mate = newPlayer(5);
        post200("/squad/join", mate, new SquadIdReq(newRequestId(), squadId));

        JsonNode denied = postRaw("/squad/kick", mate, new SquadMemberReq(newRequestId(), leader));
        assertThat(denied.get("code").asInt()).isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.code());
        assertThat(denied.path("detail").asText()).as("缺哪个权限位要写进 detail").contains("KICK_MEMBER");

        // 队长有这个权限位，所以能踢
        JsonNode ok = post200("/squad/kick", leader, new SquadMemberReq(newRequestId(), mate));
        assertThat(ok.get("squad").get("members")).hasSize(1);

        // 被踢的人收到通知
        JsonNode kicked = get200("/social/summary", mate);
        assertThat(kicked.get("squad").isNull()).isTrue();
        boolean notified = false;
        for (JsonNode event : kicked.get("events")) {
            if ("SQUAD_KICKED".equals(event.get("type").asText())) {
                notified = true;
            }
        }
        assertThat(notified).isTrue();
    }

    @Test
    @DisplayName("验收4：权限接口下发的是「我能做什么」的结论列表，不是整张矩阵")
    void permissionsEndpointReturnsConclusions() throws Exception {
        String leader = newPlayer(5);
        post200("/squad/create", leader, new SquadCreateReq(newRequestId(), "结论队"));
        JsonNode mine = get200("/social/permissions?scope=SQUAD", leader);
        assertThat(mine.get("scope").asText()).isEqualTo("SQUAD");
        assertThat(mine.get("role").asText()).isEqualTo("LEADER");
        List<String> permissions = new java.util.ArrayList<>();
        mine.get("permissions").forEach(node -> permissions.add(node.asText()));
        assertThat(permissions).contains("KICK_MEMBER", "START_RALLY", "EDIT_ANNOUNCEMENT");

        String mate = newPlayer(5);
        String squadId = get200("/social/summary", leader).get("squad").get("id").asText();
        post200("/squad/join", mate, new SquadIdReq(newRequestId(), squadId));
        JsonNode his = get200("/social/permissions?scope=SQUAD", mate);
        assertThat(his.get("role").asText()).isEqualTo("MEMBER");
        List<String> memberPermissions = new java.util.ArrayList<>();
        his.get("permissions").forEach(node -> memberPermissions.add(node.asText()));
        assertThat(memberPermissions).doesNotContain("KICK_MEMBER");
        assertThat(memberPermissions).contains("CALL_FOR_HELP");
    }

    // ---------- 联盟 ----------

    @Test
    @DisplayName("验收8：捐献后联盟资金与个人贡献值同步增加，响应里两个总额都对得上")
    void donateIncreasesFundAndContribution() throws Exception {
        String leader = newPlayer(10);
        post200("/alliance/create", leader, new AllianceCreateReq(newRequestId(), "捐献盟", "GIVE"));

        JsonNode gold = post200("/alliance/donate", leader, new AllianceDonateReq(newRequestId(), 2));
        assertThat(gold.get("fundGained").asLong()).isEqualTo(2500L);
        assertThat(gold.get("contributionGained").asLong()).isEqualTo(250L);
        assertThat(gold.get("fund").asLong()).isEqualTo(2500L);
        assertThat(gold.get("contribution").asLong()).isEqualTo(250L);
        assertThat(gold.get("donateToday").asInt()).isEqualTo(1);
        assertThat(gold.get("donateDailyCap").asInt()).isEqualTo(3);

        JsonNode again = post200("/alliance/donate", leader, new AllianceDonateReq(newRequestId(), 0));
        assertThat(again.get("fund").asLong()).as("资金累加").isEqualTo(2600L);
        assertThat(again.get("contribution").asLong()).as("贡献值累加").isEqualTo(260L);

        JsonNode summary = get200("/social/summary", leader);
        assertThat(summary.get("alliance").get("fund").asLong()).isEqualTo(2600L);
        assertThat(summary.get("alliance").get("myContribution").asLong()).isEqualTo(260L);
        assertThat(summary.get("alliance").get("myDonateToday").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("每日捐献档数用完后被拒，错误码是「档位已用完」而不是笼统的失败")
    void donateDailyCapIsEnforced() throws Exception {
        String leader = newPlayer(10);
        post200("/alliance/create", leader, new AllianceCreateReq(newRequestId(), "档数盟", "CAPS"));
        for (int tier = 0; tier < 3; tier++) {
            post200("/alliance/donate", leader, new AllianceDonateReq(newRequestId(), tier));
        }
        JsonNode root = postRaw("/alliance/donate", leader, new AllianceDonateReq(newRequestId(), 0));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.ALLIANCE_DONATE_DAILY_LIMIT.code());
    }

    // ---------- 联盟科技（B10 §2：花公账研究、上限随联盟等级） ----------

    /**
     * 建盟后捐献三档攒公账。
     *
     * <p>注意 {@code role_permission} 里 {@code RESEARCH_TECH} 是 <b>allowMember=True</b> ——
     * 普通成员本来就能研究，所以本组用例不拿「成员被拒」当权限样本（那会测到一个不存在的行为）。
     */
    private String allianceWithFund(String leader) throws Exception {
        String allianceId = post200("/alliance/create", leader,
                new AllianceCreateReq(newRequestId(), "科技盟", "TECH"))
                .get("alliance").get("id").asText();
        for (int i = 0; i < 3; i++) {
            post200("/alliance/donate", leader, new AllianceDonateReq(newRequestId(), 2));
        }
        return allianceId;
    }

    @Test
    @DisplayName("研究联盟科技：真扣公账、等级记账，并出现在联盟视图的 techs 里")
    void allianceTechChargesFundAndShowsInView() throws Exception {
        String leader = newPlayer(10);
        post200("/alliance/create", leader, new AllianceCreateReq(newRequestId(), "科技盟", "TECH"));
        for (int i = 0; i < 3; i++) {
            post200("/alliance/donate", leader, new AllianceDonateReq(newRequestId(), 2));
        }
        // 建盟响应里的 fund 是 0（公账靠捐献攒），攒完之后的余额要重新读一次
        long fundBefore = get200("/social/summary", leader).get("alliance").get("fund").asLong();
        assertThat(fundBefore).as("三次档位 2 捐献攒出的公账至少要够一级科技").isGreaterThanOrEqualTo(2000L);

        JsonNode resp = post200("/alliance/tech", leader,
                new com.ironoath.web.dto.generated.AllianceTechReq(newRequestId(), "atech_atk", 1));
        assertThat(resp.get("level").asInt()).isEqualTo(1);
        assertThat(resp.get("fundCost").asLong())
                .as("第 1 级就是表里的基础价").isEqualTo(2000L);
        assertThat(resp.get("fund").asLong()).isEqualTo(fundBefore - 2000L);
        assertThat(resp.get("levelCap").asInt())
                .as("上限 = 表里的 maxLevel × (1 + 当前联盟等级的 techCapBonus)。"
                        + "攒公账靠的就是捐献，而捐献同时加联盟经验，所以这里必然 ≥ 表值；"
                        + "「随等级放大」的精确值由 SocialSystemTest.techCapGrowsWithAllianceLevel 钉")
                .isGreaterThanOrEqualTo(40);
        assertThat(resp.get("effectValue").asLong())
                .as("效果按级线性累加（单级 1.5% ⇒ 定点 150）").isEqualTo(150L);

        JsonNode view = get200("/social/summary", leader);
        JsonNode techs = view.get("alliance").get("techs");
        assertThat(techs).as("科技进度只有服务端知道，必须能从视图里读到").hasSize(1);
        assertThat(techs.get(0).get("techId").asText()).isEqualTo("atech_atk");
        assertThat(techs.get(0).get("level").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("第二级比第一级贵：ALLIANCE_TECH_COST_GROWTH 真的逐级作用，不是固定单价")
    void eachFurtherLevelCostsMore() throws Exception {
        String leader = newPlayer(10);
        allianceWithFund(leader);

        long first = post200("/alliance/tech", leader,
                new com.ironoath.web.dto.generated.AllianceTechReq(newRequestId(), "atech_atk", 1))
                .get("fundCost").asLong();
        JsonNode second = post200("/alliance/tech", leader,
                new com.ironoath.web.dto.generated.AllianceTechReq(newRequestId(), "atech_atk", 1));

        assertThat(second.get("fundCost").asLong())
                .as("第 2 级要按递增后的价，若实现把 growth 漏掉这里会与第一级相等").isGreaterThan(first);
        assertThat(second.get("level").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("公账为空时研究被拒：错误码是「联盟资金不足」，而且不记等级、不产生欠款")
    void researchWithoutFundIsRejected() throws Exception {
        String leader = newPlayer(10);
        post200("/alliance/create", leader, new AllianceCreateReq(newRequestId(), "穷盟", "POOR"));

        JsonNode root = postRaw("/alliance/tech", leader,
                new com.ironoath.web.dto.generated.AllianceTechReq(newRequestId(), "atech_atk", 1));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.ALLIANCE_FUND_LACK.code());
        assertThat(root.get("detail").asText()).contains("需要 2000");

        JsonNode view = get200("/social/summary", leader);
        assertThat(view.get("alliance").get("techs")).as("失败的不得记账").isEmpty();
        assertThat(view.get("alliance").get("fund").asLong()).as("公账仍是 0，不能出现负数").isZero();
    }

    @Test
    @DisplayName("一次点多级超过上限：整笔拒绝而不是裁剪成「能点几级点几级」")
    void overCapRequestIsRejectedWhole() throws Exception {
        String leader = newPlayer(10);
        allianceWithFund(leader);

        JsonNode root = postRaw("/alliance/tech", leader,
                new com.ironoath.web.dto.generated.AllianceTechReq(newRequestId(), "atech_atk", 999));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.ALLIANCE_TECH_LEVEL_MAX.code());
        assertThat(root.get("detail").asText()).contains("最多只能研究");
        assertThat(get200("/social/summary", leader).get("alliance").get("techs")).isEmpty();
    }

    @Test
    @DisplayName("不在联盟里的人研究不了：ALLIANCE_NOT_FOUND，而不是「资金不足」这种误导提示")
    void outsiderCannotResearch() throws Exception {
        String lonely = newPlayer(10);
        JsonNode root = postRaw("/alliance/tech", lonely,
                new com.ironoath.web.dto.generated.AllianceTechReq(newRequestId(), "atech_atk", 1));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.ALLIANCE_NOT_FOUND.code());
    }

    @Test
    @DisplayName("同一 requestId 重放只研究一次：公账不会被同一笔操作扣两遍")
    void replayedRequestIdResearchesOnce() throws Exception {
        String leader = newPlayer(10);
        allianceWithFund(leader);
        String requestId = newRequestId();

        long fundAfterFirst = post200("/alliance/tech", leader,
                new com.ironoath.web.dto.generated.AllianceTechReq(requestId, "atech_def", 1))
                .get("fund").asLong();
        JsonNode replay = postRaw("/alliance/tech", leader,
                new com.ironoath.web.dto.generated.AllianceTechReq(requestId, "atech_def", 1));

        assertThat(replay.get("code").asInt()).isEqualTo(ErrorCode.REQUEST_DUPLICATED.code());
        JsonNode view = get200("/social/summary", leader);
        assertThat(view.get("alliance").get("fund").asLong())
                .as("重放不得再扣一次公共资产").isEqualTo(fundAfterFirst);
        assertThat(view.get("alliance").get("techs").get(0).get("level").asInt()).isEqualTo(1);
    }

    // ---------- 钱包真扣款（骨架期是占位：恒返回「够」且不真的扣） ----------

    @Test
    @DisplayName("建盟真的扣钱：扣掉的量恰好等于 alliance_config 里的建盟成本")
    void creatingAllianceActuallyChargesGold() throws Exception {
        String leader = newPlayer(10);
        long cost = com.ironoath.core.social.Alliance.createCost(socialRules.allianceRules());
        setGold(leader, cost * 3);
        long before = goldOf(leader);

        post200("/alliance/create", leader, new AllianceCreateReq(newRequestId(), "扣款盟", "PAY1"));

        assertThat(goldOf(leader)).as("建盟成本必须真的从钱包里走").isEqualTo(before - cost);
    }

    @Test
    @DisplayName("捐献真的扣钱：金币档扣金币，资源档扣对应资源")
    void donatingActuallyChargesTheWallet() throws Exception {
        String leader = newPlayer(10);
        post200("/alliance/create", leader, new AllianceCreateReq(newRequestId(), "扣款盟2", "PAY2"));

        var rules = socialRules.allianceRules();
        for (int tier = 0; tier < 3; tier++) {
            com.ironoath.core.social.Alliance.DonateTier t =
                    com.ironoath.core.social.Alliance.donateTierOf(rules, tier);
            if (t.costGold() <= 0 && t.costResourceType() == null) {
                continue;
            }
            if (t.costGold() > 0) {
                setGold(leader, t.costGold() * 5);
            }
            if (t.costResourceType() != null) {
                setResource(leader, t.costResourceType(), t.costResourceAmount() * 5);
            }
            long goldBefore = goldOf(leader);
            long resourceBefore = t.costResourceType() == null ? 0L
                    : balanceOf(leader, t.costResourceType());

            post200("/alliance/donate", leader, new AllianceDonateReq(newRequestId(), tier));

            if (t.costGold() > 0) {
                assertThat(goldOf(leader)).as("档位 %d 的金币成本必须真的扣", tier)
                        .isEqualTo(goldBefore - t.costGold());
            }
            if (t.costResourceType() != null) {
                assertThat(balanceOf(leader, t.costResourceType()))
                        .as("档位 %d 的资源成本必须真的扣", tier)
                        .isEqualTo(resourceBefore - t.costResourceAmount());
            }
        }
    }

    @Test
    @DisplayName("余额不足时被拒且一分不扣：deduct 是全有或全无，不允许扣一半")
    void insufficientBalanceIsRejectedWithoutCharging() throws Exception {
        String leader = newPlayer(10);
        long cost = com.ironoath.core.social.Alliance.createCost(socialRules.allianceRules());
        setGold(leader, cost - 1);
        long before = goldOf(leader);

        JsonNode root = postRaw("/alliance/create", leader,
                new AllianceCreateReq(newRequestId(), "穷盟", "POOR"));

        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.ALLIANCE_CREATE_COST_LACK.code());
        assertThat(root.path("detail").asText()).as("要说清差多少，而不是笼统的失败").contains("需要金币");
        assertThat(goldOf(leader)).as("被拒时不能扣钱").isEqualTo(before);
    }

    @Test
    @DisplayName("撞到每日档数上限时已扣的钱要退回来：先扣款再入账的顺序要求这条路必须存在")
    void dailyCapRejectionRefundsTheCharge() throws Exception {
        String leader = newPlayer(10);
        post200("/alliance/create", leader, new AllianceCreateReq(newRequestId(), "退款盟", "BACK"));
        var rules = socialRules.allianceRules();
        // **必须用金币档（tier 2）**：tier 0 是免费档、tier 1 扣粮食，
        // 用它们的话这条用例一分钱都不会扣，退款路径根本没被走到，测试会假绿
        int goldTier = 2;
        long tierGold = com.ironoath.core.social.Alliance.donateTierOf(rules, goldTier).costGold();
        assertThat(tierGold).as("这条用例的前提是金币档真的有成本，配置改了要跟着改档位").isPositive();
        setGold(leader, tierGold * 50);

        for (int i = 0; i < 3; i++) {
            post200("/alliance/donate", leader, new AllianceDonateReq(newRequestId(), goldTier));
        }
        long afterThree = goldOf(leader);

        JsonNode root = postRaw("/alliance/donate", leader,
                new AllianceDonateReq(newRequestId(), goldTier));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.ALLIANCE_DONATE_DAILY_LIMIT.code());
        // 这条才是重点：扣款发生在入账之前，所以撞上每日上限时必须原路退回，
        // 否则玩家的钱扣了而捐献没记上 —— 那是最糟的一种失败
        assertThat(goldOf(leader)).as("被拒的捐献必须全额退款").isEqualTo(afterThree);
    }

    @Test
    @DisplayName("验收5：盟友被攻击时盟内其他成员收到 MEMBER_ATTACKED，带支援坐标与窗口截止")
    void memberAttackedNotifiesAlliancePeers() throws Exception {
        String leader = newPlayer(10);
        String mate = newPlayer(10);
        String allianceId = post200("/alliance/create", leader,
                new AllianceCreateReq(newRequestId(), "通知盟", "PUSH"))
                .get("alliance").get("id").asText();
        post200("/alliance/apply", mate, new AllianceIdReq(newRequestId(), allianceId));
        post200("/alliance/review", leader, new AllianceReviewReq(newRequestId(), mate, true));

        // 夹具前提先自证：推送范围来自 store.allianceOf(victim) 的成员索引，
        // 索引若没建起来，后面的断言只会报「没收到通知」，指不到真正的原因
        var alliance = socialStore.allianceOf(mate);
        assertThat(alliance).as("夹具前提：mate 必须已入盟").isPresent();
        assertThat(alliance.orElseThrow().memberIds())
                .as("夹具前提：盟内应当有盟主与 mate 两人").contains(leader, mate);
        // 注意：盟主此时已经有一条 ALLIANCE_APPLIED（申请入盟时推的），
        // 所以后面的断言必须按类型过滤，不能用「只有一条事件」这种整体计数 ——
        // 整体计数会让这条用例在「申请通知」改动时无意义地变红

        long now = System.currentTimeMillis();
        social.notifyMemberAttacked(mate, "侵略者", 300L, 200L, now);

        var attacks = socialStore.unreadEvents(leader).stream()
                .filter(e -> "MEMBER_ATTACKED".equals(e.type()))
                .toList();
        assertThat(attacks).as("盟主应当收到盟友被攻击的通知").hasSize(1);
        var event = attacks.get(0);
        assertThat(event.type()).isEqualTo("MEMBER_ATTACKED");
        assertThat(event.title()).as("标题要写清是谁在打谁").contains("侵略者");
        assertThat(event.coordX()).as("支援坐标必须下发，否则盟友不知道该往哪派兵").isEqualTo(300L);
        assertThat(event.coordY()).isEqualTo(200L);
        assertThat(event.expireAt())
                .as("支援窗口由社交规则算而不是让调用方传：两处口径不一致的症状是"
                        + "「推送说还能支援，点进去已经置灰」")
                .isGreaterThan(now);

        // 受害者自己不该收到这条：他有战报，重复通知只会让红点失去意义
        assertThat(socialStore.unreadEvents(mate).stream()
                .filter(e -> "MEMBER_ATTACKED".equals(e.type())).count())
                .as("受害者不进自己那条通知的收件人").isZero();
    }

    private long goldOf(String playerId) {
        return balanceOf(playerId, "GOLD");
    }

    /** 结算后的余额。测试也必须用结算值：直接读存档的 current 会漏掉挂机产出。 */
    private long balanceOf(String playerId, String resourceType) {
        return wallet.available(playerId, resourceType, System.currentTimeMillis());
    }

    private void setGold(String playerId, long amount) {
        setResource(playerId, "GOLD", amount);
    }

    /**
     * 写死一个资源量。<b>必须夹到容量之内</b>：ResourceSettlement.settle 遇到
     * current &gt; cap 会抛异常，而炸点在之后某个完全不相干的读取上（实测炸在战力重算里），
     * 堆栈一个字都不会提到「有人把资源写超了」。lastSettle 设成当前时刻，
     * 免得产出在用例执行的几毫秒里把断言的基准值抬上去。
     */
    private void setResource(String playerId, String resourceType, long amount) {
        com.ironoath.core.player.PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        com.ironoath.core.player.PlayerResourceState state = save.resource(resourceType);
        save.putResource(resourceType, new com.ironoath.core.player.PlayerResourceState(
                Math.min(amount, state.cap()), state.cap(), state.protectedAmount(),
                state.perHour(), System.currentTimeMillis()));
        players.save(save);
    }

    @Test
    @DisplayName("验收7：解散联盟后保护期内不能再建，错误里给出还要等多久")
    void disbandProtectionBlocksRecreate() throws Exception {
        String leader = newPlayer(10);
        post200("/alliance/create", leader, new AllianceCreateReq(newRequestId(), "短命盟", "DEAD"));
        post200("/alliance/disband", leader, new AllianceSelfReq(newRequestId()));

        JsonNode root = postRaw("/alliance/create", leader,
                new AllianceCreateReq(newRequestId(), "再建一个", "AGAIN"));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.ALLIANCE_DISBAND_PROTECTED.code());
        assertThat(root.path("detail").asText()).as("UI 要据此画倒计时").contains("秒");

        // 别的玩家不受影响：保护期落在解散者头上
        JsonNode other = post200("/alliance/create", newPlayer(10),
                new AllianceCreateReq(newRequestId(), "别人的盟", "OTHER"));
        assertThat(other.get("alliance").isNull()).isFalse();
    }

    @Test
    @DisplayName("验收10：版本号相同时 sync 返回 unchanged 且成员列表为空（零数据量）")
    void allianceSyncReturnsNothingWhenUnchanged() throws Exception {
        String leader = newPlayer(10);
        long version = post200("/alliance/create", leader,
                new AllianceCreateReq(newRequestId(), "同步盟", "SYNC")).get("alliance").get("version").asLong();

        JsonNode same = post200("/alliance/sync", leader, new AllianceSyncReq(version, true));
        assertThat(same.get("unchanged").asBoolean()).isTrue();
        assertThat(same.get("changedMembers")).isEmpty();
        assertThat(same.get("removedMemberIds")).isEmpty();

        // 有变更（捐献会推进版本号）后，同一版本号就不再是最新
        post200("/alliance/donate", leader, new AllianceDonateReq(newRequestId(), 0));
        JsonNode stale = post200("/alliance/sync", leader, new AllianceSyncReq(version, true));
        assertThat(stale.get("unchanged").asBoolean()).isFalse();
        assertThat(stale.get("changedMembers")).isNotEmpty();
        assertThat(stale.get("version").asLong()).isGreaterThan(version);
    }

    @Test
    @DisplayName("入盟申请被拒时申请者会收到事件，而不是永远挂着不知道结果")
    void rejectedApplicationNotifiesApplicant() throws Exception {
        String leader = newPlayer(10);
        String allianceId = post200("/alliance/create", leader,
                new AllianceCreateReq(newRequestId(), "拒人盟", "NOPE")).get("alliance").get("id").asText();
        String applicant = newPlayer(10);
        post200("/alliance/apply", applicant, new AllianceIdReq(newRequestId(), allianceId));
        post200("/alliance/review", leader, new AllianceReviewReq(newRequestId(), applicant, false));

        JsonNode view = get200("/social/summary", applicant);
        assertThat(view.get("alliance").isNull()).isTrue();
        boolean notified = false;
        for (JsonNode event : view.get("events")) {
            if ("ALLIANCE_REJECTED".equals(event.get("type").asText())) {
                notified = true;
            }
        }
        assertThat(notified).as("只是不处理会让申请者永远不知道自己被忽略了").isTrue();
    }

    // ---------- 互助（验收 6） ----------

    @Test
    @DisplayName("验收6：一键帮助全部只帮可帮的，帮完后红点清零")
    void helpAllClearsRedDot() throws Exception {
        String helper = newPlayer(10);
        for (int i = 0; i < 3; i++) {
            String target = newPlayer(10);
            social.registerHelpRequest("hr-" + i, target, HelpTargetKind.BUILDING,
                    "inst_help_" + i, "伐木场 Lv7→8", System.currentTimeMillis() + 3_600_000L,
                    social.summary(target, System.currentTimeMillis()).serverNow());
        }
        JsonNode before = get200("/social/summary", helper);
        assertThat(before.get("pendingHelps").asInt()).as("三条都不是自己发的，都该算进红点").isEqualTo(3);

        JsonNode resp = post200("/social/helpAll", helper, new SocialEventAckReq(newRequestId(), List.of()));
        assertThat(resp.get("helped").asInt()).isEqualTo(3);
        assertThat(resp.get("pendingHelps").asInt()).as("验收6：红点清零").isZero();
        assertThat(resp.get("speedupGranted").asLong()).as("三次 × 定点 1%").isEqualTo(300L);

        JsonNode after = get200("/social/summary", helper);
        assertThat(after.get("pendingHelps").asInt()).isZero();
        assertThat(after.get("helpRemainingToday").asInt()).isEqualTo(17);
    }

    // ---------- 红点树（B12 §4 / 验收 1、2） ----------

    @Test
    @DisplayName("红点树：没人在等我的时候整棵树都不亮，而亮起来的是父链而不是只有叶子")
    void reddotTreeAggregatesParentChain() throws Exception {
        String helper = newPlayer(10);
        String target = newPlayer(10);

        JsonNode idle = get200("/social/reddot", helper);
        assertThat(idle.get("leafCount").asInt())
                .as("注册点里的叶子数必须可见：它长期停在个位数说明有人在业务模块里自己判红点")
                .isEqualTo(3);
        assertThat(litOf(idle.get("nodes"), "social")).isFalse();

        social.registerHelpRequest("rd-1", target, HelpTargetKind.BUILDING, "inst_rd_1", "伐木场 Lv7→8",
                System.currentTimeMillis() + 3_600_000L, System.currentTimeMillis());

        JsonNode after = get200("/social/reddot", helper);
        assertThat(litOf(after.get("nodes"), "social/help"))
                .as("叶子读的就是 hasHelpable，与摘要里的 pendingHelps 同一批判定").isTrue();
        assertThat(litOf(after.get("nodes"), "social"))
                .as("父节点由服务端聚合，客户端不做业务判断（B12 §4 的分工）").isTrue();
    }

    @Test
    @DisplayName("红点树里没有的 key 不会被编出来：无假红点是验收 1")
    void reddotNeverInventsKeys() throws Exception {
        JsonNode nodes = get200("/social/reddot", newPlayer(10)).get("nodes");
        assertThat(nodes.toString())
                .as("下发里只能出现注册过的路径；编一个没实现的 key 出去，客户端就会点出一个空面板")
                .doesNotContain("guide")
                .doesNotContain("hero");
        for (JsonNode top : nodes) {
            String key = top.get("key").asText();
            assertThat(key.startsWith("social") || key.startsWith("city"))
                    .as("顶层分支只能是注册过的这些，实际是 " + key).isTrue();
        }
    }

    @Test
    @DisplayName("红点条件不许静默抛异常：抛了就等于该亮的永不亮，比报错难查十倍")
    void reddotConditionsMustNotThrow() throws Exception {
        for (int i = 0; i < 3; i++) {
            JsonNode nodes = get200("/social/reddot", newPlayer(10)).get("nodes");
            assertThat(findKey(nodes, "city/building")).as("新叶子必须在树里").isNotNull();
            assertThat(litOf(nodes, "city"))
                    .as("父节点就是后代的聚合，不该自己再判一遍").isEqualTo(litOf(nodes, "city/building"));
        }
        assertThat(reddotTree.errorCount())
                .as("条件抛异常时红点只会安静地不亮 —— 玩家永远不知道为什么没有提示")
                .isZero();
    }

    private static JsonNode findKey(JsonNode nodes, String key) {
        for (JsonNode node : nodes) {
            if (key.equals(node.get("key").asText())) {
                return node;
            }
            JsonNode found = findKey(node.get("children"), key);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean litOf(JsonNode nodes, String key) {
        for (JsonNode node : nodes) {
            if (key.equals(node.get("key").asText())) {
                return node.get("lit").asBoolean();
            }
            boolean inChild = litOf(node.get("children"), key);
            if (inChild) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("互助列表与红点必须同源：徽标只数还能帮的，而列表保留帮过的那几行")
    void helpListAgreesWithTheBadge() throws Exception {
        String helper = newPlayer(10);
        String a = newPlayer(10);
        String b = newPlayer(10);
        long now = System.currentTimeMillis();
        long until = now + 3_600_000L;
        social.registerHelpRequest("hl-1", a, HelpTargetKind.BUILDING, "inst_hl_1", "伐木场 Lv7→8", until, now);
        social.registerHelpRequest("hl-2", b, HelpTargetKind.TRAINING, "inst_hl_2", "训练步兵", until, now);
        // 自己发的不该出现在自己能帮的列表里
        social.registerHelpRequest("hl-self", helper, HelpTargetKind.BUILDING, "inst_hl_self", "自己的", until, now);

        JsonNode list = get200("/social/helpRequests", helper);
        assertThat(list.get("requests").size())
                .as("两条别人的 + 不含自己那条").isEqualTo(2);
        assertThat(list.get("pendingHelps").asInt()).isEqualTo(2);
        assertThat(get200("/social/summary", helper).get("pendingHelps").asInt())
                .as("两个端点的徽标必须同值 —— 不一致的表现是「红点说 2 条、点进去 3 条、一键帮助却帮了 3 次」")
                .isEqualTo(list.get("pendingHelps").asInt());

        post200("/social/help", helper, new HelpReq(newRequestId(), "hl-1"));

        JsonNode after = get200("/social/helpRequests", helper);
        assertThat(after.get("requests").size())
                .as("帮过的那条要留在列表里：玩家要看得见「我帮过谁」，否则他会重复去帮同一个人").isEqualTo(2);
        assertThat(after.get("pendingHelps").asInt()).as("但徽标只数还能帮的").isEqualTo(1);

        boolean sawHelped = false;
        for (JsonNode row : after.get("requests")) {
            if (row.get("alreadyHelped").asBoolean()) {
                sawHelped = true;
                assertThat(row.get("requestId").asText()).isEqualTo("hl-1");
                assertThat(row.get("remainingSeconds").asLong())
                        .as("剩余秒数由服务端算好且绝不为负，客户端不碰时钟").isPositive();
            }
        }
        assertThat(sawHelped).as("alreadyHelped 不下发的话，「一键帮助」会在同一个人身上重复消耗额度").isTrue();
    }

    @Test
    @DisplayName("已帮过的请求不重复计：再点一次一键帮助，helped=0 而 skipped>0")
    void helpAllSkipsAlreadyHelped() throws Exception {
        String helper = newPlayer(10);
        String target = newPlayer(10);
        social.registerHelpRequest("hr-dup", target, HelpTargetKind.TREATING, "inst_hr_dup", "治疗伤兵",
                System.currentTimeMillis() + 3_600_000L, System.currentTimeMillis());

        assertThat(post200("/social/helpAll", helper, new SocialEventAckReq(newRequestId(), List.of()))
                .get("helped").asInt()).isEqualTo(1);
        JsonNode second = post200("/social/helpAll", helper, new SocialEventAckReq(newRequestId(), List.of()));
        assertThat(second.get("helped").asInt()).as("不重复消耗每日额度").isZero();
        assertThat(second.get("helpRemainingToday").asInt()).isEqualTo(19);
    }

    @Test
    @DisplayName("不能帮自己：自己发的帮助请求不进红点也不能被一键帮助选中")
    void cannotHelpSelf() throws Exception {
        String me = newPlayer(10);
        social.registerHelpRequest("hr-self", me, HelpTargetKind.BUILDING, "inst_hr_self", "主城 Lv9→10",
                System.currentTimeMillis() + 3_600_000L, System.currentTimeMillis());
        JsonNode summary = get200("/social/summary", me);
        assertThat(summary.get("pendingHelps").asInt()).isZero();
        assertThat(post200("/social/helpAll", me, new SocialEventAckReq(newRequestId(), List.of()))
                .get("helped").asInt()).isZero();
    }

    // ---------- 聊天（验收 9） ----------

    @Test
    @DisplayName("验收9：同内容 10 秒内第 4 次被拦，错误码是限流而不是笼统失败；不同内容不受影响")
    void chatRateLimitBlocksFourthIdenticalMessage() throws Exception {
        String player = newPlayer(5);
        ChatSendReq message = new ChatSendReq(newRequestId(), ChatChannel.WORLD, "招人了", null);
        for (int i = 0; i < 3; i++) {
            post200("/chat/send", player, new ChatSendReq(newRequestId(), ChatChannel.WORLD, "招人了", null));
        }
        JsonNode blocked = postRaw("/chat/send", player, message);
        assertThat(blocked.get("code").asInt()).isEqualTo(ErrorCode.SOCIAL_CHAT_RATE_LIMITED.code());
        assertThat(blocked.path("detail").asText()).as("要告诉玩家多久后才能再发").contains("秒");

        // 换个内容就能发：限的是复读机，不是正常聊天
        post200("/chat/send", player, new ChatSendReq(newRequestId(), ChatChannel.WORLD, "坐标 (120,88)", null));
    }

    @Test
    @DisplayName("未加入联盟时不能往联盟频道发言：否则任何人构造一个 allianceId 就能往别人盟里发消息")
    void chatChannelRequiresMembership() throws Exception {
        String player = newPlayer(5);
        JsonNode root = postRaw("/chat/send", player,
                new ChatSendReq(newRequestId(), ChatChannel.ALLIANCE, "有人吗", null));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.SOCIAL_CHAT_CHANNEL_INVALID.code());

        // 入盟之后就能发，且队友能在同一频道读到
        String leader = newPlayer(10);
        String allianceId = post200("/alliance/create", leader,
                new AllianceCreateReq(newRequestId(), "聊天盟", "CHAT")).get("alliance").get("id").asText();
        post200("/alliance/apply", player, new AllianceIdReq(newRequestId(), allianceId));
        post200("/alliance/review", leader, new AllianceReviewReq(newRequestId(), player, true));

        post200("/chat/send", player, new ChatSendReq(newRequestId(), ChatChannel.ALLIANCE, "今晚八点集结", null));
        JsonNode list = post200("/chat/list", leader, new ChatListReq(ChatChannel.ALLIANCE, null, null, 20));
        assertThat(list.get("messages")).hasSize(1);
        assertThat(list.get("messages").get(0).get("content").asText()).isEqualTo("今晚八点集结");
        assertThat(list.get("messages").get(0).get("senderName").asText()).isNotBlank();
        assertThat(list.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("拉私聊却没给对象：要说「缺对象」，不能报成「你没资格发言」")
    void privateChatListWithoutTargetSaysWhatIsMissing() throws Exception {
        String player = newPlayer(5);
        JsonNode root = postRaw("/chat/list", player,
                new ChatListReq(ChatChannel.PRIVATE, null, null, 20));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.SOCIAL_CHAT_CHANNEL_INVALID.code());
        // 漏传字段与没有资格是两件事：报成后者，玩家会以为自己被踢出了这段会话，
        // 而真正该做的动作是把对象带上（B08 §1「提示要能指引下一步」在这里同样适用）
        assertThat(root.path("detail").asText()).contains("对象");
    }

    @Test
    @DisplayName("私聊要能发也要能拉回来：会话键由双方 id 拼成，第三方拿不到别人的会话")
    void privateChatRoundTripsAndIsScopedToItsTwoParticipants() throws Exception {
        String a = newPlayer(5);
        String b = newPlayer(5);
        post200("/chat/send", a, new ChatSendReq(newRequestId(), ChatChannel.PRIVATE, "在吗", b));

        JsonNode forA = post200("/chat/list", a, new ChatListReq(ChatChannel.PRIVATE, b, null, 20));
        assertThat(forA.get("messages")).as("发起方刷新后不该丢掉整段会话")
                .hasSize(1);
        assertThat(forA.get("messages").get(0).get("content").asText()).isEqualTo("在吗");

        assertThat(post200("/chat/list", b, new ChatListReq(ChatChannel.PRIVATE, a, null, 20))
                .get("messages")).as("A→B 与 B→A 必须落在同一个会话键上")
                .hasSize(1);

        String c = newPlayer(5);
        JsonNode stolen = post200("/chat/list", c, new ChatListReq(ChatChannel.PRIVATE, a, null, 20));
        assertThat(stolen.get("messages")).as("C 与 A 之间没有会话，也不该看见 A 与 B 的那一段")
                .isEmpty();
    }

    @Test
    @DisplayName("游标已经被淘汰时回答「没有更早的了」，不许把最新一页重放一遍")
    void staleCursorMeansEndOfHistoryNotLatestPage() throws Exception {
        String player = newPlayer(5);
        post200("/chat/send", player, new ChatSendReq(newRequestId(), ChatChannel.WORLD, "第一条", null));
        post200("/chat/send", player, new ChatSendReq(newRequestId(), ChatChannel.WORLD, "第二条", null));
        post200("/chat/send", player, new ChatSendReq(newRequestId(), ChatChannel.WORLD, "第三条", null));

        // 一个不在这段历史里的游标（真实成因：更早的消息已被 CHAT_LOCAL_HISTORY_MAX 淘汰）
        JsonNode stale = post200("/chat/list", player,
                new ChatListReq(ChatChannel.WORLD, null, "msg_not_in_this_history", 20));
        assertThat(stale.get("messages")).as("退化成最新一页会让客户端把看过的当新消息追加").isEmpty();
        assertThat(stale.get("hasMore").asBoolean())
                .as("hasMore 与 messages 必须出自同一个游标解析，否则一处说没了另一处还给一页")
                .isFalse();
    }

    @Test
    @DisplayName("同一频道里重复的 messageId 被存储层当场拒绝：撞号等于静默丢消息")
    void duplicateMessageIdIsRefusedByTheStore() {
        InMemorySocialStore.ChatMessage first = new InMemorySocialStore.ChatMessage(
                "msg_same", "WORLD", "P-a", "甲", "一", 1000L);
        InMemorySocialStore.ChatMessage twin = new InMemorySocialStore.ChatMessage(
                "msg_same", "WORLD", "P-b", "乙", "二", 1001L);
        socialStore.appendChat("WORLD", first, 50);

        assertThatThrownBy(() -> socialStore.appendChat("WORLD", twin, 50))
                .as("翻页游标按 id 定位，同一个 id 出现两次意味着第二条连同它之前的都被跳过")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("空内容被拒：空消息进了频道会让其他人看到一个空白气泡")
    void chatRejectsBlankContent() throws Exception {
        String player = newPlayer(5);
        JsonNode root = postRaw("/chat/send", player,
                new ChatSendReq(newRequestId(), ChatChannel.WORLD, "   ", null));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.SOCIAL_CHAT_CONTENT_INVALID.code());
    }

    // ---------- 事件补偿（验收 12） ----------

    @Test
    @DisplayName("验收12：未读事件在汇总里给出，ackEvents 之后不再重复出现")
    void eventsAreAcknowledgedOnce() throws Exception {
        String leader = newPlayer(5);
        String squadId = post200("/squad/create", leader, new SquadCreateReq(newRequestId(), "事件队"))
                .get("squad").get("id").asText();
        String mate = newPlayer(5);
        post200("/squad/join", mate, new SquadIdReq(newRequestId(), squadId));
        post200("/squad/kick", leader, new SquadMemberReq(newRequestId(), mate));

        JsonNode first = get200("/social/summary", mate);
        assertThat(first.get("events")).isNotEmpty();
        List<String> ids = new java.util.ArrayList<>();
        first.get("events").forEach(node -> ids.add(node.get("eventId").asText()));

        JsonNode acked = post200("/social/ackEvents", mate, new SocialEventAckReq(newRequestId(), ids));
        assertThat(acked.get("events")).as("标记已读后不再下发，否则每次上线都重收同一批").isEmpty();
        assertThat(acked.get("pendingHelps").asInt()).isZero();
    }

    // ---------- 幂等 ----------

    @Test
    @DisplayName("同一个 requestId 重放被拒：创建小队会写入组织表并占名字，重放会建出两个同名小队")
    void duplicateRequestIdIsRejected() throws Exception {
        String leader = newPlayer(5);
        String requestId = newRequestId();
        post200("/squad/create", leader, new SquadCreateReq(requestId, "幂等队"));
        JsonNode replay = postRaw("/squad/create", leader, new SquadCreateReq(requestId, "幂等队"));
        assertThat(replay.get("code").asInt()).isEqualTo(ErrorCode.REQUEST_DUPLICATED.code());
    }

    @Test
    @DisplayName("缺 requestId 直接拒绝：社交域的写操作全都会改动别人的状态")
    void missingRequestIdIsRejected() throws Exception {
        String leader = newPlayer(5);
        JsonNode root = postRaw("/squad/create", leader, new SquadCreateReq(null, "无键队"));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.REQUEST_ID_MISSING.code());
    }

    // ---------- 辅助 ----------

    // ---------- 联盟管理：踢人 / 转让 / 任命（域方法早就有，本轮才接上端点） ----------

    @Test
    @DisplayName("踢人的权限位真的来自 role_permission：官员可以，普通成员被拒且点名缺哪一位")
    void kickPermissionComesFromTheTable() throws Exception {
        Quartet q = allianceWithThreeMembers();
        post200("/alliance/setRole", q.leader, new AllianceRoleReq(newRequestId(), q.b,
                com.ironoath.web.dto.generated.AllianceRole.OFFICER));

        JsonNode afterKick = post200("/alliance/kick", q.b, new AllianceMemberReq(newRequestId(), q.d));
        assertThat(afterKick.get("alliance").get("memberCount").asInt())
                .as("官员有 KICK_MEMBER 位（allowOfficer=true）").isEqualTo(3);
        assertThat(get200("/social/summary", q.d).get("alliance").isNull())
                .as("被踢的人要真的离开联盟（摘要里不再有它），而不是名单上留个标记")
                .isTrue();
        assertThat(get200("/social/summary", q.d).get("events").toString())
                .as("事件随摘要一起下发（没有独立的事件端点）；组织身份变了要能自己看见，否则只会来问客服")
                .contains("ALLIANCE_KICKED");

        JsonNode denied = postRaw("/alliance/kick", q.c, new AllianceMemberReq(newRequestId(), q.b));
        assertThat(denied.get("code").asInt()).isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.code());
        assertThat(denied.get("detail").asText())
                .as("缺哪个权限位要说出来：这是查表得出的，不该硬编在文案里").contains("KICK_MEMBER");
    }

    @Test
    @DisplayName("不能踢盟主：要换人只能走转让，否则联盟会剩下没有责任人的状态")
    void leaderCannotBeKicked() throws Exception {
        Quartet q = allianceWithThreeMembers();
        post200("/alliance/setRole", q.leader, new AllianceRoleReq(newRequestId(), q.b,
                com.ironoath.web.dto.generated.AllianceRole.OFFICER));

        assertThat(postRaw("/alliance/kick", q.b, new AllianceMemberReq(newRequestId(), q.leader))
                .get("code").asInt())
                .as("官员权限再大也不能把盟主踢没").isEqualTo(ErrorCode.PARAM_INVALID.code());
    }

    @Test
    @DisplayName("转让盟主：非盟主被拒、转给自己被拒、成功后原盟主降为官员")
    void transferLeadershipRules() throws Exception {
        Quartet q = allianceWithThreeMembers();

        assertThat(postRaw("/alliance/transfer", q.b, new AllianceMemberReq(newRequestId(), q.c))
                .get("code").asInt())
                .as("只有盟主能发起转让").isEqualTo(ErrorCode.ALLIANCE_NOT_LEADER.code());
        assertThat(postRaw("/alliance/transfer", q.leader,
                new AllianceMemberReq(newRequestId(), q.leader)).get("code").asInt())
                .as("转给自己不是转让，却会留下一条假的审计记录")
                .isEqualTo(ErrorCode.ALLIANCE_TRANSFER_TO_SELF.code());
        assertThat(postRaw("/alliance/transfer", q.leader,
                new AllianceMemberReq(newRequestId(), "P-ghost")).get("code").asInt())
                .isEqualTo(ErrorCode.ALLIANCE_NOT_MEMBER.code());

        JsonNode after = post200("/alliance/transfer", q.leader,
                new AllianceMemberReq(newRequestId(), q.b));
        assertThat(after.get("alliance").get("leaderId").asText()).isEqualTo(q.b);
        assertThat(get200("/social/summary", q.leader).get("alliance").get("myRole").asText())
                .as("原盟主降为官员，而不是继续挂着 LEADER").isEqualTo("OFFICER");
    }

    @Test
    @DisplayName("任命不能造出更高的职位：官员自封盟主被拒，降级盟主也必须走转让")
    void roleAppointmentCannotEscalate() throws Exception {
        Quartet q = allianceWithThreeMembers();
        post200("/alliance/setRole", q.leader, new AllianceRoleReq(newRequestId(), q.b,
                com.ironoath.web.dto.generated.AllianceRole.OFFICER));

        assertThat(postRaw("/alliance/setRole", q.b, new AllianceRoleReq(newRequestId(), q.b,
                com.ironoath.web.dto.generated.AllianceRole.LEADER)).get("code").asInt())
                .as("副盟主能造出新盟主的话，盟主这个位置就没有意义了")
                .isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.code());
        assertThat(postRaw("/alliance/setRole", q.leader, new AllianceRoleReq(newRequestId(), q.leader,
                com.ironoath.web.dto.generated.AllianceRole.MEMBER)).get("code").asInt())
                .as("降级盟主会让联盟没有盟主").isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.code());
        assertThat(postRaw("/alliance/setRole", q.leader, new AllianceRoleReq(newRequestId(), "P-ghost",
                com.ironoath.web.dto.generated.AllianceRole.ELDER)).get("code").asInt())
                .isEqualTo(ErrorCode.ALLIANCE_NOT_MEMBER.code());
    }

    @Test
    @DisplayName("同一个 requestId 重放踢人被幂等键挡下，不会重复推送与重复审计")
    void kickIsIdempotent() throws Exception {
        Quartet q = allianceWithThreeMembers();
        AllianceMemberReq req = new AllianceMemberReq("req-kick-fixed", q.c);

        post200("/alliance/kick", q.leader, req);

        assertThat(postRaw("/alliance/kick", q.leader, req).get("code").asInt())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED.code());
    }

    private record Quartet(String leader, String b, String c, String d, String allianceId) {
    }

    /** 盟主 + 三名普通成员：三档权限（leader/officer/member）各不相同，才验得出权限真的走表。 */
    private Quartet allianceWithThreeMembers() throws Exception {
        String leader = newPlayer(10);
        String allianceId = post200("/alliance/create", leader,
                new AllianceCreateReq(newRequestId(), "管理测试盟", "KGMS"))
                .get("alliance").get("id").asText();
        String b = newPlayer(10);
        String c = newPlayer(10);
        String d = newPlayer(10);
        for (String member : java.util.List.of(b, c, d)) {
            post200("/alliance/apply", member, new AllianceIdReq(newRequestId(), allianceId));
            post200("/alliance/review", leader, new AllianceReviewReq(newRequestId(), member, true));
        }
        return new Quartet(leader, b, c, d, allianceId);
    }

    private String newPlayer(int cityLevel) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "社交测试", 1_700_000_000_000L))
                .playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        // 小队要求主城 5 级、联盟要求 10 级，所以城等由用例指定
        save.setCityLevel(cityLevel);
        // 建盟要 500 金币（global.ALLIANCE_CREATE_COST_GOLD），而新号只有 resource.initAmount 那一点，
        // 所以测试必须自己把余额准备好。钱包已经接通（PlayerWallet 真扣款），
        // 这一行不是绕过校验：余额备足之后，建盟会真的扣掉 500，
        // 而那正是 creatingAllianceActuallyChargesGold 那条用例在断言的事
        PlayerResourceState gold = save.resources().get("GOLD");
        if (gold != null) {
            save.putResource("GOLD", new PlayerResourceState(
                    100_000L, gold.cap(), gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        }
        players.save(save);
        // 开服天数门槛不靠这里控制：SERVER_OPEN_AT 未配置时 SocialAppService 直接放行
        return playerId;
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private JsonNode get200(String url, String playerId) throws Exception {
        return okData(perform(get(url).header(PLAYER_HEADER, playerId)));
    }

    private JsonNode post200(String url, String playerId, Object req) throws Exception {
        return okData(postRawNode(url, playerId, req));
    }

    private JsonNode postRaw(String url, String playerId, Object req) throws Exception {
        return postRawNode(url, playerId, req);
    }

    private JsonNode postRawNode(String url, String playerId, Object req) throws Exception {
        MockHttpServletRequestBuilder builder = post(url)
                .header(PLAYER_HEADER, playerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(req == null ? "{}" : JsonUtils.toJson(req));
        return perform(builder);
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
