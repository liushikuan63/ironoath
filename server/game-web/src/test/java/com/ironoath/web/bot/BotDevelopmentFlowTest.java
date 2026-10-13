package com.ironoath.web.bot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import com.ironoath.common.rng.Rng;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.bot.BotDecisionTree;
import com.ironoath.core.city.*;
import com.ironoath.core.player.*;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.PlayerInitService;

/** Own Bot fixtures exercise the real observation, build, deduction and lazy completion services. */
@SpringBootTest @ActiveProfiles("test")
class BotDevelopmentFlowTest {
    @Autowired BotWorldAdapter adapter;
    @Autowired BotRegistry bots;
    @Autowired BotRuntimeService runtime;
    @Autowired BotRulesAssembler rules;
    @Autowired PlayerInitService init;
    @Autowired CityAppService city;
    @Autowired CityRepository cities;
    @Autowired PlayerRepository players;
    @Autowired com.ironoath.config.ConfigRegistry configs;
    @SpyBean TimeService time;
    long now;
    @BeforeEach void setup(){
        now=System.currentTimeMillis();doReturn(now).when(time).serverNow();
        runtime.reset();bots.clear();adapter.resetCounters();
    }
    String fixture(String archetype,int level,boolean developed){
        String key=UUID.randomUUID().toString();
        String uid=init.init(new PlayerInitReq(key,"bot-dev-"+key,"发展验收",now,"")).playerId();
        city.list(uid);
        var player=players.findByPlayerId(uid).orElseThrow();player.setCityLevel(level);
        for(String id:List.of("WOOD","STONE","IRON","GRAIN")) {
            var resource=player.resource(id);
            player.putResource(id,new PlayerResourceState(10000,10000,resource.protectedAmount(),0,now));
        }
        players.save(player);
        var state=new CityState();state.restoreBuilding(new BuildingInstance("fixture-main","main_city",level,3,3));
        if(developed){state.restoreBuilding(new BuildingInstance("fixture-wood","lumber_camp",1,1,1));state.restoreBuilding(new BuildingInstance("fixture-barracks","barracks",1,2,1));}
        cities.save(uid,state,cities.versionOf(uid));
        var template=rules.archetypes().stream().filter(a->archetype.equals(a.id())).findFirst().orElseThrow();
        bots.register(template.spawnProfile(uid,1,Rng.of(99)));
        return uid;
    }
    CityState state(String uid){return cities.findByPlayerId(uid).orElseThrow();}
    long resources(String uid){var p=players.findByPlayerId(uid).orElseThrow();return List.of("WOOD","STONE","IRON","GRAIN").stream().mapToLong(id->p.resource(id).current()).sum();}
    void develop(String uid){adapter.execute(uid,new BotDecisionTree.Decision(BotDecisionTree.Action.UPGRADE_BUILDING,false,0,"fixture: autonomous development"),now);}
    @ParameterizedTest @CsvSource({"bot_paoyao,lumber_camp","bot_jielue,barracks"})
    void personalityChoosesDifferentRealUpgradeForTheSameCity(String archetype,String target){
        String uid=fixture(archetype,4,true);long before=resources(uid);develop(uid);
        assertThat(state(uid).findByConfigId(target).status()).isEqualTo(BuildingStatus.UPGRADING);
        assertThat(state(uid).findByConfigId("main_city").status()).isEqualTo(BuildingStatus.IDLE);
        assertThat(resources(uid)).isLessThan(before);assertThat(adapter.actionCounts().get("failed")).isZero();
    }
    @Test void farmerObservesThenBuildsMissingResourceBuildingAndCompletesByLazySettlement(){
        String uid=fixture("bot_paoyao",4,false);long before=resources(uid);
        adapter.observe(uid,now);
        long rateBefore=players.findByPlayerId(uid).orElseThrow().resource("WOOD").perHour();
        assertThat(state(uid).buildings()).hasSize(1);assertThat(resources(uid)).isEqualTo(before);
        develop(uid);var building=state(uid).findByConfigId("lumber_camp");
        assertThat(building).isNotNull();assertThat(building.level()).isZero();
        assertThat(building.status()).isEqualTo(BuildingStatus.UPGRADING);
        assertThat(building.upgradeFinishAt()).isGreaterThan(now);assertThat(resources(uid)).isLessThan(before);
        assertThat(state(uid).findByConfigId("main_city").status()).isEqualTo(BuildingStatus.IDLE);
        doReturn(building.upgradeFinishAt()+1).when(time).serverNow();city.list(uid);
        assertThat(state(uid).findByConfigId("lumber_camp").level()).isEqualTo(1);
        assertThat(state(uid).findByConfigId("lumber_camp").status()).isEqualTo(BuildingStatus.IDLE);
        assertThat(players.findByPlayerId(uid).orElseThrow().resource("WOOD").perHour()).isGreaterThan(rateBefore);
    }
    @Test void raiderCannotBuildLockedMilitaryAndFallsBackToLegalCityDevelopment(){
        String uid=fixture("bot_jielue",1,false);develop(uid);
        assertThat(state(uid).findByConfigId("barracks")).isNull();
        assertThat(state(uid).findByConfigId("main_city").status()).isEqualTo(BuildingStatus.UPGRADING);
        assertThat(adapter.actionCounts().get("failed")).isZero();
    }
    @Test void noResourcesCannotPlaceMissingBuildingsOrDeductAnything(){
        String uid=fixture("bot_paoyao",4,false);var player=players.findByPlayerId(uid).orElseThrow();
        for(String id:List.of("WOOD","STONE","IRON","GRAIN")){var r=player.resource(id);player.putResource(id,new PlayerResourceState(0,r.cap(),r.protectedAmount(),0,now));}
        players.save(player);develop(uid);
        assertThat(state(uid).buildings()).hasSize(1);assertThat(resources(uid)).isZero();
        assertThat(adapter.actionCounts().get("upgraded")).isZero();assertThat(adapter.actionCounts().get("failed")).isZero();
    }
    @Test void fullQueuesCannotReserveOrBuildAnotherPlot(){
        String uid=fixture("bot_paoyao",4,true);var state=state(uid);
        for(var b:state.buildings())state.startUpgrade(b.instanceId(),3600,3,now);
        cities.save(uid,state,cities.versionOf(uid));long before=resources(uid);develop(uid);
        assertThat(state(uid).buildings()).hasSize(3);assertThat(resources(uid)).isEqualTo(before);
        assertThat(adapter.actionCounts().get("upgraded")).isZero();assertThat(adapter.actionCounts().get("failed")).isZero();
    }
    @Test void raiderBuildsMissingMilitaryOnceTheSamePlayerGateIsOpen(){
        String uid=fixture("bot_jielue",4,false);long before=resources(uid);develop(uid);
        var building=state(uid).findByConfigId("barracks");
        assertThat(building).isNotNull();assertThat(building.status()).isEqualTo(BuildingStatus.UPGRADING);
        assertThat(building.level()).isZero();assertThat(resources(uid)).isLessThan(before);
        assertThat(state(uid).findByConfigId("main_city").status()).isEqualTo(BuildingStatus.IDLE);
    }
    @Test void neutralPersonalityKeepsTheExistingPlacedBuildingPriority(){
        String uid=fixture("bot_linju",4,true);develop(uid);
        assertThat(state(uid).findByConfigId("main_city").status()).isEqualTo(BuildingStatus.UPGRADING);
        assertThat(state(uid).findByConfigId("lumber_camp").status()).isEqualTo(BuildingStatus.IDLE);
        assertThat(state(uid).findByConfigId("barracks").status()).isEqualTo(BuildingStatus.IDLE);
    }
    /** Dense restored grid fixture: maxed duplicates only occupy capacity, never model normal growth. */
    void fillGrid(String uid,String omitted,boolean leaveInteriorHole){
        var player=players.findByPlayerId(uid).orElseThrow();var policy=city.cityRules(player,now);
        int size=policy.gridSize();var dense=new CityState();
        for(var cfg:configs.all(com.ironoath.config.cfg.BuildingCfg.class)) {
            if(cfg.id().equals(omitted))continue;
            boolean placed=false;
            for(int y=0;y<size&&!placed;y++)for(int x=0;x<size&&!placed;x++) {
                if(!dense.isGridFree(x,y)||(leaveInteriorHole&&x==1&&y==2))continue;
                var probe=CityState.fromSnapshot(dense.snapshot());
                try {probe.place("probe-"+cfg.id(),cfg.id(),x,y,policy,"wall".equals(cfg.id()),cfg.type()==com.ironoath.config.cfg.BuildingCfg.Type.CORE);}
                catch(com.ironoath.common.BizException e){if(e.errorCode()!=com.ironoath.common.ErrorCode.CITY_GRID_INVALID)throw e;continue;}
                dense.restoreBuilding(new BuildingInstance("dense-"+cfg.id(),cfg.id(),(int)cfg.maxLevel(),x,y));placed=true;
            }
            assertThat(placed).as("fixture placement %s",cfg.id()).isTrue();
        }
        var resource=configs.get(com.ironoath.config.cfg.BuildingCfg.class,"lumber_camp");
        for(int y=0;y<size;y++)for(int x=0;x<size;x++)if(dense.isGridFree(x,y)&&!(leaveInteriorHole&&x==1&&y==2))
            dense.restoreBuilding(new BuildingInstance("capacity-"+x+"-"+y,resource.id(),(int)resource.maxLevel(),x,y));
        cities.save(uid,dense,cities.versionOf(uid));
    }
    @Test void fullGridWithNoLegalUpgradeQuietlySkipsMissingMilitary(){
        String uid=fixture("bot_jielue",4,false);fillGrid(uid,"barracks",false);long before=resources(uid);develop(uid);
        assertThat(state(uid).findByConfigId("barracks")).isNull();assertThat(resources(uid)).isEqualTo(before);
        assertThat(adapter.actionCounts().get("upgraded")).isZero();assertThat(adapter.actionCounts().get("failed")).isZero();
    }
    @Test void wallCannotUseTheOnlyRemainingInteriorPlot(){
        String uid=fixture("bot_paoyao",4,false);fillGrid(uid,"wall",true);
        assertThat(city.botCityTickFacts(uid,now,com.ironoath.config.cfg.BuildingCfg.Type.DEFENSE).nextUpgradable()).isNull();
        develop(uid);assertThat(state(uid).findByConfigId("wall")).isNull();assertThat(state(uid).isGridFree(1,2)).isTrue();
        assertThat(adapter.actionCounts().get("upgraded")).isZero();assertThat(adapter.actionCounts().get("failed")).isZero();
    }
}
