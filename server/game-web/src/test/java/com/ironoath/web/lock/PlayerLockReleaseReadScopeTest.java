package com.ironoath.web.lock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import com.ironoath.core.lock.PlayerLock;

class PlayerLockReleaseReadScopeTest {
    private final RedissonClient client=mock(RedissonClient.class);
    private final RLock lock=mock(RLock.class);
    private final RedissonPlayerLock provider=new RedissonPlayerLock(client);
    private static final String ID="owned-release-a";
    @BeforeEach void acquired() throws InterruptedException {
        when(client.getLock("ironoath:lock:player:"+ID)).thenReturn(lock);
        when(lock.tryLock(123,TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true); // allows old code to reach unlock; never used by new path
    }
    @Test void successfulReleaseUsesAtomicUnlockWithoutStandaloneOwnershipRead() throws Exception {
        assertThat(provider.runLocked(ID,123,()->"result")).isEqualTo("result");
        verify(lock).tryLock(123,TimeUnit.MILLISECONDS);verify(lock).unlock();
        verify(lock,never()).isHeldByCurrentThread();verify(lock,never()).forceUnlock();
    }
    @Test void lostOwnershipAtReleaseKeepsTheSuccessfulActionResult() {
        doThrow(new IllegalMonitorStateException("old owner lost")).when(lock).unlock();
        assertThatCode(()->assertThat(provider.runLocked(ID,123,()->"result")).isEqualTo("result"))
                .doesNotThrowAnyException();
        verify(lock).unlock();verify(lock,never()).forceUnlock();
    }
    @Test void actionFailureIsNotMaskedByUnlockOwnershipLoss() {
        var original=new IllegalArgumentException("action failed");
        doThrow(new IllegalMonitorStateException("owner lost at release")).when(lock).unlock();
        assertThatThrownBy(()->provider.runLocked(ID,123,()->{throw original;})).isSameAs(original);
    }
    @Test void actionOwnIllegalMonitorStateExceptionStillPropagates() {
        var original=new IllegalMonitorStateException("action failure is not release failure");
        assertThatThrownBy(()->provider.runLocked(ID,123,()->{throw original;})).isSameAs(original);
        verify(lock).unlock();
    }
    @Test void actionOwnIllegalMonitorStateExceptionIsNotMaskedByReleaseLossEither() {
        var original=new IllegalMonitorStateException("action IMSE");
        doThrow(new IllegalMonitorStateException("release IMSE")).when(lock).unlock();
        assertThatThrownBy(()->provider.runLocked(ID,123,()->{throw original;})).isSameAs(original);
    }
    @Test void unrelatedReleaseFailurePropagates() {
        var unavailable=new IllegalStateException("Redis transport unavailable");
        doThrow(unavailable).when(lock).unlock();
        assertThatThrownBy(()->provider.runLocked(ID,123,()->"result")).isSameAs(unavailable);
    }
    @Test void failedAcquisitionDoesNotRunActionOrUnlock() throws Exception {
        when(lock.tryLock(123,TimeUnit.MILLISECONDS)).thenReturn(false);var called=new AtomicBoolean();
        assertThatThrownBy(()->provider.runLocked(ID,123,()->{called.set(true);return "unused";}))
                .isInstanceOf(PlayerLock.LockTimeoutException.class);
        assertThat(called).isFalse();verify(lock,never()).unlock();verify(lock,never()).isHeldByCurrentThread();
    }
    @Test void interruptedAcquisitionKeepsInterruptAndDoesNotUnlock() throws Exception {
        when(lock.tryLock(123,TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException("interrupted"));
        var called=new AtomicBoolean();
        try {
            assertThatThrownBy(()->provider.runLocked(ID,123,()->{called.set(true);return "unused";}))
                    .isInstanceOf(PlayerLock.LockTimeoutException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(called).isFalse();
            verify(lock,never()).unlock();verify(lock,never()).isHeldByCurrentThread();
        } finally {Thread.interrupted();}
    }
    @Test void invalidArgumentsDoNotReachRedis() {
        assertThatThrownBy(()->provider.runLocked(" ",123,()->"unused")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->provider.runLocked(ID,0,()->"unused")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->provider.runLocked(ID,123,null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }
}
