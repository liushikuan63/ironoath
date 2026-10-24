package com.ironoath.battle;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 职责：战斗结果 —— 客户端播放战报所需的全部内容，自包含、不可变。
 * 依赖：无（纯数据）。
 *
 * <p>客户端<b>只播放不重算</b>：拿着本对象就能完整重现一场战斗，
 * 不需要 seed、不需要配置表、不需要 simulate()（跨语言一致性策略第 1 条）。
 * seed 仍然保留在结果里，但它的用途是<b>服务端</b>自查、问题复现与反外挂校验，
 * 不是给客户端复算用的。
 *
 * @param winner          胜方
 * @param rounds          逐回合快照，客户端按序播放
 * @param totalRounds     实际进行的回合数
 * @param atkSurvivors    攻方各兵种存活数量
 * @param defSurvivors    守方各兵种存活数量
 * @param atkDead         攻方死亡兵数（不可恢复）
 * @param atkWounded      攻方伤兵数（进医院，可治疗）
 * @param atkOverflowDead 攻方因医院溢出而额外死亡的数量。B00：医院溢出部分直接死亡
 * @param defDead         守方死亡兵数
 * @param defWounded      守方伤兵数
 * @param defOverflowDead 守方医院溢出死亡数
 * @param loot            掠夺所得，键为资源 id。未破墙或 PVE 时为空
 * @param lootCapacity    攻方剩余负载（掠夺上限）。B00：掠夺 = min(对方非保护资源, 我方剩余负载)
 * @param seed            本场战斗种子，服务端复算与反外挂用
 * @param attritionLog    每回合的减员系数（定点），排查数值异常时直接看这条曲线
 * @param atkKillsByMember 攻方成员按实际引擎有效攻击贡献获得的守方阵亡数
 * @param defKillsByMember 守方成员按实际引擎有效攻击贡献获得的攻方阵亡数
 * @param atkSurvivorsByMember 引擎按原兵阶和生产成员损失分摊顺序得到的攻方逐成员幸存兵权
 * @param defSurvivorsByMember 同上，守方逐成员幸存兵权
 */
public record BattleResult(
        Winner winner,
        List<RoundSnapshot> rounds,
        int totalRounds,
        Map<UnitType, Long> atkSurvivors,
        Map<UnitType, Long> defSurvivors,
        long atkDead,
        long atkWounded,
        long atkOverflowDead,
        long defDead,
        long defWounded,
        long defOverflowDead,
        Map<String, Long> loot,
        long lootCapacity,
        long seed,
        List<Long> attritionLog,
        Map<String, Long> atkKillsByMember,
        Map<String, Long> defKillsByMember,
        Map<String,Map<String,Long>> atkSurvivorsByMember,
        Map<String,Map<String,Long>> defSurvivorsByMember) {

    public BattleResult {
        if (winner == null) {
            throw new IllegalArgumentException("winner 不得为 null");
        }
        rounds = List.copyOf(rounds);
        attritionLog = List.copyOf(attritionLog);
        atkSurvivors = freezeUnits(atkSurvivors);
        defSurvivors = freezeUnits(defSurvivors);
        loot = freezeLoot(loot);
        atkKillsByMember = freezeMembers(atkKillsByMember,"atkKillsByMember");
        defKillsByMember = freezeMembers(defKillsByMember,"defKillsByMember");
        atkSurvivorsByMember = freezeMemberUnits(atkSurvivorsByMember,"atkSurvivorsByMember");
        defSurvivorsByMember = freezeMemberUnits(defSurvivorsByMember,"defSurvivorsByMember");
        if (totalRounds < 0 || totalRounds != rounds.size()) {
            throw new IllegalArgumentException("totalRounds 必须等于快照数量：totalRounds="
                    + totalRounds + ", rounds=" + rounds.size());
        }
        requireNonNegative(atkDead, "atkDead");
        requireNonNegative(atkWounded, "atkWounded");
        requireNonNegative(atkOverflowDead, "atkOverflowDead");
        requireNonNegative(defDead, "defDead");
        requireNonNegative(defWounded, "defWounded");
        requireNonNegative(defOverflowDead, "defOverflowDead");
        requireNonNegative(lootCapacity, "lootCapacity");
        if(sum(atkKillsByMember)>defDead)
            throw new IllegalArgumentException("攻方成员引擎击杀不得超过守方最终死亡数");
        if(sum(defKillsByMember)>atkDead)
            throw new IllegalArgumentException("守方成员引擎击杀不得超过攻方最终死亡数");
        if(!atkSurvivorsByMember.isEmpty() && sumMemberUnits(atkSurvivorsByMember)!=ArmySide.totalOf(atkSurvivors))
            throw new IllegalArgumentException("攻方成员幸存兵权与引擎聚合战果不守恒");
        if(!defSurvivorsByMember.isEmpty() && sumMemberUnits(defSurvivorsByMember)!=ArmySide.totalOf(defSurvivors))
            throw new IllegalArgumentException("守方成员幸存兵权与引擎聚合战果不守恒");
    }

    public BattleResult(Winner winner,List<RoundSnapshot> rounds,int totalRounds,
                        Map<UnitType,Long> atkSurvivors,Map<UnitType,Long> defSurvivors,
                        long atkDead,long atkWounded,long atkOverflowDead,long defDead,long defWounded,
                        long defOverflowDead,Map<String,Long> loot,long lootCapacity,long seed,List<Long> attritionLog) {
        this(winner,rounds,totalRounds,atkSurvivors,defSurvivors,atkDead,atkWounded,atkOverflowDead,
                defDead,defWounded,defOverflowDead,loot,lootCapacity,seed,attritionLog,Map.of(),Map.of(),Map.of(),Map.of());
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0L) {
            throw new IllegalArgumentException(field + " 不得为负，实际=" + value);
        }
    }

    private static Map<UnitType, Long> freezeUnits(Map<UnitType, Long> source) {
        Map<UnitType, Long> copy = new EnumMap<>(UnitType.class);
        for (UnitType type : UnitType.values()) {
            copy.put(type, source == null ? 0L : source.getOrDefault(type, 0L));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Long> freezeLoot(Map<String, Long> source) {
        Map<String, Long> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((k, v) -> {
                if (v == null || v < 0L) {
                    throw new IllegalArgumentException("loot 中资源 " + k + " 的数量非法：" + v);
                }
                copy.put(k, v);
            });
        }
        return Collections.unmodifiableMap(new java.util.TreeMap<>(copy));
    }

    private static Map<String,Long> freezeMembers(Map<String,Long> source,String field) {
        if(source==null)return Map.of();var out=new TreeMap<String,Long>();
        source.forEach((id,value)->{if(id==null || id.isBlank() || value==null || value<0)throw new IllegalArgumentException(field+"包含无效成员");out.put(id,value);});
        return Collections.unmodifiableMap(new LinkedHashMap<>(out));
    }
    private static Map<String,Map<String,Long>> freezeMemberUnits(Map<String,Map<String,Long>> source,String field) {
        if(source==null)return Map.of();var out=new TreeMap<String,Map<String,Long>>();
        source.forEach((player,units)->{
            if(player==null || player.isBlank() || units==null)throw new IllegalArgumentException(field+"包含无效成员");
            var frozen=new TreeMap<String,Long>();units.forEach((id,count)->{
                if(id==null || id.isBlank() || count==null || count<0)throw new IllegalArgumentException(field+"包含无效兵权");
                if(count>0)frozen.put(id,count);
            });out.put(player,Collections.unmodifiableMap(new LinkedHashMap<>(frozen)));
        });return Collections.unmodifiableMap(new LinkedHashMap<>(out));
    }
    private static long sumMemberUnits(Map<String,Map<String,Long>> members) {
        long total=0;for(var units:members.values())for(long count:units.values())total=Math.addExact(total,count);return total;
    }
    private static long sum(Map<String,Long> values){long total=0;for(long value:values.values())total=Math.addExact(total,value);return total;}

    /** 攻方总损失（死亡 + 伤兵，含溢出死亡）。 */
    public long atkTotalLoss() {
        return atkDead + atkWounded;
    }

    public long defTotalLoss() {
        return defDead + defWounded;
    }

    /** 掠夺到的资源总量。 */
    public long lootTotal() {
        long total = 0L;
        for (long v : loot.values()) {
            total += v;
        }
        return total;
    }
}
