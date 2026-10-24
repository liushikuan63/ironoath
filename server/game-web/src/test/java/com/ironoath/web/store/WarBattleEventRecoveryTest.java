package com.ironoath.web.store;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.web.nation.*;
import com.ironoath.web.world.TargetOperationStore;
import com.ironoath.web.store.memory.InMemoryTargetOperationStore;
import com.ironoath.web.store.memory.InMemoryWarStore;
import com.ironoath.web.store.mongo.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.query.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual owned Mongo/whole War CAS with controlled acknowledgement faults, no actual capture. */
class WarBattleEventRecoveryTest {
 static final long T0=1_800_000_000_000L;static TestMongo db;static WarRulesAssembler rules;
 @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();rules=new WarRulesAssembler(ConfigRegistry.loadFromDirectory(Path.of("contract/config")));}
 @AfterAll static void close(){if(db!=null)db.close();}
 @BeforeEach void clear(){new MongoWarStore(db.template(),rules).clear();}
 WarScoreBoard original(WarRulesAssembler r){var b=new WarScoreBoard(r.rules(),T0,"owned-NA",T0+1000);b.registerNation("owned-NA");b.registerNation("owned-NB");return b;}
 WarScoreBoard.BattleEvent event(WarScoreBoard b,String source,String operation){return b.freezeBattleEvent(source,1,operation,T0+1,List.of(new WarScoreBoard.BattleImpact("owned-A","owned-NA",11,2),new WarScoreBoard.BattleImpact("owned-B","owned-NB",3,7)));}
 MongoWarStore actual(){return new MongoWarStore(db.template(),rules);}
 Document raw(){return db.template().getCollection(WarDocument.COLLECTION).find(new Document("_id",WarStore.documentIdOf(T0))).first();}

 @Test void writeThenLostAcknowledgementOnlySucceedsAfterExactOriginalEventReadback(){
  var base=original(rules);actual().insertIfAbsent(base);var event=event(base,"gate_north","owned-op-1");var template=spy(db.template());var once=new AtomicBoolean();
  doAnswer(inv->{var result=inv.callRealMethod();if(once.compareAndSet(false,true))throw new IllegalStateException("forced-after-write");return result;}).when(template)
    .updateFirst(any(Query.class),any(UpdateDefinition.class),eq(WarDocument.class),eq(WarDocument.COLLECTION));
  assertThat(new MongoWarStore(template,rules).recordBattleEvent(event)).isEqualTo(event);var back=actual().findByStartedAt(T0).orElseThrow();assertThat(back.totalKills()).isEqualTo(14);assertThat(back.battleEvent("gate_north")).isEqualTo(event);
 }
 @Test void failureBeforeWriteAndMissingReceiptCannotBecomeCompletion(){
  var base=original(rules);actual().insertIfAbsent(base);var event=event(base,"gate_north","owned-op-1");var template=spy(db.template());
  doThrow(new IllegalStateException("forced-before-write")).when(template).updateFirst(any(Query.class),any(UpdateDefinition.class),eq(WarDocument.class),eq(WarDocument.COLLECTION));
  assertThatThrownBy(()->new MongoWarStore(template,rules).recordBattleEvent(event)).hasMessage("forced-before-write");var back=actual().findByStartedAt(T0).orElseThrow();assertThat(back.totalKills()).isZero();assertThat(back.battleEvent("gate_north")).isNull();
 }
 @Test void failedIndependentReadbackRetainsOriginalUnknownWriteEvenWhenPersisted(){
  var base=original(rules);actual().insertIfAbsent(base);var event=event(base,"gate_north","owned-op-1");var template=spy(db.template());var written=new AtomicBoolean();
  doAnswer(inv->{inv.callRealMethod();written.set(true);throw new IllegalStateException("forced-after-write");}).when(template)
    .updateFirst(any(Query.class),any(UpdateDefinition.class),eq(WarDocument.class),eq(WarDocument.COLLECTION));
  doAnswer(inv->{if(written.get())throw new IllegalStateException("forced-readback-failed");return inv.callRealMethod();}).when(template).findById(anyString(),eq(WarDocument.class),eq(WarDocument.COLLECTION));
  var failure=catchThrowable(()->new MongoWarStore(template,rules).recordBattleEvent(event));assertThat(failure).hasMessage("forced-after-write");assertThat(failure.getSuppressed()).hasSize(1);assertThat(failure.getSuppressed()[0]).hasMessage("forced-readback-failed");
  assertThat(actual().recordBattleEvent(event)).isEqualTo(event);assertThat(actual().findByStartedAt(T0).orElseThrow().totalKills()).isEqualTo(14);
 }
 @Test void concurrentOrdinaryKillBetweenReadAndCasCannotBeOverwritten(){
  var base=original(rules);actual().insertIfAbsent(base);var event=event(base,"gate_north","owned-op-1");var template=spy(db.template());var once=new AtomicBoolean();
  doAnswer(inv->{if(once.compareAndSet(false,true))actual().recordKills("owned-NA","owned-X",5,T0+1);return inv.callRealMethod();}).when(template)
    .updateFirst(any(Query.class),any(UpdateDefinition.class),eq(WarDocument.class),eq(WarDocument.COLLECTION));
  new MongoWarStore(template,rules).recordBattleEvent(event);var back=actual().findByStartedAt(T0).orElseThrow();assertThat(back.totalKills()).isEqualTo(19);assertThat(back.killsBy("owned-X")).isEqualTo(5);assertThat(back.battleEvent("gate_north")).isEqualTo(event);
 }
 @Test void ordinaryMarchKillFatigueAndSettlementPreserveOriginalEventAndClosedReplay(){
  var base=original(rules);actual().insertIfAbsent(base);var event=event(base,"gate_north","owned-op-1");
  actual().addMarchFatigueOnce("owned-march","owned-NA","owned-A",T0+1);actual().recordBattleEvent(event);actual().recordKills("owned-NA","owned-A",1,T0+2);actual().addFatigue("owned-NA","owned-A",1,0);
  var before=actual().findByStartedAt(T0).orElseThrow();assertThat(before.marchFatigueReceipt("owned-march")).isNotNull();assertThat(before.totalKills()).isEqualTo(15);assertThat(before.battleEvent("gate_north")).isEqualTo(event);
  actual().settleIfExpired(T0+1000);var closed=actual().findByStartedAt(T0).orElseThrow().toSnapshot();assertThat(closed.phase()).isEqualTo(WarScoreBoard.Phase.SETTLED);
  assertThat(actual().recordBattleEvent(event)).isEqualTo(event);assertThat(actual().findByStartedAt(T0).orElseThrow().toSnapshot()).isEqualTo(closed);
 }
 @Test void concurrentIndependentStoresKeepWholeEventsAndLegacyIncrement()throws Exception{
  var base=original(rules);actual().insertIfAbsent(base);var first=event(base,"gate_north","owned-op-1");var second=base.freezeBattleEvent("fortress_a",1,"owned-fort-op",T0+1,List.of(new WarScoreBoard.BattleImpact("owned-C","owned-NA",5,1)));
  var pool=Executors.newFixedThreadPool(4);
  try{List<Callable<Object>> tasks=List.of(()->actual().recordBattleEvent(first),()->actual().recordBattleEvent(first),()->actual().recordBattleEvent(second),()->actual().recordKills("owned-NA","owned-X",3,T0+1));for(var f:pool.invokeAll(tasks))assertThat(f.get(15,TimeUnit.SECONDS)).isNotNull();}
  finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
  var back=actual().findByStartedAt(T0).orElseThrow();assertThat(back.totalKills()).isEqualTo(22);assertThat(back.toSnapshot().battleEvents()).containsExactlyInAnyOrder(first,second);
 }
 @Test void rawLegacyBsonReadsWithoutRewriteAndFirstEventUpgradesOnlyDuringWrite(){
  var base=original(rules);actual().insertIfAbsent(base);db.template().getCollection(WarDocument.COLLECTION).updateOne(new Document("_id",WarStore.documentIdOf(T0)),new Document("$unset",new Document("state.battleEvents",1).append("version",1)));
  var before=raw();assertThat(actual().findByStartedAt(T0).orElseThrow().toSnapshot().battleEvents()).isEmpty();assertThat(raw()).isEqualTo(before);
  var event=event(base,"gate_north","owned-op-1");actual().recordBattleEvent(event);assertThat(raw().getLong("version")).isEqualTo(1);assertThat(actual().findByStartedAt(T0).orElseThrow().battleEvent("gate_north")).isEqualTo(event);
 }
 @Test void snapshotJsonRoundTripAndOldConstructorsRemainCompatible()throws Exception{
  var b=original(rules);var event=event(b,"gate_north","owned-op-1");b.applyBattleEvent(event);var snap=b.toSnapshot();
  var mapper=JsonMapper.builder().disable(MapperFeature.AUTO_DETECT_IS_GETTERS).build();var restored=mapper.readValue(mapper.writeValueAsString(snap),WarScoreBoard.Snapshot.class);assertThat(restored).isEqualTo(snap);assertThat(WarScoreBoard.fromSnapshot(restored,rules.rules()).applyBattleEvent(event)).isFalse();
  var old14=new WarScoreBoard.Snapshot(snap.startedAt(),snap.phase(),snap.initiatorNationId(),snap.nations(),snap.capitalHolder(),snap.capitalHeldSince(),snap.fatigue(),snap.playerKills(),snap.goalClaimed(),snap.totalKills(),snap.marchFatigueReceipts(),snap.siegeStartedAt(),snap.preparationExpiresAt(),snap.abandonedWithoutSiege());
  var old12=new WarScoreBoard.Snapshot(snap.startedAt(),snap.phase(),snap.initiatorNationId(),snap.nations(),snap.capitalHolder(),snap.capitalHeldSince(),snap.fatigue(),snap.playerKills(),snap.goalClaimed(),snap.totalKills(),snap.marchFatigueReceipts(),snap.siegeStartedAt());
  var old11=new WarScoreBoard.Snapshot(snap.startedAt(),snap.phase(),snap.initiatorNationId(),snap.nations(),snap.capitalHolder(),snap.capitalHeldSince(),snap.fatigue(),snap.playerKills(),snap.goalClaimed(),snap.totalKills(),snap.marchFatigueReceipts());
  for(var old:List.of(old14,old12,old11)){assertThat(old.battleEvents()).isEmpty();assertThat(WarScoreBoard.fromSnapshot(old,rules.rules()).totalKills()).isEqualTo(14);}
 }
 @Test void pendingPreDeadlineCombatReservationMustBlockLazyWarAbandonment() {
  var battle=StrategicCombatSlotTest.battle(2398,false,true);
  assertThat(battle.happenedAt()).isBetween(101L,299L);
  var plan=TargetOperationStore.CombatPlan.create(battle,1);
  var operations=new InMemoryTargetOperationStore();
  assertThat(operations.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,plan),0)).isTrue();

  var wars=new InMemoryWarStore(rules);var board=new WarScoreBoard(rules.rules(),100,"owned-N",300L);
  board.registerNation("owned-N");board.registerNation("owned-opponent");
  assertThat(wars.insertIfNoneActive(board)).isTrue();
  assertThat(wars.reserveBattle("gate_north",1,plan.operationId(),battle.happenedAt(),
          List.of(new WarScoreBoard.BattleMember("owned-attacker","owned-N",1))))
          .isPresent().get().extracting(WarScoreBoard.BattleReservation::operationId).isEqualTo(plan.operationId());
  var settlement=wars.settleIfExpired(300L).orElseThrow();

  assertThat(settlement.settledNow())
          .as("the pending target COMBAT happened inside this War window and must recover before expiry is committed")
          .isFalse();
  assertThat(settlement.board().phase()).isEqualTo(WarScoreBoard.Phase.PREPARATION);
  assertThat(operations.findByTargetId("gate_north")).isPresent()
          .get().extracting(TargetOperationStore.Snapshot::completed).isEqualTo(false);
 }
 @Test void pendingBattleReservationSurvivesMongoRestartAndReleasesOnlyAfterExactWarEvent() {
  var base=original(rules);actual().insertIfAbsent(base);
  var reservation=actual().reserveBattle("gate_north",1,"owned-op-1",T0+1,
          List.of(new WarScoreBoard.BattleMember("owned-A","owned-NA",11),
                  new WarScoreBoard.BattleMember("owned-B","owned-NB",3))).orElseThrow();

  var restarted=new MongoWarStore(db.template(),rules);
  assertThat(restarted.pendingBattleReservation(T0,"owned-op-1")).contains(reservation);
  var blocked=restarted.settleIfExpired(T0+1000).orElseThrow();
  assertThat(blocked.settledNow()).isFalse();
  assertThat(blocked.board().phase()).isEqualTo(WarScoreBoard.Phase.PREPARATION);
  assertThatThrownBy(()->restarted.completeBattleOperation(T0,"owned-op-1"))
          .hasMessageContaining("WarEvent");
  assertThat(restarted.pendingBattleReservation(T0,"owned-op-1")).contains(reservation);

  var event=restarted.findByStartedAt(T0).orElseThrow().freezeReservedBattleEvent("owned-op-1",
          Map.of("owned-A",2L,"owned-B",7L));
  assertThat(restarted.recordBattleEvent(event)).isEqualTo(event);
  assertThat(restarted.completeBattleOperation(T0,"owned-op-1")).isTrue();
  assertThat(restarted.completeBattleOperation(T0,"owned-op-1")).isTrue();
  assertThat(restarted.pendingBattleReservation(T0,"owned-op-1")).isEmpty();
  assertThat(restarted.settleIfExpired(T0+1000).orElseThrow().abandonedNow()).isTrue();
 }
 @Test void firstPersistentApplicationUsesCapturedRatesRatherThanCurrentStoreMaterialization(){
  var current=rules.rules();var captured=new WarScoreBoard.Rules(current.durationMillis(),current.gateCount(),current.occupyScorePerMinute(),current.killScorePerUnit()+1,current.buildingScorePerCapture(),current.fatiguePerMarch(),current.fatiguePerWounded()+1,current.fatigueMax(),current.serverGoalKills());
  var b=new WarScoreBoard(captured,T0,"owned-NA",T0+1000);b.registerNation("owned-NA");b.registerNation("owned-NB");var event=event(b,"gate_north","owned-op-1");actual().insertIfAbsent(b);
  // Snapshot不带全局规则；真实仓储读回使用current，事件仍必须消费自己已冻结的系数。
  actual().recordBattleEvent(event);var back=actual().findByStartedAt(T0).orElseThrow();assertThat(back.snapshot().get("owned-NA").killScore()).isEqualTo(11*captured.killScorePerUnit());assertThat(back.fatigueOf("owned-A")).isEqualTo(Math.min(captured.fatigueMax(),2*captured.fatiguePerWounded()));
 }
}
