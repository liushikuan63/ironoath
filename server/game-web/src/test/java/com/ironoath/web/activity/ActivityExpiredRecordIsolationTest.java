package com.ironoath.web.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.activity.ActivityProgress;
import com.ironoath.core.event.GameEvent;
import com.ironoath.core.event.GameEventBus;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.quest.GoalType;
import com.ironoath.core.reward.RewardService;
import com.ironoath.web.battlepass.BattlePassService;
import com.ironoath.web.dto.generated.ActivityClaimReq;
import com.ironoath.web.dto.generated.ActivityState;
import com.ironoath.web.dto.generated.ActivityView;
import com.ironoath.web.lock.InJvmPlayerLock;
import com.ironoath.web.reward.RewardNames;
import com.ironoath.web.store.memory.InMemoryActivityProgressStore;
import com.ironoath.web.store.memory.InMemoryActivityRuleHistoryStore;
import com.ironoath.web.store.memory.InMemoryIdempotencyStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/** Manual domain events through the real bus/listener/ledger; not real HELP or battle endpoints. */
class ActivityExpiredRecordIsolationTest {
    private static final String UID = "expired-record-player";
    private static final String HUNT = "activity_monster_hunt";
    private static final String HELP = "activity_squad_help";
    private static final long START = 1_793_660_000_000L;

    @Test
    void unrelatedHelpRetainsExpiredHuntUntilANewMatchingKill() {
        var fixture = new Fixture();
        fixture.publish(GoalType.KILL_MONSTER, 30L);
        var oldInstance = fixture.instance(HUNT);
        var oldEntry = fixture.entry(HUNT);
        assertThat(oldEntry.value()).isEqualTo(30L);
        assertThat(oldEntry.claimed()).isFalse();
        assertThat(oldInstance.endAt()).isNotNull();
        fixture.clock.set(oldInstance.endAt());
        assertThat(fixture.row(HUNT).state()).isEqualTo(ActivityState.EXPIRED);
        assertThat(fixture.entry(HUNT)).isEqualTo(oldEntry);
        long helperBefore = fixture.entry(HELP).value();

        fixture.publish(GoalType.HELP_SQUAD, 1L);

        assertThat(fixture.entry(HUNT))
                .as("unrelated HELP must preserve the original window, positive value, claimed state and facts")
                .isEqualTo(oldEntry);
        assertThat(fixture.instance(HUNT))
                .as("unrelated HELP must preserve the frozen definition, rewards, name and original window")
                .isEqualTo(oldInstance);
        assertThat(fixture.entry(HELP).value()).isEqualTo(helperBefore + 1L);
        assertThat(fixture.row(HUNT).state()).isEqualTo(ActivityState.EXPIRED);
        assertThatThrownBy(() -> fixture.activities.claim(UID, new ActivityClaimReq(HUNT, "expired-rejected")))
                .isInstanceOfSatisfying(BizException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.ACTIVITY_NOT_CLAIMABLE));
        assertThat(fixture.entry(HUNT)).isEqualTo(oldEntry);
        assertThat(fixture.store.load(UID).orElseThrow().pendingClaims()).isEmpty();
        assertThat(fixture.gold()).isEqualTo(100L);

        // A matching event must still open the next round; replacing EVENT with READ would fail here.
        fixture.publish(GoalType.KILL_MONSTER, 1L);
        assertThat(fixture.entry(HUNT).windowStart()).isGreaterThan(oldEntry.windowStart());
        assertThat(fixture.entry(HUNT).value()).isEqualTo(1L);
        assertThat(fixture.entry(HUNT).claimed()).isFalse();
        assertThat(fixture.row(HUNT).state()).isEqualTo(ActivityState.RUNNING);
        assertThat(fixture.gold()).isEqualTo(100L);
    }

    @Test
    void expiredRevisionUsesTheNewGoalButAnOpenWindowStillUsesItsFrozenGoal() throws Exception {
        var fixture = new Fixture();
        fixture.publish(GoalType.KILL_MONSTER, 30L);
        var oldInstance = fixture.instance(HUNT);
        var oldEntry = fixture.entry(HUNT);
        fixture.revise("help");
        fixture.publish(GoalType.HELP_SQUAD, 1L);
        assertThat(fixture.entry(HUNT)).isEqualTo(oldEntry);
        assertThat(fixture.instance(HUNT)).isEqualTo(oldInstance);
        fixture.clock.set(oldInstance.endAt());
        assertThat(fixture.row(HUNT).state()).isEqualTo(ActivityState.EXPIRED);

        fixture.publish(GoalType.KILL_MONSTER, 1L);
        assertThat(fixture.entry(HUNT)).as("the old goal cannot discard progress for a new HELP definition")
                .isEqualTo(oldEntry);
        assertThat(fixture.instance(HUNT)).isEqualTo(oldInstance);
        fixture.publish(GoalType.HELP_SQUAD, 1L);
        assertThat(fixture.entry(HUNT).windowStart()).isGreaterThan(oldEntry.windowStart());
        assertThat(fixture.entry(HUNT).value()).isEqualTo(1L);
        assertThat(fixture.instance(HUNT).def().condition().goalType()).isEqualTo(GoalType.HELP_SQUAD);
        assertThat(fixture.row(HUNT).state()).isEqualTo(ActivityState.RUNNING);
    }

    @Test
    void acceptedPendingPlanKeepsItsOriginalInstanceAcrossBothEventGoals() throws Exception {
        var fixture = new Fixture();
        fixture.publish(GoalType.KILL_MONSTER, fixture.configs.get(com.ironoath.config.cfg.ActivityCfg.class, HUNT).conditionValue());
        assertThatThrownBy(() -> fixture.activities.claim(UID, new ActivityClaimReq(HUNT, "accepted-cut")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No new reward delivery");
        var accepted = fixture.store.load(UID).orElseThrow();
        assertThat(accepted.pendingClaims()).hasSize(1);
        var plan = accepted.pendingClaims().get(0);
        var oldEntry = fixture.entry(HUNT);
        assertThat(oldEntry.claimed()).isTrue();
        fixture.revise("help");
        fixture.clock.set(plan.instance().endAt());
        fixture.publish(GoalType.HELP_SQUAD, 1L);
        fixture.publish(GoalType.KILL_MONSTER, 1L);
        fixture.ledger.load(UID, fixture.clock.get(), ActivityLedger.Mode.READ);
        assertThat(fixture.entry(HUNT)).isEqualTo(oldEntry);
        assertThat(fixture.instance(HUNT)).isEqualTo(plan.instance());
        assertThat(fixture.store.load(UID).orElseThrow().pendingClaims()).containsExactly(plan);
        assertThat(fixture.gold()).isEqualTo(100L);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void retiredOrRemovedCandidateCannotClearAnUnclaimedExpiredRecord(boolean removed) throws Exception {
        var fixture = new Fixture();
        fixture.publish(GoalType.KILL_MONSTER, 30L);
        var oldInstance = fixture.instance(HUNT);
        var oldEntry = fixture.entry(HUNT);
        fixture.revise(removed ? "removed" : "retired");
        fixture.clock.set(oldInstance.endAt());
        fixture.publish(GoalType.KILL_MONSTER, 1L);
        assertThat(fixture.entry(HUNT)).isEqualTo(oldEntry);
        assertThat(fixture.instance(HUNT)).isEqualTo(oldInstance);
        assertThat(fixture.row(HUNT).state()).isEqualTo(ActivityState.EXPIRED);
    }

    @Test
    void legacyThreeArgumentEventLoadKeepsItsExplicitRolloverContract() {
        var fixture = new Fixture();
        fixture.publish(GoalType.KILL_MONSTER, 30L);
        var original = fixture.instance(HUNT);
        fixture.clock.set(original.endAt());
        var rolled = fixture.ledger.load(UID, fixture.clock.get(), ActivityLedger.Mode.EVENT);
        assertThat(rolled.progress().entry(HUNT).windowStart()).isGreaterThan(original.startAt());
        assertThat(rolled.progress().entry(HUNT).value()).isZero();
        assertThat(rolled.pendingClaims()).isEmpty();
    }

    private static final class Fixture {
        final AtomicLong clock = new AtomicLong(START);
        final TimeService time = new TimeService(clock::get);
        final ConfigRegistry configs = ConfigRegistry.loadFromDirectory(configDir());
        final InMemoryPlayerStore players = new InMemoryPlayerStore();
        final InMemoryActivityProgressStore store = new InMemoryActivityProgressStore();
        final GameEventBus bus = new GameEventBus();
        final BattlePassService battlePass = org.mockito.Mockito.mock(BattlePassService.class);
        final ActivityLedger ledger;
        final ActivityAppService activities;

        Fixture() {
            players.insertIfAbsent(PlayerSave.createNew(UID, "expired-record-device", "活动保留夹具", 1,
                    START, 1, Map.of("GOLD", new PlayerResourceState(100L, 100_000L, 0L, 0L, START)),
                    PlayerPower.zero(), null));
            var anchors = new ActivityAnchors(configs, players);
            var history = new ActivityRuleHistory(new InMemoryActivityRuleHistoryStore(), configs, time);
            ledger = new ActivityLedger(store, history, anchors);
            new ActivityEventListener(bus, time, ledger);
            RewardService rewards = (owner, pack, context) -> {
                throw new IllegalStateException("No new reward delivery is allowed in this rejection fixture");
            };
            var names = new RewardNames(configs, null);
            org.mockito.Mockito.when(battlePass.activitySeasonId()).thenReturn("b17-fixture-season");
            org.mockito.Mockito.when(battlePass.activityTierSnapshot()).thenReturn(List.of());
            activities = new ActivityAppService(anchors, rewards, names, new InMemoryIdempotencyStore(),
                    new InJvmPlayerLock(), time, configs, battlePass,
                    new SignInRewards(configs, players, rewards, names), ledger, players);
        }

        void publish(GoalType goal, long amount) {
            bus.publish(GameEvent.progress(UID, goal, "manual-domain-fixture", amount, clock.get()));
            assertThat(bus.listenerFailureCount()).isZero();
        }

        ActivityProgress.Entry entry(String id) {
            return store.load(UID).orElseThrow().entries().stream()
                    .filter(entry -> entry.activityId().equals(id)).findFirst().orElseThrow();
        }

        ActivityInstance instance(String id) {
            return store.load(UID).orElseThrow().instances().stream()
                    .filter(instance -> instance.def().activityId().equals(id)).findFirst().orElseThrow();
        }

        ActivityView row(String id) {
            return activities.list(UID).activities().stream().filter(view -> view.id().equals(id))
                    .findFirst().orElseThrow();
        }

        long gold() { return players.findByPlayerId(UID).orElseThrow().resource("GOLD").current(); }

        void revise(String change) throws Exception {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var table = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                    Files.readString(configDir().resolve("activity.json")));
            var rows = (com.fasterxml.jackson.databind.node.ArrayNode) table.get("rows");
            com.fasterxml.jackson.databind.node.ObjectNode help = null;
            for (var row : rows) if (HELP.equals(row.path("id").asText()))
                help = (com.fasterxml.jackson.databind.node.ObjectNode) row;
            if (help == null) throw new IllegalStateException("HELP fixture rule missing");
            for (int index = 0; index < rows.size(); index++) if (HUNT.equals(rows.get(index).path("id").asText())) {
                var hunt = (com.fasterxml.jackson.databind.node.ObjectNode) rows.get(index);
                switch (change) {
                    case "help" -> hunt.put("activityType", help.path("activityType").asText())
                            .put("conditionType", help.path("conditionType").asText());
                    case "retired" -> hunt.put("retired", true);
                    case "removed" -> rows.remove(index);
                    default -> throw new IllegalArgumentException("Unknown fixture revision");
                }
                break;
            }
            clock.incrementAndGet();
            configs.reload("activity", com.ironoath.config.cfg.ActivityCfg.class, mapper.writeValueAsString(table));
        }
    }

    private static Path configDir() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("contract/config"))) directory = directory.getParent();
        if (directory == null) throw new IllegalStateException("Owned contract/config directory missing");
        return directory.resolve("contract/config");
    }
}
