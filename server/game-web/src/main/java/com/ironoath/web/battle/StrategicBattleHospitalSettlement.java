package com.ironoath.web.battle;

import com.ironoath.battle.BattleResult;
import com.ironoath.core.army.ArmyState;
import com.ironoath.web.world.TargetOperationStore;
import java.util.*;

/**
 * 将原COMBAT与每成员已提交的实际医院回执绑定。调用方须独立从Army确认回执；本记录不是写权证明。
 * 仅折合实际收容/溢出，不读当前床位、不治疗、不重分份额，也不重跑战斗；原逐回合战果保持不变。
 */
public record StrategicBattleHospitalSettlement(int formatVersion,TargetOperationStore.CombatPlan plan,
        Map<String,ArmyState.StrategicWoundReceipt> attackerReceipts,
        Map<String,ArmyState.StrategicWoundReceipt> defenderReceipts) {
    public StrategicBattleHospitalSettlement {
        if(formatVersion!=1 || plan==null)throw invalid("最终战略医院结果版本/原COMBAT缺失");
        attackerReceipts=freeze(attackerReceipts);defenderReceipts=freeze(defenderReceipts);
        var original=plan.battle();
        requireSide(plan,original.attacker(),attackerReceipts);
        if(original.defender()==null) {
            if(!defenderReceipts.isEmpty())throw invalid("无医院NPC不得带入玩家医院回执");
        } else {
            var overlap=new HashSet<>(original.attacker().currentMembers().keySet());
            overlap.retainAll(original.defender().currentMembers().keySet());
            if(!overlap.isEmpty())throw invalid("同一玩家不能复用本目标一份医院回执结算攻守两方");
            requireSide(plan,original.defender(),defenderReceipts);
        }
    }

    public static StrategicBattleHospitalSettlement fromReceipts(TargetOperationStore.CombatPlan plan,
            Map<String,ArmyState.StrategicWoundReceipt> attacker,Map<String,ArmyState.StrategicWoundReceipt> defender) {
        return new StrategicBattleHospitalSettlement(1,plan,attacker,defender);
    }

    /** 战报消费本结果；原Input和原Raw结果仍留在COMBAT，不能拿最终医院结果再进行分兵。 */
    public BattleResult result() {
        var raw=plan.battle().result();var attack=totals(attackerReceipts);
        // 现有无医院NPC沿MonsterBattleService的0容量语义：原伤兵全部溢出，不补NPC兵或创造NPC医院。
        var defense=plan.battle().defender()==null?new Admissions(0,raw.defWounded()):totals(defenderReceipts);
        return new BattleResult(raw.winner(),raw.rounds(),raw.totalRounds(),raw.atkSurvivors(),raw.defSurvivors(),
                Math.addExact(raw.atkDead(),attack.overflow()),attack.admitted(),attack.overflow(),
                Math.addExact(raw.defDead(),defense.overflow()),defense.admitted(),defense.overflow(),
                raw.loot(),raw.lootCapacity(),raw.seed(),raw.attritionLog());
    }

    private static void requireSide(TargetOperationStore.CombatPlan plan,StrategicRallyCasualties original,
                                     Map<String,ArmyState.StrategicWoundReceipt> receipts) {
        if(!receipts.keySet().equals(original.currentMembers().keySet()))
            throw invalid("原成员医院回执不全或混入其他玩家，零伤兵成员也须确认回执");
        for(var entry:receipts.entrySet()) {
            var receipt=entry.getValue();var requested=original.woundedByPlayer().get(entry.getKey());
            if(!receipt.targetId().equals(plan.battle().target().targetId()) || receipt.sequence()!=plan.sequence()
                    || !receipt.operationId().equals(plan.operationId())
                    || receipt.hospitalCapacity()!=original.hospitalCapacities().get(entry.getKey())
                    || !receipt.requested().equals(requested)
                    || (receipt.policy()==ArmyState.StrategicWoundPolicy.LEGACY_ORDERED
                        && !new ArrayList<>(receipt.requested().entrySet()).equals(new ArrayList<>(requested.entrySet()))))
                throw invalid("原成员医院回执的目标/代次/操作/容量/伤兵来源不一致");
        }
    }
    private static Map<String,ArmyState.StrategicWoundReceipt> freeze(Map<String,ArmyState.StrategicWoundReceipt> source) {
        if(source==null)throw invalid("实际医院回执集合缺失");var copy=new TreeMap<String,ArmyState.StrategicWoundReceipt>();
        source.forEach((id,receipt)->{if(id==null || id.isBlank() || receipt==null)throw invalid("实际医院回执身份缺失");copy.put(id,receipt);});
        return Collections.unmodifiableMap(copy);
    }
    private record Admissions(long admitted,long overflow) {}
    private static Admissions totals(Map<String,ArmyState.StrategicWoundReceipt> receipts) {
        long admitted=0,overflow=0;
        for(var receipt:receipts.values()) {
            for(long n:receipt.admitted().values())admitted=Math.addExact(admitted,n);
            overflow=Math.addExact(overflow,receipt.overflowDead());
        }return new Admissions(admitted,overflow);
    }
    private static IllegalArgumentException invalid(String reason){return new IllegalArgumentException(reason);}
}
