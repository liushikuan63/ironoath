package com.ironoath.web;

import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.TierSplit;
import com.ironoath.core.march.March;
import com.ironoath.core.social.Rally;
import com.ironoath.core.world.Coord;
import com.ironoath.web.service.MarchAppService;
import com.ironoath.web.social.SocialStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 直接验证生产返兵步骤；全部为唯一命名的隔离内存夹具，不伪装真实争夺战。 */
@SpringBootTest @ActiveProfiles("test")
class RallyGarrisonReturnTest {
    private static final String UNIT="unit_infantry_t1";
    @Autowired private ArmyRepository armies;
    @Autowired private SocialStore social;
    @Autowired private MarchAppService service;
    @Autowired private com.ironoath.web.service.SocialAppService socialApp;

    private March fixture(boolean invalidMember) {
        String prefix="owned-garrison-"+UUID.randomUUID();
        Map<String,Rally.Participant> members=new LinkedHashMap<>();
        Map<String,Long> committed=new LinkedHashMap<>();long[] weights={1500,1500,900,500,500,200};
        for(int i=0;i<weights.length;i++) {
            String id=prefix+"-P"+(i+1);committed.put(id,weights[i]);
            members.put(id,new Rally.Participant(id,Map.of(UNIT,weights[i]),List.of()));
        }
        String leader=members.keySet().iterator().next();
        var rally=Rally.restore(prefix,Rally.Scope.NATION,"N-owned",leader,6,2,100,110,
                members,Rally.Status.PREPARING,null,20,20,"STRATEGIC_TARGET",1);
        rally.depart(110);social.saveRally(rally,0);
        var m=new March(prefix+"-march",leader,Coord.of(10,10),Coord.of(20,20),100,110,
                Map.of(UNIT,26L),List.of(),1000,1,March.TargetType.STRATEGIC_TARGET,"gate_north",March.Action.ATTACK);
        m.attachToRally(prefix);m.arrive(110);
        Map<String,Map<String,Long>> shares=new LinkedHashMap<>();
        TierSplit.splitProportionally(committed,26L).forEach((p,n)->shares.put(p,Map.of(UNIT,n)));
        if(invalidMember){shares.clear();shares.put(prefix+"-intruder",Map.of(UNIT,26L));}
        m.holdStrategicVictory(shares);
        m.beginGarrisonDefense();m.applyLosses(Map.of(UNIT,1L));m.finishGarrisonDefense();
        m.beginReturn(120,10);m.arriveHome(130);return m;
    }

    @Test void repeatedReturnUsesCurrentOwnershipAndCreditsEachMemberOnce() {
        var m=fixture(false);var expected=m.garrisonTroopsByPlayer();
        ReflectionTestUtils.invokeMethod(service,"returnRallyTroops",m);
        ReflectionTestUtils.invokeMethod(service,"returnRallyTroops",m);
        long total=0;
        for(var entry:expected.entrySet()) {
            var army=armies.findByPlayerId(entry.getKey()).orElseThrow();
            assertThat(army.countOf(UNIT)).isEqualTo(entry.getValue().getOrDefault(UNIT,0L));
            assertThat(army.snapshot().rallyRefunds()).hasSize(1);
            total+=army.countOf(UNIT);
        }
        assertThat(total).isEqualTo(25L);
    }

    @Test void unknownContributorIsRejectedBeforeAnyRefund() {
        var m=fixture(true);String intruder=m.garrisonTroopsByPlayer().keySet().iterator().next();
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(service,"returnRallyTroops",m))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("非原集结成员");
        assertThat(armies.findByPlayerId(intruder)).isEmpty();
    }

    @Test void readableStrategicRallyTypeDoesNotOpenAttackOrReserveTroops() {
        String player="closed-strategic-"+UUID.randomUUID();
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(socialApp,"initiateRally",player,
                Rally.Scope.NATION,"N-owned",new com.ironoath.web.dto.generated.SocialCoord(256,216),
                com.ironoath.web.dto.generated.SocialTargetType.STRATEGIC_TARGET,
                Map.of(UNIT,1L),List.of(),50,60_000L,100L))
                .isInstanceOf(com.ironoath.common.BizException.class).hasMessageContaining("尚未开放争夺");
        assertThat(armies.findByPlayerId(player)).isEmpty();
    }
}
