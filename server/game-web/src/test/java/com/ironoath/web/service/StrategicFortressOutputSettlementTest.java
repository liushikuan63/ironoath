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
import com.ironoath.web.store.memory.InMemoryNationStore;
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
        when(social.allianceMembershipEpochs("member-a","alliance-a",START+7_200_000)).thenReturn(List.of(
                new com.ironoath.web.social.SocialStore.AllianceMembershipEpoch("alliance-a",START,Long.MAX_VALUE)));
        var nation=Nation.found("nation-a","产出国","king-a","alliance-a",10,20,START,
                new NationRulesAssembler(configs).rules());
        when(nations.findByAlliance("alliance-a")).thenReturn(Optional.of(nation));
        when(nations.allianceNationEpochs("alliance-a")).thenReturn(List.of(
                new NationStore.AllianceNationEpoch("nation-a",START,Long.MAX_VALUE)));
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

    @Test void playerAllianceMembershipChangesSplitFortressEligibilityAtExactTimes() {
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var social=mock(com.ironoath.web.social.SocialStore.class);var nations=mock(NationStore.class);
        var leaders=mock(NationLeaders.class);var currentAlliance=mock(Alliance.class);
        when(currentAlliance.id()).thenReturn("alliance-b");
        when(social.allianceOf("member-changed")).thenReturn(Optional.of(currentAlliance));
        when(social.allianceMembershipEpochs("member-changed","alliance-b",START+7_200_000)).thenReturn(List.of(
                new com.ironoath.web.social.SocialStore.AllianceMembershipEpoch("alliance-a",START,START+3_600_000),
                new com.ironoath.web.social.SocialStore.AllianceMembershipEpoch("alliance-b",START+3_600_000,Long.MAX_VALUE)));
        var rules=new NationRulesAssembler(configs);
        var oldNation=Nation.found("nation-a","旧国","old-king","alliance-a",10,20,START,rules.rules());
        when(nations.allianceNationEpochs("alliance-a")).thenReturn(List.of(
                new NationStore.AllianceNationEpoch("nation-a",START,Long.MAX_VALUE)));
        when(nations.allianceNationEpochs("alliance-b")).thenReturn(List.of());
        when(nations.findByAlliance("alliance-b")).thenReturn(Optional.empty());
        when(leaders.bind(any())).thenAnswer(call->call.getArgument(0));
        var occupations=new InMemoryTargetOccupationStore();
        var forts=WorldTargets.from(configs).targets().stream()
                .filter(target->target.definition().kind()==com.ironoath.config.cfg.WorldTargetCfg.Kind.FORTRESS)
                .toList();
        for(var target:forts)assertThat(occupations.compareAndSet(new TargetOccupationStore.Snapshot(
                target.definition().id(),WorldGenerator.EntityType.FORTRESS,target.coord().x(),target.coord().y(),
                "nation-a","旧国",START,"capture-"+target.definition().id(),1,TargetOccupationStore.Garrison.empty()),0)).isTrue();
        var national=new NationTechBonuses(configs,social,nations,leaders);
        var policy=new NationPolicyBonuses(configs,social,nations,leaders,mock(com.ironoath.web.battle.BattleArmyFactory.class));
        var rates=new ResourceRateService(configs,new TechEffects(configs),national,policy,occupations);
        var player=PlayerSave.createNew("member-changed","device-change","变更成员",1,START,1,
                Map.of(ResourceIds.GRAIN,new PlayerResourceState(0,1_000_000,0,0,START)),PlayerPower.zero(),null);

        var result=rates.settle(player,new com.ironoath.core.city.CityState(),START+7_200_000);

        long base=configs.getResource(ResourceIds.GRAIN).basePerHour();
        long oldNationRate=base+(base*1_600/10_000);
        assertThat(result.credited().get(ResourceIds.GRAIN)).isEqualTo(oldNationRate+base);
        var viewPlayer=PlayerSave.createNew("member-changed","device-change-view","变更成员",1,START,1,
                Map.of(ResourceIds.GRAIN,new PlayerResourceState(0,1_000_000,0,0,START)),PlayerPower.zero(),null);
        assertThat(rates.settledView(viewPlayer,new com.ironoath.core.city.CityState(),START+7_200_000)
                .get(ResourceIds.GRAIN).current()).isEqualTo(oldNationRate+base);
    }

    @Test void nationMembershipTransferSplitsFortressEligibilityAtTheJoinExitTimestamp() {
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        var social=mock(com.ironoath.web.social.SocialStore.class);var leaders=mock(NationLeaders.class);
        var alliance=mock(Alliance.class);when(alliance.id()).thenReturn("alliance-a");
        when(social.allianceOf("member-transfer")).thenReturn(Optional.of(alliance));
        when(social.allianceMembershipEpochs("member-transfer","alliance-a",START+7_200_000)).thenReturn(List.of(
                new com.ironoath.web.social.SocialStore.AllianceMembershipEpoch("alliance-a",START,Long.MAX_VALUE)));
        var rules=new NationRulesAssembler(configs);var nations=new InMemoryNationStore(rules);
        Nation oldNation=Nation.found("nation-old","旧国","old-king","alliance-a",10,20,START-100,rules.rules());
        assertThat(nations.insertIfAbsent(oldNation)).isTrue();
        oldNation=nations.findById(oldNation.id()).orElseThrow();
        oldNation.removeAlliance("alliance-a",false,"old-king",START+3_600_000);
        nations.save(oldNation,oldNation.version());
        Nation newNation=Nation.found("nation-new","新国","new-king","alliance-b",30,40,START-100,rules.rules());
        assertThat(nations.insertIfAbsent(newNation)).isTrue();
        newNation=nations.findById(newNation.id()).orElseThrow();
        newNation.admitAlliance("alliance-a",START+3_600_000);nations.save(newNation,newNation.version());
        when(leaders.bind(any())).thenAnswer(call->call.getArgument(0));
        var occupations=new InMemoryTargetOccupationStore();
        var forts=WorldTargets.from(configs).targets().stream()
                .filter(target->target.definition().kind()==com.ironoath.config.cfg.WorldTargetCfg.Kind.FORTRESS)
                .toList();
        for(var target:forts)assertThat(occupations.compareAndSet(new TargetOccupationStore.Snapshot(
                target.definition().id(),WorldGenerator.EntityType.valueOf(target.definition().kind().name()),
                target.coord().x(),target.coord().y(),"nation-old","旧国",START-200,"capture-"+target.definition().id(),
                1,TargetOccupationStore.Garrison.empty()),0)).isTrue();
        var national=new NationTechBonuses(configs,social,nations,leaders);
        var policy=new NationPolicyBonuses(configs,social,nations,leaders,mock(com.ironoath.web.battle.BattleArmyFactory.class));
        var rates=new ResourceRateService(configs,new TechEffects(configs),national,policy,occupations);
        var player=PlayerSave.createNew("member-transfer","device-transfer","转籍成员",1,START,1,
                Map.of(ResourceIds.GRAIN,new PlayerResourceState(0,1_000_000,0,0,START)),PlayerPower.zero(),null);

        var result=rates.settle(player,new com.ironoath.core.city.CityState(),START+7_200_000);

        long base=configs.getResource(ResourceIds.GRAIN).basePerHour();
        long oldNationRate=com.ironoath.core.resource.ResourceOutputCalculator.compute(
                List.of(com.ironoath.core.resource.ResourceOutputCalculator.Line.flat("base",base)),1_600,0,0,
                "combined").totalPerHour();
        assertThat(result.credited().get(ResourceIds.GRAIN)).isEqualTo(oldNationRate+base);
        assertThat(result.rates().perHour(ResourceIds.GRAIN)).isEqualTo(base);
        assertThat(nations.nationAtAlliance("alliance-a",START+3_600_000,true)).map(Nation::id).contains("nation-old");
        assertThat(nations.nationAtAlliance("alliance-a",START+3_600_000,false)).map(Nation::id).contains("nation-new");
        var viewPlayer=PlayerSave.createNew("member-transfer","device-transfer-view","转籍成员",1,START,1,
                Map.of(ResourceIds.GRAIN,new PlayerResourceState(0,1_000_000,0,0,START)),PlayerPower.zero(),null);
        assertThat(rates.settledView(viewPlayer,new com.ironoath.core.city.CityState(),START+7_200_000)
                .get(ResourceIds.GRAIN).current()).isEqualTo(oldNationRate+base);
    }
}
