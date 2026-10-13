package com.ironoath.core.activity;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ironoath.core.event.GameEvent;
import com.ironoath.core.quest.GoalType;

class ActivityCampaignProgressTest {
    private static final long START=1_793_476_800_000L, END=START+14*86_400_000L;
    private static final String ID="theme-major";
    private ActivityProgress book() {
        return ActivityProgress.restoreInstances("P", List.of(new ActivityProgress.Def(ID,ActivityType.KILL_MONSTER,
                ActivityCondition.KILL_MONSTER_TOTAL,40,14,false,true)),
                List.of(new ActivityProgress.Entry(ID,START,0,false,0)), START,
                Map.of(ID,new ActivityWindow.Span(START,END)));
    }
    private GameEvent victory(String fact,long at) { return GameEvent.progressOnce("P",GoalType.KILL_MONSTER,"monster",1,at,"solo-monster:"+fact); }
    @Test void bareOrAggregatedOrRallyFactsCannotBuyOrdinaryVictoryQualification() {
        var book=book();
        book.onEvent(GameEvent.progress("P",GoalType.KILL_MONSTER,"monster",40,START));
        book.onEvent(GameEvent.progressOnce("P",GoalType.KILL_MONSTER,"monster",40,START,"solo-monster:bulk"));
        book.onEvent(GameEvent.progressOnce("P",GoalType.KILL_MONSTER,"monster",1,START,"rally:battle"));
        assertThat(book.entry(ID).value()).isZero();
    }
    @Test void repeatedStableSoloVictoryCountsOnlyOnce() {
        var book=book(); book.onEvent(victory("one",START)); book.onEvent(victory("one",START+1));
        assertThat(book.entry(ID).value()).isEqualTo(1);
        assertThat(book.entry(ID).facts()).containsExactly("solo-monster:one");
    }
    @Test void exactDeadlineAndForeignPlayerOrEarlierFactDoNotAdvance() {
        var book=book(); book.onEvent(victory("before",START-1)); book.onEvent(victory("end",END));
        book.onEvent(GameEvent.progressOnce("OTHER",GoalType.KILL_MONSTER,"monster",1,START,"solo-monster:other"));
        assertThat(book.entry(ID).value()).isZero();
    }
    @Test void acceptedFactLedgerSurvivesClaimAndNeverGrowsAfterGoal() {
        var book=book(); for(int i=0;i<45;i++) book.onEvent(victory("battle-"+i,START+i));
        assertThat(book.entry(ID).value()).isEqualTo(40);
        assertThat(book.entry(ID).facts()).hasSize(40);
        book.claim(ID,START+100,START);
        assertThat(book.entry(ID).claimed()).isTrue();
        assertThat(book.entry(ID).facts()).hasSize(40);
    }
}
