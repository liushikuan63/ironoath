package com.ironoath.core.world;

import com.ironoath.core.march.March;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** 连续守备按当前成员兵权扣损，不能每场按原始出兵比例重分剩余兵。 */
class StrategicGarrisonTest {
    private static final String UNIT="unit_infantry_t1";
    private March fighting() {
        var m=new March("garrison-march","P1",Coord.of(10,10),Coord.of(20,20),100,110,
                Map.of(UNIT,26L),List.of(),1000,1,March.TargetType.valueOf("STRATEGIC_TARGET"),
                "gate_north",March.Action.ATTACK);
        m.attachToRally("owned-rally");m.arrive(110);return m;
    }
    private Map<String,Map<String,Long>> owners() {
        var result=new LinkedHashMap<String,Map<String,Long>>();
        long[] values={8,8,5,2,2,1};
        for(int i=0;i<values.length;i++)result.put("P"+(i+1),new LinkedHashMap<>(Map.of(UNIT,values[i])));
        return result;
    }
    @Test void consecutiveLossesNeverIncreaseAnyMembersTroops() {
        var m=fighting();m.holdStrategicVictory(owners());
        assertThat(m.status()).isEqualTo(March.Status.STATIONED);
        var previous=m.garrisonTroopsByPlayer();
        for(int i=0;i<10;i++) {
            m.beginGarrisonDefense();m.applyLosses(Map.of(UNIT,1L));m.finishGarrisonDefense();
            var next=m.garrisonTroopsByPlayer();
            for(String player:previous.keySet())assertThat(next.get(player).getOrDefault(UNIT,0L))
                    .isLessThanOrEqualTo(previous.get(player).getOrDefault(UNIT,0L));
            assertThat(next.values().stream().mapToLong(u->u.getOrDefault(UNIT,0L)).sum())
                    .isEqualTo(m.totalUnits());
            m=March.fromSnapshot(m.snapshot());previous=m.garrisonTroopsByPlayer();
        }
        assertThat(m.totalUnits()).isEqualTo(16);
        assertThat(m.garrisonTroopsByPlayer().get("P4").getOrDefault(UNIT,0L)).isLessThanOrEqualTo(2);
    }
    @Test void ownershipIsDeeplyFrozenAndMismatchCannotPartiallyEnterGarrison() {
        var m=fighting();var input=owners();input.get("P4").put(UNIT,3L);
        assertThatThrownBy(()->m.holdStrategicVictory(input)).isInstanceOf(IllegalArgumentException.class);
        assertThat(m.status()).isEqualTo(March.Status.FIGHTING);
        input.get("P4").put(UNIT,2L);m.holdStrategicVictory(input);
        input.get("P4").put(UNIT,999L);
        assertThat(m.garrisonTroopsByPlayer().get("P4").get(UNIT)).isEqualTo(2L);
        assertThatThrownBy(()->m.garrisonTroopsByPlayer().get("P4").put(UNIT,99L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(m.hasGarrisonOwnership()).isTrue();
    }
    @Test void emptyVictorsAndOrdinaryTargetsCannotBecomeStrategicGarrisons() {
        var empty=fighting();empty.applyLosses(Map.of(UNIT,26L));
        assertThatThrownBy(()->empty.holdStrategicVictory(Map.of())).isInstanceOf(IllegalStateException.class);
        var normal=new March("normal","P1",Coord.of(1,1),Coord.of(2,2),100,110,
                Map.of(UNIT,26L),List.of(),1000,1,March.TargetType.MONSTER,"monster",March.Action.ATTACK);
        normal.attachToRally("owned-rally");normal.arrive(110);
        assertThatThrownBy(()->normal.holdStrategicVictory(owners())).isInstanceOf(IllegalStateException.class);
    }
}
