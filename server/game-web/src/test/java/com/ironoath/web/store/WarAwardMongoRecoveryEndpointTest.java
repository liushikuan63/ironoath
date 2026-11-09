package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.nation.WarAwardPlan;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.season.SeasonSettlement;
import com.ironoath.web.nation.NationMembership;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.nation.WarStore;
import com.ironoath.web.rank.RankBoardService;
import com.ironoath.web.season.CapitalWarWindow;
import com.ironoath.web.season.SeasonBoardStore;
import com.ironoath.web.season.SeasonRulesAssembler;
import com.ironoath.web.service.WarAppService;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.social.SocialRulesAssembler;
import com.ironoath.web.store.mongo.MongoSeasonBoardStore;
import com.ironoath.web.store.mongo.MongoWarStore;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

    private static Object failAfterDurableWrite(InvocationOnMock invocation, AtomicBoolean failOnce) throws Throwable {
        Object written = invocation.callRealMethod();
        if (failOnce.compareAndSet(true, false)) {
            throw new IllegalStateException("simulated crash after first durable WAR season receipt");
        }
        return written;
    }
}
