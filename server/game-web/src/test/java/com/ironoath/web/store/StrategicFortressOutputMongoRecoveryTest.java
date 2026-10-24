package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.WorldTargetCfg;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.social.Alliance;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.web.battle.BattleArmyFactory;
import com.ironoath.web.nation.NationLeaders;
import com.ironoath.web.nation.NationPolicyBonuses;
import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.nation.NationTechBonuses;
import com.ironoath.web.service.ResourceRateService;
import com.ironoath.web.service.WorldTargets;
import com.ironoath.web.social.SocialRulesAssembler;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.mongo.MongoNationStore;
import com.ironoath.web.store.mongo.MongoSocialStore;
import com.ironoath.web.store.mongo.MongoTargetOccupationStore;
import com.ironoath.web.tech.TechEffects;
import com.ironoath.web.world.TargetOccupationStore;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Real Mongo readback proves membership and all-resource fortress boundaries survive store reconstruction. */
class StrategicFortressOutputMongoRecoveryTest {
    private static final long START=1_700_000_000_000L;
    private static ConfigRegistry configs;
    private static SocialRulesAssembler socialRules;
    private static NationRulesAssembler nationRules;
    private static TestMongo db;

    @BeforeAll static void setUp() {
        configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        socialRules=new SocialRulesAssembler(configs);
        nationRules=new NationRulesAssembler(configs);
        db=TestMongo.tryOpen();
    }

    @AfterAll static void release() {
        if(db!=null) { db.close(); db=null; }
    }

    @Test void closedMemberEpochAndEightFortressOwnersSurviveFreshMongoStores() {
        assumeTrue(db!=null,"本机 Mongo 不可用：隔离Mongo恢复路径未验证");
        String suffix=UUID.randomUUID().toString().replace("-","");
        String allianceId="alliance-"+suffix;
        String nationId="nation-"+suffix;
        String memberId="member-"+suffix;
        String leaderId="leader-"+suffix;
        var template=db.template();
        SocialStore social=new MongoSocialStore(template,socialRules);
        NationStore nations=new MongoNationStore(template,nationRules);
        var occupations=new MongoTargetOccupationStore(template);

        Alliance alliance=Alliance.create(allianceId,"历史要塞盟"+suffix.substring(0,6),"H"+suffix.substring(0,6),
                leaderId,1_000_000L,socialRules.allianceRules());
        social.saveAlliance(alliance,0L,START);
        Alliance beforeJoin=social.allianceById(allianceId).orElseThrow();
        long expectedAllianceVersion=beforeJoin.version();
        beforeJoin.join(memberId);
        social.saveAlliance(beforeJoin,expectedAllianceVersion,START);
        var nation=Nation.found(nationId,"历史要塞国"+suffix.substring(0,6),leaderId,allianceId,10,20,START,
                nationRules.rules());
        assertThat(nations.insertIfAbsent(nation)).isTrue();

        var forts=WorldTargets.from(configs).targets().stream()
                .filter(target->target.definition().kind()==WorldTargetCfg.Kind.FORTRESS).toList();
        assertThat(forts).hasSize(8);
        for(var target:forts)assertThat(occupations.compareAndSet(new TargetOccupationStore.Snapshot(
                target.definition().id(),WorldGenerator.EntityType.FORTRESS,target.coord().x(),target.coord().y(),
                nationId,nation.name(),START,"capture-"+suffix+"-"+target.definition().id(),1,
                TargetOccupationStore.Garrison.empty()),0)).isTrue();

        Alliance leaving=social.allianceById(allianceId).orElseThrow();
        long leavingVersion=leaving.version();
        leaving.leave(memberId);
        social.saveAlliance(leaving,leavingVersion,START+3_600_000L);

        List<String> resourceIds=List.of(ResourceIds.GRAIN,ResourceIds.WOOD,ResourceIds.STONE,ResourceIds.IRON);
        var resources=resourceIds.stream().collect(java.util.stream.Collectors.toMap(id->id,
                id->new PlayerResourceState(0,1_000_000L,0,0,START),(left,right)->left,LinkedHashMap::new));
        long settledAt=START+7_200_000L;
        var firstPlayer=PlayerSave.createNew(memberId,"device-"+suffix,"四资源恢复成员",1,START,1,resources,
                PlayerPower.zero(),null);
        var firstRates=service(configs,social,nations,occupations);
        var settled=firstRates.settle(firstPlayer,new com.ironoath.core.city.CityState(),settledAt);

        for(String id:resourceIds) {
            long base=configs.getResource(id).basePerHour();
            long expectedEightForts=base+(base*16/100);
            assertThat(settled.credited().get(id)).isEqualTo(expectedEightForts+base);
            assertThat(settled.rates().perHour(id)).isEqualTo(base);
        }

        SocialStore restartedSocial=new MongoSocialStore(template,socialRules);
        NationStore restartedNations=new MongoNationStore(template,nationRules);
        var restartedOccupations=new MongoTargetOccupationStore(template);
        assertThat(restartedSocial.allianceMembershipEpochs(memberId)).containsExactly(
                new SocialStore.AllianceMembershipEpoch(allianceId,START,START+3_600_000L));
        assertThat(restartedNations.allianceNationEpochs(allianceId)).containsExactly(
                new NationStore.AllianceNationEpoch(nationId,START,Long.MAX_VALUE));
        assertThat(restartedOccupations.findByTargetIds(forts.stream()
                .map(target->target.definition().id()).toList())).hasSize(8);

        var restartedViewPlayer=PlayerSave.createNew(memberId,"device-view-"+suffix,"四资源恢复成员",1,START,1,
                resources,PlayerPower.zero(),null);
        var restartedView=service(configs,restartedSocial,restartedNations,restartedOccupations)
                .settledView(restartedViewPlayer,new com.ironoath.core.city.CityState(),settledAt);
        for(String id:resourceIds) {
            long base=configs.getResource(id).basePerHour();
            assertThat(restartedView.get(id).current()).isEqualTo(base+(base*16/100)+base);
            assertThat(restartedViewPlayer.resource(id).current()).isZero();
        }
    }

    private static ResourceRateService service(ConfigRegistry configs,SocialStore social,NationStore nations,
            TargetOccupationStore occupations) {
        var leaders=mock(NationLeaders.class);
        var national=new NationTechBonuses(configs,social,nations,leaders);
        var policy=new NationPolicyBonuses(configs,social,nations,leaders,mock(BattleArmyFactory.class));
        return new ResourceRateService(configs,new TechEffects(configs),national,policy,occupations);
    }
}
