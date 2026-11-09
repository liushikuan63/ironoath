package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.model.GlobalCfg;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.nation.WarAwardPlan;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.season.SeasonSettlement;
import com.ironoath.core.social.Alliance;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.nation.NationMembership;
import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.nation.WarStore;
import com.ironoath.web.rank.RankBoardService;
import com.ironoath.web.season.CapitalWarWindow;
import com.ironoath.web.season.SeasonBoardStore;
import com.ironoath.web.season.SeasonRulesAssembler;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.WarAppService;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.social.SocialRulesAssembler;
import com.ironoath.web.store.mongo.MongoNationStore;
import com.ironoath.web.store.mongo.MongoSocialStore;
import com.ironoath.web.store.mongo.MongoSeasonBoardStore;
import com.ironoath.web.store.mongo.MongoWarStore;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies partial award delivery and restart recovery across the two production Mongo stores. */
@SpringBootTest(properties = "ironoath.storage=mongo")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WarAwardMongoRecoveryEndpointTest {
    private static final String OWNER = UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE = "ironoath_b13_war_award_" + OWNER;
    private static final String SEASON = "season_b13_mongo_award_recovery";
    private static final String READER = "b13-mongo-award-recovery-reader";

    @Autowired private MockMvc mockMvc;
    @Autowired private MongoTemplate mongo;
    @Autowired private PlayerInitService playerInit;
    @Autowired private WarRulesAssembler warRules;
    @Autowired private NationStore nations;
    @Autowired private SocialStore social;
    @Autowired private PlayerRepository players;
    @Autowired private BotRegistry bots;
    @Autowired private ConfigRegistry configs;
    @Autowired private SeasonRulesAssembler seasonRules;
    @Autowired private TimeService time;
    @Autowired private CapitalWarWindow capitalWarWindow;
    @Autowired private SocialRulesAssembler socialRules;
    @Autowired private PlayerLock playerLocks;
    @Autowired private IdempotencyStore idempotency;
    @Autowired private RewardService rewards;
    @SpyBean(name = "seasonBoardStore") private SeasonBoardStore seasonBoards;

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry properties) {
        properties.add("ironoath.storage", () -> "mongo");
        properties.add("spring.data.mongodb.uri", () -> TestMongo.uriForDatabase(DATABASE));
    }

    @BeforeAll
    static void markOwnedDatabase() {
        try (var client = MongoClients.create(TestMongo.uri())) {
            client.getDatabase(DATABASE).getCollection("__owned_run")
                    .insertOne(new Document("_id", OWNER));
        }
    }

    @AfterAll
    static void dropOwnedDatabase() {
        try (var client = MongoClients.create(TestMongo.uri())) {
            var database = client.getDatabase(DATABASE);
            assertThat(database.getCollection("__owned_run").countDocuments(new Document("_id", OWNER)))
                    .as("奖分恢复验收只能清理由本测试标记的临时库").isEqualTo(1L);
            assertThat(((Number) database.runCommand(new Document("dropDatabase", 1)
                    .append("comment", OWNER)).get("ok")).doubleValue()).isEqualTo(1.0);
            assertThat(client.getDatabase("admin").runCommand(new Document("listDatabases", 1)
                            .append("filter", new Document("name", DATABASE)).append("nameOnly", true))
                    .getList("databases", Document.class)).isEmpty();
        }
    }

    @Test
    void aFreshWarAndSeasonStoreResumeAfterTheFirstMongoReceiptWasCommitted() throws Exception {
        long now = time.serverNow();
        long startedAt = now - warRules.rules().durationMillis() - 120_000L;
        WarScoreBoard board = new WarScoreBoard(warRules.rules(), startedAt,
                "n_award_attack", Long.MAX_VALUE);
        board.registerNation("n_award_attack");
        board.registerNation("n_award_defend");
        for (int gate = 1; gate <= board.rules().gateCount(); gate++) {
            board.captureGate("n_award_attack", "gate_" + gate);
        }
        board.beginSiege(startedAt);
        board.captureCapital("n_award_attack", startedAt);
        board.settle(startedAt + board.rules().durationMillis());
        WarAwardPlan frozen = new WarAwardPlan(1, startedAt, SEASON, List.of(
                new WarAwardPlan.Member("award_player_1", "成员甲", 11L),
                new WarAwardPlan.Member("award_player_2", "成员乙", 22L),
                new WarAwardPlan.Member("award_player_3", "成员丙", 33L)));
        board.freezeSeasonAwardPlan(frozen);
        assertThat(new MongoWarStore(mongo, warRules).insertIfNoneActive(board)).isTrue();

        AtomicBoolean failAfterFirstMongoWrite = new AtomicBoolean(true);
        doAnswer(invocation -> failAfterDurableWrite(invocation, failAfterFirstMongoWrite))
                .when(seasonBoards).recordScopedIncrement(anyString(), any(SeasonSettlement.Board.class), any());

        mockMvc.perform(get("/nation/war").header("X-Player-Id", READER))
                .andExpect(status().is5xxServerError());

        WarStore afterFailure = new MongoWarStore(mongo, warRules);
        WarScoreBoard partiallyDelivered = afterFailure.findByStartedAt(startedAt).orElseThrow();
        assertThat(partiallyDelivered.phase()).isEqualTo(WarScoreBoard.Phase.SETTLED);
        assertThat(partiallyDelivered.seasonAwardPlan()).isEqualTo(frozen);
        assertThat(partiallyDelivered.seasonAwardedAt()).isNull();

        SeasonBoardStore afterFailureBoards = new MongoSeasonBoardStore(mongo);
        String sourceId = WarStore.documentIdOf(startedAt);
        assertThat(afterFailureBoards.scopedIncrementOf(SEASON, SeasonSettlement.Board.WAR,
                frozen.members().get(0).playerId(), sourceId))
                .contains(new SeasonBoardStore.ScopedIncrement(sourceId, 1L, sourceId,
                        frozen.members().get(0).playerId(), frozen.members().get(0).displayName(), 11L));
        assertThat(afterFailureBoards.scopedIncrementOf(SEASON, SeasonSettlement.Board.WAR,
                frozen.members().get(1).playerId(), sourceId)).isEmpty();

        SeasonBoardStore afterRestartBoards = new MongoSeasonBoardStore(mongo);
        NationMembership membership = new NationMembership(social, nations);
        RankBoardService afterRestartRanks = new RankBoardService(afterRestartBoards, social, nations,
                membership, players, bots, configs, seasonRules, time);
        WarAppService afterRestart = new WarAppService(new MongoWarStore(mongo, warRules), nations,
                warRules, capitalWarWindow, time, membership, socialRules, playerLocks,
                idempotency, configs, afterRestartRanks, rewards);

        afterRestart.warStatus(READER);

        WarScoreBoard completed = new MongoWarStore(mongo, warRules).findByStartedAt(startedAt).orElseThrow();
        assertThat(completed.seasonAwardPlan()).isEqualTo(frozen);
        assertThat(completed.seasonAwardedAt()).isNotNull();
        Map<String, Long> scores = new MongoSeasonBoardStore(mongo).board(SEASON, SeasonSettlement.Board.WAR)
                .stream().collect(Collectors.toMap(SeasonSettlement.Entry::id, SeasonSettlement.Entry::score));
        assertThat(scores).containsExactlyInAnyOrderEntriesOf(Map.of(
                "award_player_1", 11L, "award_player_2", 22L, "award_player_3", 33L));
        for (WarAwardPlan.Member member : frozen.members()) {
            assertThat(new MongoSeasonBoardStore(mongo).scopedIncrementOf(
                    SEASON, SeasonSettlement.Board.WAR, member.playerId(), sourceId))
                    .contains(new SeasonBoardStore.ScopedIncrement(sourceId, 1L, sourceId,
                            member.playerId(), member.displayName(), member.score()));
        }
    }

    @Test
    void persistedMongoMembershipHistoryFreezesProratedAwardsBeforeRestartRecovery() throws Exception {
        String originalGlobal = Files.readString(locateConfigDir().resolve("global.json"), StandardCharsets.UTF_8);
        long winnerBonus = 13L;
        configs.reload(ConfigRegistry.TABLE_GLOBAL, GlobalCfg.class,
                withSeasonStartAndWarBonuses(winnerBonus));
        try {
            String historyDatabase;
            try (TestMongo historyDb = TestMongo.tryOpen()) {
                assertThat(historyDb).as("历史分摊集成用例必须连接本任务隔离Mongo").isNotNull();
                MongoTemplate historyMongo = historyDb.template();
                historyDatabase = historyMongo.getDb().getName();
                SocialStore historySocial = new MongoSocialStore(historyMongo, socialRules);
                NationStore historyNations = new MongoNationStore(historyMongo,
                        new NationRulesAssembler(configs));
                WarStore historyWars = new MongoWarStore(historyMongo, warRules);

            long startedAt = time.serverNow() + 1_000L;
            long duration = warRules.rules().durationMillis();
            long settledAt = Math.addExact(startedAt, duration);
            long historyStart = startedAt - 500L;
            String suffix = UUID.randomUUID().toString().replace("-", "");

            String winner = syntheticPlayer("b13-history-winner-" + suffix);
            String joinedMidWar = syntheticPlayer("b13-history-joined-" + suffix);
            String shortMember = syntheticPlayer("b13-history-short-" + suffix);
            String countryIntervalMember = syntheticPlayer("b13-history-country-" + suffix);
            String joinedAfterWar = syntheticPlayer("b13-history-late-" + suffix);
            String defender = syntheticPlayer("b13-history-defender-" + suffix);

            String winnerAllianceId = "alliance_b13_history_" + suffix.substring(0, 12);
            Alliance winnerAlliance = Alliance.create(winnerAllianceId,
                    "奖分历史盟A" + suffix.substring(0, 6), "A" + suffix.substring(0, 3),
                    winner, 100_000L, socialRules.allianceRules());
            historySocial.saveAlliance(winnerAlliance, 0L, historyStart);

            String countryAllianceId = "alliance_b13_country_" + suffix.substring(0, 12);
            Alliance countryAlliance = Alliance.create(countryAllianceId,
                    "奖分历史盟B" + suffix.substring(0, 6), "B" + suffix.substring(0, 3),
                    countryIntervalMember, 100_000L, socialRules.allianceRules());
            historySocial.saveAlliance(countryAlliance, 0L, historyStart);

            String defenderAllianceId = "alliance_b13_defender_" + suffix.substring(0, 12);
            Alliance defenderAlliance = Alliance.create(defenderAllianceId,
                    "奖分历史守盟" + suffix.substring(0, 6), "D" + suffix.substring(0, 3),
                    defender, 100_000L, socialRules.allianceRules());
            historySocial.saveAlliance(defenderAlliance, 0L, historyStart);

            String winnerNationId = "nation_b13_history_" + suffix.substring(0, 12);
            NationRulesAssembler nationRules = new NationRulesAssembler(configs);
            Nation winnerNation = Nation.found(winnerNationId, "奖分历史胜国" + suffix.substring(0, 6),
                    winner, winnerAllianceId, 100L, 200L, historyStart, nationRules.rules());
            assertThat(historyNations.insertIfAbsent(winnerNation)).isTrue();

            String defenderNationId = "nation_b13_defender_" + suffix.substring(0, 12);
            Nation defenderNation = Nation.found(defenderNationId, "奖分历史守国" + suffix.substring(0, 6),
                    defender, defenderAllianceId, 300L, 400L, historyStart, nationRules.rules());
            assertThat(historyNations.insertIfAbsent(defenderNation)).isTrue();

            long joinAt = Math.addExact(startedAt, duration / 5L);
            long halfWar = Math.addExact(startedAt, duration / 2L);
            long shortMemberLeavesAt = Math.addExact(startedAt, duration * 3L / 4L);
            long lateJoinAt = Math.addExact(settledAt, 200L);

            winnerAlliance = historySocial.allianceById(winnerAllianceId).orElseThrow();
            long expectedAllianceVersion = winnerAlliance.version();
            winnerAlliance.join(joinedMidWar);
            historySocial.saveAlliance(winnerAlliance, expectedAllianceVersion, joinAt);

            winnerAlliance = historySocial.allianceById(winnerAllianceId).orElseThrow();
            expectedAllianceVersion = winnerAlliance.version();
            winnerAlliance.join(shortMember);
            historySocial.saveAlliance(winnerAlliance, expectedAllianceVersion, halfWar);

            winnerAlliance = historySocial.allianceById(winnerAllianceId).orElseThrow();
            expectedAllianceVersion = winnerAlliance.version();
            winnerAlliance.leave(shortMember);
            historySocial.saveAlliance(winnerAlliance, expectedAllianceVersion, shortMemberLeavesAt);

            winnerAlliance = historySocial.allianceById(winnerAllianceId).orElseThrow();
            expectedAllianceVersion = winnerAlliance.version();
            winnerAlliance.leave(joinedMidWar);
            historySocial.saveAlliance(winnerAlliance, expectedAllianceVersion, settledAt + 100L);

            winnerAlliance = historySocial.allianceById(winnerAllianceId).orElseThrow();
            expectedAllianceVersion = winnerAlliance.version();
            winnerAlliance.join(joinedAfterWar);
            historySocial.saveAlliance(winnerAlliance, expectedAllianceVersion, lateJoinAt);

            Nation changedWinnerNation = historyNations.findById(winnerNationId).orElseThrow();
            long expectedNationVersion = changedWinnerNation.version();
            changedWinnerNation.admitAlliance(countryAllianceId,
                    Math.addExact(startedAt, duration / 10L));
            historyNations.save(changedWinnerNation, expectedNationVersion);
            changedWinnerNation = historyNations.findById(winnerNationId).orElseThrow();
            expectedNationVersion = changedWinnerNation.version();
            changedWinnerNation.removeAlliance(countryAllianceId, false, countryIntervalMember, halfWar);
            historyNations.save(changedWinnerNation, expectedNationVersion);

            WarScoreBoard board = new WarScoreBoard(warRules.rules(), startedAt,
                    winnerNationId, defenderNationId, startedAt + 60_000L);
            board.registerNation(winnerNationId);
            board.registerNation(defenderNationId);
            var gates = com.ironoath.web.service.WorldTargets.from(configs).targets().stream()
                    .filter(target -> target.definition().kind() ==
                            com.ironoath.config.cfg.WorldTargetCfg.Kind.GATE)
                    .sorted(java.util.Comparator.comparing(target -> target.definition().id())).toList();
            assertThat(gates).hasSize(board.rules().gateCount());
            for (var gate : gates) board.captureGate(winnerNationId, gate.definition().id());
            board.beginSiege(startedAt);
            board.captureCapital(winnerNationId, startedAt);
            assertThat(historyWars.insertIfNoneActive(board)).isTrue();
            var settled = historyWars.settleIfExpired(settledAt).orElseThrow();
            assertThat(settled.settledNow()).isTrue();
            assertThat(settled.result().winnerId()).isEqualTo(winnerNationId);

            TimeService settledTime = new TimeService(() -> settledAt + 1L);
            NationMembership actualHistory = new NationMembership(historySocial, historyNations);
            MongoSeasonBoardStore persistedBoards = new MongoSeasonBoardStore(historyMongo);
            AtomicBoolean failAfterFirstReceipt = new AtomicBoolean(true);
            SeasonBoardStore faultingBoards = Mockito.mock(SeasonBoardStore.class,
                    AdditionalAnswers.delegatesTo(persistedBoards));
            doAnswer(invocation -> {
                String seasonId = invocation.getArgument(0);
                SeasonSettlement.Board rankBoard = invocation.getArgument(1);
                SeasonBoardStore.ScopedIncrement increment = invocation.getArgument(2);
                var written = persistedBoards.recordScopedIncrement(seasonId, rankBoard, increment);
                if (failAfterFirstReceipt.compareAndSet(true, false)) {
                    throw new IllegalStateException("simulated crash after the first Mongo history-based award receipt");
                }
                return written;
            }).when(faultingBoards).recordScopedIncrement(anyString(), any(SeasonSettlement.Board.class), any());

            RankBoardService firstRanks = new RankBoardService(faultingBoards, historySocial, historyNations,
                    actualHistory, players, bots, configs, new SeasonRulesAssembler(configs), settledTime);
            WarAppService firstProcess = new WarAppService(new MongoWarStore(historyMongo, warRules), historyNations,
                    warRules, capitalWarWindow, settledTime, actualHistory, socialRules, playerLocks,
                    idempotency, configs, firstRanks, rewards);
            assertThatThrownBy(() -> firstProcess.warStatus(READER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("simulated crash after the first Mongo history-based award receipt");

            WarScoreBoard partial = new MongoWarStore(historyMongo, warRules).findByStartedAt(startedAt).orElseThrow();
            WarAwardPlan frozen = partial.seasonAwardPlan();
            assertThat(frozen).isNotNull();
            assertThat(frozen.seasonId()).isNotBlank();
            Map<String, Long> expectedAwards = Map.of(
                    winner, 13L, joinedMidWar, 10L, shortMember, 3L, countryIntervalMember, 5L);
            Map<String, Long> actualAwards = frozen.members().stream().collect(Collectors.toMap(
                    WarAwardPlan.Member::playerId, WarAwardPlan.Member::score));
            assertThat(actualAwards).containsExactlyInAnyOrderEntriesOf(expectedAwards)
                    .doesNotContainKey(joinedAfterWar);
            assertThat(partial.seasonAwardedAt()).isNull();

            String sourceId = WarStore.documentIdOf(startedAt);
            var firstReceipt = persistedBoards.scopedIncrementOf(frozen.seasonId(),
                    SeasonSettlement.Board.WAR, frozen.members().get(0).playerId(), sourceId);
            assertThat(firstReceipt).isPresent();
            assertThat(persistedBoards.board(frozen.seasonId(), SeasonSettlement.Board.WAR)).hasSize(1);

            SocialStore noHistorySocial = Mockito.mock(SocialStore.class,
                    AdditionalAnswers.delegatesTo(historySocial));
            NationStore noHistoryNations = Mockito.mock(NationStore.class,
                    AdditionalAnswers.delegatesTo(historyNations));
            Mockito.doThrow(new IllegalStateException("frozen Mongo plan must not reread member history"))
                    .when(noHistorySocial).allianceMemberEpochs(anyString());
            Mockito.doThrow(new IllegalStateException("frozen Mongo plan must not reread nation history"))
                    .when(noHistoryNations).allianceNationEpochs(anyString());
            NationMembership restartedMembership = new NationMembership(noHistorySocial, noHistoryNations);
            RankBoardService restartedRanks = new RankBoardService(new MongoSeasonBoardStore(historyMongo),
                    noHistorySocial, noHistoryNations, restartedMembership, players, bots, configs,
                    new SeasonRulesAssembler(configs), settledTime);
            WarAppService restarted = new WarAppService(new MongoWarStore(historyMongo, warRules), noHistoryNations,
                    warRules, capitalWarWindow, settledTime, restartedMembership, socialRules, playerLocks,
                    idempotency, configs, restartedRanks, rewards);

            restarted.warStatus(READER);

            verify(noHistorySocial, never()).allianceMemberEpochs(anyString());
            verify(noHistoryNations, never()).allianceNationEpochs(anyString());
            WarScoreBoard completed = new MongoWarStore(historyMongo, warRules).findByStartedAt(startedAt).orElseThrow();
            assertThat(completed.seasonAwardPlan()).isEqualTo(frozen);
            assertThat(completed.seasonAwardedAt()).isEqualTo(settledAt + 1L);
            Map<String, Long> scores = new MongoSeasonBoardStore(historyMongo)
                    .board(frozen.seasonId(), SeasonSettlement.Board.WAR).stream()
                    .collect(Collectors.toMap(SeasonSettlement.Entry::id, SeasonSettlement.Entry::score));
            assertThat(scores).containsExactlyInAnyOrderEntriesOf(expectedAwards);
            }
            try (var cleanupClient = MongoClients.create(TestMongo.uri())) {
                var remaining = cleanupClient.getDatabase("admin").runCommand(new Document("listDatabases", 1)
                                .append("filter", new Document("name", historyDatabase))
                                .append("nameOnly", true))
                        .getList("databases", Document.class);
                assertThat(remaining).as("历史分摊用例必须删除它自己的随机Mongo库").isEmpty();
            }
        } finally {
            configs.reload(ConfigRegistry.TABLE_GLOBAL, GlobalCfg.class, originalGlobal);
        }
    }

    private String syntheticPlayer(String nickName) {
        String role = nickName.startsWith("b13-history-") ? nickName.substring(12) : "member";
        String compactNickName = "奖分" + role.substring(0, Math.min(4, role.length()))
                + UUID.randomUUID().toString().substring(0, 4);
        return playerInit.init(new PlayerInitReq("b13-" + UUID.randomUUID(),
                "b13-device-" + UUID.randomUUID(), compactNickName, time.serverNow(), "")).playerId();
    }

    private Alliance createSyntheticAlliance(String allianceId, String name, String tag,
                                              String leaderId, long at) {
        Alliance alliance = Alliance.create(allianceId, name, tag, leaderId, 100_000L,
                socialRules.allianceRules());
        social.saveAlliance(alliance, 0L, at);
        return alliance;
    }

    private static String withSeasonStartAndWarBonuses(long winnerBonus) {
        try {
            Path configPath = locateConfigDir().resolve("global.json");
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode table = (ObjectNode) mapper.readTree(Files.readString(configPath, StandardCharsets.UTF_8));
            ArrayNode rows = (ArrayNode) table.get("rows");
            ObjectNode start = mapper.createObjectNode();
            start.put("id", "SEASON_START_AT");
            start.put("valueType", "LONG");
            start.put("value", 1_757_000_000_000L);
            start.put("unit", "毫秒时间戳");
            start.put("source", "B14 §一（部署参数，不进表）");
            start.put("why", "测试注入的赛季锚点：真实环境由部署时配置");
            rows.add(start);
            setGlobalValue(rows, "WAR_SEASON_POINT_WINNER", winnerBonus);
            setGlobalValue(rows, "WAR_SEASON_POINT_PARTICIPANT", 0L);
            setGlobalValue(rows, "WAR_SEASON_INITIATOR_BONUS", 0L);
            return mapper.writeValueAsString(table);
        } catch (Exception failure) {
            throw new IllegalStateException("无法构造Mongo奖分成员历史测试配置", failure);
        }
    }

    private static void setGlobalValue(ArrayNode rows, String key, long value) {
        for (JsonNode row : rows) {
            if (key.equals(row.path("id").asText())) {
                ((ObjectNode) row).put("value", value);
                return;
            }
        }
        throw new IllegalStateException("global 表缺失测试参数：" + key);
    }

    private static Path locateConfigDir() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("contract/config");
            if (Files.isDirectory(candidate)) return candidate;
            directory = directory.getParent();
        }
        throw new IllegalStateException("找不到 contract/config");
    }

    private static Object failAfterDurableWrite(InvocationOnMock invocation, AtomicBoolean failOnce) throws Throwable {
        Object written = invocation.callRealMethod();
        if (failOnce.compareAndSet(true, false)) {
            throw new IllegalStateException("simulated crash after first durable WAR season receipt");
        }
        return written;
    }
}
