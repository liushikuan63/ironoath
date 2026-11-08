package com.ironoath.core.nation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WarAwardPlanTest {
    private static final long T0 = 1_800_000_000_000L;

    private static WarScoreBoard.Rules rules() {
        return new WarScoreBoard.Rules(60_000, 4, 1, 2, 3, 5, 1, 100, 50);
    }

    private static WarScoreBoard preparationBoard() {
        WarScoreBoard board = new WarScoreBoard(rules(), T0, "owned-NA", T0 + 120_000);
        board.registerNation("owned-NA");
        board.registerNation("owned-NB");
        return board;
    }

    private static WarScoreBoard settledBoard() {
        WarScoreBoard board = preparationBoard();
        for (int gate = 1; gate <= 4; gate++) {
            board.captureGate("owned-NA", "gate_" + gate);
        }
        board.beginSiege(T0 + 1);
        board.captureCapital("owned-NA", T0 + 1);
        board.settle(T0 + 60_001);
        return board;
    }

    private static WarAwardPlan activePlan(long startedAt) {
        return new WarAwardPlan(1, startedAt, "season-1", List.of(
                new WarAwardPlan.Member("player-a", "A", 12),
                new WarAwardPlan.Member("player-b", "B", 7)));
    }

    @Test
    void frozenPlanAndCompletionMarkerSurviveSnapshotRoundTrip() {
        WarScoreBoard board = settledBoard();
        WarAwardPlan plan = activePlan(T0);
        board.freezeSeasonAwardPlan(plan);
        board.markSeasonAwarded(T0 + 60_002);

        WarScoreBoard.Snapshot persisted = board.toSnapshot();
        WarScoreBoard restored = WarScoreBoard.fromSnapshot(persisted, rules());

        assertThat(persisted.seasonAwardPlan()).isEqualTo(plan);
        assertThat(restored.seasonAwardPlan()).isEqualTo(plan);
        assertThat(restored.seasonAwardedAt()).isEqualTo(T0 + 60_002);
        assertThat(restored.settledAt()).isEqualTo(T0 + 60_001);
        assertThat(restored.settledResult()).isEqualTo(board.settledResult());
        assertThat(restored.toSnapshot()).isEqualTo(persisted);
    }

    @Test
    void legacyNineteenFieldSnapshotKeepsOldMarkerButHasNoPlan() {
        WarScoreBoard board = settledBoard();
        board.freezeSeasonAwardPlan(activePlan(T0));
        board.markSeasonAwarded(T0 + 60_002);
        WarScoreBoard.Snapshot current = board.toSnapshot();

        WarScoreBoard.Snapshot legacy = new WarScoreBoard.Snapshot(
                current.startedAt(), current.phase(), current.initiatorNationId(), current.targetNationId(),
                current.nations(), current.capitalHolder(), current.capitalHeldSince(), current.fatigue(),
                current.playerKills(), current.goalClaimed(), current.totalKills(), current.marchFatigueReceipts(),
                current.siegeStartedAt(), current.preparationExpiresAt(), current.abandonedWithoutSiege(),
                current.battleEvents(), current.pendingBattleReservations(), current.settledAt(),
                current.seasonAwardedAt());
        WarScoreBoard restored = WarScoreBoard.fromSnapshot(legacy, rules());

        assertThat(legacy.seasonAwardPlan()).isNull();
        assertThat(restored.seasonAwardPlan()).isNull();
        assertThat(restored.seasonAwardedAt()).isEqualTo(T0 + 60_002);
    }

    @Test
    void onlyMatchingSettledWarCanFreezeAndOnlyPlannedWarCanBeMarkedComplete() {
        WarScoreBoard preparing = preparationBoard();
        WarAwardPlan plan = activePlan(T0);
        assertThatThrownBy(() -> preparing.freezeSeasonAwardPlan(plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("真正结算");
        assertThat(preparing.seasonAwardPlan()).isNull();

        WarScoreBoard settled = settledBoard();
        assertThatThrownBy(() -> settled.markSeasonAwarded(T0 + 70_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未定格");
        assertThat(settled.seasonAwardedAt()).isNull();
        assertThatThrownBy(() -> settled.freezeSeasonAwardPlan(activePlan(T0 + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于本战事");
        assertThat(settled.seasonAwardPlan()).isNull();

        settled.freezeSeasonAwardPlan(plan);
        settled.freezeSeasonAwardPlan(plan);
        assertThat(settled.seasonAwardPlan()).isEqualTo(plan);
        assertThatThrownBy(() -> settled.freezeSeasonAwardPlan(new WarAwardPlan(1, T0, "season-1", List.of(
                new WarAwardPlan.Member("player-a", "A", 13),
                new WarAwardPlan.Member("player-b", "B", 7)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不允许改写");

        settled.markSeasonAwarded(T0 + 70_000);
        settled.markSeasonAwarded(T0 + 80_000);
        assertThat(settled.seasonAwardedAt()).isEqualTo(T0 + 70_000);

        WarScoreBoard abandoned = preparationBoard();
        abandoned.expirePreparation(T0 + 120_000);
        assertThatThrownBy(() -> abandoned.freezeSeasonAwardPlan(activePlan(T0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("真正结算");
        assertThatThrownBy(() -> abandoned.markSeasonAwarded(T0 + 120_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("真正结算");
    }

    @Test
    void snapshotRejectsPlanFromAnotherOrUnsettledWar() {
        WarScoreBoard active = preparationBoard();
        WarScoreBoard.Snapshot snapshot = active.toSnapshot();
        WarAwardPlan matchingPlan = activePlan(T0);
        assertThatThrownBy(() -> new WarScoreBoard.Snapshot(
                snapshot.startedAt(), snapshot.phase(), snapshot.initiatorNationId(), snapshot.targetNationId(),
                snapshot.nations(), snapshot.capitalHolder(), snapshot.capitalHeldSince(), snapshot.fatigue(),
                snapshot.playerKills(), snapshot.goalClaimed(), snapshot.totalKills(), snapshot.marchFatigueReceipts(),
                snapshot.siegeStartedAt(), snapshot.preparationExpiresAt(), snapshot.abandonedWithoutSiege(),
                snapshot.battleEvents(), snapshot.pendingBattleReservations(), snapshot.settledAt(),
                snapshot.seasonAwardedAt(), matchingPlan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("真正结算");

        WarScoreBoard settled = settledBoard();
        WarScoreBoard.Snapshot settledSnapshot = settled.toSnapshot();
        assertThatThrownBy(() -> new WarScoreBoard.Snapshot(
                settledSnapshot.startedAt(), settledSnapshot.phase(), settledSnapshot.initiatorNationId(),
                settledSnapshot.targetNationId(), settledSnapshot.nations(), settledSnapshot.capitalHolder(),
                settledSnapshot.capitalHeldSince(), settledSnapshot.fatigue(), settledSnapshot.playerKills(),
                settledSnapshot.goalClaimed(), settledSnapshot.totalKills(), settledSnapshot.marchFatigueReceipts(),
                settledSnapshot.siegeStartedAt(), settledSnapshot.preparationExpiresAt(),
                settledSnapshot.abandonedWithoutSiege(), settledSnapshot.battleEvents(),
                settledSnapshot.pendingBattleReservations(), settledSnapshot.settledAt(),
                settledSnapshot.seasonAwardedAt(), activePlan(T0 + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不匹配");
    }

    @Test
    void planDefensivelyCopiesMembersAndRequiresCanonicalPositiveRows() {
        List<WarAwardPlan.Member> mutable = new ArrayList<>(List.of(
                new WarAwardPlan.Member("player-a", null, 1)));
        WarAwardPlan plan = new WarAwardPlan(1, T0, "season-1", mutable);
        mutable.clear();

        assertThat(plan.members()).containsExactly(new WarAwardPlan.Member("player-a", null, 1));
        assertThatThrownBy(() -> plan.members().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new WarAwardPlan(2, T0, "season-1", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("版本无效");
        assertThatThrownBy(() -> new WarAwardPlan(1, T0, " ", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得为空白");
        assertThatThrownBy(() -> new WarAwardPlan(1, T0, null, List.of(
                new WarAwardPlan.Member("player-a", "A", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有赛季");
        assertThatThrownBy(() -> new WarAwardPlan(1, T0, "season-1", List.of(
                new WarAwardPlan.Member("player-b", "B", 1),
                new WarAwardPlan.Member("player-a", "A", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("严格升序");
        assertThatThrownBy(() -> new WarAwardPlan.Member("player-a", "A", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分必须为正");
    }
}
