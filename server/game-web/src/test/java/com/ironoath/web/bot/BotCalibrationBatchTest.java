package com.ironoath.web.bot;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class BotCalibrationBatchTest {
    private static void await(CountDownLatch latch) {
        try { latch.await(1,TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new AssertionError(e); }
    }
    private static void join(Thread thread) throws InterruptedException {
        thread.join(5000);assertThat(thread.isAlive()).as("测试调用线程必须退出").isFalse();
    }
    private static void gate(CountDownLatch latch) {
        try { if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Owned write gate timeout"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new AssertionError(e); }
    }

    @Test void differentPlayersOverlapWithinTheBoundAndResultsKeepInputOrder() {
        CountDownLatch firstPair=new CountDownLatch(2);
        AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();
        Set<Thread> threads=ConcurrentHashMap.newKeySet();
        List<String> ids=List.of("0","1","2","3","4","5");
        var result=new BotCalibrationBatch(2).run(ids,id->{
            threads.add(Thread.currentThread());peak.accumulateAndGet(active.incrementAndGet(),Math::max);
            try { firstPair.countDown();await(firstPair);return id+"-done"; }
            finally { active.decrementAndGet(); }
        },true);
        assertThat(peak.get()).isEqualTo(2);
        assertThat(active.get()).isZero();
        assertThat(result).containsExactly("0-done","1-done","2-done","3-done","4-done","5-done");
        assertThat(threads).hasSize(2).allSatisfy(t->assertThat(t.isAlive()).isFalse());
    }

    @Test void firstObservedFailureStopsDispatchAndDrainsAcceptedWrites() throws Exception {
        var failure=new IllegalStateException("owned failure");
        CountDownLatch secondEntered=new CountDownLatch(1),release=new CountDownLatch(1),failureQueued=new CountDownLatch(1);
        Set<String> started=ConcurrentHashMap.newKeySet();
        AtomicBoolean committed=new AtomicBoolean(),returnedBeforeCommit=new AtomicBoolean();
        AtomicReference<Throwable> caught=new AtomicReference<>();
        AtomicReference<ExecutorService> executor=new AtomicReference<>();
        ThreadLocal<String> labels=new ThreadLocal<>();
        BotCalibrationBatch batch=new BotCalibrationBatch(2,n->{
            var pool=new ThreadPoolExecutor(n,n,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>()) {
                @Override protected void afterExecute(Runnable task,Throwable failure) {
                    super.afterExecute(task,failure);
                    if("0".equals(labels.get()))failureQueued.countDown();
                }
            };
            executor.set(pool);return pool;
        });
        Thread caller=new Thread(()->{
            try { batch.run(List.of("0","1","2","3"),id->{
                started.add(id);labels.set(id);
                if(id.equals("0")) { await(secondEntered);throw failure; }
                secondEntered.countDown();gate(release);committed.set(true);return id;
            },true); }
            catch(Throwable e) { caught.set(e); }
            finally { returnedBeforeCommit.set(!committed.get()); }
        });
        caller.start();
        try {
            assertThat(secondEntered.await(3,TimeUnit.SECONDS)).isTrue();
            assertThat(failureQueued.await(3,TimeUnit.SECONDS)).as("失败completion先于阻塞写completion入队").isTrue();
        } finally { release.countDown();join(caller);if(executor.get()!=null)executor.get().shutdownNow(); }
        assertThat(caught.get()).isSameAs(failure);
        assertThat(started).containsExactlyInAnyOrder("0","1");
        assertThat(returnedBeforeCommit).isFalse();
    }

    @Test void submissionRejectionAlsoDrainsTheAlreadyAcceptedWrite() throws Exception {
        var rejection=new RejectedExecutionException("owned rejection");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),rejected=new CountDownLatch(1);
        AtomicReference<ExecutorService> executor=new AtomicReference<>();
        AtomicReference<Throwable> caught=new AtomicReference<>();
        AtomicBoolean committed=new AtomicBoolean(),returnedBeforeCommit=new AtomicBoolean(),terminatedAtReturn=new AtomicBoolean();
        BotCalibrationBatch batch=new BotCalibrationBatch(2,n->{
            var pool=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),(task,p)->{rejected.countDown();throw rejection;});
            executor.set(pool);return pool;
        });
        Thread caller=new Thread(()->{
            try { batch.run(List.of("0","1","2"),id->{entered.countDown();gate(release);committed.set(true);return id;},true); }
            catch(Throwable e) { caught.set(e); }
            finally { returnedBeforeCommit.set(!committed.get());terminatedAtReturn.set(executor.get()!=null && executor.get().isTerminated()); }
        });
        caller.start();
        try {
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            assertThat(rejected.await(3,TimeUnit.SECONDS)).as("先确认第二次派发拒绝，再放行首个写入").isTrue();
        }
        finally { release.countDown();join(caller);if(executor.get()!=null)executor.get().shutdownNow(); }
        assertThat(caught.get()).isSameAs(rejection);
        assertThat(returnedBeforeCommit).isFalse();
        assertThat(terminatedAtReturn).as("不能由测试finally关闭executor来冒充实现已收口").isTrue();
        assertThat(executor.get()).isNotNull().matches(ExecutorService::isTerminated);
    }

    @Test void explicitSerialAndBoundSpringResourcesStayOnTheCallerThread() {
        Thread caller=Thread.currentThread();BotCalibrationBatch batch=new BotCalibrationBatch(2);
        assertThat(batch.run(List.of("a","b"),id->Thread.currentThread(),false)).containsOnly(caller);
        Object key=new Object();TransactionSynchronizationManager.bindResource(key,new Object());
        try { assertThat(batch.run(List.of("a","b"),id->Thread.currentThread(),true)).containsOnly(caller); }
        finally { TransactionSynchronizationManager.unbindResource(key); }
    }

    @Test void preexistingInterruptKeepsSameThreadAndFlagWithoutCreatingWorkers() {
        Thread caller=Thread.currentThread();caller.interrupt();
        try {
            assertThat(new BotCalibrationBatch(2).run(List.of("a","b"),id->Thread.currentThread(),true)).containsOnly(caller);
            assertThat(caller.isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void workerBoundsAreOperationalInputAndRejectInvalidValues() {
        assertThatThrownBy(()->new BotCalibrationBatch(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new BotCalibrationBatch(9)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void workerErrorKeepsTheLowestDispatchedIndexAndPreservesOtherFailures() {
        var primary=new AssertionError("owned index zero");
        var secondary=new IllegalStateException("owned index one");
        CountDownLatch secondFailureQueued=new CountDownLatch(1);
        ThreadLocal<String> labels=new ThreadLocal<>();Set<String> started=ConcurrentHashMap.newKeySet();
        AtomicReference<ExecutorService> executor=new AtomicReference<>();
        BotCalibrationBatch batch=new BotCalibrationBatch(2,n->{
            var pool=new ThreadPoolExecutor(n,n,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>()) {
                @Override protected void afterExecute(Runnable task,Throwable error) {
                    super.afterExecute(task,error);if("1".equals(labels.get()))secondFailureQueued.countDown();
                }
            };
            executor.set(pool);return pool;
        });
        try {
            assertThatThrownBy(()->batch.run(List.of("0","1","2"),id->{
                labels.set(id);started.add(id);
                if(id.equals("0")) { await(secondFailureQueued);throw primary; }
                throw secondary;
            },true)).isSameAs(primary);
            assertThat(started).containsExactlyInAnyOrder("0","1");
            assertThat(primary.getSuppressed()).containsExactly(secondary);
            assertThat(executor.get()).isNotNull().matches(ExecutorService::isTerminated);
        } finally { if(executor.get()!=null)executor.get().shutdownNow(); }
    }

    @Test void interruptionStopsDispatchDrainsWritesAndRestoresTheCallerFlag() throws Exception {
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        AtomicInteger commits=new AtomicInteger();Set<String> started=ConcurrentHashMap.newKeySet();
        AtomicBoolean workerInterrupted=new AtomicBoolean(),returnedTooSoon=new AtomicBoolean(),flagRestored=new AtomicBoolean();
        AtomicReference<Throwable> caught=new AtomicReference<>();Set<Thread> workers=ConcurrentHashMap.newKeySet();
        Thread caller=new Thread(()->{
            try { new BotCalibrationBatch(2).run(List.of("0","1","2","3"),id->{
                started.add(id);workers.add(Thread.currentThread());entered.countDown();
                boolean released=false;
                while(!released) {
                    try { released=release.await(5,TimeUnit.SECONDS);if(!released)throw new AssertionError("Owned interrupt gate timeout"); }
                    catch(InterruptedException e) { workerInterrupted.set(true); }
                }
                commits.incrementAndGet();return id;
            },true); }
            catch(Throwable e) { caught.set(e); }
            finally { returnedTooSoon.set(commits.get()!=2);flagRestored.set(Thread.currentThread().isInterrupted()); }
        });
        caller.start();
        try { assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();caller.interrupt(); }
        finally { release.countDown();join(caller); }
        assertThat(caught.get()).isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(InterruptedException.class);
        assertThat(started).containsExactlyInAnyOrder("0","1");
        assertThat(commits.get()).isEqualTo(2);assertThat(returnedTooSoon).isFalse();
        assertThat(flagRestored).isTrue();assertThat(workerInterrupted).isFalse();
        assertThat(workers).allSatisfy(t->assertThat(t.isAlive()).isFalse());
    }

    @Test void synchronizationAndActualTransactionAlsoStayOnTheCallerThread() {
        Thread caller=Thread.currentThread();BotCalibrationBatch batch=new BotCalibrationBatch(2);
        TransactionSynchronizationManager.initSynchronization();
        try { assertThat(batch.run(List.of("a","b"),id->Thread.currentThread(),true)).containsOnly(caller); }
        finally { TransactionSynchronizationManager.clearSynchronization(); }
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThat(batch.run(List.of("a","b"),id->Thread.currentThread(),true)).containsOnly(caller); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    @Test void emptySingleAndOneWorkerBatchesDoNotCreateAnExecutor() {
        BotCalibrationBatch batch=new BotCalibrationBatch(2,n->{throw new AssertionError("Unexpected executor");});
        assertThat(batch.run(List.of(),id->id,true)).isEmpty();
        assertThat(batch.run(List.of("single"),id->Thread.currentThread(),true)).containsExactly(Thread.currentThread());
        assertThat(new BotCalibrationBatch(1,n->{throw new AssertionError("Unexpected executor");})
                .run(List.of("a","b"),id->Thread.currentThread(),true)).containsOnly(Thread.currentThread());
    }
}
