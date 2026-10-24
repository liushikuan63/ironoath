package com.ironoath.web.store;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.march.March;
import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.web.battle.CapturedStrategicBattle;
import com.ironoath.web.battle.StrategicRallyCasualties;
import com.ironoath.web.battle.StrategicBattleHospitalSettlement;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.store.mongo.*;
import com.ironoath.web.world.StrategicCombatRecovery;
import com.ironoath.web.world.TargetOperationStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** The recovery owner replays actual Mongo Army/March/War/COMBAT receipts across fresh store instances. */
class StrategicCombatRecoveryMongoTest {
    private static TestMongo db;
    @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();}
    @AfterAll static void close(){if(db!=null)db.close();}

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
