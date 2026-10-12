package com.ironoath.web.quest;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.ironoath.core.event.GameEvent;
import com.ironoath.core.quest.GoalType;
import com.ironoath.core.quest.QuestProgress;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardType;

class QuestPermanentQualificationTest {
    private static final String PLAYER = "qualification-player";
    private static QuestRulesAssembler.QuestDef def(long goal, long gold) {
        return new QuestRulesAssembler.QuestDef(new QuestProgress.Def("permanent", QuestProgress.QuestType.ACHIEVEMENT,
                GoalType.UPGRADE_BUILDING, null, goal, null, true, gold), "城建历程", List.of(new RewardItem(RewardType.RESOURCE, "GOLD", gold)), List.of());
    }
    private static QuestProgress progress(int facts) {
        var result = QuestProgress.open(PLAYER, List.of(def(10, 50).def()), "20261013", "2026W42");
        for (int n = 1; n <= facts; n++) result.onEvent(GameEvent.progressOnce(PLAYER, GoalType.UPGRADE_BUILDING,
                "main_city", 1, 1_800_000_000_000L, "building:instance:" + n));
        return result;
    }
    private static QuestProgress changed(QuestProgress before) {
        return QuestAppService.reconcile(PLAYER, List.of(def(30, 150)),
                new QuestProgressStore.State(PLAYER, before.entries(), before.dayKey(), before.weekKey()), "20261101", "2026W44");
    }
    @Test void earnedUnclaimedQualificationKeepsItsOriginalGoalWhenConfigChanges() {
        var result = changed(progress(10));
        assertThat(result.entry("permanent").goalValue()).isEqualTo(10);
        assertThat(result.entry("permanent").goldReward()).isEqualTo(50);
        assertThat(result.entry("permanent").complete()).isTrue();
        assertThat(result.entry("permanent").claimed()).isFalse();
        assertThat(result.claimableCount()).isEqualTo(1);
    }
    @Test void claimedPermanentQualificationAlsoKeepsItsOriginalGoal() {
        var before = progress(10);
        before.claim("permanent");
        var result = changed(before);
        assertThat(result.entry("permanent").goalValue()).isEqualTo(10);
        assertThat(result.entry("permanent").claimed()).isTrue();
        assertThat(result.entry("permanent").goldReward()).isEqualTo(50);
        assertThat(result.claimableCount()).isZero();
    }
    @Test void unqualifiedProgressUsesTheNewGoalWithoutLosingAcceptedFacts() {
        var result = changed(progress(9));
        assertThat(result.entry("permanent").goalValue()).isEqualTo(30);
        assertThat(result.entry("permanent").current()).isEqualTo(9);
        assertThat(result.entry("permanent").factIds()).hasSize(9);
        assertThat(result.entry("permanent").goldReward()).isEqualTo(150);
        assertThat(result.claimableCount()).isZero();
    }
}
