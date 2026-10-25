package com.ironoath.core.march;

import com.ironoath.core.army.GarrisonLossAllocation;
import com.ironoath.core.world.Coord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class StrategicMarchCustodyTest {
    private static final String UNIT="unit_infantry_t4";
    private static Map<String,Map<String,Long>> owners(){return Map.of("owned-A",Map.of(UNIT,1L),"owned-B",Map.of(UNIT,1L));}
    private static March attack(){var m=new March("owned-custody","owned-A",Coord.of(10,10),Coord.of(256,216),100,110,Map.of(UNIT,2L),List.of(),50,1,March.TargetType.STRATEGIC_TARGET,"gate_north",March.Action.ATTACK);m.attachToRally("owned-custody-rally");m.arrive(110);return m;}

    @Test void returningUsesRecordedSurvivorOwnersRatherThanResplittingTheirTotal() {
        var before=owners();var allocation=GarrisonLossAllocation.plan(before,Map.of(UNIT,1L));
        assertThat(allocation.survivorsByPlayer()).isNotEqualTo(allocation.lossesByPlayer());
        var m=attack();var original=m.snapshot();m.applyStrategicSurvivors(before,allocation.survivorsByPlayer(),200,false);
        assertThat(m.status()).isEqualTo(March.Status.RETURNING);assertThat(m.returnArriveAt()).isEqualTo(210L);
        assertThat(m.hasGarrisonOwnership()).isTrue();assertThat(m.garrisonTroopsByPlayer()).isEqualTo(allocation.survivorsByPlayer());
        assertThat(m.units()).containsExactlyEntriesOf(Map.of(UNIT,1L));assertThat(m.from()).isEqualTo(original.from());assertThat(m.to()).isEqualTo(original.to());
        assertThat(m.startAt()).isEqualTo(original.startAt());assertThat(m.arriveAt()).isEqualTo(original.arriveAt());assertThat(m.loadCap()).isEqualTo(original.loadCap());
        assertThat(March.fromSnapshot(m.snapshot()).snapshot()).isEqualTo(m.snapshot());
        assertThatThrownBy(()->m.garrisonTroopsByPlayer().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void holdCopiesActualOwnersAndNeverMutatesCallerFacts() {
        var original=owners();var survivors=new LinkedHashMap<String,Map<String,Long>>();survivors.put("owned-A",new LinkedHashMap<>(Map.of(UNIT,1L)));survivors.put("owned-B",new LinkedHashMap<>());
        var m=attack();m.applyStrategicSurvivors(original,survivors,200,true);survivors.get("owned-A").clear();survivors.clear();
        assertThat(m.status()).isEqualTo(March.Status.STATIONED);assertThat(m.returnArriveAt()).isNull();assertThat(m.garrisonTroopsByPlayer()).containsEntry("owned-A",Map.of(UNIT,1L)).containsEntry("owned-B",Map.of());
        assertThat(original).isEqualTo(owners());assertThatThrownBy(()->m.garrisonTroopsByPlayer().get("owned-A").put(UNIT,5L)).isInstanceOf(UnsupportedOperationException.class);
    }
    @ParameterizedTest @ValueSource(strings={"extra-member","extra-unit","increase","wrong-before","early","overflow","empty"})
    void invalidFrozenCustodyOrTimeLeavesWholeOriginalUnchanged(String problem) {
        var m=attack();var snapshot=m.snapshot();var before=new LinkedHashMap<>(owners());var survivors=new LinkedHashMap<>(owners());long at=200;
        switch(problem){
            case "extra-member" -> survivors.put("intruder",Map.of(UNIT,1L));
            case "extra-unit" -> survivors.put("owned-A",Map.of("unit_cavalry_t4",1L));
            case "increase" -> survivors.put("owned-A",Map.of(UNIT,2L));
            case "wrong-before" -> before.put("owned-A",Map.of(UNIT,2L));
            case "early" -> at=109;
            case "overflow" -> at=Long.MAX_VALUE;
            case "empty" -> {survivors.put("owned-A",Map.of());survivors.put("owned-B",Map.of());}
        }
        final long happenedAt=at;
        assertThatThrownBy(()->m.applyStrategicSurvivors(before,survivors,happenedAt,false)).isInstanceOfAny(IllegalArgumentException.class,IllegalStateException.class,ArithmeticException.class);
        assertThat(m.snapshot()).isEqualTo(snapshot);
    }
    @Test void capitalRelocationMovesTheSameStationedGarrisonAndPreservesOwnerShares() {
        var m=attack();var ownerMap=owners();m.holdStrategicVictory(ownerMap);var before=m.snapshot();
        var destination=Coord.of(300,280);
        m.relocateStationedGarrison(destination);
        assertThat(m.id()).isEqualTo(before.id());assertThat(m.status()).isEqualTo(March.Status.STATIONED);
        assertThat(m.to()).isEqualTo(destination);assertThat(m.targetId()).isEqualTo("gate_north");
        assertThat(m.garrisonTroopsByPlayer()).isEqualTo(ownerMap);assertThat(m.units()).isEqualTo(before.units());
        assertThat(March.fromSnapshot(m.snapshot()).snapshot()).isEqualTo(m.snapshot());
        assertThatThrownBy(()->m.garrisonTroopsByPlayer().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void capitalRelocationRefusesAnUnstationedMarchWithoutMutation() {
        var m=attack();var original=m.snapshot();
        assertThatThrownBy(()->m.relocateStationedGarrison(Coord.of(300,280))).isInstanceOf(IllegalStateException.class);
        assertThat(m.snapshot()).isEqualTo(original);
    }
    @Test void zeroDepartureAndZeroSnapshotStayRejectedWithoutInventingTerminalState() {
        assertThat(March.Status.values()).hasSize(5);
        assertThatThrownBy(()->new March("empty","owned-A",Coord.of(1,1),Coord.of(2,2),100,110,Map.of(),List.of(),0,1,March.TargetType.EMPTY,null,March.Action.ATTACK)).isInstanceOf(IllegalArgumentException.class);
        var m=attack();m.applyLosses(Map.of(UNIT,2L));assertThatThrownBy(()->March.fromSnapshot(m.snapshot())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void differentMembersCannotChangeGlobalNormalizedUnitIterationOrder() {
        var before=Map.of("owned-A",Map.of("unit_siege_t4",1L),"owned-B",Map.of("unit_archer_t4",1L));
        var m=new March("owned-order","owned-A",Coord.of(10,10),Coord.of(256,216),100,110,Map.of("unit_siege_t4",1L,"unit_archer_t4",1L),List.of(),50,1,March.TargetType.STRATEGIC_TARGET,"gate_north",March.Action.ATTACK);
        m.attachToRally("owned-order-rally");m.arrive(110);m.applyStrategicSurvivors(before,before,200,true);
        assertThat(m.units().keySet()).containsExactly("unit_archer_t4","unit_siege_t4");
    }
    @Test void stationedOutcomeCannotReplaceCurrentOwnersEvenWhenTotalMatches() {
        var m=attack();m.holdStrategicVictory(owners());var original=m.snapshot();
        var other=Map.of("owned-A",Map.of(UNIT,2L),"owned-B",Map.<String,Long>of());
        assertThatThrownBy(()->m.applyStrategicSurvivors(other,other,200,false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(m.snapshot()).isEqualTo(original);
    }
    @Test void legacyGarrisonMayKeepCommanderAfterHisOwnShareIsGone() {
        var m=attack();var allocation=GarrisonLossAllocation.plan(owners(),Map.of(UNIT,1L));
        assertThat(allocation.survivorsByPlayer().get("owned-A")).isEmpty();m.applyLosses(Map.of(UNIT,1L));
        // 既有hold API允许存档省掉已无兵的成员，指挥者身份仍是原发起人，不能补回其兵。
        var current=Map.of("owned-B",Map.of(UNIT,1L));m.holdStrategicVictory(current);
        m.applyStrategicSurvivors(current,current,200,true);
        assertThat(m.playerId()).isEqualTo("owned-A");assertThat(m.garrisonTroopsByPlayer()).isEqualTo(current);
        assertThat(March.fromSnapshot(m.snapshot()).snapshot()).isEqualTo(m.snapshot());
    }
}
