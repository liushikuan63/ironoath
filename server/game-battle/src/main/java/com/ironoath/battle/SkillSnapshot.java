package com.ironoath.battle;

/**
 * 职责：一个上阵武将携带的技能（战前快照，不可变）。
 * 依赖：无（纯数据）。
 *
 * @param skillId        技能 id，对应 contract/config/skill.json
 * @param phase          触发时机，决定它在回合内的结算位置（顺序固定，见 SkillPhase）
 * @param chanceFixed    触发概率（定点，0~10000）
 * @param effect         效果类型，决定作用在哪个乘区
 * @param valueFixed     效果数值（定点）。含义随 effect 变化，见 SkillEffect 各值注释
 * @param durationRounds 持续回合数，1 表示只在触发当回合生效
 */
public record SkillSnapshot(
        String skillId,
        SkillPhase phase,
        long chanceFixed,
        SkillEffect effect,
        long valueFixed,
        int durationRounds) {

    public SkillSnapshot {
        if (skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("skillId 不得为空");
        }
        if (phase == null) {
            throw new IllegalArgumentException("phase 不得为 null，skillId=" + skillId);
        }
        if (effect == null) {
            throw new IllegalArgumentException("effect 不得为 null，skillId=" + skillId);
        }
        if (chanceFixed < 0L || chanceFixed > com.ironoath.common.num.FixedPoint.SCALE) {
            throw new IllegalArgumentException("chanceFixed 必须落在 [0, 1.0] 的定点区间，skillId="
                    + skillId + "，实际=" + chanceFixed);
        }
        if (valueFixed < 0L) {
            throw new IllegalArgumentException("valueFixed 不得为负，skillId=" + skillId);
        }
        if (durationRounds < 1) {
            throw new IllegalArgumentException("durationRounds 必须 >= 1，skillId=" + skillId);
        }
    }
}
