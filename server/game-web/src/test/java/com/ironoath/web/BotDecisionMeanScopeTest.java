package com.ironoath.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.web.bot.*;

@SpringBootTest
@ActiveProfiles("test")
class BotDecisionMeanScopeTest {
    @Autowired BotWorldAdapter adapter;
    @SpyBean BotSpawnService spawner;
    @BeforeEach void clearCalls() { clearInvocations(spawner); }

    @Test void unusedRoundDoesNotQueryAndDirectReadsRemainFresh() {
        assertThat(adapter.withHumanAverageSnapshot(1,()->42)).isEqualTo(42);
        verifyNoInteractions(spawner);
        doReturn(10L,20L).when(spawner).humanAverageMatchPower();
        assertThat(adapter.humanAverageMatchPower(1)).isEqualTo(10);
        assertThat(adapter.humanAverageMatchPower(1)).isEqualTo(20);
    }
    @Test void sameRoundSharesValueDifferentNowRestoresParentAndNextDriveReadsAgain() {
        AtomicLong value=new AtomicLong();doAnswer(call->value.addAndGet(10)).when(spawner).humanAverageMatchPower();
        adapter.withHumanAverageSnapshot(1,()->{
            assertThat(adapter.humanAverageMatchPower(1)).isEqualTo(10);
            adapter.withHumanAverageSnapshot(1,()->{assertThat(adapter.humanAverageMatchPower(1)).isEqualTo(10);return null;});
            adapter.withHumanAverageSnapshot(2,()->{assertThat(adapter.humanAverageMatchPower(2)).isEqualTo(20);return null;});
            assertThat(adapter.humanAverageMatchPower(1)).isEqualTo(10);return null;
        });
        assertThat(adapter.humanAverageMatchPower(1)).isEqualTo(30);
        assertThat(adapter.withHumanAverageSnapshot(1,()->adapter.humanAverageMatchPower(1))).isEqualTo(40);
        verify(spawner,times(4)).humanAverageMatchPower();
    }
    @Test void unavailableInputThrowsToConsumersAndBodyFailureReleasesScope() {
        IllegalStateException unavailable=new IllegalStateException("mean read unavailable");
        doThrow(unavailable).doReturn(70L).when(spawner).humanAverageMatchPower();
        assertThatThrownBy(()->adapter.withHumanAverageSnapshot(1,()->{
            assertThatThrownBy(()->adapter.humanAverageMatchPower(1)).isSameAs(unavailable);
            assertThatThrownBy(()->adapter.humanAverageMatchPower(1)).isSameAs(unavailable);
            throw new IllegalArgumentException("body failed");
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("body failed");
        assertThat(adapter.humanAverageMatchPower(1)).isEqualTo(70);
        verify(spawner,times(2)).humanAverageMatchPower();
    }
    @Test void simultaneousDriversOnDifferentThreadsDoNotShareMeans() throws Exception {
        doAnswer(call->Thread.currentThread().getName().endsWith("A")?11L:22L).when(spawner).humanAverageMatchPower();
        CyclicBarrier barrier=new CyclicBarrier(2);ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            Future<Long> a=executor.submit(()->concurrentRead("mean-A",barrier));
            Future<Long> b=executor.submit(()->concurrentRead("mean-B",barrier));
            assertThat(a.get(5,TimeUnit.SECONDS)).isEqualTo(11);assertThat(b.get(5,TimeUnit.SECONDS)).isEqualTo(22);
            verify(spawner,times(2)).humanAverageMatchPower();
        } finally { executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
    }
    private long concurrentRead(String name,CyclicBarrier barrier) {
        Thread.currentThread().setName(name);
        return adapter.withHumanAverageSnapshot(1,()->{
            long first=adapter.humanAverageMatchPower(1);
            try { barrier.await(3,TimeUnit.SECONDS); } catch(Exception error) { throw new IllegalStateException(error); }
            assertThat(adapter.humanAverageMatchPower(1)).isEqualTo(first);return first;
        });
    }
}
