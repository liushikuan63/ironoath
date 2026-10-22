package com.ironoath.core.army;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** 医院与当前目标代次同档；不是全历史账本，也不证明目标结算权。 */
class StrategicWoundReceiptTest {
    @Test void replayReturnsExactAdmissionAfterPersistenceAndTreatment() {
        var army=new ArmyState();army.add("unit_infantry_t4",100L);
        army.admitWounded(Map.of("unit_infantry_t4",3L),10);
        Map<String,Long> incoming=new LinkedHashMap<>();incoming.put("unit_infantry_t4",6L);incoming.put("unit_cavalry_t4",4L);
        var receipt=army.admitStrategicWoundedOnce("gate_north",1,"battle-one",incoming,10);
        assertThat(receipt.admitted()).hasSize(2).containsEntry("unit_infantry_t4",6L).containsEntry("unit_cavalry_t4",1L);
        assertThat(receipt.requested().keySet()).containsExactly("unit_infantry_t4","unit_cavalry_t4");
        assertThat(receipt.overflowDead()).isEqualTo(3L);
        assertThat(army.totalWounded()).isEqualTo(10L);assertThat(army.totalTroops()).isEqualTo(100L);
        var restored=ArmyState.fromSnapshot(army.snapshot());
        assertThat(restored.admitStrategicWoundedOnce("gate_north",1,"battle-one",incoming,10)).isEqualTo(receipt);
        assertThat(restored.totalWounded()).isEqualTo(10L);
        restored.startTreatment(1,Map.of(),100);restored.collectTreated(1100);
        assertThat(restored.totalWounded()).isZero();
        assertThat(restored.admitStrategicWoundedOnce("gate_north",1,"battle-one",incoming,10)).isEqualTo(receipt);
        assertThat(restored.totalWounded()).as("已治愈者不能被旧战斗再次送进医院").isZero();
        assertThatThrownBy(()->receipt.admitted().put("unit_siege_t4",1L)).isInstanceOf(UnsupportedOperationException.class);
        incoming.clear();assertThat(receipt.requested()).hasSize(2);
    }

    @Test void newerTargetSequenceReplacesOneSlotAndRejectsOldOrChangedOperation() {
        var army=new ArmyState();Map<String,Long> wounds=Map.of("unit_infantry_t4",2L);
        army.admitStrategicWoundedOnce("gate_north",1,"battle-one",wounds,100);
        army.admitStrategicWoundedOnce("gate_north",3,"battle-two",wounds,100);
        army.admitStrategicWoundedOnce("gate_south",1,"battle-three",wounds,100);
        var before=army.snapshot();
        assertThat(before.strategicWounds()).hasSize(2);
        assertThatThrownBy(()->army.admitStrategicWoundedOnce("gate_north",1,"battle-one",wounds,100)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->army.admitStrategicWoundedOnce("gate_north",3,"different",wounds,100)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->army.admitStrategicWoundedOnce("gate_north",4,"battle-two",wounds,100)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->army.admitStrategicWoundedOnce("gate_north",3,"battle-two",Map.of("unit_infantry_t4",3L),100)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->army.admitStrategicWoundedOnce("gate_north",3,"battle-two",wounds,101)).isInstanceOf(IllegalStateException.class);
        assertThat(army.snapshot()).isEqualTo(before);
    }

    @Test void invalidInputCannotPartiallyAdmitAndEmptyResultStillRetainsWatermark() {
        var army=new ArmyState();army.admitWounded(Map.of("unit_infantry_t4",3L),100);
        var before=army.snapshot();Map<String,Long> mixed=new LinkedHashMap<>();mixed.put("unit_infantry_t4",2L);mixed.put("unit_cavalry_t4",-1L);
        assertThatThrownBy(()->army.admitStrategicWoundedOnce("gate_north",1,"bad",mixed,100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->army.admitStrategicWoundedOnce("gate_north",1,"overflow",Map.of("a",Long.MAX_VALUE,"b",1L),100)).isInstanceOf(ArithmeticException.class);
        assertThat(army.snapshot()).isEqualTo(before);
        var empty=army.admitStrategicWoundedOnce("gate_north",2,"zero-wounds",Map.of(),0);
        assertThat(empty.admitted()).isEmpty();assertThat(empty.overflowDead()).isZero();assertThat(army.totalWounded()).isEqualTo(3L);
        assertThat(army.snapshot().strategicWounds()).hasSize(1);
    }
}
