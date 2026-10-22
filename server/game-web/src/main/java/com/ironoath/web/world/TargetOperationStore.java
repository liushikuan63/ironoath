package com.ironoath.web.world;

import com.ironoath.core.march.March;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 单目标当前操作槽。代次不重置；所有持久步骤确认后才能完成并被下一计划替换。 */
public interface TargetOperationStore {
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
    record Snapshot(String targetId,long version,boolean completed,WithdrawalPlan plan) {
        public Snapshot {
            if(targetId==null || targetId.isBlank() || version<1 || plan==null || !targetId.equals(plan.targetId()))
                throw new IllegalArgumentException("目标操作槽与计划绑定无效");
        }
    }
    private static March.Snapshot freeze(March.Snapshot source){return March.fromSnapshot(source).snapshot();}

    Optional<Snapshot> findByTargetId(String targetId);
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
        if(current==null)return expectedVersion==0 && !next.completed() && next.plan().sequence()==1;
        if(current.version()!=expectedVersion)return false;
        if(current.plan().operationId().equals(next.plan().operationId()))
            return !current.completed() && next.completed() && current.plan().equals(next.plan());
        return current.completed() && !next.completed()
                && next.plan().sequence()==Math.addExact(current.plan().sequence(),1);
    }
}
