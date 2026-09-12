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
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.nation.Nation.Office;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.AllianceSelfReq;
import com.ironoath.web.dto.generated.DiplomacyRelation;
import com.ironoath.web.dto.generated.NationAppointReq;
import com.ironoath.web.dto.generated.NationDiplomacyReq;
import com.ironoath.web.dto.generated.NationDisbandReq;
import com.ironoath.web.dto.generated.NationFoundReq;
import com.ironoath.web.dto.generated.NationJoinReq;
import com.ironoath.web.dto.generated.NationLeaveReq;
import com.ironoath.web.dto.generated.NationOffice;
import com.ironoath.web.dto.generated.NationTreasurySpendReq;
import com.ironoath.web.dto.generated.TreasuryPayeeType;
import com.ironoath.web.dto.generated.TreasurySink;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemoryNationStore;
import com.ironoath.web.store.memory.InMemorySocialStore;

/**
 * 职责：B13 国家域的端到端验证 —— 建国前置、联盟 ⊂ 国家、官职任命的权限与合规红线。
 * 依赖：Spring Boot Test + MockMvc；test profile（内存存储）。
 *
 * <p><b>本类最重要的一条是 Bot 合规红线</b>：B13 §2 与 B16 上线清单 §七 都写着
 * 「Bot 不得担任任何国家官职」，而 {@code BotTuning.mayHoldOffice} 这个判定函数
 * 交付之后长期<b>没有任何调用点</b> —— 判定写了却没接上，等于红线只存在于文档里。
 * 现在任命路径与议员席（派生）两个调用点都转调它（{@code BotRegistry.requireMayHoldOffice}
 * 与 {@code BotRegistry.mayHoldOffice}），下面的两条用例就是它真的生效的证据。
 * {@code BotRegistryComplianceTest} 另有一条用例钉住"判定确实来自那张表"而不是一刀切。
 *
 * <p><b>「联盟 ⊂ 国家」是靠数据结构保证的，但用例仍然要验</b>：
 * 国家成员表的键是 allianceId，所以「个人单独入籍」在领域层无法表达；
 * 可 web 层仍然可能写出一条绕过它的路径（例如直接按 playerId 任命一个不在本国联盟里的人），
 * 所以 {@code NATION_NOT_MEMBER} 那条用例验的是 web 层没有开出这个口子。
 *
 * <p><b>入籍与退出国这一族用例的价值不只是"多两个端点"</b>：{@code Nation.admitAlliance} 交付以来
 * 在生产里<b>零调用点</b>，于是国家永远只有建国的那一个联盟 —— §1 的「200=2 盟 → 800=8 盟」
 * 容量梯度一次也没走通过，而 §2 的议员席（每盟主 1 席，由外层注入的盟主查询派生）恒为空。
 * 「议员恒为空」这件事在过去<b>没有任何用例能发现</b>：没有端点能让第二个联盟入籍，
 * 而那正是派生席位唯一的输入。这一族用例把这条链从 HTTP 一路走到领域层，
 * 顺带钉住三件容易各说各话的事：议员名单等于成员联盟的盟主集合、国王不被派生成议员示人、
 * 以及联盟解散后国家不再挂它（否则名额被永久占掉）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NationEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private InMemorySocialStore socialStore;
    @Autowired private InMemoryNationStore nationStore;
    @Autowired private BotRegistry bots;
    /** 攻击闸门：外交那一层是否真的生效，只有问它本人才算数。 */
    @Autowired private com.ironoath.web.service.AttackGuardService guard;
    @Autowired private com.ironoath.web.service.WorldAppService worlds;
    @Autowired private com.ironoath.common.time.TimeService timeService;

    /** 联盟名/标签的序号。@BeforeEach 重置。 */
    private int allianceSeq;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        socialStore.clear();
        nationStore.clear();
        bots.clear();
        allianceSeq = 0;
    }

    // ---------- 建国 ----------

    @Test
    @DisplayName("建国：发起人所在联盟整体入籍，发起人任国王，视图给出联盟数与国库上限")
    void foundingCreatesANationAroundTheAlliance() throws Exception {
        Kingdom k = kingdom();

        JsonNode nation = post200("/nation/found", k.king,
                new NationFoundReq(newRequestId(), "铁誓王国", 100L, 200L)).get("nation");

        assertThat(nation.get("name").asText()).isEqualTo("铁誓王国");
        assertThat(nation.get("kingId").asText()).isEqualTo(k.king);
        assertThat(nation.get("level").asInt()).isEqualTo(1);
        assertThat(nation.get("allianceCount").asInt())
                .as("成员表的最小单位是联盟，所以这里数的是联盟而不是玩家").isEqualTo(1);
        assertThat(nation.get("memberCap").asInt()).as("来自 nation_config 的 1 级行").isEqualTo(200);
        assertThat(nation.get("capitalX").asLong()).isEqualTo(100L);
        assertThat(nation.get("capitalY").asLong()).isEqualTo(200L);
        assertThat(nation.get("treasuryCap").asLong()).isPositive();
        assertThat(nation.get("myOffice").asText()).as("发起人任国王").isEqualTo("KING");

        // 同联盟的成员也能看到同一个国家（国籍跟随联盟）
        JsonNode mine = get200("/nation", k.mate).get("nation");
        assertThat(mine.get("nationId").asText()).isEqualTo(nation.get("nationId").asText());
        assertThat(mine.get("myOffice").isNull() || mine.get("myOffice").asText().isEmpty())
                .as("普通成员没有官职").isTrue();
    }

    @Test
    @DisplayName("建国前置：不在联盟中的人建不了国（个人不能单独入籍，所以也不能单独建国）")
    void foundingRequiresAnAlliance() throws Exception {
        String loner = newPlayer(16);
        JsonNode root = postRaw("/nation/found", loner,
                new NationFoundReq(newRequestId(), "孤家寡人", 10L, 10L));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.NATION_LOCKED.code());
        assertThat(root.path("detail").asText()).contains("联盟");
    }

    @Test
    @DisplayName("主城不足 16 级时建国被拒，且错误里说清差什么（绝不静默失败）")
    void foundingRequiresCityLevel() throws Exception {
        String king = newPlayer(16);
        String mate = newPlayer(16);
        String allianceId = createAlliance(king, mate);
        // 把国王的城降回 15 级：门槛是 16 级
        PlayerSave save = players.findByPlayerId(king).orElseThrow();
        save.setCityLevel(15);
        players.save(save);

        JsonNode root = postRaw("/nation/found", king,
                new NationFoundReq(newRequestId(), "差一级", 10L, 10L));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.NATION_LOCKED.code());
        assertThat(root.path("detail").asText()).as("要说清差什么").contains("主城");
        assertThat(allianceId).isNotBlank();
    }

    @Test
    @DisplayName("国名重复被拒：两个同名国家会让「按名字找国家」变成一个不确定的查询")
    void duplicateNationNameIsRejected() throws Exception {
        Kingdom k = kingdom();
        post200("/nation/found", k.king, new NationFoundReq(newRequestId(), "重名国", 100L, 200L));

        String otherKing = newPlayer(16);
        String otherMate = newPlayer(16);
        createAlliance(otherKing, otherMate);
        JsonNode root = postRaw("/nation/found", otherKing,
                new NationFoundReq(newRequestId(), "重名国", 300L, 300L));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.NATION_NAME_TAKEN.code());
    }

    // ---------- 官职任命 ----------

    @Test
    @DisplayName("国王任命同联盟成员：权限走 role_permission 表，任命后视图里能看到官职")
    void kingCanAppointAMember() throws Exception {
        Kingdom k = kingdom();
        String nationId = post200("/nation/found", k.king,
                new NationFoundReq(newRequestId(), "任命国", 100L, 200L)).get("nation").get("nationId").asText();

        JsonNode nation = post200("/nation/appoint", k.king,
                new NationAppointReq(newRequestId(), k.mate, NationOffice.GENERAL)).get("nation");
        assertThat(nation.get("nationId").asText()).isEqualTo(nationId);

        JsonNode mateView = get200("/nation", k.mate).get("nation");
        assertThat(mateView.get("myOffice").asText()).isEqualTo("GENERAL");
    }

    @Test
    @DisplayName("合规红线：Bot 不得担任任何国家官职（mayHoldOffice 这条判定终于有了调用点）")
    void botsCannotHoldOffice() throws Exception {
        Kingdom k = kingdom();
        post200("/nation/found", k.king, new NationFoundReq(newRequestId(), "红线国", 100L, 200L));
        bots.register(botProfile(k.mate()));

        JsonNode root = postRaw("/nation/appoint", k.king,
                new NationAppointReq(newRequestId(), k.mate, NationOffice.GENERAL));

        assertThat(root.get("code").asInt())
                .as("B13 §2 与 B16 上线清单 §七 的合规红线").isEqualTo(ErrorCode.BOT_NOT_ELIGIBLE.code());
        JsonNode mateView = get200("/nation", k.mate).get("nation");
        assertThat(mateView.get("myOffice").isNull() || mateView.get("myOffice").asText().isEmpty())
                .as("被拒的任命不该留下任何官职").isTrue();
    }

    @Test
    @DisplayName("权限走表：普通成员（无官职）不能任命，错误里点明缺哪个权限位")
    void memberCannotAppoint() throws Exception {
        Kingdom k = kingdom();
        post200("/nation/found", k.king, new NationFoundReq(newRequestId(), "权限国", 100L, 200L));

        JsonNode root = postRaw("/nation/appoint", k.mate,
                new NationAppointReq(newRequestId(), k.king, NationOffice.MINISTER));

        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.code());
        assertThat(root.path("detail").asText())
                .as("缺哪个权限位是查表得出的，不能硬编码在文案里").contains("APPOINT_OFFICE");
    }

    @Test
    @DisplayName("联盟 ⊂ 国家：被任命者的联盟不属于本国时被拒，个人不能绕过联盟单独入籍")
    void appointingANonMemberIsRejected() throws Exception {
        Kingdom k = kingdom();
        post200("/nation/found", k.king, new NationFoundReq(newRequestId(), "边界国", 100L, 200L));

        String otherKing = newPlayer(16);
        String otherMate = newPlayer(16);
        createAlliance(otherKing, otherMate);

        JsonNode root = postRaw("/nation/appoint", k.king,
                new NationAppointReq(newRequestId(), otherMate, NationOffice.GENERAL));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.NATION_NOT_MEMBER.code());
    }

    // ---------- 联盟入籍与退出国（B13 §二冲突规则、验收 2） ----------

    @Test
    @DisplayName("盟主发起入籍：全盟随联盟入籍，而这个盟主自动占一席议员（B13 §2「每盟主 1 席」）")
    void allianceLeaderJoinsAndTakesARepresentativeSeat() throws Exception {
        TwoKingdoms k = twoKingdoms();
        FreeAlliance f = freeAlliance();

        JsonNode nation = post200("/nation/join", f.leader(),
                new NationJoinReq(newRequestId(), k.nationA())).get("nation");
        assertThat(nation.get("allianceCount").asInt())
                .as("成员表的最小单位是联盟，所以这里数的是联盟而不是玩家").isEqualTo(2);

        // 议员是 B13 §2 里唯一的派生席位：不是谁任命的，而是"这个人本来就是盟主"。
        // 这份派生要靠外层注入的盟主查询才算得出来，而生产里此前从来没注入过 ——
        // 结果就是议员恒为空，而没有任何用例能发现这件事（连一个能让第二个联盟入籍的端点都没有）。
        assertThat(get200("/nation", f.leader()).get("nation").get("myOffice").asText())
                .as("入籍联盟的盟主占一席议员").isEqualTo("REPRESENTATIVE");
        assertThat(get200("/nation", k.kingA()).get("nation").get("myOffice").asText())
                .as("国王本来也是自己那个盟的盟主，但 officeOf 按官职声明顺序取，KING 不被议员盖掉")
                .isEqualTo("KING");
        JsonNode mateView = get200("/nation", f.mate()).get("nation");
        assertThat(mateView.get("myOffice").isNull() || mateView.get("myOffice").asText().isEmpty())
                .as("普通成员跟随联盟入籍，但不跟随盟主入席").isTrue();
        assertThat(nationOf(k.nationA()).holdersOf(Office.REPRESENTATIVE))
                .as("议员名单就是「全部成员联盟的盟主」，顺序按入籍先后")
                .containsExactly(k.kingA(), f.leader());
    }

    @Test
    @DisplayName("合规红线：Bot 盟主的联盟照样能入籍，但不占议员席（任命闸门管不到派生席位）")
    void botsCannotTakeTheDerivedRepresentativeSeat() throws Exception {
        TwoKingdoms k = twoKingdoms();
        FreeAlliance f = freeAlliance();
        // 议员不是任命出来的，所以 appoint 那条闸门管不到它 —— 红线必须落在派生本身
        bots.register(botProfile(f.leader()));

        JsonNode nation = post200("/nation/join", f.leader(),
                new NationJoinReq(newRequestId(), k.nationA())).get("nation");
        assertThat(nation.get("allianceCount").asInt())
                .as("入籍是成员关系而不是官职：B11 要求 Bot 走与真人完全相同的 service 层，"
                        + "所以这个联盟该进得来").isEqualTo(2);

        assertThat(get200("/nation", f.leader()).get("nation").get("myOffice").isNull()
                        || get200("/nation", f.leader()).get("nation").get("myOffice").asText().isEmpty())
                .as("B13 §2：Bot 不得担任任何国家官职，而议员正是 Nation.Office 的一档").isTrue();
        assertThat(nationOf(k.nationA()).holdersOf(Office.REPRESENTATIVE))
                .as("落选的方式是「这一席不给」，不是让整个视图报错")
                .containsExactly(k.kingA());
        assertThat(get200("/nation", f.mate()).get("nation").get("nationId").asText())
                .as("该盟的真人成员不受影响：国籍跟随联盟")
                .isEqualTo(k.nationA());
    }

    @Test
    @DisplayName("入籍由盟主发起：普通成员代表不了全盟，被拒之后国家成员表一个字没改")
    void onlyTheAllianceLeaderCanInitiateJoin() throws Exception {
        TwoKingdoms k = twoKingdoms();
        FreeAlliance f = freeAlliance();

        JsonNode root = postRaw("/nation/join", f.mate(),
                new NationJoinReq(newRequestId(), k.nationA()));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.ALLIANCE_NOT_LEADER.code());
        assertThat(nationOf(k.nationA()).memberAllianceCount())
                .as("被拒的入籍不该留下半个成员联盟").isEqualTo(1);
    }

    @Test
    @DisplayName("一个联盟只能属一个国家：已在别国的盟主再来入籍被拒（个人不单独入籍，所以也没有「双重国籍」）")
    void anAllianceAlreadyInANationCannotJoinAnother() throws Exception {
        TwoKingdoms k = twoKingdoms();

        // kingA 是他那个联盟的盟主，而那个联盟已经属于 nationA
        JsonNode root = postRaw("/nation/join", k.kingA(),
                new NationJoinReq(newRequestId(), k.nationB()));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.NATION_LOCKED.code());
        assertThat(root.path("detail").asText()).contains("已经属于一个国家");
        assertThat(nationOf(k.nationB()).memberAllianceCount())
                .as("被拒的一方不该多出成员").isEqualTo(1);
    }

    @Test
    @DisplayName("B13 验收 2：联盟退出国之后，24h 内加入任何国家都被拒，而冷却时刻与配置同源")
    void leavingANationStartsTheJoinCooldown() throws Exception {
        TwoKingdoms k = twoKingdoms();
        FreeAlliance f = freeAlliance();
        post200("/nation/join", f.leader(), new NationJoinReq(newRequestId(), k.nationA()));

        JsonNode left = post200("/nation/leave", f.leader(), new NationLeaveReq(newRequestId()));
        assertThat(left.get("nationId").asText()).isEqualTo(k.nationA());
        assertThat(left.get("nationName").asText()).isEqualTo("东方王国");
        long cooldown = nationOf(k.nationA()).rules().joinCooldownMillis();
        assertThat(left.get("cooldownUntil").asLong() - left.get("serverNow").asLong())
                .as("客户端显示的倒计时必须与服务端判定同源，不能自己拿一个时长再加一遍")
                .isEqualTo(cooldown);
        assertThat(cooldown).as("冷却来自 global.NATION_JOIN_COOLDOWN_HOURS（24 小时）").isEqualTo(86_400_000L);

        JsonNode afterLeave = perform(get("/nation").header(PLAYER_HEADER, f.leader()));
        assertThat(afterLeave.get("code").asInt())
                .as("退出之后他就没有国家了 —— 这也是 /leave 不复用 NationResp 的理由："
                        + "回一份他无权查询的国家视图会让客户端停在一个假对象上")
                .isEqualTo(ErrorCode.NATION_NOT_FOUND.code());

        JsonNode root = postRaw("/nation/join", f.leader(),
                new NationJoinReq(newRequestId(), k.nationB()));
        assertThat(root.get("code").asInt())
                .as("换一个目标国家也不行：冷却落在联盟身上，不是落在某一个国家上")
                .isEqualTo(ErrorCode.NATION_JOIN_COOLDOWN.code());
        assertThat(root.path("detail").asText()).as("要给出还要等多久，否则玩家只会反复试")
                .contains("秒");
        assertThat(nationOf(k.nationB()).memberAllianceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("名额按国家等级：1 级国只收 2 个联盟，第三个被拒且说清上限")
    void nationCapacityIsCountedInAlliances() throws Exception {
        TwoKingdoms k = twoKingdoms();
        FreeAlliance first = freeAlliance();
        FreeAlliance second = freeAlliance();

        post200("/nation/join", first.leader(), new NationJoinReq(newRequestId(), k.nationA()));
        JsonNode root = postRaw("/nation/join", second.leader(),
                new NationJoinReq(newRequestId(), k.nationA()));

        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.NATION_ALLIANCE_FULL.code());
        assertThat(root.path("detail").asText()).contains("上限 2 个");
        assertThat(nationOf(k.nationA()).memberAllianceCount())
                .as("满了就是满了，不该有第三个挤进来").isEqualTo(2);
    }

    @Test
    @DisplayName("成员联盟解散 ⇒ 国家里不再挂着它：名额与议员席都跟着回落（B13 §二冲突规则）")
    void disbandingAMemberAllianceRemovesItFromTheNation() throws Exception {
        TwoKingdoms k = twoKingdoms();
        FreeAlliance f = freeAlliance();
        post200("/nation/join", f.leader(), new NationJoinReq(newRequestId(), k.nationA()));
        assertThat(nationOf(k.nationA()).memberAllianceCount()).isEqualTo(2);

        post200("/alliance/disband", f.leader(), new AllianceSelfReq(newRequestId()));

        com.ironoath.core.nation.Nation after = nationOf(k.nationA());
        assertThat(after.memberAllianceCount())
                .as("联盟已经不存在了，继续挂在成员表里等于永久占掉一个名额")
                .isEqualTo(1);
        assertThat(after.holdersOf(Office.REPRESENTATIVE))
                .as("一个已经不存在的联盟的盟主不该还坐在议员席上")
                .containsExactly(k.kingA());
        assertThat(after.hasAlliance(f.allianceId())).isFalse();
    }

    // ---------- 解散国家（B13 §1、§3 验收 5） ----------

    @Test
    @DisplayName("国王解散：成员联盟全部进冷却，而国库是「核销留痕」不是静默清零")
    void kingDisbandsAndTreasuryWriteOffLeavesEvidence() throws Exception {
        TwoKingdoms k = twoKingdoms();
        FreeAlliance f = freeAlliance();
        post200("/nation/join", f.leader(), new NationJoinReq(newRequestId(), k.nationA()));

        com.ironoath.core.nation.Nation before = nationOf(k.nationA());
        long treasury = before.treasury();
        int logsBefore = before.treasuryLogs().size();
        assertThat(treasury).as("解散前该有结清的周税，否则这条用例验不到「核销」").isPositive();

        JsonNode resp = post200("/nation/disband", k.kingA(), new NationDisbandReq(newRequestId()));
        assertThat(resp.get("nationId").asText()).isEqualTo(k.nationA());
        assertThat(resp.get("memberAllianceCount").asInt())
                .as("影响面按联盟数计（成员表的最小单位就是联盟）").isEqualTo(2);
        assertThat(resp.get("treasuryWrittenOff").asLong()).isEqualTo(treasury);

        com.ironoath.core.nation.Nation after = nationOf(k.nationA());
        assertThat(after.isDisbanded()).isTrue();
        assertThat(after.treasury()).isZero();
        assertThat(after.treasuryLogs()).as("公共资产不能静默消失：多出一条，且余额写的是 0")
                .hasSize(logsBefore + 1);
        com.ironoath.core.nation.Nation.TreasuryLog last =
                after.treasuryLogs().get(after.treasuryLogs().size() - 1);
        assertThat(last.operatorId()).as("谁下的令必须可查").isEqualTo(k.kingA());
        assertThat(last.payee()).as("这笔钱没有收款人，所以 payee 是用途标识而不是某个 id")
                .isEqualTo("disband_writeoff");
        assertThat(last.balanceAfter()).isZero();

        assertThat(postRaw("/nation/join", f.leader(),
                new NationJoinReq(newRequestId(), k.nationB())).get("code").asInt())
                .as("亡国不能成为跳边的绕道：解散写的冷却照样拦得住入籍")
                .isEqualTo(ErrorCode.NATION_JOIN_COOLDOWN.code());
        assertThat(perform(get("/nation").header(PLAYER_HEADER, k.mateA())).get("code").asInt())
                .as("原成员现在没有国籍（成员表随解散清空）").isEqualTo(ErrorCode.NATION_NOT_FOUND.code());

        // 与上一条不同的路径：一个从未入籍的联盟（身上没有冷却）去加一个亡国。
        // 少了 join 那侧的 isDisbanded 过滤，它会穿过"目标存在"检查、最后撞领域层的 requireActive，
        // 变成一个答非所问的 SYSTEM_ERROR —— 这条断言就是那个过滤的存在理由。
        FreeAlliance fresh = freeAlliance();
        assertThat(postRaw("/nation/join", fresh.leader(),
                new NationJoinReq(newRequestId(), k.nationA())).get("code").asInt())
                .as("已经不存在的东西不能被加入").isEqualTo(ErrorCode.NATION_NOT_FOUND.code());
    }

    @Test
    @DisplayName("「你不是国王」与「你根本没有国」分开答，而国家在两次失败之后原样存在")
    void disbandAuthorityFailuresAreDistinct() throws Exception {
        TwoKingdoms k = twoKingdoms();
        String loner = newPlayer(16);

        JsonNode inside = postRaw("/nation/disband", k.mateA(), new NationDisbandReq(newRequestId()));
        assertThat(inside.get("code").asInt())
                .as("他有国籍，缺的是国王身份 —— 下一步是去找国王")
                .isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.code());

        JsonNode nobody = postRaw("/nation/disband", loner, new NationDisbandReq(newRequestId()));
        assertThat(nobody.get("code").asInt())
                .as("他压根没有国 —— 下一步与上面那条完全不同，所以不能共用一个码")
                .isEqualTo(ErrorCode.NATION_NOT_FOUND.code());

        assertThat(nationOf(k.nationA()).isDisbanded()).as("两次被拒的解散都不该留下痕迹").isFalse();
        assertThat(nationOf(k.nationA()).treasury()).isPositive();
    }

    @Test
    @DisplayName("亡国不再被当作存在：不占本服国家名额，也不能被外交")
    void disbandedNationStopsCountingAsPresent() throws Exception {
        java.util.List<String> ids = foundedNations(4);
        Kingdom fifth = kingdom();
        assertThat(postRaw("/nation/found", fifth.king(),
                new NationFoundReq(newRequestId(), "挤不进", 900L, 900L)).get("code").asInt())
                .as("global.NATION_MAX_PER_KINGDOM=4，满了就该拒").isEqualTo(ErrorCode.NATION_CREATE_LIMIT.code());

        post200("/nation/disband", kingOf(ids.get(3)), new NationDisbandReq(newRequestId()));

        assertThat(post200("/nation/found", fifth.king(),
                new NationFoundReq(newRequestId(), "腾出位子了", 900L, 900L))
                .get("nation").get("nationId").asText())
                .as("亡国若仍被算作存在，一个死人就会永久吃掉四个名额之一").isNotBlank();

        JsonNode dip = postRaw("/nation/diplomacy", kingOf(ids.get(0)),
                new NationDiplomacyReq(newRequestId(), ids.get(3), DiplomacyRelation.HOSTILE));
        assertThat(dip.get("code").asInt())
                .as("对一个已经亡掉的国家宣布敌对，是一条指向虚空的关系").isEqualTo(ErrorCode.NATION_NOT_FOUND.code());

        JsonNode valid = post200("/nation/diplomacy", kingOf(ids.get(0)),
                new NationDiplomacyReq(newRequestId(), ids.get(1), DiplomacyRelation.HOSTILE));
        // 此时本服是 A、B、C 加第五个新建的国（D 已亡），所以可对手是 3 个而不是 4 个。
        // 数量断言与「两条都不指向 D」要一起写：只数数量，一个把亡国换成别的漏法照样能过
        java.util.List<String> listed = new java.util.ArrayList<>();
        valid.get("allRelations").forEach(row -> listed.add(row.get("nationId").asText()));
        assertThat(listed).as("外交面板里不该再有一个已经不存在的国家")
                .hasSize(3).doesNotContain(ids.get(3));
    }

    // ---------- 国库流水（B13 §3、验收 5） ----------

    @Test
    @DisplayName("验收5：国库流水对成员联盟的每个成员可见、最新一笔在前，且余额与流水自证连贯")
    void treasuryLedgerIsPublicToMembersAndSelfConsistent() throws Exception {
        TwoKingdoms k = twoKingdoms();
        FreeAlliance f = freeAlliance();
        post200("/nation/join", f.leader(), new NationJoinReq(newRequestId(), k.nationA()));

        // 一周只收一笔税，所以不推一周就永远只有一行 —— 而"最新在前"与"余额自洽"都要至少两行
        // 才验得出来。这里推的是存储层那把带时刻的入口（与 settleTax 用的同一个方法），
        // 国家还活着，所以后面走的仍是真端点而不是内部对象。
        nationStore.settleWeeklyTax(k.nationA(),
                System.currentTimeMillis() + 8L * 24 * 3600L * 1000L);

        JsonNode asKing = get200("/nation/treasury", k.kingA());
        JsonNode asMate = get200("/nation/treasury", k.mateA());
        JsonNode asJoinedLeader = get200("/nation/treasury", f.leader());

        long balance = asKing.get("balance").asLong();
        assertThat(balance).as("两笔周税入账").isPositive();
        // 防贪污的机制是「成员看得见」：后来入籍那个联盟的成员也必须读到同一本账
        assertThat(asJoinedLeader.get("balance").asLong()).isEqualTo(balance);
        assertThat(asMate.get("logs")).isEqualTo(asKing.get("logs"));
        assertThat(asJoinedLeader.get("logs")).isEqualTo(asKing.get("logs"));

        JsonNode logs = asKing.get("logs");
        assertThat(logs.size()).as("第一周 + 推到的那一周").isEqualTo(2);
        long sum = 0L;
        for (JsonNode row : logs) {
            assertThat(row.get("at").asLong()).as("何时").isPositive();
            assertThat(row.get("operatorId").asText()).as("谁（系统入账就是 system）").isEqualTo("system");
            assertThat(row.get("counterparty").asText()).as("支给谁 / 来自哪里").isEqualTo("weekly_tax");
            assertThat(row.get("reason").asText()).as("用途").contains("周税");
            sum += row.get("amount").asLong();
        }
        assertThat(sum).as("流水加总就该等于余额：对不上的那一段就是有人改过账本").isEqualTo(balance);
        assertThat(logs.get(0).get("balanceAfter").asLong())
                .as("倒序：第一行是最近一笔，它的 balanceAfter 就是当前余额")
                .isEqualTo(balance);
        assertThat(logs.get(1).get("balanceAfter").asLong()).isLessThan(balance);
    }

    @Test
    @DisplayName("读国库不该产生钱：没有国籍的人读不到，而读两次不会多收一笔税")
    void treasuryReadDoesNotMintMoney() throws Exception {
        TwoKingdoms k = twoKingdoms();
        long firstBalance = get200("/nation/treasury", k.kingA()).get("balance").asLong();

        JsonNode again = get200("/nation/treasury", k.kingA());
        assertThat(again.get("balance").asLong())
                .as("读一次收一遍税等于凭空造钱，而这条路径是纯读")
                .isEqualTo(firstBalance);
        assertThat(again.get("logs")).hasSize(1);

        String loner = newPlayer(16);
        JsonNode denied = perform(get("/nation/treasury").header(PLAYER_HEADER, loner));
        assertThat(denied.get("code").asInt())
                .as("不是本国成员就读不到（门槛就是成员关系，表里没有 VIEW_TREASURY 位）")
                .isEqualTo(ErrorCode.NATION_NOT_FOUND.code());
    }

    // ---------- 国库支出（B13 §3，2026-09-11 的落点裁决） ----------

    @Test
    @DisplayName("俸禄：扣国库、写日志、钱真的到玩家账上；日志四件套（谁/何时/支给谁/多少）齐全")
    void spendingSalaryDeductsLogsAndPays() throws Exception {
        TwoKingdoms k = twoKingdoms();
        long balanceBefore = get200("/nation/treasury", k.kingA()).get("balance").asLong();
        assertThat(balanceBefore).as("前置：结清周税后国库有钱可支").isPositive();
        long payeeGoldBefore = goldOf(k.mateA());

        JsonNode resp = post200("/nation/treasury/spend", k.kingA(), new NationTreasurySpendReq(
                newRequestId(), TreasuryPayeeType.PLAYER, k.mateA(), null, 500L, "本月官职俸禄"));

        assertThat(resp.get("balance").asLong())
                .as("国库真的少了这一笔").isEqualTo(balanceBefore - 500L);
        assertThat(resp.get("payee").asText())
                .as("落点写成 player:<id> 的形态，与流水那一列同源").isEqualTo("player:" + k.mateA());
        JsonNode log = resp.get("log");
        assertThat(log.get("operatorId").asText()).as("谁").isEqualTo(k.kingA());
        assertThat(log.get("counterparty").asText()).as("支给谁").isEqualTo("player:" + k.mateA());
        assertThat(log.get("amount").asLong()).as("多少").isEqualTo(500L);
        assertThat(log.get("reason").asText()).as("为什么").isEqualTo("本月官职俸禄");
        assertThat(log.get("balanceAfter").asLong()).isEqualTo(balanceBefore - 500L);
        assertThat(log.get("at").asLong()).as("何时").isPositive();

        assertThat(goldOf(k.mateA())).as("俸禄必须真的到账 —— 只记账不发钱就是把钱凭空吃掉")
                .isEqualTo(payeeGoldBefore + 500L);
        assertThat(nationOf(k.nationA()).treasury()).isEqualTo(balanceBefore - 500L);
    }

    @Test
    @DisplayName("消耗性用途：核销出库但没有收款人（日志写 sink:<用途>），谁的金币都不动")
    void spendingOnASinkWritesOffWithoutPayingAnyone() throws Exception {
        TwoKingdoms k = twoKingdoms();
        long balanceBefore = get200("/nation/treasury", k.kingA()).get("balance").asLong();
        long kingGoldBefore = goldOf(k.kingA());
        long mateGoldBefore = goldOf(k.mateA());

        JsonNode resp = post200("/nation/treasury/spend", k.kingA(), new NationTreasurySpendReq(
                newRequestId(), TreasuryPayeeType.SINK, null, TreasurySink.NATIONAL_TECH,
                300L, "研究国家科技·攻击"));

        assertThat(resp.get("payee").asText()).as("「没有收款人」也要显式写出来")
                .isEqualTo("sink:NATIONAL_TECH");
        assertThat(resp.get("balance").asLong()).isEqualTo(balanceBefore - 300L);
        assertThat(goldOf(k.kingA())).as("核销不是发钱：操作者自己也不该收到").isEqualTo(kingGoldBefore);
        assertThat(goldOf(k.mateA())).isEqualTo(mateGoldBefore);
    }

    @Test
    @DisplayName("只有国主能支取（权限表 WITHDRAW_TREASURY 只给 leader 档），成员被拒且国库不动")
    void onlyTheKingMaySpend() throws Exception {
        TwoKingdoms k = twoKingdoms();
        long balanceBefore = get200("/nation/treasury", k.kingA()).get("balance").asLong();

        JsonNode denied = postRaw("/nation/treasury/spend", k.mateA(), new NationTreasurySpendReq(
                newRequestId(), TreasuryPayeeType.PLAYER, k.mateA(), null, 500L, "自己给自己发俸禄"));
        assertThat(denied.get("code").asInt())
                .as("普通成员支取国库必须被拒（表里只给国主）")
                .isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.code());
        assertThat(get200("/nation/treasury", k.kingA()).get("balance").asLong())
                .as("被拒的支取不能留下任何痕迹").isEqualTo(balanceBefore);

        JsonNode outsider = postRaw("/nation/treasury/spend", newPlayer(16), new NationTreasurySpendReq(
                newRequestId(), TreasuryPayeeType.PLAYER, k.mateA(), null, 500L, "外人"));
        assertThat(outsider.get("code").asInt()).as("没有国籍更谈不上支取")
                .isEqualTo(ErrorCode.NATION_NOT_FOUND.code());
    }

    @Test
    @DisplayName("余额不足整笔拒绝（不做部分出账）；payeeId 与 sink 含糊时直接拒绝")
    void spendRejectsInsufficientBalanceAndAmbiguousPayee() throws Exception {
        TwoKingdoms k = twoKingdoms();
        long balanceBefore = get200("/nation/treasury", k.kingA()).get("balance").asLong();

        JsonNode tooMuch = postRaw("/nation/treasury/spend", k.kingA(), new NationTreasurySpendReq(
                newRequestId(), TreasuryPayeeType.PLAYER, k.mateA(), null,
                balanceBefore + 1L, "超出余额一分"));
        assertThat(tooMuch.get("code").asInt()).isEqualTo(ErrorCode.NATION_TREASURY_NOT_ENOUGH.code());
        assertThat(get200("/nation/treasury", k.kingA()).get("balance").asLong())
                .as("失败的那一笔不能扣钱、也不该留日志").isEqualTo(balanceBefore);

        // 含糊的支给对象：PLAYER 却没给 payeeId
        JsonNode noPayee = postRaw("/nation/treasury/spend", k.kingA(), new NationTreasurySpendReq(
                newRequestId(), TreasuryPayeeType.PLAYER, null, null, 100L, "含糊"));
        assertThat(noPayee.get("code").asInt()).as("PLAYER 必须且只能给 payeeId")
                .isEqualTo(ErrorCode.PARAM_INVALID.code());
        // 给了两个落点同样拒绝：日志只能有一种解释
        JsonNode both = postRaw("/nation/treasury/spend", k.kingA(), new NationTreasurySpendReq(
                newRequestId(), TreasuryPayeeType.SINK, k.mateA(), TreasurySink.WAR_BOOST, 100L, "两个都填"));
        assertThat(both.get("code").asInt()).as("sink 与 payeeId 只能出现一个")
                .isEqualTo(ErrorCode.PARAM_INVALID.code());
        // 收款玩家不存在：记在一个不存在的 id 上等于这笔钱没有收款人，而日志却写着有
        JsonNode ghost = postRaw("/nation/treasury/spend", k.kingA(), new NationTreasurySpendReq(
                newRequestId(), TreasuryPayeeType.PLAYER, "P_ghost", null, 100L, "给一个不存在的人"));
        assertThat(ghost.get("code").asInt()).isEqualTo(ErrorCode.PLAYER_NOT_FOUND.code());
        assertThat(get200("/nation/treasury", k.kingA()).get("balance").asLong())
                .as("三次被拒都不该动账").isEqualTo(balanceBefore);
    }

    /** 某个玩家当前的金币。读存档而不是看响应 —— 用例要的是「钱真的进了他的账」。 */
    private long goldOf(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow().resource("GOLD").current();
    }

    // ---------- 外交（B13 §5） ----------

    @Test
    @DisplayName("外交关系不是装饰性标签：改成盟约后 mayAttackNation 立刻拒绝，改成敌对后放行")
    void diplomacyChangesWhoCanAttackWhom() throws Exception {
        TwoKingdoms k = twoKingdoms();

        JsonNode resp = post200("/nation/diplomacy", k.kingA(),
                new NationDiplomacyReq(newRequestId(), k.nationB(), DiplomacyRelation.ALLIED));
        assertThat(resp.get("targetNationId").asText()).isEqualTo(k.nationB());
        assertThat(resp.get("relation").asText()).isEqualTo("ALLIED");
        assertThat(resp.get("allRelations").size()).as("回整张表，省掉客户端再查一次").isEqualTo(1);
        assertThat(resp.get("allRelations").get(0).get("nationName").asText()).isNotBlank();

        assertThat(nationOf(k.nationA()).mayAttackNation(k.nationB()))
                .as("盟约之间不能互相攻击").isFalse();

        post200("/nation/diplomacy", k.kingA(),
                new NationDiplomacyReq(newRequestId(), k.nationB(), DiplomacyRelation.HOSTILE));
        assertThat(nationOf(k.nationA()).mayAttackNation(k.nationB()))
                .as("敌对之间才可以进攻：关系一变，谁能打谁立刻跟着变").isTrue();
    }

    @Test
    @DisplayName("权限走表：普通成员改不了外交（MANAGE_DIPLOMACY 只给国王与外交官档）")
    void memberCannotChangeDiplomacy() throws Exception {
        TwoKingdoms k = twoKingdoms();
        boolean before = nationOf(k.nationA()).mayAttackNation(k.nationB());
        JsonNode root = postRaw("/nation/diplomacy", k.mateA(),
                new NationDiplomacyReq(newRequestId(), k.nationB(), DiplomacyRelation.HOSTILE));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.code());
        assertThat(root.path("detail").asText())
                .as("缺哪个权限位是查表得出的，不能硬编码在文案里").contains("MANAGE_DIPLOMACY");
        assertThat(nationOf(k.nationA()).mayAttackNation(k.nationB()))
                .as("被拒的变更不该生效：关系必须与调用前完全一致").isEqualTo(before);
    }

    @Test
    @DisplayName("不能与自己建立外交关系：与自己敌对会让 mayAttackNation 拒绝一切进攻，等于自废武功")
    void cannotDeclareDiplomacyTowardSelf() throws Exception {
        TwoKingdoms k = twoKingdoms();
        JsonNode root = postRaw("/nation/diplomacy", k.kingA(),
                new NationDiplomacyReq(newRequestId(), k.nationA(), DiplomacyRelation.HOSTILE));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.PARAM_INVALID.code());
    }

    @Test
    @DisplayName("目标国家不存在时被拒，而不是静默写下一条指向虚空的关系")
    void diplomacyTowardUnknownNationIsRejected() throws Exception {
        TwoKingdoms k = twoKingdoms();
        JsonNode root = postRaw("/nation/diplomacy", k.kingA(),
                new NationDiplomacyReq(newRequestId(), "nation_nobody", DiplomacyRelation.ALLIED));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.NATION_NOT_FOUND.code());
    }

    private record TwoKingdoms(String kingA, String mateA, String nationA,
                               String kingB, String nationB) {
    }

    /** 两个各自建了国的联盟。国名与联盟名都带序号，避免同一条用例里撞名。 */
    private TwoKingdoms twoKingdoms() throws Exception {
        Kingdom a = kingdom();
        String nationA = post200("/nation/found", a.king(),
                new NationFoundReq(newRequestId(), "东方王国", 100L, 200L))
                .get("nation").get("nationId").asText();
        Kingdom b = kingdom();
        String nationB = post200("/nation/found", b.king(),
                new NationFoundReq(newRequestId(), "西方王国", 300L, 400L))
                .get("nation").get("nationId").asText();
        return new TwoKingdoms(a.king(), a.mate(), nationA, b.king(), nationB);
    }

    private com.ironoath.core.nation.Nation nationOf(String nationId) {
        return nationStore.findById(nationId).orElseThrow();
    }

    /**
     * 建 n 个各自建了国的联盟，返回国家 id。
     * 名额类用例（global.NATION_MAX_PER_KINGDOM）需要"把名额占满"这个前提，
     * 而这个前提只能靠真建出来 —— 伪造一个"已有四国"的状态会让用例验的是夹具而不是规则。
     */
    private java.util.List<String> foundedNations(int count) throws Exception {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            Kingdom k = kingdom();
            ids.add(post200("/nation/found", k.king(),
                    new NationFoundReq(newRequestId(), "计数国" + i, 100L + i, 200L))
                    .get("nation").get("nationId").asText());
        }
        return ids;
    }

    /** 这个国家此刻的国王。从存储读而不是从夹具记 —— 用例要的是"谁是国王"这个当前事实。 */
    private String kingOf(String nationId) {
        return nationOf(nationId).kingId();
    }

    // ---------- 外交关系立刻改变谁能打谁（B13 验收 12、禁止项） ----------

    /**
     * 攻击闸门给出的答案（业务码）。直接调 {@link AttackGuardService} 而不是走 /march：
     * 本类要验的是"外交这一层到底有没有生效"，而行军入口还会先撞上编队、兵力、体力那些
     * 与本题无关的前置。返回 {@code OK} 表示闸门放行了这一仗。
     */
    private ErrorCode attackFailure(String attacker, String target) {
        try {
            guard.guard(attacker, worlds.homeOf(target), timeService.serverNow());
            return ErrorCode.OK;
        } catch (BizException e) {
            return e.errorCode();
        }
    }

    @Test
    @DisplayName("验收12：盟约一宣布就双向打不动，改成敌对才放行 —— 单方面宣布不等于只对宣布方生效")
    void treatyBlocksBothDirectionsAndHostilityReleasesIt() throws Exception {
        TwoKingdoms k = twoKingdoms();

        assertThat(attackFailure(k.kingA(), k.kingB()))
                .as("中立可被宣战：没结盟之前不该以外交为由拒绝")
                .isNotEqualTo(ErrorCode.NATION_TREATY_PROTECTED);

        post200("/nation/diplomacy", k.kingA(),
                new NationDiplomacyReq(newRequestId(), k.nationB(), DiplomacyRelation.ALLIED));

        assertThat(attackFailure(k.kingA(), k.kingB()))
                .as("宣布方自己当然打不动")
                .isEqualTo(ErrorCode.NATION_TREATY_PROTECTED);
        // 这一条才是"双向"的证据：关系只记在宣布方那一侧，如果闸门只查攻方，
        // B 会在 A 宣布和平之后立刻把 A 抢一遍 —— 那等于对 B 而言这条盟约不存在
        assertThat(attackFailure(k.kingB(), k.kingA()))
                .as("被宣布盟约的那一方同样打不动：盟约是互相的，不是单方声明")
                .isEqualTo(ErrorCode.NATION_TREATY_PROTECTED);

        post200("/nation/diplomacy", k.kingB(),
                new NationDiplomacyReq(newRequestId(), k.nationA(), DiplomacyRelation.HOSTILE));
        // 双向生效的代价：B 单方面改成敌对**撕不开** A 记着的那份盟约，两个方向仍然打不动。
        // 要放行得由 A 自己也改。这等于"宣布和平就给自己加了一块免战牌"，是一条玩法后果，
        // 已记进收口清单等裁决；这里先如实钉住当前行为，而不是挑一个方向假装它不存在。
        assertThat(attackFailure(k.kingA(), k.kingB()))
                .as("一侧的盟约约束两侧：B 单方面改敌对撕不开")
                .isEqualTo(ErrorCode.NATION_TREATY_PROTECTED);
        assertThat(attackFailure(k.kingB(), k.kingA()))
                .isEqualTo(ErrorCode.NATION_TREATY_PROTECTED);

        post200("/nation/diplomacy", k.kingA(),
                new NationDiplomacyReq(newRequestId(), k.nationB(), DiplomacyRelation.HOSTILE));
        assertThat(attackFailure(k.kingA(), k.kingB()))
                .as("两边都改成敌对之后才真的放行 —— 外交这一次是真的在改谁能打谁")
                .isNotEqualTo(ErrorCode.NATION_TREATY_PROTECTED);
        assertThat(attackFailure(k.kingB(), k.kingA()))
                .isNotEqualTo(ErrorCode.NATION_TREATY_PROTECTED);
    }

    @Test
    @DisplayName("口径裁决：同国不可互攻，而无国籍玩家不受这条规则管辖")
    void sameNationAttacksAreRefusedWhileStatelessPlayersAreNot() throws Exception {
        TwoKingdoms k = twoKingdoms();
        String loner = newPlayer(16);

        // 同国此前是被放行的 —— 但那不是决定，只是领域层「未登记关系算中立」的默认值被顺带当成答案。
        // 2026-09-11 裁决：国家内部不是无政府状态，国战的对手只能是别的国。
        assertThat(attackFailure(k.kingA(), k.mateA()))
                .as("同胞之间打不了，而且是独立的码：提示要说清是同国，不能套一句「有约在先」")
                .isEqualTo(ErrorCode.NATION_SAME_KINGDOM);
        assertThat(attackFailure(k.mateA(), k.kingA()))
                .as("这条与登记的外交关系无关，两个方向同样成立")
                .isEqualTo(ErrorCode.NATION_SAME_KINGDOM);

        assertThat(attackFailure(k.kingA(), loner))
                .as("对方没有国家：这条规则管的是国家之间与国家内部的关系")
                .isNotEqualTo(ErrorCode.NATION_SAME_KINGDOM);
    }

    @Test
    @DisplayName("B13 §3 验收5：建国当周就结清，而同一周再看多少次都不再涨")
    void weeklyTaxSettlesOnViewButOnlyOnce() throws Exception {
        TwoKingdoms k = twoKingdoms();
        com.ironoath.core.nation.Nation atFound = nationOf(k.nationA());
        long expected = atFound.rules().taxWeeklyPerAlliance() * atFound.memberAllianceCount();
        assertThat(atFound.treasury())
                .as("建完国立刻就该有本周的税。原先 found() 在 save 之前就调结算，"
                        + "而结算改的是「已经在册」的对象 —— 那一笔静默为 0，且旧的 settleTax 返回 void "
                        + "所以没有任何一处能看出来")
                .isEqualTo(expected);

        // 每次断言都要重读：存储返回的是副本，手里那份不会跟着后面的结算变
        // （这正是本轮改掉的那个"活对象"依赖 —— 以前它让这条用例看着比实际可信）
        get200("/nation", k.kingA);
        assertThat(nationOf(k.nationA()).treasury())
                .as("玩家看到的国库数就该是当下的数，而当下这个数已经结清了")
                .isEqualTo(expected);

        get200("/nation", k.kingA);
        get200("/nation", k.kingA);
        assertThat(nationOf(k.nationA()).treasury())
                .as("同一个周键只收一次：B13 的税是按周的，读三次收三遍等于凭空造钱")
                .isEqualTo(expected);
    }

    // ---------- 夹具 ----------

    private record Kingdom(String king, String mate) {
    }

    /** 一个还没有属于任何国家的联盟：盟主 + 一名成员 + 联盟 id。 */
    private record FreeAlliance(String leader, String mate, String allianceId) {
    }

    /**
     * 造一个"未入籍"的联盟。入籍与退出国那组用例的前提就是它 ——
     * 每个联盟一建出来就属于某个国家的话，"加入第二个国家"这条路径根本没有输入。
     */
    private FreeAlliance freeAlliance() throws Exception {
        String leader = newPlayer(16);
        String mate = newPlayer(16);
        String allianceId = createAlliance(leader, mate);
        return new FreeAlliance(leader, mate, allianceId);
    }

    /** 一个已建国的两人联盟：国王 + 一名成员。 */
    private Kingdom kingdom() throws Exception {
        String king = newPlayer(16);
        String mate = newPlayer(16);
        createAlliance(king, mate);
        return new Kingdom(king, mate);
    }

    private String createAlliance(String king, String mate) throws Exception {
        // 名字与标签必须唯一：同一条用例里常常要建两个联盟（例如「另一个国家的成员」），
        // 用固定名字的话第二个会在 ALLIANCE_NAME_TAKEN 上失败，
        // 而报错指向的是夹具、不是被测逻辑
        allianceSeq++;
        String allianceId = post200("/alliance/create", king,
                new AllianceCreateReq(newRequestId(), "建国联盟" + allianceSeq,
                        String.format("F%03d", allianceSeq % 1000)))
                .get("alliance").get("id").asText();
        post200("/alliance/apply", mate, new AllianceIdReq(newRequestId(), allianceId));
        post200("/alliance/review", king, new AllianceReviewReq(newRequestId(), mate, true));
        return allianceId;
    }

    private static BotProfile botProfile(String botId) {
        return new BotProfile(botId, "bot_linju",
                new BotProfile.AiProfile(FixedPoint.parse("0.50"), FixedPoint.parse("0.50"),
                        FixedPoint.parse("0.50"), FixedPoint.parse("0.60")),
                new BotProfile.Persona(42L, 7L, 99L, List.of(12, 13, 20, 21, 22),
                        3L, 30L, FixedPoint.parse("0.10")),
                FixedPoint.parse("1.0"));
    }

    /** 建国要主城 16 级、建盟要 500 金币（真实扣款），两样都在夹具里备好。 */
    private String newPlayer(int cityLevel) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "国家测试", 1_700_000_000_000L))
                .playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(cityLevel);
        PlayerResourceState gold = save.resources().get("GOLD");
        if (gold != null) {
            save.putResource("GOLD", new PlayerResourceState(
                    100_000L, gold.cap(), gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        }
        players.save(save);
        // 开服天数门槛不靠这里控制：SERVER_OPEN_AT 未配置时 NationAppService 直接放行
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
        return okData(perform(get(url).header(PLAYER_HEADER, playerId)));
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
