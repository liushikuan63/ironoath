package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
import com.ironoath.common.time.TimeService;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.DiplomacyRelation;
import com.ironoath.web.dto.generated.NationDiplomacyReq;
import com.ironoath.web.dto.generated.NationDisbandReq;
import com.ironoath.web.dto.generated.NationFoundReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.WarDeclareReq;
import com.ironoath.web.dto.generated.WarPhase;
import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.nation.WarStore;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：{@code GET /nation/war}（B13 国战承载切片 1）的端到端验证。
 * 依赖：Spring Boot Test + MockMvc；test profile（内存存储）。
 *
 * <p><b>本类要证的不是「有这一个 200」，而是这条读链路真的走通了存储</b>：
 * 一个恒回零值的实现与一个真读了 {@code WarStore} 的实现，在空存储上给出的响应一模一样。
 * 所以 {@link #warBoardReadsThroughTheStoreOnEveryDimension} 先<b>往存储里放一块有真实状态的板子</b>，
 * 再断言视图里的积分三项、占领者、击杀数、目标进度<b>逐项等于那块板子</b> ——
 * 把 {@code WarAppService} 里的任何一处取值改回常量，对应那条就红。
 *
 * <p><b>身份不是装饰</b>：{@link #fatigueAndMarchGateArePerPlayer} 用两个玩家各读一次，
 * 断言拿到的是<b>各自</b>的疲劳与闸门。漏用 {@code playerId} 的表现是把甲的疲劳显示成乙的，
 * 而乙会以为自己还能再派一批 —— 那正是 {@code check-identity-used.sh} 拦不住的那一层
 * （它只看方法体里有没有出现这个名字，不看是不是真按这个人取）。
 *
 * <p><b>国名必须服务端下发</b>：视图里每行都带 {@code nationName}，查不到国家的那一行给 null
 * 而不是裸 id（客户端据此显示回退语）。这一条是 B13 的红线，也是本仓「印内部 id 给玩家」那一族事故。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WarEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final long MINUTE = 60_000L;

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private SocialStore socialStore;
    @Autowired private WarStore wars;
    @Autowired private WarRulesAssembler warRules;
    @Autowired private NationStore nations;
    @Autowired private NationRulesAssembler nationRules;
    @Autowired private TimeService timeService;

    /** 联盟名/标签的序号（@BeforeEach 重置）：同一条用例里建两个联盟时，固定名字会在第二个上撞名。 */
    private int allianceSeq;

    @BeforeEach
    void resetStores() {
        wars.clear();
        nations.clear();
        socialStore.clear();
        ((InMemoryPlayerStore) players).clear();
        allianceSeq = 0;
    }

    // ---------- 空态 ----------

    @Test
    @DisplayName("还没有任何写入路径：视图老实回 hasWar=false，并把上下界照常下发")
    void noWarYetReadsAsEmptyButStillSendsBounds() throws Exception {
        JsonNode data = get200("/nation/war", newPlayer());

        assertThat(data.get("hasWar").asBoolean())
                .as("本切片没有开战路径，恒空是**已知的未做**，不是这一格交付的功能")
                .isFalse();
        // 无战事时那三项没有真值可给：填 0 会被读成「1970 年开过一场仗」，所以必须是 null
        assertThat(data.get("phase").isNull()).as("phase 没有真值，不许拿 SETTLED 凑").isTrue();
        assertThat(data.get("startedAt").isNull()).isTrue();
        assertThat(data.get("capitalHolder").isNull()).isTrue();
        assertThat(data.get("capitalHolderName").isNull()).isTrue();
        assertThat(data.get("scores")).isEmpty();
        assertThat(data.get("remainingSec").asLong()).isZero();
        assertThat(data.get("totalKills").asLong()).isZero();
        // 上下界与目标值仍然要下发：客户端不抄配置表，面板要写「0 / 50000」「疲劳 0 / 100」
        assertThat(data.get("gateCount").asInt()).isEqualTo(4);
        assertThat(data.get("fatigueMax").asLong()).isEqualTo(100L);
        assertThat(data.get("serverGoalKills").asLong()).isEqualTo(50_000L);
        assertThat(data.get("myFatigue").asLong()).isZero();
        assertThat(data.get("canMarch").asBoolean())
                .as("没有仗就没有那道闸；「有没有仗」由 hasWar 单独说")
                .isTrue();
        assertThat(data.get("serverNow").asLong()).isPositive();
    }

    @Test
    @DisplayName("缺身份头必须被拒：这一格虽然读的是全服数据，身份仍然是疲劳那两列的键")
    void blankPlayerHeaderIsRejected() throws Exception {
        JsonNode root = perform(get("/nation/war").header(PLAYER_HEADER, "  "));

        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.PLAYER_NOT_FOUND.code());
    }

    // ---------- 真读了存储 ----------

    @Test
    @DisplayName("视图逐项等于存进去的那块板：阶段、三类积分、占领者、击杀、全服目标、剩余时间")
    void warBoardReadsThroughTheStoreOnEveryDimension() throws Exception {
        String playerId = newPlayer();
        long startedAt = timeService.serverNow() - MINUTE;
        nations.insertIfAbsent(nation("n_live", "铁誓王国"));
        wars.insertIfAbsent(board(startedAt, 80L));

        JsonNode data = get200("/nation/war", playerId);
        assertThat(data.get("hasWar").asBoolean()).isTrue();
        assertThat(data.get("phase").asText()).isEqualTo("SIEGE");
        assertThat(data.get("startedAt").asLong()).isEqualTo(startedAt);
        assertThat(data.get("totalKills").asLong()).isEqualTo(60_120L);
        assertThat(data.get("serverGoalReached").asBoolean())
                .as("60120 ≥ 50000 —— 达成判定在服务端算，客户端只读结果")
                .isTrue();
        // 剩余时间：startedAt 是「一分钟前」，3 小时时长 ⇒ 落在 10735~10740 之间。
        // 写成区间而不是等号：service 里那次 serverNow() 与本行取时刻之间会流逝毫秒
        assertThat(data.get("remainingSec").asLong())
                .as("读了 startedAt 才会是这个数；没读会是 10800 或 0")
                .isBetween(10_735L, 10_740L);

        JsonNode rows = data.get("scores");
        assertThat(rows).hasSize(2);
        JsonNode live = rowOf(rows, "n_live");
        assertThat(live.get("nationName").asText())
                .as("国名服务端下发（客户端不抄表，也不许自己拼）")
                .isEqualTo("铁誓王国");
        assertThat(live.get("occupyScore").asLong()).isZero();
        assertThat(live.get("killScore").asLong()).isEqualTo(60_000L);
        assertThat(live.get("buildingScore").asLong()).isEqualTo(100L);
        assertThat(live.get("totalScore").asLong())
                .as("合计必须等于三项之和，客户端不需要自己加")
                .isEqualTo(60_100L);
        assertThat(live.get("gatesHeld").asInt()).isEqualTo(1);
        assertThat(live.get("attackQualified").asBoolean()).isTrue();

        JsonNode ghost = rowOf(rows, "n_ghost");
        assertThat(ghost.get("nationId").asText()).isEqualTo("n_ghost");
        assertThat(ghost.get("nationName").isNull())
                .as("国家可能在战争进行中被解散：查不到就给 null，客户端给回退语，**绝不回落到裸 id**")
                .isTrue();
        assertThat(ghost.get("killScore").asLong()).isEqualTo(120L);

        assertThat(data.get("capitalHolder").asText()).isEqualTo("n_live");
        assertThat(data.get("capitalHolderName").asText()).isEqualTo("铁誓王国");
    }

    @Test
    @DisplayName("占领者换人后视图跟着变：证明读的是当前状态而不是建档那一刻")
    void capitalHolderFollowsTheStoredBoard() throws Exception {
        String playerId = newPlayer();
        long startedAt = timeService.serverNow() - 2 * MINUTE;
        nations.insertIfAbsent(nation("n_live", "铁誓王国"));
        nations.insertIfAbsent(nation("n_ghost", "无名之国"));
        WarScoreBoard board = board(startedAt, 80L);
        wars.insertIfAbsent(board);
        assertThat(get200("/nation/war", playerId).get("capitalHolder").asText())
                .as("前置：先占着王城的是 n_live").isEqualTo("n_live");

        WarScoreBoard again = wars.findLatest().orElseThrow();
        again.captureCapital("n_ghost", timeService.serverNow());
        wars.save(again);

        JsonNode data = get200("/nation/war", playerId);
        assertThat(data.get("capitalHolder").asText()).isEqualTo("n_ghost");
        assertThat(data.get("capitalHolderName").asText())
                .as("换人之后名字跟着换，而且仍然是服务端下发的那一个")
                .isEqualTo("无名之国");
    }

    @Test
    @DisplayName("疲劳与行军闸门按人给：两个玩家各读一次拿到的是各自的那一份")
    void fatigueAndMarchGateArePerPlayer() throws Exception {
        String light = newPlayer();
        String spent = newPlayer();
        nations.insertIfAbsent(nation("n_live", "铁誓王国"));
        nations.insertIfAbsent(nation("n_ghost", "无名之国"));
        WarScoreBoard board = board(timeService.serverNow() - MINUTE, 0L);
        board.addFatigue(light, 15L, 5L);   // 15×5 + 5 = 80 < 100
        board.addFatigue(spent, 25L, 0L);   // 125 → 夹到上限 100
        wars.insertIfAbsent(board);

        JsonNode mine = get200("/nation/war", light);
        assertThat(mine.get("myFatigue").asLong()).isEqualTo(80L);
        assertThat(mine.get("canMarch").asBoolean()).isTrue();

        JsonNode theirs = get200("/nation/war", spent);
        assertThat(theirs.get("myFatigue").asLong())
                .as("漏用 playerId 的表现是把甲的疲劳显示成乙的，而乙会以为自己还能再派一批")
                .isEqualTo(100L);
        assertThat(theirs.get("canMarch").asBoolean())
                .as("验收 7：到顶之后不能再行军，判定在服务端一处")
                .isFalse();
        // 全服那一半不因身份而变
        assertThat(theirs.get("totalKills").asLong()).isEqualTo(mine.get("totalKills").asLong());
    }

    // ---------- 宣战（切片 2a）----------

    @Test
    @DisplayName("宣战：开出 PREPARATION 的一场，两行参战国都带服务端下发的国名，并把关系转成敌对")
    void declaringOpensTheWarAndFlipsTheRelation() throws Exception {
        Kingdom a = kingdom("铁誓");
        Kingdom b = kingdom("赤原");

        JsonNode data = post200("/nation/war/declare", a.king,
                new WarDeclareReq(newRequestId(), b.nationId));

        assertThat(data.get("hasWar").asBoolean()).isTrue();
        assertThat(data.get("phase").asText()).isEqualTo("PREPARATION");
        assertThat(data.get("remainingSec").asLong())
                .as("剩余时间只在 SIEGE 阶段倒数；这里必须是 0 而不是把 3 小时直接印出来")
                .isZero();
        assertThat(data.get("scores")).hasSize(2);
        assertThat(rowOf(data.get("scores"), a.nationId).get("nationName").asText())
                .as("参战方那一行给的是服务端下发的国名（客户端不抄表）").isEqualTo("铁誓");
        assertThat(rowOf(data.get("scores"), b.nationId).get("nationName").asText())
                .isEqualTo("赤原");
        assertThat(data.get("totalKills").asLong())
                .as("击杀累计属切片 2b：宣完战就是零分。这条断言把「宣战 ≠ 国战能玩」钉进用例里，"
                        + "下一格接击杀时必须改它，届时不会没人发现")
                .isZero();

        assertThat(nations.findById(a.nationId).orElseThrow().diplomacyWith(b.nationId))
                .as("Nation.mayAttackNation 的注释里那句「宣战后转为敌对」从交付起没人执行过，这一格是它的执行点")
                .isEqualTo(Nation.Diplomacy.HOSTILE);
    }

    @Test
    @DisplayName("没有 DECLARE_WAR 的人不能宣战：同国的普通成员被拒，且没有开出任何仗")
    void memberWithoutDeclarePermissionCannotOpenAWar() throws Exception {
        Kingdom a = kingdom("铁誓");
        Kingdom b = kingdom("赤原");

        JsonNode root = postRaw("/nation/war/declare", a.mate,
                new WarDeclareReq(newRequestId(), b.nationId));

        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.code());
        assertThat(wars.findLatest())
                .as("被拒的那一次不许留下半个写入：仗根本没开起来")
                .isEmpty();
        assertThat(nations.findById(a.nationId).orElseThrow().diplomacyWith(b.nationId))
                .as("关系也不许被顺手改掉")
                .isEqualTo(Nation.Diplomacy.NEUTRAL);
    }

    @Test
    @DisplayName("不能对本国宣战；也不许把不存在的国家打成参战方")
    void selfAndMissingTargetsAreRefused() throws Exception {
        Kingdom a = kingdom("铁誓");

        assertThat(postRaw("/nation/war/declare", a.king,
                new WarDeclareReq(newRequestId(), a.nationId)).get("code").asInt())
                .as("打自己是入参错误，不是「国战业务」错误 —— 它不该占用 WAR_* 那几个码")
                .isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(postRaw("/nation/war/declare", a.king,
                new WarDeclareReq(newRequestId(), "n-never-exists")).get("code").asInt())
                .isEqualTo(ErrorCode.WAR_TARGET_NATION_NOT_FOUND.code());
        assertThat(wars.findLatest()).isEmpty();
    }

    @Test
    @DisplayName("已解散的国家不能当宣战目标：档还在（供审计），但不能再被拖进一场仗")
    void disbandedNationCannotBeDeclaredOn() throws Exception {
        Kingdom a = kingdom("铁誓");
        Kingdom b = kingdom("赤原");
        post200("/nation/disband", b.king, new NationDisbandReq(newRequestId()));

        assertThat(postRaw("/nation/war/declare", a.king,
                new WarDeclareReq(newRequestId(), b.nationId)).get("code").asInt())
                .as("少了 isDisbanded 那道 filter，这里会开出一场打空国的仗，而积分板上永远挂着一行查不到名字的参战国")
                .isEqualTo(ErrorCode.WAR_TARGET_NATION_NOT_FOUND.code());
        assertThat(wars.findLatest()).isEmpty();
    }

    @Test
    @DisplayName("盟约挡宣战（B13 冲突规则 4：外交关系优先）")
    void alliedNationCannotBeDeclaredOn() throws Exception {
        Kingdom a = kingdom("铁誓");
        Kingdom b = kingdom("赤原");
        // C21：盟约要两侧各自宣布才成立，所以两边都说一次 ALLIED
        post200("/nation/diplomacy", a.king,
                new NationDiplomacyReq(newRequestId(), b.nationId, DiplomacyRelation.ALLIED));
        post200("/nation/diplomacy", b.king,
                new NationDiplomacyReq(newRequestId(), a.nationId, DiplomacyRelation.ALLIED));

        assertThat(postRaw("/nation/war/declare", a.king,
                new WarDeclareReq(newRequestId(), b.nationId)).get("code").asInt())
                .isEqualTo(ErrorCode.WAR_TARGET_DIPLOMACY_BLOCKED.code());
        assertThat(wars.findLatest()).isEmpty();
    }

    @Test
    @DisplayName("同时只有一场仗：第三国再宣战被拒（错误码而不是静默开出第二场）")
    void secondWarWhileOneIsActiveIsRefused() throws Exception {
        Kingdom a = kingdom("铁誓");
        Kingdom b = kingdom("赤原");
        Kingdom c = kingdom("苍梧");
        post200("/nation/war/declare", a.king, new WarDeclareReq(newRequestId(), b.nationId));

        assertThat(postRaw("/nation/war/declare", c.king,
                new WarDeclareReq(newRequestId(), b.nationId)).get("code").asInt())
                .as("两个国王各自握着按玩家分的锁，这里必须靠存储层的临界区而不是靠锁")
                .isEqualTo(ErrorCode.WAR_ALREADY_ACTIVE.code());

        WarScoreBoard live = wars.findLatest().orElseThrow();
        assertThat(live.registeredNations())
                .as("被拒的那一次不许往第一场里塞第三个参战国")
                .containsExactlyInAnyOrder(a.nationId, b.nationId);
    }

    @Test
    @DisplayName("幂等：缺 requestId 被拒；同一个 requestId 重放不产生第二场，也不重翻关系")
    void declareIsIdempotentOnRequestId() throws Exception {
        Kingdom a = kingdom("铁誓");
        Kingdom b = kingdom("赤原");

        assertThat(postRaw("/nation/war/declare", a.king,
                new WarDeclareReq("  ", b.nationId)).get("code").asInt())
                .isEqualTo(ErrorCode.REQUEST_ID_MISSING.code());

        String requestId = newRequestId();
        assertThat(post200("/nation/war/declare", a.king,
                new WarDeclareReq(requestId, b.nationId)).get("hasWar").asBoolean()).isTrue();
        long startedAt = wars.findLatest().orElseThrow().startedAt();

        assertThat(postRaw("/nation/war/declare", a.king,
                new WarDeclareReq(requestId, b.nationId)).get("code").asInt())
                .as("重放必须挡在门口，而不是靠「已有仗」那条兜住 —— 后者会把「你重复提交了」报成「仗还在打」")
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED.code());
        assertThat(wars.findLatest().orElseThrow().startedAt())
                .as("重放不许开出第二场，也不许挪走第一场的起点")
                .isEqualTo(startedAt);
    }

    // ---------- 协议与内核同源 ----------

    @Test
    @DisplayName("协议 WarPhase 与内核 WarScoreBoard.Phase 常量完全一致（含顺序）")
    void warPhaseMatchesTheDomainEnum() {
        List<String> contract = Arrays.stream(WarPhase.values()).map(Enum::name).toList();
        List<String> core = Arrays.stream(WarScoreBoard.Phase.values()).map(Enum::name).toList();
        assertThat(contract)
                .as("两份枚举一旦分家，症状是服务端下发的阶段客户端解析不出来（TS 侧变 undefined）。"
                        + "而 WarAppService 用的是 valueOf(name())，那里会抛而不是静默换阶段 —— "
                        + "本用例负责让分家在编译前就响")
                .containsExactlyElementsOf(core);
    }

    // ---------- 夹具 ----------

    /**
     * 一块把视图各维都填满的板子。
     *
     * <p>两个参战方刻意<b>不对称</b>：n_live 击杀 60000、n_ghost 击杀 120，占领分都是 0，
     * 建筑分 100 与 50 —— 对称的数值会让「读串了行」这类错误看不见。
     * {@code n_ghost} 刻意<b>不在国家存储里</b>，用来走「查不到国名给 null」那一支。
     *
     * @param startedAt 开战时刻（调用方按当前时刻倒推，好让剩余秒数落在可断言的区间）
     * @param myFatigue 顺带给请求者的一点疲劳（0 时不影响空态用例）
     */
    private WarScoreBoard board(long startedAt, long myFatigue) {
        WarScoreBoard board = new WarScoreBoard(warRules.rules(), startedAt);
        board.registerNation("n_live");
        board.registerNation("n_ghost");
        board.captureGate("n_live", "gate_1");
        board.captureGate("n_ghost", "gate_2");
        board.beginSiege(startedAt + MINUTE / 2);
        board.captureCapital("n_live", startedAt + MINUTE);
        board.recordKill("n_live", 60_000L);
        board.recordKill("n_ghost", 120L);
        if (myFatigue > 0) {
            board.addFatigue("someone-else", myFatigue / 5L, myFatigue % 5L);
        }
        return board;
    }

    private Nation nation(String id, String name) {
        return Nation.found(id, name, "K-" + id, "AL-" + id, 100L, 200L,
                timeService.serverNow(), nationRules.rules());
    }

    private static JsonNode rowOf(JsonNode rows, String nationId) {
        for (JsonNode row : rows) {
            if (nationId.equals(row.get("nationId").asText())) {
                return row;
            }
        }
        throw new AssertionError("视图里没有这一行：" + nationId + "，实际=" + rows);
    }

    /** 建一个真玩家：读口虽不查玩家档，但身份头要来自真实存在的存档（与其余端点测试同一条）。 */
    private String newPlayer() {
        return newPlayer(1);
    }

    /**
     * 建国要主城 16 级、建盟要 500 金币（真实扣款），两样都在这里备好
     * （与 {@code NationEndpointTest.newPlayer} 同一条夹具，抄过来而不是共用：那份 60KB 且属于别的用例族）。
     */
    private String newPlayer(int cityLevel) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "国战测试",
                1_700_000_000_000L, "")).playerId();
        if (cityLevel <= 1) {
            return playerId;
        }
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

    /** 一个可用的宣战主体：国王、同盟成员（用来验"没权限的人"）、他自己的国家 id。 */
    private record Kingdom(String king, String mate, String nationId) {
    }

    /**
     * 真人路线建一个国：主城 16 级 → 建盟 → 拉一个成员入盟 → 建国。
     *
     * <p><b>刻意不走 {@code NationStore.insertIfAbsent} 直接塞档</b>：宣战的权限判定读的是
     * 「这个人在这国担任什么官职」，而那份官职是 {@code /nation/found} 才写进去的 ——
     * 自己抄近路建档，测的就是夹具而不是生产路径（本仓「判定写了没接上」那一族的反面教材）。
     */
    private Kingdom kingdom(String nationName) throws Exception {
        String king = newPlayer(16);
        String mate = newPlayer(16);
        allianceSeq++;
        String allianceId = post200("/alliance/create", king,
                new AllianceCreateReq(newRequestId(), "国战联盟" + allianceSeq,
                        String.format("G%03d", allianceSeq % 1000)))
                .get("alliance").get("id").asText();
        post200("/alliance/apply", mate, new AllianceIdReq(newRequestId(), allianceId));
        post200("/alliance/review", king, new AllianceReviewReq(newRequestId(), mate, true));
        String nationId = post200("/nation/found", king,
                new NationFoundReq(newRequestId(), nationName, 100L + allianceSeq, 200L))
                .get("nation").get("nationId").asText();
        return new Kingdom(king, mate, nationId);
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private JsonNode get200(String url, String playerId) throws Exception {
        return okData(perform(get(url).header(PLAYER_HEADER, playerId)));
    }

    private JsonNode post200(String url, String playerId, Object req) throws Exception {
        return okData(postRaw(url, playerId, req));
    }

    /** 回整份响应（含业务码），用来断言"被拒"那几条 —— 拒的时候要看的是码，不是 data。 */
    private JsonNode postRaw(String url, String playerId, Object req) throws Exception {
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
