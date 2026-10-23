package com.ironoath.web.store;

import com.ironoath.core.season.SeasonSettlement;
import com.ironoath.core.season.SeasonSettlement.Board;
import com.ironoath.web.season.SeasonBoardStore;
import com.ironoath.web.season.SeasonBoardStore.ScopedIncrement;
import com.ironoath.web.store.memory.InMemorySeasonBoardStore;
import com.ironoath.web.store.mongo.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Target-operation stream authority belongs to caller; real stores prove atomic score/receipt, not actual capture. */
class SeasonScopedIncrementTest {
 enum Backend{MEMORY,MONGO}
 static final String S="owned-season-2384",P="owned-P",SOURCE="gate.$[unsafe]";
 static TestMongo db;
 @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();}
 @AfterAll static void close(){if(db!=null)db.close();}
 @BeforeEach void clear(){new MongoSeasonBoardStore(db.template()).clear();}
 SeasonBoardStore store(Backend b){return b==Backend.MEMORY?new InMemorySeasonBoardStore():new MongoSeasonBoardStore(db.template());}
 SeasonBoardStore rebuild(Backend b,SeasonBoardStore s){return b==Backend.MONGO?store(b):s;}
 ScopedIncrement event(String source,long sequence,String operation,long delta){return new ScopedIncrement(source,sequence,operation,P,"原名",delta);}
 long score(SeasonBoardStore s){return s.board(S,Board.KILL).stream().filter(e->e.id().equals(P)).findFirst().orElseThrow().score();}

 @ParameterizedTest @EnumSource(Backend.class)
 void exactReplayAfterRebuildNeverAddsAgainAndNextSequenceKeepsOneWatermark(Backend b){
  var s=store(b);var e=event(SOURCE,1,"owned-op-1",7);
  assertThat(s.recordScopedIncrement(S,Board.KILL,e)).isEqualTo(e);s=rebuild(b,s);
  assertThat(s.scopedIncrementOf(S,Board.KILL,P,SOURCE)).contains(e);assertThat(s.recordScopedIncrement(S,Board.KILL,e)).isEqualTo(e);assertThat(score(s)).isEqualTo(7);
  var next=event(SOURCE,3,"owned-op-3",11);assertThat(s.recordScopedIncrement(S,Board.KILL,next)).isEqualTo(next);
  assertThat(s.scopedIncrementOf(S,Board.KILL,P,SOURCE)).contains(next);assertThat(score(s)).isEqualTo(18);
  if(b==Backend.MONGO){var row=db.template().getCollection(SeasonBoardDocument.COLLECTION).find(new org.bson.Document("_id",SeasonBoardDocument.keyOf(S,Board.KILL,P))).first();assertThat(((org.bson.Document)row.get("scopedIncrements")).size()).isEqualTo(1);}
 }
 @ParameterizedTest @EnumSource(Backend.class)
 void changedPayloadOldSequenceOrSameOperationWithNewSequenceCannotMutate(Backend b){
  var s=store(b);var e=event(SOURCE,3,"owned-op-3",7);s.recordScopedIncrement(S,Board.KILL,e);
  for(var invalid:List.of(event(SOURCE,3,"different",7),event(SOURCE,3,"owned-op-3",8),new ScopedIncrement(SOURCE,3,"owned-op-3",P,"改名",7),event(SOURCE,2,"owned-op-2",7),event(SOURCE,4,"owned-op-3",7)))
   assertThatThrownBy(()->s.recordScopedIncrement(S,Board.KILL,invalid)).isInstanceOf(IllegalStateException.class);
  assertThat(score(s)).isEqualTo(7);assertThat(s.scopedIncrementOf(S,Board.KILL,P,SOURCE)).contains(e);
 }
 @ParameterizedTest @EnumSource(Backend.class)
 void ordinaryAbsoluteAndAdditiveReportsPreserveReceiptWithoutReplayingOldDelta(Backend b){
  var s=store(b);var e=event(SOURCE,1,"owned-op-1",7);s.recordScopedIncrement(S,Board.KILL,e);
  s.report(S,Board.KILL,new SeasonSettlement.Entry(P,"后名",100));s.accumulate(S,Board.KILL,new SeasonSettlement.Entry(P,"后名",5),5);
  s=rebuild(b,s);assertThat(s.recordScopedIncrement(S,Board.KILL,e)).isEqualTo(e);assertThat(score(s)).isEqualTo(105);
  assertThat(s.board(S,Board.KILL).get(0).name()).isEqualTo("后名");
 }
 @ParameterizedTest @EnumSource(Backend.class)
 void independentSourcesPlayersSeasonsAndBoardsDoNotConsumeEachOthersReceipt(Backend b){
  var s=store(b);var e=event(SOURCE,1,"owned-op-1",7);s.recordScopedIncrement(S,Board.KILL,e);
  s.recordScopedIncrement(S,Board.KILL,event("gate-other",1,"owned-op-other",11));
  s.recordScopedIncrement(S,Board.WAR,e);s.recordScopedIncrement(S+"-other",Board.KILL,e);
  var other=new ScopedIncrement(SOURCE,1,"owned-op-1","owned-Q","其他",13);s.recordScopedIncrement(S,Board.KILL,other);
  assertThat(score(s)).isEqualTo(18);assertThat(s.board(S,Board.WAR).get(0).score()).isEqualTo(7);assertThat(s.board(S+"-other",Board.KILL).get(0).score()).isEqualTo(7);
  assertThat(s.scopedIncrementOf(S,Board.KILL,"owned-Q",SOURCE)).contains(other);
 }
 @ParameterizedTest @EnumSource(Backend.class)
 void zeroDeltaConfirmsOriginalEventAndOverflowLeavesNoReceiptOrPartialScore(Backend b){
  var s=store(b);var zero=event(SOURCE,1,"owned-op-1",0);s.recordScopedIncrement(S,Board.KILL,zero);s.recordScopedIncrement(S,Board.KILL,zero);assertThat(score(s)).isZero();
  s.report(S,Board.KILL,new SeasonSettlement.Entry(P,"原名",Long.MAX_VALUE));
  assertThatThrownBy(()->s.recordScopedIncrement(S,Board.KILL,event(SOURCE,2,"owned-op-2",1))).isInstanceOf(ArithmeticException.class);
  assertThat(score(s)).isEqualTo(Long.MAX_VALUE);assertThat(s.scopedIncrementOf(S,Board.KILL,P,SOURCE)).contains(zero);
 }
 @ParameterizedTest @EnumSource(Backend.class)
 void sameEventConcurrentReplayAndIndependentSourcesConserveScore(Backend b)throws Exception{
  var s=store(b);var e=event(SOURCE,1,"owned-op-1",1);var pool=Executors.newFixedThreadPool(4);
  try{
   var tasks=new ArrayList<Callable<ScopedIncrement>>();for(int i=0;i<16;i++)tasks.add(()->s.recordScopedIncrement(S,Board.KILL,e));
   for(var f:pool.invokeAll(tasks))assertThat(f.get(15,TimeUnit.SECONDS)).isEqualTo(e);assertThat(score(s)).isEqualTo(1);
   tasks.clear();for(int i=0;i<4;i++){var unique=event("gate-"+i,1,"op-"+i,2);tasks.add(()->s.recordScopedIncrement(S,Board.KILL,unique));}
   for(var f:pool.invokeAll(tasks))assertThat(f.get(15,TimeUnit.SECONDS)).isNotNull();assertThat(score(s)).isEqualTo(9);
  }finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
 }
 @ParameterizedTest @EnumSource(Backend.class)
 void purgeRemovesReceiptWithItsRowAndColdReadDoesNotInventCompletion(Backend b){
  var s=store(b);assertThat(s.scopedIncrementOf(S,Board.KILL,P,SOURCE)).isEmpty();s.recordScopedIncrement(S,Board.KILL,event(SOURCE,1,"owned-op-1",7));
  assertThat(s.purgeSeason(S)).isEqualTo(1);assertThat(s.board(S,Board.KILL)).isEmpty();assertThat(s.scopedIncrementOf(S,Board.KILL,P,SOURCE)).isEmpty();
 }
}
