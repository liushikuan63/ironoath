package com.ironoath.web.lock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import com.ironoath.core.lock.PlayerLock;

class RedissonPlayerSharedScopeTest {
    private final RedissonClient client=mock(RedissonClient.class);
    private final RLock raw=mock(RLock.class),quest=mock(RLock.class);
    private final RedissonPlayerLock provider=new RedissonPlayerLock(client);
    private static final String ID="owned-shared-scope",QUEST="quest-progress:owned-shared-scope";
    @BeforeEach void owners() throws Exception {
        when(client.getLock("ironoath:lock:player:"+ID)).thenReturn(raw);
        when(client.getLock("ironoath:lock:player:"+QUEST)).thenReturn(quest);
        when(raw.tryLock(123,TimeUnit.MILLISECONDS)).thenReturn(true);
        when(quest.tryLock(123,TimeUnit.MILLISECONDS)).thenReturn(true);
        when(raw.isHeldByCurrentThread()).thenReturn(true);
    }
    @Test void explicitScopeReusesOnlyAnAuthoritativelyVerifiedSamePlayerOwner() throws Exception {
        assertThat(provider.runWithSharedScope(ID,123,()->provider.runLocked(ID,123,()->"same owner"))).isEqualTo("same owner");
        verify(raw,times(1)).tryLock(123,TimeUnit.MILLISECONDS);verify(raw,times(1)).unlock();
        verify(raw).isHeldByCurrentThread();
    }
    @Test void ordinaryReentrancyStillAcquiresAndReleasesBothRemoteHolds() throws Exception {
        provider.runLocked(ID,123,()->provider.runLocked(ID,123,()->"ordinary"));
        verify(raw,times(2)).tryLock(123,TimeUnit.MILLISECONDS);verify(raw,times(2)).unlock();
        verify(raw,never()).isHeldByCurrentThread();
    }
    @Test void unknownOwnerReadPropagatesWithoutRunningTheNestedAction() throws Exception {
        var unavailable=new IllegalStateException("owner read unavailable");when(raw.isHeldByCurrentThread()).thenThrow(unavailable);
        var called=new AtomicBoolean();
        assertThatThrownBy(()->provider.runWithSharedScope(ID,123,()->provider.runLocked(ID,123,()->{called.set(true);return null;})))
                .isSameAs(unavailable);
        assertThat(called).isFalse();verify(raw,times(1)).tryLock(123,TimeUnit.MILLISECONDS);verify(raw).unlock();
        doReturn(true).when(raw).isHeldByCurrentThread();provider.runLocked(ID,123,()->"fresh ordinary");
        verify(raw,times(1)).isHeldByCurrentThread();
    }
    @Test void lostOwnerFallsBackToOriginalAcquisitionAndKeepsItsFailure() throws Exception {
        when(raw.isHeldByCurrentThread()).thenReturn(false);when(raw.tryLock(123,TimeUnit.MILLISECONDS)).thenReturn(true,false);
        var called=new AtomicBoolean();
        assertThatThrownBy(()->provider.runWithSharedScope(ID,123,()->provider.runLocked(ID,123,()->{called.set(true);return null;})))
                .isInstanceOf(PlayerLock.LockTimeoutException.class);
        assertThat(called).isFalse();verify(raw).isHeldByCurrentThread();verify(raw).unlock();
        when(raw.tryLock(123,TimeUnit.MILLISECONDS)).thenReturn(true);provider.runLocked(ID,123,()->"fresh ordinary");
        verify(raw,times(1)).isHeldByCurrentThread();
    }
    @Test void nestedSharedScopesDoNotClearTheOuterScopeEarly() throws Exception {
        provider.runWithSharedScope(ID,123,()->{
            provider.runWithSharedScope(ID,123,()->provider.runLocked(ID,123,()->"inner"));
            return provider.runLocked(ID,123,()->"still inside outer");
        });
        verify(raw,times(1)).tryLock(123,TimeUnit.MILLISECONDS);verify(raw,times(3)).isHeldByCurrentThread();verify(raw).unlock();
    }
    @Test void independentQuestKeyStillAcquiresItsOwnRemoteLock() throws Exception {
        provider.runWithSharedScope(ID,123,()->provider.runLocked(QUEST,123,()->"independent"));
        verify(raw).tryLock(123,TimeUnit.MILLISECONDS);verify(quest).tryLock(123,TimeUnit.MILLISECONDS);
        verify(raw).unlock();verify(quest).unlock();verify(raw,never()).isHeldByCurrentThread();verify(quest,never()).isHeldByCurrentThread();
    }
    @Test void anotherThreadDoesNotInheritTheLexicalScope() throws Exception {
        when(raw.tryLock(123,TimeUnit.MILLISECONDS)).thenReturn(true,false);var pool=Executors.newSingleThreadExecutor();
        try{
            provider.runWithSharedScope(ID,123,()->{
                var result=pool.submit(()->provider.runLocked(ID,123,()->{throw new AssertionError("other thread bypassed remote acquisition");}));
                assertThatThrownBy(()->result.get(3,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(PlayerLock.LockTimeoutException.class);
                return null;
            });
            verify(raw,times(2)).tryLock(123,TimeUnit.MILLISECONDS);verify(raw,never()).isHeldByCurrentThread();verify(raw).unlock();
        }finally{pool.shutdownNow();assertThat(pool.awaitTermination(3,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void nestedActionFailureClearsScopeWithoutReplacingItsException() throws Exception {
        var original=new IllegalArgumentException("nested action failed");
        assertThatThrownBy(()->provider.runWithSharedScope(ID,123,()->provider.runLocked(ID,123,()->{throw original;}))).isSameAs(original);
        provider.runLocked(ID,123,()->"fresh ordinary");
        verify(raw,times(1)).isHeldByCurrentThread();verify(raw,times(2)).tryLock(123,TimeUnit.MILLISECONDS);verify(raw,times(2)).unlock();
    }
    @Test void preInterruptedNestedAcquireDoesNotSendRemoteOwnerReadOrReentry() throws Exception {
        provider.runWithSharedScope(ID,123,()->{
            Thread.currentThread().interrupt();try{
                assertThatThrownBy(()->provider.runLocked(ID,123,()->{throw new AssertionError("interrupted action bypassed acquire");}))
                        .isInstanceOf(PlayerLock.LockTimeoutException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            }finally{Thread.interrupted();}return null;
        });
        verify(raw,times(1)).tryLock(123,TimeUnit.MILLISECONDS);verify(raw,never()).isHeldByCurrentThread();verify(raw).unlock();
    }
    @Test void preInterruptedOrdinaryAndExplicitCallsNeverReachRedis(){
        Thread.currentThread().interrupt();try{
            assertThatThrownBy(()->provider.runLocked(ID,123,()->{throw new AssertionError("pre-interrupted ordinary action ran");}))
                    .isInstanceOf(PlayerLock.LockTimeoutException.class);
            assertThatThrownBy(()->provider.runWithSharedScope(ID,123,()->{throw new AssertionError("pre-interrupted scoped action ran");}))
                    .isInstanceOf(PlayerLock.LockTimeoutException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();verifyNoInteractions(client);
        }finally{Thread.interrupted();}
    }
    @Test void invalidExplicitScopeArgumentsNeverReachRedis(){
        assertThatThrownBy(()->provider.runWithSharedScope(" ",123,()->null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->provider.runWithSharedScope(ID,0,()->null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->provider.runWithSharedScope(ID,123,null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }
}
