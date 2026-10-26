package com.ironoath.battle;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.ironoath.common.config.GlobalParamSource;
import com.ironoath.common.num.FixedPoint;

class BossMechanicAssemblyTest {
    static final class Source implements GlobalParamSource {
        final Map<String,Long> longs=new HashMap<>(Map.of("BOSS_REINFORCE_INTERVAL_ROUNDS",2L));
        final Map<String,Long> fixed=new HashMap<>(Map.of("BOSS_REINFORCE_RATIO",2000L,"BOSS_SHIELD_HP_THRESHOLD",5000L,"BOSS_SHIELD_DEF_BONUS",4000L,"BOSS_COUNTER_REFLECT_RATIO",1500L));
        final List<String> reads=new ArrayList<>();
        public long longParam(String id){reads.add(id);if(!longs.containsKey(id))throw new IllegalArgumentException("missing "+id);return longs.get(id);}
        public long fixedParam(String id){reads.add(id);if(!fixed.containsKey(id))throw new IllegalArgumentException("missing "+id);return fixed.get(id);}
        public boolean boolParam(String id){throw new AssertionError("unexpected bool read");}
        public String stringParam(String id){throw new AssertionError("unexpected string read");}
        public boolean hasParam(String id){throw new AssertionError("defaults must not hide missing parameters");}
    }
    @ParameterizedTest @EnumSource(BossMechanic.Type.class)
    void exactOriginalMappingReadsOnlySelectedFields(BossMechanic.Type type){
        var s=new Source();var actual=BossMechanic.from(type,s);
        var expected=switch(type){case NONE->BossMechanic.none();case REINFORCEMENT->BossMechanic.reinforcement(2,2000);case SHIELD_PHASE->BossMechanic.shieldPhase(5000,4000);case COUNTER_STRIKE->BossMechanic.counterStrike(1500);};
        assertThat(actual).isEqualTo(expected);
        assertThat(s.reads).containsExactlyElementsOf(switch(type){case NONE->List.of();case REINFORCEMENT->List.of("BOSS_REINFORCE_INTERVAL_ROUNDS","BOSS_REINFORCE_RATIO");case SHIELD_PHASE->List.of("BOSS_SHIELD_HP_THRESHOLD","BOSS_SHIELD_DEF_BONUS");case COUNTER_STRIKE->List.of("BOSS_COUNTER_REFLECT_RATIO");});
    }
    @Test void noneNeedsNoUnrelatedParameterSource(){assertThat(BossMechanic.from(BossMechanic.Type.NONE,null)).isEqualTo(BossMechanic.none());}
    @Test void nullTypeIsRejectedBeforeAnySourceRead(){var s=new Source();assertThatThrownBy(()->BossMechanic.from(null,s)).isInstanceOf(IllegalArgumentException.class);assertThat(s.reads).isEmpty();}
    @Test void invalidOrMissingSelectedParametersNeverBecomeNone(){
        var s=new Source();s.fixed.put("BOSS_COUNTER_REFLECT_RATIO",0L);assertThatThrownBy(()->BossMechanic.from(BossMechanic.Type.COUNTER_STRIKE,s)).isInstanceOf(IllegalArgumentException.class);
        s.fixed.clear();assertThatThrownBy(()->BossMechanic.from(BossMechanic.Type.COUNTER_STRIKE,s)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing");
    }
    @Test void laterSourceChangeCannotMutateAlreadyBuiltMechanic(){
        var s=new Source();var first=BossMechanic.from(BossMechanic.Type.COUNTER_STRIKE,s);s.fixed.put("BOSS_COUNTER_REFLECT_RATIO",FixedPoint.parse("0.35"));
        var next=BossMechanic.from(BossMechanic.Type.COUNTER_STRIKE,s);assertThat(first.ratioFixed()).isEqualTo(1500);assertThat(next.ratioFixed()).isEqualTo(3500);
    }
}
