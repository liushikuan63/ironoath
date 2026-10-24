package com.ironoath.web.store;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.march.March;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.core.nation.Nation;
import com.ironoath.web.battle.CapturedStrategicBattle;
import com.ironoath.web.battle.StrategicRallyCasualties;
import com.ironoath.web.battle.StrategicBattleHospitalSettlement;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.store.mongo.*;
import com.ironoath.web.world.StrategicCombatRecovery;
import com.ironoath.web.world.TargetOperationStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** The recovery owner replays actual Mongo Army/March/War/COMBAT receipts across fresh store instances. */
class StrategicCombatRecoveryMongoTest {
    private static TestMongo db;
    @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();}
    @AfterAll static void close(){if(db!=null)db.close();}
    @BeforeEach void clear(){db.template().getDb().drop();MongoIndexes.ensure(db.template());}

    @Test void admittedHospitalReceiptsSurviveRecoveryRestartWithoutAdvancingThePendingCombat() {
        var battle=StrategicCombatSlotTest.battle(2400,true,true);var plan=TargetOperationStore.CombatPlan.create(battle,1);
        var operations=new MongoTargetOperationStore(db.template());
        assertThat(operations.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,plan),0)).isTrue();

        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));var rules=new WarRulesAssembler(configs);
        var wars=new MongoWarStore(db.template(),rules);var board=new WarScoreBoard(rules.rules(),battle.happenedAt()-10,
                battle.attacker().rally().nationId(),battle.happenedAt()+1000);
        board.registerNation(battle.attacker().rally().nationId());board.registerNation(battle.defender().rally().nationId());
        assertThat(wars.insertIfNoneActive(board)).isTrue();
        var warMembers=new ArrayList<WarScoreBoard.BattleMember>();
        addWarMembers(warMembers,battle.attacker(),battle.result().atkKillsByMember());
        addWarMembers(warMembers,battle.defender(),battle.result().defKillsByMember());
        assertThat(wars.reserveBattle(battle.target().targetId(),1,plan.operationId(),battle.happenedAt(),warMembers)).isPresent();

        var armies=new MongoArmyStore(db.template());
        var marches=new MongoMarchStore(db.template(),32);var originalMarches=new java.util.LinkedHashMap<String,March.Snapshot>();
        var originalVersions=new java.util.LinkedHashMap<String,Long>();
        for(var side:List.of(battle.attacker(),battle.defender())) {
            for(String player:side.currentMembers().keySet()) {
                var army=new ArmyState();army.add("unit_infantry_t4",100);assertThat(armies.insertIfAbsent(player,army)).isTrue();
            }
            var march=March.fromSnapshot(side.march());assertThat(marches.insertIfAbsent(march)).isTrue();
            while(marches.versionOf(march.id())<side.marchVersion())marches.save(march,marches.versionOf(march.id()));
            originalMarches.put(march.id(),march.snapshot());originalVersions.put(march.id(),marches.versionOf(march.id()));
        }

        var recovery=new StrategicCombatRecovery(operations,armies,marches);
        var hospital=recovery.recoverHospitalReceipts(operations.findByTargetId(battle.target().targetId()).orElseThrow());
        assertThat(hospital.plan()).isEqualTo(plan);
        var receipts=readReceipts(battle,new MongoArmyStore(db.template()));
        assertThat(receipts).hasSize(battle.attacker().currentMembers().size()+battle.defender().currentMembers().size());
        assertThat(recoveryFromFreshStores(plan)).isEqualTo(hospital);
        assertThat(readReceipts(battle,new MongoArmyStore(db.template()))).isEqualTo(receipts);

        var afterOperations=new MongoTargetOperationStore(db.template()).findByTargetId(battle.target().targetId()).orElseThrow();
        assertThat(afterOperations).isEqualTo(TargetOperationStore.Snapshot.combat(1,false,plan));
        var afterWar=new MongoWarStore(db.template(),rules).pendingBattleReservation(battle.happenedAt()-10,plan.operationId());
        assertThat(afterWar).isPresent();
        var afterMarches=new MongoMarchStore(db.template(),32);
        originalMarches.forEach((id,snapshot)->{
            assertThat(afterMarches.findById(id).orElseThrow().snapshot()).isEqualTo(snapshot);
            assertThat(afterMarches.versionOf(id)).isEqualTo(originalVersions.get(id));
        });
        assertThat(new MongoTargetOccupationStore(db.template()).findByTargetIds(List.of(battle.target().targetId())))
                .doesNotContainKey(battle.target().targetId());
        assertThat(new MongoMarchDueQueue(db.template()).dueBefore(battle.happenedAt()+10_000,16)).isEmpty();
    }

    @Test void npcVictoryPersistsCaptureAndNeutralRetirementInMongoBeforeMarchCompletion() {
        var battle=StrategicCombatSlotTest.battle(2382,false,true,false);
        assertThat(battle.result().winner()).isEqualTo(com.ironoath.battle.Winner.ATTACKER);
        assertThat(battle.npcSurvivors()).isNotEmpty();
        var plan=TargetOperationStore.CombatPlan.create(battle,1);var operations=new MongoTargetOperationStore(db.template());
        assertThat(operations.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,plan),0)).isTrue();
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));var warRules=new WarRulesAssembler(configs);
        var wars=new MongoWarStore(db.template(),warRules);var board=new WarScoreBoard(warRules.rules(),battle.happenedAt()-10,
                battle.attacker().rally().nationId(),battle.happenedAt()+1000);
        board.registerNation(battle.attacker().rally().nationId());board.registerNation("owned-opponent");
        assertThat(wars.insertIfNoneActive(board)).isTrue();var members=new ArrayList<WarScoreBoard.BattleMember>();
        addWarMembers(members,battle.attacker(),battle.result().atkKillsByMember());
        assertThat(wars.reserveBattle(battle.target().targetId(),1,plan.operationId(),battle.happenedAt(),members)).isPresent();

        var armies=new MongoArmyStore(db.template());
        for(String player:battle.attacker().currentMembers().keySet()) {
            var army=new ArmyState();army.add("unit_infantry_t4",100);assertThat(armies.insertIfAbsent(player,army)).isTrue();
        }
        var marches=new MongoMarchStore(db.template(),32);var attacker=March.fromSnapshot(battle.attacker().march());
        assertThat(marches.insertIfAbsent(attacker)).isTrue();
        while(marches.versionOf(attacker.id())<battle.attacker().marchVersion())marches.save(attacker,marches.versionOf(attacker.id()));
        long marchVersion=marches.versionOf(attacker.id());var marchBefore=marches.findById(attacker.id()).orElseThrow().snapshot();
        var nationRules=new NationRulesAssembler(configs);var nations=new MongoNationStore(db.template(),nationRules);
        var nation=Nation.found(battle.attacker().rally().nationId(),"进攻国",battle.attacker().rally().initiatorId(),
                "owned-attacker-alliance",0,0,100,nationRules.rules());assertThat(nations.insertIfAbsent(nation)).isTrue();
        var occupations=new MongoTargetOccupationStore(db.template());var neutral=new MongoNeutralTargetGarrisonStore(db.template());
        var recovery=new StrategicCombatRecovery(operations,armies,marches,occupations,neutral,nations);
        var current=operations.findByTargetId(battle.target().targetId()).orElseThrow();
        var hospital=recovery.recoverHospitalReceipts(current);recovery.recoverTargetState(current,hospital);

        var recoveredOwner=new MongoTargetOccupationStore(db.template()).findByTargetIds(List.of(battle.target().targetId()))
                .get(battle.target().targetId());
        assertThat(recoveredOwner.nationId()).isEqualTo(nation.id());
        assertThat(recoveredOwner.garrison()).isEqualTo(com.ironoath.web.world.TargetOccupationStore.Garrison.stationed(attacker.id()));
        var recoveredNpc=new MongoNeutralTargetGarrisonStore(db.template()).findByTargetIds(List.of(battle.target().targetId()))
                .get(battle.target().targetId());
        assertThat(recoveredNpc.phase()).isEqualTo(com.ironoath.web.world.NeutralTargetGarrisonStore.Phase.RETIRED);
        assertThat(recoveredNpc.troops()).isEmpty();
        assertThat(new MongoMarchStore(db.template(),32).versionOf(attacker.id())).isEqualTo(marchVersion);
        assertThat(new MongoMarchStore(db.template(),32).findById(attacker.id()).orElseThrow().snapshot()).isEqualTo(marchBefore);
        assertThat(new MongoTargetOperationStore(db.template()).findByTargetId(battle.target().targetId()))
                .contains(TargetOperationStore.Snapshot.combat(1,false,plan));
        assertThat(new MongoWarStore(db.template(),warRules).pendingBattleReservation(battle.happenedAt()-10,plan.operationId()))
                .isPresent();
    }

    @Test void playerDefenseKeepsFrozenOwnerInOneMongoRecoveryBoundary() {
        var battle=StrategicCombatSlotTest.battle(2399,true,true);var plan=TargetOperationStore.CombatPlan.create(battle,1);
        var operations=new MongoTargetOperationStore(db.template());
        assertThat(operations.compareAndSet(TargetOperationStore.Snapshot.combat(1,false,plan),0)).isTrue();
        var configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));var warRules=new WarRulesAssembler(configs);
        var wars=new MongoWarStore(db.template(),warRules);var board=new WarScoreBoard(warRules.rules(),battle.happenedAt()-10,
                battle.attacker().rally().nationId(),battle.happenedAt()+1000);
        board.registerNation(battle.attacker().rally().nationId());board.registerNation(battle.defender().rally().nationId());
        assertThat(wars.insertIfNoneActive(board)).isTrue();var members=new ArrayList<WarScoreBoard.BattleMember>();
        addWarMembers(members,battle.attacker(),battle.result().atkKillsByMember());
        addWarMembers(members,battle.defender(),battle.result().defKillsByMember());
        assertThat(wars.reserveBattle(battle.target().targetId(),1,plan.operationId(),battle.happenedAt(),members)).isPresent();

        var armies=new MongoArmyStore(db.template());
        for(var side:List.of(battle.attacker(),battle.defender()))for(String player:side.currentMembers().keySet()) {
            var army=new ArmyState();army.add("unit_infantry_t4",100);assertThat(armies.insertIfAbsent(player,army)).isTrue();
        }
        var marches=new MongoMarchStore(db.template(),32);var originalVersions=new java.util.LinkedHashMap<String,Long>();
        var originalMarches=new java.util.LinkedHashMap<String,March.Snapshot>();
        for(var side:List.of(battle.attacker(),battle.defender())) {
            var march=March.fromSnapshot(side.march());assertThat(marches.insertIfAbsent(march)).isTrue();
            while(marches.versionOf(march.id())<side.marchVersion())marches.save(march,marches.versionOf(march.id()));
            originalVersions.put(march.id(),marches.versionOf(march.id()));originalMarches.put(march.id(),march.snapshot());
        }
        var nationRules=new NationRulesAssembler(configs);var nations=new MongoNationStore(db.template(),nationRules);
        var attacker=Nation.found(battle.attacker().rally().nationId(),"进攻国",battle.attacker().rally().initiatorId(),
                "owned-attacker-alliance",0,0,100,nationRules.rules());assertThat(nations.insertIfAbsent(attacker)).isTrue();
        var originalOwner=battle.target().defender().occupation();var occupations=new MongoTargetOccupationStore(db.template());
        assertThat(originalOwner).isNotNull();assertThat(occupations.compareAndSet(originalOwner,0)).isTrue();
        var neutral=new MongoNeutralTargetGarrisonStore(db.template());
        var recovery=new StrategicCombatRecovery(operations,armies,marches,occupations,neutral,nations);
        var pending=operations.findByTargetId(battle.target().targetId()).orElseThrow();
        var hospital=recovery.recoverHospitalReceipts(pending);recovery.recoverTargetState(pending,hospital);

        var ownerRead=new MongoTargetOccupationStore(db.template()).findByTargetIds(List.of(battle.target().targetId())).get(battle.target().targetId());
        if(battle.result().winner()==com.ironoath.battle.Winner.ATTACKER) {
            assertThat(ownerRead.nationId()).isEqualTo(attacker.id());
            assertThat(ownerRead.garrison()).isEqualTo(com.ironoath.web.world.TargetOccupationStore.Garrison.stationed(battle.attacker().march().id()));
        } else assertThat(ownerRead).isEqualTo(originalOwner);
        var currentSlot=new MongoTargetOperationStore(db.template()).findByTargetId(battle.target().targetId()).orElseThrow();
        assertThat(currentSlot).isEqualTo(TargetOperationStore.Snapshot.combat(1,false,plan));
        assertThat(new MongoWarStore(db.template(),warRules).pendingBattleReservation(battle.happenedAt()-10,plan.operationId())).isPresent();
        var marchRead=new MongoMarchStore(db.template(),32);
        originalMarches.forEach((id,snapshot)->{
            assertThat(marchRead.findById(id).orElseThrow().snapshot()).isEqualTo(snapshot);
            assertThat(marchRead.versionOf(id)).isEqualTo(originalVersions.get(id));
        });
    }

    private static StrategicBattleHospitalSettlement recoveryFromFreshStores(TargetOperationStore.CombatPlan plan) {
        var battle=plan.battle();
        return new StrategicCombatRecovery(new MongoTargetOperationStore(db.template()),new MongoArmyStore(db.template()),
                new MongoMarchStore(db.template(),32))
                .recoverHospitalReceipts(new MongoTargetOperationStore(db.template()).findByTargetId(battle.target().targetId()).orElseThrow());
    }

    private static Map<String,ArmyState.StrategicWoundReceipt> readReceipts(CapturedStrategicBattle battle,MongoArmyStore armies) {
        var receipts=new java.util.LinkedHashMap<String,ArmyState.StrategicWoundReceipt>();
        for(var side:List.of(battle.attacker(),battle.defender()))for(String player:side.currentMembers().keySet()) {
            var receipt=armies.findByPlayerId(player).orElseThrow().snapshot().strategicWounds().get(battle.target().targetId());
            assertThat(receipt).isNotNull();receipts.put(player,receipt);
        }
        return Map.copyOf(receipts);
    }

    private static void addWarMembers(List<WarScoreBoard.BattleMember> out,StrategicRallyCasualties side,Map<String,Long> kills) {
        side.currentMembers().keySet().forEach(player->out.add(new WarScoreBoard.BattleMember(player,side.rally().nationId(),kills.get(player))));
    }
}
