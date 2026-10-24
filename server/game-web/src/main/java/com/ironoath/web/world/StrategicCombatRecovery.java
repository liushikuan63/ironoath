package com.ironoath.web.world;

import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.web.battle.StrategicBattleHospitalSettlement;
import com.ironoath.web.battle.StrategicRallyCasualties;
import com.ironoath.web.battle.StrategicWoundSettlement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Ordered replay owner for immutable strategic COMBAT slots. Called only while original nation/rally/target locks are held. */
@Service
public final class StrategicCombatRecovery {
    private final TargetOperationStore operations;
    private final ArmyRepository armies;
    private final MarchRepository marches;

    public StrategicCombatRecovery(TargetOperationStore operations,ArmyRepository armies,MarchRepository marches) {
        this.operations=Objects.requireNonNull(operations);this.armies=Objects.requireNonNull(armies);
        this.marches=Objects.requireNonNull(marches);
    }

    /**
     * First durable replay step: admit every original member's wounds exactly once, then materialize the result only
     * from the persisted receipts. The caller deliberately keeps COMBAT pending; ownership, March, report and War
     * steps follow in order and must all confirm before the operation can complete.
     */
    public StrategicBattleHospitalSettlement recoverHospitalReceipts(TargetOperationStore.Snapshot slot) {
        requireCurrentPending(slot);
        var plan=slot.combatPlan();var battle=plan.battle();
        if(battle.formatVersion()!=2)throw new IllegalStateException("旧COMBAT缺逐成员引擎来源，副作用前拒绝恢复");
        var sides=new ArrayList<StrategicRallyCasualties>();sides.add(battle.attacker());
        if(battle.defender()!=null)sides.add(battle.defender());
        boolean allReceipts=preflightMembers(slot,sides);
        if(!allReceipts)preflightSourceMarches(sides);

        var attacker=admitSide(slot,battle.attacker());
        var defender=battle.defender()==null?Map.<String,ArmyState.StrategicWoundReceipt>of():admitSide(slot,battle.defender());
        var result=StrategicBattleHospitalSettlement.fromReceipts(plan,attacker,defender);
        requireCurrentPending(slot);
        return result;
    }

    public boolean ownsMarch(TargetOperationStore.Snapshot slot,String marchId) {
        if(slot==null || slot.kind()!=TargetOperationStore.Kind.COMBAT || marchId==null)return false;
        var battle=slot.combatPlan().battle();
        return battle.attacker().march().id().equals(marchId)
                || battle.defender()!=null && battle.defender().march().id().equals(marchId);
    }

    private boolean preflightMembers(TargetOperationStore.Snapshot slot,List<StrategicRallyCasualties> sides) {
        var seen=new java.util.HashSet<String>();
        boolean allReceipts=true;
        for(var side:sides)for(String player:side.currentMembers().keySet()) {
            if(!seen.add(player))throw new IllegalStateException("原COMBAT攻守成员重叠，拒绝重复收容伤兵");
            var army=armies.findByPlayerId(player).orElseThrow(
                    ()->new IllegalStateException("原COMBAT成员军队档缺失，不能创建空档掩盖伤兵来源"));
            var old=army.snapshot().strategicWounds().get(slot.targetId());
            if(old==null || old.sequence()<slot.sequence())allReceipts=false;
            if(old!=null)requireSameWoundRequest(old,slot,side,player);
        }
        return allReceipts;
    }

    /** 首次伤兵写前仍须看到原March兵权；全体医院回执已存在时允许重放后续恢复阶段。 */
    private void preflightSourceMarches(List<StrategicRallyCasualties> sides) {
        for(var side:sides) {
            String id=March.fromSnapshot(side.march()).id();long before=marches.versionOf(id);
            var current=marches.findById(id).orElseThrow(()->new IllegalStateException("原COMBAT行军缺失，拒绝收容伤兵"));
            long after=marches.versionOf(id);
            if(before!=side.marchVersion() || after!=before || !current.snapshot().equals(side.march()))
                throw new IllegalStateException("原COMBAT行军版本/兵权变化，拒绝在医院步骤继续");
        }
    }

    private Map<String,ArmyState.StrategicWoundReceipt> admitSide(TargetOperationStore.Snapshot slot,
            StrategicRallyCasualties side) {
        var receipts=new LinkedHashMap<String,ArmyState.StrategicWoundReceipt>();
        for(String player:side.currentMembers().keySet()) {
            long capacity=side.hospitalCapacities().get(player);
            var returned=StrategicWoundSettlement.admit(armies,player,slot.targetId(),slot.sequence(),slot.operationId(),
                    side.woundedByPlayer().get(player),capacity);
            var persisted=armies.findByPlayerId(player).orElseThrow(
                    ()->new IllegalStateException("医院回执写入后原成员军队档无法读回"))
                    .snapshot().strategicWounds().get(slot.targetId());
            if(!returned.equals(persisted))throw new IllegalStateException("原成员医院回执未完整独立读回");
            requireSameWoundRequest(persisted,slot,side,player);
            receipts.put(player,persisted);
        }
        return Map.copyOf(receipts);
    }

    private static void requireSameWoundRequest(ArmyState.StrategicWoundReceipt receipt,
            TargetOperationStore.Snapshot slot,StrategicRallyCasualties side,String player) {
        if(!receipt.targetId().equals(slot.targetId()) || receipt.sequence()>slot.sequence())
            throw new IllegalStateException("既有医院回执属于更晚的COMBAT代次，拒绝覆盖");
        if(receipt.sequence()<slot.sequence())return;
        var requested=side.woundedByPlayer().get(player);
        boolean woundsMatch=receipt.policy()==ArmyState.StrategicWoundPolicy.LEGACY_ORDERED
                ?new ArrayList<>(receipt.requested().entrySet()).equals(new ArrayList<>(requested.entrySet()))
                :receipt.requested().equals(requested);
        if(!receipt.operationId().equals(slot.operationId())
                || receipt.hospitalCapacity()!=side.hospitalCapacities().get(player) || !woundsMatch)
            throw new IllegalStateException("既有医院回执与原COMBAT成员/目标/代次/容量不一致");
    }

    private void requireCurrentPending(TargetOperationStore.Snapshot expected) {
        if(expected==null || expected.kind()!=TargetOperationStore.Kind.COMBAT || expected.completed()
                || !operations.findByTargetId(expected.targetId()).filter(expected::equals).isPresent())
            throw new IllegalStateException("原COMBAT操作槽已变化，恢复必须重读完整冻结计划");
    }
}
