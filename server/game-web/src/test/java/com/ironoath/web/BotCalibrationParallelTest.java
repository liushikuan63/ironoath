package com.ironoath.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.common.rng.Rng;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.bot.BotCalibrationService;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.bot.BotRulesAssembler;
import com.ironoath.web.bot.BotRuntimeService;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;

/** Actual Spring Runtime entry; only this context's owned memory players are written. */
@SpringBootTest(properties={"ironoath.storage=memory", "ironoath.bot.calibration-workers=2", "ironoath.test.fixture=calibration-parallel"})
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class BotCalibrationParallelTest {
    private long now;
    @Autowired PlayerInitService init;
    @Autowired BotRegistry bots;
    @SpyBean BotRulesAssembler rules;
    @Autowired WorldRepository world;
    @Autowired BotRuntimeService runtime;
    @Autowired BotCalibrationService calibration;
    @SpyBean PlayerRepository players;
    @SpyBean PlayerLock playerLock;
    @SpyBean TimeService time;
    private final Set<String> owned=ConcurrentHashMap.newKeySet();
    private final ThreadLocal<String> insideRealLock=new ThreadLocal<>();

    @BeforeEach void prepare() {
        now=time.serverNow();doReturn(now).when(time).serverNow();
        runtime.reset();calibration.reset();
        doAnswer(call->{
            String previous=insideRealLock.get();insideRealLock.set(call.getArgument(0));
            try { return call.callRealMethod(); }
            finally { if(previous==null)insideRealLock.remove();else insideRealLock.set(previous); }
        }).when(playerLock).runLocked(anyString(),anyLong(),any());
    }

    @AfterEach void cleanup() { owned.forEach(bots::unregister);runtime.reset(); }

    private String bot(int x) {
        String token=UUID.randomUUID().toString();
        String id=init.init(new PlayerInitReq(token,"parallel-"+token,"校准并发",now,"")).playerId();
        owned.add(id);
        assertThat(world.placeCity(id,Coord.of(x,100))).isTrue();
        bots.register(rules.archetypes().get(0).spawnProfile(id,7,Rng.of(x)));
        return id;
    }

    private void capAtOne() {
        var r=rules.tuningRules();
        doReturn(new com.ironoath.core.bot.BotTuning.Rules(r.powerRatioMinFixed(),r.powerRatioMaxFixed(),
                r.checkRatioMinFixed(),r.checkRatioMaxFixed(),r.attackLimitPer24h(),1,
                r.densityD1Fixed(),r.densityD7Fixed(),r.densityD30Fixed())).when(rules).tuningRules();
    }

    private static void awaitWrite(CountDownLatch latch) {
        try { if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Owned write gate timeout"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new AssertionError(e); }
    }
    private static void join(Thread thread) throws InterruptedException {
        thread.join(5000);assertThat(thread.isAlive()).as("自有测试调用线程退出").isFalse();
    }

    @Test void runtimeCalibrationOverlapsDifferentPlayerWritesButReturnsAfterBoth() throws Exception {
        String first=bot(100),second=bot(110);
        CountDownLatch entered=new CountDownLatch(2);
        AtomicInteger active=new AtomicInteger(),maxActive=new AtomicInteger();
        Set<String> observed=ConcurrentHashMap.newKeySet();
        Set<Thread> threads=ConcurrentHashMap.newKeySet();
        doAnswer(call->{
            PlayerSave save=call.getArgument(0);
            if(owned.contains(save.playerId()) && Long.valueOf(now).equals(save.lastBotCalibrationAt()) && observed.add(save.playerId())) {
                assertThat(insideRealLock.get()).as("校准保存经过真实玩家锁调用").isEqualTo(save.playerId());
                threads.add(Thread.currentThread());maxActive.accumulateAndGet(active.incrementAndGet(),Math::max);entered.countDown();
                try {
                    entered.await(2,TimeUnit.SECONDS); // old serial path times out here, then reports max=1; no exception-based fake red
                    return call.callRealMethod();
                } finally { active.decrementAndGet(); }
            }
            return call.callRealMethod();
        }).when(players).save(any(PlayerSave.class));
        runtime.tick(now);
        assertThat(observed).containsExactlyInAnyOrder(first,second);
        assertThat(maxActive.get()).as("实际Runtime的不同玩家校准保存应有界重叠").isEqualTo(2);
        assertThat(active.get()).isZero();
        assertThat(threads).hasSize(2).allSatisfy(thread->assertThat(thread.isAlive()).as("调用返回后本次worker已退出").isFalse());
        assertThat(players.findByPlayerId(first).orElseThrow().lastBotCalibrationAt()).isEqualTo(now);
        assertThat(players.findByPlayerId(second).orElseThrow().lastBotCalibrationAt()).isEqualTo(now);
    }

    @Test void ackFailureDrainsWorkersAndRetryUsesPersistedMarkers() throws Exception {
        String first=bot(100),second=bot(110);
        capAtOne();
        var failure=new IllegalStateException("owned save acknowledgement lost");
        CountDownLatch peerEntered=new CountDownLatch(1),releasePeer=new CountDownLatch(1);
        AtomicReference<String> failedPlayer=new AtomicReference<>();
        AtomicReference<Throwable> caught=new AtomicReference<>();
        AtomicBoolean peerCommitted=new AtomicBoolean(),returnedBeforePeer=new AtomicBoolean();
        Set<String> intercepted=ConcurrentHashMap.newKeySet();
        var saves=new ConcurrentHashMap<String,AtomicInteger>();
        doAnswer(call->{
            PlayerSave save=call.getArgument(0);
            if(owned.contains(save.playerId()) && Long.valueOf(now).equals(save.lastBotCalibrationAt())) {
                saves.computeIfAbsent(save.playerId(),id->new AtomicInteger()).incrementAndGet();
                assertThat(insideRealLock.get()).isEqualTo(save.playerId());
                if(intercepted.add(save.playerId())) {
                    if(failedPlayer.compareAndSet(null,save.playerId())) {
                        call.callRealMethod();
                        peerEntered.await(1,TimeUnit.SECONDS);
                        throw failure;
                    }
                    peerEntered.countDown();awaitWrite(releasePeer);
                    Object result=call.callRealMethod();peerCommitted.set(true);return result;
                }
            }
            return call.callRealMethod();
        }).when(players).save(any(PlayerSave.class));
        Thread caller=new Thread(()->{
            try { runtime.tick(now); }
            catch(Throwable e) { caught.set(e); }
            finally { returnedBeforePeer.set(!peerCommitted.get()); }
        });
        caller.start();
        try { assertThat(peerEntered.await(3,TimeUnit.SECONDS)).isTrue(); }
        finally { releasePeer.countDown();join(caller); }
        assertThat(caught.get()).isSameAs(failure);assertThat(returnedBeforePeer).isFalse();
        assertThat(bots.isBot(first)).as("失败批次不得执行超限回收").isTrue();
        assertThat(bots.isBot(second)).isTrue();
        assertThat(players.findByPlayerId(first).orElseThrow().lastBotCalibrationAt()).isEqualTo(now);
        assertThat(players.findByPlayerId(second).orElseThrow().lastBotCalibrationAt()).isEqualTo(now);
        assertThat(calibration.calibrateIfNewDay(now)).as("失败批次没有推进本实例日缓存").isTrue();
        assertThat(saves.keySet()).containsExactlyInAnyOrder(first,second);
        assertThat(saves.values()).allSatisfy(count->assertThat(count.get()).as("已持久完成的同日校准不因重试重复save").isEqualTo(1));
        assertThat(owned.stream().filter(bots::isBot).count()).as("成功重试才回收超限画像").isEqualTo(1);
        assertThat(runtime.enrolledCount()).isEqualTo(1);
    }

    @Test void overCapRecyclingWaitsForEveryAcceptedCalibrationAndKeepsSavesAndCities() throws Exception {
        String first=bot(100),second=bot(110);capAtOne();
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        AtomicReference<Throwable> caught=new AtomicReference<>();
        Set<String> observed=ConcurrentHashMap.newKeySet();
        doAnswer(call->{
            PlayerSave save=call.getArgument(0);
            if(owned.contains(save.playerId()) && Long.valueOf(now).equals(save.lastBotCalibrationAt()) && observed.add(save.playerId())) {
                entered.countDown();awaitWrite(release);
            }
            return call.callRealMethod();
        }).when(players).save(any(PlayerSave.class));
        Thread caller=new Thread(()->{try {runtime.tick(now);}catch(Throwable e){caught.set(e);}});
        caller.start();
        try {
            assertThat(entered.await(2,TimeUnit.SECONDS)).as("超限时两份真实校准写入都必须先进入").isTrue();
            assertThat(bots.isBot(first)).isTrue();assertThat(bots.isBot(second)).isTrue();
        } finally {release.countDown();join(caller);}
        assertThat(caught.get()).isNull();
        assertThat(observed).containsExactlyInAnyOrder(first,second);
        assertThat(players.findByPlayerId(first).orElseThrow().lastBotCalibrationAt()).isEqualTo(now);
        assertThat(players.findByPlayerId(second).orElseThrow().lastBotCalibrationAt()).isEqualTo(now);
        assertThat(world.cityOf(first)).isPresent();assertThat(world.cityOf(second)).isPresent();
        assertThat(owned.stream().filter(bots::isBot).count()).isEqualTo(1);
        assertThat(runtime.enrolledCount()).as("回收同时撤销原运行时登记与排期").isEqualTo(1);
    }

    @Test void legacyEntryKeepsCallerThreadAndPlayerLockReentrancy() {
        String first=bot(100),second=bot(110);Thread caller=Thread.currentThread();
        Set<Thread> observed=ConcurrentHashMap.newKeySet();
        doAnswer(call->{
            PlayerSave save=call.getArgument(0);
            if(owned.contains(save.playerId()) && Long.valueOf(now).equals(save.lastBotCalibrationAt())) {
                assertThat(insideRealLock.get()).isEqualTo(save.playerId());observed.add(Thread.currentThread());
            }
            return call.callRealMethod();
        }).when(players).save(any(PlayerSave.class));
        boolean calibrated=playerLock.runLocked(first,3000,()->calibration.calibrateIfNewDay(now));
        assertThat(calibrated).isTrue();assertThat(observed).containsExactly(caller);
        assertThat(players.findByPlayerId(second).orElseThrow().lastBotCalibrationAt()).isEqualTo(now);
        verify(players,never()).supportsIndependentPlayerLocks(any());
    }

    @Test void waitingWorkerRechecksProfileAfterAcquiringTheRealPlayerLock() throws Exception {
        String first=bot(100),second=bot(110);
        long before=players.findByPlayerId(first).orElseThrow().version();
        CountDownLatch held=new CountDownLatch(1),attempted=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicReference<Thread> holderRef=new AtomicReference<>();AtomicReference<Throwable> caught=new AtomicReference<>(),holderError=new AtomicReference<>();
        doAnswer(call->{
            if(Thread.currentThread()!=holderRef.get())attempted.countDown();
            String previous=insideRealLock.get();insideRealLock.set(first);
            try { return call.callRealMethod(); }
            finally { if(previous==null)insideRealLock.remove();else insideRealLock.set(previous); }
        }).when(playerLock).runLocked(eq(first),anyLong(),any());
        Thread holder=new Thread(()->{
            try { playerLock.runLocked(first,3000,()->{held.countDown();awaitWrite(release);return null;}); }
            catch(Throwable error) { holderError.set(error); }
        });
        holderRef.set(holder);holder.start();
        Thread caller=new Thread(()->{
            try { calibration.calibrateInParallelIfNewDay(now); }
            catch(Throwable e) { caught.set(e); }
        });
        try {
            assertThat(held.await(2,TimeUnit.SECONDS)).isTrue();caller.start();
            assertThat(attempted.await(2,TimeUnit.SECONDS)).isTrue();
            bots.unregister(first); // peer identity change while the worker is waiting for its actual lock
        } finally { release.countDown();join(holder);if(caller.getState()!=Thread.State.NEW)join(caller); }
        assertThat(caught.get()).isNull();
        assertThat(holderError.get()).isNull();
        assertThat(players.findByPlayerId(first).orElseThrow().lastBotCalibrationAt()).isNull();
        assertThat(players.findByPlayerId(first).orElseThrow().version()).isEqualTo(before);
        assertThat(players.findByPlayerId(second).orElseThrow().lastBotCalibrationAt()).isEqualTo(now);
    }
}
