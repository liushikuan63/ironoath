package com.ironoath.web.world;

import com.ironoath.core.march.March;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 单目标当前操作槽。代次不重置；所有持久步骤确认后才能完成并被下一计划替换。 */
public interface TargetOperationStore {
    enum Kind { WITHDRAWAL,COMBAT }
    record Anchor(String targetId,com.ironoath.core.world.WorldGenerator.EntityType kind,int x,int y) {}
    record MarchIntent(String marchId,long beforeVersion,March.Snapshot before,
                       com.ironoath.web.battle.StrategicBattleMarchOutcome.Disposition disposition,
                       March.Snapshot after) {
        public MarchIntent {
            if(marchId==null || marchId.isBlank() || beforeVersion<0 || before==null || disposition==null)
                throw new IllegalArgumentException("COMBAT行军恢复意图缺少来源/版本/去向");
            before=freeze(before);
            if(!marchId.equals(before.id()))throw new IllegalArgumentException("COMBAT行军恢复前态ID不匹配");
            if(disposition==com.ironoath.web.battle.StrategicBattleMarchOutcome.Disposition.ANNIHILATED) {
                if(after!=null)throw new IllegalArgumentException("全灭行军意图不能保存0兵after态");
            } else {
                if(after==null)throw new IllegalArgumentException("仍存活行军恢复意图缺少after态");
                after=freeze(after);
                if(!marchId.equals(after.id()))throw new IllegalArgumentException("COMBAT行军恢复后态ID不匹配");
            }
        }
        public static MarchIntent from(com.ironoath.web.battle.StrategicBattleMarchOutcome outcome) {
            return new MarchIntent(outcome.before().id(),outcome.beforeVersion(),outcome.before(),outcome.disposition(),outcome.after());
        }
    }
    record MarchRecoveryPlan(MarchIntent attacker,MarchIntent defender) {
        public MarchRecoveryPlan {
            if(attacker==null || attacker.disposition()==null)throw new IllegalArgumentException("攻方COMBAT行军恢复意图缺失");
            if(defender!=null && defender.marchId().equals(attacker.marchId()))
                throw new IllegalArgumentException("COMBAT攻守不能复用同一March恢复意图");
        }
    }
    record CombatPlan(String operationId,long sequence,com.ironoath.web.battle.CapturedStrategicBattle battle,
                      MarchRecoveryPlan marchRecovery) {
        public CombatPlan(String operationId,long sequence,com.ironoath.web.battle.CapturedStrategicBattle battle) {
            this(operationId,sequence,battle,null);
        }
        public CombatPlan {
            if(sequence<1 || battle==null || !Objects.equals(operationId,"battle:"+battle.attacker().march().id()+":"+battle.attacker().marchVersion()))
                throw new IllegalArgumentException("原战斗操作ID/代次与兵权版本不一致");
            if(marchRecovery!=null) {
                var attacker=marchRecovery.attacker();
                if(!attacker.marchId().equals(battle.attacker().march().id())
                        || attacker.beforeVersion()!=battle.attacker().marchVersion()
                        || !attacker.before().equals(battle.attacker().march()))
                    throw new IllegalArgumentException("COMBAT攻方行军恢复意图与冻结战果不一致");
                var frozenDefender=battle.defender();var intentDefender=marchRecovery.defender();
                if((frozenDefender==null)!=(intentDefender==null)
                        || frozenDefender!=null && (!intentDefender.marchId().equals(frozenDefender.march().id())
                        || intentDefender.beforeVersion()!=frozenDefender.marchVersion()
                        || !intentDefender.before().equals(frozenDefender.march())))
                    throw new IllegalArgumentException("COMBAT守方行军恢复意图与冻结战果不一致");
            }
        }
        public static CombatPlan create(com.ironoath.web.battle.CapturedStrategicBattle battle,long sequence) {
            return new CombatPlan("battle:"+battle.attacker().march().id()+":"+battle.attacker().marchVersion(),sequence,battle);
        }
        public CombatPlan withMarchRecovery(MarchRecoveryPlan recovery) {
            return new CombatPlan(operationId,sequence,battle,recovery);
        }
    }
    record WithdrawalPlan(String operationId,long sequence,long happenedAt,
                          TargetOccupationStore.Snapshot before,TargetOccupationStore.Snapshot after,
                          long marchVersion,March.Snapshot stationed,March.Snapshot returning) {
        public WithdrawalPlan {
            if(before==null || after==null || sequence<1 || happenedAt<before.capturedAt() || marchVersion<0
                    || stationed==null || returning==null)throw new IllegalArgumentException("撤防计划缺失或代次无效");
            stationed=freeze(stationed);returning=freeze(returning);
            var original=March.fromSnapshot(stationed);
            if(!original.hasGarrisonOwnership() || original.status()!=March.Status.STATIONED
                    || original.targetType()!=March.TargetType.STRATEGIC_TARGET || !original.isRallyMarch()
                    || !before.targetId().equals(original.targetId())
                    || before.x()!=original.to().x() || before.y()!=original.to().y()
                    || !TargetOccupationStore.Garrison.stationed(original.id()).equals(before.garrison())
                    || !Objects.equals(operationId,"withdraw:"+original.id()))
                throw new IllegalArgumentException("撤防计划原驻军/目标/操作绑定不一致");
            var expectedOwner=new TargetOccupationStore.Snapshot(before.targetId(),before.kind(),before.x(),before.y(),
                    before.nationId(),before.nationName(),before.capturedAt(),operationId,
                    Math.addExact(before.version(),1),TargetOccupationStore.Garrison.empty());
            if(!expectedOwner.equals(after))throw new IllegalArgumentException("撤防不能改变国家/占领锚点或补回守军");
            original.recall(happenedAt);
            if(!original.snapshot().equals(returning) || returning.returnArriveAt()==null
                    || returning.returnArriveAt()<happenedAt)
                throw new IllegalArgumentException("撤防返程必须是原固定时刻与兵权的精确召回");
        }
        public String targetId(){return before.targetId();}
        public static WithdrawalPlan create(TargetOccupationStore.Snapshot owner,March march,long marchVersion,
                                            long sequence,long now) {
            var before=freeze(march.snapshot());var returned=March.fromSnapshot(before);returned.recall(now);
            String op="withdraw:"+march.id();
            var after=new TargetOccupationStore.Snapshot(owner.targetId(),owner.kind(),owner.x(),owner.y(),
                    owner.nationId(),owner.nationName(),owner.capturedAt(),op,Math.addExact(owner.version(),1),
                    TargetOccupationStore.Garrison.empty());
            return new WithdrawalPlan(op,sequence,now,owner,after,marchVersion,before,returned.snapshot());
        }
    }
    record Snapshot(String targetId,long version,boolean completed,WithdrawalPlan plan,Kind kind,CombatPlan combatPlan) {
        /** 旧BSON缺kind/combatPlan时仅在原撤防计划完整存在的情况下归一化，读取不回填。 */
        public Snapshot(String targetId,long version,boolean completed,WithdrawalPlan plan) {
            this(targetId,version,completed,plan,Kind.WITHDRAWAL,null);
        }
        public Snapshot {
            if(kind==null && plan!=null && combatPlan==null)kind=Kind.WITHDRAWAL;
            if(targetId==null || targetId.isBlank() || version<1 || kind==null
                    || (kind==Kind.WITHDRAWAL && (plan==null || combatPlan!=null || !targetId.equals(plan.targetId())))
                    || (kind==Kind.COMBAT && (plan!=null || combatPlan==null || !targetId.equals(combatPlan.battle().target().targetId()))))
                throw new IllegalArgumentException("目标操作槽与计划绑定无效");
        }
        public static Snapshot combat(long version,boolean completed,CombatPlan plan) {
            return new Snapshot(plan.battle().target().targetId(),version,completed,null,Kind.COMBAT,plan);
        }
        public String operationId(){return kind==Kind.WITHDRAWAL?plan.operationId():combatPlan.operationId();}
        public long sequence(){return kind==Kind.WITHDRAWAL?plan.sequence():combatPlan.sequence();}
        public long happenedAt(){return kind==Kind.WITHDRAWAL?plan.happenedAt():combatPlan.battle().happenedAt();}
        public Anchor anchor(){
            if(kind==Kind.WITHDRAWAL){var b=plan.before();return new Anchor(targetId,b.kind(),b.x(),b.y());}
            var b=combatPlan.battle().target();return new Anchor(targetId,b.kind(),b.x(),b.y());
        }
    }
    private static March.Snapshot freeze(March.Snapshot source){return March.fromSnapshot(source).snapshot();}

    Optional<Snapshot> findByTargetId(String targetId);
    /** A bounded catalog batch, so viewport recovery projection does not fan out one read per strategic target. */
    Map<String,Snapshot> findByTargetIds(Collection<String> targetIds);
    /** 仅查询仍保留的当前槽；不是全历史RPC响应缓存。 */
    Optional<Snapshot> findByOperationId(String operationId);
    /** 只读候选ID，不解码计划；坏目标可逐项隔离，不挡住其他固定目标。 */
    List<String> pendingTargetIds(Collection<String> catalogIds);
    List<String> findAllTargetIds(int limit);
    boolean compareAndSet(Snapshot next,long expectedVersion);

    static void validateWrite(Snapshot next,long expectedVersion){
        if(next==null || expectedVersion<0 || next.version()!=Math.addExact(expectedVersion,1))
            throw new IllegalArgumentException("目标操作版本必须等于原期望版本加一");
    }
    static boolean allowed(Snapshot current,Snapshot next,long expectedVersion){
        if(current==null)return expectedVersion==0 && !next.completed() && next.sequence()==1;
        if(current.version()!=expectedVersion)return false;
        if(current.operationId().equals(next.operationId())) {
            if(current.kind()!=next.kind())return false;
            if(current.kind()==Kind.WITHDRAWAL)
                return !current.completed() && next.completed() && Objects.equals(current.plan(),next.plan());
            var before=current.combatPlan();var after=next.combatPlan();
            if(before==null || after==null || !Objects.equals(before.operationId(),after.operationId())
                    || before.sequence()!=after.sequence() || !Objects.equals(before.battle(),after.battle()))return false;
            if(current.completed())return false;
            if(next.completed())return Objects.equals(before,after);
            return before.marchRecovery()==null && after.marchRecovery()!=null;
        }
        return current.completed() && !next.completed()
                && next.sequence()==Math.addExact(current.sequence(),1);
    }
}
