package com.ironoath.web.store;

import com.ironoath.core.season.SeasonSettlement;
import com.ironoath.core.season.SeasonSettlement.Board;
import com.ironoath.web.season.SeasonBoardStore.ScopedIncrement;
import com.ironoath.web.store.mongo.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real owned Mongo; injected acknowledgement faults do not replace persisted storage or assert rollback. */
class SeasonScopedIncrementRecoveryTest {
 static final String S="owned-season-2384",P="owned-P",SOURCE="gate.$[unsafe]";
 static TestMongo db;
 @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();}
 @AfterAll static void close(){if(db!=null)db.close();}
 @BeforeEach void clear(){new MongoSeasonBoardStore(db.template()).clear();}
 ScopedIncrement event(){return new ScopedIncrement(SOURCE,1,"owned-op-1",P,"原名",7);}
 Document row(){return db.template().getCollection(SeasonBoardDocument.COLLECTION).find(new Document("_id",SeasonBoardDocument.keyOf(S,Board.KILL,P))).first();}
 long score(){return new MongoSeasonBoardStore(db.template()).board(S,Board.KILL).get(0).score();}
 String hash()throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(SOURCE.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}

 @Test void legacyRawRowIsReadWithoutRewriteAndFirstReceiptUsesExistingScore(){
  var legacy=new Document("_id",SeasonBoardDocument.keyOf(S,Board.KILL,P)).append("seasonId",S).append("board","KILL").append("playerId",P).append("name","旧名").append("score",10L);
  db.template().getCollection(SeasonBoardDocument.COLLECTION).insertOne(legacy);var s=new MongoSeasonBoardStore(db.template());
  assertThat(s.scopedIncrementOf(S,Board.KILL,P,SOURCE)).isEmpty();assertThat(row()).isEqualTo(legacy);
  s.recordScopedIncrement(S,Board.KILL,event());assertThat(score()).isEqualTo(17);assertThat(s.recordScopedIncrement(S,Board.KILL,event())).isEqualTo(event());assertThat(score()).isEqualTo(17);
 }
 @Test void realWriteThenLostAcknowledgementRequiresExactIndependentReceiptReadback(){
  var template=spy(db.template());var once=new AtomicBoolean();
  doAnswer(inv->{var result=inv.callRealMethod();if(once.compareAndSet(false,true))throw new IllegalStateException("forced-after-write");return result;})
    .when(template).upsert(any(Query.class),any(UpdateDefinition.class),eq(SeasonBoardDocument.class),eq(SeasonBoardDocument.COLLECTION));
  var s=new MongoSeasonBoardStore(template);assertThat(s.recordScopedIncrement(S,Board.KILL,event())).isEqualTo(event());
  assertThat(new MongoSeasonBoardStore(db.template()).scopedIncrementOf(S,Board.KILL,P,SOURCE)).contains(event());assertThat(score()).isEqualTo(7);
 }
 @Test void failureBeforeWriteWithMissingReceiptIsNotSwallowedOrInvented(){
  var template=spy(db.template());doThrow(new IllegalStateException("forced-before-write")).when(template)
    .upsert(any(Query.class),any(UpdateDefinition.class),eq(SeasonBoardDocument.class),eq(SeasonBoardDocument.COLLECTION));
  assertThatThrownBy(()->new MongoSeasonBoardStore(template).recordScopedIncrement(S,Board.KILL,event())).hasMessage("forced-before-write");
  assertThat(row()).isNull();assertThat(new MongoSeasonBoardStore(db.template()).scopedIncrementOf(S,Board.KILL,P,SOURCE)).isEmpty();
 }
 @Test void readbackFailureRetainsOriginalUncertaintyEvenWhenActualWriteCommitted(){
  var template=spy(db.template());var written=new AtomicBoolean();
  doAnswer(inv->{inv.callRealMethod();written.set(true);throw new IllegalStateException("forced-after-write");}).when(template)
    .upsert(any(Query.class),any(UpdateDefinition.class),eq(SeasonBoardDocument.class),eq(SeasonBoardDocument.COLLECTION));
  doAnswer(inv->{if(written.get())throw new IllegalStateException("forced-readback-failed");return inv.callRealMethod();}).when(template)
    .findById(anyString(),eq(Document.class),eq(SeasonBoardDocument.COLLECTION));
  var failure=catchThrowable(()->new MongoSeasonBoardStore(template).recordScopedIncrement(S,Board.KILL,event()));
  assertThat(failure).hasMessage("forced-after-write");assertThat(failure.getSuppressed()).hasSize(1);assertThat(failure.getSuppressed()[0]).hasMessage("forced-readback-failed");
  assertThat(score()).isEqualTo(7);var rebuilt=new MongoSeasonBoardStore(db.template());assertThat(rebuilt.recordScopedIncrement(S,Board.KILL,event())).isEqualTo(event());assertThat(score()).isEqualTo(7);
 }
 @Test void legacyIncrementBetweenReadAndCasIsRetriedWithoutLostScore(){
  var actual=new MongoSeasonBoardStore(db.template());actual.report(S,Board.KILL,new SeasonSettlement.Entry(P,"旧名",10));
  var template=spy(db.template());var once=new AtomicBoolean();
  doAnswer(inv->{if(once.compareAndSet(false,true))actual.accumulate(S,Board.KILL,new SeasonSettlement.Entry(P,"并发名",5),5);return inv.callRealMethod();}).when(template)
    .upsert(any(Query.class),any(UpdateDefinition.class),eq(SeasonBoardDocument.class),eq(SeasonBoardDocument.COLLECTION));
  new MongoSeasonBoardStore(template).recordScopedIncrement(S,Board.KILL,event());assertThat(score()).isEqualTo(22);assertThat(actual.scopedIncrementOf(S,Board.KILL,P,SOURCE)).contains(event());
 }
 @Test void malformedOrDifferentSourceReceiptFailsBeforeAnyWrite()throws Exception{
  var actual=new MongoSeasonBoardStore(db.template());actual.recordScopedIncrement(S,Board.KILL,event());var original=row();
  for(Object bad:Arrays.asList(null,"invalid",new Document(hash(),new Document("sourceId","other-source")))){
   var changed=new Document(original);changed.put("scopedIncrements",bad);db.template().getCollection(SeasonBoardDocument.COLLECTION).replaceOne(new Document("_id",original.get("_id")),changed);
   assertThatThrownBy(()->actual.recordScopedIncrement(S,Board.KILL,event())).isInstanceOf(IllegalStateException.class);assertThat(row()).isEqualTo(changed);
  }
 }
}
