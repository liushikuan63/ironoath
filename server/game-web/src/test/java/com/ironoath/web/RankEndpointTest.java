package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.season.SeasonSettlement;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.NationFoundReq;
import com.ironoath.web.dto.generated.OpsRankSnapshotResp;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.RankEntryView;
import com.ironoath.web.dto.generated.RankSnapshotResp;
import com.ironoath.web.dto.generated.RankType;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.rank.RankBoardService;
import com.ironoath.web.season.SeasonBoardStore;
import com.ironoath.web.season.SeasonRulesAssembler;
import com.ironoath.web.season.SeasonSettlementService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemorySeasonBoardStore;

/**
 * 职责：B23 §一 1 的四类实时榜 —— 验收 1（四个数据源）、2（我的名次：未上榜给 null）、
 * 3（Bot 不进榜前 3，写读两侧）与端点层的参数校验。
 * 依赖：Spring Boot Test + MockMvc + 内存存储。
 *
 * <p><b>为什么赛季锚点要自己搭一份</b>：`SEASON_START_AT` 是部署参数、不在表里（`SeasonStatusTest` 同一处理），
 * 而"没有赛季就没有榜"是**本批的前提**：锚点缺省时上报会静默返回（那是设计，不是缺陷），
 * 所以四类榜的数据源测试必须自带一份锚好的 `ConfigRegistry`（与 `SeasonSettlementTest` 同一手法）。
 * 端点层的校验用例则用 Spring 那份未锚定的配置 —— 它顺带钉住"没人上报时榜是空的、不是报错"。
 * 注意别把它读成"seasonId 为空"：seasonId 来自 season 表首行 id，**恒非空**
 * （表为空时 timelineRules 直接抛），空的是榜不是赛季。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RankEndpointTest {

    /** 结算里会触发战令的赛季末补发（B24）：这里的用例不关心补发，但构造要真的传进去 —— 传 null 会让"结算顺手补发"这条路径在测试里被静默跳过。 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.ironoath.web.battlepass.BattlePassService battlePass;

    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final long SEASON_START = 1_757_000_000_000L;
    /** 运维令牌的测试值（与其它 ops 只读端点同一条：测试 profile 里配的就是它）。 */
    private static final String OPS_TOKEN = "test-ops-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private SocialAppService social;
    @Autowired private SocialStore socialStore;
    @Autowired private NationStore nationStore;
    @Autowired private BotRegistry bots;
    @Autowired private TimeService timeService;
    @Autowired private com.ironoath.core.reward.RewardService rewardService;

    /** 锚好赛季的一整套（榜服务 + 赛季上报服务）—— 用的是内存榜存储，与全局那份互不干扰。 */
    private RankBoardService ranks;
    private SeasonSettlementService settlements;
    private String seasonId;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        socialStore.clear();
        nationStore.clear();
        bots.clear();
        ConfigRegistry anchored = anchoredConfigs();
        SeasonRulesAssembler assembler = new SeasonRulesAssembler(anchored);
        SeasonBoardStore boards = new InMemorySeasonBoardStore();
        ranks = new RankBoardService(boards, socialStore, nationStore, players, bots, anchored, assembler, timeService);
        settlements = new SeasonSettlementService(anchored, timeService, assembler, players,
                rewardService, new com.ironoath.web.store.memory.InMemorySeasonLedger(), boards,
                new com.ironoath.web.store.memory.InMemoryIdempotencyStore(), bots, battlePass);
        seasonId = assembler.timelineRules().seasonId();
        assertThat(seasonId).as("夹具前提：锚点生效后时间轴给出赛季 id").isNotBlank();
    }

    @Test
    @DisplayName("验收 1：四个榜各有各的数据源 —— 战力、击杀、联盟合计、国家合计")
    void fourBoardsReadFromFourSources() throws Exception {
        String king = newPlayer(16);
        String mate = newPlayer(16);
        String allianceId = createAlliance(king, mate);
        post200("/nation/found", king, new NationFoundReq(newRequestId(), "铁誓王国", 100L, 200L));

        // POWER：走赛季上报那条生产入口（线上由 PowerRefreshService 驱动）
        settlements.report(king, "老王", 9_000L);
        settlements.report(mate, "小李", 3_000L);

        // KILL：走击杀上报（线上由战报域的战斗结算驱动）
        ranks.reportKills(king, "老王", 120L);
        ranks.reportKills(king, "老王", 30L);
        ranks.reportKills(mate, "小李", 200L);

        var power = ranks.list(king, RankType.POWER, 1, 0);
        assertThat(power.entries().stream().map(e -> e.id()).toList())
                .as("战力榜按 MatchPower 降序").containsExactly(king, mate);
        assertThat(power.entries().get(0).value()).isEqualTo(9_000L);

        var kill = ranks.list(king, RankType.KILL, 1, 0);
        assertThat(kill.entries().get(0).id())
                .as("击杀榜：累加型，两次上报要加成一条").isEqualTo(mate);
        assertThat(kill.entries().get(0).value()).isEqualTo(200L);
        assertThat(kill.entries().get(1).value())
                .as("同一人的两次击杀累加（120 + 30）").isEqualTo(150L);

        var alliance = ranks.list(king, RankType.ALLIANCE, 1, 0);
        assertThat(alliance.entries()).as("联盟榜：成员赛季分合计").hasSize(1);
        assertThat(alliance.entries().get(0).id()).isEqualTo(allianceId);
        assertThat(alliance.entries().get(0).value())
                .as("12,000 = 9,000 + 3,000，不是任何一个人的分").isEqualTo(12_000L);
        assertThat(alliance.entries().get(0).tag()).as("联盟榜带缩写").isNotBlank();

        var nation = ranks.list(king, RankType.NATION, 1, 0);
        assertThat(nation.entries()).as("国家榜：成员联盟的合计").hasSize(1);
        assertThat(nation.entries().get(0).value()).isEqualTo(12_000L);

        // 我的名次：组织榜回的是我所在组织那一行（个人榜回我自己）
        assertThat(ranks.me(mate, RankType.ALLIANCE).myRank())
                .as("组织榜回的是我所在组织那一行").isEqualTo(1);
        assertThat(ranks.me(mate, RankType.KILL).myRank())
                .as("击杀 200 排第 1").isEqualTo(1);
        assertThat(ranks.me(king, RankType.KILL).myRank())
                .as("击杀 150 排第 2 —— 名次跟着榜值走，不是跟着谁先上报").isEqualTo(2);
    }

    /**
     * 国战赛季分（V18 / B13 承载 3b）：一场仗结算时按人把击杀换成 {@code WAR} 榜的分。
     *
     * <p><b>走 {@code reportWarSeasonPoints(board)} 这个真实入口</b>而不是直接调 {@code accumulate}：
     * 门槛、Bot 排除、赛季没开就返回这三段判断都长在这个方法里，直接调累加等于只测了存储。
     *
     * <p><b>两个因子都从表里现取</b>（{@code warSeasonPoints()}），所以这条用例不绑
     * {@code WAR_SEASON_POINT_*} 的具体初值 —— 运营改表它照样成立，而"改表就红"的量具
     * 恰好是本仓「数值零硬编码」那条铁律的反面教材。
     */
    @Test
    @DisplayName("国战结算发赛季分：门槛不过不建行；分等于击杀乘每杀点数；调两次就是两倍（旗标存在的理由）")
    void warSeasonPointsLandOnTheWarBoardWithThresholdApplied() {
        String hunter = newPlayer(16);
        String idler = newPlayer(16);
        SeasonRulesAssembler rules = new SeasonRulesAssembler(anchoredConfigs());
        var points = rules.warSeasonPoints();
        assertThat(points.minKills())
                .as("夹具前提：门槛为正数，否则「不过」这一支根本测不到").isPositive();
        assertThat(points.pointPerKill())
                .as("夹具前提：每杀点数为正数，否则下面那句等式对任何分都成立").isPositive();

        com.ironoath.core.nation.WarScoreBoard board = new com.ironoath.core.nation.WarScoreBoard(
                new com.ironoath.web.nation.WarRulesAssembler(anchoredConfigs()).rules(),
                timeService.serverNow());
        board.registerNation("n1");
        board.registerNation("n2");
        board.recordKill("n1", hunter, points.minKills() + 40L);   // 打够门槛
        board.recordKill("n2", idler, points.minKills() - 1L);     // 差一个，不该建行

        assertThat(ranks.reportWarSeasonPoints(board))
                .as("只有打够门槛的那一个进账").isEqualTo(1);
        var view = ranks.list(hunter, RankType.WAR, 1, 0);
        assertThat(view.entries().stream().map(e -> e.id()).toList())
                .as("低于门槛的人不建行：给 0 分会占住一个位次并出现在分页里")
                .containsExactly(hunter);
        assertThat(view.entries().get(0).value())
                .as("分 = 击杀 × WAR_SEASON_POINT_PER_KILL（两个因子都现取，不写死）")
                .isEqualTo((points.minKills() + 40L) * points.pointPerKill());

        assertThat(ranks.list(hunter, RankType.KILL, 1, 0).entries())
                .as("国战分不进击杀榜：两张榜各记一件事（击杀榜收全量，WAR 榜只收这一场）")
                .isEmpty();

        // 同一场再发一次：本方法自己不幂等 —— 这正是"每场仗只调一次"这条前提必须成立的直接证据，
        // 那条前提由 WarStore.Settlement#settledNow 保证（等价测试里钉着）。
        ranks.reportWarSeasonPoints(board);
        assertThat(ranks.list(hunter, RankType.WAR, 1, 0).entries().get(0).value())
                .as("累加语义调两次就是两倍分 —— settledNow 旗标存在的全部理由")
                .isEqualTo(2L * (points.minKills() + 40L) * points.pointPerKill());
    }

    @Test
    @DisplayName("验收 2：未上榜给 null，不用 0 冒充（0 会与「第 0 名」混淆）")
    void unrankedPlayerGetsNullNotZero() throws Exception {
        String nobody = newPlayer(1);
        var resp = ranks.list(nobody, RankType.POWER, 1, 0);
        assertThat(resp.entries()).as("没人上报过 ⇒ 空榜").isEmpty();
        assertThat(resp.myRank()).isNull();
        assertThat(resp.myValue()).isNull();
        assertThat(ranks.me(nobody, RankType.POWER).myRank()).isNull();
    }

    @Test
    @DisplayName("验收 3：Bot 不进榜前 3 —— 写入侧挡掉，读侧再兜一遍（库里可能存着规则生效前的条目）")
    void botsStayOutOfTheTopRanksOnBothSides() throws Exception {
        String human = newPlayer(16);
        String botId = newPlayer(16);
        bots.register(botProfile(botId));

        settlements.report(human, "真人", 5_000L);
        settlements.report(botId, "邻居", 999_999L);

        var power = ranks.list(human, RankType.POWER, 1, 0);
        assertThat(power.entries().stream().map(e -> e.id()).toList())
                .as("写入侧：Bot 的战力上报被拦在门外").containsExactly(human);
        assertThat(power.entries()).noneMatch(entry -> entry.id().equals(botId));

        // 读侧兜底：直接往榜里塞一条 Bot（模拟"这条规则生效之前写进去的数据"）
        SeasonBoardStore boards = new InMemorySeasonBoardStore();
        boards.report(seasonId, SeasonSettlement.Board.POWER,
                new SeasonSettlement.Entry(botId, "老 Bot", 888_888L));
        SeasonRulesAssembler assembler = new SeasonRulesAssembler(anchoredConfigs());
        var readSide = new RankBoardService(boards, socialStore, nationStore, players, bots,
                anchoredConfigs(), assembler, timeService);
        assertThat(readSide.list(human, RankType.POWER, 1, 0).entries().stream().map(e -> e.id()).toList())
                .as("读侧：只有 Bot 在榜时，榜是空的（而不是把 Bot 顶到第 1）")
                .isEmpty();
    }

    @Test
    @DisplayName("端点：不认识的榜类型回参数错误（空榜会被读成「这个榜还没人」），没锚点时榜是空的")
    void endpointRejectsUnknownTypeAndShowsAnEmptyBoardWhenNothingWasReported() throws Exception {
        String player = newPlayer(1);

        JsonNode bad = getRaw("/rank/list?type=NOPE", player);
        assertThat(bad.get("code").asInt()).isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(bad.path("detail").asText()).contains("POWER");

        // Spring 那份配置里没有 SEASON_START_AT（它是部署参数）⇒ 榜为空、不是报错
        JsonNode empty = get200("/rank/list?type=POWER", player);
        assertThat(empty.get("type").asText()).isEqualTo("POWER");
        assertThat(empty.get("entries")).isEmpty();
        assertThat(empty.get("myRank").isNull()).as("空榜也要给 null 而不是 0").isTrue();
        assertThat(empty.get("pageSize").asInt()).isPositive();
        assertThat(empty.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("战斗结算的击杀进 KILL 榜（战报域是所有战斗的唯一漏斗，累计挂在那里）")
    void battleSettlementFeedsTheKillBoard() {
        String winner = newPlayer(16);
        String loser = newPlayer(16);
        var battleStore = new com.ironoath.web.store.memory.InMemoryBattleReportStore();
        var reports = new com.ironoath.web.battle.BattleReportService(anchoredConfigs(), battleStore,
                timeService, social, ranks,
                // 国战归属用一份<b>全新的空存储</b>：本用例验的是 KILL 榜，不该被上下文里
                // 那颗共享的 war bean 影响（若哪条用例先宣过战，这里就会顺手把击杀记进那场仗）
                new com.ironoath.web.store.memory.InMemoryWarStore(
                        new com.ironoath.web.nation.WarRulesAssembler(anchoredConfigs())),
                new com.ironoath.web.nation.NationMembership(socialStore, nationStore));

        // 战果由内核产出（这里手工构造一份：本用例验的是"记战报会不会上报击杀"，
        // 不是内核怎么算伤害 —— 形状与 BattleReportStoreEquivalenceTest 的夹具一致）
        var result = new com.ironoath.battle.BattleResult(com.ironoath.battle.Winner.ATTACKER,
                List.of(new com.ironoath.battle.RoundSnapshot(1, Map.of(), Map.of(),
                        0L, 400L, 0L, 0L, 0L, List.of())),
                1, Map.of(), Map.of(), 0L, 0L, 0L, 400L, 100L, 0L,
                Map.of(), 0L, 42L, List.of());
        reports.record(winner, winner, loser, "守方", null,
                com.ironoath.battle.BattleType.PVE, List.of(), List.of(), result, timeService.serverNow());

        var kill = ranks.list(winner, RankType.KILL, 1, 0);
        assertThat(kill.entries()).as("记一份战报 ⇒ 主人的击杀榜上多出 400（对方的阵亡数）")
                .hasSize(1);
        assertThat(kill.entries().get(0).value()).isEqualTo(400L);
        assertThat(ranks.me(winner, RankType.POWER).myRank())
                .as("击杀与战力是两本账：这一场不会给战力榜添行").isNull();
    }

    @Test
    @DisplayName("验收 4：每日快照一天只拍一份（重复读不刷新它）；跨天后的第一次读补拍一份新的")
    void dailySnapshotIsOncePerDayAndRefreshedAfterTheDayTurns() {
        String human = newPlayer(16);
        String other = newPlayer(16);
        // 可控时钟：本用例要跨天，而"跨天"在这个仓库里只能靠把时钟推过去（不许 sleep、不许定时器）
        long[] now = {SEASON_START + 3600_000L};
        TimeService clock = new TimeService(() -> now[0]);
        ConfigRegistry anchored = anchoredConfigs();
        SeasonRulesAssembler assembler = new SeasonRulesAssembler(anchored);
        InMemorySeasonBoardStore boards = new InMemorySeasonBoardStore();
        RankBoardService svc = new RankBoardService(boards, socialStore, nationStore, players, bots,
                anchored, assembler, clock);

        String day1 = com.ironoath.common.time.DayKey.of(now[0]);
        boards.report(seasonId, SeasonSettlement.Board.POWER,
                new SeasonSettlement.Entry(human, "老王", 500L));
        boards.report(seasonId, SeasonSettlement.Board.POWER,
                new SeasonSettlement.Entry(other, "小李", 100L));
        svc.list(human, RankType.POWER, 1, 0);

        SeasonSettlement.Snapshot first = boards.daily(seasonId, SeasonSettlement.Board.POWER, day1);
        assertThat(first).as("读一次榜就把今天的拍上了（惰性，无定时器）").isNotNull();
        assertThat(first.snapshotAt()).isEqualTo(now[0]);
        assertThat(first.rankOf(human)).as("那一刻的名次被冻住").isEqualTo(1);

        // 同一天晚些时候再读：榜变了，但今天那份**不许**跟着变（它记的是那一刻的名次）
        now[0] += 3600_000L;
        boards.report(seasonId, SeasonSettlement.Board.POWER,
                new SeasonSettlement.Entry(human, "老王", 50L));
        svc.list(human, RankType.POWER, 1, 0);
        SeasonSettlement.Snapshot again = boards.daily(seasonId, SeasonSettlement.Board.POWER, day1);
        assertThat(again.snapshotAt()).as("同一天重复读不刷新拍摄时刻").isEqualTo(first.snapshotAt());
        assertThat(again.rankOf(human)).as("内容也是那一刻的：他掉分了，但快照里仍是第 1")
                .isEqualTo(1);
        assertThat(boards.dailyDays(seasonId, SeasonSettlement.Board.POWER)).as("仍然只有一天")
                .containsExactly(day1);

        // 跨天：第一次读补一份新的，内容是新值
        now[0] += 24L * 3600_000L;
        String day2 = com.ironoath.common.time.DayKey.of(now[0]);
        assertThat(day2).as("夹具前提：时钟确实跨了一天").isNotEqualTo(day1);
        svc.list(other, RankType.POWER, 1, 0);
        assertThat(boards.dailyDays(seasonId, SeasonSettlement.Board.POWER))
                .as("第二天补一份新的，且第一天那份还在（这就是时间线）")
                .containsExactly(day1, day2);
        SeasonSettlement.Snapshot second = boards.daily(seasonId, SeasonSettlement.Board.POWER, day2);
        assertThat(second.snapshotAt()).isEqualTo(now[0]);
        assertThat(second.rankOf(other)).as("新那份记的是新一天的榜（小李 100 > 老王 50）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("每日快照只回自己：同一个日期，两个人各查各的；运营侧才给全量")
    void snapshotReturnsOnlyTheCallersOwnRow() {
        String human = newPlayer(16);
        String other = newPlayer(16);
        long[] now = {SEASON_START + 3600_000L};
        TimeService clock = new TimeService(() -> now[0]);
        ConfigRegistry anchored = anchoredConfigs();
        SeasonRulesAssembler assembler = new SeasonRulesAssembler(anchored);
        InMemorySeasonBoardStore boards = new InMemorySeasonBoardStore();
        RankBoardService svc = new RankBoardService(boards, socialStore, nationStore, players, bots,
                anchored, assembler, clock);

        String day = com.ironoath.common.time.DayKey.of(now[0]);
        boards.report(seasonId, SeasonSettlement.Board.KILL,
                new SeasonSettlement.Entry(human, "老王", 30L));
        svc.list(human, RankType.KILL, 1, 0);

        RankSnapshotResp mine = svc.snapshot(human, RankType.KILL, day);
        assertThat(mine.myRank()).as("我在那天是第 1").isEqualTo(1);
        assertThat(mine.myValue()).isEqualTo(30L);
        assertThat(mine.snapshotAt()).isEqualTo(now[0]);
        // 别人查同一天：拿到的是"那天榜上没有我"，而不是我的名次（裁决③：只能查自己）
        RankSnapshotResp theirs = svc.snapshot(other, RankType.KILL, day);
        assertThat(theirs.myRank()).as("没上过榜的人查回来是 null").isNull();
        assertThat(theirs.myValue()).isNull();

        // 运营看的是全量（同一个快照，多少人都看得到）
        OpsRankSnapshotResp ops = svc.opsSnapshot(RankType.KILL, day, 1);
        assertThat(ops.totalPeople()).isEqualTo(1);
        assertThat(ops.entries()).extracting(RankEntryView::id).containsExactly(human);

        // 没拍过的那天：明确拒绝，且 detail 要说清"可查的最早一天"是哪天
        assertThatThrownBy(() -> svc.snapshot(human, RankType.KILL, "20200101"))
                .as("那天没有快照 → RANK_SNAPSHOT_EMPTY（不是 200 空数据）")
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最早一天")
                .hasMessageContaining(day);
        // 日期键格式不对是**参数问题**，不是"那天没快照"：两者的下一步完全不同
        assertThatThrownBy(() -> svc.snapshot(human, RankType.KILL, "2026-09-19"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("8 位日期");
    }

    @Test
    @DisplayName("端点：/rank/snapshot 查今天会把今天补拍上、查没拍过的过去某天明确拒绝；/ops/rank/snapshot 没令牌读不到（对照）")
    void snapshotEndpointsExposeOnlyWhatTheyShould() throws Exception {
        String player = newPlayer(1);
        // 今天：第一次读就把今天这份拍上（惰性拍摄的 HTTP 面）——
        // 本进程的 seasonId 来自 season 表（不是部署锚点），所以"有赛季"这一半在这里恒成立
        String today = com.ironoath.common.time.DayKey.of(timeService.serverNow());
        JsonNode mine = get200("/rank/snapshot?type=POWER&dayKey=" + today, player);
        assertThat(mine.get("dayKey").asText()).isEqualTo(today);
        assertThat(mine.get("snapshotAt").asLong()).as("快照真的被拍了（时刻非 0）").isPositive();
        assertThat(mine.get("myRank").isNull()).as("新号没上报过战力 ⇒ 那天榜上没有他").isTrue();

        // 这一天现在有快照了，但它里面一个人都没有 —— 运营侧读得出来"拍了但是空的"
        String opsEmpty = mockMvc.perform(get("/ops/rank/snapshot?type=POWER&dayKey=" + today)
                        .header("X-Ops-Token", OPS_TOKEN))
                .andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonUtils.readTree(opsEmpty).path("data").get("totalPeople").asInt())
                .as("拍了但那天榜上没人：0 而不是查不到").isZero();

        // 没拍过的那一天：明确拒绝，detail 带上"可查的最早一天"（现在确实有那一天：今天）
        JsonNode missing = getRaw("/rank/snapshot?type=POWER&dayKey=20200101", player);
        assertThat(missing.get("code").asInt()).isEqualTo(ErrorCode.RANK_SNAPSHOT_EMPTY.code());
        assertThat(missing.path("detail").asText()).contains("最早一天").contains(today);

        // 日期键格式错 → 参数错误（与上一条分开：这条的下一步是改参数，那条是选个别的日期）
        JsonNode badDay = getRaw("/rank/snapshot?type=POWER&dayKey=2026-09-19", player);
        assertThat(badDay.get("code").asInt()).isEqualTo(ErrorCode.PARAM_INVALID.code());

        // 运营那个出口没有令牌必须读不到（没有对照组的话，"端点存在"与"谁都能读"分不清）。
        // 注意判据是**错误码**而不是 HTTP 状态码：本仓库的 ops 闸门走 BizException ⇒ 200 + OPS_UNAUTHORIZED，
        // 与"端点不存在"（1000）和"端点通了"（0）都能分开
        String noToken = mockMvc.perform(get("/ops/rank/snapshot?type=POWER&dayKey=" + today))
                .andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonUtils.readTree(noToken).get("code").asInt())
                .as("没令牌读不到（对照组：上面带令牌那次读到了 totalPeople）")
                .isEqualTo(ErrorCode.OPS_UNAUTHORIZED.code());
    }

    @Test
    @DisplayName("每页条数：客户端按一屏能画几行来要，服务端按上限夹（体积预算不因客户端乱填而失效）")
    void clientPicksPageSizeButTheServerClampsIt() {
        String king = newPlayer(16);
        String mate = newPlayer(16);
        settlements.report(king, "老王", 9_000L);
        settlements.report(mate, "小李", 3_000L);
        int cap = (int) anchoredConfigs().longParam("RANK_PAGE_SIZE_MAX");

        assertThat(ranks.list(king, RankType.POWER, 1, 1).pageSize())
                .as("要 1 条就给它 1 条：一屏画得下几行是客户端的显示需要").isEqualTo(1);
        assertThat(ranks.list(king, RankType.POWER, 1, 1).entries())
                .as("这一页真的只有 1 行").hasSize(1);
        assertThat(ranks.list(king, RankType.POWER, 1, 1).hasMore())
                .as("榜上还有第 2 个人，所以还有下一页").isTrue();
        assertThat(ranks.list(king, RankType.POWER, 1, 200).pageSize())
                .as("要 200 条只会拿到上限：验收 6 的体积预算是服务端守的，不是客户端自律").isEqualTo(cap);
        assertThat(ranks.list(king, RankType.POWER, 1, 0).pageSize())
                .as("0（或不传）表示用上限").isEqualTo(cap);
    }

    // ---------- 夹具 ----------
    private String newPlayer(int cityLevel) {
        String playerId = playerInitService.init(new PlayerInitReq("req-" + UUID.randomUUID(),
                "dev-" + UUID.randomUUID(), "榜测试", 1_700_000_000_000L, "")).playerId();
        var save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(cityLevel);
        // 建盟要 500 金币（真扣款），夹具把余额备足 —— 这一行不是绕过校验：
        // 余额够之后建盟会真的扣掉 500，而那正是生产路径
        var gold = save.resources().get("GOLD");
        if (gold != null) {
            save.putResource("GOLD", new com.ironoath.core.player.PlayerResourceState(
                    100_000L, gold.cap(), gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        }
        players.save(save);
        return playerId;
    }

    private String createAlliance(String king, String mate) throws Exception {
        String allianceId = post200("/alliance/create", king,
                new AllianceCreateReq(newRequestId(), "榜盟" + UUID.randomUUID().toString().substring(0, 6),
                        "R" + UUID.randomUUID().toString().substring(0, 5)))
                .get("alliance").get("id").asText();
        post200("/alliance/apply", mate, new AllianceIdReq(newRequestId(), allianceId));
        post200("/alliance/review", king, new AllianceReviewReq(newRequestId(), mate, true));
        return allianceId;
    }

    private static com.ironoath.core.bot.BotProfile botProfile(String botId) {
        return new com.ironoath.core.bot.BotProfile(botId, "bot_linju",
                new com.ironoath.core.bot.BotProfile.AiProfile(
                        com.ironoath.common.num.FixedPoint.parse("0.50"), com.ironoath.common.num.FixedPoint.parse("0.50"),
                        com.ironoath.common.num.FixedPoint.parse("0.50"), com.ironoath.common.num.FixedPoint.parse("0.60")),
                new com.ironoath.core.bot.BotProfile.Persona(42L, 7L, 99L, List.of(12, 13, 20, 21, 22),
                        3L, 30L, com.ironoath.common.num.FixedPoint.parse("0.10")),
                com.ironoath.common.num.FixedPoint.parse("1.0"));
    }

    /** 往 global 表追加一行 SEASON_START_AT（它平时不在表里：赛季锚点是部署参数）。 */
    private static ConfigRegistry anchoredConfigs() {
        ConfigRegistry registry = ConfigRegistry.loadFromDirectory(locateConfigDir());
        registry.reload(ConfigRegistry.TABLE_GLOBAL, com.ironoath.config.model.GlobalCfg.class,
                withSeasonStartJson());
        return registry;
    }

    /** 从当前工作目录往上找 contract/config（surefire 的 cwd 是模块目录，不是仓库根）。 */
    private static Path locateConfigDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("contract/config");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 contract/config 目录");
    }

    private static String withSeasonStartJson() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode table = (ObjectNode) mapper.readTree(
                    Files.readString(locateConfigDir().resolve("global.json"), StandardCharsets.UTF_8));
            ArrayNode rows = (ArrayNode) table.get("rows");
            ObjectNode row = mapper.createObjectNode();
            row.put("id", "SEASON_START_AT");
            row.put("valueType", "LONG");
            row.put("value", SEASON_START);
            row.put("unit", "毫秒时间戳");
            row.put("source", "B14 §一（部署参数，不进表）");
            row.put("why", "测试注入的赛季锚点：真实环境由部署时配置");
            rows.add(row);
            return mapper.writeValueAsString(table);
        } catch (Exception e) {
            throw new IllegalStateException("无法构造带 SEASON_START_AT 的 global 表", e);
        }
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
        MvcResult result = mockMvc.perform(post(url)
                        .header(PLAYER_HEADER, playerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtils.toJson(req)))
                .andExpect(status().isOk()).andReturn();
        return okData(JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)));
    }

    private JsonNode perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
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
