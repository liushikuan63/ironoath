package com.ironoath.web.battle;

import com.ironoath.battle.Winner;
import com.ironoath.core.march.March;
import com.ironoath.web.world.TargetOperationStore;
import java.util.Optional;

/**
 * 原COMBAT的战后行军投影：保存具体after或带原版本的删除意图，不执行仓储写入。
 * 全灭不是0兵March；恢复应消费已存投影，不能重模拟或按原出兵比例重分余兵。
 */
public record StrategicBattleMarchOutcome(int formatVersion,TargetOperationStore.CombatPlan plan,
        StrategicRallyCasualties.Side side,Disposition disposition,March.Snapshot after) {
    public enum Disposition { HOLD,RETURN,ANNIHILATED }
    public StrategicBattleMarchOutcome {
        if(formatVersion!=1 || plan==null || side==null || disposition==null)
            throw invalid("原战略行军投影版本/计划/攻守侧缺失");
        var original=source(plan,side);
        if(original==null)throw invalid("NPC没有玩家行军，不能伪造战后行军投影");
        var expected=disposition(plan,side);
        if(disposition!=expected)throw invalid("战后行军去向与原胜方/实际余兵不一致");
        if(expected==Disposition.ANNIHILATED) {
            if(after!=null)throw invalid("全灭须使用原版本删除意图，不能保存0兵行军");
        } else {
            if(after==null)throw invalid("仍有实际余兵，不能伪记全灭或丢失战后行军");
            after=March.fromSnapshot(after).snapshot();
            if(!after.equals(projectAfter(plan,side,expected)))throw invalid("战后行军与原目标/时点/兵权/版本来源不一致");
        }
    }
    public static Optional<StrategicBattleMarchOutcome> project(TargetOperationStore.CombatPlan plan,StrategicRallyCasualties.Side side) {
        if(plan==null || side==null)throw invalid("原战略行军计划/攻守侧缺失");
        if(source(plan,side)==null)return Optional.empty();
        var disposition=disposition(plan,side);
        return Optional.of(new StrategicBattleMarchOutcome(1,plan,side,disposition,
                disposition==Disposition.ANNIHILATED?null:projectAfter(plan,side,disposition)));
    }
    public March.Snapshot before(){return source(plan,side).march();}
    public long beforeVersion(){return source(plan,side).marchVersion();}

    private static StrategicRallyCasualties source(TargetOperationStore.CombatPlan plan,StrategicRallyCasualties.Side side) {
        return side==StrategicRallyCasualties.Side.ATTACKER?plan.battle().attacker():plan.battle().defender();
    }
    private static Disposition disposition(TargetOperationStore.CombatPlan plan,StrategicRallyCasualties.Side side) {
        long total=0;for(var member:source(plan,side).survivorsByPlayer().values())for(long n:member.values())total=Math.addExact(total,n);
        if(total==0)return Disposition.ANNIHILATED;
        boolean hold=side==StrategicRallyCasualties.Side.ATTACKER?plan.battle().result().winner()==Winner.ATTACKER
                :plan.battle().result().winner()!=Winner.ATTACKER;
        return hold?Disposition.HOLD:Disposition.RETURN;
    }
    private static March.Snapshot projectAfter(TargetOperationStore.CombatPlan plan,StrategicRallyCasualties.Side side,Disposition disposition) {
        var original=source(plan,side);var march=March.fromSnapshot(original.march());
        march.applyStrategicSurvivors(original.currentMembers(),original.survivorsByPlayer(),plan.battle().happenedAt(),disposition==Disposition.HOLD);
        return march.snapshot();
    }
    private static IllegalArgumentException invalid(String reason){return new IllegalArgumentException(reason);}
}
