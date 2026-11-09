package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.world.Coord;
import com.ironoath.web.lock.InJvmPlayerLock;
import org.junit.jupiter.api.Test;

class CapitalMigrationServicePolicyTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long ONE_WEEK = 7L * 24L * 60L * 60L * 1000L;

    @Test
    void kingCanMoveWithoutReadingHisLoginTimestamp() {
        AtomicBoolean read = new AtomicBoolean();

        boolean allowed = CapitalMigrationService.mayDelegateByKingOffline(
                "king", "king", NOW, () -> {
                    read.set(true);
                    throw new IllegalStateException("login read should be bypassed for King");
                });

        assertThat(allowed).isTrue();
        assertThat(read).isFalse();
    }

    @Test
    void officerDelegationRequiresAValidKnownLoginAtLeastSevenDaysOld() {
        assertThat(CapitalMigrationService.mayDelegateByKingOffline(
                "minister", "king", NOW, OptionalLong::empty)).isFalse();
        assertThat(CapitalMigrationService.mayDelegateByKingOffline(
                "minister", "king", NOW, () -> OptionalLong.of(0L))).isFalse();
        assertThat(CapitalMigrationService.mayDelegateByKingOffline(
                "minister", "king", NOW, () -> OptionalLong.of(NOW + 1L))).isFalse();
        assertThat(CapitalMigrationService.mayDelegateByKingOffline(
                "minister", "king", NOW, () -> OptionalLong.of(NOW - ONE_WEEK + 1L))).isFalse();
        assertThat(CapitalMigrationService.mayDelegateByKingOffline(
                "minister", "king", NOW, () -> OptionalLong.of(NOW - ONE_WEEK))).isTrue();
    }

    @Test
    void loginReadFailureClosesOnlyOfficerDelegation() {
        assertThat(CapitalMigrationService.mayDelegateByKingOffline(
                "minister", "king", NOW, () -> { throw new IllegalStateException("storage unavailable"); }))
                .isFalse();
        assertThat(CapitalMigrationService.mayDelegateByKingOffline(
                "king", "king", NOW, () -> { throw new IllegalStateException("must not read"); }))
                .isTrue();
    }

    @Test
    void coordinateCellLockSerializesAllCallersOfTheSharedWorldCellGate() throws Exception {
        Coord cell = Coord.of(300, 301);
        String lockKey = "@world-coordinate:" + cell.storageKey();
        InJvmPlayerLock delegate = new InJvmPlayerLock();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        AtomicBoolean secondEntered = new AtomicBoolean();
        PlayerLock observing = new PlayerLock() {
            @Override
            public <T> T runLocked(String key, long timeoutMs, Supplier<T> action) {
                if (key.equals(lockKey) && Thread.currentThread() != firstThread.get()) {
                    secondAttempted.countDown();
                }
                return delegate.runLocked(key, timeoutMs, action);
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                // Models a relocation holding the cell before validation and CAS write.
                firstThread.set(Thread.currentThread());
                return CapitalMigrationService.runCoordinateLocked(observing, cell, () -> {
                    firstEntered.countDown();
                    try {
                        if (!releaseFirst.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("cell lock test release timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    return true;
                });
            });
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();

            var second = pool.submit(() -> CapitalMigrationService.runCoordinateLocked(observing, cell, () -> {
                // MarchAppService uses this same helper before resolving/persisting its target.
                secondEntered.set(true);
                return true;
            }));
            assertThat(secondAttempted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(secondEntered).isFalse();
            releaseFirst.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(secondEntered).isTrue();
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }
}
