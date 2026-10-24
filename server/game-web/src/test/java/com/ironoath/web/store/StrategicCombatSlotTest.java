package com.ironoath.web.store;

import com.ironoath.battle.*;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.march.March;
import com.ironoath.core.player.*;
import com.ironoath.core.social.Rally;
import com.ironoath.core.world.Coord;
import com.ironoath.web.battle.*;
import com.ironoath.web.hero.EquipLedgers;
import com.ironoath.web.nation.*;
import com.ironoath.web.service.HeroStatsService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.*;
import com.ironoath.web.store.mongo.*;
import com.ironoath.web.tech.TechEffects;
import com.ironoath.web.world.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际捕获载荷/双仓储兼容；所有Mongo数据归TestMongo，不开放真实目标争夺。 */
class StrategicCombatSlotTest {
    enum Backend { MEMORY,MONGO }
    private static TestMongo db;
    @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();}
    @AfterAll static void close(){if(db!=null)db.close();}
    @BeforeEach void clear(){db.template().remove(new org.springframework.data.mongodb.core.query.Query(),TargetOperationDocument.COLLECTION);}
    private TargetOperationStore store(Backend b){return b==Backend.MEMORY?new InMemoryTargetOperationStore():new MongoTargetOperationStore(db.template());}
    static CapturedStrategicBattle battle(long seed) {
        return battle(seed,false);
    }
    static CapturedStrategicBattle battle(long seed,boolean playerDefender) {
        return battle(seed,playerDefender,false);
    }
    static CapturedStrategicBattle battle(long seed,boolean playerDefender,boolean hospital) {
        return battle(seed,playerDefender,hospital,false);
    }
    static CapturedStrategicBattle battle(long seed,boolean playerDefender,boolean hospital,boolean forceDraw)throws RuntimeException {
        var cfg=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));var social=mock(SocialStore.class);
        if(forceDraw)try {
            var root=Path.of("").toAbsolutePath();while(root!=null && !java.nio.file.Files.isRegularFile(root.resolve("contract/config/global.json")))root=root.getParent();
            if(root==null)throw new IllegalStateException("owned fixture config root missing");
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var json=mapper.readTree(java.nio.file.Files.readString(root.resolve("contract/config/global.json")));
            for(var row:json.get("rows")){
                if(row.get("id").asText().equals("BATTLE_MAX_ROUNDS"))((com.fasterxml.jackson.databind.node.ObjectNode)row).put("value",1);
                if(row.get("id").asText().equals("BATTLE_DRAW_GAP_RATIO"))((com.fasterxml.jackson.databind.node.ObjectNode)row).put("value","1.0");
            }
            cfg.reload("global",com.ironoath.config.model.GlobalCfg.class,json.toString());
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
        var heroes=new HeroBattleMapper(cfg,new HeroStatsService(cfg),new EquipLedgers(mock(InventoryRepository.class),cfg),mock(HeroRepository.class),social);
        var tech=new BattleTechBonuses(new AllianceTechBonuses(social,cfg),new TechEffects(cfg),mock(PlayerRepository.class));
        var prep=new CapturedBattlePreparation(cfg,heroes,tech,new NationPolicyBonuses(cfg,social,mock(NationStore.class),mock(NationLeaders.class),new BattleArmyFactory(cfg)));
        var scope=prep.capture(200);
        var leaderTroops=hospital&&!playerDefender?Map.of("unit_infantry_t3",50000L,"unit_infantry_t4",50000L):Map.of("unit_infantry_t4",100L);
        var participants=new LinkedHashMap<String,Rally.Participant>();participants.put("owned-leader",new Rally.Participant("owned-leader",leaderTroops,List.of()));
        if(hospital&&!playerDefender)participants.put("owned-tiny",new Rally.Participant("owned-tiny",Map.of("unit_infantry_t4",1L),List.of()));
        var troops=new LinkedHashMap<String,Long>();participants.values().forEach(p->p.troops().forEach((id,n)->troops.merge(id,n,Math::addExact)));
        var rally=Rally.restore("owned-combat-rally",Rally.Scope.NATION,"owned-N","owned-leader",2,1,100,110,
                participants,Rally.Status.PREPARING,null,256,216,"STRATEGIC_TARGET",1);
        rally.depart(110);
        var march=new March("owned-combat-march","owned-leader",Coord.of(10,10),Coord.of(256,216),110,120,troops,List.of(),1000,1,March.TargetType.STRATEGIC_TARGET,"gate_north",March.Action.ATTACK);
        march.attachToRally(rally.rallyId());march.arrive(120);
        var attacker=scope.playerSide(march,7,PlayerTech.empty(),rally);
        var owners=new InMemoryTargetOccupationStore();var marches=new InMemoryMarchStore(32);Rally defenderRally=null;
        if(playerDefender) {
            var troopsDef=Map.of("unit_cavalry_t4",100L);
            defenderRally=Rally.restore("owned-defender-rally",Rally.Scope.NATION,"owned-defender-N","owned-defender",2,1,100,110,
                    Map.of("owned-defender",new Rally.Participant("owned-defender",troopsDef,List.of())),Rally.Status.PREPARING,null,256,216,"STRATEGIC_TARGET",1);
            defenderRally.depart(110);when(social.rallyOf(defenderRally.rallyId())).thenReturn(Optional.of(defenderRally));
            var def=new March("owned-defender-march","owned-defender",Coord.of(10,10),Coord.of(256,216),110,120,troopsDef,List.of(),1000,1,March.TargetType.STRATEGIC_TARGET,"gate_north",March.Action.ATTACK);
            def.attachToRally(defenderRally.rallyId());def.arrive(120);def.holdStrategicVictory(Map.of("owned-defender",troopsDef));marches.insertIfAbsent(def);
            owners.compareAndSet(new TargetOccupationStore.Snapshot("gate_north",com.ironoath.core.world.WorldGenerator.EntityType.GATE,256,216,
                    "owned-defender-N","原驻军国",120,"owned-capture",1,TargetOccupationStore.Garrison.stationed(def.id())),0);
        }
        var reader=new StrategicTargetDefender(cfg,owners,marches,social,new InMemoryNeutralTargetGarrisonStore());
        var target=scope.readTarget(reader,"gate_north");
        var simulation=scope.simulateTarget(attacker,target,playerDefender?PlayerTech.empty():null,defenderRally,seed,BattleModifier.none(),BattleModifier.none());
        var save=mock(PlayerSave.class);when(save.tech()).thenReturn(PlayerTech.empty());
        var city=mock(CityState.class);
        if(hospital){var building=mock(com.ironoath.core.city.BuildingInstance.class);when(building.configId()).thenReturn("hospital");when(building.level()).thenReturn(1);when(city.buildings()).thenReturn(List.of(building));}
        var member=new CapturedBattlePreparation.MemberHospital(city,save);var members=new LinkedHashMap<String,CapturedBattlePreparation.MemberHospital>();participants.keySet().forEach(id->members.put(id,member));
        var hospitals=scope.prepareHospitals(members);
        var defenderHospitals=playerDefender?scope.prepareHospitals(Map.of("owned-defender",member)):null;
        return scope.freezeTargetBattle(simulation,hospitals,defenderHospitals);
    }

    static CapturedStrategicBattle fortressBattle(long seed,String targetId) {
        var cfg=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));var social=mock(SocialStore.class);
        var heroes=new HeroBattleMapper(cfg,new HeroStatsService(cfg),new EquipLedgers(mock(InventoryRepository.class),cfg),mock(HeroRepository.class),social);
        var tech=new BattleTechBonuses(new AllianceTechBonuses(social,cfg),new TechEffects(cfg),mock(PlayerRepository.class));
        var prep=new CapturedBattlePreparation(cfg,heroes,tech,new NationPolicyBonuses(cfg,social,
                mock(NationStore.class),mock(NationLeaders.class),new BattleArmyFactory(cfg)));
        var scope=prep.capture(200);
        var target=com.ironoath.web.service.WorldTargets.from(cfg).targets().stream()
                .filter(row->row.definition().id().equals(targetId))
                .findFirst().orElseThrow();
        if(target.definition().kind()!=com.ironoath.config.cfg.WorldTargetCfg.Kind.FORTRESS)
            throw new IllegalArgumentException("fortress fixture requires configured fortress target");
        var coord=target.coord();var troops=Map.of("unit_cavalry_t4",1_000_000L);
        var participant=new Rally.Participant("owned-leader",troops,List.of());
        var rally=Rally.restore("owned-fortress-rally",Rally.Scope.NATION,"owned-fortress-nation","owned-leader",
                2,1,100,110,Map.of("owned-leader",participant),Rally.Status.PREPARING,null,
                coord.x(),coord.y(),"STRATEGIC_TARGET",1);
        rally.depart(110);
        var march=new March("owned-fortress-march","owned-leader",Coord.of(10,10),Coord.of(coord.x(),coord.y()),
                110,120,troops,List.of(),1000,1,March.TargetType.STRATEGIC_TARGET,targetId,March.Action.ATTACK);
        march.attachToRally(rally.rallyId());march.arrive(120);
        var attacker=scope.playerSide(march,7,PlayerTech.empty(),rally);
        var owners=new InMemoryTargetOccupationStore();var marches=new InMemoryMarchStore(32);
        var reader=new StrategicTargetDefender(cfg,owners,marches,social,new InMemoryNeutralTargetGarrisonStore());
        var targetInput=scope.readTarget(reader,targetId);
        var simulation=scope.simulateTarget(attacker,targetInput,null,null,seed,BattleModifier.none(),BattleModifier.none());
        var save=mock(PlayerSave.class);when(save.tech()).thenReturn(PlayerTech.empty());
        var member=new CapturedBattlePreparation.MemberHospital(mock(CityState.class),save);
        return scope.freezeTargetBattle(simulation,scope.prepareHospitals(Map.of("owned-leader",member)),null);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void withdrawalCombatWithdrawalUsesOneMonotonicSlotAndExactReplay(Backend b) {
        var store=store(b);var first=TargetOperationStoreTest.plan("before-combat",1,120);
        assertThat(store.compareAndSet(new TargetOperationStore.Snapshot("gate_north",1,false,first),0)).isTrue();
        var plan=TargetOperationStore.CombatPlan.create(battle(2380),2);
        var pending=TargetOperationStore.Snapshot.combat(3,false,plan);
        assertThat(store.compareAndSet(pending,2)).as("不能越过未完成撤防").isFalse();
        assertThat(store.compareAndSet(new TargetOperationStore.Snapshot("gate_north",2,true,first),1)).isTrue();
        assertThat(store.compareAndSet(pending,2)).isTrue();assertThat(store.compareAndSet(pending,2)).isTrue();
        assertThat(store.findByOperationId(plan.operationId())).contains(pending);
        assertThat(store.findByOperationId(first.operationId())).isEmpty();
        var changed=TargetOperationStore.CombatPlan.create(battle(2381),2);
        assertThat(changed.operationId()).isEqualTo(plan.operationId());
        assertThat(store.compareAndSet(TargetOperationStore.Snapshot.combat(4,true,changed),3)).as("同ID不可换原输入/结果").isFalse();
        assertThat(store.pendingTargetIds(List.of("gate_north"))).containsExactly("gate_north");
        var done=TargetOperationStore.Snapshot.combat(4,true,plan);assertThat(store.compareAndSet(done,3)).isTrue();
        var next=new TargetOperationStore.Snapshot("gate_north",5,false,TargetOperationStoreTest.plan("after-combat",3,200));
        assertThat(store.compareAndSet(next,4)).isTrue();assertThat(store.compareAndSet(done,3)).isFalse();
        assertThat(store.findByTargetId("gate_north")).contains(next);assertThat(store.findByOperationId(plan.operationId())).isEmpty();
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void legacyRawWithdrawalBsonWithoutDiscriminatorReadsWithoutRewriting(boolean completed) {
        var store=store(Backend.MONGO);var old=new TargetOperationStore.Snapshot("gate_north",1,false,TargetOperationStoreTest.plan("legacy",1,120));
        assertThat(store.compareAndSet(old,0)).isTrue();
        if(completed){old=new TargetOperationStore.Snapshot("gate_north",2,true,old.plan());assertThat(store.compareAndSet(old,1)).isTrue();}
        var collection=db.template().getCollection(TargetOperationDocument.COLLECTION);
        collection.updateOne(new Document("_id","gate_north"),new Document("$unset",new Document("state.kind",1).append("state.combatPlan",1)));
        var before=collection.find(new Document("_id","gate_north")).first();
        var loaded=new MongoTargetOperationStore(db.template()).findByTargetId("gate_north").orElseThrow();
        assertThat(loaded).isEqualTo(old);assertThat(loaded.kind()).isEqualTo(TargetOperationStore.Kind.WITHDRAWAL);
        assertThat(loaded.anchor().x()).isEqualTo(256);assertThat(loaded.sequence()).isEqualTo(1);
        assertThat(collection.find(new Document("_id","gate_north")).first()).isEqualTo(before);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void actualFrozenCombatRoundTripPreservesOriginalFactsAndCollections(Backend b) {
        var battle=battle(2380);var plan=TargetOperationStore.CombatPlan.create(battle,1);var state=TargetOperationStore.Snapshot.combat(1,false,plan);
        var store=store(b);assertThat(store.compareAndSet(state,0)).isTrue();
        if(b==Backend.MONGO)store=new MongoTargetOperationStore(db.template());
        var loaded=store.findByTargetId("gate_north").orElseThrow();assertThat(loaded).isEqualTo(state);
        assertThat(loaded.kind()).isEqualTo(TargetOperationStore.Kind.COMBAT);assertThat(loaded.plan()).isNull();
        assertThat(loaded.operationId()).isEqualTo("battle:owned-combat-march:7");assertThat(loaded.happenedAt()).isEqualTo(200);
        var original=loaded.combatPlan().battle();assertThat(original.attacker().marchVersion()).isEqualTo(7);
        assertThat(original.target().defender().initialNpc()).isTrue();assertThat(original.target().defender().troops().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(60185);
        assertThat(original.input().seed()).isEqualTo(original.result().seed());
        assertThat(BattleSimulator.simulate(original.input())).isEqualTo(original.result()); // 仅测试显式复算；仓储读取不调用内核。
        assertThatThrownBy(()->original.input().rules().counterMatrix().get(UnitType.INFANTRY).clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->original.npcSurvivors().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->original.target().defender().troops().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void malformedMixedOrMissingPayloadAndWrongTargetCannotBecomeAbsence() {
        var battle=TargetOperationStore.CombatPlan.create(battle(2380),1);
        var withdrawal=TargetOperationStoreTest.plan("ambiguous",1,120);
        assertThatThrownBy(()->new TargetOperationStore.Snapshot("gate_north",1,false,withdrawal,TargetOperationStore.Kind.COMBAT,battle)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TargetOperationStore.Snapshot("gate_north",1,false,null,TargetOperationStore.Kind.COMBAT,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TargetOperationStore.Snapshot("gate_south",1,false,null,TargetOperationStore.Kind.COMBAT,battle)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TargetOperationStore.CombatPlan(withdrawal.operationId(),1,battle.battle())).isInstanceOf(IllegalArgumentException.class);
        var store=store(Backend.MONGO);store.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,battle),0);
        var collection=db.template().getCollection(TargetOperationDocument.COLLECTION);
        collection.updateOne(new Document("_id","gate_north"),new Document("$set",new Document("state.kind","UNKNOWN_KIND")));
        assertThat(store.pendingTargetIds(List.of("gate_north","gate_south"))).containsExactly("gate_north");
        assertThatThrownBy(()->store.findByTargetId("gate_north")).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void playerGarrisonOriginalOwnerMarchAndMemberSharesSurviveRoundTrip(Backend b) {
        var frozen=battle(2380,true);var slot=TargetOperationStore.Snapshot.combat(1,false,TargetOperationStore.CombatPlan.create(frozen,1));
        var store=store(b);assertThat(store.compareAndSet(slot,0)).isTrue();
        var actual=store.findByTargetId("gate_north").orElseThrow().combatPlan().battle();assertThat(actual).isEqualTo(frozen);
        assertThat(actual.defender().march()).isEqualTo(actual.target().defender().march());
        assertThat(actual.defender().marchVersion()).isEqualTo(actual.target().defender().marchVersion());
        assertThat(actual.defender().currentMembers()).containsOnlyKeys("owned-defender");
        assertThat(actual.npcUnitTypes()).isEmpty();assertThat(actual.npcSurvivors()).isEmpty();
        assertThat(actual.target().defender().occupation().nationId()).isEqualTo("owned-defender-N");
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void absentOperationIdDoesNotMatchMissingOtherKindPayload(Backend b) {
        var store=store(b);var slot=TargetOperationStore.Snapshot.combat(1,false,TargetOperationStore.CombatPlan.create(battle(2380),1));
        assertThat(store.compareAndSet(slot,0)).isTrue();assertThat(store.findByOperationId(null)).isEmpty();assertThat(store.findByOperationId("")).isEmpty();
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void racingDifferentOriginalResultsCannotBothOwnSameTarget(Backend b)throws Exception {
        var store=store(b);var one=TargetOperationStore.Snapshot.combat(1,false,TargetOperationStore.CombatPlan.create(battle(2380),1));
        var two=TargetOperationStore.Snapshot.combat(1,false,TargetOperationStore.CombatPlan.create(battle(2381),1));
        var second=b==Backend.MONGO?new MongoTargetOperationStore(db.template()):store;
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->{start.await();return store.compareAndSet(one,0);});var d=pool.submit(()->{start.await();return second.compareAndSet(two,0);});start.countDown();
            assertThat(List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),d.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(store.findByTargetId("gate_north").orElseThrow()).isIn(one,two);
        }finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }

    @Test void lostCombatWriteReplyRequiresExactIndependentReadback() {
        var real=db.template();var mongo=spy(real);var store=new MongoTargetOperationStore(mongo);
        var plan=TargetOperationStore.CombatPlan.create(battle(2380),1);var pending=TargetOperationStore.Snapshot.combat(1,false,plan);
        doAnswer(inv->{inv.callRealMethod();throw new IllegalStateException("owned combat insert reply lost");})
                .when(mongo).insert(any(TargetOperationDocument.class),eq(TargetOperationDocument.COLLECTION));
        assertThat(store.compareAndSet(pending,0)).isTrue();
        var done=TargetOperationStore.Snapshot.combat(2,true,plan);
        doAnswer(inv->{inv.callRealMethod();throw new IllegalStateException("owned combat complete reply lost");})
                .when(mongo).updateFirst(any(org.springframework.data.mongodb.core.query.Query.class),any(org.springframework.data.mongodb.core.query.Update.class),eq(TargetOperationDocument.class),eq(TargetOperationDocument.COLLECTION));
        assertThat(store.compareAndSet(done,1)).isTrue();assertThat(new MongoTargetOperationStore(real).findByTargetId("gate_north")).contains(done);
        var wrong=TargetOperationStore.Snapshot.combat(1,false,TargetOperationStore.CombatPlan.create(battle(2381),1));
        var unavailable=mock(org.springframework.data.mongodb.core.MongoTemplate.class);var failure=new IllegalStateException("owned unknown write");
        doThrow(failure).when(unavailable).insert(any(TargetOperationDocument.class),eq(TargetOperationDocument.COLLECTION));
        when(unavailable.findById(eq("gate_north"),eq(TargetOperationDocument.class),eq(TargetOperationDocument.COLLECTION))).thenReturn(new TargetOperationDocument("gate_north",wrong));
        assertThatThrownBy(()->new MongoTargetOperationStore(unavailable).compareAndSet(pending,0)).isSameAs(failure);
    }
}
