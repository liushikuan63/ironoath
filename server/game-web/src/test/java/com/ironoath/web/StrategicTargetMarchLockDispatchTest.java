package com.ironoath.web;

import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.world.Coord;
import com.ironoath.web.service.MarchAppService;
import com.ironoath.web.service.StrategicTargetMarchAdvancer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Strategic arrival must acquire both real battle rally locks before the shared target lock. */
class StrategicTargetMarchLockDispatchTest {
    @Test
    void strategicArrivalUsesWholeBattleRallyLockSetBeforeTargetOperation() {
        String marchId="owned-strategic-attacker-march";
        var attacker=new March(marchId,"owned-attacker",Coord.of(10,10),Coord.of(256,216),100,200,
                Map.of("unit_infantry_t4",1L),List.of(),100,1,March.TargetType.STRATEGIC_TARGET,
                "gate_north",March.Action.ATTACK);
        attacker.attachToRally("owned-attacker-rally");
        var marches=mock(MarchRepository.class);when(marches.findById(marchId)).thenReturn(Optional.of(attacker));
        var strategic=mock(StrategicTargetMarchAdvancer.class);
        when(strategic.advance(eq(attacker),eq(200L),any())).thenReturn(true);
        var service=mock(MarchAppService.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service,"marches",marches);
        ReflectionTestUtils.setField(service,"strategicTargetMarchAdvancer",strategic);

        var advanced=ReflectionTestUtils.invokeMethod(service,"advanceDueMarch",marchId,200L);

        assertThat(advanced).isEqualTo(true);
        verify(strategic).advance(eq(attacker),eq(200L),any());
    }
}
