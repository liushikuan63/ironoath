package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.social.Alliance;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.web.nation.NationLeaders;
import com.ironoath.web.nation.NationPolicyBonuses;
import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.nation.NationTechBonuses;
import com.ironoath.web.store.memory.InMemoryTargetOccupationStore;
import com.ironoath.web.tech.TechEffects;
import com.ironoath.web.world.TargetOccupationStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StrategicFortressOutputSettlementTest {
    private static final long START=1_700_000_000_000L;

    @Test void lazySettlementUsesSevenThenEightFortressesAtTheirExactCaptureTime() {
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var social=mock(com.ironoath.web.social.SocialStore.class);var nations=mock(NationStore.class);
        var leaders=mock(NationLeaders.class);var alliance=mock(Alliance.class);when(alliance.id()).thenReturn("alliance-a");
        when(social.allianceOf("member-a")).thenReturn(Optional.of(alliance));
        var nation=Nation.found("nation-a","产出国","king-a","alliance-a",10,20,START,
                new NationRulesAssembler(configs).rules());
        when(nations.findByAlliance("alliance-a")).thenReturn(Optional.of(nation));
        when(leaders.bind(any())).thenAnswer(call->call.getArgument(0));
        var occupations=new InMemoryTargetOccupationStore();
        var forts=WorldTargets.from(configs).targets().stream()
                .filter(target->target.definition().kind()==com.ironoath.config.cfg.WorldTargetCfg.Kind.FORTRESS)
                .toList();
        assertThat(forts).hasSize(8);
        for(int i=0;i<forts.size();i++) {
            var target=forts.get(i);long capturedAt=i==0?START+3_600_000:START;
            var owner=new TargetOccupationStore.Snapshot(target.definition().id(),
                    WorldGenerator.EntityType.valueOf(target.definition().kind().name()),target.coord().x(),target.coord().y(),
                    "nation-a","产出国",capturedAt,"capture-"+target.definition().id(),1,
                    TargetOccupationStore.Garrison.empty());
            assertThat(occupations.compareAndSet(owner,0)).isTrue();
        }
        var nationTech=new NationTechBonuses(configs,social,nations,leaders);
        var policy=new NationPolicyBonuses(configs,social,nations,leaders,mock(com.ironoath.web.battle.BattleArmyFactory.class));
        var rates=new ResourceRateService(configs,new TechEffects(configs),nationTech,policy,occupations);
        var resources=Map.of(ResourceIds.GRAIN,new PlayerResourceState(0,1_000_000,0,0,START));
        var player=PlayerSave.createNew("member-a","device-a","成员甲",1,START,1,resources,PlayerPower.zero(),null);

        var result=rates.settle(player,new com.ironoath.core.city.CityState(),START+7_200_000);

        assertThat(result.rates().breakdowns().get(ResourceIds.GRAIN).lines())
                .as("fortress output contributor should be resolved from this player's nation")
                .anySatisfy(line->{assertThat(line.source()).isEqualTo("科技、国家、国策与要塞加成");
                    assertThat(line.percentFixed()).isEqualTo(1_600);});

        long base=configs.getResource(ResourceIds.GRAIN).basePerHour();
        long sevenFortressRate=com.ironoath.core.resource.ResourceOutputCalculator.compute(
                List.of(com.ironoath.core.resource.ResourceOutputCalculator.Line.flat("base",base)),1_400,0,0,
                "combined").totalPerHour();
        long eightFortressRate=com.ironoath.core.resource.ResourceOutputCalculator.compute(
                List.of(com.ironoath.core.resource.ResourceOutputCalculator.Line.flat("base",base)),1_600,0,0,
                "combined").totalPerHour();
        assertThat(result.credited().get(ResourceIds.GRAIN)).isEqualTo(sevenFortressRate+eightFortressRate);
        assertThat(result.rates().perHour(ResourceIds.GRAIN)).isEqualTo(eightFortressRate);
        assertThat(result.rates().breakdowns().get(ResourceIds.GRAIN).lines())
                .anySatisfy(line->{assertThat(line.source()).isEqualTo("科技、国家、国策与要塞加成");
                    assertThat(line.percentFixed()).isEqualTo(1_600);});
        assertThat(result.states().get(ResourceIds.GRAIN).lastSettle()).isEqualTo(START+7_200_000);
        var viewPlayer=PlayerSave.createNew("member-a","device-view","成员乙",1,START,1,
                Map.of(ResourceIds.GRAIN,new PlayerResourceState(0,1_000_000,0,0,START)),PlayerPower.zero(),null);
        var view=rates.settledView(viewPlayer,new com.ironoath.core.city.CityState(),START+7_200_000);
        assertThat(view.get(ResourceIds.GRAIN).current()).isEqualTo(sevenFortressRate+eightFortressRate);
        assertThat(viewPlayer.resource(ResourceIds.GRAIN).current()).isZero();
        verify(nations,times(4)).findByAlliance("alliance-a");
    }
}
