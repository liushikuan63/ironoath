package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.ironoath.web.dto.generated.PlayerInitReq;
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
 * 端点层的校验用例则用 Spring 那份未锚定的配置 —— 它顺带钉住"没有赛季时榜是空的、不是报错"。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RankEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final long SEASON_START = 1_757_000_000_000L;

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
        ranks = new RankBoardService(boards, socialStore, nationStore, players, bots, anchored, assembler);
        settlements = new SeasonSettlementService(anchored, timeService, assembler, players,
                rewardService, new com.ironoath.web.store.memory.InMemorySeasonLedger(), boards,
                new com.ironoath.web.store.memory.InMemoryIdempotencyStore(), bots);
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

        var power = ranks.list(king, RankType.POWER, 1);
        assertThat(power.entries().stream().map(e -> e.id()).toList())
                .as("战力榜按 MatchPower 降序").containsExactly(king, mate);
        assertThat(power.entries().get(0).value()).isEqualTo(9_000L);

        var kill = ranks.list(king, RankType.KILL, 1);
        assertThat(kill.entries().get(0).id())
                .as("击杀榜：累加型，两次上报要加成一条").isEqualTo(mate);
        assertThat(kill.entries().get(0).value()).isEqualTo(200L);
        assertThat(kill.entries().get(1).value())
                .as("同一人的两次击杀累加（120 + 30）").isEqualTo(150L);

        var alliance = ranks.list(king, RankType.ALLIANCE, 1);
        assertThat(alliance.entries()).as("联盟榜：成员赛季分合计").hasSize(1);
        assertThat(alliance.entries().get(0).id()).isEqualTo(allianceId);
        assertThat(alliance.entries().get(0).value())
                .as("12,000 = 9,000 + 3,000，不是任何一个人的分").isEqualTo(12_000L);
        assertThat(alliance.entries().get(0).tag()).as("联盟榜带缩写").isNotBlank();

        var nation = ranks.list(king, RankType.NATION, 1);
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

    @Test
    @DisplayName("验收 2：未上榜给 null，不用 0 冒充（0 会与「第 0 名」混淆）")
    void unrankedPlayerGetsNullNotZero() throws Exception {
        String nobody = newPlayer(1);
        var resp = ranks.list(nobody, RankType.POWER, 1);
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

        var power = ranks.list(human, RankType.POWER, 1);
        assertThat(power.entries().stream().map(e -> e.id()).toList())
                .as("写入侧：Bot 的战力上报被拦在门外").containsExactly(human);
        assertThat(power.entries()).noneMatch(entry -> entry.id().equals(botId));

        // 读侧兜底：直接往榜里塞一条 Bot（模拟"这条规则生效之前写进去的数据"）
        SeasonBoardStore boards = new InMemorySeasonBoardStore();
        boards.report(seasonId, SeasonSettlement.Board.POWER,
                new SeasonSettlement.Entry(botId, "老 Bot", 888_888L));
        SeasonRulesAssembler assembler = new SeasonRulesAssembler(anchoredConfigs());
        var readSide = new RankBoardService(boards, socialStore, nationStore, players, bots,
                anchoredConfigs(), assembler);
        assertThat(readSide.list(human, RankType.POWER, 1).entries().stream().map(e -> e.id()).toList())
                .as("读侧：只有 Bot 在榜时，榜是空的（而不是把 Bot 顶到第 1）")
                .isEmpty();
    }

    @Test
    @DisplayName("端点：不认识的榜类型回参数错误（空榜会被读成「这个榜还没人」），没有赛季时榜是空的")
    void endpointRejectsUnknownTypeAndStaysEmptyWithoutSeason() throws Exception {
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
                timeService, social, ranks);

        // 战果由内核产出（这里手工构造一份：本用例验的是"记战报会不会上报击杀"，
        // 不是内核怎么算伤害 —— 形状与 BattleReportStoreEquivalenceTest 的夹具一致）
        var result = new com.ironoath.battle.BattleResult(com.ironoath.battle.Winner.ATTACKER,
                List.of(new com.ironoath.battle.RoundSnapshot(1, Map.of(), Map.of(),
                        0L, 400L, 0L, 0L, 0L, List.of())),
                1, Map.of(), Map.of(), 0L, 0L, 0L, 400L, 100L, 0L,
                Map.of(), 0L, 42L, List.of());
        reports.record(winner, winner, loser, "守方", null,
                com.ironoath.battle.BattleType.PVE, List.of(), List.of(), result, timeService.serverNow());

        var kill = ranks.list(winner, RankType.KILL, 1);
        assertThat(kill.entries()).as("记一份战报 ⇒ 主人的击杀榜上多出 400（对方的阵亡数）")
                .hasSize(1);
        assertThat(kill.entries().get(0).value()).isEqualTo(400L);
        assertThat(ranks.me(winner, RankType.POWER).myRank())
                .as("击杀与战力是两本账：这一场不会给战力榜添行").isNull();
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
