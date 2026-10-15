package com.ironoath.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.common.BizException;
import com.ironoath.common.rng.Rng;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.cfg.BuildingCfg;
import com.ironoath.core.bot.BotDecisionTree;
import com.ironoath.core.city.*;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.*;
import com.ironoath.web.bot.*;
import com.ironoath.web.dto.generated.CityUpgradeReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.*;

/** Own real service fixtures; controlled transport/peer hooks expose failure boundaries, not real partitions. */
@SpringBootTest @ActiveProfiles("test")
class CitySelectedUpgradeTest {
    @Autowired CityAppService service;
    @Autowired PlayerInitService init;
    @Autowired BotWorldAdapter adapter;
    @Autowired BotRegistry bots;
    @Autowired BotRulesAssembler rules;
    @SpyBean PlayerRepository players;
    @SpyBean CityRepository cities;
    @SpyBean PlayerLock lock;
    @SpyBean IdempotencyStore idempotency;
    @SpyBean TimeService time;
    long now;

    @BeforeEach void setup() {
        now=System.currentTimeMillis();doReturn(now).when(time).serverNow();adapter.resetCounters();
    }
    String fixture() {
        String run=UUID.randomUUID().toString();
        String uid=init.init(new PlayerInitReq(run,"selected-upgrade-"+run,"城建单锁",now,"")).playerId();
        service.list(uid);var p=players.findByPlayerId(uid).orElseThrow();p.setCityLevel(4);
        for(String id:List.of("WOOD","STONE","IRON","GRAIN"))p.putResource(id,new PlayerResourceState(10000,10000,0,0,now));
        players.save(p);var city=new CityState();
        city.restoreBuilding(new BuildingInstance("selected-main","main_city",4,3,3));
        city.restoreBuilding(new BuildingInstance("selected-wood","lumber_camp",1,1,1));
        cities.save(uid,city,cities.versionOf(uid));return uid;
    }
    void bot(String uid) { bots.register(rules.archetypes().stream().filter(a->a.id().equals("bot_paoyao")).findFirst().orElseThrow().spawnProfile(uid,1,Rng.of(99))); }
    String request() {return "selected-"+UUID.randomUUID();}
    long playerVersion(String uid){return players.findByPlayerId(uid).orElseThrow().version();}
    long cityVersion(String uid){return cities.findVersionedByPlayerId(uid).orElseThrow().version();}
    long stone(String uid){return players.findByPlayerId(uid).orElseThrow().resource("STONE").current();}
    CityState city(String uid){return cities.findByPlayerId(uid).orElseThrow();}
    Optional<CityAppService.SelectedUpgrade> selected(String uid,String key){return service.upgradeFirstAvailable(uid,BuildingCfg.Type.RESOURCE,()->key);}
    void clearCalls(){clearInvocations(players,cities,lock,idempotency,time);}
    void fullQueues(String uid){var c=city(uid);for(var b:c.buildings())c.startUpgrade(b.instanceId(),3600,2,now);cities.save(uid,c,cityVersion(uid));}
    void execute(String uid){adapter.execute(uid,new BotDecisionTree.Decision(BotDecisionTree.Action.UPGRADE_BUILDING,false,0,"selected fixture"),now);}

    @Test void successKeepsTwoAuthoritativeLoadsAndBothSaveVersionsInsideOneLock() {
        String uid=fixture(),key=request();long p=playerVersion(uid),c=cityVersion(uid),before=stone(uid);clearCalls();
        var result=selected(uid,key).orElseThrow();
        assertThat(result.target().configId()).isEqualTo("lumber_camp");
        assertThat(result.response().finishAt()).isGreaterThan(now);
        verify(lock).runLocked(eq(uid),anyLong(),any());
        verify(cities,times(2)).findVersionedByPlayerId(uid);
        verify(cities,times(2)).save(eq(uid),any(),anyLong());verify(players,times(2)).save(argThat(s->s.playerId().equals(uid)));
        assertThat(playerVersion(uid)).isEqualTo(p+2);assertThat(cityVersion(uid)).isEqualTo(c+2);
        assertThat(stone(uid)).isLessThan(before);assertThat(city(uid).findByConfigId("lumber_camp").status()).isEqualTo(BuildingStatus.UPGRADING);
        verify(idempotency,never()).release(key);
        assertThat(idempotency.tryAcquire(key,now,10000)).as("successful request remains reserved").isFalse();
    }
    @Test void noTargetStillPersistsSelectionWithoutGeneratingOrReservingARequest() {
        String uid=fixture();fullQueues(uid);long p=playerVersion(uid),c=cityVersion(uid);clearCalls();
        AtomicInteger generated=new AtomicInteger();
        assertThat(service.upgradeFirstAvailable(uid,BuildingCfg.Type.RESOURCE,()->{generated.incrementAndGet();throw new AssertionError("no request without target");})).isEmpty();
        assertThat(generated).hasValue(0);assertThat(playerVersion(uid)).isEqualTo(p+1);assertThat(cityVersion(uid)).isEqualTo(c+1);
        verify(idempotency,never()).tryAcquire(anyString(),anyLong(),anyLong());verify(idempotency,never()).release(anyString());
    }
    @Test void secondLoadFailureReleasesOnlyItsClaimAndKeepsFirstSelectionWrites() {
        String uid=fixture(),key=request();long p=playerVersion(uid),c=cityVersion(uid),before=stone(uid);clearCalls();
        RuntimeException fault=new IllegalStateException("owned second load failed");AtomicInteger reads=new AtomicInteger();
        doAnswer(call->{if(reads.incrementAndGet()==2)throw fault;return call.callRealMethod();}).when(cities).findVersionedByPlayerId(uid);
        assertThatThrownBy(()->selected(uid,key)).isSameAs(fault);
        assertThat(reads).hasValue(2);verify(idempotency).release(key);verify(lock).runLocked(eq(uid),anyLong(),any());
        assertThat(playerVersion(uid)).isEqualTo(p+1);assertThat(cityVersion(uid)).isEqualTo(c+1);
        assertThat(stone(uid)).isEqualTo(before);assertThat(city(uid).findByConfigId("lumber_camp").status()).isEqualTo(BuildingStatus.IDLE);
    }
    @Test void initialSaveFailureCannotReserveOrReleaseAnUnownedRequest() {
        String uid=fixture(),key=request();RuntimeException fault=new IllegalStateException("owned selection write failed");clearCalls();
        doThrow(fault).when(players).save(argThat(p->p!=null&&p.playerId().equals(uid)));
        AtomicInteger generated=new AtomicInteger();
        assertThatThrownBy(()->service.upgradeFirstAvailable(uid,BuildingCfg.Type.RESOURCE,()->{generated.incrementAndGet();return key;})).isSameAs(fault);
        assertThat(generated).hasValue(0);verify(cities,never()).save(eq(uid),any(),anyLong());
        verify(idempotency,never()).tryAcquire(anyString(),anyLong(),anyLong());verify(idempotency,never()).release(anyString());
    }
    @Test void deniedClaimDoesNotReleaseThePreviousOwnerAndRetainsFirstWrites() {
        String uid=fixture(),key=request();long p=playerVersion(uid),c=cityVersion(uid);clearCalls();
        doReturn(false).when(idempotency).tryAcquire(eq(key),anyLong(),anyLong());
        assertThatThrownBy(()->selected(uid,key)).isInstanceOf(BizException.class).hasMessageContaining("requestId=");
        assertThat(playerVersion(uid)).isEqualTo(p+1);assertThat(cityVersion(uid)).isEqualTo(c+1);verify(idempotency,never()).release(key);
    }
    @Test void acquisitionExceptionDoesNotPretendTheRequestWasAcquired() {
        String uid=fixture(),key=request();RuntimeException fault=new IllegalStateException("owned acquire reply failed");clearCalls();
        doThrow(fault).when(idempotency).tryAcquire(eq(key),anyLong(),anyLong());
        assertThatThrownBy(()->selected(uid,key)).isSameAs(fault);verify(idempotency,never()).release(key);
    }
    @Test void failedSelectionCitySaveKeepsTheEarlierPlayerWriteWithoutCreatingARequest() {
        String uid=fixture(),key=request();long p=playerVersion(uid),c=cityVersion(uid);RuntimeException fault=new IllegalStateException("owned selection city write failed");clearCalls();
        doThrow(fault).when(cities).save(eq(uid),any(),anyLong());AtomicInteger generated=new AtomicInteger();
        assertThatThrownBy(()->service.upgradeFirstAvailable(uid,BuildingCfg.Type.RESOURCE,()->{generated.incrementAndGet();return key;})).isSameAs(fault);
        assertThat(playerVersion(uid)).isEqualTo(p+1);assertThat(cityVersion(uid)).isEqualTo(c);assertThat(generated).hasValue(0);
        verify(idempotency,never()).tryAcquire(anyString(),anyLong(),anyLong());verify(idempotency,never()).release(anyString());
    }
    @Test void ordinaryUpgradeAlsoReleasesAfterItsFinalUnlockFailure() {
        String uid=fixture(),key=request();RuntimeException fault=new IllegalStateException("owned ordinary unlock failed");clearCalls();List<String> order=new ArrayList<>();
        doAnswer(call->{call.callRealMethod();order.add("unlock");throw fault;}).when(lock).runLocked(eq(uid),anyLong(),any());
        doAnswer(call->{order.add("release");return call.callRealMethod();}).when(idempotency).release(key);
        assertThatThrownBy(()->service.upgrade(uid,new CityUpgradeReq(key,"lumber_camp",null,null))).isSameAs(fault);
        assertThat(city(uid).findByConfigId("lumber_camp").status()).isEqualTo(BuildingStatus.UPGRADING);
        assertThat(order).containsExactly("unlock","release");verify(idempotency).release(key);
    }
    @Test void finalUnlockFailureAfterRealUpgradeReleasesTheClaimExactlyOnce() {
        String uid=fixture(),key=request();RuntimeException fault=new IllegalStateException("owned unlock reply failed");clearCalls();
        List<String> order=new ArrayList<>();
        doAnswer(call->{Object result=call.callRealMethod();order.add("unlock");throw fault;}).when(lock).runLocked(eq(uid),anyLong(),any());
        doAnswer(call->{order.add("release");return call.callRealMethod();}).when(idempotency).release(key);
        assertThatThrownBy(()->selected(uid,key)).isSameAs(fault);
        assertThat(city(uid).findByConfigId("lumber_camp").status()).isEqualTo(BuildingStatus.UPGRADING);
        assertThat(order).containsExactly("unlock","release");verify(idempotency,times(1)).release(key);
    }
    @Test void businessFailureUnlocksBeforeReleasingAndAReleaseFailureIsNotRetried() {
        String uid=fixture(),key=request();RuntimeException business=new IllegalStateException("owned read failed"),release=new IllegalStateException("owned release failed");clearCalls();
        AtomicInteger reads=new AtomicInteger();List<String> order=new ArrayList<>();
        doAnswer(call->{if(reads.incrementAndGet()==2)throw business;return call.callRealMethod();}).when(cities).findVersionedByPlayerId(uid);
        doAnswer(call->{try{return call.callRealMethod();}finally{order.add("unlock");}}).when(lock).runLocked(eq(uid),anyLong(),any());
        doAnswer(call->{order.add("release");throw release;}).when(idempotency).release(key);
        assertThatThrownBy(()->selected(uid,key)).isSameAs(release);
        assertThat(order).containsExactly("unlock","release");verify(idempotency,times(1)).release(key);
    }
    @Test void ordinaryUpgradeStillReservesBeforeItsIndependentLock() {
        String uid=fixture(),key=request();clearCalls();List<String> order=new ArrayList<>();
        doAnswer(call->{order.add("reserve");return call.callRealMethod();}).when(idempotency).tryAcquire(eq(key),anyLong(),anyLong());
        doAnswer(call->{order.add("lock");return call.callRealMethod();}).when(lock).runLocked(eq(uid),anyLong(),any());
        service.upgrade(uid,new CityUpgradeReq(key,"lumber_camp",null,null));
        assertThat(order).containsExactly("reserve","lock");verify(idempotency,never()).release(key);
    }
    @Test void firstTimeIsSampledBeforeLockAndUpgradeTakesAFreshTimeInsideIt() {
        String uid=fixture(),key=request();clearCalls();List<Boolean> sampledInside=new ArrayList<>();AtomicBoolean inside=new AtomicBoolean();AtomicInteger samples=new AtomicInteger();
        doAnswer(call->{sampledInside.add(inside.get());return now+500L*samples.getAndIncrement();}).when(time).serverNow();
        doAnswer(call->{inside.set(true);try{return call.callRealMethod();}finally{inside.set(false);}}).when(lock).runLocked(eq(uid),anyLong(),any());
        var result=selected(uid,key).orElseThrow();
        assertThat(sampledInside).startsWith(false,true);assertThat(samples.get()).isGreaterThanOrEqualTo(2);
        verify(idempotency).tryAcquire(eq(key),eq(now+500),anyLong());assertThat(result.response().finishAt()).isGreaterThan(now+500);
    }
    @Test void adapterDistinguishesSelectionFailureFromPostRequestFailure() {
        String first=fixture();bot(first);RuntimeException fault=new IllegalStateException("owned adapter first save failed");clearCalls();
        doThrow(fault).when(players).save(argThat(p->p!=null&&p.playerId().equals(first)));execute(first);
        assertThat(adapter.actionCounts()).containsEntry("failed",1L).containsEntry("skipped",1L).containsEntry("upgraded",0L);
        String second=fixture();bot(second);adapter.resetCounters();
        doThrow(new IllegalStateException("owned adapter acquire failed")).when(idempotency).tryAcquire(anyString(),anyLong(),anyLong());execute(second);
        assertThat(adapter.actionCounts()).containsEntry("failed",1L).containsEntry("skipped",0L).containsEntry("upgraded",0L);
    }
    @Test void adapterNoTargetSkipsWithoutAnyRequestReservation() {
        String uid=fixture();bot(uid);fullQueues(uid);clearCalls();execute(uid);
        assertThat(adapter.actionCounts()).containsEntry("failed",0L).containsEntry("skipped",1L).containsEntry("upgraded",0L);
        verify(idempotency,never()).tryAcquire(anyString(),anyLong(),anyLong());
    }
    @Test void peerCityWriteAfterSecondReadIsRejectedWithoutOverwritingPeerOrPretendingRollback() {
        String uid=fixture(),key=request();long p=playerVersion(uid),before=stone(uid);clearCalls();AtomicInteger reads=new AtomicInteger();AtomicBoolean peerWrote=new AtomicBoolean();
        doAnswer(call->{
            @SuppressWarnings("unchecked") var loaded=(Optional<CityRepository.VersionedCity>)call.callRealMethod();
            if(reads.incrementAndGet()==2) {
                var original=loaded.orElseThrow();var peer=CityState.fromSnapshot(new CityState.Snapshot(original.state().snapshot().buildings(),1));
                cities.save(uid,peer,original.version());peerWrote.set(true);
            }
            return loaded;
        }).when(cities).findVersionedByPlayerId(uid);
        assertThatThrownBy(()->selected(uid,key)).isInstanceOf(IllegalStateException.class).hasMessageContaining("乐观锁冲突");
        assertThat(peerWrote).isTrue();assertThat(city(uid).extraQueues()).isEqualTo(1);
        assertThat(city(uid).findByConfigId("lumber_camp").status()).isEqualTo(BuildingStatus.IDLE);
        assertThat(playerVersion(uid)).as("original player save precedes city CAS; no multi-document rollback").isEqualTo(p+2);
        assertThat(stone(uid)).isLessThan(before);verify(idempotency).release(key);
    }
    @Test void samePlayerCannotInterleaveBetweenSelectionAndUpgradeWhileAnotherPlayerCanProceed() throws Exception {
        String uid=fixture(),other=fixture(),key=request();clearCalls();
        CountDownLatch selected=new CountDownLatch(1),release=new CountDownLatch(1),attempted=new CountDownLatch(1),entered=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        doAnswer(call->{if(calls.incrementAndGet()==2)attempted.countDown();return call.callRealMethod();}).when(lock).runLocked(eq(uid),anyLong(),any());
        ExecutorService workers=Executors.newFixedThreadPool(3);Future<?> first=null,same=null;
        try {
            first=workers.submit(()->service.upgradeFirstAvailable(uid,BuildingCfg.Type.RESOURCE,()->{
                selected.countDown();try {if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("owned supplier latch timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return key;
            }));
            assertThat(selected.await(5,TimeUnit.SECONDS)).isTrue();
            same=workers.submit(()->lock.runLocked(uid,3000,()->{entered.countDown();return null;}));
            assertThat(attempted.await(2,TimeUnit.SECONDS)).isTrue();assertThat(entered.await(100,TimeUnit.MILLISECONDS)).isFalse();
            assertThat(workers.submit(()->lock.runLocked(other,1000,()->42)).get(2,TimeUnit.SECONDS)).isEqualTo(42);
            release.countDown();assertThat(first.get(5,TimeUnit.SECONDS)).isInstanceOf(Optional.class);same.get(5,TimeUnit.SECONDS);
            assertThat(entered.getCount()).isZero();assertThat(city(uid).findByConfigId("lumber_camp").status()).isEqualTo(BuildingStatus.UPGRADING);
        } finally {
            release.countDown();workers.shutdown();assertThat(workers.awaitTermination(10,TimeUnit.SECONDS)).isTrue();
        }
    }
}
