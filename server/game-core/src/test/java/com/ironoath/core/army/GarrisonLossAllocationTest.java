package com.ironoath.core.army;

import com.ironoath.core.march.March;
import com.ironoath.core.world.Coord;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GarrisonLossAllocationTest {
    private static final String U="unit_infantry_t1", V="unit_archer_t4";
    private Map<String,Map<String,Long>> owners(long... counts) {
        var out=new LinkedHashMap<String,Map<String,Long>>();
        for(int i=0;i<counts.length;i++)out.put("P"+(i+1),new LinkedHashMap<>(Map.of(U,counts[i])));
        return out;
    }
    private long sum(Map<String,Map<String,Long>> all,String unit) {
        return all.values().stream().mapToLong(u->u.getOrDefault(unit,0L)).sum();
    }

    @Test void independentWoundedApportionmentHasARealCounterexample() {
        var current=owners(1500,1500,900,500,500,200);
        var weights=new LinkedHashMap<String,Long>();current.forEach((p,u)->weights.put(p,u.get(U)));
        var oldLosses=TierSplit.splitProportionally(weights,26);
        var oldWounds=TierSplit.splitProportionally(weights,25);
        assertThat(oldWounds.get("P4")).isGreaterThan(oldLosses.get("P4"));
        var plan=GarrisonLossAllocation.plan(current,Map.of(U,26L));
        var result=plan.withWounded(Map.of(U,25L));
        assertThat(sum(result.woundedByPlayer(),U)).isEqualTo(25);
        assertThat(sum(result.deadByPlayer(),U)).isEqualTo(1);
        for(String p:current.keySet()) {
            long loss=plan.lossesByPlayer().get(p).getOrDefault(U,0L);
            long wounds=result.woundedByPlayer().get(p).getOrDefault(U,0L);
            assertThat(wounds).isLessThanOrEqualTo(loss);
            assertThat(wounds+result.deadByPlayer().get(p).getOrDefault(U,0L)).isEqualTo(loss);
        }
    }

    @Test void multipleTiersNeverPayEachOthersLossOrInjury() {
        var current=Map.of("A",Map.of(U,10L,V,1L),"B",Map.of(U,2L,V,5L));
        var plan=GarrisonLossAllocation.plan(current,Map.of(U,5L,V,4L));
        var result=plan.withWounded(Map.of(U,3L,V,2L));
        assertThat(sum(plan.survivorsByPlayer(),U)).isEqualTo(7);
        assertThat(sum(plan.survivorsByPlayer(),V)).isEqualTo(2);
        assertThat(sum(result.woundedByPlayer(),U)).isEqualTo(3);
        assertThat(sum(result.woundedByPlayer(),V)).isEqualTo(2);
        assertThat(sum(result.deadByPlayer(),U)).isEqualTo(2);
        assertThat(sum(result.deadByPlayer(),V)).isEqualTo(2);
    }

    @Test void permutationAndTiesHaveStablePlayerAndUnitOrder() {
        var a=new LinkedHashMap<String,Map<String,Long>>();
        a.put("P2",Map.of(V,1L,U,1L));a.put("P10",Map.of(U,1L,V,1L));
        var b=new LinkedHashMap<String,Map<String,Long>>();b.put("P10",a.get("P10"));b.put("P2",a.get("P2"));
        var first=GarrisonLossAllocation.plan(a,Map.of(V,1L,U,1L));
        var second=GarrisonLossAllocation.plan(b,Map.of(U,1L,V,1L));
        assertThat(first.lossesByPlayer()).isEqualTo(second.lossesByPlayer());
        assertThat(first.lossesByPlayer().keySet()).containsExactly("P10","P2");
        assertThat(first.lossesByPlayer().get("P10")).containsEntry(U,1L).containsEntry(V,1L);
        assertThat(first.lossesByPlayer().get("P2")).isEmpty();
    }

    @Test void annihilationAndZeroLossRetainEmptyMemberIdentity() {
        var current=new LinkedHashMap<String,Map<String,Long>>();current.put("empty",Map.of());current.put("last",Map.of(U,2L));
        var plan=GarrisonLossAllocation.plan(current,Map.of(U,2L));
        assertThat(plan.survivorsByPlayer()).containsEntry("empty",Map.of()).containsEntry("last",Map.of());
        assertThat(plan.withWounded(Map.of()).deadByPlayer().get("last")).containsEntry(U,2L);
        var unchanged=GarrisonLossAllocation.plan(current,Map.of());
        assertThat(unchanged.survivorsByPlayer()).isEqualTo(current);
        assertThat(unchanged.withWounded(Map.of()).woundedByPlayer()).containsEntry("empty",Map.of());
    }

    @Test void outputsAreDeeplyFrozenAndInputsAreNeverRetained() {
        var current=owners(3,2);var loss=new LinkedHashMap<>(Map.of(U,2L));
        var plan=GarrisonLossAllocation.plan(current,loss);
        current.get("P1").put(U,999L);loss.put(U,99L);
        assertThat(sum(plan.survivorsByPlayer(),U)).isEqualTo(3);
        assertThatThrownBy(()->plan.survivorsByPlayer().put("X",Map.of())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->plan.lossesByPlayer().get("P1").put(U,99L)).isInstanceOf(UnsupportedOperationException.class);
        var wounds=new LinkedHashMap<>(Map.of(U,1L));var result=plan.withWounded(wounds);wounds.put(U,0L);
        assertThat(sum(result.woundedByPlayer(),U)).isEqualTo(1);
        assertThatThrownBy(()->result.woundedByPlayer().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->result.deadByPlayer().get("P1").put(U,99L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void invalidLossesAndOwnersCannotPartiallyChangeInput() {
        var current=owners(3,2);var original=owners(3,2);
        for(var bad:List.of(Map.of(U,-1L),Map.of(U,6L),Map.of("unknown",1L)))
            assertThatThrownBy(()->GarrisonLossAllocation.plan(current,bad)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->GarrisonLossAllocation.plan(null,Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->GarrisonLossAllocation.plan(current,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->GarrisonLossAllocation.plan(Map.of(" ",Map.of(U,1L)),Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->GarrisonLossAllocation.plan(Map.of("P",Map.of(U,-1L)),Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(current).isEqualTo(original);
    }

    @Test void arithmeticOverflowFailsClosedWithoutChangingOwners() {
        var current=owners(Long.MAX_VALUE,1);var before=owners(Long.MAX_VALUE,1);
        assertThatThrownBy(()->GarrisonLossAllocation.plan(current,Map.of(U,1L))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(()->GarrisonLossAllocation.plan(owners(Long.MAX_VALUE),Map.of(U,2L))).isInstanceOf(ArithmeticException.class);
        assertThat(current).isEqualTo(before);
    }

    @Test void invalidWoundsLeaveAnExistingPlanReusable() {
        var plan=GarrisonLossAllocation.plan(owners(3,2),Map.of(U,2L));
        for(var bad:List.of(Map.of(U,-1L),Map.of(U,3L),Map.of("unknown",1L)))
            assertThatThrownBy(()->plan.withWounded(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->plan.withWounded(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sum(plan.withWounded(Map.of(U,2L)).woundedByPlayer(),U)).isEqualTo(2);
        assertThat(sum(plan.survivorsByPlayer(),U)).isEqualTo(3);
    }

    @Test void seededCasesConserveEveryMemberAndTier() {
        var random=new Random(2373);
        for(int scenario=0;scenario<200;scenario++) {
            var current=new LinkedHashMap<String,Map<String,Long>>();
            for(int player=0;player<6;player++)current.put("P"+player,Map.of(U,(long)random.nextInt(51),V,(long)random.nextInt(51)));
            var losses=new LinkedHashMap<String,Long>();var wounded=new LinkedHashMap<String,Long>();
            for(String unit:List.of(U,V)) {long loss=random.nextInt((int)sum(current,unit)+1);losses.put(unit,loss);wounded.put(unit,(long)random.nextInt((int)loss+1));}
            var plan=GarrisonLossAllocation.plan(current,losses);var result=plan.withWounded(wounded);
            for(String unit:List.of(U,V)) {
                assertThat(sum(plan.lossesByPlayer(),unit)).isEqualTo(losses.get(unit));
                assertThat(sum(result.woundedByPlayer(),unit)).isEqualTo(wounded.get(unit));
                for(String player:current.keySet()) {
                    long loss=plan.lossesByPlayer().get(player).getOrDefault(unit,0L);
                    assertThat(plan.survivorsByPlayer().get(player).getOrDefault(unit,0L)+loss).isEqualTo(current.get(player).get(unit));
                    assertThat(result.woundedByPlayer().get(player).getOrDefault(unit,0L)+result.deadByPlayer().get(player).getOrDefault(unit,0L)).isEqualTo(loss);
                }
            }
        }
    }

    @Test void realMarchRepeatedDefenseUsesExactlyThePlannedCurrentShares() {
        var current=owners(8,8,5,2,2,1);
        var march=new March("g","P1",Coord.of(10,10),Coord.of(20,20),100,110,Map.of(U,26L),List.of(),1000,1,
                March.TargetType.STRATEGIC_TARGET,"gate_north",March.Action.ATTACK);
        march.attachToRally("r");march.arrive(110);march.holdStrategicVictory(current);
        for(int round=0;round<10;round++) {
            var expected=GarrisonLossAllocation.plan(march.garrisonTroopsByPlayer(),Map.of(U,1L));
            march.beginGarrisonDefense();assertThat(march.applyLosses(Map.of(U,1L))).containsEntry(U,1L);
            assertThat(march.garrisonTroopsByPlayer()).isEqualTo(expected.survivorsByPlayer());
            march.finishGarrisonDefense();march=March.fromSnapshot(march.snapshot());
        }
        assertThat(march.totalUnits()).isEqualTo(16);
        march.beginGarrisonDefense();assertThat(march.applyLosses(Map.of(U,999L))).containsEntry(U,16L);
        assertThat(march.garrisonTroopsByPlayer().values()).allSatisfy(u->assertThat(u).isEmpty());
    }

    @Test void realMarchMatchesExplicitPreExtractionMultiTierShares() {
        var current=Map.of("A",Map.of(U,10L,V,1L),"B",Map.of(U,2L,V,5L),"C",Map.<String,Long>of());
        var march=new March("compat","A",Coord.of(10,10),Coord.of(20,20),100,110,Map.of(U,12L,V,6L),List.of(),1000,1,
                March.TargetType.STRATEGIC_TARGET,"gate_north",March.Action.ATTACK);
        march.attachToRally("compat-rally");march.arrive(110);march.holdStrategicVictory(current);
        march.beginGarrisonDefense();march.applyLosses(Map.of(U,5L,V,4L));
        // 直接写死旧最大余数分配结果，不调用规划器生成expected。
        assertThat(march.garrisonTroopsByPlayer()).isEqualTo(Map.of("A",Map.of(U,6L),"B",Map.of(U,1L,V,2L),"C",Map.of()));
        march.finishGarrisonDefense();march=March.fromSnapshot(march.snapshot());
        march.beginGarrisonDefense();march.applyLosses(Map.of(U,2L,V,1L));
        assertThat(march.garrisonTroopsByPlayer()).isEqualTo(Map.of("A",Map.of(U,4L),"B",Map.of(U,1L,V,1L),"C",Map.of()));
        assertThat(march.totalUnits()).isEqualTo(6);
    }

    @Test void malformedDetailsAndLaterTierFailureLeaveInputsAndPlansIntact() {
        var current=Map.of("A",Map.of(U,1L,V,5L));
        for(var details:List.of(Map.of(" ",1L),Collections.singletonMap(U,(Long)null),Collections.singletonMap((String)null,1L)))
            assertThatThrownBy(()->GarrisonLossAllocation.plan(Map.of("A",details),Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->GarrisonLossAllocation.plan(Collections.singletonMap("A",null),Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->GarrisonLossAllocation.plan(Collections.singletonMap(null,Map.of(U,1L)),Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->GarrisonLossAllocation.plan(current,Map.of(V,1L,U,2L))).isInstanceOf(IllegalArgumentException.class);
        assertThat(current.get("A")).containsExactlyInAnyOrderEntriesOf(Map.of(U,1L,V,5L));
        var plan=GarrisonLossAllocation.plan(current,Map.of(V,2L,U,1L));
        assertThatThrownBy(()->plan.withWounded(Map.of(V,1L,U,2L))).isInstanceOf(IllegalArgumentException.class);
        assertThat(plan.survivorsByPlayer().get("A")).containsExactlyInAnyOrderEntriesOf(Map.of(V,3L));
        assertThat(plan.withWounded(Map.of(V,1L,U,1L)).woundedByPlayer().get("A")).containsExactlyInAnyOrderEntriesOf(Map.of(V,1L,U,1L));
    }

    @Test void survivorAndWoundedInnerMapsAlsoRejectWrites() {
        var plan=GarrisonLossAllocation.plan(owners(3,2),Map.of(U,2L));
        assertThatThrownBy(()->plan.survivorsByPlayer().get("P1").put(U,99L)).isInstanceOf(UnsupportedOperationException.class);
        var result=plan.withWounded(Map.of(U,1L));
        assertThatThrownBy(()->result.woundedByPlayer().get("P1").put(U,99L)).isInstanceOf(UnsupportedOperationException.class);
    }
}
