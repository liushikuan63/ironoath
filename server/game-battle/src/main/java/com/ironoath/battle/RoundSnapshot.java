package com.ironoath.battle;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 职责：单回合的战后快照 —— 客户端播放战斗的最小单位。
 * 依赖：无（纯数据）。
 *
 * <p><b>战报重放 = 重播快照，不是重算</b>（跨语言一致性策略第 1 条）。
 * 所以这个 record 必须自包含：客户端拿着 rounds 列表就能完整播出一场战斗，
 * 不需要 seed、不需要配置表、不需要任何服务端参与。
 *
 * <p>兵力表用 EnumMap 且不可变，理由同 {@link ArmySide}：迭代顺序必须确定，
 * 否则客户端播放顺序会随平台变化。
 *
 * @param round      回合序号，从 1 开始
 * @param atkUnits   本回合<b>结束后</b>攻方各兵种剩余数量
 * @param defUnits   本回合结束后守方各兵种剩余数量
 * @param atkLoss    攻方本回合损失兵数（整数，已从定点落地）
 * @param defLoss    守方本回合损失兵数
 * @param atkAttack  攻方本回合的有效攻击（定点），战报里展示用，也便于排查数值异常
 * @param defAttack  守方本回合的有效攻击（定点），与攻方口径相同
 * @param defDefense 守方本回合的有效防御（定点）
 * @param attritionFixed 减员系数（定点）= 攻方攻击 / (攻方攻击 + 守方防御 × K)
 * @param skills     本回合触发的技能，按 (phase 声明序, slot 升序) 排好，客户端顺序播放即可
 * @param attackerEffectiveAttackByMember 本回合按原成员拆分的攻方有效攻击；和恰为 atkAttack
 * @param defenderEffectiveAttackByMember 本回合按原成员拆分的守方有效攻击；和恰为守方有效攻击
 */
public record RoundSnapshot(
        int round,
        Map<UnitType, Long> atkUnits,
        Map<UnitType, Long> defUnits,
        long atkLoss,
        long defLoss,
        long atkAttack,
        long defAttack,
        long defDefense,
        long attritionFixed,
        List<SkillTrigger> skills,
        Map<String, Long> attackerEffectiveAttackByMember,
        Map<String, Long> defenderEffectiveAttackByMember) {

    public RoundSnapshot {
        if (round < 1) {
            throw new IllegalArgumentException("round 必须 >= 1，实际=" + round);
        }
        atkUnits = freeze(atkUnits);
        defUnits = freeze(defUnits);
        skills = List.copyOf(skills);
        attackerEffectiveAttackByMember = freezeMembers(attackerEffectiveAttackByMember);
        defenderEffectiveAttackByMember = freezeMembers(defenderEffectiveAttackByMember);
        if(!attackerEffectiveAttackByMember.isEmpty() && sum(attackerEffectiveAttackByMember)!=atkAttack)
            throw new IllegalArgumentException("攻方成员攻击贡献与总有效攻击不守恒");
        if(!defenderEffectiveAttackByMember.isEmpty() && sum(defenderEffectiveAttackByMember)!=defAttack)
            throw new IllegalArgumentException("守方成员攻击贡献与总有效攻击不守恒");
        if (atkLoss < 0L || defLoss < 0L) {
            throw new IllegalArgumentException("损失兵数不得为负：round=" + round
                    + ", atkLoss=" + atkLoss + ", defLoss=" + defLoss);
        }
    }

    public RoundSnapshot(int round, Map<UnitType, Long> atkUnits, Map<UnitType, Long> defUnits,
                         long atkLoss, long defLoss, long atkAttack, long defDefense,
                         long attritionFixed, List<SkillTrigger> skills) {
        this(round,atkUnits,defUnits,atkLoss,defLoss,atkAttack,0L,defDefense,attritionFixed,skills,Map.of(),Map.of());
    }

    private static Map<String,Long> freezeMembers(Map<String,Long> source) {
        if(source==null)return Map.of();
        var out=new TreeMap<String,Long>();source.forEach((id,value)->{
            if(id==null || id.isBlank() || value==null || value<0)throw new IllegalArgumentException("回合成员攻击贡献无效");
            out.put(id,value);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(out));
    }
    private static long sum(Map<String,Long> values){long total=0;for(long value:values.values())total=Math.addExact(total,value);return total;}

    private static Map<UnitType, Long> freeze(Map<UnitType, Long> source) {
        Map<UnitType, Long> copy = new EnumMap<>(UnitType.class);
        for (UnitType type : UnitType.values()) {
            copy.put(type, source.getOrDefault(type, 0L));
        }
        return Collections.unmodifiableMap(copy);
    }
}
