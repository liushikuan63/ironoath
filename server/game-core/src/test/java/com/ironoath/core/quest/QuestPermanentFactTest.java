package com.ironoath.core.quest;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.ironoath.core.event.GameEvent;

class QuestPermanentFactTest {
    private static final String PLAYER = "permanent-player";
    private static final long NOW = 1_800_000_000_000L;
    private static final List<QuestProgress.Def> DEFS = List.of(
            new QuestProgress.Def("building-10", QuestProgress.QuestType.ACHIEVEMENT, GoalType.UPGRADE_BUILDING, null, 10, null, true),
            new QuestProgress.Def("building-30", QuestProgress.QuestType.ACHIEVEMENT, GoalType.UPGRADE_BUILDING, null, 30, null, true),
            new QuestProgress.Def("building-60", QuestProgress.QuestType.ACHIEVEMENT, GoalType.UPGRADE_BUILDING, null, 60, null, true));
    private static QuestProgress opened() { return QuestProgress.open(PLAYER, DEFS, "20261013", "2026W42"); }
    private static GameEvent completed(int n) {
        return GameEvent.progressOnce(PLAYER, GoalType.UPGRADE_BUILDING, "main_city", 1, NOW, "building:instance:" + n);
    }
    @Test void repeatedFactsDoNotInflateAnyTier() {
        var progress = opened();
        for (int n = 1; n <= 10; n++) { progress.onEvent(completed(n)); progress.onEvent(completed(n)); }
        assertThat(progress.entry("building-30").current()).isEqualTo(10);
        assertThat(progress.entry("building-60").current()).isEqualTo(10);
        assertThat(progress.claimableCount()).isEqualTo(1);
    }
    @Test void acceptedFactsSurviveRestoreAndNaturalDayOrWeekChanges() {
        var progress = opened();
        progress.onEvent(completed(1));
        var restored = QuestProgress.restore(PLAYER, progress.entries(), "20261013", "2026W42");
        restored.rollover("20261101", "2026W44");
        restored.onEvent(completed(1));
        assertThat(restored.entry("building-10").current()).isEqualTo(1);
    }
    @Test void lowTierClaimDoesNotConsumeHigherTierProgress() {
        var progress = opened();
        for (int n = 1; n <= 10; n++) progress.onEvent(completed(n));
        progress.claim("building-10");
        var restored = QuestProgress.restore(PLAYER, progress.entries(), "20261013", "2026W42");
        for (int n = 1; n <= 60; n++) restored.onEvent(completed(n));
        assertThat(restored.entry("building-10").claimed()).isTrue();
        assertThat(restored.claimableCount()).isEqualTo(2);
        assertThat(restored.entry("building-30").factIds()).hasSize(30);
        assertThat(restored.entry("building-60").factIds()).hasSize(60);
    }
    @Test void unkeyedEventsKeepLegacyDailyBehaviorButCannotAdvancePermanentGoldAchievements() {
        var defs = new java.util.ArrayList<>(DEFS);
        defs.add(new QuestProgress.Def("legacy-daily", QuestProgress.QuestType.DAILY, GoalType.UPGRADE_BUILDING, null, 2, null));
        var progress = QuestProgress.open(PLAYER, defs, "20261013", "2026W42");
        progress.onEvent(GameEvent.progress(PLAYER, GoalType.UPGRADE_BUILDING, "main_city", 1, NOW));
        assertThat(progress.entry("legacy-daily").current()).isEqualTo(1);
        assertThat(progress.entry("building-10").current()).isZero();
    }
    @Test void anotherPlayersFactCannotAdvanceThisPlayersBook() {
        var progress = opened();
        progress.onEvent(GameEvent.progressOnce("other-player", GoalType.UPGRADE_BUILDING, "main_city", 1, NOW, "building:instance:1"));
        assertThat(progress.entry("building-10").current()).isZero();
    }
}
