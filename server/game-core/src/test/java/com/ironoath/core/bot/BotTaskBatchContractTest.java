package com.ironoath.core.bot;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BotTaskBatchContractTest {
    private static BotScheduler.BotTask task(String id,long at) {return BotScheduler.BotTask.decisionAt(id,at);}
    private static final class Queue implements BotScheduler.TaskQueue {
        final List<BotScheduler.BotTask> attempted=new ArrayList<>(),stored=new ArrayList<>();
        public void schedule(BotScheduler.BotTask task) {
            attempted.add(task);
            if(task.botId().equals("b"))throw new IllegalStateException("unconfirmed");
            stored.add(task);
        }
        public boolean scheduleIfAbsent(BotScheduler.BotTask task){throw new AssertionError("Not enrollment");}
        public List<BotScheduler.BotTask> dueBefore(long now,int limit){throw new AssertionError("Not claiming");}
        public void cancel(String id){throw new AssertionError("Not cancelling");}
        public int size(){return stored.size();}
    }
    @Test void middleFailureReturnsOnlyThatOriginalTaskAndContinuesTheSuffix() {
        var queue=new Queue();var input=List.of(task("a",10),task("b",20),task("c",30));
        var failed=queue.scheduleAll(input);
        assertThat(failed).containsExactly(input.get(1));assertThat(failed.get(0)).isSameAs(input.get(1));
        assertThat(queue.attempted).containsExactlyElementsOf(input);assertThat(queue.stored).containsExactly(input.get(0),input.get(2));
        assertThatThrownBy(()->failed.add(task("d",40))).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void emptyBatchPerformsNoSingleWrite() {
        var queue=new Queue();assertThat(queue.scheduleAll(List.of())).isEmpty();assertThat(queue.attempted).isEmpty();
    }
    @Test void duplicateIdentityWithDifferentDueIsRejectedBeforeAnyWrite() {
        var queue=new Queue();assertThatThrownBy(()->queue.scheduleAll(List.of(task("a",10),task("a",20))))
                .isInstanceOf(IllegalArgumentException.class);assertThat(queue.attempted).isEmpty();
    }
    @Test void nullItemIsRejectedBeforeAnyWrite() {
        var queue=new Queue();assertThatThrownBy(()->queue.scheduleAll(Arrays.asList(task("a",10),null)))
                .isInstanceOf(NullPointerException.class);assertThat(queue.attempted).isEmpty();
    }
    @Test void inputSnapshotDoesNotFollowLaterCallerMutation() {
        var input=new ArrayList<>(List.of(task("a",10)));var snapshot=BotScheduler.TaskQueue.checkedBatch(input);
        input.add(task("c",30));assertThat(snapshot).containsExactly(task("a",10));
        assertThatThrownBy(()->snapshot.add(task("d",40))).isInstanceOf(UnsupportedOperationException.class);
    }
}
