package com.ironoath.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironoath.battle.*;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.march.March;
import com.ironoath.core.social.Rally;
import com.ironoath.core.world.Coord;
import com.ironoath.web.battle.BattleArmyFactory;
import com.ironoath.web.battle.BattleRulesAssembler;
import com.ironoath.web.battle.StrategicRallyCasualties;
import com.ironoath.web.battle.StrategicRallyCasualties.Side;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 真模拟与显式账务反例分开；本类不写Army/目标，不代表实际攻占。 */
class StrategicRallyCasualtiesTest {
    private static final String U="unit_infantry_t1",V="unit_archer_t4";
    private final ConfigRegistry configs=ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    private final BattleArmyFactory factory=new BattleArmyFactory(configs);
    private Map<String,Map<String,Long>> owners(long... counts) {
        var out=new LinkedHashMap<String,Map<String,Long>>();
        for(int i=0;i<counts.length;i++)out.put("P"+(i+1),Map.of(U,counts[i]));return out;
    }
    private Map<String,Long> merged(Map<String,Map<String,Long>> owners) {
        var out=new LinkedHashMap<String,Long>();owners.values().forEach(u->u.forEach((id,n)->out.merge(id,n,Math::addExact)));return out;
    }
    private Rally rally(Map<String,Map<String,Long>> owners) {
        var participants=new LinkedHashMap<String,Rally.Participant>();owners.forEach((p,u)->participants.put(p,new Rally.Participant(p,u)));
        var r=Rally.restore("frozen-rally",Rally.Scope.NATION,"N-owned","P1",owners.size(),1,0,100,
                participants,Rally.Status.PREPARING,null,256,216,"STRATEGIC_TARGET",1);
        r.depart(100);return r;
    }
    private March march(Rally rally) {
        var m=new March("frozen-march",rally.initiatorId(),Coord.of(255,216),Coord.of(256,216),100,110,
                rally.departure().mergedTroops(),List.of(),1000000,1,March.TargetType.STRATEGIC_TARGET,"gate_north",March.Action.ATTACK);
        m.attachToRally(rally.rallyId());m.arrive(110);return m;
    }
    private Map<String,Long> capacities(Collection<String> players) {
        var out=new LinkedHashMap<String,Long>();players.forEach(p->out.put(p,1000L));return out;
    }
    private BattleResult simulated(March own,Side side) {
        var ownFold=factory.fold(own.units());var otherFold=factory.fold(Map.of("unit_infantry_t4",1700L,"unit_archer_t3",900L));
        var ownSide=factory.toSide("owned",ownFold,List.of(),0,TechBonus.none(),Long.MAX_VALUE/4);
        var otherSide=factory.toSide("other",otherFold,List.of(),0,TechBonus.none(),Long.MAX_VALUE/4);
        return BattleSimulator.simulate(new BattleInput(side==Side.ATTACKER?ownSide:otherSide,
                side==Side.ATTACKER?otherSide:ownSide,TerrainType.PLAIN,2374,BattleType.PVP_RALLY,
                BattleModifier.none(),BattleModifier.none(),side==Side.ATTACKER?ownFold.stats():otherFold.stats(),
                side==Side.ATTACKER?otherFold.stats():ownFold.stats(),new BattleRulesAssembler(configs).rules(),DefenderStore.none()));
    }
    /** 显式账务夹具，不冒充simulate输出；用于小整数取整/损坏输入反证。 */
    private BattleResult accounting(Map<UnitType,Long> survivors,long dead,long wounded,long overflow) {
        return new BattleResult(Winner.DEFENDER,List.of(),0,survivors,Map.of(UnitType.INFANTRY,1L),
                dead,wounded,overflow,0,0,0,Map.of(),0,2374,List.of());
    }
    private StrategicRallyCasualties freeze(March march,Rally rally,BattleResult result,Side side) {
        var current=march.hasGarrisonOwnership()?march.garrisonTroopsByPlayer().keySet():rally.participants().keySet();
        return factory.freezeStrategicCasualties(march,7,rally,result,side,capacities(current));
    }
    private long sum(Map<String,Map<String,Long>> values) {
        return values.values().stream().flatMap(u->u.values().stream()).mapToLong(Long::longValue).sum();
    }

    @Test void realSimulatorAttackerPayloadConservesEveryOriginalMember() {
        var r=rally(owners(1500,1500,900,500,500,200));var m=march(r);var result=simulated(m,Side.ATTACKER);
        var before=m.snapshot();var p=freeze(m,r,result,Side.ATTACKER);
        assertThat(p.result()).isEqualTo(result);assertThat(p.march()).isEqualTo(before);assertThat(m.snapshot()).isEqualTo(before);
        assertThat(sum(p.lossesByPlayer())+sum(p.survivorsByPlayer())).isEqualTo(m.totalUnits());
        assertThat(sum(p.woundedByPlayer())).isEqualTo(result.atkWounded());
        assertThat(sum(p.deadByPlayer())).isEqualTo(result.atkDead());
        assertThat(p.rally().participants().keySet()).containsExactlyInAnyOrderElementsOf(r.participants().keySet());
        assertThat(p.marchVersion()).isEqualTo(7);
    }

    @Test void realSimulatorDefenderUsesDepletedCurrentOwnershipAndKeepsOriginalCaps() {
        var r=rally(owners(1500,1000));var m=march(r);var original=r.departure().mergedTroops();
        var initial=new LinkedHashMap<String,Map<String,Long>>();r.participants().forEach((p,u)->initial.put(p,u.troops()));
        m.holdStrategicVictory(initial);m.beginGarrisonDefense();m.applyLosses(Map.of(U,501L));m.finishGarrisonDefense();
        var before=m.snapshot();var p=freeze(m,r,simulated(m,Side.DEFENDER),Side.DEFENDER);
        assertThat(p.currentMembers()).isEqualTo(m.garrisonTroopsByPlayer());
        assertThat(sum(p.currentMembers())).isEqualTo(1999);
        assertThat(p.rally().departure().mergedTroops()).isEqualTo(original);
        assertThat(m.snapshot()).isEqualTo(before);
        assertThat(sum(p.lossesByPlayer())).isEqualTo(p.result().defDead()+p.result().defWounded());
    }

    @Test void smallIntegerWitnessNeverAssignsWoundedBeyondAPlayersLoss() {
        var r=rally(owners(1500,1500,900,500,500,200));var m=march(r);
        var p=freeze(m,r,accounting(Map.of(UnitType.INFANTRY,5074L),1,25,0),Side.ATTACKER);
        assertThat(sum(p.lossesByPlayer())).isEqualTo(26);assertThat(sum(p.woundedByPlayer())).isEqualTo(25);
        for(String player:p.currentMembers().keySet()) {
            long loss=p.lossesByPlayer().get(player).getOrDefault(U,0L);
            assertThat(p.woundedByPlayer().get(player).getOrDefault(U,0L)).isLessThanOrEqualTo(loss);
            assertThat(p.deadByPlayer().get(player).getOrDefault(U,0L)+p.woundedByPlayer().get(player).getOrDefault(U,0L)).isEqualTo(loss);
        }
    }

    @Test void woundIntentKeepsExistingBattleTypeOrderRatherThanLexicalUnitOrder() {
        var r=rally(Map.of("P1",Map.of(U,10L,V,10L),"P2",Map.of(U,10L,V,10L)));var m=march(r);
        var result=accounting(Map.of(UnitType.INFANTRY,10L,UnitType.ARCHER,10L),10,10,0);
        var p=freeze(m,r,result,Side.ATTACKER);
        assertThat(p.woundedByPlayer().get("P1").keySet()).containsExactly(U,V);
        assertThat(p.woundedByPlayer().get("P2").keySet()).containsExactly(U,V);
    }

    @Test void zeroLossAndAnnihilationKeepAllMembersWithoutCreatingZeroMarch() {
        var r=rally(owners(3,2));var m=march(r);
        var none=freeze(m,r,accounting(Map.of(UnitType.INFANTRY,5L),0,0,0),Side.ATTACKER);
        assertThat(sum(none.lossesByPlayer())).isZero();assertThat(none.survivorsByPlayer()).isEqualTo(owners(3,2));
        var wiped=freeze(m,r,accounting(Map.of(),2,3,0),Side.ATTACKER);
        assertThat(wiped.survivorsByPlayer().keySet()).containsExactly("P1","P2");
        assertThat(wiped.survivorsByPlayer().values()).allSatisfy(u->assertThat(u).isEmpty());
        assertThat(m.totalUnits()).isEqualTo(5);assertThat(wiped.march().units()).containsEntry(U,5L);
    }

    @Test void malformedPhysicsCannotBeSilentlyClampedOrPreApplyOneGroupHospital() {
        var r=rally(owners(3,2));var m=march(r);var before=m.snapshot();
        for(var bad:List.of(accounting(Map.of(UnitType.INFANTRY,-1L),6,0,0),
                accounting(Map.of(UnitType.INFANTRY,6L),0,0,0),accounting(Map.of(UnitType.INFANTRY,4L),0,0,0),
                accounting(Map.of(UnitType.INFANTRY,4L),1,0,1),accounting(Map.of(UnitType.ARCHER,1L),2,3,0)))
            assertThatThrownBy(()->freeze(m,r,bad,Side.ATTACKER)).isInstanceOf(IllegalArgumentException.class);
        assertThat(m.snapshot()).isEqualTo(before);
    }

    @Test void wrongRallyAndInvalidCapacityInputsDoNotMutateTheLiveBattleInputs() {
        var r=rally(owners(3,2));var m=march(r);var result=accounting(Map.of(UnitType.INFANTRY,4L),1,0,0);
        var wrong=Rally.restore("wrong",Rally.Scope.NATION,"N-owned","P1",2,1,0,100,r.participants(),
                r.status(),r.departure(),256,216,"STRATEGIC_TARGET",r.version());
        assertThatThrownBy(()->freeze(m,wrong,result,Side.ATTACKER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->factory.freezeStrategicCasualties(m,-1,r,result,Side.ATTACKER,capacities(r.memberIds()))).isInstanceOf(IllegalArgumentException.class);
        for(var bad:List.of(Map.of("P1",1L),Map.of("P1",-1L,"P2",1L),Map.of("P1",1L,"P2",1L,"foreign",1L)))
            assertThatThrownBy(()->factory.freezeStrategicCasualties(m,7,r,result,Side.ATTACKER,bad)).isInstanceOf(IllegalArgumentException.class);
        assertThat(m.totalUnits()).isEqualTo(5);assertThat(r.status()).isEqualTo(Rally.Status.DEPARTED);
    }

    @Test void payloadIsDeeplyFrozenAndOriginalAggregatesRemainIndependent() {
        var r=rally(owners(3,2));var m=march(r);
        var p=freeze(m,r,accounting(Map.of(UnitType.INFANTRY,3L),1,1,0),Side.ATTACKER);
        m.applyLosses(Map.of(U,1L));r.arrive();
        assertThat(p.march().units()).containsEntry(U,5L);assertThat(p.rally().status()).isEqualTo(Rally.Status.DEPARTED);
        assertThatThrownBy(()->p.currentMembers().get("P1").put(U,99L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->p.woundedByPlayer().get("P1").put(U,99L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->p.hospitalCapacities().put("P1",0L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->p.rally().participants().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void jsonRoundTripRetainsStoredResultsAndMemberWoundOrder() throws Exception {
        var r=rally(Map.of("P1",Map.of(U,10L,V,10L),"P2",Map.of(U,10L,V,10L)));var m=march(r);
        var p=freeze(m,r,accounting(Map.of(UnitType.INFANTRY,10L,UnitType.ARCHER,10L),10,10,0),Side.ATTACKER);
        var mapper=new ObjectMapper();var restored=mapper.readValue(mapper.writeValueAsBytes(p),StrategicRallyCasualties.class);
        assertThat(restored).isEqualTo(p);
        assertThat(restored.woundedByPlayer().get("P1").keySet()).containsExactly(U,V);
    }

    @Test void storedMemberResultsCannotCreateTroopsOrMoveWoundsToAnotherMember() {
        var r=rally(owners(3,2));var m=march(r);var p=freeze(m,r,accounting(Map.of(UnitType.INFANTRY,3L),1,1,0),Side.ATTACKER);
        var forged=new LinkedHashMap<>(p.survivorsByPlayer());forged.put("P1",Map.of(U,99L));
        assertThatThrownBy(()->new StrategicRallyCasualties(p.formatVersion(),p.side(),p.marchVersion(),p.march(),p.rally(),p.result(),p.unitTypes(),p.woundUnitOrder(),
                p.currentMembers(),p.lossesByPlayer(),forged,p.woundedByPlayer(),p.deadByPlayer(),p.hospitalCapacities()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void scopeLeaderTargetAndUndepartedRalliesCannotBeSubstituted() {
        var r=rally(owners(3,2));var m=march(r);var result=accounting(Map.of(UnitType.INFANTRY,3L),1,1,0);
        var wrongScope=Rally.restore(r.rallyId(),Rally.Scope.ALLIANCE,r.groupId(),r.initiatorId(),2,1,0,100,
                r.participants(),r.status(),r.departure(),256,216,"STRATEGIC_TARGET",r.version());
        var wrongLeader=Rally.restore(r.rallyId(),r.scope(),r.groupId(),"P2",2,1,0,100,
                r.participants(),r.status(),r.departure(),256,216,"STRATEGIC_TARGET",r.version());
        var wrongTarget=Rally.restore(r.rallyId(),r.scope(),r.groupId(),r.initiatorId(),2,1,0,100,
                r.participants(),r.status(),r.departure(),257,216,"STRATEGIC_TARGET",r.version());
        var preparing=Rally.restore(r.rallyId(),r.scope(),r.groupId(),r.initiatorId(),2,1,0,100,
                r.participants(),Rally.Status.PREPARING,null,256,216,"STRATEGIC_TARGET",r.version());
        for(var bad:List.of(wrongScope,wrongLeader,wrongTarget,preparing))
            assertThatThrownBy(()->freeze(m,bad,result,Side.ATTACKER)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void missingAttackerOwnershipCannotBeGuessedFromOriginalPromises() {
        var r=rally(owners(3,2));var m=march(r);m.applyLosses(Map.of(U,1L));var before=m.snapshot();
        assertThatThrownBy(()->freeze(m,r,accounting(Map.of(UnitType.INFANTRY,3L),1,0,0),Side.ATTACKER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(m.snapshot()).isEqualTo(before);
    }

    @Test void forgedDefenderMemberOrSharesBeyondOriginalCapsAreRejected() {
        var r=rally(owners(3,2));
        for(var bad:List.of(Map.of("P1",Map.of(U,5L)),Map.of("foreign",Map.of(U,5L)))) {
            var m=march(r);m.holdStrategicVictory(bad);var before=m.snapshot();
            assertThatThrownBy(()->freeze(m,r,simulated(m,Side.DEFENDER),Side.DEFENDER)).isInstanceOf(IllegalArgumentException.class);
            assertThat(m.snapshot()).isEqualTo(before);
        }
    }

    @Test void corruptedDepartureAndFrozenTypeMappingCannotPassConservation() {
        var r=rally(owners(3,2));var m=march(r);var result=accounting(Map.of(UnitType.INFANTRY,3L),1,1,0);
        var bad=Rally.restore(r.rallyId(),r.scope(),r.groupId(),r.initiatorId(),2,1,0,100,r.participants(),r.status(),
                new Rally.Departure(Map.of(U,99L),99,2,100),256,216,"STRATEGIC_TARGET",r.version());
        assertThatThrownBy(()->freeze(m,bad,result,Side.ATTACKER)).isInstanceOf(IllegalArgumentException.class);
        var p=freeze(m,r,result,Side.ATTACKER);
        assertThatThrownBy(()->new StrategicRallyCasualties(p.formatVersion(),p.side(),p.marchVersion(),p.march(),p.rally(),p.result(),Map.of(U,UnitType.ARCHER),p.woundUnitOrder(),
                p.currentMembers(),p.lossesByPlayer(),p.survivorsByPlayer(),p.woundedByPlayer(),p.deadByPlayer(),p.hospitalCapacities()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void storedCoherentOutcomesAreReadWithoutReroundingTheirMemberShares() throws Exception {
        var r=rally(owners(3,2));var m=march(r);var p=freeze(m,r,accounting(Map.of(UnitType.INFANTRY,3L),1,1,0),Side.ATTACKER);
        // 模拟已冻结的另一份合法整数结果。读取只核守恒，不以今天的分配算法重写历史结果。
        var stored=new StrategicRallyCasualties(p.formatVersion(),p.side(),p.marchVersion(),p.march(),p.rally(),p.result(),p.unitTypes(),p.woundUnitOrder(),p.currentMembers(),
                Map.of("P1",Map.of(U,2L),"P2",Map.of()),Map.of("P1",Map.of(U,1L),"P2",Map.of(U,2L)),
                Map.of("P1",Map.of(U,1L),"P2",Map.of()),Map.of("P1",Map.of(U,1L),"P2",Map.of()),p.hospitalCapacities());
        assertThat(stored.lossesByPlayer()).isNotEqualTo(p.lossesByPlayer());
        var mapper=new ObjectMapper();var restored=mapper.readValue(mapper.writeValueAsBytes(stored),StrategicRallyCasualties.class);
        assertThat(restored).isEqualTo(stored);
        assertThat(restored.lossesByPlayer().get("P1")).containsEntry(U,2L);
    }

    @Test void woundPriorityIsPartOfFrozenPayloadIdentity() {
        var r=rally(Map.of("P1",Map.of(U,10L,V,10L),"P2",Map.of(U,10L,V,10L)));var m=march(r);
        var p=freeze(m,r,accounting(Map.of(UnitType.INFANTRY,10L,UnitType.ARCHER,10L),10,10,0),Side.ATTACKER);
        var reversed=new LinkedHashMap<String,Map<String,Long>>();
        p.woundedByPlayer().forEach((player,counts)->{
            var flipped=new LinkedHashMap<String,Long>();flipped.put(V,counts.get(V));flipped.put(U,counts.get(U));reversed.put(player,flipped);
        });
        var changed=new StrategicRallyCasualties(p.formatVersion(),p.side(),p.marchVersion(),p.march(),p.rally(),p.result(),p.unitTypes(),List.of(V,U),
                p.currentMembers(),p.lossesByPlayer(),p.survivorsByPlayer(),reversed,p.deadByPlayer(),p.hospitalCapacities());
        assertThat(changed.woundedByPlayer()).isEqualTo(p.woundedByPlayer());
        assertThat(changed).as("相同数量但不同收容优先级不是原冻结计划").isNotEqualTo(p);
    }

    @Test void inconsistentDuplicateMissingAndExtraWoundOrderEntriesAreRejected() {
        var r=rally(Map.of("P1",Map.of(U,10L,V,10L),"P2",Map.of(U,10L,V,10L)));var m=march(r);
        var p=freeze(m,r,accounting(Map.of(UnitType.INFANTRY,10L,UnitType.ARCHER,10L),10,10,0),Side.ATTACKER);
        var flipped=new LinkedHashMap<String,Map<String,Long>>();
        p.woundedByPlayer().forEach((player,counts)->{var values=new LinkedHashMap<String,Long>();values.put(V,counts.get(V));values.put(U,counts.get(U));flipped.put(player,values);});
        assertThatThrownBy(()->new StrategicRallyCasualties(p.formatVersion(),p.side(),p.marchVersion(),p.march(),p.rally(),p.result(),p.unitTypes(),p.woundUnitOrder(),
                p.currentMembers(),p.lossesByPlayer(),p.survivorsByPlayer(),flipped,p.deadByPlayer(),p.hospitalCapacities())).isInstanceOf(IllegalArgumentException.class);
        for(var bad:List.of(List.of(U,U,V),List.of(U),List.of(U,V,"unknown")))
            assertThatThrownBy(()->new StrategicRallyCasualties(p.formatVersion(),p.side(),p.marchVersion(),p.march(),p.rally(),p.result(),p.unitTypes(),bad,
                    p.currentMembers(),p.lossesByPlayer(),p.survivorsByPlayer(),p.woundedByPlayer(),p.deadByPlayer(),p.hospitalCapacities())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->p.woundUnitOrder().add("unknown")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void sameTypeMultipleTiersKeepExplicitThreeLevelRemainderResults() {
        String high="unit_infantry_t4";
        var r=rally(Map.of("P1",Map.of(U,3L,high,1L),"P2",Map.of(U,1L,high,3L)));var m=march(r);
        var p=freeze(m,r,accounting(Map.of(UnitType.INFANTRY,3L),2,3,0),Side.ATTACKER);
        assertThat(p.lossesByPlayer()).isEqualTo(Map.of("P1",Map.of(U,2L,high,1L),"P2",Map.of(U,1L,high,1L)));
        assertThat(p.survivorsByPlayer()).isEqualTo(Map.of("P1",Map.of(U,1L),"P2",Map.of(high,2L)));
        assertThat(p.woundedByPlayer()).isEqualTo(Map.of("P1",Map.of(U,1L,high,1L),"P2",Map.of(U,1L)));
        assertThat(p.deadByPlayer()).isEqualTo(Map.of("P1",Map.of(U,1L),"P2",Map.of(high,1L)));
        assertThat(p.woundUnitOrder()).containsExactly(U,high);
    }

    @Test void explicitNullSurvivorIsRejectedWithoutMutatingLiveInputs() {
        var r=rally(owners(3,2));var m=march(r);var before=m.snapshot();long version=r.version();
        // BattleResult构造器先拒绝显式null；该坏对象不能先构造好再传入冻结器。
        assertThatThrownBy(()->accounting(Collections.singletonMap(UnitType.INFANTRY,null),0,0,0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(()->freeze(m,r,null,Side.ATTACKER)).isInstanceOf(IllegalArgumentException.class);
        assertThat(m.snapshot()).isEqualTo(before);assertThat(r.version()).isEqualTo(version);
    }

    @Test void invalidHugeInputsFailBeforeAnyRatioOverflowOrPartialMutation() {
        var participants=Map.of("P1",new Rally.Participant("P1",Map.of(U,Long.MAX_VALUE)),"P2",new Rally.Participant("P2",Map.of(U,1L)));
        assertThatThrownBy(()->new StrategicRallyCasualties.RallyIdentity("huge",Rally.Scope.NATION,"N-owned","P1",Rally.Status.DEPARTED,1,256,216,
                "STRATEGIC_TARGET",participants,new Rally.Departure(Map.of(U,Long.MAX_VALUE),Long.MAX_VALUE,2,100))).isInstanceOf(ArithmeticException.class);
        var r=rally(owners(Long.MAX_VALUE));var m=march(r);var before=m.snapshot();long version=r.version();
        assertThatThrownBy(()->freeze(m,r,accounting(Map.of(UnitType.INFANTRY,Long.MAX_VALUE-2),1,1,0),Side.ATTACKER)).isInstanceOf(ArithmeticException.class);
        assertThat(m.snapshot()).isEqualTo(before);assertThat(r.version()).isEqualTo(version);
        var two=rally(Map.of("P1",Map.of(U,3000000000L,V,3000000000L)));var large=march(two);var largeBefore=large.snapshot();
        assertThatThrownBy(()->freeze(large,two,accounting(Map.of(),6000000000L,0,0),Side.ATTACKER)).isInstanceOf(ArithmeticException.class);
        assertThat(large.snapshot()).isEqualTo(largeBefore);
    }
}
