package com.ironoath.core.stage;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import com.ironoath.core.army.UnitKind;

class StageUnitRuleTest {
    static Stream<Arguments> cases(){
        Map<String,Set<UnitKind>> allowed=Map.of("NONE",Set.of(UnitKind.values()),
                "NO_SIEGE",Set.of(UnitKind.INFANTRY,UnitKind.CAVALRY,UnitKind.ARCHER),
                "CAVALRY_ONLY",Set.of(UnitKind.CAVALRY),"RANGED_ONLY",Set.of(UnitKind.ARCHER,UnitKind.SIEGE));
        return allowed.entrySet().stream().flatMap(r->Arrays.stream(UnitKind.values()).map(k->Arguments.of(r.getKey(),k,r.getValue().contains(k))));
    }
    @ParameterizedTest @MethodSource("cases")
    void originalProductionTruthTable(String rule,UnitKind kind,boolean expected){
        assertThat(StageUnitRule.valueOf(rule).allows(kind)).isEqualTo(expected);
    }
    @Test void missingUnitKindFailsClosedEvenForUnrestrictedStage(){
        assertThatThrownBy(()->StageUnitRule.NONE.allows(null)).isInstanceOf(NullPointerException.class);
    }
}
