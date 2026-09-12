package com.ironoath.battle;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
 * @param defDefense 守方本回合的有效防御（定点）
 * @param attritionFixed 减员系数（定点）= 攻方攻击 / (攻方攻击 + 守方防御 × K)
 * @param skills     本回合触发的技能，按 (phase 声明序, slot 升序) 排好，客户端顺序播放即可
 */
public record RoundSnapshot(
        int round,
        Map<UnitType, Long> atkUnits,
        Map<UnitType, Long> defUnits,
        long atkLoss,
        long defLoss,
        long atkAttack,
        long defDefense,
        long attritionFixed,
        List<SkillTrigger> skills) {

    public RoundSnapshot {
        if (round < 1) {
            throw new IllegalArgumentException("round 必须 >= 1，实际=" + round);
        }
        atkUnits = freeze(atkUnits);
        defUnits = freeze(defUnits);
        skills = List.copyOf(skills);
        if (atkLoss < 0L || defLoss < 0L) {
            throw new IllegalArgumentException("损失兵数不得为负：round=" + round
                    + ", atkLoss=" + atkLoss + ", defLoss=" + defLoss);
        }
    }

    private static Map<UnitType, Long> freeze(Map<UnitType, Long> source) {
        Map<UnitType, Long> copy = new EnumMap<>(UnitType.class);
        for (UnitType type : UnitType.values()) {
            copy.put(type, source.getOrDefault(type, 0L));
        }
        return Collections.unmodifiableMap(copy);
    }
}
