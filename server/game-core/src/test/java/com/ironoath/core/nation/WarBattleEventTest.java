package com.ironoath.core.nation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 原目标全成员效果的领域回执，不代表真实攻占或真实成员击杀分配。 */
class WarBattleEventTest {
 static final long T0=1_800_000_000_000L;
 static WarScoreBoard.Rules rules(){return new WarScoreBoard.Rules(60000,4,1,2,3,5,1,100,50);}
 static WarScoreBoard board(){var b=new WarScoreBoard(rules(),T0,"owned-NA",T0+1000);b.registerNation("owned-NA");b.registerNation("owned-NB");return b;}
 static List<WarScoreBoard.BattleImpact> impacts(){return List.of(new WarScoreBoard.BattleImpact("owned-A","owned-NA",11,2),new WarScoreBoard.BattleImpact("owned-B","owned-NB",3,7),new WarScoreBoard.BattleImpact("owned-O","owned-NO",5,9),new WarScoreBoard.BattleImpact("owned-Z","owned-NA",0,0));}

 @Test void wholeOriginalEventCreditsOpponentDeadAndOwnWoundsOnce(){
  var b=board();var event=b.freezeBattleEvent("gate_north",1,"owned-op-1",T0+1,impacts());assertThat(b.applyBattleEvent(event)).isTrue();var after=b.toSnapshot();
  assertThat(b.applyBattleEvent(event)).isFalse();assertThat(b.toSnapshot()).isEqualTo(after);assertThat(b.totalKills()).isEqualTo(19);
  assertThat(b.killsBy("owned-A")).isEqualTo(11);assertThat(b.killsBy("owned-B")).isEqualTo(3);assertThat(b.killsBy("owned-O")).isEqualTo(5);
  assertThat(b.snapshot().get("owned-NA").killScore()).isEqualTo(22);assertThat(b.snapshot().get("owned-NB").killScore()).isEqualTo(6);
  assertThat(b.fatigueOf("owned-A")).isEqualTo(2);assertThat(b.fatigueOf("owned-B")).isEqualTo(7);assertThat(b.fatigueOf("owned-O")).isZero();assertThat(b.fatigueOf("owned-Z")).isZero();
  assertThat(b.battleEvent("gate_north")).isEqualTo(event);assertThat(event.effects()).hasSize(4);
 }
 @Test void pendingReservationSurvivesSnapshotAndBlocksEveryWarCloseUntilExactEventCompletion(){
  var b=board();var members=impacts().stream().map(i->new WarScoreBoard.BattleMember(i.playerId(),i.nationId(),i.kills())).toList();
  var reservation=b.reserveBattle("gate_north",1,"owned-op-1",T0+1,members).orElseThrow();
  var hotRules=new WarScoreBoard.Rules(60000,4,1,99,3,5,9,999,50);
  var restored=WarScoreBoard.fromSnapshot(b.toSnapshot(),hotRules);restored.registerNation("owned-NO");
  assertThat(restored.pendingBattleReservation("owned-op-1")).contains(reservation);
  assertThatThrownBy(()->restored.expirePreparation(T0+1000)).hasMessageContaining("COMBAT");
  assertThatThrownBy(()->restored.settle(T0+1000)).hasMessageContaining("COMBAT");
  assertThatThrownBy(()->restored.completeBattleOperation("owned-op-1")).hasMessageContaining("WarEvent");
  var wounds=Map.of("owned-A",2L,"owned-B",7L,"owned-O",9L,"owned-Z",0L);
  var event=restored.freezeReservedBattleEvent("owned-op-1",wounds);
  assertThat(event.rates()).isEqualTo(reservation.rates());
  assertThat(event.effects().stream().filter(e->e.impact().playerId().equals("owned-O")).findFirst().orElseThrow().participant()).isFalse();
  assertThat(restored.applyBattleEvent(event)).isTrue();
  assertThatThrownBy(()->restored.expirePreparation(T0+1000)).hasMessageContaining("COMBAT");
  assertThat(restored.completeBattleOperation("owned-op-1")).isTrue();
  assertThat(restored.completeBattleOperation("owned-op-1")).isTrue();
  restored.expirePreparation(T0+1000);
  assertThat(restored.phase()).isEqualTo(WarScoreBoard.Phase.SETTLED);
 }
 @Test void capturedNumericEffectsAndParticipationSurviveRuleAndMembershipMaterializationChanges(){
  var original=board();var event=original.freezeBattleEvent("gate_north",1,"owned-op-1",T0+1,impacts());
  var hot=new WarScoreBoard.Rules(60000,4,1,100,3,5,9,999,50);var b=WarScoreBoard.fromSnapshot(original.toSnapshot(),hot);b.registerNation("owned-NO");
  b.applyBattleEvent(event);assertThat(b.snapshot().get("owned-NA").killScore()).isEqualTo(22);assertThat(b.fatigueOf("owned-A")).isEqualTo(2);
  assertThat(b.snapshot().get("owned-NO").killScore()).isZero();assertThat(b.fatigueOf("owned-O")).isZero();
  b.recordKill("owned-NA",1);assertThat(b.snapshot().get("owned-NA").killScore()).isEqualTo(122);
  var restored=WarScoreBoard.fromSnapshot(b.toSnapshot(),hot);assertThat(restored.applyBattleEvent(event)).isFalse();assertThat(restored.toSnapshot()).isEqualTo(b.toSnapshot());
 }
 @Test void zeroImpactStillConfirmsAndClosedWarOnlyAcceptsExactPriorReceipt(){
  var b=board();var event=b.freezeBattleEvent("gate_north",1,"owned-zero",T0+1,List.of(new WarScoreBoard.BattleImpact("owned-A","owned-NA",0,0)));
  var missing=b.freezeBattleEvent("gate_south",1,"owned-missing",T0+1,impacts());b.applyBattleEvent(event);b.expirePreparation(T0+1000);var before=b.toSnapshot();
  assertThat(b.applyBattleEvent(event)).isFalse();assertThatThrownBy(()->b.applyBattleEvent(missing)).isInstanceOf(IllegalStateException.class);assertThat(b.toSnapshot()).isEqualTo(before);
 }
 @Test void capturedOlderFatigueLimitCannotEraseHigherAlreadyCommittedFatigue(){
  var original=board();var event=original.freezeBattleEvent("gate_north",1,"owned-old-limit",T0+1,List.of(new WarScoreBoard.BattleImpact("owned-A","owned-NA",0,2)));
  var hot=new WarScoreBoard.Rules(60000,4,1,2,3,5,9,999,50);var b=WarScoreBoard.fromSnapshot(original.toSnapshot(),hot);b.addFatigue("owned-A",0,20);
  assertThat(b.fatigueOf("owned-A")).isEqualTo(180);b.applyBattleEvent(event);assertThat(b.fatigueOf("owned-A")).isEqualTo(180);
 }
 @Test void aLatePublisherCannotCreateAnUnreservedPreDeadlineCombatAfterWarAlreadyClosed(){
  var closed=board();closed.expirePreparation(T0+1000);
  assertThatThrownBy(()->closed.reserveBattle("gate_north",1,"owned-late",T0+999,
          List.of(new WarScoreBoard.BattleMember("owned-A","owned-NA",1))))
          .hasMessageContaining("已闭战窗口");
  assertThat(closed.reserveBattle("gate_north",1,"owned-after",T0+1000,
          List.of(new WarScoreBoard.BattleMember("owned-A","owned-NA",1)))).isEmpty();
 }
 @Test void laterMemberOverflowCannotLeaveEarlierMemberOrReceiptPartiallyChanged(){
  var b=board();b.recordServerKill("owned-Z",Long.MAX_VALUE-2);var before=b.toSnapshot();
  var event=b.freezeBattleEvent("gate_north",1,"owned-overflow",T0+1,List.of(new WarScoreBoard.BattleImpact("owned-A","owned-NA",1,1),new WarScoreBoard.BattleImpact("owned-B","owned-NB",2,1)));
  assertThatThrownBy(()->b.applyBattleEvent(event)).isInstanceOf(ArithmeticException.class);assertThat(b.toSnapshot()).isEqualTo(before);assertThat(b.battleEvent("gate_north")).isNull();
 }
 @Test void originalWarAndSourceSequenceAreExactAndKeepOnlyLatestSourceEvent(){
  var b=board();var first=b.freezeBattleEvent("gate_north",1,"owned-op-1",T0+1,impacts());b.applyBattleEvent(first);
  var next=b.freezeBattleEvent("gate_north",3,"owned-op-3",T0+2,List.of(new WarScoreBoard.BattleImpact("owned-A","owned-NA",1,1)));b.applyBattleEvent(next);var before=b.toSnapshot();
  assertThat(before.battleEvents()).containsExactly(next);
  for(var bad:List.of(first,b.freezeBattleEvent("gate_north",3,"changed",T0+2,impacts()),b.freezeBattleEvent("gate_north",4,"owned-op-3",T0+2,impacts())))
   assertThatThrownBy(()->b.applyBattleEvent(bad)).isInstanceOf(IllegalStateException.class);
  var other=new WarScoreBoard(rules(),T0+10,"owned-NA",T0+1000);other.registerNation("owned-NA");assertThatThrownBy(()->other.applyBattleEvent(first)).isInstanceOf(IllegalArgumentException.class);
  assertThat(b.toSnapshot()).isEqualTo(before);
 }
 @Test void invalidMembersOrFreezeTimeRejectWithoutMutatingOriginalAndFactsAreImmutable(){
  var b=board();var before=b.toSnapshot();var duplicate=List.of(impacts().get(0),impacts().get(0));
  assertThatThrownBy(()->b.freezeBattleEvent("gate_north",1,"owned-bad",T0+1,duplicate)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->b.freezeBattleEvent("gate_north",1,"owned-bad",T0-1,impacts())).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->b.freezeBattleEvent("gate_north",1,"owned-bad",T0+1000,impacts())).isInstanceOf(IllegalStateException.class);
  var mutable=new ArrayList<>(impacts());var event=b.freezeBattleEvent("gate_north",1,"owned-op-1",T0+1,mutable);mutable.clear();assertThat(event.effects()).hasSize(4);assertThatThrownBy(()->event.effects().clear()).isInstanceOf(UnsupportedOperationException.class);assertThat(b.toSnapshot()).isEqualTo(before);
 }
}
