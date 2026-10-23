package com.ironoath.web.store;

import com.ironoath.battle.*;
import com.ironoath.web.battle.*;
import com.ironoath.web.store.mongo.*;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual isolated Mongo plus precise write/readback acknowledgement failures. */
class BattleReportExactRecoveryTest {
 static final long T0=1_800_000_000_000L;static TestMongo db;
 @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();}
 @AfterAll static void close(){if(db!=null)db.close();}
 @BeforeEach void clear(){new MongoBattleReportStore(db.template()).clear();}
 BattleReport report(String id,long seed){return new BattleReport(id,"owned-A","owned-A","owned-D","守方","攻方",BattleType.SIEGE,List.of(),List.of(),new BattleResult(Winner.ATTACKER,List.of(),0,Map.of(),Map.of(),3,2,0,11,7,0,Map.of(),0,seed,List.of()),T0,T0+1000);}

 @Test void insertThenLostAcknowledgementNeedsAndAcceptsFullExactIndependentReadback(){
  var original=report(BattleReport.idForOperation("owned-op","owned-A"),1);var template=spy(db.template());var once=new AtomicBoolean();
  doAnswer(inv->{var value=inv.callRealMethod();if(once.compareAndSet(false,true))throw new IllegalStateException("forced-after-insert");return value;}).when(template).insert(any(BattleReportDocument.class),eq(BattleReportDocument.COLLECTION));
  assertThat(new MongoBattleReportStore(template).saveExact(original)).isEqualTo(original);assertThat(new MongoBattleReportStore(db.template()).findById(original.reportId())).contains(original);
 }
 @Test void failureBeforeInsertWithMissingReadbackPreservesOriginalWriteError(){
  var original=report(BattleReport.idForOperation("owned-op","owned-A"),1);var template=spy(db.template());
  doThrow(new IllegalStateException("forced-before-insert")).when(template).insert(any(BattleReportDocument.class),eq(BattleReportDocument.COLLECTION));
  assertThatThrownBy(()->new MongoBattleReportStore(template).saveExact(original)).hasMessage("forced-before-insert");assertThat(new MongoBattleReportStore(db.template()).findById(original.reportId())).isEmpty();
 }
 @Test void failedReadbackRetainsOriginalUnknownWriteAndAttachesReadError(){
  var original=report(BattleReport.idForOperation("owned-op","owned-A"),1);var template=spy(db.template());var inserted=new AtomicBoolean();
  doAnswer(inv->{inv.callRealMethod();inserted.set(true);throw new IllegalStateException("forced-after-insert");}).when(template).insert(any(BattleReportDocument.class),eq(BattleReportDocument.COLLECTION));
  doAnswer(inv->{if(inserted.get())throw new IllegalStateException("forced-readback");return inv.callRealMethod();}).when(template).findById(anyString(),eq(BattleReportDocument.class),eq(BattleReportDocument.COLLECTION));
  var failure=catchThrowable(()->new MongoBattleReportStore(template).saveExact(original));assertThat(failure).hasMessage("forced-after-insert");assertThat(failure.getSuppressed()).hasSize(1);assertThat(failure.getSuppressed()[0]).hasMessage("forced-readback");
  assertThat(new MongoBattleReportStore(db.template()).saveExact(original)).isEqualTo(original);
 }
 @Test void apparentInsertSuccessWithAbsentReadbackIsNeverCalledConfirmed(){
  var original=report(BattleReport.idForOperation("owned-op","owned-A"),1);var template=spy(db.template());
  doReturn(null).when(template).findById(eq(original.reportId()),eq(BattleReportDocument.class),eq(BattleReportDocument.COLLECTION));
  assertThatThrownBy(()->new MongoBattleReportStore(template).saveExact(original)).hasMessageContaining("未完整确认");assertThat(new MongoBattleReportStore(db.template()).findById(original.reportId())).contains(original);
 }
 @Test void mismatchedReadbackIsNotAnExactOriginalReceipt(){
  var original=report(BattleReport.idForOperation("owned-op","owned-A"),1);var other=report("owned-other-id",2);var actual=new MongoBattleReportStore(db.template());actual.save(other);var template=spy(db.template());
  var wrong=db.template().findById(other.reportId(),BattleReportDocument.class,BattleReportDocument.COLLECTION);doReturn(wrong).when(template).findById(eq(original.reportId()),eq(BattleReportDocument.class),eq(BattleReportDocument.COLLECTION));
  assertThatThrownBy(()->new MongoBattleReportStore(template).saveExact(original)).hasMessageContaining("载荷不一致");assertThat(actual.findById(original.reportId())).contains(original);
 }
 @Test void sameThrownWriteAndReadExceptionIsNotMaskedBySelfSuppression(){
  var original=report(BattleReport.idForOperation("owned-op","owned-A"),1);var same=new IllegalStateException("same-instance-io-failure");var template=spy(db.template());
  doThrow(same).when(template).insert(any(BattleReportDocument.class),eq(BattleReportDocument.COLLECTION));doThrow(same).when(template).findById(anyString(),eq(BattleReportDocument.class),eq(BattleReportDocument.COLLECTION));
  var failure=catchThrowable(()->new MongoBattleReportStore(template).saveExact(original));assertThat(failure).isSameAs(same);assertThat(failure.getSuppressed()).isEmpty();assertThat(new MongoBattleReportStore(db.template()).findById(original.reportId())).isEmpty();
 }
}
