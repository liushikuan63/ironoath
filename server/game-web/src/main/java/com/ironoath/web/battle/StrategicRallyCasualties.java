package com.ironoath.web.battle;

import com.ironoath.battle.BattleResult;
import com.ironoath.battle.UnitType;
import com.ironoath.core.march.March;
import com.ironoath.core.social.Rally;
import java.util.*;

/**
 * 战略单方的冻结账务载荷。读取时只验守恒，不重分已存成员结果；没有Army或归属写入权。
 * 原战斗输入/配置与版本、成员医院容量的真实读取仍由持有目标操作权的producer冻结。
 */
public record StrategicRallyCasualties(int formatVersion,Side side,long marchVersion,March.Snapshot march,
        RallyIdentity rally,BattleResult result,Map<String,UnitType> unitTypes,List<String> woundUnitOrder,
        Map<String,Map<String,Long>> currentMembers,Map<String,Map<String,Long>> lossesByPlayer,
        Map<String,Map<String,Long>> survivorsByPlayer,Map<String,Map<String,Long>> woundedByPlayer,
        Map<String,Map<String,Long>> deadByPlayer,Map<String,Long> hospitalCapacities) {
    public enum Side { ATTACKER,DEFENDER }

    public record RallyIdentity(String rallyId,Rally.Scope scope,String nationId,String initiatorId,
            Rally.Status status,long version,long targetX,long targetY,String targetType,
            Map<String,Rally.Participant> participants,Rally.Departure departure) {
        public RallyIdentity {
            text(rallyId);text(nationId);text(initiatorId);
            if(scope!=Rally.Scope.NATION || (status!=Rally.Status.DEPARTED && status!=Rally.Status.ARRIVED)
                    || version<0 || targetX<0 || targetY<0 || !"STRATEGIC_TARGET".equals(targetType)
                    || participants==null || departure==null)throw invalid("原集结身份/状态无效");
            var copied=new LinkedHashMap<String,Rally.Participant>();
            for(var entry:participants.entrySet()) {
                text(entry.getKey());var p=entry.getValue();
                if(p==null || !entry.getKey().equals(p.playerId()))throw invalid("原集结成员错绑");
                copied.put(entry.getKey(),new Rally.Participant(p.playerId(),p.troops(),p.heroes()));
            }
            participants=Collections.unmodifiableMap(copied);
            if(!participants.containsKey(initiatorId))throw invalid("原集结缺少发起人");
            var merged=new LinkedHashMap<String,Long>();
            participants.values().forEach(p->p.troops().forEach((unit,n)->merged.merge(unit,n,Math::addExact)));
            departure=new Rally.Departure(departure.mergedTroops(),departure.totalTroops(),departure.memberCount(),departure.departAt());
            if(!merged.equals(departure.mergedTroops()) || total(merged)!=departure.totalTroops()
                    || departure.memberCount()!=participants.size() || departure.departAt()<0)
                throw invalid("原集结出发承诺不守恒");
        }
        public static RallyIdentity freeze(Rally rally) {
            if(rally==null)throw invalid("原集结缺失");
            return new RallyIdentity(rally.rallyId(),rally.scope(),rally.groupId(),rally.initiatorId(),
                    rally.status(),rally.version(),rally.targetX(),rally.targetY(),rally.targetType(),
                    rally.participants(),rally.departure());
        }
    }

    public StrategicRallyCasualties {
        if(formatVersion!=1 || side==null || marchVersion<0 || march==null || rally==null || result==null)
            throw invalid("冻结战损版本/输入缺失");
        var original=March.fromSnapshot(march);march=original.snapshot();
        if(original.targetType()!=March.TargetType.STRATEGIC_TARGET || original.action()!=March.Action.ATTACK
                || !original.isRallyMarch() || !original.rallyId().equals(rally.rallyId())
                || !original.playerId().equals(rally.initiatorId())
                || original.to().x()!=rally.targetX() || original.to().y()!=rally.targetY()
                || original.startAt()<rally.departure().departAt())throw invalid("行军与原集结身份/目标不一致");
        if(side==Side.ATTACKER && (original.status()!=March.Status.FIGHTING || original.hasGarrisonOwnership()))
            throw invalid("进攻输入必须是首次到达的战略集结");
        if(side==Side.DEFENDER && (original.status()!=March.Status.STATIONED || !original.hasGarrisonOwnership()))
            throw invalid("守备输入必须是当前实际驻军");
        currentMembers=members(currentMembers,false);lossesByPlayer=members(lossesByPlayer,false);
        survivorsByPlayer=members(survivorsByPlayer,false);woundedByPlayer=members(woundedByPlayer,true);
        deadByPlayer=members(deadByPlayer,false);hospitalCapacities=capacities(hospitalCapacities);
        var types=new TreeMap<String,UnitType>();
        if(unitTypes==null)throw invalid("冻结兵种映射缺失");
        unitTypes.forEach((id,type)->{text(id);if(type==null)throw invalid("冻结兵种类型缺失");types.put(id,type);});
        unitTypes=Collections.unmodifiableMap(new LinkedHashMap<>(types));
        if(!unitTypes.keySet().equals(march.units().keySet()) || !merged(currentMembers).equals(march.units()))
            throw invalid("当前成员兵权/兵种映射与行军不一致");
        if(woundUnitOrder==null)throw invalid("冻结伤兵收容顺序缺失");
        woundUnitOrder.forEach(StrategicRallyCasualties::text);woundUnitOrder=List.copyOf(woundUnitOrder);
        if(new HashSet<>(woundUnitOrder).size()!=woundUnitOrder.size()
                || !new HashSet<>(woundUnitOrder).equals(merged(woundedByPlayer).keySet()))
            throw invalid("冻结伤兵顺序与明细不一致");
        for(var values:woundedByPlayer.values()) {
            var expectedOrder=new ArrayList<String>();
            for(String unit:woundUnitOrder)if(values.containsKey(unit))expectedOrder.add(unit);
            if(!expectedOrder.equals(new ArrayList<>(values.keySet())))throw invalid("成员收容顺序与冻结优先级不一致");
        }
        var keys=currentMembers.keySet();
        if(!keys.equals(lossesByPlayer.keySet()) || !keys.equals(survivorsByPlayer.keySet())
                || !keys.equals(woundedByPlayer.keySet()) || !keys.equals(deadByPlayer.keySet())
                || !keys.equals(hospitalCapacities.keySet()))throw invalid("成员结果或医院容量身份集合不一致");
        for(var entry:currentMembers.entrySet()) {
            var participant=rally.participants().get(entry.getKey());
            if(participant==null)throw invalid("当前兵权出现非原集结成员");
            for(var count:entry.getValue().entrySet())
                if(count.getValue()>participant.troops().getOrDefault(count.getKey(),0L))throw invalid("当前兵权超过原承诺");
        }
        if(side==Side.ATTACKER) {
            var expected=new LinkedHashMap<String,Map<String,Long>>();rally.participants().forEach((p,v)->expected.put(p,v.troops()));
            if(!expected.equals(currentMembers))throw invalid("进攻行军缺少当前兵权，不能按原比例猜幸存兵");
        } else if(!original.garrisonTroopsByPlayer().equals(currentMembers))throw invalid("守军当前兵权被替换");
        for(String player:keys) {
            var all=new HashSet<>(currentMembers.get(player).keySet());
            all.addAll(lossesByPlayer.get(player).keySet());all.addAll(survivorsByPlayer.get(player).keySet());
            all.addAll(woundedByPlayer.get(player).keySet());all.addAll(deadByPlayer.get(player).keySet());
            for(String unit:all) {
                long before=currentMembers.get(player).getOrDefault(unit,0L),loss=lossesByPlayer.get(player).getOrDefault(unit,0L);
                long survivors=survivorsByPlayer.get(player).getOrDefault(unit,0L),wounded=woundedByPlayer.get(player).getOrDefault(unit,0L);
                long dead=deadByPlayer.get(player).getOrDefault(unit,0L);
                if(before!=Math.addExact(loss,survivors) || loss!=Math.addExact(wounded,dead))
                    throw invalid("逐成员逐阶伤亡不守恒");
            }
        }
        requirePhysics(march.units(),unitTypes,result,side);
        var actualSurvivors=countsByType(merged(survivorsByPlayer),unitTypes);
        if(!actualSurvivors.equals(side==Side.ATTACKER?result.atkSurvivors():result.defSurvivors())
                || total(merged(woundedByPlayer))!=(side==Side.ATTACKER?result.atkWounded():result.defWounded())
                || total(merged(deadByPlayer))!=(side==Side.ATTACKER?result.atkDead():result.defDead()))
            throw invalid("冻结成员结果与真实战果不一致");
    }

    static void requirePhysics(Map<String,Long> units,Map<String,UnitType> types,BattleResult result,Side side) {
        if(result==null || side==null)throw invalid("原始战果缺失");
        var before=countsByType(units,types);var survivors=side==Side.ATTACKER?result.atkSurvivors():result.defSurvivors();
        long lost=0;
        for(UnitType type:UnitType.values()) {
            Long n=survivors.get(type);
            if(n==null || n<0 || n>before.get(type))throw invalid("战果幸存兵数无效，不能截断掩盖");
            lost=Math.addExact(lost,before.get(type)-n);
        }
        long dead=side==Side.ATTACKER?result.atkDead():result.defDead();
        long wounded=side==Side.ATTACKER?result.atkWounded():result.defWounded();
        long overflow=side==Side.ATTACKER?result.atkOverflowDead():result.defOverflowDead();
        if(overflow!=0 || lost!=Math.addExact(dead,wounded))throw invalid("战略战果不得预扣组医院，死亡伤兵须覆盖实际损失");
    }

    static Map<UnitType,Long> countsByType(Map<String,Long> units,Map<String,UnitType> types) {
        var result=new EnumMap<UnitType,Long>(UnitType.class);for(UnitType type:UnitType.values())result.put(type,0L);
        units.forEach((id,n)->{var type=types.get(id);if(type==null)throw invalid("单位缺少冻结兵种类型");result.merge(type,n,Math::addExact);});
        return result;
    }
    private static Map<String,Map<String,Long>> members(Map<String,Map<String,Long>> source,boolean orderedUnits) {
        if(source==null)throw invalid("成员兵力明细缺失");
        var sorted=new TreeMap<String,Map<String,Long>>();
        source.forEach((player,u)->{text(player);sorted.put(player,units(u,orderedUnits));});
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
    private static Map<String,Long> units(Map<String,Long> source,boolean ordered) {
        if(source==null)throw invalid("部队明细缺失");
        Map<String,Long> result=ordered?new LinkedHashMap<>():new TreeMap<>();
        source.forEach((id,n)->{text(id);if(n==null || n<0)throw invalid("部队数量无效");if(n>0)result.put(id,n);});
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }
    private static Map<String,Long> capacities(Map<String,Long> source) {
        if(source==null)throw invalid("成员医院容量缺失");var result=new TreeMap<String,Long>();
        source.forEach((player,n)->{text(player);if(n==null || n<0)throw invalid("成员医院容量无效");result.put(player,n);});
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }
    private static Map<String,Long> merged(Map<String,Map<String,Long>> members) {
        var result=new LinkedHashMap<String,Long>();members.values().forEach(u->u.forEach((id,n)->result.merge(id,n,Math::addExact)));return result;
    }
    private static long total(Map<String,Long> counts){long n=0;for(long count:counts.values())n=Math.addExact(n,count);return n;}
    private static void text(String value){if(value==null || value.isBlank())throw invalid("身份/单位标识缺失");}
    private static IllegalArgumentException invalid(String reason){return new IllegalArgumentException(reason);}
}
