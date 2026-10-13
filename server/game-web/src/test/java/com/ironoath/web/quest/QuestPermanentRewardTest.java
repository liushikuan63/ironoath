package com.ironoath.web.quest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.quest.GoalType;
import com.ironoath.core.quest.QuestProgress;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.QuestClaimReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/** 真生产服务和钱包，资格次数为明确的总线夹具；不冒充十次真实建造或原生玩家验收。 */
@SpringBootTest @ActiveProfiles("test")
class QuestPermanentRewardTest {
    private static final String LOW = "quest_achievement_build_10";
    private static final String MIDDLE = "quest_achievement_build_30";
    @Autowired PlayerInitService init;
    @Autowired PlayerRepository players;
    @Autowired QuestAppService quests;
    @Autowired QuestEvents events;
    @Autowired TimeService time;
    @SpyBean QuestProgressStore store;
    @BeforeEach void clear() { ((InMemoryPlayerStore) players).clear(); store.clear(); }
    private String id() { return UUID.randomUUID().toString(); }
    private String player() { return init.init(new PlayerInitReq(id(), "dev-"+id(), "成就金币验收", time.serverNow(), "")).playerId(); }
    private long gold(String uid) { return players.findByPlayerId(uid).orElseThrow().resource("GOLD").current(); }
    private void qualify(String uid) {
        for (int n=1;n<=10;n++) events.progressOnce(uid, GoalType.UPGRADE_BUILDING, "main_city", 1, time.serverNow(), "fixture-building:"+n);
    }
    private com.ironoath.core.reward.GoldClaimReceipt receipt(String uid) {
        return players.findByPlayerId(uid).orElseThrow().goldClaims().get("achievement:"+LOW);
    }
    @Test void oneQualificationCreatesOneStableWalletReceiptAndPreview() {
        String uid=player(); long before=gold(uid); qualify(uid);
        var preview=quests.list(uid).quests().stream().filter(q->LOW.equals(q.questId())).findFirst().orElseThrow();
        assertThat(preview.rewards()).singleElement().satisfies(r->assertThat(r.count()).isEqualTo(50));
        var response=quests.claim(uid,new QuestClaimReq(id(),LOW,null));
        assertThat(gold(uid)).isEqualTo(before+50);
        assertThat(receipt(uid)).isNotNull();
        assertThat(receipt(uid).requested()).isEqualTo(50);
        assertThat(response.message()).contains("50");
        assertThatThrownBy(()->quests.claim(uid,new QuestClaimReq(id(),LOW,null))).isInstanceOf(RuntimeException.class);
        assertThat(gold(uid)).isEqualTo(before+50);
    }
    @Test void sameRequestIdInTwoAccountsDoesNotCrossTheirClaimScopes() {
        String first=player(),second=player(),request=id();
        long a=gold(first),b=gold(second);qualify(first);qualify(second);
        quests.claim(first,new QuestClaimReq(request,LOW,null));
        quests.claim(second,new QuestClaimReq(request,LOW,null));
        assertThat(gold(first)).isEqualTo(a+50);assertThat(gold(second)).isEqualTo(b+50);
    }
    private void failProjection(String uid, boolean afterCommit) {
        var fail=new AtomicBoolean(true);
        doAnswer(call->{
            QuestProgressStore.State state=call.getArgument(1);
            if(state.entries().stream().anyMatch(e->LOW.equals(e.questId())&&e.claimed())&&fail.compareAndSet(true,false)) {
                if(afterCommit) call.callRealMethod();
                throw new IllegalStateException("fixture projection acknowledgement interrupted");
            }
            return call.callRealMethod();
        }).when(store).save(eq(uid),any(QuestProgressStore.State.class));
    }
    private void projectionRecovery(boolean afterCommit) {
        String uid=player();long before=gold(uid);qualify(uid);failProjection(uid,afterCommit);
        assertThatThrownBy(()->quests.claim(uid,new QuestClaimReq(id(),LOW,null))).isInstanceOf(RuntimeException.class);
        assertThat(gold(uid)).isEqualTo(before+50);
        assertThat(receipt(uid)).isNotNull();
        var refreshed=quests.list(uid).quests().stream().filter(q->LOW.equals(q.questId())).findFirst().orElseThrow();
        assertThat(refreshed.claimed()).isTrue();
        assertThatThrownBy(()->quests.claim(uid,new QuestClaimReq(id(),LOW,null))).isInstanceOf(RuntimeException.class);
        assertThat(gold(uid)).isEqualTo(before+50);
    }
    @Test void failedClaimProjectionRecoversFromAlreadyAcceptedWalletWithoutRegrant() { projectionRecovery(false); }
    @Test void lostClaimProjectionAcknowledgementDoesNotLoseOrRegrantGold() { projectionRecovery(true); }
    @Test void oldQualifiedAmountDrivesPreviewAndPaymentInsteadOfCurrentConfigAmount() {
        String uid=player();long before=gold(uid);quests.list(uid);
        var old=store.load(uid).orElseThrow();
        List<QuestProgress.Entry> entries=old.entries().stream().map(e->MIDDLE.equals(e.questId())
                ? new QuestProgress.Entry(e.questId(),QuestProgress.QuestType.ACHIEVEMENT,GoalType.UPGRADE_BUILDING,
                    null,10,null,10,false,true,Set.of("fixture-old-qualification"),50L) : e).toList();
        store.save(uid,new QuestProgressStore.State(uid,entries,old.dayKey(),old.weekKey()));
        var view=quests.list(uid).quests().stream().filter(q->MIDDLE.equals(q.questId())).findFirst().orElseThrow();
        assertThat(view.goalValue()).isEqualTo(10);
        assertThat(view.rewards()).singleElement().satisfies(r->assertThat(r.count()).isEqualTo(50));
        quests.claim(uid,new QuestClaimReq(id(),MIDDLE,null));
        assertThat(gold(uid)).isEqualTo(before+50);
    }

    private com.ironoath.web.dto.generated.QuestView firstKill(String uid,int level) {
        return quests.list(uid).quests().stream().filter(q->q.questId().equals(String.format("quest_firstkill_lv%02d",level)))
                .findFirst().orElseThrow(()->new AssertionError("Missing first kill level "+level));
    }
    private Long firstAt(Object entry) throws Exception { return (Long)entry.getClass().getMethod("firstVictoryAt").invoke(entry); }
    // Explicit bus qualification fixture: not fifty actual battles or native acceptance.
    private void firstWin(String uid,int level,long at) {
        events.progressOnce(uid,GoalType.valueOf("SOLO_MONSTER_WIN"),Integer.toString(level),1,at,"firstkill:level:"+level);
    }
    @Test void fiftyLevelsBudgetIsFiveHundredAndUidReplaySeasonAreIsolated() throws Exception {
        String a=player(),b=player();long goldA=gold(a),goldB=gold(b),at=time.serverNow();
        assertThat(quests.list(a).quests().stream().filter(q->q.questId().startsWith("quest_firstkill_lv"))).hasSize(50);
        for(int level=1;level<=50;level++) {
            firstWin(a,level,at);firstWin(a,level,at+1000);
            var row=firstKill(a,level);assertThat(row.current()).isEqualTo(1);assertThat(firstAt(row)).isEqualTo(at);
            assertThat(row.claimable()).isTrue();assertThat(firstKill(b,level).current()).isZero();
            quests.claim(a,new QuestClaimReq("shared-request-"+level,row.questId(),null));
            firstWin(a,level,at+2000);
            assertThatThrownBy(()->quests.claim(a,new QuestClaimReq(id(),row.questId(),null))).isInstanceOf(RuntimeException.class);
        }
        assertThat(gold(a)).isEqualTo(goldA+500);assertThat(gold(b)).isEqualTo(goldB);
        firstWin(b,1,at);quests.claim(b,new QuestClaimReq("shared-request-1",firstKill(b,1).questId(),null));
        assertThat(gold(b)).isEqualTo(goldB+10);
        var old=store.load(a).orElseThrow();var restored=QuestProgress.restore(a,old.entries(),old.dayKey(),old.weekKey());
        restored.rollover("20991231","2099W52");store.save(a,new QuestProgressStore.State(a,restored.entries(),restored.dayKey(),restored.weekKey()));
        assertThat(firstKill(a,1).claimed()).isTrue();assertThat(firstAt(firstKill(a,1))).isEqualTo(at);
    }
    @Test void acceptedWalletRestoresFirstVictoryTimeAfterCompleteProjectionLoss() throws Exception {
        String uid=player();long before=gold(uid),at=time.serverNow();firstWin(uid,1,at);
        var initial=store.load(uid).orElseThrow();
        quests.claim(uid,new QuestClaimReq(id(),firstKill(uid,1).questId(),null));
        // Only this fixture UID: discard qualification/claim projection; durable wallet is untouched.
        var lost=initial.entries().stream().map(e->e.questId().equals("quest_firstkill_lv01")
                ? new QuestProgress.Entry(e.questId(),e.type(),e.goalType(),e.goalTarget(),e.goalValue(),e.preQuestId(),0,false,true,Set.of(),10L) : e).toList();
        store.save(uid,new QuestProgressStore.State(uid,lost,initial.dayKey(),initial.weekKey()));
        var recovered=firstKill(uid,1);assertThat(recovered.claimed()).isTrue();assertThat(firstAt(recovered)).isEqualTo(at);
        assertThat(gold(uid)).isEqualTo(before+10);
        assertThatThrownBy(()->quests.claim(uid,new QuestClaimReq(id(),recovered.questId(),null))).isInstanceOf(RuntimeException.class);
        assertThat(gold(uid)).isEqualTo(before+10);
    }

}
