package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
import com.ironoath.battle.BattleResult;
import com.ironoath.battle.BattleType;
import com.ironoath.battle.Winner;
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
import com.ironoath.web.dto.generated.MarchAction;
import com.ironoath.web.dto.generated.MarchReq;
import com.ironoath.web.dto.generated.MarchUnit;
import com.ironoath.web.dto.generated.NationDiplomacyReq;
import com.ironoath.web.dto.generated.NationDisbandReq;
import com.ironoath.web.dto.generated.NationFoundReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.web.dto.generated.WarDeclareReq;
import com.ironoath.web.dto.generated.WarGoalClaimReq;
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
    /** 摆「早已打过 {@code WAR_DURATION_HOURS}」那两场用的量具单位（不是配置值，配置值现取）。 */
    private static final long HOUR = 60 * MINUTE;

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private SocialStore socialStore;
    @Autowired private WarStore wars;
    @Autowired private WarRulesAssembler warRules;
    @Autowired private NationStore nations;
    @Autowired private NationRulesAssembler nationRules;
    @Autowired private TimeService timeService;
    @Autowired private ConfigRegistry configs;
    /** 所有战斗的唯一漏斗（打野/关卡/攻城/拦截都汇到这里）—— 击杀归属就挂在这一句上，所以要真的走它。 */
    @Autowired private com.ironoath.web.battle.BattleReportService battleReports;

    /** 给号发兵用（#769 的疲劳拒行军用例要过"必须派兵"那道校验，与 MarchRepeatInvariantsTest 同一手法）。 */
    @Autowired private com.ironoath.core.army.ArmyRepository armies;

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

    @Test
    @DisplayName("参战方疲劳到顶 ⇒ 新行军被拒（13026）；同国没出过力的成员照常能发（#769）")
    void fatigueCapRejectsNewMarchesOfSpentParticipants() throws Exception {
        Kingdom attacker = kingdom("疲劳闸门国");
        Kingdom defender = kingdom("被宣国");
        post200("/nation/war/declare", attacker.king(),
                new WarDeclareReq(newRequestId(), defender.nationId()));

        // 用生产口把国王拉满（每行军 +5，20 次到顶）——这样造的疲劳与真发 20 次走的是同一笔账，
        // 省掉的只是 20 次往返；"这一笔真记进参战国的板子"由下面那句自证（那句红了先查夹具，
        // 别去怀疑被拒的那条断言 —— 没有活跃战事时 addFatigue 会按 NOT_PARTICIPANT 不记账）
        wars.addFatigue(attacker.nationId(), attacker.king(), 20L, 0L);
        assertThat(wars.findLatest().orElseThrow().fatigueOf(attacker.king()))
                .as("夹具自证：宣战后疲劳已记到顶")
                .isEqualTo(configs.longParam("WAR_FATIGUE_MAX"));

        giveTroops(attacker.king(), 5L);
        giveTroops(attacker.mate(), 5L);
        // SCOUT 到 (1,1)：免战力圈层、免战斗结算 —— 让"疲劳"成为这一发唯一的变量
        // （SCOUT 是唯一"到了自动返程"的动作，也正是 #768 修复后名额会释放的那条路）
        JsonNode rejected = postRaw("/world/march", attacker.king(), new MarchReq(
                newRequestId(), 1, 1, List.of(new MarchUnit("unit_infantry_t1", 1L)), List.of(), MarchAction.SCOUT));
        assertThat(rejected.get("code").asInt())
                .as("到顶之后不能再行军：验收 7「超过上限后无法继续行军」的服务端执行者（响应=%s）", rejected)
                .isEqualTo(ErrorCode.WAR_FATIGUE_MAX_REACHED.code());

        JsonNode mateOk = postRaw("/world/march", attacker.mate(), new MarchReq(
                newRequestId(), 1, 1, List.of(new MarchUnit("unit_infantry_t1", 1L)), List.of(), MarchAction.SCOUT));
        assertThat(mateOk.get("code").asInt())
                .as("疲劳按人记：同国没出过力的成员照常能发（响应=%s）", mateOk).isZero();
    }

    /** 给号发兵（SCOUT 要过"必须派兵"那道校验；与 MarchRepeatInvariantsTest 同一手法）。 */
    private void giveTroops(String playerId, long count) {
        if (armies.findByPlayerId(playerId).isEmpty()) {
            armies.insertIfAbsent(playerId, new com.ironoath.core.army.ArmyState());
        }
        var army = armies.findByPlayerId(playerId).orElseThrow();
        long version = armies.versionOf(playerId);
        army.add("unit_infantry_t1", count);
        armies.save(playerId, army, version);
    }

    @Test
    @DisplayName("宣战冷却进视图（#755）：宣完战，两边都能看到对方在冷却里、还要等多久；没交过手的第三国不出现")
    void cooldownsAreVisibleForBothSidesOfTheRecentWar() throws Exception {
        Kingdom a = kingdom("铁誓");
        Kingdom b = kingdom("赤原");
        Kingdom c = kingdom("旁观国");

        post200("/nation/war/declare", a.king, new WarDeclareReq(newRequestId(), b.nationId));

        JsonNode fromA = get200("/nation/war/cooldowns", a.king);
        JsonNode rowForB = null;
        for (JsonNode row : fromA.get("cooldowns")) {
            if (b.nationId.equals(row.get("targetNationId").asText())) {
                rowForB = row;
            }
            assertThat(row.get("targetNationId").asText())
                    .as("没交过手的第三国不许出现在这份表里（表里只有「还在冷却」的）")
                    .isNotEqualTo(c.nationId);
        }
        assertThat(rowForB).as("刚宣过战的目标必须在表里").isNotNull();
        assertThat(rowForB.get("targetNationName").asText())
                .as("国名服务端下发（面板不许印 id）").isEqualTo("赤原");
        long remaining = rowForB.get("remainingSec").asLong();
        assertThat(remaining).as("剩余秒数恒为正").isPositive();
        assertThat(remaining).as("冷却 24 小时 = 86400 秒，不会算出一个更大的数")
                .isLessThanOrEqualTo(24L * 3600L);

        // 对称：被打的一方也看得到"对方还在冷却"（同一对两国靠乒乓互宣刷击杀，是刻意防的形状）
        JsonNode fromB = get200("/nation/war/cooldowns", b.king);
        JsonNode rowForA = null;
        for (JsonNode row : fromB.get("cooldowns")) {
            if (a.nationId.equals(row.get("targetNationId").asText())) {
                rowForA = row;
            }
        }
        assertThat(rowForA).as("冷却是对称的：防守方也要看得到").isNotNull();
        // 两侧的读数来自**同一份档**，但两次调用之间服务端时钟在走 ⇒ 差只可能来自那一小段流逝，
        // 而秒级取整会把它放大成 1 秒。判**不变量**（差 ≤ 1 秒）而不是判相等 ——
        // 判相等在跨秒边界时会 flaky（本用例第一版就这么红过一次），而 sleep 掩盖法本仓不用。
        assertThat(Math.abs(rowForA.get("remainingSec").asLong() - remaining))
                .as("两侧读到的是同一份冷却，差值只可能来自两次读之间的那一小段流逝")
                .isLessThanOrEqualTo(3L);   // 3 秒的余量只为容两次 HTTP 往返的间隔，不掩盖两侧不同源（那会差几小时）

        // 没国籍就没有这份表（与 /nation、/nation/treasury 同一条门槛）
        JsonNode loner = perform(get("/nation/war/cooldowns").header(PLAYER_HEADER, newPlayer(1)));
        assertThat(loner.get("code").asInt()).as("没有国籍：13000 而不是一张空表")
                .isEqualTo(ErrorCode.NATION_NOT_FOUND.code());
    }
    // ---------- 全服目标奖励（切片 3d）----------

    @Test
    @DisplayName("全服目标奖励：没达成就领不了、达成后领到配置里的金币、再领被名单挡住，而状态里的今天领没领跟着翻")
    void serverGoalPaysOncePerWarAndFlipsMyClaimFlag() throws Exception {
        Kingdom a = kingdom("铁誓");
        Kingdom b = kingdom("赤原");
        post200("/nation/war/declare", a.king, new WarDeclareReq(newRequestId(), b.nationId));

        // ① 还没达成：宣完战 totalKills=0，而目标是表里那个数
        assertThat(postRaw("/nation/war/goal/claim", a.king, new WarGoalClaimReq(newRequestId()))
                .get("code").asInt())
                .as("没达成就领不了：13024 而不是一个空响应").isEqualTo(ErrorCode.WAR_GOAL_NOT_REACHED.code());

        // ② 把击杀堆过目标。**走生产同一条写回路径**（读出来 → 改 → save 回去），
        //    而不是直接往库里塞一份——否则这一格验的就不是存储层那一段临界区了
        WarScoreBoard board = wars.findLatest().orElseThrow();
        board.recordKill(a.nationId, a.king, warRules.rules().serverGoalKills());
        wars.save(board);

        JsonNode beforeClaim = get200("/nation/war", a.king);
        assertThat(beforeClaim.get("serverGoalReached").asBoolean()).as("目标达成了").isTrue();
        assertThat(beforeClaim.get("myGoalClaimed").asBoolean())
                .as("还没领 —— 这一位存在的全部理由：面板要能区分「可以领」与「已经领过」")
                .isFalse();

        long goldBefore = goldOf(a.king);
        long expected = configs.longParam("WAR_SERVER_GOAL_GOLD");
        JsonNode claimed = post200("/nation/war/goal/claim", a.king, new WarGoalClaimReq(newRequestId()));
        assertThat(claimed.get("gold").asLong())
                .as("回的是配置里那个数 —— 客户端拿它拼文案，不自己抄表").isEqualTo(expected);
        assertThat(goldOf(a.king) - goldBefore).as("金币真的到账").isEqualTo(expected);

        // ③ 再领一次：**名单挡住**（幂等键只挡网络重放，换一条请求再点靠的是名单）
        assertThat(postRaw("/nation/war/goal/claim", a.king, new WarGoalClaimReq(newRequestId()))
                .get("code").asInt())
                .as("第二次是 13025：这一条才是验收 10 的护栏").isEqualTo(ErrorCode.WAR_GOAL_ALREADY_CLAIMED.code());
        assertThat(goldOf(a.king) - goldBefore).as("被拒那一次不许再发一分钱").isEqualTo(expected);

        // ④ 状态跟着翻，且**只翻我自己的**（名单是按人记的）
        assertThat(get200("/nation/war", a.king).get("myGoalClaimed").asBoolean())
                .as("领过之后面板画「已领取」，而不是一颗点了就被拒的键").isTrue();
        assertThat(get200("/nation/war", b.king).get("myGoalClaimed").asBoolean())
                .as("同场的另一个参战国没领过，不受影响").isFalse();
        assertThat(get200("/nation/war", b.king).get("serverGoalReached").asBoolean())
                .as("全服进度是全服的：别人领没领不改变它").isTrue();
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

    // ---------- 击杀归属（切片 2b：生产漏斗 → 积分板 → 读端点）----------

    /**
     * 打野/关卡这类 PVE 结算的击杀要进当前那一场仗。
     *
     * <p><b>走的是 {@code BattleReportService.record} 这个真实漏斗</b>而不是直接调 {@code WarStore.recordKills}：
     * 打野、关卡、攻城、拦截四条战斗路径都汇到这一句，挂在这里才等于"所有战斗都进账"。
     * 战果本身是夹具（{@link #battleResult}）—— 内核真算出来的战果形状由 B05/B09 的用例覆盖，
     * 本用例要钉的是<b>归属与累计</b>那一跳，不是战斗数值。
     */
    @Test
    @DisplayName("打完一场打野：全服进度、该国击杀分与读端点的视图同时跟着变")
    void battleFunnelFeedsTheWarBoardAndTheReadEndpoint() throws Exception {
        Kingdom a = kingdom("铁誓");
        Kingdom b = kingdom("赤原");
        post200("/nation/war/declare", a.king, new WarDeclareReq(newRequestId(), b.nationId));
        assertThat(get200("/nation/war", a.king).get("totalKills").asLong()).isZero();

        battleReports.record(a.king, a.king, "mapmonster_7", "叛军小头目", null,
                BattleType.PVE, List.of(), List.of(), battleResult(0L, 50L), timeService.serverNow());

        JsonNode data = get200("/nation/war", a.king);
        assertThat(data.get("totalKills").asLong())
                .as("全服累计击杀第一次在生产路径上真的动起来")
                .isEqualTo(50L);
        assertThat(rowOf(data.get("scores"), a.nationId).get("killScore").asLong())
                .as("国家击杀分 = 50 兵 × WAR_SCORE_KILL_PER_UNIT(1)")
                .isEqualTo(50L);
        assertThat(rowOf(data.get("scores"), b.nationId).get("killScore").asLong())
                .as("对面那一国没打，就该一分不涨")
                .isZero();
        assertThat(wars.findLatest().orElseThrow().killsBy(a.king))
                .as("个人账（V18 赛季分的键）必须留下 —— 只记国家维度，3b 就只能回头翻会过期的战报")
                .isEqualTo(50L);
    }

    @Test
    @DisplayName("PVP 双方各记一份战报：全服总数是双方阵亡之和，不是把同一批死亡算两遍")
    void twoReportCopiesOfOnePvpBattleDoNotDoubleCount() throws Exception {
        Kingdom a = kingdom("铁誓");
        Kingdom b = kingdom("赤原");
        post200("/nation/war/declare", a.king, new WarDeclareReq(newRequestId(), b.nationId));
        long now = timeService.serverNow();
        // 攻方阵亡 30、守方阵亡 70：两份战报的主人视角互为攻守
        battleReports.record(a.king, a.king, b.king, "赤原王", "铁誓王",
                BattleType.PVP_SOLO, List.of(), List.of(), battleResult(30L, 70L), now);
        battleReports.record(b.king, a.king, b.king, "赤原王", "铁誓王",
                BattleType.PVP_SOLO, List.of(), List.of(), battleResult(30L, 70L), now);

        JsonNode data = get200("/nation/war", a.king);
        assertThat(data.get("totalKills").asLong())
                .as("70（A 消灭的）+ 30（B 消灭的）= 100；写成 170 就是把同一批死亡数了两遍，"
                        + "而全服目标会提前达成、500 金币提前发出去")
                .isEqualTo(100L);
        assertThat(rowOf(data.get("scores"), a.nationId).get("killScore").asLong()).isEqualTo(70L);
        assertThat(rowOf(data.get("scores"), b.nationId).get("killScore").asLong()).isEqualTo(30L);
    }

    @Test
    @DisplayName("没有仗的时候打一场：战报照常落库，国战那条旁路只是 no-op")
    void battleWithoutAWarIsUnaffected() throws Exception {
        Kingdom a = kingdom("铁誓");

        var report = battleReports.record(a.king, a.king, "mapmonster_8", "叛军小头目", null,
                BattleType.PVE, List.of(), List.of(), battleResult(0L, 42L), timeService.serverNow());

        assertThat(report.reportId()).as("战报必须落库（这一格不能反过来把战斗卡住）").isNotBlank();
        assertThat(wars.findLatest())
                .as("没有仗就是 NO_ACTIVE_WAR：什么都不记，也不凭空开一场")
                .isEmpty();
        assertThat(get200("/nation/war", a.king).get("hasWar").asBoolean()).isFalse();
    }

    // ---------- 惰性结算（切片 2c：读端点顺带把时间推进一格）----------

    /**
     * 一场打满了 {@code WAR_DURATION_HOURS} 的仗，靠这一次读面板定格。
     *
     * <p><b>夹具把 {@code startedAt} 摆到四小时前</b>而不是 sleep、也不是拨系统时钟：
     * 到期判据是 {@code now >= startedAt + duration}，动 {@code startedAt} 这一侧就能精确跨过那一刻。
     *
     * <p><b>「未到期不许结」这一半由 {@link #warBoardReadsThroughTheStoreOnEveryDimension} 钉住</b>
     * （它把 {@code startedAt} 摆在一分钟前并断言 {@code phase=SIEGE} 与 {@code remainingSec≈10740}）——
     * 把结算改成无条件执行，那一条会红。所以这一条只需专攻"到期之后"那一半。
     */
    @Test
    @DisplayName("打到结束时间：读一次面板即结算并落盘，第二次读一个字都不动")
    void expiredWarSettlesOnThePanelRead() throws Exception {
        String playerId = newPlayer();
        nations.insertIfAbsent(nation("n_live", "铁誓王国"));
        nations.insertIfAbsent(nation("n_ghost", "无名之国"));
        long startedAt = timeService.serverNow() - 4 * HOUR;   // 远超 3 小时的时长
        wars.insertIfAbsent(board(startedAt, 0L));
        assertThat(wars.findLatest().orElseThrow().phase())
                .as("前置：findLatest 只读不动，此刻档里仍然是一场没结束的仗（结算不能是建档的副作用）")
                .isEqualTo(WarScoreBoard.Phase.SIEGE);

        JsonNode first = get200("/nation/war", playerId);
        assertThat(first.get("hasWar").asBoolean())
                .as("结算完不是 hasWar=false：那等于把打过的那一场从玩家眼前抹掉")
                .isTrue();
        assertThat(first.get("phase").asText()).isEqualTo("SETTLED");
        assertThat(first.get("remainingSec").asLong())
                .as("验收 6 的面板读数：已结束为 0，绝不为负")
                .isZero();
        assertThat(first.get("startedAt").asLong())
                .as("结算不许挪走这一场的起点（主键就是按它推导的）")
                .isEqualTo(startedAt);
        long occupy = rowOf(first.get("scores"), "n_live").get("occupyScore").asLong();
        assertThat(occupy)
                .as("结算是真跑了一次：n_live 从 startedAt+1min 起占着王城，结算把这段占领分结进去"
                        + "（结算前这一项恒为 0）")
                .isPositive();
        assertThat(first.get("totalKills").asLong()).isEqualTo(60_120L);

        JsonNode second = get200("/nation/war", playerId);
        assertThat(second.get("scores"))
                .as("第二次读不许再动任何分：再结一次会撞内核 settle() 的护栏（响应变成 500），"
                        + "绕过它则占领分被算两遍")
                .isEqualTo(first.get("scores"));
        assertThat(second.get("phase").asText()).isEqualTo("SETTLED");
        // 直接查存储而不是再走端点：findLatest 不做推进，所以它给出的 SETTLED 只能来自真的写回过
        assertThat(wars.findLatest().orElseThrow().phase())
                .as("结算是落盘的，不是只改了这一次读手里的副本 —— 后者会让下一次读看到一场还在打的仗")
                .isEqualTo(WarScoreBoard.Phase.SETTLED);
    }

    /**
     * <b>宣战口自己也推进时间</b>：过期那一场不需要"先有人打开面板"才能解锁下一场。
     *
     * <p>这条要拦的形状很具体：{@code insertIfNoneActive} 的判据是存储里的 {@code phase != SETTLED}
     * 字面值，而一场打满 3 小时的仗在结算之前那个字面值仍然是 {@code PREPARATION}。
     * 于是如果只有读端点会结算，玩家侧表现就是「仗明明早打完了，宣战却一直回 WAR_ALREADY_ACTIVE」，
     * 解锁条件落在<b>别人</b>的某一次面板读取上 —— 那是"判定读了字面值而不是语义真值"那一族缺陷。
     */
    @Test
    @DisplayName("过期没人读过：直接宣战就能开出新的一场，且过期那一场被就地结掉")
    void declaringSettlesTheExpiredWarWithoutAPanelRead() throws Exception {
        Kingdom a = kingdom("铁誓");
        Kingdom b = kingdom("赤原");
        Kingdom c = kingdom("苍梧");
        long staleStartedAt = timeService.serverNow() - 4 * HOUR;
        WarScoreBoard stale = new WarScoreBoard(warRules.rules(), staleStartedAt);
        stale.registerNation(a.nationId);
        stale.registerNation(b.nationId);
        stale.recordKill(a.nationId, 700L);
        wars.insertIfAbsent(stale);
        assertThat(wars.findLatest().orElseThrow().phase())
                .as("前置：这一场是「过期但没结算」的活仗，而且全程没人读过面板")
                .isEqualTo(WarScoreBoard.Phase.PREPARATION);

        JsonNode data = post200("/nation/war/declare", c.king,
                new WarDeclareReq(newRequestId(), a.nationId));

        assertThat(data.get("hasWar").asBoolean()).isTrue();
        assertThat(data.get("phase").asText())
                .as("开出来的是新那一场：停在 PREPARATION（关卡与王城还不是可占领物，beginSiege 进不去）")
                .isEqualTo(WarScoreBoard.Phase.PREPARATION.name());
        assertThat(data.get("totalKills").asLong())
                .as("视图必须是新那一场，过期那场的 700 击杀不能带过来")
                .isZero();
        assertThat(wars.findLatest().orElseThrow().startedAt())
                .as("当前这场已经换成刚宣的那一场")
                .isGreaterThan(staleStartedAt);
        // 宣战能成这件事本身就是"过期那一场已被就地结掉"的证据：
        // 没结掉就会走 insertIfNoneActive 返回 false 那一支，响应是 WAR_ALREADY_ACTIVE 而不是 200。
        assertThat(wars.insertIfAbsent(stale))
                .as("过期那一场仍然留在原主键上（结完就查无此仗，历史与赛季榜都失去依据）")
                .isFalse();
    }

    // ---------- 宣战冷却（切片 3a：warCooldownHours 从零消费者变成有执行点）----------

    @Test
    @DisplayName("冷却内对同一目标再宣被拒（13023 而不是 13020），换个目标立刻放行")
    void samePairWithinCooldownIsRefusedWhileAnotherTargetIsOpen() throws Exception {
        Kingdom a = kingdom("铁誓");
        Kingdom b = kingdom("赤原");
        Kingdom c = kingdom("苍梧");
        // 摆一场「四小时前开的」A-B 仗，它同时跨过两条线：超过 3 小时的战事时长（所以挡住下一次宣战的
        // 理由只能是冷却，不能是"那场还在打"），又仍在 24 小时冷却之内。
        seedPairWar(a.nationId, b.nationId, timeService.serverNow() - 4 * HOUR);

        assertThat(postRaw("/nation/war/declare", a.king,
                new WarDeclareReq(newRequestId(), b.nationId)).get("code").asInt())
                .as("这一码与 13020 的分工要分得开：回「等这一场打完」是误导 —— 那场仗只有 3 小时，"
                        + "按它说的等完还会再被拒一次，而玩家已经付了一次点击")
                .isEqualTo(ErrorCode.WAR_DECLARE_COOLDOWN.code());

        assertThat(post200("/nation/war/declare", a.king,
                new WarDeclareReq(newRequestId(), c.nationId)).get("hasWar").asBoolean())
                .as("换目标必须放行：冷却按「那一对」取档，不是把全国锁一天")
                .isTrue();
    }

    @Test
    @DisplayName("冷却对这一对是对称的：被打的一方反宣同样被挡（否则可以乒乓刷击杀）")
    void theDefenderCannotCounterDeclareWithinCooldown() throws Exception {
        Kingdom a = kingdom("铁誓");
        Kingdom b = kingdom("赤原");
        seedPairWar(a.nationId, b.nationId, timeService.serverNow() - 4 * HOUR);

        assertThat(postRaw("/nation/war/declare", b.king,
                new WarDeclareReq(newRequestId(), a.nationId)).get("code").asInt())
                .as("只挡发起国的写法在这里会退成 200：B 反宣成功，而反宣又让 A 重新进入冷却 —— "
                        + "同一对两国可以在 24 小时里靠乒乓互宣把击杀刷满，那正是 warCooldownHours 的理由")
                .isEqualTo(ErrorCode.WAR_DECLARE_COOLDOWN.code());
    }

    @Test
    @DisplayName("冷却走完就能再宣同一对：判据是上一场的开场时刻 + warCooldownMillis")
    void theSamePairMayDeclareAgainOnceTheCooldownExpires() throws Exception {
        Kingdom a = kingdom("铁誓");
        Kingdom b = kingdom("赤原");
        long cooldown = nations.findById(a.nationId).orElseThrow().warCooldownMillis();
        assertThat(cooldown)
                .as("前置：本国那一档的冷却是 24 小时（nation_config 三档同为 24）——"
                        + "下面那 25 小时是按这个数摆的，配置改了要重算")
                .isEqualTo(24 * HOUR);
        seedPairWar(a.nationId, b.nationId, timeService.serverNow() - 25 * HOUR);

        assertThat(post200("/nation/war/declare", a.king,
                new WarDeclareReq(newRequestId(), b.nationId)).get("hasWar").asBoolean())
                .as("过了那一刻就必须放行，否则冷却变成「永久」，而国战变成一赛季一次的仪式")
                .isTrue();
        assertThat(wars.findLatest().orElseThrow().registeredNations())
                .as("新那一场登记的是这一对，而不是把历史那一场改回来")
                .containsExactlyInAnyOrder(a.nationId, b.nationId);
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

    /**
     * 往存储里摆一场「这两国之间、开场于某时刻」的仗（3a 的冷却判据只读参战方与 {@code startedAt}）。
     *
     * <p>刻意不跑真实的宣战去产生它：宣战的 {@code startedAt} 就是此刻，冷却永远不可能在
     * 同一条用例里"已经过完"。把历史那一档<b>直接摆进存储</b>，才量得到冷却这一条判据本身；
     * 而"仗确实这么开出来过"由上面那几条宣战用例负责。
     */
    private void seedPairWar(String nationA, String nationB, long startedAt) {
        WarScoreBoard board = new WarScoreBoard(warRules.rules(), startedAt);
        board.registerNation(nationA);
        board.registerNation(nationB);
        wars.insertIfAbsent(board);
    }

    private Nation nation(String id, String name) {
        return Nation.found(id, name, "K-" + id, "AL-" + id, 100L, 200L,
                timeService.serverNow(), nationRules.rules());
    }

    /**
     * 一份最小可用的战果：归属逻辑只读<b>阵亡数</b>（其余给空集合与零）。
     *
     * <p>刻意不跑 {@code BattleSimulator}：内核战果的形状由 B05/B09 的用例覆盖，
     * 本类要钉的是「漏斗 → 归属 → 读端点」这一跳。把两个被测对象焊在一起，红了不知道该怪谁。
     *
     * <p><b>只有"阵亡"进账、伤兵不进</b>：伤兵治得回来（{@code ArmyState.admitWounded}），
     * 把它算成被消灭会让全服进度虚高 —— 这条口径与 KILL 榜那一句是同一个三目式，
     * 不是这里新定的。
     */
    private static BattleResult battleResult(long atkDead, long defDead) {
        return new BattleResult(Winner.ATTACKER, List.of(), 0, Map.of(), Map.of(),
                atkDead, 0L, 0L, defDead, 0L, 0L, Map.of(), 0L, 1L, List.of());
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

    /** 这个号现在有多少金币（领取用例要按差值断言，不写死余额）。 */
    private long goldOf(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow()
                .resources().get("GOLD").current();
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
