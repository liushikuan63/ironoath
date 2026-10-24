package com.ironoath.web.battle;

import com.ironoath.battle.*;
import com.ironoath.core.march.March;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.web.world.*;
import java.util.*;

/**
 * 目标操作的原始战斗载荷。生产构造来自Scope亲自执行的Simulation，解码仅核对守恒/绑定，不重模拟。
 * 记录构造器不是争夺授权；调用者仍须持原国家/集结/目标锁并确认各原版本后才持久化。
 */
public record CapturedStrategicBattle(int formatVersion,long happenedAt,String configurationFingerprint,
        TargetFacts target,BattleInput input,BattleResult result,StrategicRallyCasualties attacker,
        StrategicRallyCasualties defender,Map<String,UnitType> npcUnitTypes,Map<String,Long> npcSurvivors) {
    public record TargetFacts(String targetId,WorldGenerator.EntityType kind,int x,int y,
                              StrategicTargetDefender.Defender defender) {
        public TargetFacts {
            if(targetId==null || targetId.isBlank() || kind==null || !kind.isStrategic() || x<0 || y<0 || defender==null)
                throw invalid("原目标锚点或守军缺失");
            var march=defender.march()==null?null:March.fromSnapshot(defender.march()).snapshot();
            defender=new StrategicTargetDefender.Defender(defender.occupation(),defender.marchVersion(),march,
                    freezeTroops(defender.troops()),defender.initialNpc(),defender.neutral());
            var owner=defender.occupation();var neutral=defender.neutral();
            if(owner!=null && (!targetId.equals(owner.targetId()) || kind!=owner.kind() || x!=owner.x() || y!=owner.y()))
                throw invalid("原归属与目标锚点不一致");
            if(neutral!=null && (!targetId.equals(neutral.targetId()) || kind!=neutral.kind() || x!=neutral.x() || y!=neutral.y()))
                throw invalid("原中立守军与目标锚点不一致");
            if(march==null) {
                if(owner!=null || defender.marchVersion()!=null || defender.initialNpc()!=(neutral==null)
                        || (neutral!=null && (neutral.phase()!=NeutralTargetGarrisonStore.Phase.NEUTRAL || !neutral.troops().equals(defender.troops()))))
                    throw invalid("NPC原归属/余兵状态不一致，空目标须走明确无抵抗分支");
            } else if(owner==null || defender.initialNpc() || defender.marchVersion()==null || defender.marchVersion()<0
                    || !TargetOccupationStore.Garrison.stationed(march.id()).equals(owner.garrison())
                    || !march.targetId().equals(targetId) || march.to().x()!=x || march.to().y()!=y
                    || march.status()!=March.Status.STATIONED || !march.units().equals(defender.troops())
                    || (neutral!=null && neutral.phase()!=NeutralTargetGarrisonStore.Phase.RETIRED))
                throw invalid("玩家原驻军/版本与目标不一致");
        }
    }

    public CapturedStrategicBattle {
        if((formatVersion!=1 && formatVersion!=2) || happenedAt<0 || configurationFingerprint==null || configurationFingerprint.isBlank()
                || target==null || input==null || result==null || attacker==null)
            throw invalid("原战略战斗载荷版本或输入缺失");
        if(input.battleType()!=BattleType.SIEGE || input.seed()!=result.seed()
                || input.attacker().hospitalCapacity()!=Long.MAX_VALUE || input.defender().hospitalCapacity()!=Long.MAX_VALUE
                || result.atkOverflowDead()!=0 || result.defOverflowDead()!=0)
            throw invalid("原战略战斗类型/seed/组医院结果不一致");
        requirePlayer(attacker,StrategicRallyCasualties.Side.ATTACKER,input.attacker(),result,target,happenedAt);
        if(formatVersion==2) {
            if(!attacker.survivorsByPlayer().equals(result.atkSurvivorsByMember()))
                throw invalid("攻方引擎成员幸存兵权与生产成员分摊不一致");
            if(!result.atkKillsByMember().keySet().equals(attacker.currentMembers().keySet()))
                throw invalid("攻方原成员引擎击杀来源缺失");
            if(sum(result.atkKillsByMember())!=result.defDead())
                throw invalid("攻方成员引擎击杀未覆盖全部守方阵亡");
        } else if(!result.atkKillsByMember().isEmpty() || !result.defKillsByMember().isEmpty()
                || !result.atkSurvivorsByMember().isEmpty() || !result.defSurvivorsByMember().isEmpty()) {
            throw invalid("旧战略战斗载荷不能伪带成员击杀扩展");
        }
        npcUnitTypes=freezeTypes(npcUnitTypes);npcSurvivors=freezeTroops(npcSurvivors);
        var original=target.defender();
        if(original.march()==null) {
            if(defender!=null || !npcUnitTypes.keySet().equals(original.troops().keySet())
                    || !counts(original.troops(),npcUnitTypes).equals(input.defender().units())
                    || !counts(npcSurvivors,npcUnitTypes).equals(result.defSurvivors()))throw invalid("原NPC兵种/战果错绑");
            for(var unit:npcSurvivors.entrySet())if(unit.getValue()>original.troops().getOrDefault(unit.getKey(),0L))throw invalid("NPC实际余兵超过原兵权");
            if(total(original.troops())!=Math.addExact(total(npcSurvivors),Math.addExact(result.defDead(),result.defWounded())))
                throw invalid("原NPC战损不守恒");
            if(!result.defKillsByMember().isEmpty() || !result.defSurvivorsByMember().isEmpty())
                throw invalid("NPC不能伪造玩家成员引擎贡献");
        } else {
            if(defender==null || !npcUnitTypes.isEmpty() || !npcSurvivors.isEmpty()
                    || !original.march().equals(defender.march()) || original.marchVersion()!=defender.marchVersion()
                    || !original.occupation().nationId().equals(defender.rally().nationId()))throw invalid("原玩家守军/医院载荷错绑");
            if(formatVersion==2) {
                if(!defender.survivorsByPlayer().equals(result.defSurvivorsByMember()))
                    throw invalid("守方引擎成员幸存兵权与生产成员分摊不一致");
                if(!result.defKillsByMember().keySet().equals(defender.currentMembers().keySet()))
                    throw invalid("守方原成员引擎击杀来源缺失");
                if(sum(result.defKillsByMember())!=result.atkDead())
                    throw invalid("守方成员引擎击杀未覆盖全部攻方阵亡");
            }
            requirePlayer(defender,StrategicRallyCasualties.Side.DEFENDER,input.defender(),result,target,happenedAt);
        }
    }

    private static void requirePlayer(StrategicRallyCasualties side,StrategicRallyCasualties.Side role,ArmySide army,
                                      BattleResult result,TargetFacts target,long at) {
        var march=side.march();
        if(side.side()!=role || !side.result().equals(result) || !march.playerId().equals(army.sideId())
                || !march.targetId().equals(target.targetId()) || march.to().x()!=target.x() || march.to().y()!=target.y()
                || at<march.arriveAt() || !counts(march.units(),side.unitTypes()).equals(army.units()))
            throw invalid("原玩家战果/目标/兵权错绑");
    }
    private static Map<String,Long> freezeTroops(Map<String,Long> source) {
        if(source==null)throw invalid("原兵力明细缺失");var copy=new TreeMap<String,Long>();
        source.forEach((id,n)->{if(id==null || id.isBlank() || n==null || n<1)throw invalid("原兵力无效，零兵须空明细");copy.put(id,n);});
        return Collections.unmodifiableMap(copy);
    }
    private static Map<String,UnitType> freezeTypes(Map<String,UnitType> source) {
        if(source==null)throw invalid("原NPC兵种映射缺失");var copy=new TreeMap<String,UnitType>();
        source.forEach((id,type)->{if(id==null || id.isBlank() || type==null)throw invalid("原NPC兵种映射无效");copy.put(id,type);});
        return Collections.unmodifiableMap(copy);
    }
    private static Map<UnitType,Long> counts(Map<String,Long> troops,Map<String,UnitType> types) {
        var out=new EnumMap<UnitType,Long>(UnitType.class);for(var type:UnitType.values())out.put(type,0L);
        troops.forEach((id,n)->{var type=types.get(id);if(type==null)throw invalid("原兵种映射遗漏");out.merge(type,n,Math::addExact);});return out;
    }
    private static long total(Map<String,Long> troops){long n=0;for(long count:troops.values())n=Math.addExact(n,count);return n;}
    private static long sum(Map<String,Long> values){long total=0;for(long value:values.values())total=Math.addExact(total,value);return total;}
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
