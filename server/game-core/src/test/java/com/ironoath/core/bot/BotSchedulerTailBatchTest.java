package com.ironoath.core.bot;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import com.ironoath.common.rng.Rng;

/** Claimed-but-unprocessed tails must return together without replaying acknowledged peers. */
class BotSchedulerTailBatchTest {
    private static final long NOW=10_000;
    private static BotScheduler.BotTask task(String id) {
        return BotScheduler.BotTask.actionAt(id,NOW,new BotDecisionTree.Decision(
                BotDecisionTree.Action.IDLE,false,0,"preserved pending "+id));
    }
    private static final class World implements BotScheduler.World {
        final List<String> profiles=new ArrayList<>();
        public BotProfile profileOf(String id) { profiles.add(id);return null; }
        public BotDecisionTree.WorldState observe(String id,long now) { throw new AssertionError("Unprocessed tail observed"); }
        public void execute(String id,BotDecisionTree.Decision d,long now) { throw new AssertionError("Unprocessed tail executed"); }
        public long matchPowerOf(String id) { throw new AssertionError("No active profile"); }
        public long humanAverageMatchPower(long now) { throw new AssertionError("No active profile"); }
    }
    private static final class Queue implements BotScheduler.TaskQueue {
        final Map<String,BotScheduler.BotTask> stored=new LinkedHashMap<>();
        final List<List<BotScheduler.BotTask>> batches=new ArrayList<>();
        final List<String> cancelled=new ArrayList<>();
        String failedId;boolean throwBulk,invalidResult;int singleWrites;
        Queue() { for(String id:List.of("a","b","c"))stored.put(id,task(id)); }
        public void schedule(BotScheduler.BotTask task) {
            singleWrites++;
            if(task.botId().equals(failedId))throw new IllegalStateException("one write unavailable");
            stored.put(task.botId(),task);
        }
        // Compiles against the old interface too, so the first red is behavioral:
        // the old scheduler never calls this batch capability.
        public List<BotScheduler.BotTask> scheduleAll(List<BotScheduler.BotTask> tasks) {
            batches.add(List.copyOf(tasks));
            if(throwBulk)throw new IllegalStateException("unexpected batch failure");
            if(invalidResult)return List.of(task("not-in-input"));
            List<BotScheduler.BotTask> failed=new ArrayList<>();
            for(var task:tasks) {
                if(task.botId().equals(failedId))failed.add(task);
                else stored.put(task.botId(),task);
            }
            return List.copyOf(failed);
        }
        public boolean scheduleIfAbsent(BotScheduler.BotTask task) {
            if(stored.containsKey(task.botId()))return false;
            schedule(task);return true;
        }
        public List<BotScheduler.BotTask> dueBefore(long now,int limit) {
            var due=stored.values().stream().filter(t->t.dueAt()<=now).limit(limit).toList();
            due.forEach(t->stored.remove(t.botId()));return due;
        }
        public void cancel(String id) {cancelled.add(id);stored.remove(id);}
        public int size(){return stored.size();}
    }
    private BotScheduler scheduler(Queue queue,World world,BotScheduler.RecoveryState recovery,
                                   LongSupplier clock,int limit) {
        var schedule=new BotSchedule(new BotSchedule.Rules(3,8,Map.of(BotSchedule.Action.LOGIN,Map.of(20,10))));
        return new BotScheduler(new BotScheduler.Rules(1,limit),world,queue,new BotDecisionTree(),schedule,Rng.of(1),clock,recovery);
    }
    private LongSupplier immediatelyExpired() {var nanos=new AtomicLong();return ()->nanos.getAndAdd(1_000_000);}

    @Test void completeClaimedTailUsesOneBatchAndPreservesPending() {
        var queue=new Queue();var world=new World();var recovery=new BotScheduler.RecoveryState();
        var scheduler=scheduler(queue,world,recovery,immediatelyExpired(),50);
        assertThat(scheduler.processDue(NOW)).isZero();
        assertThat(queue.batches).containsExactly(List.of(task("a"),task("b"),task("c")));
        assertThat(queue.singleWrites).isZero();assertThat(queue.stored.values()).containsExactly(task("a"),task("b"),task("c"));
        assertThat(scheduler.budgetHitCount()).isEqualTo(3);assertThat(scheduler.failedCount()).isZero();
        assertThat(recovery.size()).isZero();assertThat(world.profiles).isEmpty();
    }
    @Test void failedMiddleItemDoesNotReplayAcknowledgedPrefixOrSuffixAfterPeerConsumption() {
        var queue=new Queue();queue.failedId="b";var world=new World();var recovery=new BotScheduler.RecoveryState();
        var scheduler=scheduler(queue,world,recovery,immediatelyExpired(),50);
        assertThat(scheduler.processDue(NOW)).isZero();
        assertThat(queue.batches).hasSize(1);assertThat(queue.stored.values()).containsExactly(task("a"),task("c"));
        assertThat(recovery.size()).isEqualTo(1);assertThat(scheduler.failedCount()).isEqualTo(1);
        assertThat(queue.dueBefore(NOW,50)).containsExactly(task("a"),task("c")); // another consumer has taken confirmed items
        scheduler(queue,world,recovery,()->0,50).processDue(NOW+1);
        assertThat(world.profiles).containsExactly("b");assertThat(queue.cancelled).containsExactly("b");
        assertThat(recovery.size()).isZero();assertThat(queue.stored).isEmpty();
    }
    @Test void unexpectedBulkThrowRetainsEveryUnconfirmedItemAndCountsEachFailure() {
        var queue=new Queue();queue.throwBulk=true;var recovery=new BotScheduler.RecoveryState();
        var scheduler=scheduler(queue,new World(),recovery,immediatelyExpired(),50);
        assertThatCode(()->scheduler.processDue(NOW)).doesNotThrowAnyException();
        assertThat(recovery.size()).isEqualTo(3);assertThat(scheduler.failedCount()).isEqualTo(3);
        assertThat(scheduler.budgetHitCount()).isEqualTo(3);assertThat(queue.stored).isEmpty();
    }
    @Test void invalidBulkAcknowledgementDoesNotLoseTheClaimedTail() {
        var queue=new Queue();queue.invalidResult=true;var recovery=new BotScheduler.RecoveryState();
        var scheduler=scheduler(queue,new World(),recovery,immediatelyExpired(),50);
        assertThatCode(()->scheduler.processDue(NOW)).doesNotThrowAnyException();
        assertThat(recovery.size()).isEqualTo(3);assertThat(scheduler.failedCount()).isEqualTo(3);
    }
    @Test void afterOneProcessedItemOnlyTheRemainingTailIsBatchedAndOriginalLimitRemains() {
        var queue=new Queue();var world=new World();var calls=new AtomicLong();
        var scheduler=scheduler(queue,world,new BotScheduler.RecoveryState(),()->calls.getAndIncrement()<2?0:1_000_000,2);
        assertThat(scheduler.processDue(NOW)).isEqualTo(1); // first profile retired through the normal path
        assertThat(world.profiles).containsExactly("a");assertThat(queue.batches).containsExactly(List.of(task("b")));
        assertThat(queue.stored.values()).containsExactly(task("c"),task("b"));
        assertThat(scheduler.budgetHitCount()).isEqualTo(1);assertThat(queue.singleWrites).isZero();
    }
    @Test void emptyClaimDoesNotIssueAnEmptyBatch() {
        var queue=new Queue();queue.stored.clear();
        assertThat(scheduler(queue,new World(),new BotScheduler.RecoveryState(),immediatelyExpired(),50).processDue(NOW)).isZero();
        assertThat(queue.batches).isEmpty();assertThat(queue.singleWrites).isZero();
    }
}
