package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.city.CityState;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.resource.ResourceIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.NationAppointReq;
import com.ironoath.web.dto.generated.NationFoundReq;
import com.ironoath.web.dto.generated.NationOffice;
import com.ironoath.web.dto.generated.NationRallyReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.RallyJoinReq;
import com.ironoath.web.dto.generated.RallyTroop;
import com.ironoath.web.dto.generated.SocialCoord;
import com.ironoath.web.dto.generated.SocialTargetType;
import com.ironoath.web.nation.NationLeaders;
import com.ironoath.web.nation.NationPolicyBonuses;
import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.nation.NationTechBonuses;
import com.ironoath.web.service.MarchAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.PowerRefreshService;
import com.ironoath.web.service.ResourceRateService;
import com.ironoath.web.service.StrategicTargetCircleGuard;
import com.ironoath.web.service.WorldTargets;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.mongo.MongoIndexes;
import com.ironoath.web.store.mongo.MongoNationStore;
import com.ironoath.web.store.mongo.MongoTargetOccupationStore;
import com.ironoath.web.world.StrategicGarrisonWithdrawal;
import com.ironoath.web.world.TargetOperationStore;
import com.ironoath.web.world.TargetOccupationStore;
import com.ironoath.web.tech.TechEffects;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.mockito.ArgumentMatchers;

/** Eight real HTTP siege captures persist owners in a random Mongo database before rates are rebuilt. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(NationRallyFortressMongoEndpointTest.DeterministicSeed.class)
class NationRallyFortressMongoEndpointTest {
    private static final String OWNER=UUID.randomUUID().toString().replace("-","");
    private static final String DATABASE="ironoath_b13_fortress_http_"+OWNER;
    private static final String BASE_URI="mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=3000";

    @TestConfiguration(proxyBeanMethods=false)
    static class DeterministicSeed {
        @Bean @org.springframework.context.annotation.Primary
        com.ironoath.web.reward.ServerSeedSource fortressBattleSeed(){return ()->2384L;}
    }

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry properties) {
        properties.add("ironoath.storage",()->"mongo");
        properties.add("spring.data.mongodb.uri",()->"mongodb://127.0.0.1:27017/"+DATABASE+"?serverSelectionTimeoutMS=3000");
    }

    @BeforeAll static void markOwnedDatabase() {
        try(var client=MongoClients.create(BASE_URI)) {
            client.getDatabase(DATABASE).getCollection("__owned_run").insertOne(new Document("_id",OWNER));
        }
    }

    @AfterAll static void dropOwnedDatabase() {
        try(var client=MongoClients.create(BASE_URI)) {
            var database=client.getDatabase(DATABASE);
            assertThat(database.getCollection("__owned_run").countDocuments(new Document("_id",OWNER))).isEqualTo(1);
            var dropped=database.runCommand(new Document("dropDatabase",1).append("comment",OWNER));
            assertThat(((Number)dropped.get("ok")).doubleValue()).isEqualTo(1);
            assertThat(client.getDatabase("admin").runCommand(new Document("listDatabases",1)
                    .append("filter",new Document("name",DATABASE)).append("nameOnly",true)
                    .append("comment",OWNER)).getList("databases",Document.class)).isEmpty();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private ConfigRegistry configs;
    @Autowired private PlayerInitService playerInit;
    @Autowired private com.ironoath.core.player.PlayerRepository players;
    @Autowired private com.ironoath.core.army.ArmyRepository armies;
    @Autowired private PowerRefreshService powerRefresh;
    @Autowired private SocialStore socialStore;
    @Autowired private com.ironoath.web.service.SocialAppService socialApp;
    @Autowired private MarchAppService marchApp;
    @Autowired private MarchRepository marchRepository;
    @Autowired private TargetOperationStore targetOperations;
    @Autowired private TargetOccupationStore targetOccupations;
    @Autowired private StrategicGarrisonWithdrawal recovery;
    @Autowired private ResourceRateService resourceRates;
    @Autowired private NationStore nationStore;
    @Autowired private NationLeaders nationLeaders;
    @Autowired private NationTechBonuses nationTech;
    @Autowired private NationPolicyBonuses policy;
    @Autowired private TechEffects techEffects;
    @Autowired private NationRulesAssembler nationRules;
    @SpyBean private StrategicTargetCircleGuard circleGuard;
    private int sequence;

    @BeforeEach void cleanOwnedMongo() {
        mongoTemplate.getDb().drop();
        mongoTemplate.getDb().getCollection("__owned_run").insertOne(new Document("_id",OWNER));
        MongoIndexes.ensure(mongoTemplate);
        sequence=0;
    }

    @Test
    @DirtiesContext(methodMode=DirtiesContext.MethodMode.AFTER_METHOD)
    void allEightFortressesCaptureThroughHttpAndRebuildMongoIncome() throws Exception {
        var nation=createNation(4);
        post200("/nation/appoint",nation.king(),new NationAppointReq(newRequestId(),nation.members().get(0),NationOffice.PRIME_MINISTER));
        post200("/nation/appoint",nation.king(),new NationAppointReq(newRequestId(),nation.members().get(1),NationOffice.GENERAL));
        post200("/nation/appoint",nation.king(),new NationAppointReq(newRequestId(),nation.members().get(2),NationOffice.GENERAL));
        doNothing().when(circleGuard).guardRallyStart(ArgumentMatchers.anyString(),ArgumentMatchers.any(),ArgumentMatchers.anyInt());
        doNothing().when(circleGuard).guardArrival(ArgumentMatchers.anyLong(),ArgumentMatchers.any(),ArgumentMatchers.anyInt());

        var fortresses=WorldTargets.from(configs).targets().stream()
                .filter(target->target.definition().kind()==com.ironoath.config.cfg.WorldTargetCfg.Kind.FORTRESS)
                .sorted(java.util.Comparator.comparing(target->target.definition().id())).toList();
        assertThat(fortresses).hasSize(8);
        var captains=new ArrayList<String>();captains.add(nation.king());captains.addAll(nation.members());
        captains.forEach(player->giveTroops(player,"unit_cavalry_t4",10_000_000L));

        long startedAt=System.currentTimeMillis();
        var resources=List.of(ResourceIds.GRAIN,ResourceIds.WOOD,ResourceIds.STONE,ResourceIds.IRON).stream()
                .collect(java.util.stream.Collectors.toMap(id->id,id->new PlayerResourceState(0,1_000_000L,0,0,startedAt),
                        (left,right)->left,LinkedHashMap::new));
        var player=PlayerSave.createNew(nation.king(),"mongo-eight-"+UUID.randomUUID(),"Mongo八要塞成员",16,
                startedAt,1,resources,PlayerPower.zero(),null);
        var baseline=resourceRates.compute(new CityState(),player.tech(),player.playerId(),startedAt);
        var captures=new ArrayList<TargetOccupationStore.Snapshot>();

        for(int index=0;index<fortresses.size();index++) {
            var target=fortresses.get(index);var coord=target.coord();
            String captain=captains.get(index%captains.size());
            String joiner=captains.get((index+1)%captains.size());
            String rallyId=post200("/rally/nation",captain,new NationRallyReq(newRequestId(),
                    new SocialCoord(coord.x(),coord.y()),SocialTargetType.STRATEGIC_TARGET,4,10,
                    List.of(new RallyTroop("unit_cavalry_t4",500_000L)),List.of()))
                    .get("rally").get("rallyId").asText();
            post200("/rally/join",joiner,new RallyJoinReq(newRequestId(),rallyId,
                    List.of(new RallyTroop("unit_cavalry_t4",500_000L)),List.of()));
            var rally=socialStore.rallyOf(rallyId).orElseThrow();
            marchApp.processDue(captain,rally.prepareUntil()+1L);
            String marchId="march_rally_"+rallyId;
            var march=marchRepository.findById(marchId).orElseThrow().snapshot();
            marchApp.processDue(captain,march.arriveAt());
            var slot=targetOperations.findByTargetId(target.definition().id()).orElseThrow();
            assertThat(slot.combatPlan().battle().input().battleType())
                    .as("%s must use SIEGE in Mongo storage",target.definition().id())
                    .isEqualTo(com.ironoath.battle.BattleType.SIEGE);
            assertThat(slot.combatPlan().battle().input().terrain()).isEqualTo(target.battleTerrain());
            assertThat(slot.combatPlan().battle().result().winner()).isEqualTo(com.ironoath.battle.Winner.ATTACKER);
            recovery.recoverPending();
            var owner=new MongoTargetOccupationStore(mongoTemplate).findByTargetIds(List.of(target.definition().id()))
                    .get(target.definition().id());
            assertThat(owner).as("Mongo recovery must persist %s occupation",target.definition().id()).isNotNull();
            assertThat(owner.nationId()).isEqualTo(nation.nationId());
            assertThat(owner.garrison()).isEqualTo(TargetOccupationStore.Garrison.stationed(marchId));
            assertThat(targetOperations.findByTargetId(target.definition().id()).orElseThrow().completed()).isTrue();
            captures.add(owner);
        }

        long settledAt=captures.stream().mapToLong(TargetOccupationStore.Snapshot::capturedAt).max().orElseThrow()+3_600_000L;
        var freshOccupationStore=new MongoTargetOccupationStore(mongoTemplate);
        var freshNationStore=new MongoNationStore(mongoTemplate,nationRules);
        var rebuiltRates=new ResourceRateService(configs,techEffects,
                new NationTechBonuses(configs,socialStore,freshNationStore,nationLeaders),
                new NationPolicyBonuses(configs,socialStore,freshNationStore,nationLeaders,
                        new com.ironoath.web.battle.BattleArmyFactory(configs)),freshOccupationStore);
        var settled=rebuiltRates.settle(player,new CityState(),settledAt);
        var viewPlayer=PlayerSave.createNew(nation.king(),"mongo-eight-view-"+UUID.randomUUID(),"Mongo只读验收",16,
                startedAt,1,resources,PlayerPower.zero(),null);
        var view=rebuiltRates.settledView(viewPlayer,new CityState(),settledAt);
        for(String id:resources.keySet()) {
            long expected=baseline.perHour(id)+configs.getResource(id).basePerHour()*16/100;
            assertThat(settled.rates().perHour(id)).as("Mongo rebuilt 16 percent rate for %s",id).isEqualTo(expected);
            assertThat(settled.credited().get(id)).isGreaterThanOrEqualTo(expected);
            assertThat(view.get(id).perHour()).isEqualTo(expected);
            assertThat(view.get(id).current()).isEqualTo(settled.credited().get(id));
            assertThat(viewPlayer.resource(id).current()).isZero();
        }
    }

    private record NationFixture(String king,List<String> members,String nationId,String allianceId) {}

    private NationFixture createNation(int members) throws Exception {
        String king=newPlayer(16);sequence++;
        String allianceId=post200("/alliance/create",king,new AllianceCreateReq(newRequestId(),
                "Mongo集结盟"+sequence,String.format("M%03d",sequence%1000))).get("alliance").get("id").asText();
        var players=new ArrayList<String>();
        for(int i=1;i<members;i++) {
            String member=newPlayer(16);
            post200("/alliance/apply",member,new AllianceIdReq(newRequestId(),allianceId));
            post200("/alliance/review",king,new AllianceReviewReq(newRequestId(),member,true));
            players.add(member);
        }
        String nationId=post200("/nation/found",king,new NationFoundReq(newRequestId(),"Mongo集结国"+sequence,
                100L,200L)).get("nation").get("nationId").asText();
        return new NationFixture(king,List.copyOf(players),nationId,allianceId);
    }

    private String newPlayer(int cityLevel) {
        String player=playerInit.init(new PlayerInitReq(newRequestId(),"mongo-device-"+UUID.randomUUID(),
                "Mongo集结测试",1_700_000_000_000L,"")).playerId();
        var save=players.findByPlayerId(player).orElseThrow();save.setCityLevel(cityLevel);save.setProtectUntil(null);
        var gold=save.resources().get("GOLD");
        if(gold!=null)save.putResource("GOLD",new PlayerResourceState(100_000L,gold.cap(),gold.protectedAmount(),
                gold.perHour(),gold.lastSettle()));
        players.save(save);return player;
    }

    private void giveTroops(String player,String unit,long count) {
        var army=armies.findByPlayerId(player).orElse(null);
        if(army==null){armies.insertIfAbsent(player,new ArmyState());army=armies.findByPlayerId(player).orElseThrow();}
        long version=armies.versionOf(player);army.add(unit,count);armies.save(player,army,version);powerRefresh.refresh(player);
    }

    private JsonNode post200(String path,String player,Object request) throws Exception {
        var root=perform(post(path).header("X-Player-Id",player).contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(request)));
        assertThat(root.get("code").asInt()).as("%s response: %s",path,root).isZero();
        return root.get("data");
    }

    private JsonNode perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result=mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String newRequestId(){return "req-"+UUID.randomUUID();}
}
