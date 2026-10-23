package com.ironoath.web.store;

import com.ironoath.battle.*;
import com.ironoath.web.battle.*;
import com.ironoath.web.store.memory.InMemoryBattleReportStore;
import com.ironoath.web.store.mongo.MongoBattleReportStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit immutable report fixtures/real stores; no actual battle producer or replay of additive service. */
class BattleReportExactTest {
 enum Backend{MEMORY,MONGO}
 static final long T0=1_800_000_000_000L;static TestMongo db;
 @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();}
 @AfterAll static void close(){if(db!=null)db.close();}
 @BeforeEach void clear(){new MongoBattleReportStore(db.template()).clear();}
 BattleReportStore store(Backend b){return b==Backend.MEMORY?new InMemoryBattleReportStore():new MongoBattleReportStore(db.template());}
 static BattleReport report(String id,String owner,long seed,long expires,String defenderName,List<String> heroes){return new BattleReport(id,owner,"owned-A","owned-D",defenderName,"攻方",BattleType.SIEGE,heroes,List.of(),new BattleResult(Winner.ATTACKER,List.of(),0,Map.of(),Map.of(),3,2,0,11,7,0,Map.of(),0,seed,List.of()),T0,expires);}
 static BattleReport original(){return report(BattleReport.idForOperation("owned-op-1","owned-A"),"owned-A",1,T0+1000,"守方",List.of());}

 @Test void stableIdentityFramesOperationAndOwnerWithoutClockOrDelimiterAmbiguity(){
  String id=BattleReport.idForOperation("owned-op-1","owned-A");assertThat(id).isEqualTo(BattleReport.idForOperation("owned-op-1","owned-A")).matches("battle_[a-f0-9]{64}");
  assertThat(id).isNotEqualTo(BattleReport.idForOperation("owned-op-2","owned-A")).isNotEqualTo(BattleReport.idForOperation("owned-op-1","owned-D"));
  assertThat(BattleReport.idForOperation("a:b","c")).isNotEqualTo(BattleReport.idForOperation("a","b:c"));
  assertThatThrownBy(()->BattleReport.idForOperation(" ","owned-A")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->BattleReport.idForOperation("op",null)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void malformedUnicodeCannotAliasLiteralReplacementCharacterInStableIdentity(){
  String malformed="op"+(char)0xd800;assertThatThrownBy(()->BattleReport.idForOperation(malformed,"owned-A")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->BattleReport.idForOperation("op","owner"+(char)0xd800)).isInstanceOf(IllegalArgumentException.class);
  assertThat(BattleReport.idForOperation("operation 🐎","玩家🐎")).isNotBlank();assertThat(BattleReport.idForOperation("op?","owned-A")).isNotBlank();
 }
 @ParameterizedTest @EnumSource(Backend.class)
 void exactReplayAfterRebuildKeepsOriginalAndExistingShareMetadata(Backend b){
  var s=store(b);var original=original();assertThat(s.saveExact(original)).isEqualTo(original);s.markShared(original.reportId(),"alliance:owned");
  s=b==Backend.MONGO?store(b):s;assertThat(s.saveExact(original)).isEqualTo(original);assertThat(s.reportsOf(original.ownerId())).containsExactly(original);assertThat(s.sharedChannels(original.reportId())).containsExactly("alliance:owned");
 }
 @ParameterizedTest @EnumSource(Backend.class)
 void sameIdWithChangedOriginalPayloadIsRejectedWhileLegacySaveStillKeepsFirst(Backend b){
  var s=store(b);var first=original();s.saveExact(first);s.markShared(first.reportId(),"alliance:owned");
  for(var changed:List.of(report(first.reportId(),"owned-D",1,T0+1000,"守方",List.of()),report(first.reportId(),"owned-A",2,T0+1000,"守方",List.of()),report(first.reportId(),"owned-A",1,T0+2000,"守方",List.of()),report(first.reportId(),"owned-A",1,T0+1000,"新名",List.of()),report(first.reportId(),"owned-A",1,T0+1000,"守方",List.of("owned-hero")))){
   assertThatThrownBy(()->s.saveExact(changed)).as("backend=%s owner=%s seed=%d expires=%d name=%s heroes=%s",b,changed.ownerId(),changed.result().seed(),changed.expiresAt(),changed.defenderName(),changed.attackerHeroIds()).isInstanceOf(IllegalStateException.class);s.save(changed);assertThat(s.findById(first.reportId())).contains(first);assertThat(s.sharedChannels(first.reportId())).containsExactly("alliance:owned");
  }
 }
 @ParameterizedTest @EnumSource(Backend.class)
 void retentionPurgeAndOriginalReinsertNeverRenewCapturedExpiration(Backend b){
  var s=store(b);var first=original();s.saveExact(first);assertThat(s.purgeExpired(T0+1000)).isEqualTo(1);assertThat(s.findById(first.reportId())).isEmpty();
  var replay=s.saveExact(first);assertThat(replay).isEqualTo(first);assertThat(replay.expired(T0+1000)).isTrue();assertThat(replay.expiresAt()).isEqualTo(T0+1000);
 }
 @ParameterizedTest @EnumSource(Backend.class)
 void originalOperationAndBothOwnerPerspectivesHaveDistinctStableRecords(Backend b){
  var s=store(b);var first=original();var next=report(BattleReport.idForOperation("owned-op-2","owned-A"),"owned-A",2,T0+1000,"守方",List.of());var defender=report(BattleReport.idForOperation("owned-op-1","owned-D"),"owned-D",1,T0+1000,"守方",List.of());
  for(var report:List.of(first,next,defender))assertThat(s.saveExact(report)).isEqualTo(report);assertThat(s.reportsOf("owned-A")).hasSize(2);assertThat(s.reportsOf("owned-D")).containsExactly(defender);
 }
 @ParameterizedTest @EnumSource(Backend.class)
 void concurrentSameOriginalAndConflictingPayloadHaveOneImmutableWinner(Backend b)throws Exception{
  var s=store(b);var first=original();var pool=Executors.newFixedThreadPool(4);
  try{
   List<Callable<BattleReport>> tasks=new ArrayList<>();for(int i=0;i<12;i++)tasks.add(()->s.saveExact(first));for(var result:pool.invokeAll(tasks))assertThat(result.get(15,TimeUnit.SECONDS)).isEqualTo(first);
   var changed=report(first.reportId(),"owned-A",2,T0+1000,"守方",List.of());List<Callable<Object>> mixed=List.of(()->s.saveExact(first),()->{try{return s.saveExact(changed);}catch(IllegalStateException expected){return expected;}});
   var results=pool.invokeAll(mixed);assertThat(results.get(0).get()).isEqualTo(first);assertThat(results.get(1).get()).isInstanceOf(IllegalStateException.class);assertThat(s.findById(first.reportId())).contains(first);
  }finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
 }
}
