package com.ironoath.web.battle;

import com.ironoath.battle.*;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerTech;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.hero.*;
import com.ironoath.core.city.*;
import com.ironoath.core.march.March;
import com.ironoath.core.social.Rally;
import com.ironoath.core.social.Alliance;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.world.Coord;
import com.ironoath.config.cfg.*;
import com.ironoath.config.model.GlobalCfg;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.web.hero.EquipLedgers;
import com.ironoath.web.world.StrategicTargetDefender;
import com.ironoath.web.store.memory.InMemoryTargetOccupationStore;
import com.ironoath.web.store.memory.InMemoryNeutralTargetGarrisonStore;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.service.HeroStatsService;
import com.ironoath.web.tech.TechEffects;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.nation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际kernel与显式类型夹具；不裁定B13生产类型、不授予目标写入权。 */
class CapturedBattlePreparationTest {
    private final ConfigRegistry configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    private final SocialStore social=mock(SocialStore.class);
    private final PlayerRepository players=mock(PlayerRepository.class);
    private final HeroRepository heroRepository=mock(HeroRepository.class);
    private final InventoryRepository inventories=mock(InventoryRepository.class);
    private final NationStore nations=mock(NationStore.class);
    private final NationLeaders leaders=mock(NationLeaders.class);
    private CapturedBattlePreparation preparation() {
        return preparation(nations,leaders);
    }
    private CapturedBattlePreparation preparation(NationStore nationStore,NationLeaders nationLeaders) {
        var factory=new BattleArmyFactory(configs);
        var heroes=new HeroBattleMapper(configs,new HeroStatsService(configs),new EquipLedgers(inventories,configs),heroRepository,social);
        var tech=new BattleTechBonuses(new AllianceTechBonuses(social,configs),new TechEffects(configs),players);
        var policy=new NationPolicyBonuses(configs,social,nationStore,nationLeaders,factory);
        return new CapturedBattlePreparation(configs,heroes,tech,policy);
    }
    @Test void oneCapturedScopeBuildsOriginalInputAndExecutesActualKernel() {
        var scope=preparation().capture(100);
        var attack=scope.neutralSide("owned-a",Map.of("unit_infantry_t4",200L));
        var defense=scope.neutralSide("owned-d",Map.of("unit_cavalry_t4",100L));
        var battle=scope.simulate(attack,defense,TerrainType.PLAIN,2379,BattleType.PVE,BattleModifier.none(),BattleModifier.none());
        assertThat(battle.input().attacker().units()).containsEntry(UnitType.INFANTRY,200L);
        assertThat(BattleSimulator.simulate(battle.input())).isEqualTo(battle.result());
        assertThatThrownBy(()->battle.input().rules().counterMatrix().get(UnitType.INFANTRY).clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void sameVersionFingerprintDoesNotPermitMixingDifferentCapturedScopes() {
        var preparation=preparation();var one=preparation.capture(100);var two=preparation.capture(100);
        assertThat(one.fingerprint()).isEqualTo(two.fingerprint());
        var a=one.neutralSide("owned-a",Map.of("unit_infantry_t4",100L));
        var d=two.neutralSide("owned-d",Map.of("unit_cavalry_t4",100L));
        assertThatThrownBy(()->one.simulate(a,d,TerrainType.PLAIN,1,BattleType.PVE,BattleModifier.none(),BattleModifier.none()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("同一");
    }
    @Test void emptyDefenderHasNoFakeNpcAndNoImplicitBattleType() {
        var scope=preparation().capture(100);
        assertThatThrownBy(()->scope.neutralSide("owned-empty",Map.of())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("无抵抗");
        var a=scope.neutralSide("owned-a",Map.of("unit_infantry_t4",100L));
        var d=scope.neutralSide("owned-d",Map.of("unit_cavalry_t4",100L));
        assertThatThrownBy(()->scope.simulate(a,d,TerrainType.PLAIN,1,null,BattleModifier.none(),BattleModifier.none())).isInstanceOf(IllegalArgumentException.class);
    }

    private void reloadRow(String table,Class<?> type,String id,Consumer<ObjectNode> edit)throws Exception {
        Path root=Path.of("").toAbsolutePath();while(root!=null && !Files.isDirectory(root.resolve("contract/config")))root=root.getParent();
        assertThat(root).isNotNull();var mapper=new ObjectMapper();var json=(ObjectNode)mapper.readTree(Files.readString(root.resolve("contract/config/"+table+".json")));
        json.set("rows",mapper.valueToTree(configs.rawTable(table).rows()));boolean found=false;
        for(var row:json.get("rows"))if(row.get("id").asText().equals(id)){edit.accept((ObjectNode)row);found=true;}
        assertThat(found).as("actual row %s/%s",table,id).isTrue();configs.reload(table,type,json.toString());
    }
    private Rally rally(String id,List<String> heroes) {
        var units=Map.of("unit_infantry_t4",100L);
        var r=Rally.restore(id,Rally.Scope.NATION,"owned-N","owned-leader",2,1,100,110,
                Map.of("owned-leader",new Rally.Participant("owned-leader",units,heroes)),
                Rally.Status.PREPARING,null,256,216,"STRATEGIC_TARGET",1);
        r.depart(110);when(social.rallyOf(id)).thenReturn(Optional.of(r));return r;
    }
    private March march(String id,List<String> heroes) {
        var m=new March("march-"+id,"owned-leader",Coord.of(10,10),Coord.of(256,216),110,120,
                Map.of("unit_infantry_t4",100L),heroes,1000,1,March.TargetType.STRATEGIC_TARGET,"gate_north",March.Action.ATTACK);
        m.attachToRally(id);m.arrive(120);return m;
    }
    @ParameterizedTest @ValueSource(strings={"ratio","slots","minimum"})
    void expiredRealNationVoteKeepsCapturedRulesAfterParentReload(String change)throws Exception {
        long day=24*3600_000L,at=2*day+2;
        var store=new com.ironoath.web.store.memory.InMemoryNationStore(new NationRulesAssembler(configs));
        var nation=Nation.found("owned-N","原国策夹具","owned-leader","owned-alliance",10,10,0,new NationRulesAssembler(configs).rules());
        nation.propose("owned-p1","np_cavalry_t1","owned-leader",true,1);
        nation.propose("owned-p2","np_fortress","owned-leader",true,2);
        nation.settlePolicy(day+1);
        for(String proposal:List.of("owned-p1","owned-p2")) {
            nation.vote(proposal,"owned-v1",true,day+2);nation.vote(proposal,"owned-v2",false,day+2);
        }
        assertThat(store.insertIfAbsent(nation)).isTrue();var persisted=store.findById("owned-N").orElseThrow().snapshot();
        var alliance=mock(Alliance.class);when(alliance.id()).thenReturn("owned-alliance");
        when(social.allianceOf("owned-leader")).thenReturn(Optional.of(alliance));
        var prep=preparation(store,new NationLeaders(social,mock(com.ironoath.web.bot.BotRegistry.class)));
        var party=rally("owned-pending-policy",List.of());var march=march("owned-pending-policy",List.of());
        var old=prep.capture(at);var before=old.playerSide(march,0,PlayerTech.empty(),party).army().orgBonus();
        assertThat(before.policyAttackFor(UnitType.CAVALRY)).isPositive();assertThat(before.policyDefense()).isZero();
        switch(change) {
            case "ratio" -> reloadRow("global",GlobalCfg.class,"NATION_POLICY_PASS_RATIO",r->r.put("value","0.75"));
            case "slots" -> reloadRow("nation_config",NationConfigCfg.class,"nation_lv1",r->r.put("policySlotCount",2));
            case "minimum" -> reloadRow("global",GlobalCfg.class,"NATION_POLICY_MIN_VOTERS_PER_ALLIANCE",r->r.put("value",3));
        }
        assertThat(old.playerSide(march,0,PlayerTech.empty(),party).army().orgBonus()).isEqualTo(before);
        var fresh=prep.capture(at).playerSide(march,0,PlayerTech.empty(),party).army().orgBonus();
        if(change.equals("slots"))assertThat(fresh.policyDefense()).isPositive();else assertThat(fresh.policyAttackFor(UnitType.CAVALRY)).isZero();
        assertThat(store.findById("owned-N").orElseThrow().snapshot()).isEqualTo(persisted);
    }
    @ParameterizedTest @CsvSource({"false,id","true,id","false,coord","true,coord","false,same","true,same"})
    void bothSimulationEntriesBindBothRealMarchTargets(boolean strategic,String mismatch) {
        var scope=preparation().capture(120);var attackerRally=rally("owned-bound-atk",List.of());
        var attack=scope.playerSide(march("owned-bound-atk",List.of()),0,PlayerTech.empty(),attackerRally);
        int x=mismatch.equals("coord")?255:256;
        var defenderRally=Rally.restore("owned-bound-def",Rally.Scope.NATION,"owned-N","owned-leader",2,1,100,110,
                Map.of("owned-leader",new Rally.Participant("owned-leader",Map.of("unit_infantry_t4",100L),List.of())),
                Rally.Status.PREPARING,null,x,216,"STRATEGIC_TARGET",1);
        defenderRally.depart(110);
        var stationed=new March("owned-bound-def-march","owned-leader",Coord.of(10,10),Coord.of(x,216),110,120,
                Map.of("unit_infantry_t4",100L),List.of(),1000,1,March.TargetType.STRATEGIC_TARGET,
                mismatch.equals("id")?"gate_south":"gate_north",March.Action.ATTACK);
        stationed.attachToRally(defenderRally.rallyId());stationed.arrive(120);stationed.holdStrategicVictory(Map.of("owned-leader",stationed.units()));
        var defense=scope.playerSide(stationed,0,PlayerTech.empty(),defenderRally);
        java.util.function.Supplier<CapturedBattlePreparation.Simulation> simulate=()->strategic
                ?scope.simulateStrategic(attack,defense,TerrainType.PLAIN,2379,BattleModifier.none(),BattleModifier.none())
                :scope.simulate(attack,defense,TerrainType.PLAIN,2379,BattleType.SIEGE,BattleModifier.none(),BattleModifier.none());
        if(!mismatch.equals("same"))assertThatThrownBy(simulate::get).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("目标");
        else assertThat(BattleSimulator.simulate(simulate.get().input())).isEqualTo(simulate.get().result());
    }
    @Test void capturedGraphKeepsHeroSkillGearTechPolicyRallyAndHospitalOnOriginalTables()throws Exception {
        String heroId="hero_ssr_02";var instance=new HeroInstance(heroId,10,0,1,0,1,1);
        instance.equip(EquipSlot.WEAPON,"eq_iron_sword");var roster=new HeroRoster();roster.restoreHero(instance);
        when(heroRepository.findByPlayerId("owned-leader")).thenReturn(Optional.of(roster));
        var alliance=mock(Alliance.class);when(alliance.id()).thenReturn("owned-alliance");when(alliance.techLevel(anyString())).thenReturn(1);
        when(social.allianceOf(anyString())).thenReturn(Optional.of(alliance));
        var originalNation=Nation.found("owned-N","原图国家","owned-leader","owned-alliance",10,10,0,new NationRulesAssembler(configs).rules());
        var mapper=new ObjectMapper();var snapshot=mapper.valueToTree(originalNation.snapshot());
        ((ObjectNode)snapshot).set("activePolicies",mapper.valueToTree(Map.of("np_cavalry_t1",1000L)));
        var nation=Nation.fromSnapshot(mapper.treeToValue(snapshot,Nation.Snapshot.class),new NationRulesAssembler(configs).rules());
        when(nations.findByAlliance("owned-alliance")).thenReturn(Optional.of(nation));when(leaders.bind(any(Nation.class))).thenAnswer(inv->inv.getArgument(0));
        var party=rally("owned-rich",List.of(heroId));var source=march("owned-rich",List.of(heroId));
        var tech=new PlayerTech(Map.of("tech_mil_atk",1,"tech_mil_hosp",1),null,null,0,0);
        var save=mock(PlayerSave.class);when(save.tech()).thenReturn(tech);var city=mock(CityState.class);var hospital=mock(BuildingInstance.class);
        when(hospital.configId()).thenReturn("hospital");when(hospital.level()).thenReturn(1);when(city.buildings()).thenReturn(List.of(hospital));
        var prep=preparation();var old=prep.capture(200);var before=old.playerSide(source,0,tech,party);
        var defense=old.neutralSide("owned-defender",Map.of("unit_cavalry_t4",80L));
        var original=old.simulateStrategic(before,defense,TerrainType.PLAIN,42,BattleModifier.none(),BattleModifier.none());
        long capacity=old.hospitalCapacity(city,save);assertThat(capacity).isPositive();
        assertThat(before.army().heroes()).hasSize(1);assertThat(before.army().techBonus().attackFixed()).isPositive();
        assertThat(before.army().orgBonus().policyAttackByUnit().get(UnitType.CAVALRY)).isPositive();
        reloadRow("unit",UnitCfg.class,"unit_infantry_t4",r->r.put("type","CAVALRY"));
        reloadRow("hero",HeroCfg.class,heroId,r->r.put("might",500));
        reloadRow("equip",EquipCfg.class,"eq_iron_sword",r->r.put("might",500));
        reloadRow("skill",SkillCfg.class,"skill_ssr_wang_yan",r->r.put("value","0.75"));
        reloadRow("tech",TechCfg.class,"tech_mil_atk",r->r.put("effectValue","0.30"));
        reloadRow("tech",TechCfg.class,"tech_mil_hosp",r->r.put("effectValue","0.40"));
        reloadRow("alliance_tech",AllianceTechCfg.class,"atech_atk",r->r.put("effectValue","0.15"));
        reloadRow("nation_policy",NationPolicyCfg.class,"np_cavalry_t1",r->r.put("effectValue","0.45"));
        reloadRow("building",BuildingCfg.class,"hospital",r->r.put("woundedCapBase",800));
        reloadRow("global",GlobalCfg.class,"RALLY_ATTACK_BONUS_FIXED",r->r.put("value","0.25"));
        reloadRow("global",GlobalCfg.class,"BATTLE_MAX_ROUNDS",r->r.put("value",1));
        var again=old.playerSide(source,0,tech,party);assertThat(again.army()).isEqualTo(before.army());assertThat(old.hospitalCapacity(city,save)).isEqualTo(capacity);
        assertThat(old.simulateStrategic(again,defense,TerrainType.PLAIN,42,BattleModifier.none(),BattleModifier.none()).result()).isEqualTo(original.result());
        var current=prep.capture(200);var changed=current.playerSide(source,0,tech,party);
        assertThat(changed.army().units()).containsEntry(UnitType.CAVALRY,100L);
        assertThat(changed.army().heroes().get(0).heroBonusFixed()).isNotEqualTo(before.army().heroes().get(0).heroBonusFixed());
        assertThat(changed.army().heroes().get(0).skills()).isNotEqualTo(before.army().heroes().get(0).skills());
        assertThat(changed.army().techBonus()).isNotEqualTo(before.army().techBonus());
        assertThat(changed.army().orgBonus()).isNotEqualTo(before.army().orgBonus());assertThat(current.hospitalCapacity(city,save)).isGreaterThan(capacity);
        var newResult=current.simulateStrategic(changed,current.neutralSide("owned-defender",Map.of("unit_cavalry_t4",80L)),TerrainType.PLAIN,42,BattleModifier.none(),BattleModifier.none());
        assertThat(newResult.input().rules().maxRounds()).isEqualTo(1);assertThat(original.input().rules().maxRounds()).isEqualTo(8);
        assertThat(nation.snapshot().activePolicies()).containsEntry("np_cavalry_t1",1000L);verifyNoInteractions(players);
    }
    @Test void policyLookupFailureAbortsCapturedPreparationButLegacyFallbackIsPreserved() {
        var alliance=mock(Alliance.class);when(alliance.id()).thenReturn("owned-alliance");when(social.allianceOf(anyString())).thenReturn(Optional.of(alliance));
        var failure=new IllegalStateException("owned nation lookup unavailable");when(nations.findByAlliance("owned-alliance")).thenThrow(failure);
        var party=rally("owned-error",List.of());var source=march("owned-error",List.of());var before=source.snapshot();
        assertThatThrownBy(()->preparation().capture(200).playerSide(source,0,PlayerTech.empty(),party)).isSameAs(failure);
        assertThat(source.snapshot()).isEqualTo(before);
        var legacy=new NationPolicyBonuses(configs,social,nations,leaders,new BattleArmyFactory(configs));
        assertThat(legacy.combatBonusFor("owned-leader")).isEqualTo(OrgBonus.none());
    }
    @Test void realSimulationFreezesCasualtiesFromOriginalMarchAndRejectsForeignScope()throws Exception {
        var party=rally("owned-casualties",List.of());var source=march("owned-casualties",List.of());
        var prep=preparation();var scope=prep.capture(120);var attack=scope.playerSide(source,7,PlayerTech.empty(),party);
        source.applyLosses(Map.of("unit_infantry_t4",1L));
        var npc=scope.neutralSide("owned-npc",Map.of("unit_cavalry_t4",10000L));
        assertThatThrownBy(()->scope.simulate(attack,npc,TerrainType.PLAIN,17,BattleType.PVE,BattleModifier.none(),BattleModifier.none())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SIEGE");
        assertThatThrownBy(()->scope.simulateStrategic(npc,attack,TerrainType.PLAIN,17,BattleModifier.none(),BattleModifier.none())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("攻守角色");
        var battle=scope.simulateStrategic(attack,npc,TerrainType.PLAIN,17,BattleModifier.none(),BattleModifier.none());
        assertThat(battle.input().attacker().totalUnits()).isEqualTo(100);assertThat(battle.input().attacker().hospitalCapacity()).isEqualTo(Long.MAX_VALUE);
        assertThat(battle.result().atkOverflowDead()).isZero();assertThat(battle.result().atkWounded()).isPositive();
        reloadRow("unit",UnitCfg.class,"unit_infantry_t4",r->r.put("type","CAVALRY"));
        party.arrive();
        var save=mock(PlayerSave.class);var hospitals=scope.prepareHospitals(Map.of("owned-leader",new CapturedBattlePreparation.MemberHospital(mock(CityState.class),save)));
        var frozen=scope.freezePlayerCasualties(battle,StrategicRallyCasualties.Side.ATTACKER,hospitals);
        assertThat(frozen.result()).isEqualTo(battle.result());assertThat(frozen.march().units()).containsEntry("unit_infantry_t4",100L);
        assertThat(frozen.marchVersion()).isEqualTo(7);assertThat(frozen.rally().status()).isEqualTo(Rally.Status.DEPARTED);assertThat(party.status()).isEqualTo(Rally.Status.ARRIVED);
        assertThat(frozen.unitTypes()).containsEntry("unit_infantry_t4",UnitType.INFANTRY);assertThat(frozen.hospitalCapacities()).containsEntry("owned-leader",0L);
        var another=prep.capture(120);
        assertThatThrownBy(()->another.freezePlayerCasualties(battle,StrategicRallyCasualties.Side.ATTACKER,hospitals)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("同一");
        assertThatThrownBy(()->scope.freezePlayerCasualties(battle,StrategicRallyCasualties.Side.ATTACKER,another.prepareHospitals(Map.of()))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("医院");
        assertThatThrownBy(()->scope.freezePlayerCasualties(battle,StrategicRallyCasualties.Side.DEFENDER,hospitals)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NPC");
        assertThatThrownBy(()->hospitals.capacities().put("owned-leader",100L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->scope.npcSurvivors(battle,StrategicRallyCasualties.Side.ATTACKER)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("玩家");
    }
    @Test void invalidTimeAndMissingMemberFactsAreRejected() {
        var prep=preparation();assertThatThrownBy(()->prep.capture(-1)).isInstanceOf(IllegalArgumentException.class);
        var scope=prep.capture(100);assertThatThrownBy(()->scope.playerSide(march("owned-missing",List.of()),0,null,null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(()->scope.hospitalCapacity(null,mock(PlayerSave.class))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(()->scope.hospitalCapacity(mock(CityState.class),null)).isInstanceOf(NullPointerException.class);verifyNoInteractions(players,heroRepository,inventories);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void strategicEntryUsesSiegeAndBuildingBonusForNpcOrStationedPlayer(boolean playerGarrison) {
        var scope=preparation().capture(120);
        var attack=scope.neutralSide("owned-siege-attack",Map.of("unit_infantry_t4",200L,"unit_siege_t4",100L));
        CapturedBattlePreparation.PreparedSide defense;
        if(playerGarrison){
            var party=rally("owned-siege-defender",List.of());var stationed=march("owned-siege-defender",List.of());
            stationed.holdStrategicVictory(Map.of(stationed.playerId(),stationed.units()));
            defense=scope.playerSide(stationed,0,PlayerTech.empty(),party);
        }else defense=scope.neutralSide("owned-siege-npc",Map.of("unit_infantry_t4",100L));
        var siege=scope.simulateStrategic(attack,defense,TerrainType.PLAIN,2379,BattleModifier.none(),BattleModifier.none());
        if(playerGarrison) {
            var actualDefense=defense;
            assertThatThrownBy(()->scope.simulate(attack,actualDefense,TerrainType.PLAIN,2379,BattleType.PVP_RALLY,BattleModifier.none(),BattleModifier.none())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SIEGE");
            assertThatThrownBy(()->scope.simulateStrategic(actualDefense,attack,TerrainType.PLAIN,2379,BattleModifier.none(),BattleModifier.none())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("攻守角色");
        }
        var original=siege.input();
        var comparison=new BattleInput(original.attacker(),original.defender(),original.terrain(),original.seed(),BattleType.PVP_RALLY,
                original.modifierAttacker(),original.modifierDefender(),original.attackerUnitStats(),original.defenderUnitStats(),original.rules(),null);
        var ordinary=BattleSimulator.simulate(comparison);
        assertThat(siege.input().battleType()).isEqualTo(BattleType.SIEGE);
        assertThat(siege.result().rounds().get(0).atkAttack()).isGreaterThan(ordinary.rounds().get(0).atkAttack());
        assertThat(siege.input().rules().deadRatio(siege.input().battleType(),true)).isEqualTo(configs.fixedParam("WOUND_RATIO_PVP_ATTACKER_DEAD"));
        assertThat(siege.input().rules().deadRatio(siege.input().battleType(),false)).isEqualTo(configs.fixedParam("WOUND_RATIO_PVP_DEFENDER_DEAD"));
        assertThat(BattleSimulator.simulate(siege.input())).isEqualTo(siege.result());
    }
    @Test void npcActualSurvivorsStayBoundToOriginalTierCountsAndCapturedConfig()throws Exception {
        var scope=preparation().capture(100);var supplied=new LinkedHashMap<>(Map.of("unit_infantry_t3",15L,"unit_infantry_t4",25L));
        var attack=scope.neutralSide("owned-npc-attacker",Map.of("unit_cavalry_t4",30L));var defense=scope.neutralSide("owned-npc-defender",supplied);
        supplied.clear();var battle=scope.simulateStrategic(attack,defense,TerrainType.PLAIN,19,BattleModifier.none(),BattleModifier.none());
        var before=scope.npcSurvivors(battle,StrategicRallyCasualties.Side.DEFENDER);
        reloadRow("unit",UnitCfg.class,"unit_infantry_t4",r->r.put("type","CAVALRY"));
        assertThat(scope.npcSurvivors(battle,StrategicRallyCasualties.Side.DEFENDER)).isEqualTo(before);
        assertThat(before.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(battle.result().defSurvivors().values().stream().mapToLong(Long::longValue).sum());
        before.forEach((unit,count)->assertThat(count).isBetween(1L,unit.endsWith("t3")?15L:25L));
        assertThatThrownBy(()->before.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->preparation().capture(100).npcSurvivors(battle,StrategicRallyCasualties.Side.DEFENDER)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("同一");
    }
    @Test void mismatchedOriginalRallyOrVersionFailsBeforeHeroAndEquipmentReads() {
        var party=rally("owned-origin",List.of());var wrong=rally("owned-other",List.of());var source=march("owned-origin",List.of());var scope=preparation().capture(120);
        assertThatThrownBy(()->scope.playerSide(source,0,PlayerTech.empty(),wrong)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("原集结");
        assertThatThrownBy(()->scope.playerSide(source,-1,PlayerTech.empty(),party)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("版本");
        assertThatThrownBy(()->scope.playerSide(source,0,PlayerTech.empty(),null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("原集结");
        assertThatThrownBy(()->preparation().capture(119).playerSide(source,0,PlayerTech.empty(),party)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("到达");
        verifyNoInteractions(players,heroRepository,inventories);
    }
    private StrategicTargetDefender reader() {
        return new StrategicTargetDefender(configs,new InMemoryTargetOccupationStore(),new InMemoryMarchStore(32),social,new InMemoryNeutralTargetGarrisonStore());
    }
    @Test void initialNpcCompositionAndCoordinateUseSameCapturedViewAsUnitStats()throws Exception {
        var reader=reader();var preparation=preparation();var old=preparation.capture(200);
        var before=old.readTarget(reader,"gate_north");long total=before.defender().troops().values().stream().mapToLong(Long::longValue).sum();
        reloadRow("world_target",WorldTargetCfg.class,"gate_north",r->r.put("x",255));
        var fresh=preparation.capture(200).readTarget(reader,"gate_north");
        assertThat(fresh.target().coord().x()).isEqualTo(255);assertThat(before.target().coord().x()).isEqualTo(256);
        reloadRow("unit",UnitCfg.class,"unit_infantry_t4",r->r.put("type","CAVALRY"));
        var captured=old.readTarget(reader,"gate_north");
        assertThat(captured.target().coord().x()).isEqualTo(256);assertThat(captured.defender().troops().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(total);
        var side=old.defenderSide(captured,null,null);assertThat(side.army().units().get(UnitType.INFANTRY)).isPositive();
        assertThatThrownBy(()->reader.read(fresh.target())).isInstanceOf(IllegalStateException.class).hasMessageContaining("兵种配置");
    }
    @Test void targetSimulationBindsOriginalTargetAndRejectsOtherTargetOrScope() {
        var reader=reader();var prep=preparation();var scope=prep.capture(200);var party=rally("owned-target-bound",List.of());
        var attack=scope.playerSide(march("owned-target-bound",List.of()),3,PlayerTech.empty(),party);
        var target=scope.readTarget(reader,"gate_north");
        var battle=scope.simulateTarget(attack,target,null,null,23,BattleModifier.none(),BattleModifier.none());
        assertThat(battle.target()).isSameAs(target);assertThat(battle.input().battleType()).isEqualTo(BattleType.SIEGE);
        assertThat(battle.target().defender().troops().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(60185);
        assertThat(BattleSimulator.simulate(battle.input())).isEqualTo(battle.result());
        var other=scope.readTarget(reader,"gate_south");
        assertThatThrownBy(()->scope.simulateTarget(attack,other,null,null,23,BattleModifier.none(),BattleModifier.none())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("原进攻行军");
        var foreign=prep.capture(200).readTarget(reader,"gate_north");
        assertThatThrownBy(()->scope.simulateTarget(attack,foreign,null,null,23,BattleModifier.none(),BattleModifier.none())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("同一");
    }
    @Test void persistedTargetBattleRequiresOriginalTargetAndSameScopeHospitals() {
        var prep=preparation();var scope=prep.capture(200);var party=rally("owned-journal-bound",List.of());
        var attack=scope.playerSide(march("owned-journal-bound",List.of()),3,PlayerTech.empty(),party);
        var target=scope.readTarget(reader(),"gate_north");
        var battle=scope.simulateTarget(attack,target,null,null,2380,BattleModifier.none(),BattleModifier.none());
        var member=new CapturedBattlePreparation.MemberHospital(mock(CityState.class),mock(PlayerSave.class));
        var hospitals=scope.prepareHospitals(Map.of("owned-leader",member));
        var frozen=scope.freezeTargetBattle(battle,hospitals,null);assertThat(frozen.input()).isSameAs(battle.input());assertThat(frozen.result()).isSameAs(battle.result());
        assertThatThrownBy(()->scope.freezeTargetBattle(battle,prep.capture(200).prepareHospitals(Map.of("owned-leader",member)),null)).hasMessageContaining("同一");
        assertThatThrownBy(()->scope.freezeTargetBattle(battle,hospitals,hospitals)).hasMessageContaining("NPC");
        var unbound=scope.simulateStrategic(attack,scope.neutralSide("owned-unbound-npc",Map.of("unit_infantry_t4",100L)),TerrainType.PLAIN,2380,BattleModifier.none(),BattleModifier.none());
        assertThatThrownBy(()->scope.freezeTargetBattle(unbound,hospitals,null)).hasMessageContaining("真实目标");
    }

    @Test void strategicTargetSimulationMustConsumeTheTerrainResolvedFromItsFrozenTarget() {
        var scope=preparation().capture(120);var party=rally("owned-directional-terrain",List.of());
        var attack=scope.playerSide(march("owned-directional-terrain",List.of()),0,PlayerTech.empty(),party);
        var target=scope.readTarget(reader(),"gate_north");
        assertThatThrownBy(()->scope.simulateTarget(attack,target,null,null,TerrainType.PLAIN,2396,
                BattleModifier.none(),BattleModifier.none()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("地形");
        var battle=scope.simulateTarget(attack,target,null,null,2396,
                BattleModifier.none(),BattleModifier.none());
        assertThat(battle.input().battleType()).isEqualTo(BattleType.SIEGE);
        assertThat(battle.input().terrain()).isEqualTo(TerrainType.MOUNTAIN);
    }
}
