package com.ironoath.core.army;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 当前成员兵权的不可变损失计划。总损失先按当前兵权分摊，伤兵再在已分配的损失内分摊。
 * 不读取原出兵承诺、不决定伤亡比例或医院容量；医院溢出须由各成员持久收容回执结算。
 */
public final class GarrisonLossAllocation {
    private final Map<String,Map<String,Long>> survivorsByPlayer;
    private final Map<String,Map<String,Long>> lossesByPlayer;
    private final Map<String,Long> totalLosses;

    private GarrisonLossAllocation(Map<String,Map<String,Long>> survivors,
            Map<String,Map<String,Long>> losses,Map<String,Long> totals) {
        survivorsByPlayer=freeze(survivors);lossesByPlayer=freeze(losses);totalLosses=units(totals);
    }

    public Map<String,Map<String,Long>> survivorsByPlayer(){return survivorsByPlayer;}
    public Map<String,Map<String,Long>> lossesByPlayer(){return lossesByPlayer;}

    public static GarrisonLossAllocation plan(Map<String,Map<String,Long>> current,
                                               Map<String,Long> requestedLosses) {
        var before=freeze(current);var losses=units(requestedLosses);
        var after=mutable(before);var byPlayer=emptyMembers(before);
        for(var loss:losses.entrySet()) {
            var weights=weights(before,loss.getKey());
            var shares=split(weights,loss.getValue());
            shares.forEach((player,count)->{
                byPlayer.get(player).put(loss.getKey(),count);
                var troops=after.get(player);long left=troops.get(loss.getKey())-count;
                if(left==0)troops.remove(loss.getKey());else troops.put(loss.getKey(),left);
            });
        }
        return new GarrisonLossAllocation(after,byPlayer,losses);
    }

    /** wounded是该阶实际总损失中的伤兵，不能独立按原兵权分摊，以免伤兵超过成员损失。 */
    public Casualties withWounded(Map<String,Long> requestedWounded) {
        var wounded=units(requestedWounded);var byPlayer=emptyMembers(lossesByPlayer);
        var dead=mutable(lossesByPlayer);
        for(var injury:wounded.entrySet()) {
            if(injury.getValue()>totalLosses.getOrDefault(injury.getKey(),0L))
                throw new IllegalArgumentException("伤兵超过该阶实际损失");
            var shares=split(weights(lossesByPlayer,injury.getKey()),injury.getValue());
            shares.forEach((player,count)->{
                byPlayer.get(player).put(injury.getKey(),count);
                var units=dead.get(player);long left=units.get(injury.getKey())-count;
                if(left==0)units.remove(injury.getKey());else units.put(injury.getKey(),left);
            });
        }
        return new Casualties(byPlayer,dead);
    }

    public static final class Casualties {
        private final Map<String,Map<String,Long>> woundedByPlayer;
        private final Map<String,Map<String,Long>> deadByPlayer;
        private Casualties(Map<String,Map<String,Long>> wounded,Map<String,Map<String,Long>> dead) {
            woundedByPlayer=freeze(wounded);deadByPlayer=freeze(dead);
        }
        public Map<String,Map<String,Long>> woundedByPlayer(){return woundedByPlayer;}
        public Map<String,Map<String,Long>> deadByPlayer(){return deadByPlayer;}
    }

    private static Map<String,Long> split(Map<String,Long> weights,long amount) {
        long total=0;
        for(long count:weights.values()) {
            total=Math.addExact(total,count);
            // TierSplit的乘积在游戏量级内有界；异常输入必须在计算前拒绝，不能溢出造兵。
            Math.multiplyExact(count,amount);
        }
        if(amount>total)throw new IllegalArgumentException("损失超过当前成员兵权");
        var shares=TierSplit.splitProportionally(weights,amount);
        long assigned=0;for(long count:shares.values())assigned=Math.addExact(assigned,count);
        if(assigned!=amount)throw new IllegalStateException("成员损失未分完");
        return shares;
    }

    private static Map<String,Long> weights(Map<String,Map<String,Long>> owners,String unit) {
        var result=new LinkedHashMap<String,Long>();
        owners.forEach((player,counts)->{long count=counts.getOrDefault(unit,0L);if(count>0)result.put(player,count);});
        return result;
    }

    private static Map<String,Long> units(Map<String,Long> source) {
        if(source==null)throw new IllegalArgumentException("部队明细不得为空");
        var sorted=new TreeMap<String,Long>();
        for(var entry:source.entrySet()) {
            if(entry.getKey()==null || entry.getKey().isBlank() || entry.getValue()==null || entry.getValue()<0)
                throw new IllegalArgumentException("部队标识/数量无效");
            if(entry.getValue()>0)sorted.put(entry.getKey(),entry.getValue());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static Map<String,Map<String,Long>> freeze(Map<String,Map<String,Long>> source) {
        if(source==null)throw new IllegalArgumentException("成员兵权不得为空");
        var sorted=new TreeMap<String,Map<String,Long>>();
        for(var entry:source.entrySet()) {
            if(entry.getKey()==null || entry.getKey().isBlank())throw new IllegalArgumentException("成员标识无效");
            sorted.put(entry.getKey(),units(entry.getValue()));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static Map<String,Map<String,Long>> mutable(Map<String,Map<String,Long>> source) {
        var out=new LinkedHashMap<String,Map<String,Long>>();
        source.forEach((player,counts)->out.put(player,new LinkedHashMap<>(counts)));return out;
    }

    private static Map<String,Map<String,Long>> emptyMembers(Map<String,Map<String,Long>> source) {
        var out=new LinkedHashMap<String,Map<String,Long>>();
        source.keySet().forEach(player->out.put(player,new LinkedHashMap<>()));return out;
    }
}
