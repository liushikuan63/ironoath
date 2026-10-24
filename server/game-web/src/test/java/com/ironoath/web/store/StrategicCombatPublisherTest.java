package com.ironoath.web.store;

import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.social.Rally;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.web.battle.CapturedStrategicBattle;
import com.ironoath.web.battle.StrategicRallyCasualties;
import com.ironoath.web.lock.InJvmPlayerLock;
import com.ironoath.web.nation.NationOperationLock;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.nation.WarStore;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.*;
import com.ironoath.web.store.mongo.MongoTargetOperationStore;
import com.ironoath.web.store.mongo.MongoWarStore;
import com.ironoath.web.world.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual simulator payload, actual nation/rally/target lock scopes, random TestMongo only. */
class StrategicCombatPublisherTest {
    private static TestMongo db;
    @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();}
    @AfterAll static void close(){if(db!=null)db.close();}

    private static final class Fixture {
        final ConfigRegistry cfg=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        final CapturedStrategicBattle battle;
        final InMemoryMarchStore marches=new InMemoryMarchStore(32);
        final InMemoryTargetOccupationStore owners=new InMemoryTargetOccupationStore();
        final SocialStore store=mock(SocialStore.class);
        final SocialAppService social=mock(SocialAppService.class,CALLS_REAL_METHODS);
        final StrategicGarrisonWithdrawal targetLocks;
        final StrategicTargetDefender defenders;
        final WarStore wars;
        final TargetOperationStore operations;
        final StrategicCombatPublisher publisher;
        Fixture(boolean mongo,boolean player) {
            battle=StrategicCombatSlotTest.battle(2394,player,true,false);
            operations=mongo?new MongoTargetOperationStore(db.template()):new InMemoryTargetOperationStore();
            if(mongo)db.template().remove(new org.springframework.data.mongodb.core.query.Query(),"world_target_operation");
            var lock=new InJvmPlayerLock();
            var warRules=new WarRulesAssembler(cfg);wars=mongo?new MongoWarStore(db.template(),warRules):new InMemoryWarStore(warRules);
            wars.clear();
            var board=new WarScoreBoard(warRules.rules(),100,"owned-N",300L);
            board.registerNation("owned-N");board.registerNation(player?"owned-defender-N":"owned-opponent");
            assertThat(wars.insertIfNoneActive(board)).isTrue();
            ReflectionTestUtils.setField(social,"store",store);
            ReflectionTestUtils.setField(social,"armies",mock(ArmyRepository.class));
            ReflectionTestUtils.setField(social,"playerLock",lock);
            ReflectionTestUtils.setField(social,"nationLock",new NationOperationLock(lock));
            ReflectionTestUtils.setField(social,"heldRallyContexts",new ThreadLocal<>());
            install(battle.attacker());if(battle.defender()!=null)install(battle.defender());
            if(battle.target().defender().occupation()!=null)
                assertThat(owners.compareAndSet(battle.target().defender().occupation(),0)).isTrue();
            defenders=new StrategicTargetDefender(cfg,owners,marches,store,new InMemoryNeutralTargetGarrisonStore());
            targetLocks=new StrategicGarrisonWithdrawal(cfg,operations,owners,marches,mock(MarchDueQueue.class),defenders,social,lock);
            publisher=new StrategicCombatPublisher(cfg,operations,marches,defenders,store,social,targetLocks,wars);
        }
        StrategicCombatPublisher publisherWith(TargetOperationStore port) {
            return new StrategicCombatPublisher(cfg,port,marches,defenders,store,social,targetLocks,wars);
        }
        void install(StrategicRallyCasualties side) {
            var identity=side.rally();
            var rally=Rally.restore(identity.rallyId(),identity.scope(),identity.nationId(),identity.initiatorId(),
                    2,1,100,110,identity.participants(),identity.status(),identity.departure(),
                    identity.targetX(),identity.targetY(),identity.targetType(),identity.version());
            when(store.rallyOf(rally.rallyId())).thenReturn(Optional.of(rally));
            var march=March.fromSnapshot(side.march());assertThat(marches.insertIfAbsent(march)).isTrue();
            while(marches.versionOf(march.id())<side.marchVersion())marches.save(march,marches.versionOf(march.id()));
        }
        <T> T locked(Supplier<T> action) {
            var ids=new ArrayList<String>();ids.add(battle.attacker().rally().rallyId());
            if(battle.defender()!=null)ids.add(battle.defender().rally().rallyId());
            return social.runRalliesDepartureLocked(ids,action);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void publishAndExactReplayPreserveOnePendingOriginalPayload(boolean mongo) {
        var f=new Fixture(mongo,false);
        var slot=f.locked(()->f.publisher.publish(f.battle));
        assertThat(slot.kind()).isEqualTo(TargetOperationStore.Kind.COMBAT);
        assertThat(slot.completed()).isFalse();assertThat(slot.sequence()).isEqualTo(1);
        assertThat(slot.combatPlan().battle()).isEqualTo(f.battle);
        assertThat(f.operations.findByTargetId(slot.targetId())).contains(slot);
        assertThat(f.wars.pendingBattleReservation(100,slot.operationId())).isPresent()
                .get().extracting(com.ironoath.core.nation.WarScoreBoard.BattleReservation::sourceId).isEqualTo(slot.targetId());
        assertThat(f.locked(()->f.publisher.publish(f.battle))).isEqualTo(slot);
        assertThat(f.marches.versionOf(f.battle.attacker().march().id())).isEqualTo(f.battle.attacker().marchVersion());
        assertThat(f.owners.findByTargetIds(List.of(slot.targetId()))).isEmpty();
    }

    @Test void bothOriginalPlayerRalliesMustAlreadyBeLocked() {
        var f=new Fixture(false,true);
        assertThatThrownBy(()->f.publisher.publish(f.battle)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->f.social.runRallyDepartureLocked(f.battle.attacker().rally().rallyId(),
                ()->f.publisher.publish(f.battle))).isInstanceOf(IllegalStateException.class);
        assertThat(f.operations.findByTargetId("gate_north")).isEmpty();
        assertThat(f.locked(()->f.publisher.publish(f.battle)).combatPlan().battle()).isEqualTo(f.battle);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void changedSourceMarchVersionCannotPublishOldFrozenCombat(boolean playerDefender) {
        var f=new Fixture(false,playerDefender);
        var side=playerDefender?f.battle.defender():f.battle.attacker();
        var original=March.fromSnapshot(side.march());f.marches.save(original,side.marchVersion());
        assertThatThrownBy(()->f.locked(()->f.publisher.publish(f.battle)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(f.operations.findByTargetId("gate_north")).isEmpty();
    }

    @Test void closedWarCannotAcceptUnreservedPreDeadlineCombatPublication() {
        var f=new Fixture(false,false);
        assertThat(f.wars.settleIfExpired(300L).orElseThrow().abandonedNow()).isTrue();
        assertThatThrownBy(()->f.locked(()->f.publisher.publish(f.battle)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已闭战窗口");
        assertThat(f.operations.findByTargetId("gate_north")).isEmpty();
        assertThat(f.marches.findById(f.battle.attacker().march().id())).isPresent();
    }

    @Test void changedTargetCannotBePublishedEvenWithOriginalMarchAndRally() {
        var f=new Fixture(false,true);var before=f.battle.target().defender().occupation();
        var changed=new TargetOccupationStore.Snapshot(before.targetId(),before.kind(),before.x(),before.y(),
                before.nationId(),before.nationName(),before.capturedAt(),"owned-new-owner-operation",2,before.garrison());
        assertThat(f.owners.compareAndSet(changed,1)).isTrue();
        assertThatThrownBy(()->f.locked(()->f.publisher.publish(f.battle)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("守军或归属");
        assertThat(f.operations.findByTargetId("gate_north")).isEmpty();
    }

    @Test void sameOperationIdCannotReplaceStoredKernelResult() {
        var f=new Fixture(false,false);var first=f.locked(()->f.publisher.publish(f.battle));
        var changed=StrategicCombatSlotTest.battle(2395,false,true,false);
        assertThatThrownBy(()->f.locked(()->f.publisher.publish(changed)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("原载荷");
        assertThat(f.operations.findByTargetId(first.targetId())).contains(first);
    }

    @Test void pendingWithdrawalCannotBeOverwrittenByNewCombat() {
        var f=new Fixture(false,false);
        var pending=new TargetOperationStore.Snapshot("gate_north",1,false,TargetOperationStoreTest.plan("owned-pending",1,120));
        f.operations.compareAndSet(pending,0);
        assertThatThrownBy(()->f.locked(()->f.publisher.publish(f.battle)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("未完成");
        assertThat(f.operations.findByTargetId("gate_north")).contains(pending);
    }

    @Test void holdingSameRallyIdForAnotherNationDoesNotAuthorizeOriginalCombat() {
        var f=new Fixture(false,false);var r=f.battle.attacker().rally();
        var changed=Rally.restore(r.rallyId(),r.scope(),"owned-another-nation",r.initiatorId(),2,1,100,110,
                r.participants(),r.status(),r.departure(),r.targetX(),r.targetY(),r.targetType(),r.version());
        when(f.store.rallyOf(r.rallyId())).thenReturn(Optional.of(changed));
        assertThatThrownBy(()->f.locked(()->f.publisher.publish(f.battle)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("国家");
        assertThat(f.operations.findByTargetId("gate_north")).isEmpty();
    }

    @Test void sameNationAndRallyIdCannotHideChangedOriginalRallyVersion() {
        var f=new Fixture(false,false);
        f.store.rallyOf(f.battle.attacker().rally().rallyId()).orElseThrow().arrive();
        assertThatThrownBy(()->f.locked(()->f.publisher.publish(f.battle)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("集结状态或成员");
        assertThat(f.operations.findByTargetId("gate_north")).isEmpty();
    }

    @Test void pendingExactReplayReadsFrozenPayloadDespitePartialLiveTargetChanges() {
        var f=new Fixture(false,false);var first=f.locked(()->f.publisher.publish(f.battle));
        f.marches.deleteIfVersion(f.battle.attacker().march().id(),f.battle.attacker().marchVersion());
        f.owners.compareAndSet(TargetOperationStoreTest.owner(TargetOperationStoreTest.stationed("owned-unreadable-live"),1),0);
        assertThatThrownBy(()->f.defenders.read(com.ironoath.web.service.WorldTargets.from(f.cfg).targets().stream()
                .filter(t->t.definition().id().equals("gate_north")).findFirst().orElseThrow()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(f.locked(()->f.publisher.publish(f.battle))).isEqualTo(first);
        assertThat(f.operations.findByTargetId("gate_north")).contains(first);
    }

    @Test void lostWriteReplyIsAcceptedOnlyAfterExactIndependentReadback() {
        var f=new Fixture(false,false);var port=spy(f.operations);
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("owned reply lost");})
                .when(port).compareAndSet(any(),anyLong());
        var actual=f.locked(()->f.publisherWith(port).publish(f.battle));
        assertThat(f.operations.findByTargetId("gate_north")).contains(actual);
        assertThat(actual.completed()).isFalse();
    }

    @Test void unreadableUnknownWriteKeepsOriginalFailureAndPendingStateUnconfirmed() {
        var f=new Fixture(false,false);var port=mock(TargetOperationStore.class);
        var write=new IllegalStateException("owned write unknown");var read=new IllegalStateException("owned read unavailable");
        when(port.findByTargetId("gate_north")).thenReturn(Optional.empty()).thenThrow(read);
        when(port.compareAndSet(any(),anyLong())).thenThrow(write);
        assertThatThrownBy(()->f.locked(()->f.publisherWith(port).publish(f.battle)))
                .isSameAs(write).hasSuppressedException(read);
        assertThat(f.operations.findByTargetId("gate_north")).isEmpty();
    }

    @Test void successfulReplyWithoutStoredPlanCannotPassPublicationBarrier() {
        var f=new Fixture(false,false);var port=mock(TargetOperationStore.class);
        when(port.compareAndSet(any(),anyLong())).thenReturn(true);
        assertThatThrownBy(()->f.locked(()->f.publisherWith(port).publish(f.battle)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("独立读回");
        assertThat(f.operations.findByTargetId("gate_north")).isEmpty();
    }

    @Test void targetCasConflictDoesNotGrabNewVersionAndOverwriteAnotherOperation() {
        var f=new Fixture(false,false);var port=spy(f.operations);
        var competing=new TargetOperationStore.Snapshot("gate_north",1,false,TargetOperationStoreTest.plan("owned-racing",1,120));
        doAnswer(call->{f.operations.compareAndSet(competing,0);return call.callRealMethod();})
                .when(port).compareAndSet(any(),anyLong());
        assertThatThrownBy(()->f.locked(()->f.publisherWith(port).publish(f.battle)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CAS冲突");
        assertThat(f.operations.findByTargetId("gate_north")).contains(competing);
        verify(port,times(1)).compareAndSet(any(),eq(0L));
    }
}
