package com.ironoath.core.army;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ArmySnapshotImmutabilityTest {
    @Test void snapshotOfAnArmyDoesNotChangeAfterTheArmyChanges() {
        var army=new ArmyState();army.add("unit_a",5L);var captured=army.snapshot();army.add("unit_a",95L);
        assertThat(captured.troops()).containsEntry("unit_a",5L);assertThat(army.countOf("unit_a")).isEqualTo(100L);
    }
    @Test void constructorCopiesAllSuppliedMutableContainers() {
        var troops=new LinkedHashMap<String,Long>();troops.put("unit_a",5L);
        var wounded=new LinkedHashMap<String,Long>();wounded.put("unit_a",2L);
        var cost=new LinkedHashMap<String,Long>();cost.put("GRAIN",10L);
        var task=new ArmyState.TrainingTask("unit_a",7L,1000L,2000L,1L,1L);
        var queue=new ArrayList<ArmyState.TrainingTask>();queue.add(task);
        var captured=new ArmyState.Snapshot(troops,queue,wounded,null,0,0,cost,1);
        troops.put("unit_a",100L);wounded.clear();cost.clear();queue.clear();
        assertThat(captured.troops()).containsExactly(entry("unit_a",5L));
        assertThat(captured.wounded()).containsExactly(entry("unit_a",2L));
        assertThat(captured.treatCost()).containsExactly(entry("GRAIN",10L));assertThat(captured.queue()).containsExactly(task);
    }
    @Test void directlyConstructedSnapshotContainersAreReadOnly() {
        var captured=new ArmyState.Snapshot(new LinkedHashMap<>(),new ArrayList<>(),new LinkedHashMap<>(),null,0,0,new LinkedHashMap<>(),0);
        assertThatThrownBy(()->captured.troops().put("unit_a",1L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->captured.wounded().put("unit_a",1L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->captured.treatCost().put("GRAIN",1L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->captured.queue().add(new ArmyState.TrainingTask("unit_a",1,1000,2000,1,1))).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void existingNullShapeAndLinkedOrderRemainUnchanged() {
        var empty=new ArmyState.Snapshot(null,null,null,null,0,0,null,0);
        assertThat(empty.troops()).isNull();assertThat(empty.queue()).isNull();assertThat(empty.wounded()).isNull();assertThat(empty.treatCost()).isNull();
        var ordered=new LinkedHashMap<String,Long>();ordered.put("unit_z",1L);ordered.put("unit_a",2L);
        var captured=new ArmyState.Snapshot(ordered,List.of(),ordered,null,0,0,ordered,0);
        assertThat(captured.troops().keySet()).containsExactly("unit_z","unit_a");
        assertThat(captured.wounded().keySet()).containsExactly("unit_z","unit_a");
        assertThat(captured.treatCost().keySet()).containsExactly("unit_z","unit_a");
    }
}
