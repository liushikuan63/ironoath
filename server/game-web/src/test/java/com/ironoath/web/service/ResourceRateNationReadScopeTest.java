package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.city.CityState;
import com.ironoath.core.player.PlayerTech;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.resource.ResourceOutputCalculator;
import com.ironoath.core.nation.Nation;
import com.ironoath.config.cfg.NationPolicyCfg;
import com.ironoath.config.cfg.NationTechCfg;
import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.nation.NationLeaders;
import com.ironoath.web.nation.NationPolicyBonuses;
import com.ironoath.web.nation.NationTechBonuses;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.social.SocialStore;
import com.ironoath.core.social.Alliance;
import com.ironoath.web.battle.BattleArmyFactory;
import com.ironoath.web.tech.TechEffects;

/** Actual output producers; counted mock ports distinguish one compute from global caching. */
class ResourceRateNationReadScopeTest {
    final ConfigRegistry configs=spy(ConfigRegistry.loadFromDirectory(Path.of("contract/config")));
    final SocialStore social=mock(SocialStore.class);
    final NationStore nations=mock(NationStore.class);
    final NationLeaders leaders=mock(NationLeaders.class);
    final NationTechBonuses national=new NationTechBonuses(configs,social,nations,leaders);
    final NationPolicyBonuses policy=new NationPolicyBonuses(configs,social,nations,leaders,mock(BattleArmyFactory.class));
    final ResourceRateService service=new ResourceRateService(configs,new TechEffects(configs),national,policy);

    ResourceRateService.Rates compute(String player){
        assertThat(configs.allResources()).anyMatch(r->ResourceIds.GRAIN.equals(r.id()));
        return service.compute(new CityState(),PlayerTech.empty(),player);
    }
    void baseline(ResourceRateService.Rates rates){
        for(var resource:configs.allResources())assertThat(rates.perHour(resource.id())).isEqualTo(resource.basePerHour());
    }
    @Test void noAllianceIsOneSuccessfulAbsenceWithinTheCalculation(){
        baseline(compute("player-a"));
        verify(social).allianceOf("player-a");
        verifyNoInteractions(nations,leaders);
    }
    @Test void aMemberWithNoNationDoesNotReadTheSameRelationshipTwice(){
        Alliance alliance=mock(Alliance.class);when(alliance.id()).thenReturn("alliance-a");
        when(social.allianceOf("player-a")).thenReturn(Optional.of(alliance));
        baseline(compute("player-a"));
        verify(social).allianceOf("player-a");
        verify(nations).findByAlliance("alliance-a");
        verifyNoInteractions(leaders);
    }
    @Test void nextCalculationAndAnotherPlayerReadTheirOwnFreshAbsence(){
        baseline(compute("player-a"));baseline(compute("player-a"));baseline(compute("player-b"));
        verify(social,times(2)).allianceOf("player-a");verify(social).allianceOf("player-b");
    }
    @Test void aFailedLookupIsNotCachedAndTheNextCalculationStartsAgain(){
        when(social.allianceOf("player-a")).thenThrow(new IllegalStateException("owned lookup fault")).thenReturn(Optional.empty());
        baseline(compute("player-a"));
        verify(social,times(2)).allianceOf("player-a");
        baseline(compute("player-a"));
        verify(social,times(3)).allianceOf("player-a");
    }
    @Test void noResourcesOrEarlierFailureAddsNoLookup(){
        doReturn(List.of()).when(configs).allResources();
        assertThat(service.compute(new CityState(),PlayerTech.empty(),"player-a").breakdowns()).isEmpty();
        doThrow(new IllegalStateException("curve unavailable")).when(configs).curve("BUILDING_OUTPUT");
        assertThatThrownBy(()->service.compute(new CityState(),PlayerTech.empty(),"player-a")).isInstanceOf(IllegalStateException.class).hasMessage("curve unavailable");
        verifyNoInteractions(social,nations,leaders);
    }
    @Test void standaloneOutputCallsRemainIndependentFreshReads(){
        assertThat(national.outputPercent(ResourceIds.GRAIN,"player-a")).isZero();
        assertThat(policy.outputPercent("player-a")).isZero();
        assertThat(national.outputPercent(ResourceIds.GRAIN,"player-a")).isZero();
        verify(social,times(3)).allianceOf("player-a");
    }

    Nation positive(String id,String alliance,int level,long expires){
        var rules=new NationRulesAssembler(configs).rules();
        Nation initial=Nation.found(id,"产率快照国","king-"+id,alliance,10,20,System.currentTimeMillis(),rules);
        ObjectMapper mapper=new ObjectMapper();
        Map<String,Object> fields=mapper.convertValue(initial.snapshot(),new TypeReference<Map<String,Object>>(){});
        fields.put("techLevels",Map.of(grainRow().id(),level));
        fields.put("activePolicies",Map.of(policyRow().id(),expires));
        return Nation.fromSnapshot(mapper.convertValue(fields,Nation.Snapshot.class),rules);
    }
    Nation copy(Nation nation){return Nation.fromSnapshot(nation.snapshot(),new NationRulesAssembler(configs).rules());}
    NationTechCfg grainRow(){return configs.all(NationTechCfg.class).stream().filter(r->r.effectAttr()==NationTechCfg.EffectAttr.GRAIN_OUTPUT).findFirst().orElseThrow();}
    NationPolicyCfg policyRow(){return configs.all(NationPolicyCfg.class).stream().filter(r->r.effectAttr()==NationPolicyCfg.EffectAttr.OUTPUT).findFirst().orElseThrow();}
    void affiliated(String player,String alliance,AtomicReference<Nation> current){
        Alliance member=mock(Alliance.class);when(member.id()).thenReturn(alliance);
        when(social.allianceOf(player)).thenReturn(Optional.of(member));
        when(nations.findByAlliance(alliance)).thenAnswer(call->Optional.of(copy(current.get())));
        when(leaders.bind(any())).thenAnswer(call->call.getArgument(0));
    }
    void bonuses(ResourceRateService.Rates rates,long techPercent,long policyPercent){
        for(var resource:configs.allResources()){
            var base=resource.basePerHour()==0?List.<ResourceOutputCalculator.Line>of():List.of(ResourceOutputCalculator.Line.flat("领地基础产出",resource.basePerHour()));
            long own=ResourceIds.GRAIN.equals(resource.id())?techPercent:0;
            assertThat(rates.breakdowns().get(resource.id())).isEqualTo(ResourceOutputCalculator.compute(
                    base,own+policyPercent,0,0,"科技、国家、国策与要塞加成"));
        }
    }
    @Test void realNationTechAndPolicyKeepTheirValuesWithOneLookup(){
        var current=new AtomicReference<>(positive("nation-a","alliance-a",2,Long.MAX_VALUE));
        affiliated("player-a","alliance-a",current);
        assertThat(grainRow().effectValue()).isPositive();assertThat(policyRow().effectValue()).isPositive();
        bonuses(compute("player-a"),grainRow().effectValue()*2,policyRow().effectValue());
        verify(social).allianceOf("player-a");verify(nations).findByAlliance("alliance-a");verify(leaders).bind(any());
        verifyNoMoreInteractions(nations);
    }
    @Test void nextComputeAndAnotherPlayerSeeChangedNationSnapshots(){
        var a=new AtomicReference<>(positive("nation-a","alliance-a",2,Long.MAX_VALUE));
        var b=new AtomicReference<>(positive("nation-b","alliance-b",1,Long.MAX_VALUE));
        affiliated("player-a","alliance-a",a);affiliated("player-b","alliance-b",b);
        bonuses(compute("player-a"),grainRow().effectValue()*2,policyRow().effectValue());
        a.set(positive("nation-a","alliance-a",3,System.currentTimeMillis()-1));
        bonuses(compute("player-a"),grainRow().effectValue()*3,0);
        bonuses(compute("player-b"),grainRow().effectValue(),policyRow().effectValue());
        verify(social,times(2)).allianceOf("player-a");verify(social).allianceOf("player-b");
        verify(nations,times(2)).findByAlliance("alliance-a");verify(nations).findByAlliance("alliance-b");
    }
    @Test void failedNationReadCanRecoverForTheOtherProducerWithoutChangingFallbackOrder(){
        var current=new AtomicReference<>(positive("nation-a","alliance-a",2,Long.MAX_VALUE));
        affiliated("player-a","alliance-a",current);
        doThrow(new IllegalStateException("owned nation lookup fault")).doAnswer(call->Optional.of(copy(current.get()))).when(nations).findByAlliance("alliance-a");
        boolean grainFirst=ResourceIds.GRAIN.equals(configs.allResources().get(0).id());
        bonuses(compute("player-a"),grainFirst?0:grainRow().effectValue()*2,grainFirst?policyRow().effectValue():0);
        verify(nations,times(2)).findByAlliance("alliance-a");
        bonuses(compute("player-a"),grainRow().effectValue()*2,policyRow().effectValue());
        verify(nations,times(3)).findByAlliance("alliance-a");verify(social,times(3)).allianceOf("player-a");
    }
    @Test void expiredPolicySettlesLocallyWithoutSavingOrChangingTech(){
        var current=new AtomicReference<>(positive("nation-a","alliance-a",2,System.currentTimeMillis()-1));
        affiliated("player-a","alliance-a",current);
        bonuses(compute("player-a"),grainRow().effectValue()*2,0);
        verify(nations).findByAlliance("alliance-a");verifyNoMoreInteractions(nations);
        assertThat(current.get().snapshot().activePolicies()).containsKey(policyRow().id());
    }
    @Test void aMembershipChangeDuringTheCalculationIsReadOnTheNextCalculation(){
        var current=new AtomicReference<>(positive("nation-a","alliance-a",2,Long.MAX_VALUE));
        affiliated("player-a","alliance-a",current);
        Alliance member=mock(Alliance.class);when(member.id()).thenReturn("alliance-a");
        doReturn(Optional.empty(),Optional.of(member)).when(social).allianceOf("player-a");
        baseline(compute("player-a"));
        verify(social).allianceOf("player-a");
        bonuses(compute("player-a"),grainRow().effectValue()*2,policyRow().effectValue());
        verify(social,times(2)).allianceOf("player-a");
    }
    @Test void standalonePositiveCallsStillReadSubsequentChanges(){
        var current=new AtomicReference<>(positive("nation-a","alliance-a",2,Long.MAX_VALUE));
        affiliated("player-a","alliance-a",current);
        assertThat(national.outputPercent(ResourceIds.GRAIN,"player-a")).isEqualTo(grainRow().effectValue()*2);
        current.set(positive("nation-a","alliance-a",3,System.currentTimeMillis()-1));
        assertThat(policy.outputPercent("player-a")).isZero();
        assertThat(national.outputPercent(ResourceIds.GRAIN,"player-a")).isEqualTo(grainRow().effectValue()*3);
        verify(social,times(3)).allianceOf("player-a");verify(nations,times(3)).findByAlliance("alliance-a");
    }
}
