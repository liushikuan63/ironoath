package com.ironoath.web.store;

import com.ironoath.battle.BattleResult;
import com.ironoath.core.army.*;
import com.ironoath.web.battle.*;
import com.ironoath.web.world.TargetOperationStore;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.mongo.MongoArmyStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 实际内核原战果+真实Army医院回执；各Mongo库均由TestMongo独立拥有。 */
class StrategicBattleHospitalSettlementTest {
    enum Backend { MEMORY,MONGO }
    private static TestMongo db;
    @BeforeAll static void open(){db=TestMongo.tryOpen();assertThat(db).isNotNull();}
    @AfterAll static void close(){if(db!=null)db.close();}
    @BeforeEach void clear(){db.template().remove(new org.springframework.data.mongodb.core.query.Query(),"army");}
    private ArmyRepository store(Backend b){return b==Backend.MEMORY?new InMemoryArmyStore():new MongoArmyStore(db.template());}
    private static long sum(Map<String,Long> units){return units.values().stream().mapToLong(Long::longValue).sum();}
    private Map<String,ArmyState.StrategicWoundReceipt> admit(ArmyRepository repo,TargetOperationStore.CombatPlan plan,StrategicRallyCasualties side) {
        var result=new LinkedHashMap<String,ArmyState.StrategicWoundReceipt>();if(side==null)return result;
        for(String player:side.currentMembers().keySet()) {
            long cap=side.hospitalCapacities().get(player);assertThat(cap).isGreaterThan(7);
            var army=new ArmyState();army.add("unit_infantry_t4",40);army.admitWounded(Map.of("unit_cavalry_t4",cap-7),cap);
            assertThat(repo.insertIfAbsent(player,army)).isTrue();
            result.put(player,StrategicWoundSettlement.admit(repo,player,plan.battle().target().targetId(),plan.sequence(),plan.operationId(),side.woundedByPlayer().get(player),cap));
        }return result;
    }

    @ParameterizedTest @CsvSource({"MEMORY,false","MONGO,false","MEMORY,true","MONGO,true"})
    void finalResultConsumesActualPerMemberAdmissionsAndKeepsRawBattle(Backend backend,boolean playerDefender) {
        var original=StrategicCombatSlotTest.battle(2381,playerDefender,true);var plan=TargetOperationStore.CombatPlan.create(original,2);var repo=store(backend);
        var attacks=admit(repo,plan,original.attacker());var defenses=admit(repo,plan,original.defender());
        var settled=StrategicBattleHospitalSettlement.fromReceipts(plan,attacks,defenses);var raw=original.result();var actual=settled.result();
        assertThat(actual.atkKillsByMember()).isEqualTo(raw.atkKillsByMember());
        assertThat(actual.defKillsByMember()).isEqualTo(raw.defKillsByMember());
        long attackAdmitted=attacks.values().stream().mapToLong(x->sum(x.admitted())).sum();long attackOverflow=attacks.values().stream().mapToLong(ArmyState.StrategicWoundReceipt::overflowDead).sum();
        assertThat(raw.atkWounded()).isPositive();assertThat(actual.atkWounded()).isEqualTo(attackAdmitted);
        assertThat(actual.atkOverflowDead()).isEqualTo(attackOverflow);assertThat(actual.atkDead()).isEqualTo(raw.atkDead()+attackOverflow);
        assertThat(actual.atkDead()+actual.atkWounded()).isEqualTo(raw.atkDead()+raw.atkWounded());
        if(playerDefender) {
            assertThat(actual.defWounded()).isEqualTo(defenses.values().stream().mapToLong(x->sum(x.admitted())).sum());
            assertThat(actual.defOverflowDead()).isEqualTo(defenses.values().stream().mapToLong(ArmyState.StrategicWoundReceipt::overflowDead).sum());
        } else {
            assertThat(raw.defWounded()).isPositive();assertThat(actual.defWounded()).isZero();
            assertThat(actual.defDead()).isEqualTo(raw.defDead()+raw.defWounded());assertThat(actual.defOverflowDead()).isEqualTo(raw.defWounded());
        }
        assertThat(actual.defDead()+actual.defWounded()).isEqualTo(raw.defDead()+raw.defWounded());
        assertThat(actual.rounds()).isEqualTo(raw.rounds());assertThat(actual.attritionLog()).isEqualTo(raw.attritionLog());
        assertThat(actual.seed()).isEqualTo(raw.seed());assertThat(actual.winner()).isEqualTo(raw.winner());assertThat(actual.totalRounds()).isEqualTo(raw.totalRounds());
        assertThat(actual.atkSurvivors()).isEqualTo(raw.atkSurvivors());assertThat(actual.defSurvivors()).isEqualTo(raw.defSurvivors());
        assertThat(actual.loot()).isEqualTo(raw.loot());assertThat(actual.lootCapacity()).isEqualTo(raw.lootCapacity());
        assertThat(original.result()).isEqualTo(raw);assertThat(raw.atkOverflowDead()).isZero();assertThat(raw.defOverflowDead()).isZero();
        attacks.clear();assertThat(settled.attackerReceipts()).hasSize(original.attacker().currentMembers().size());
        assertThatThrownBy(()->settled.attackerReceipts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(settled.result()).isEqualTo(actual);
        for(String player:original.attacker().currentMembers().keySet())assertThat(repo.findByPlayerId(player).orElseThrow().totalTroops()).isEqualTo(40);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void treatmentAndRepositoryRebuildCannotReallocatePreviousResult(Backend backend) {
        var original=StrategicCombatSlotTest.battle(2381,false,true);var plan=TargetOperationStore.CombatPlan.create(original,2);var repo=store(backend);
        var attacks=admit(repo,plan,original.attacker());var before=StrategicBattleHospitalSettlement.fromReceipts(plan,attacks,Map.of()).result();
        for(String player:attacks.keySet()) {
            var army=repo.findByPlayerId(player).orElseThrow();army.startTreatment(1,Map.of(),100);army.collectTreated(1100);repo.save(player,army,repo.versionOf(player));
        }
        if(backend==Backend.MONGO)repo=new MongoArmyStore(db.template());
        var persisted=new LinkedHashMap<String,ArmyState.StrategicWoundReceipt>();
        for(String player:attacks.keySet()) {
            var army=repo.findByPlayerId(player).orElseThrow();assertThat(army.totalWounded()).isZero();persisted.put(player,army.snapshot().strategicWounds().get("gate_north"));
        }
        assertThat(StrategicBattleHospitalSettlement.fromReceipts(plan,persisted,Map.of()).result()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"target","operation","sequence","capacity","requested","missing","extra","npc-hospital"})
    void mismatchedOrMissingArmyReceiptCannotBecomeFinalReport(String mismatch) {
        var original=StrategicCombatSlotTest.battle(2381,false,true);var plan=TargetOperationStore.CombatPlan.create(original,2);
        var receipts=admit(new InMemoryArmyStore(),plan,original.attacker());var old=receipts.get("owned-leader");
        var changedRequested=new LinkedHashMap<>(old.requested());long overflow=old.overflowDead();
        if(mismatch.equals("requested")){var first=changedRequested.keySet().iterator().next();changedRequested.put(first,changedRequested.get(first)+1);overflow++;}
        var changed=new ArmyState.StrategicWoundReceipt(mismatch.equals("target")?"gate_south":old.targetId(),
                mismatch.equals("operation")?"battle:other":old.operationId(),mismatch.equals("sequence")?3:old.sequence(),
                changedRequested,old.admitted(),mismatch.equals("capacity")?old.hospitalCapacity()+1:old.hospitalCapacity(),overflow,old.policy());
        receipts.put("owned-leader",changed);
        if(mismatch.equals("missing"))receipts.remove("owned-tiny");if(mismatch.equals("extra"))receipts.put("intruder",old);
        var defense=mismatch.equals("npc-hospital")?Map.of("npc",old):Map.<String,ArmyState.StrategicWoundReceipt>of();
        assertThatThrownBy(()->StrategicBattleHospitalSettlement.fromReceipts(plan,receipts,defense)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void zeroWoundMemberStillNeedsItsActualZeroReceipt() {
        var original=StrategicCombatSlotTest.battle(2381,false,true);assertThat(original.attacker().woundedByPlayer().get("owned-tiny")).isEmpty();
        var plan=TargetOperationStore.CombatPlan.create(original,2);var receipts=admit(new InMemoryArmyStore(),plan,original.attacker());
        assertThat(receipts.get("owned-tiny").requested()).isEmpty();
        assertThat(StrategicBattleHospitalSettlement.fromReceipts(plan,receipts,Map.of()).result()).isNotNull();
        receipts.remove("owned-tiny");assertThatThrownBy(()->StrategicBattleHospitalSettlement.fromReceipts(plan,receipts,Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void legacyActualAdmissionAndRequestOrderArePreservedWithoutProportionalReplay() {
        var original=StrategicCombatSlotTest.battle(2381,false,true);var plan=TargetOperationStore.CombatPlan.create(original,2);
        var receipts=admit(new InMemoryArmyStore(),plan,original.attacker());var old=receipts.get("owned-leader");
        assertThat(old.requested()).hasSize(2);String first=old.requested().keySet().iterator().next();assertThat(old.requested().get(first)).isGreaterThan(7);
        // 协议夹具表示旧档已实际按顺序收容的7兵，不能再用新比例重分这份历史结果。
        var legacy=new ArmyState.StrategicWoundReceipt(old.targetId(),old.operationId(),old.sequence(),old.requested(),Map.of(first,7L),old.hospitalCapacity(),sum(old.requested())-7,ArmyState.StrategicWoundPolicy.LEGACY_ORDERED);
        receipts.put("owned-leader",legacy);var settled=StrategicBattleHospitalSettlement.fromReceipts(plan,receipts,Map.of());
        assertThat(settled.attackerReceipts().get("owned-leader")).isEqualTo(legacy);assertThat(settled.result().atkWounded()).isEqualTo(7);
        var entries=new ArrayList<>(old.requested().entrySet());Collections.reverse(entries);var reversed=new LinkedHashMap<String,Long>();entries.forEach(x->reversed.put(x.getKey(),x.getValue()));
        receipts.put("owned-leader",new ArmyState.StrategicWoundReceipt(legacy.targetId(),legacy.operationId(),legacy.sequence(),reversed,legacy.admitted(),legacy.hospitalCapacity(),legacy.overflowDead(),legacy.policy()));
        assertThatThrownBy(()->StrategicBattleHospitalSettlement.fromReceipts(plan,receipts,Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void jsonRoundTripRestoresBoundActualReceiptsAndRejectsArbitraryFinalResult()throws Exception {
        var original=StrategicCombatSlotTest.battle(2381,false,true);var plan=TargetOperationStore.CombatPlan.create(original,2);
        var settled=StrategicBattleHospitalSettlement.fromReceipts(plan,admit(new InMemoryArmyStore(),plan,original.attacker()),Map.of());
        var mapper=com.fasterxml.jackson.databind.json.JsonMapper.builder().disable(com.fasterxml.jackson.databind.MapperFeature.AUTO_DETECT_IS_GETTERS).build();
        String json=mapper.writeValueAsString(settled);var loaded=mapper.readValue(json,StrategicBattleHospitalSettlement.class);
        assertThat(loaded).isEqualTo(settled);assertThat(loaded.result()).isEqualTo(settled.result());
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(json);assertThat(node.has("result")).isFalse();node.set("result",mapper.valueToTree(original.result()));
        assertThatThrownBy(()->mapper.treeToValue(node,StrategicBattleHospitalSettlement.class)).isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
    }

    @ParameterizedTest @EnumSource(Backend.class)
    void playersWithoutHospitalStillRequireActualZeroAdmissionReceipts(Backend backend) {
        var original=StrategicCombatSlotTest.battle(2381,true);var plan=TargetOperationStore.CombatPlan.create(original,2);var repo=store(backend);
        var attacks=new LinkedHashMap<String,ArmyState.StrategicWoundReceipt>();var defenses=new LinkedHashMap<String,ArmyState.StrategicWoundReceipt>();
        for(var side:List.of(original.attacker(),original.defender()))for(String player:side.currentMembers().keySet()) {
            assertThat(side.hospitalCapacities().get(player)).isZero();assertThat(repo.insertIfAbsent(player,new ArmyState())).isTrue();
            var receipt=StrategicWoundSettlement.admit(repo,player,"gate_north",plan.sequence(),plan.operationId(),side.woundedByPlayer().get(player),0);
            assertThat(receipt.admitted()).isEmpty();
            (side.side()==StrategicRallyCasualties.Side.ATTACKER?attacks:defenses).put(player,receipt);
        }
        var actual=StrategicBattleHospitalSettlement.fromReceipts(plan,attacks,defenses).result();
        assertThat(actual.atkWounded()).isZero();assertThat(actual.defWounded()).isZero();
        assertThat(actual.atkDead()).isEqualTo(original.result().atkDead()+original.result().atkWounded());
        assertThat(actual.defDead()).isEqualTo(original.result().defDead()+original.result().defWounded());
    }
}
