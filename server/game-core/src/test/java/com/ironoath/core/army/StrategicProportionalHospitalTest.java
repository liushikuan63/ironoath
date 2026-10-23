package com.ironoath.core.army;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** 用户2026-10-23裁定仅用于战略成员本医院；普通收容维持原顺序。 */
class StrategicProportionalHospitalTest {
    private static final String LOW="unit_infantry_t1",HIGH="unit_infantry_t4";
    private Map<String,Long> wounds(boolean reverse) {
        var values=new LinkedHashMap<String,Long>();
        if(reverse){values.put(HIGH,4L);values.put(LOW,4L);}else{values.put(LOW,4L);values.put(HIGH,4L);}
        return values;
    }

    @Test void strategicAdmissionIsProportionalAndIndependentOfRequestOrder() {
        for(boolean reverse:new boolean[]{false,true}) {
            var army=new ArmyState();army.add(LOW,100L);var input=wounds(reverse);
            var receipt=army.admitStrategicWoundedOnce("gate_north",1,"battle-proportional",input,5);
            assertThat(receipt.admitted()).isEqualTo(Map.of(LOW,3L,HIGH,2L));
            assertThat(receipt.overflowDead()).isEqualTo(3);assertThat(army.totalWounded()).isEqualTo(5);
            assertThat(army.totalTroops()).as("出征已扣的兵不能再扣城内留守兵").isEqualTo(100);
            assertThat(input).isEqualTo(wounds(reverse));
        }
    }

    @Test void ordinaryAdmissionKeepsExistingInputOrder() {
        var army=new ArmyState();long overflow=army.admitWounded(wounds(false),5);
        assertThat(army.wounded()).isEqualTo(Map.of(LOW,4L,HIGH,1L));assertThat(overflow).isEqualTo(3);
    }

    @Test void eachMemberUsesItsOwnRemainingBeds() {
        var first=new ArmyState();first.admitWounded(Map.of(LOW,3L),100);
        var second=new ArmyState();
        var a=first.admitStrategicWoundedOnce("gate_north",1,"one-battle",wounds(false),5);
        var b=second.admitStrategicWoundedOnce("gate_north",1,"one-battle",wounds(false),5);
        assertThat(a.admitted()).isEqualTo(Map.of(LOW,1L,HIGH,1L));
        assertThat(b.admitted()).isEqualTo(Map.of(LOW,3L,HIGH,2L));
        assertThat(a.overflowDead()).isEqualTo(6);assertThat(b.overflowDead()).isEqualTo(3);
        assertThat(first.totalWounded()).isEqualTo(5);assertThat(second.totalWounded()).isEqualTo(5);
    }

    private ArmyState withReceipt(ArmyState army,ArmyState.StrategicWoundReceipt receipt) {
        var s=army.snapshot();return ArmyState.fromSnapshot(new ArmyState.Snapshot(s.troops(),s.queue(),s.wounded(),s.treatFinishAt(),
                s.treatTotalSeconds(),s.treatOriginalSeconds(),s.treatCost(),s.extraSlots(),s.autoTrain(),s.rallyRefunds(),Map.of(receipt.targetId(),receipt)));
    }

    @Test void newPolicyReplaysTheOriginalResultAfterTreatmentAndRequestReordering() {
        var army=new ArmyState();var first=army.admitStrategicWoundedOnce("gate_north",1,"proportional",wounds(false),5);
        assertThat(first.policy()).isEqualTo(ArmyState.StrategicWoundPolicy.PROPORTIONAL_V1);
        army.startTreatment(1,Map.of(),100);army.collectTreated(1100);assertThat(army.totalWounded()).isZero();
        var restored=ArmyState.fromSnapshot(army.snapshot());
        assertThat(restored.admitStrategicWoundedOnce("gate_north",1,"proportional",wounds(true),5)).isEqualTo(first);
        assertThat(restored.totalWounded()).isZero();
    }

    @Test void legacyReceiptKeepsOriginalOrderedFactsAndNeverReadmitsAfterHealing() {
        var legacy=new ArmyState.StrategicWoundReceipt("gate_north","legacy",1,wounds(false),Map.of(LOW,4L,HIGH,1L),5,3);
        var army=withReceipt(new ArmyState(),legacy);var before=army.snapshot();
        assertThat(legacy.policy()).isEqualTo(ArmyState.StrategicWoundPolicy.LEGACY_ORDERED);
        assertThat(army.admitStrategicWoundedOnce("gate_north",1,"legacy",wounds(false),5)).isEqualTo(legacy);
        assertThat(army.totalWounded()).isZero();
        assertThatThrownBy(()->army.admitStrategicWoundedOnce("gate_north",1,"legacy",wounds(true),5)).isInstanceOf(IllegalStateException.class);
        assertThat(army.snapshot()).isEqualTo(before);
        var next=army.admitStrategicWoundedOnce("gate_north",2,"new-battle",wounds(true),5);
        assertThat(next.policy()).isEqualTo(ArmyState.StrategicWoundPolicy.PROPORTIONAL_V1);
        assertThat(next.admitted()).isEqualTo(Map.of(LOW,3L,HIGH,2L));
    }

    @Test void missingPolicyNormalizesToLegacyWithoutRecomputingSavedAdmission() {
        var receipt=new ArmyState.StrategicWoundReceipt("gate_north","old-no-column",1,wounds(false),Map.of(LOW,4L,HIGH,1L),5,3,null);
        assertThat(receipt.policy()).isEqualTo(ArmyState.StrategicWoundPolicy.LEGACY_ORDERED);
        assertThat(receipt.admitted()).isEqualTo(Map.of(LOW,4L,HIGH,1L));
    }

    @Test void zeroRoomAndExistingWoundedAboveCapacityDoNotEvictOldPatients() {
        var army=new ArmyState();army.admitWounded(Map.of(LOW,3L),100);
        var first=army.admitStrategicWoundedOnce("gate_north",1,"no-room",wounds(false),2);
        assertThat(first.admitted()).isEmpty();assertThat(first.overflowDead()).isEqualTo(8);
        assertThat(army.wounded()).containsExactlyEntriesOf(Map.of(LOW,3L));
        var empty=army.admitStrategicWoundedOnce("gate_south",1,"zero-wounds",Map.of(),0);
        assertThat(empty.admitted()).isEmpty();assertThat(empty.overflowDead()).isZero();
        assertThat(empty.policy()).isEqualTo(ArmyState.StrategicWoundPolicy.PROPORTIONAL_V1);
        assertThat(army.snapshot().strategicWounds()).hasSize(2);
    }

    @Test void malformedAndOverflowingIncomingRequestsCannotPartiallyChangeHospital() {
        var army=new ArmyState();army.add(LOW,100);var before=army.snapshot();
        assertThatThrownBy(()->army.admitStrategicWoundedOnce("gate_north",1,"multiply-overflow",Map.of(LOW,Long.MAX_VALUE),2)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(()->army.admitStrategicWoundedOnce("gate_north",1,"sum-overflow",Map.of(LOW,Long.MAX_VALUE,HIGH,1L),2)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(()->army.admitStrategicWoundedOnce("gate_north",1,"negative",Map.of(LOW,2L,HIGH,-1L),2)).isInstanceOf(IllegalArgumentException.class);
        assertThat(army.snapshot()).isEqualTo(before);
    }

    @Test void smallIntegerInputsConserveBedsAndNeverAdmitMoreThanRequested() {
        for(long low=0;low<7;low++)for(long high=0;high<7;high++)for(long room=0;room<8;room++) {
            var army=new ArmyState();var requested=new LinkedHashMap<String,Long>();requested.put(HIGH,high);requested.put(LOW,low);
            var receipt=army.admitStrategicWoundedOnce("gate_north",1,"bounded-fixture",requested,room);
            long admitted=receipt.admitted().values().stream().mapToLong(Long::longValue).sum();
            assertThat(admitted).isEqualTo(Math.min(room,low+high));assertThat(admitted+receipt.overflowDead()).isEqualTo(low+high);
            assertThat(receipt.admitted().getOrDefault(LOW,0L)).isBetween(0L,low);
            assertThat(receipt.admitted().getOrDefault(HIGH,0L)).isBetween(0L,high);
        }
    }
}
