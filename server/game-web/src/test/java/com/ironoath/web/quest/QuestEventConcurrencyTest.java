package com.ironoath.web.quest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.quest.GoalType;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.QuestClaimReq;
import com.ironoath.web.service.PlayerInitService;

/** 用实际整档存储和同步总线重现：持主人锁的关卡事实与锁外行军事实并发覆盖。 */
@SpringBootTest @ActiveProfiles("test")
class QuestEventConcurrencyTest {
    @Autowired PlayerInitService init;
    @Autowired QuestAppService quests;
    @Autowired QuestEvents events;
    @Autowired PlayerLock locks;
    @Autowired TimeService time;
    @SpyBean QuestProgressStore store;

    private String id(){return UUID.randomUUID().toString();}
    private long current(String uid,String quest){
        return quests.list(uid).quests().stream().filter(q->q.questId().equals(quest)).findFirst().orElseThrow().current();
    }
    @Test void concurrentStageAndMonsterFactsBothSurviveAndStillDeduplicate() throws Exception {
        String uid=overlap("victory");
        assertThat(current(uid,"quest_achievement_pve_10")).isEqualTo(2);
        events.progressOnce(uid,GoalType.PVE_WIN,null,1,time.serverNow(),"pve:stage:test-stage");
        events.progressOnce(uid,GoalType.PVE_WIN,null,1,time.serverNow(),"pve:monster:test-monster");
        assertThat(current(uid,"quest_achievement_pve_10")).isEqualTo(2);
    }
    @Test void legacyKillCannotOverwriteConcurrentPermanentVictory() throws Exception {
        String uid=overlap("legacy");
        assertThat(current(uid,"quest_achievement_pve_10")).isEqualTo(1);
        assertThat(current(uid,"quest_daily_01")).isEqualTo(1);
    }
    @Test void rollingEntryReadCannotOverwriteConcurrentVictory() throws Exception {
        String uid=overlap("read");
        assertThat(current(uid,"quest_achievement_pve_10")).isEqualTo(1);
        assertThat(store.load(uid).orElseThrow().dayKey()).isEqualTo(com.ironoath.common.time.DayKey.of(time.serverNow()));
    }
    @Test void permanentClaimPreservesConcurrentHigherTierFact() throws Exception {
        String uid=overlap("claim");
        assertThat(current(uid,"quest_achievement_pve_50")).isEqualTo(11);
        assertThat(quests.list(uid).quests().stream().filter(q->q.questId().equals("quest_achievement_pve_10"))
                .findFirst().orElseThrow().claimed()).isTrue();
    }
    @Test void oppositeCallerOwnerLocksDoNotInvertLedgerSettlementLocks() throws Exception {
        String a=init.init(new PlayerInitReq(id(),"a-"+id(),"并发甲",time.serverNow(),"")).playerId();
        String b=init.init(new PlayerInitReq(id(),"b-"+id(),"并发乙",time.serverNow(),"")).playerId();
        quests.list(a);quests.list(b);
        var held=new CountDownLatch(2);var pool=Executors.newFixedThreadPool(2);
        try {
            var left=pool.submit(()->locks.runLocked(a,3000,()->{
                held.countDown();awaitHeld(held);
                events.progressOnce(b,GoalType.PVE_WIN,null,1,time.serverNow(),"from-global-due:a");return null;
            }));
            var right=pool.submit(()->locks.runLocked(b,3000,()->{
                held.countDown();awaitHeld(held);
                events.progressOnce(a,GoalType.PVE_WIN,null,1,time.serverNow(),"from-global-due:b");return null;
            }));
            left.get(4,TimeUnit.SECONDS);right.get(4,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();assertThat(pool.awaitTermination(2,TimeUnit.SECONDS)).isTrue();}
        assertThat(current(a,"quest_achievement_pve_10")).isEqualTo(1);
        assertThat(current(b,"quest_achievement_pve_10")).isEqualTo(1);
    }
    private void awaitHeld(CountDownLatch held){
        try {if(!held.await(2,TimeUnit.SECONDS))throw new AssertionError("opposite owner gate timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    private String overlap(String mode) throws Exception {
        String uid=init.init(new PlayerInitReq(id(),"concurrent-"+id(),"并发成就",time.serverNow(),"")).playerId();
        quests.list(uid);
        if(mode.equals("read")){
            events.state(uid,GoalType.REACH_RESOURCE,"GRAIN",0,time.serverNow());
            var old=store.load(uid).orElseThrow();
            // 明确跨日夹具使entryOf真正需要落盘；新号原粮食为0，不冒充资源增长。
            store.save(uid,new QuestProgressStore.State(uid,old.entries(),"19700101","1970W01"));
        }
        if(mode.equals("claim"))for(int n=1;n<=10;n++)events.progressOnce(uid,GoalType.PVE_WIN,null,1,time.serverNow(),"initial:"+n);
        var firstLoaded=new CountDownLatch(1);
        var releaseFirst=new CountDownLatch(1);
        var anySaved=new CountDownLatch(1);
        var secondStarted=new CountDownLatch(1);
        var first=new AtomicBoolean(true);
        doAnswer(call->{
            Object snapshot=call.callRealMethod();
            if(first.compareAndSet(true,false)){
                firstLoaded.countDown();
                if(!releaseFirst.await(2,TimeUnit.SECONDS))throw new AssertionError("first gate timeout");
            }
            return snapshot;
        }).when(store).load(eq(uid));
        doAnswer(call->{Object result=call.callRealMethod();anySaved.countDown();return result;})
                .when(store).save(eq(uid),any(QuestProgressStore.State.class));
        var pool=Executors.newFixedThreadPool(2);
        try {
            var stage=pool.submit(()->{
                if(mode.equals("read"))quests.entryOf(uid,"quest_main_02");
                else if(mode.equals("claim"))quests.claim(uid,new QuestClaimReq(id(),"quest_achievement_pve_10",null));
                else locks.runLocked(uid,3000,()->{
                    events.progressOnce(uid,GoalType.PVE_WIN,null,1,time.serverNow(),"pve:stage:test-stage");return null;
                });
            });
            assertThat(firstLoaded.await(2,TimeUnit.SECONDS)).isTrue();
            var march=pool.submit(()->{
                secondStarted.countDown();
                if(mode.equals("legacy"))events.progress(uid,GoalType.KILL_MONSTER,null,1,time.serverNow());
                else events.progressOnce(uid,GoalType.PVE_WIN,null,1,time.serverNow(),"pve:monster:test-monster");
            });
            assertThat(secondStarted.await(2,TimeUnit.SECONDS)).isTrue();
            // 旧实现在关卡旧快照悬停时先存行军事实；修复后第二个写者等待同主人锁。
            anySaved.await(500,TimeUnit.MILLISECONDS);
            releaseFirst.countDown();
            stage.get(2,TimeUnit.SECONDS);march.get(2,TimeUnit.SECONDS);
        } finally {releaseFirst.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(2,TimeUnit.SECONDS)).isTrue();}
        return uid;
    }
}
