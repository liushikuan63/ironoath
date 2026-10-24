package com.ironoath.web.world;

import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.nation.Nation;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.battle.StrategicBattleHospitalSettlement;
import com.ironoath.web.battle.StrategicRallyCasualties;
import com.ironoath.web.battle.StrategicBattleMarchOutcome;
import com.ironoath.web.battle.StrategicWoundSettlement;
import com.ironoath.web.battle.BattleReport;
import com.ironoath.web.battle.BattleReportService;
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
    private final TargetOccupationStore occupations;
    private final NeutralTargetGarrisonStore neutralGarrisons;
    private final NationStore nations;
    private final MarchDueQueue due;
    private final BattleReportService reports;

    public StrategicCombatRecovery(TargetOperationStore operations,ArmyRepository armies,MarchRepository marches) {
        this(operations,armies,marches,null,null,null,null);
    }

    public StrategicCombatRecovery(TargetOperationStore operations,ArmyRepository armies,MarchRepository marches,
            TargetOccupationStore occupations,NeutralTargetGarrisonStore neutralGarrisons,NationStore nations) {
        this(operations,armies,marches,occupations,neutralGarrisons,nations,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StrategicCombatRecovery(TargetOperationStore operations,ArmyRepository armies,MarchRepository marches,
            TargetOccupationStore occupations,NeutralTargetGarrisonStore neutralGarrisons,NationStore nations,
            MarchDueQueue due) {
        this(operations,armies,marches,occupations,neutralGarrisons,nations,due,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StrategicCombatRecovery(TargetOperationStore operations,ArmyRepository armies,MarchRepository marches,
            TargetOccupationStore occupations,NeutralTargetGarrisonStore neutralGarrisons,NationStore nations,
            MarchDueQueue due,BattleReportService reports) {
        this.operations=Objects.requireNonNull(operations);this.armies=Objects.requireNonNull(armies);
        this.marches=Objects.requireNonNull(marches);
        this.occupations=occupations;this.neutralGarrisons=neutralGarrisons;this.nations=nations;
        this.due=due;this.reports=reports;
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

    /** Resolve the target only after all hospital receipts exist; this remains a separate durable step from March writes. */
    public void recoverTargetState(TargetOperationStore.Snapshot slot,StrategicBattleHospitalSettlement hospital) {
        requireCurrentPending(slot);
        if(occupations==null || neutralGarrisons==null || nations==null)
            throw new IllegalStateException("战略目标恢复仓储未装配，保留原COMBAT待后续恢复");
        if(hospital==null || !hospital.plan().equals(slot.combatPlan()))
            throw new IllegalArgumentException("医院结果不属于当前pending COMBAT计划");
        var battle=slot.combatPlan().battle();var result=hospital.result();
        boolean attackerAlive=hasMemberTroops(battle.attacker().survivorsByPlayer());
        boolean defenderAlive=battle.defender()==null?hasTroops(battle.npcSurvivors()):hasMemberTroops(battle.defender().survivorsByPlayer());
        boolean attackerWon=result.winner()==com.ironoath.battle.Winner.ATTACKER;
        if(attackerWon && !attackerAlive)throw new IllegalStateException("原战果判攻方获胜但无幸存兵，拒绝授予目标");
        if(!attackerWon && !defenderAlive)throw new IllegalStateException("原战果未判攻方胜且守方无幸存兵，拒绝推断空目标归属");

        var originalOwner=battle.target().defender().occupation();
        var originalNeutral=battle.target().defender().neutral();
        if(battle.defender()==null) {
            if(!battle.target().defender().initialNpc() && originalNeutral==null)
                throw new IllegalStateException("原COMBAT既无玩家守军又无冻结NPC守军，拒绝推断无抵抗目标");
            resolveNpcTarget(slot,originalOwner,originalNeutral,attackerWon);
        } else {
            if(originalOwner==null)throw new IllegalStateException("玩家守方缺少原目标归属，拒绝覆盖目标");
            if(attackerWon)resolveAttackerOwnership(slot,originalOwner);
            else if(!Objects.equals(currentOwner(slot.targetId()),originalOwner))
                throw new IllegalStateException("守方胜利后的原目标归属已被其他操作修改");
        }
        requireCurrentPending(slot);
    }

    /** Freeze exact HOLD/RETURN/ANNIHILATED outcomes before any player March or due-queue mutation. */
    public TargetOperationStore.Snapshot freezeMarchOutcomes(TargetOperationStore.Snapshot slot) {
        requireCurrentPending(slot);
        if(slot.combatPlan().marchRecovery()!=null)return slot;
        var plan=slot.combatPlan();var battle=plan.battle();
        var attacker=StrategicBattleMarchOutcome.project(plan,StrategicRallyCasualties.Side.ATTACKER)
                .orElseThrow(()->new IllegalStateException("攻方原March outcome缺失，拒绝恢复COMBAT"));
        var defender=battle.defender()==null?null:StrategicBattleMarchOutcome.project(plan,StrategicRallyCasualties.Side.DEFENDER)
                .orElseThrow(()->new IllegalStateException("玩家守方原March outcome缺失，拒绝恢复COMBAT"));
        var frozen=new TargetOperationStore.MarchRecoveryPlan(TargetOperationStore.MarchIntent.from(attacker),
                defender==null?null:TargetOperationStore.MarchIntent.from(defender));
        requireOriginalMarch(frozen.attacker());if(frozen.defender()!=null)requireOriginalMarch(frozen.defender());
        var next=TargetOperationStore.Snapshot.combat(Math.addExact(slot.version(),1),false,plan.withMarchRecovery(frozen));
        try {
            if(!operations.compareAndSet(next,slot.version())
                    && !operations.findByTargetId(slot.targetId()).filter(next::equals).isPresent())
                throw new IllegalStateException("March outcome冻结CAS未确认，尚未写战后March效果");
        } catch(RuntimeException unknown) {
            if(!operations.findByTargetId(slot.targetId()).filter(next::equals).isPresent())throw unknown;
        }
        return operations.findByTargetId(slot.targetId()).filter(next::equals)
                .orElseThrow(()->new IllegalStateException("March outcome冻结后COMBAT槽未完整读回"));
    }

    /** Apply durable March intents; each exact outcome is replayable and the COMBAT slot remains pending afterward. */
    public void recoverMarchOutcomes(TargetOperationStore.Snapshot slot) {
        requireCurrentPending(slot);
        if(due==null)throw new IllegalStateException("MarchDueQueue未装配，保留COMBAT pending");
        var frozen=slot.combatPlan().marchRecovery();
        if(frozen==null)throw new IllegalStateException("COMBAT March outcome尚未持久冻结");
        requireCurrentPending(slot);applyMarchIntent(frozen.attacker());
        if(frozen.defender()!=null){requireCurrentPending(slot);applyMarchIntent(frozen.defender());}
        requireCurrentPending(slot);
    }

    /** Persist exact operation-scoped report rows after all original battle outcomes have been confirmed. */
    public List<BattleReport> recoverReports(TargetOperationStore.Snapshot slot,StrategicBattleHospitalSettlement hospital) {
        requireCurrentPending(slot);
        if(reports==null)throw new IllegalStateException("战略战报恢复仓储未装配，保留COMBAT pending");
        var plan=slot.combatPlan();var reportPlan=plan.reportPlan();
        if(reportPlan==null)throw new IllegalStateException("原COMBAT缺战报身份计划，不能从当前昵称或TTL补造");
        if(hospital==null || !hospital.plan().operationId().equals(plan.operationId())
                || hospital.plan().sequence()!=plan.sequence() || !hospital.plan().battle().equals(plan.battle())
                || !Objects.equals(hospital.plan().reportPlan(),reportPlan))
            throw new IllegalArgumentException("医院结果不属于当前pending COMBAT报告计划");
        var saved=reports.recordStrategic(reportPlan,hospital.result());
        requireCurrentPending(slot);
        return saved;
    }

    private void requireOriginalMarch(TargetOperationStore.MarchIntent intent) {
        long before=marches.versionOf(intent.marchId());
        var current=marches.findById(intent.marchId()).orElseThrow(()->new IllegalStateException("March outcome冻结前原行军缺失"));
        long after=marches.versionOf(intent.marchId());
        if(before!=intent.beforeVersion() || after!=before || !current.snapshot().equals(intent.before()))
            throw new IllegalStateException("March outcome冻结时原版本/兵权已变化");
    }

    private void applyMarchIntent(TargetOperationStore.MarchIntent intent) {
        var current=marches.findById(intent.marchId()).orElse(null);
        if(intent.disposition()==StrategicBattleMarchOutcome.Disposition.ANNIHILATED) {
            if(current!=null) {
                long version=marches.versionOf(intent.marchId());
                if(version!=intent.beforeVersion() || !current.snapshot().equals(intent.before()))
                    throw new IllegalStateException("全灭March版本/兵权不同，拒绝删除");
                try {
                    if(!marches.deleteIfVersion(intent.marchId(),intent.beforeVersion())
                            && marches.findById(intent.marchId()).isPresent())
                        throw new IllegalStateException("全灭March原版本删除未确认");
                } catch(RuntimeException unknown) {
                    if(marches.findById(intent.marchId()).isPresent())throw unknown;
                }
            }
            cancelDueExact(intent.marchId());return;
        }
        if(current==null)throw new IllegalStateException("仍有幸存兵的原March缺档，不能当作全灭");
        long version=marches.versionOf(intent.marchId());
        boolean before=version==intent.beforeVersion() && current.snapshot().equals(intent.before());
        boolean after=version==Math.addExact(intent.beforeVersion(),1) && current.snapshot().equals(intent.after());
        if(!before && !after)throw new IllegalStateException("March与冻结的原前态/战后态均不一致");
        if(before) {
            try {marches.save(March.fromSnapshot(intent.after()),intent.beforeVersion());}
            catch(RuntimeException unknown) {if(!marchAfterConfirmed(intent))throw unknown;}
            if(!marchAfterConfirmed(intent))throw new IllegalStateException("战后March完整快照未独立读回");
        }
        if(intent.disposition()==StrategicBattleMarchOutcome.Disposition.RETURN)
            scheduleDueExact(intent.marchId(),intent.after().returnArriveAt());
        else cancelDueExact(intent.marchId());
    }

    private boolean marchAfterConfirmed(TargetOperationStore.MarchIntent intent) {
        return marches.versionOf(intent.marchId())==Math.addExact(intent.beforeVersion(),1)
                && marches.findById(intent.marchId()).map(m->m.snapshot().equals(intent.after())).orElse(false);
    }

    private void scheduleDueExact(String marchId,Long dueAt) {
        if(dueAt==null || dueAt<=0)throw new IllegalStateException("RETURN March缺返程到期时刻");
        try {due.reschedule(marchId,dueAt);}
        catch(RuntimeException unknown) {
            var persisted=due.dueAtOf(marchId);
            if(persisted.isPresent() && persisted.getAsLong()==dueAt)return;
            throw unknown;
        }
        var persisted=due.dueAtOf(marchId);
        if(persisted.isEmpty() || persisted.getAsLong()!=dueAt)
            throw new IllegalStateException("March RETURN到期队列精确读回不符");
    }

    private void cancelDueExact(String marchId) {
        try {due.cancel(marchId);}
        catch(RuntimeException unknown) {if(due.dueAtOf(marchId).isPresent())throw unknown;}
        if(due.dueAtOf(marchId).isPresent())throw new IllegalStateException("非RETURN March仍有旧due事件");
    }

    private void resolveNpcTarget(TargetOperationStore.Snapshot slot,TargetOccupationStore.Snapshot ownerBefore,
            NeutralTargetGarrisonStore.Snapshot neutralBefore,boolean attackerWon) {
        if(ownerBefore!=null)throw new IllegalStateException("无玩家守方的COMBAT带有原目标所有权，拒绝重解释");
        var battle=slot.combatPlan().battle();var defender=battle.target().defender();
        var initial=neutralBefore==null?defender.troops():neutralBefore.initialTroops();
        var current=neutralGarrisons.findByTargetIds(List.of(slot.targetId())).get(slot.targetId());
        var afterTroops=attackerWon?Map.<String,Long>of():battle.npcSurvivors();
        var phase=attackerWon?NeutralTargetGarrisonStore.Phase.RETIRED:NeutralTargetGarrisonStore.Phase.NEUTRAL;
        long expected=neutralBefore==null?0:neutralBefore.version();
        var after=new NeutralTargetGarrisonStore.Snapshot(slot.targetId(),battle.target().kind(),battle.target().x(),
                battle.target().y(),initial,afterTroops,phase,battle.happenedAt(),slot.operationId(),Math.addExact(expected,1));
        if(!Objects.equals(current,neutralBefore) && !Objects.equals(current,after))
            throw new IllegalStateException("中立守军原始版本已变化，保留COMBAT待恢复");
        if(attackerWon)resolveAttackerOwnership(slot,null);
        if(!Objects.equals(current,after)) {
            try {
                if(!neutralGarrisons.compareAndSet(after,expected)
                        && !after.equals(neutralGarrisons.findByTargetIds(List.of(slot.targetId())).get(slot.targetId())))
                    throw new IllegalStateException("中立守军CAS未确认，保留COMBAT待恢复");
            } catch(RuntimeException unknown) {
                if(!after.equals(neutralGarrisons.findByTargetIds(List.of(slot.targetId())).get(slot.targetId())))throw unknown;
            }
        }
    }

    private void resolveAttackerOwnership(TargetOperationStore.Snapshot slot,TargetOccupationStore.Snapshot ownerBefore) {
        var battle=slot.combatPlan().battle();String nationId=battle.attacker().rally().nationId();
        var current=currentOwner(slot.targetId());
        if(ownerBefore==null)ownerBefore=battle.target().defender().occupation();
        long expected=ownerBefore==null?0:ownerBefore.version();
        String marchId=March.fromSnapshot(battle.attacker().march()).id();
        if(ownerMatchesCapture(current,slot,ownerBefore,nationId,marchId,expected))return;
        if(!Objects.equals(current,ownerBefore))throw new IllegalStateException("目标归属不是原版本或当前COMBAT收据，拒绝改写");
        Nation nation=nations.findById(nationId).filter(value->!value.isDisbanded())
                .orElseThrow(()->new IllegalStateException("攻方国家存档缺失或已解散，不能授予战略目标"));
        var after=new TargetOccupationStore.Snapshot(slot.targetId(),battle.target().kind(),battle.target().x(),battle.target().y(),
                nationId,nation.name(),battle.happenedAt(),slot.operationId(),Math.addExact(expected,1),
                TargetOccupationStore.Garrison.stationed(marchId));
        try {
            if(!occupations.compareAndSet(after,expected) && !ownerMatchesCapture(currentOwner(slot.targetId()),slot,ownerBefore,nationId,marchId,expected))
                throw new IllegalStateException("战略目标归属CAS未确认，保留COMBAT待恢复");
        } catch(RuntimeException unknown) {
            if(!ownerMatchesCapture(currentOwner(slot.targetId()),slot,ownerBefore,nationId,marchId,expected))throw unknown;
        }
    }

    private TargetOccupationStore.Snapshot currentOwner(String targetId) {
        return occupations.findByTargetIds(List.of(targetId)).get(targetId);
    }

    private static boolean ownerMatchesCapture(TargetOccupationStore.Snapshot current,TargetOperationStore.Snapshot slot,
            TargetOccupationStore.Snapshot ownerBefore,String nationId,String marchId,long previousVersion) {
        return current!=null && current.targetId().equals(slot.targetId()) && current.kind()==slot.combatPlan().battle().target().kind()
                && current.x()==slot.combatPlan().battle().target().x() && current.y()==slot.combatPlan().battle().target().y()
                && current.nationId().equals(nationId) && current.capturedAt()==slot.combatPlan().battle().happenedAt()
                && current.operationId().equals(slot.operationId()) && current.version()==Math.addExact(previousVersion,1)
                && TargetOccupationStore.Garrison.stationed(marchId).equals(current.garrison());
    }

    private static boolean hasTroops(Map<String,Long> troops) {
        long sum=0;for(long count:troops.values())sum=Math.addExact(sum,count);return sum>0;
    }

    private static boolean hasMemberTroops(Map<String,Map<String,Long>> members) {
        long sum=0;for(var units:members.values())for(long count:units.values())sum=Math.addExact(sum,count);return sum>0;
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
