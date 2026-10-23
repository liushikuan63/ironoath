package com.ironoath.web.store;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.social.Rally;
import com.ironoath.web.lock.InJvmPlayerLock;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.world.*;
import com.ironoath.web.store.memory.*;
import com.ironoath.web.store.mongo.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实存储上的阶段故障与完成屏障；数据均为自有夹具，不是实际攻占胜战。 */
class StrategicGarrisonWithdrawalTest {
    enum Backend { MEMORY, MONGO }
    private static TestMongo db;
    @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();}
    @AfterAll static void close(){if(db!=null)db.close();}
    private final ConfigRegistry configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    private class Fixture {
        TargetOperationStore ops;TargetOccupationStore owners;MarchRepository marches;MarchDueQueue due;
        final SocialStore social=mock(SocialStore.class);final SocialAppService app=mock(SocialAppService.class);
        final InJvmPlayerLock locks=new InJvmPlayerLock();final March original=TargetOperationStoreTest.stationed("owned-withdrawal");
        Fixture(Backend b){
            if(b==Backend.MONGO){for(String c:List.of("world_target_operation","world_target_occupation","march","march_due"))db.template().remove(new org.springframework.data.mongodb.core.query.Query(),c);}
            ops=b==Backend.MEMORY?new InMemoryTargetOperationStore():new MongoTargetOperationStore(db.template());
            owners=b==Backend.MEMORY?new InMemoryTargetOccupationStore():new MongoTargetOccupationStore(db.template());
            marches=b==Backend.MEMORY?new InMemoryMarchStore(32):new MongoMarchStore(db.template(),32);
            due=b==Backend.MEMORY?new SortedMarchDueQueue():new MongoMarchDueQueue(db.template());
            marches.insertIfAbsent(original);owners.compareAndSet(TargetOperationStoreTest.owner(original,1),0);
            var rally=Rally.restore(original.rallyId(),Rally.Scope.NATION,"N-owned",original.playerId(),2,1,100,110,
                    Map.of(original.playerId(),new Rally.Participant(original.playerId(),original.units(),List.of())),
                    Rally.Status.PREPARING,null,256,216,"STRATEGIC_TARGET",1);rally.depart(110);
            when(social.rallyOf(original.rallyId())).thenReturn(Optional.of(rally));
            doAnswer(inv->((Supplier<?>)inv.getArgument(1)).get()).when(app).runRallyDepartureLocked(anyString(),any());
        }
        StrategicGarrisonWithdrawal service(){return new StrategicGarrisonWithdrawal(configs,ops,owners,marches,due,
                new StrategicTargetDefender(configs,owners,marches,social,new com.ironoath.web.store.memory.InMemoryNeutralTargetGarrisonStore()),app,locks);}
        March current(){return marches.findById(original.id()).orElseThrow();}
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void withdrawalRetainsNationAndCompletedReplayNeverRequeuesDeletedMarch(Backend b){
        var f=new Fixture(b);var service=f.service();var returned=service.recall("owned-leader",f.original.id(),120);
        assertThat(returned.returnArriveAt()).isEqualTo(130L);
        assertThat(f.owners.findByTargetIds(List.of("gate_north")).get("gate_north").garrison()).isEqualTo(TargetOccupationStore.Garrison.empty());
        assertThat(f.owners.findByTargetIds(List.of("gate_north")).get("gate_north").nationId()).isEqualTo("N-owned");
        assertThat(f.ops.findByTargetId("gate_north").orElseThrow().completed()).isTrue();
        assertThat(f.due.dueBefore(130,10)).contains(f.original.id());
        f.marches.delete(f.original.id());f.due.cancel(f.original.id());
        assertThat(service.replay("owned-leader",f.original.id()).orElseThrow().returnArriveAt()).isEqualTo(130L);
        assertThat(f.marches.findById(f.original.id())).isEmpty();assertThat(f.due.dueBefore(1000,10)).isEmpty();
        assertThatThrownBy(()->service.replay("intruder",f.original.id())).hasMessageContaining("行军不存在");
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void failedMarchWriteKeepsOriginalCustodyAndRecoversFromFrozenPlan(Backend b){
        var f=new Fixture(b);var real=f.marches;var broken=spy(real);f.marches=broken;
        var failure=new IllegalStateException("owned before return write");doThrow(failure).when(broken).save(any(March.class),anyLong());
        var service=f.service();assertThatThrownBy(()->service.recall("owned-leader",f.original.id(),120)).isSameAs(failure);
        assertThat(real.findById(f.original.id()).orElseThrow().status()).isEqualTo(March.Status.STATIONED);
        assertThat(f.ops.findByTargetId("gate_north").orElseThrow().completed()).isFalse();
        doCallRealMethod().when(broken).save(any(March.class),anyLong());
        assertThat(service.recall("owned-leader",f.original.id(),900).returnArriveAt()).isEqualTo(130L);
        assertThat(f.ops.findByTargetId("gate_north").orElseThrow().completed()).isTrue();
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void committedMarchLostReplyDoesNotSaveOrLoseTroopsTwice(Backend b){
        var f=new Fixture(b);var uncertain=spy(f.marches);f.marches=uncertain;
        doAnswer(inv->{inv.callRealMethod();throw new IllegalStateException("owned return write reply lost");})
                .when(uncertain).save(any(March.class),anyLong());
        var returned=f.service().recall("owned-leader",f.original.id(),120);
        assertThat(returned.units()).containsEntry("unit_infantry_t4",2L);
        assertThat(f.marches.versionOf(f.original.id())).isEqualTo(1L);
        verify(uncertain,times(1)).save(any(March.class),anyLong());
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void ownerFailureKeepsReturningArmyAndRetryDoesNotRecalculateArrival(Backend b){
        var f=new Fixture(b);var real=f.owners;var broken=spy(real);f.owners=broken;
        doThrow(new IllegalStateException("owned owner unavailable")).when(broken).compareAndSet(any(),eq(1L));
        var service=f.service();assertThatThrownBy(()->service.recall("owned-leader",f.original.id(),120)).isInstanceOf(IllegalStateException.class);
        assertThat(f.current().status()).isEqualTo(March.Status.RETURNING);assertThat(f.current().returnArriveAt()).isEqualTo(130L);
        assertThat(real.findByTargetIds(List.of("gate_north")).get("gate_north").garrison()).isEqualTo(TargetOccupationStore.Garrison.stationed(f.original.id()));
        doCallRealMethod().when(broken).compareAndSet(any(),eq(1L));service.recoverPending();
        assertThat(f.current().returnArriveAt()).isEqualTo(130L);assertThat(f.marches.versionOf(f.original.id())).isEqualTo(1L);
        assertThat(f.ops.findByTargetId("gate_north").orElseThrow().completed()).isTrue();
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void completionFailureAfterQueueCommitBlocksEvenAnAlreadyDueRefundAndDeletion(Backend b){
        var f=new Fixture(b);var real=f.ops;var broken=spy(real);f.ops=broken;
        var failure=new IllegalStateException("owned completion unavailable");
        doAnswer(inv->{TargetOperationStore.Snapshot next=inv.getArgument(0);if(next.completed())throw failure;return inv.callRealMethod();})
                .when(broken).compareAndSet(any(),anyLong());
        var service=f.service();assertThatThrownBy(()->service.recall("owned-leader",f.original.id(),120)).isSameAs(failure);
        assertThat(f.due.dueBefore(500,10)).contains(f.original.id());
        var refunded=new AtomicInteger();
        assertThatThrownBy(()->service.runMarchLocked(f.current(),()->{refunded.incrementAndGet();f.marches.delete(f.original.id());return true;})).isSameAs(failure);
        assertThat(refunded).hasValue(0);assertThat(f.current().status()).isEqualTo(March.Status.RETURNING);
        doCallRealMethod().when(broken).compareAndSet(any(),anyLong());service.recoverPending();
        assertThat(service.runMarchLocked(f.current(),()->{refunded.incrementAndGet();return true;})).isTrue();
        assertThat(refunded).hasValue(1);assertThat(real.findByTargetId("gate_north").orElseThrow().completed()).isTrue();
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void queueFailureIsRecoverableAndCannotProduceACompletedSlot(Backend b){
        var f=new Fixture(b);var real=f.due;var broken=spy(real);f.due=broken;
        doThrow(new IllegalStateException("owned queue unavailable")).when(broken).reschedule(anyString(),anyLong());
        var service=f.service();assertThatThrownBy(()->service.recall("owned-leader",f.original.id(),120)).isInstanceOf(IllegalStateException.class);
        assertThat(f.ops.findByTargetId("gate_north").orElseThrow().completed()).isFalse();
        doCallRealMethod().when(broken).reschedule(anyString(),anyLong());service.recoverPending();
        assertThat(real.dueBefore(500,10)).contains(f.original.id());assertThat(f.ops.findByTargetId("gate_north").orElseThrow().completed()).isTrue();
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void replayRechecksPendingPlanAfterAnEarlierEmptyOperationLookup(Backend b){
        var f=new Fixture(b);var brokenQueue=spy(f.due);f.due=brokenQueue;
        var failure=new IllegalStateException("owned queue still unavailable");
        doThrow(failure).when(brokenQueue).reschedule(anyString(),anyLong());
        assertThatThrownBy(()->f.service().recall("owned-leader",f.original.id(),120)).isSameAs(failure);
        // A较早的查询先于B的计划落盘；A随后才观察到B留下的RETURNING/EMPTY中间态。
        var staleLookup=spy(f.ops);f.ops=staleLookup;
        doReturn(Optional.empty()).when(staleLookup).findByOperationId("withdraw:"+f.original.id());
        assertThatThrownBy(()->f.service().replay("owned-leader",f.original.id())).isSameAs(failure);
        assertThat(f.ops.findByTargetId("gate_north").orElseThrow().completed()).isFalse();
        doCallRealMethod().when(brokenQueue).reschedule(anyString(),anyLong());
        assertThat(f.service().replay("owned-leader",f.original.id()).orElseThrow().returnArriveAt()).isEqualTo(130L);
        assertThat(f.ops.findByTargetId("gate_north").orElseThrow().completed()).isTrue();
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void ownerAndCompletionLostRepliesConfirmExactCommittedState(Backend b){
        var f=new Fixture(b);var uncertainOwner=spy(f.owners);f.owners=uncertainOwner;
        doAnswer(inv->{inv.callRealMethod();throw new IllegalStateException("owned owner reply lost");})
                .when(uncertainOwner).compareAndSet(any(),eq(1L));
        var uncertainSlot=spy(f.ops);f.ops=uncertainSlot;
        doAnswer(inv->{TargetOperationStore.Snapshot next=inv.getArgument(0);var result=inv.callRealMethod();
            if(next.completed())throw new IllegalStateException("owned completion reply lost");return result;})
                .when(uncertainSlot).compareAndSet(any(),anyLong());
        assertThat(f.service().recall("owned-leader",f.original.id(),120).returnArriveAt()).isEqualTo(130L);
        assertThat(f.ops.findByTargetId("gate_north").orElseThrow().completed()).isTrue();
        verify(uncertainOwner,times(1)).compareAndSet(any(),eq(1L));
        verify(uncertainSlot,times(1)).compareAndSet(argThat(TargetOperationStore.Snapshot::completed),anyLong());
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void queueCommittedLostReplyRemainsPendingUntilConfirmedRetry(Backend b){
        var f=new Fixture(b);var uncertain=spy(f.due);f.due=uncertain;
        doAnswer(inv->{inv.callRealMethod();throw new IllegalStateException("owned queue reply lost");})
                .when(uncertain).reschedule(anyString(),anyLong());
        assertThatThrownBy(()->f.service().recall("owned-leader",f.original.id(),120)).isInstanceOf(IllegalStateException.class);
        assertThat(f.due.dueBefore(500,10)).contains(f.original.id());
        assertThat(f.ops.findByTargetId("gate_north").orElseThrow().completed()).isFalse();
        doCallRealMethod().when(uncertain).reschedule(anyString(),anyLong());
        assertThat(f.service().recall("owned-leader",f.original.id(),900).returnArriveAt()).isEqualTo(130L);
        assertThat(f.ops.findByTargetId("gate_north").orElseThrow().completed()).isTrue();
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void corruptCandidateDoesNotStarveAnotherTargetRecovery(Backend b){
        var f=new Fixture(b);var brokenQueue=spy(f.due);f.due=brokenQueue;
        doThrow(new IllegalStateException("owned queue before recovery")).when(brokenQueue).reschedule(anyString(),anyLong());
        assertThatThrownBy(()->f.service().recall("owned-leader",f.original.id(),120)).isInstanceOf(IllegalStateException.class);
        doCallRealMethod().when(brokenQueue).reschedule(anyString(),anyLong());
        var isolated=spy(f.ops);f.ops=isolated;
        doReturn(List.of("capital","gate_north")).when(isolated).pendingTargetIds(anyCollection());
        doThrow(new IllegalStateException("owned corrupt first plan")).when(isolated).findByTargetId("capital");
        f.service().recoverPending();
        assertThat(f.ops.findByTargetId("gate_north").orElseThrow().completed()).isTrue();
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void concurrentRecallsKeepOneFrozenTimeAndSequence(Backend b) throws Exception {
        var f=new Fixture(b);var service=f.service();var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var one=pool.submit(()->{ready.countDown();start.await();return service.recall("owned-leader",f.original.id(),120);});
            var two=pool.submit(()->{ready.countDown();start.await();return service.recall("owned-leader",f.original.id(),900);});
            assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
            var first=one.get(10,java.util.concurrent.TimeUnit.SECONDS);var second=two.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(first.snapshot()).isEqualTo(second.snapshot());
            assertThat(f.marches.versionOf(f.original.id())).isEqualTo(1L);
            var slot=f.ops.findByTargetId("gate_north").orElseThrow();
            assertThat(slot.completed()).isTrue();assertThat(slot.version()).isEqualTo(2);assertThat(slot.plan().sequence()).isEqualTo(1);
        } finally {start.countDown();pool.shutdownNow();}
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void missingJournalWithModernWithdrawalOwnerCannotConfirmOrAdvance(Backend b){
        var f=new Fixture(b);var service=f.service();service.recall("owned-leader",f.original.id(),120);
        f.ops=new InMemoryTargetOperationStore();var withoutJournal=f.service();
        assertThatThrownBy(()->withoutJournal.replay("owned-leader",f.original.id())).hasMessageContaining("操作槽缺失");
        var advanced=new AtomicInteger();
        assertThatThrownBy(()->withoutJournal.runMarchLocked(f.current(),advanced::incrementAndGet)).hasMessageContaining("操作槽缺失");
        assertThat(advanced).hasValue(0);assertThat(f.marches.findById(f.original.id())).isPresent();
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void recallReplaysWhenOtherCallerCompletesBetweenReplayAndOwnMarchRead(Backend b){
        var f=new Fixture(b);var other=f.service();var observed=spy(f.marches);f.marches=observed;
        var reads=new AtomicInteger();
        doAnswer(inv->{var snapshot=inv.callRealMethod();
            if(reads.getAndIncrement()==0)other.recall("owned-leader",f.original.id(),120);return snapshot;})
                .when(observed).findById(f.original.id());
        assertThat(f.service().recall("owned-leader",f.original.id(),900).returnArriveAt()).isEqualTo(130L);
        assertThat(f.ops.findByTargetId("gate_north").orElseThrow().plan().sequence()).isEqualTo(1L);
        assertThat(f.marches.versionOf(f.original.id())).isEqualTo(1L);
    }
}
