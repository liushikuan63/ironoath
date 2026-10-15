package com.ironoath.web.quest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

/** 记录并委托同一个生产PlayerLock；不以测试私有锁代替服务共享互斥。 */
@SpringBootTest @ActiveProfiles("test")
class QuestProgressLockReadTest {
    @Autowired PlayerInitService init;
    @Autowired QuestAppService quests;
    @Autowired QuestEvents events;
    @Autowired TimeService time;
    @SpyBean PlayerLock locks;
    @SpyBean QuestProgressStore store;

    private String id(){return UUID.randomUUID().toString();}
    private String player(){return init.init(new PlayerInitReq(id(),"quest-lock-"+id(),"锁验证",time.serverNow(),"")).playerId();}
    private String qualifiedPlayer(){
        String uid=player();quests.list(uid);
        for(int n=1;n<=10;n++)events.progressOnce(uid,GoalType.UPGRADE_BUILDING,"main_city",1,time.serverNow(),"fixture-lock-building:"+n);
        assertThat(quests.list(uid).quests()).anySatisfy(q->{
            assertThat(q.questId()).isEqualTo("quest_achievement_build_10");
            assertThat(q.current()).isEqualTo(10);assertThat(q.claimed()).isFalse();
        });
        return uid;
    }
    private final class Trace {
        final List<String> beforeLoad=new ArrayList<>(),held=new ArrayList<>();
        boolean seenLoad;int loads,saves;
        RuntimeException nextLoadFailure,nextSaveFailure;
        Trace(String uid){
            Thread caller=Thread.currentThread();
            doAnswer(call->{
                if(Thread.currentThread()!=caller)return call.callRealMethod();
                String key=call.getArgument(0);
                if(!seenLoad)beforeLoad.add(key);
                held.add(key);
                try{return call.callRealMethod();}finally{held.remove(held.size()-1);}
            }).when(locks).runLocked(anyString(),anyLong(),any());
            doAnswer(call->{
                if(uid.equals(call.getArgument(0))&&Thread.currentThread()==caller){
                    assertThat(held).contains(QuestProgressStore.lockKey(uid));seenLoad=true;loads++;
                    if(nextLoadFailure!=null){var failure=nextLoadFailure;nextLoadFailure=null;throw failure;}
                }
                return call.callRealMethod();
            }).when(store).load(anyString());
            doAnswer(call->{
                if(uid.equals(call.getArgument(0))&&Thread.currentThread()==caller){
                    assertThat(held).contains(QuestProgressStore.lockKey(uid));saves++;
                    if(nextSaveFailure!=null){var failure=nextSaveFailure;nextSaveFailure=null;throw failure;}
                }
                return call.callRealMethod();
            }).when(store).save(anyString(),any(QuestProgressStore.State.class));
        }
        void assertPlayerThenProgress(String uid){
            assertThat(loads).isPositive();
            assertThat(beforeLoad).containsExactly(uid,QuestProgressStore.lockKey(uid));
            assertThat(held).isEmpty();
        }
        void restart(){beforeLoad.clear();seenLoad=false;loads=0;saves=0;}
    }
    @Test void panelLoadsUnderOnePlayerThenOneProgressLock(){
        String uid=player();var trace=new Trace(uid);quests.list(uid);trace.assertPlayerThenProgress(uid);
    }
    @Test void claimableLoadsUnderOnePlayerThenOneProgressLock(){
        String uid=player();var trace=new Trace(uid);quests.claimableCount(uid);trace.assertPlayerThenProgress(uid);
    }
    @Test void claimLoadsUnderOnePlayerThenOneProgressLockWithoutForbiddingRewardReentry(){
        String uid=qualifiedPlayer();var trace=new Trace(uid);
        quests.claim(uid,new QuestClaimReq(id(),"quest_achievement_build_10",null));trace.assertPlayerThenProgress(uid);
        assertThat(trace.saves).isPositive();
    }
    @Test void externalLoadedStillAcquiresItsOwnProgressLock(){
        String uid=player();var trace=new Trace(uid);quests.loaded(uid,time.serverNow());
        assertThat(trace.beforeLoad).containsExactly(QuestProgressStore.lockKey(uid));assertThat(trace.held).isEmpty();
    }
    @Test void externalEntryReadStillAcquiresItsOwnProgressLock(){
        String uid=player();var trace=new Trace(uid);quests.entryOf(uid,"quest_achievement_build_10");
        assertThat(trace.beforeLoad).containsExactly(QuestProgressStore.lockKey(uid));assertThat(trace.held).isEmpty();
    }
    @Test void claimLoadFailurePropagatesReleasesLocksAndAllowsSameRequestRetry(){
        String uid=qualifiedPlayer();var trace=new Trace(uid);var failure=new IllegalStateException("load unavailable");
        trace.nextLoadFailure=failure;var request=new QuestClaimReq(id(),"quest_achievement_build_10",null);
        assertThatThrownBy(()->quests.claim(uid,request)).isSameAs(failure);assertThat(trace.held).isEmpty();
        trace.restart();quests.claim(uid,request);trace.assertPlayerThenProgress(uid);
    }
    @Test void rolloverSaveFailurePropagatesAndLaterReadCanPersistUnderTheSameSharedLocks(){
        String uid=player();quests.list(uid);var old=store.load(uid).orElseThrow();
        store.save(uid,new QuestProgressStore.State(uid,old.entries(),"19700101","1970W01"));
        var trace=new Trace(uid);var failure=new IllegalStateException("save unavailable");trace.nextSaveFailure=failure;
        assertThatThrownBy(()->quests.list(uid)).isSameAs(failure);assertThat(trace.saves).isEqualTo(1);assertThat(trace.held).isEmpty();
        trace.restart();quests.list(uid);trace.assertPlayerThenProgress(uid);assertThat(trace.saves).isPositive();
    }
}
