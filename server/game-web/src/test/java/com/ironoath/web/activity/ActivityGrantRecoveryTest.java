package com.ironoath.web.activity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.quest.GoalType;
import com.ironoath.core.reward.*;
import com.ironoath.web.battlepass.BattlePassService;
import com.ironoath.web.dto.generated.ActivityClaimReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.quest.QuestEvents;
import com.ironoath.web.service.PlayerInitService;

/** Legacy mixed window fixtures; exercise the real service/aggregate boundary around durable writes. */
@SpringBootTest @ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ActivityGrantRecoveryTest {
    private static final String ID="activity_monster_hunt",ITEM="item_res_iron_5k";
    private static final long NOW=OffsetDateTime.parse("2026-10-13T12:00:00+08:00").toInstant().toEpochMilli();
    @Autowired ActivityAppService activities;
    @SpyBean ActivityProgressStore store;
    @Autowired PlayerInitService init;
    @Autowired PlayerRepository players;
    @Autowired InventoryRepository inventories;
    @Autowired QuestEvents events;
    @SpyBean BattlePassService battlePass;
    @SpyBean com.ironoath.web.battlepass.BattlePassStore pointsStore;
    @SpyBean com.ironoath.web.battlepass.BattlePassRules pointRules;
    @SpyBean com.ironoath.web.mail.MailStore mails;
    @SpyBean TimeService time;
    @SpyBean RewardService rewards;
    private String uid;
    private long goldBefore,itemBefore,pointsBefore,points;
    @BeforeEach void setup(){
        doReturn(NOW).when(time).serverNow();
        uid=init.init(new PlayerInitReq(id(),"recovery-"+id(),"活动恢复验收",NOW,"")).playerId();
        events.progress(uid,GoalType.KILL_MONSTER,"monster_1",50,NOW);
        activities.list(uid);
        var state=store.load(uid).orElseThrow();
        var instances=state.instances().stream().map(i->ID.equals(i.def().activityId())
                ?new ActivityInstance(i.def(),"旧混合讨伐",List.of(new RewardItem(RewardType.RESOURCE,"GOLD",500),
                    new RewardItem(RewardType.ITEM,ITEM,2)),i.battlePassPoints(),i.startAt(),i.endAt(),i.openedAt()):i).toList();
        store.save(uid,new ActivityProgressStore.State(uid,state.entries(),state.serverOpenMs(),instances));
        points=instances.stream().filter(i->ID.equals(i.def().activityId())).findFirst().orElseThrow().battlePassPoints();
        goldBefore=gold();itemBefore=items();pointsBefore=battlePass.status(uid).points();
    }
    private static String id(){return UUID.randomUUID().toString();}
    private long gold(){return players.findByPlayerId(uid).orElseThrow().resource("GOLD").current();}
    private long items(){return inventories.findByPlayerId(uid).map(i->i.countOf(ITEM)).orElse(0L);}
    private void assertRecovered(){
        assertThat(activities.list(uid).activities().stream().filter(v->ID.equals(v.id())).findFirst().orElseThrow().claimed()).isTrue();
        assertThat(gold()).isEqualTo(goldBefore+500);
        assertThat(items()).isEqualTo(itemBefore+2);
        assertThat(battlePass.status(uid).points()).isEqualTo(pointsBefore+points);
        assertThatThrownBy(()->activities.claim(uid,new ActivityClaimReq(ID,id()))).isInstanceOf(com.ironoath.common.BizException.class);
        activities.claimableCount(uid);
        assertThat(gold()).isEqualTo(goldBefore+500);assertThat(items()).isEqualTo(itemBefore+2);
        assertThat(battlePass.status(uid).points()).isEqualTo(pointsBefore+points);
    }
    private void failReward(boolean after){
        var once=new AtomicBoolean(true);
        doAnswer(call->{RewardContext ctx=call.getArgument(2);
            if(ID.equals(ctx.sourceRef())&&once.getAndSet(false)){if(after)call.callRealMethod();throw new IllegalStateException("fixture: reward boundary");}
            return call.callRealMethod();}).when(rewards).grant(anyString(),any(),any(RewardContext.class));
    }
    @Test void persistedQualificationBeforeAnyRewardRecoversTheOriginalMixedPack(){
        failReward(false);
        assertThatThrownBy(()->activities.claim(uid,new ActivityClaimReq(ID,id()))).isInstanceOf(IllegalStateException.class);
        assertRecovered();
    }
    @Test void acceptedRewardWithLostReplyRecoversOtherComponentsWithoutRepeatingIt(){
        failReward(true);
        assertThatThrownBy(()->activities.claim(uid,new ActivityClaimReq(ID,id()))).isInstanceOf(IllegalStateException.class);
        assertRecovered();
    }
    @Test void qualificationWriteWithLostReplyRecoversWithoutGuessingWhetherTheWriteCommitted(){
        var once=new AtomicBoolean(true);
        doAnswer(call->{ActivityProgressStore.State state=call.getArgument(1);Object result=call.callRealMethod();
            if(state.entries().stream().anyMatch(e->ID.equals(e.activityId())&&e.claimed())&&once.getAndSet(false))throw new IllegalStateException("fixture: state committed ack lost");return result;
        }).when(store).save(eq(uid),any());
        assertThatThrownBy(()->activities.claim(uid,new ActivityClaimReq(ID,id()))).isInstanceOf(IllegalStateException.class);
        assertRecovered();
    }
    @Test void beforePlanCommitFailureDoesNotGiveAnythingAndSameRequestCanRetry(){
        var once=new AtomicBoolean(true);
        doAnswer(call->{ActivityProgressStore.State state=call.getArgument(1);
            if(!state.pendingClaims().isEmpty()&&once.getAndSet(false))throw new IllegalStateException("fixture: before plan commit");return call.callRealMethod();
        }).when(store).save(eq(uid),any());
        String request=id();assertThatThrownBy(()->activities.claim(uid,new ActivityClaimReq(ID,request))).isInstanceOf(IllegalStateException.class);
        assertThat(gold()).isEqualTo(goldBefore);assertThat(items()).isEqualTo(itemBefore);
        assertThat(store.load(uid).orElseThrow().entries().stream().filter(e->ID.equals(e.activityId())).findFirst().orElseThrow().claimed()).isFalse();
        activities.claim(uid,new ActivityClaimReq(ID,request));assertRecovered();
    }
    @Test void itemAcceptanceWithLostReplyDoesNotRecreditGoldOrEitherItem(){
        var once=new AtomicBoolean(true);
        doAnswer(call->{RewardContext ctx=call.getArgument(2);Object value=call.callRealMethod();
            if("free_item".equals(ctx.source())&&ID.equals(ctx.sourceRef())&&once.getAndSet(false))throw new IllegalStateException("fixture: item accepted ack lost");return value;
        }).when(rewards).grant(anyString(),any(),any(RewardContext.class));
        assertThatThrownBy(()->activities.claim(uid,new ActivityClaimReq(ID,id()))).isInstanceOf(IllegalStateException.class);assertRecovered();
        assertThat(inventories.findByPlayerId(uid).orElseThrow().itemClaims().values()).allMatch(r->"free_item".equals(r.source()));
    }
    @Test void pointsAcceptanceWithLostReplyDoesNotAddAgain(){
        var once=new AtomicBoolean(true);
        doAnswer(call->{Object value=call.callRealMethod();if(once.getAndSet(false))throw new IllegalStateException("fixture: points accepted ack lost");return value;
        }).when(pointsStore).update(anyString(),eq(uid),any());
        assertThatThrownBy(()->activities.claim(uid,new ActivityClaimReq(ID,id()))).isInstanceOf(IllegalStateException.class);assertRecovered();
        assertThat(pointsStore.load(battlePass.activitySeasonId(),uid).pointClaims()).hasSize(1);
    }
    @Test void cleanupFailureKeepsPlanAndReplayingItHasNoDuplicateCredit(){
        var once=new AtomicBoolean(true);
        doAnswer(call->{ActivityProgressStore.State state=call.getArgument(1);
            if(state.pendingClaims().isEmpty()&&state.entries().stream().anyMatch(e->ID.equals(e.activityId())&&e.claimed())&&once.getAndSet(false))throw new IllegalStateException("fixture: before cleanup");return call.callRealMethod();
        }).when(store).save(eq(uid),any());
        assertThatThrownBy(()->activities.claim(uid,new ActivityClaimReq(ID,id()))).isInstanceOf(IllegalStateException.class);
        assertThat(store.load(uid).orElseThrow().pendingClaims()).hasSize(1);assertRecovered();
        assertThat(store.load(uid).orElseThrow().pendingClaims()).isEmpty();
    }
    @Test void oldClaimedWithoutAPlanIsNeverGuessedAsUndelivered(){
        var state=store.load(uid).orElseThrow();
        var entries=state.entries().stream().map(e->ID.equals(e.activityId())?new com.ironoath.core.activity.ActivityProgress.Entry(e.activityId(),e.windowStart(),e.value(),true,e.lastLoginAt(),e.facts()):e).toList();
        store.save(uid,new ActivityProgressStore.State(uid,entries,state.serverOpenMs(),state.instances()));
        activities.list(uid);activities.claimableCount(uid);
        assertThat(gold()).isEqualTo(goldBefore);assertThat(items()).isEqualTo(itemBefore);assertThat(battlePass.status(uid).points()).isEqualTo(pointsBefore);
    }
    @Test void pendingOldWindowAndOriginalSeasonSurviveExpiryAndRebuiltService(){
        failReward(false);assertThatThrownBy(()->activities.claim(uid,new ActivityClaimReq(ID,id()))).isInstanceOf(IllegalStateException.class);
        var plan=store.load(uid).orElseThrow().pendingClaims().get(0);
        long after=plan.instance().endAt()+60_000;
        doReturn(after).when(time).serverNow();
        doReturn("fixture-next-season").when(battlePass).activitySeasonId();
        // Rebuild only the service using existing durable ports, as a process recovery would do.
        var restored=new ActivityAppService(getField("anchors"),rewards,getField("names"),getField("idempotency"),getField("playerLock"),time,getField("configs"),battlePass,getField("signIn"),getField("ledger"),players);
        restored.list(uid);
        assertThat(gold()).isEqualTo(goldBefore+500);assertThat(items()).isEqualTo(itemBefore+2);
        assertThat(pointsStore.load(plan.seasonId(),uid).points()).isEqualTo(pointsBefore+points);
        assertThat(pointsStore.load("fixture-next-season",uid).points()).isZero();
        assertThat(store.load(uid).orElseThrow().pendingClaims()).isEmpty();
        restored.list(uid);assertThat(gold()).isEqualTo(goldBefore+500);assertThat(items()).isEqualTo(itemBefore+2);
    }
    @Test void eventAfterExpiryCannotEraseTheAcceptedPlanOrReplaceItsFrozenInstance(){
        failReward(false);assertThatThrownBy(()->activities.claim(uid,new ActivityClaimReq(ID,id()))).isInstanceOf(IllegalStateException.class);
        var plan=store.load(uid).orElseThrow().pendingClaims().get(0);long after=plan.instance().endAt()+60_000;
        doReturn(after).when(time).serverNow();events.progress(uid,GoalType.KILL_MONSTER,"monster_1",1,after);
        assertThat(store.load(uid).orElseThrow().pendingClaims()).containsExactly(plan);
        activities.list(uid);assertThat(gold()).isEqualTo(goldBefore+500);assertThat(items()).isEqualTo(itemBefore+2);
    }
    @Test void oldSeasonSweepBeforeLateRecoveryStillMailsTheNewlyEarnedFrozenTier(){
        var tier=pointRules.tiers().get(0);String season=battlePass.activitySeasonId();
        pointsStore.update(season,uid,p->p.withPoints(Math.max(0,tier.requiredPoints()-points)));
        failReward(false);assertThatThrownBy(()->activities.claim(uid,new ActivityClaimReq(ID,id()))).isInstanceOf(IllegalStateException.class);
        battlePass.sweepSeasonToMail(season);
        assertThat(mails.listOf(uid,NOW).stream().filter(m->"BATTLE_PASS".equals(m.kind()))).isEmpty();
        doReturn("fixture-next-season").when(battlePass).activitySeasonId();
        doReturn(List.of()).when(pointRules).tiers(); // Current table removal must not erase the accepted old tier.
        activities.list(uid);
        var mail=mails.listOf(uid,NOW).stream().filter(m->"BATTLE_PASS".equals(m.kind())).toList();
        assertThat(mail).hasSize(1);
        assertThat(mail.get(0).rewards()).singleElement().satisfies(r->{assertThat(r.id()).isEqualTo(tier.freeRewardId());assertThat(r.count()).isEqualTo(tier.freeRewardCount());});
        assertThat(pointsStore.load(season,uid).claimed((int)tier.tier(),com.ironoath.web.dto.generated.BattlePassTrack.FREE)).isTrue();
        activities.list(uid);assertThat(mails.listOf(uid,NOW).stream().filter(m->"BATTLE_PASS".equals(m.kind()))).hasSize(1);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void lateTierMailBeforeWriteOrLostAckKeepsThePlanAndCannotResetAnEarlyClaim(boolean after){
        var tier=pointRules.tiers().get(0);String season=battlePass.activitySeasonId();
        pointsStore.update(season,uid,p->p.withPoints(Math.max(0,tier.requiredPoints()-points)));
        failReward(false);assertThatThrownBy(()->activities.claim(uid,new ActivityClaimReq(ID,id()))).isInstanceOf(IllegalStateException.class);
        doReturn("fixture-next-season").when(battlePass).activitySeasonId();
        var once=new AtomicBoolean(true);
        doAnswer(call->{com.ironoath.web.mail.MailStore.MailRecord mail=call.getArgument(0);
            if("BATTLE_PASS".equals(mail.kind())&&once.getAndSet(false)){if(after)call.callRealMethod();throw new IllegalStateException("fixture: tier mail boundary");}return call.callRealMethod();
        }).when(mails).save(any());
        assertThatThrownBy(()->activities.list(uid)).isInstanceOf(IllegalStateException.class);
        assertThat(store.load(uid).orElseThrow().pendingClaims()).hasSize(1);
        var current=mails.listOf(uid,NOW).stream().filter(m->"BATTLE_PASS".equals(m.kind())).toList();
        assertThat(current).hasSize(after?1:0);
        if(after)assertThat(mails.claim(uid,current.get(0).mailId(),NOW)).isTrue();
        activities.list(uid);activities.list(uid);
        var recovered=mails.listOf(uid,NOW).stream().filter(m->"BATTLE_PASS".equals(m.kind())).toList();assertThat(recovered).hasSize(1);
        if(after)assertThat(recovered.get(0).claimedAt()).isEqualTo(NOW);
        assertThat(store.load(uid).orElseThrow().pendingClaims()).isEmpty();
        assertThat(gold()).isEqualTo(goldBefore+500);assertThat(items()).isEqualTo(itemBefore+2);
        assertThat(pointsStore.load(season,uid).points()).isEqualTo(tier.requiredPoints());
    }
    @Test void lateTierMarkLostAckKeepsExactlyOneMailAndNoDuplicatePoints(){
        var tier=pointRules.tiers().get(0);String season=battlePass.activitySeasonId();
        pointsStore.update(season,uid,p->p.withPoints(Math.max(0,tier.requiredPoints()-points)));
        failReward(false);assertThatThrownBy(()->activities.claim(uid,new ActivityClaimReq(ID,id()))).isInstanceOf(IllegalStateException.class);
        doReturn("fixture-next-season").when(battlePass).activitySeasonId();var once=new AtomicBoolean(true);
        doAnswer(call->{Object value=call.callRealMethod();var progress=(com.ironoath.web.battlepass.BattlePassStore.Progress)value;
            if(progress.claimed((int)tier.tier(),com.ironoath.web.dto.generated.BattlePassTrack.FREE)&&once.getAndSet(false))throw new IllegalStateException("fixture: tier mark ack lost");return value;
        }).when(pointsStore).update(eq(season),eq(uid),any());
        assertThatThrownBy(()->activities.list(uid)).isInstanceOf(IllegalStateException.class);
        activities.list(uid);
        assertThat(mails.listOf(uid,NOW).stream().filter(m->"BATTLE_PASS".equals(m.kind()))).hasSize(1);
        assertThat(pointsStore.load(season,uid).points()).isEqualTo(tier.requiredPoints());
        assertThat(store.load(uid).orElseThrow().pendingClaims()).isEmpty();
    }
    @Test void latePaidTierUsesOldSeasonUnlockAndDoesNotUnlockTheNewSeason(){
        var tier=pointRules.tiers().get(0);String season=battlePass.activitySeasonId();
        pointsStore.update(season,uid,p->p.withPoints(Math.max(0,tier.requiredPoints()-points)).withPaidUnlocked());
        failReward(false);assertThatThrownBy(()->activities.claim(uid,new ActivityClaimReq(ID,id()))).isInstanceOf(IllegalStateException.class);
        doReturn("fixture-next-season").when(battlePass).activitySeasonId();activities.list(uid);
        var mail=mails.listOf(uid,NOW).stream().filter(m->"BATTLE_PASS".equals(m.kind())).toList();assertThat(mail).hasSize(2);
        assertThat(pointsStore.load(season,uid).claimed((int)tier.tier(),com.ironoath.web.dto.generated.BattlePassTrack.PAID)).isTrue();
        assertThat(pointsStore.load("fixture-next-season",uid).paidUnlocked()).isFalse();
        activities.list(uid);assertThat(mails.listOf(uid,NOW).stream().filter(m->"BATTLE_PASS".equals(m.kind()))).hasSize(2);
    }
    @Test void lateMailRetryAfterRetentionCannotRecreateAClaimableMail(){
        var tier=pointRules.tiers().get(0);String season=battlePass.activitySeasonId();
        pointsStore.update(season,uid,p->p.withPoints(Math.max(0,tier.requiredPoints()-points)));
        failReward(false);assertThatThrownBy(()->activities.claim(uid,new ActivityClaimReq(ID,id()))).isInstanceOf(IllegalStateException.class);
        doReturn("fixture-next-season").when(battlePass).activitySeasonId();var once=new AtomicBoolean(true);
        var attempted=new ArrayList<com.ironoath.web.mail.MailStore.MailRecord>();
        doAnswer(call->{com.ironoath.web.mail.MailStore.MailRecord mail=call.getArgument(0);Object result=call.callRealMethod();
            if("BATTLE_PASS".equals(mail.kind())){attempted.add(mail);if(once.getAndSet(false))throw new IllegalStateException("fixture: persisted mail ack lost");}return result;
        }).when(mails).save(any());
        assertThatThrownBy(()->activities.list(uid)).isInstanceOf(IllegalStateException.class);
        var first=attempted.get(0);assertThat(mails.claim(uid,first.mailId(),NOW)).isTrue();
        doReturn(first.expireAt()+1).when(time).serverNow();mails.purgeExpired(first.expireAt()+1);activities.list(uid);
        assertThat(attempted).hasSize(1);
        var receipt=mails.findById(uid,first.mailId()).orElseThrow();
        assertThat(receipt.createdAt()).isEqualTo(first.createdAt());assertThat(receipt.expireAt()).isEqualTo(first.expireAt());
        assertThat(receipt.claimedAt()).isEqualTo(NOW);assertThat(receipt.claimable(first.expireAt()+1)).isFalse();
        assertThat(store.load(uid).orElseThrow().pendingClaims()).isEmpty();
    }
    @Test void firstMailWriteFailureAfterThirtyDaysStillProvidesItsFirstRealRetentionWindow(){
        var tier=pointRules.tiers().get(0);String season=battlePass.activitySeasonId();
        pointsStore.update(season,uid,p->p.withPoints(Math.max(0,tier.requiredPoints()-points)));
        failReward(false);assertThatThrownBy(()->activities.claim(uid,new ActivityClaimReq(ID,id()))).isInstanceOf(IllegalStateException.class);
        doReturn("fixture-next-season").when(battlePass).activitySeasonId();var once=new AtomicBoolean(true);
        doAnswer(call->{com.ironoath.web.mail.MailStore.MailRecord mail=call.getArgument(0);
            if("BATTLE_PASS".equals(mail.kind())&&once.getAndSet(false))throw new IllegalStateException("fixture: first mail never written");return call.callRealMethod();
        }).when(mails).save(any());
        assertThatThrownBy(()->activities.list(uid)).isInstanceOf(IllegalStateException.class);
        long later=NOW+31L*86_400_000L;doReturn(later).when(time).serverNow();activities.list(uid);
        var received=mails.listOf(uid,later).stream().filter(m->"BATTLE_PASS".equals(m.kind())).toList();
        assertThat(received).hasSize(1);assertThat(received.get(0).createdAt()).isEqualTo(later);assertThat(received.get(0).claimable(later)).isTrue();
        assertThat(store.load(uid).orElseThrow().pendingClaims()).isEmpty();
    }
    @SuppressWarnings("unchecked") private <T> T getField(String name){return (T)org.springframework.test.util.ReflectionTestUtils.getField(activities,name);}
}
