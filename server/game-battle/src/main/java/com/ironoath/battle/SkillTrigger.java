package com.ironoath.battle;

/**
 * 职责：一次技能触发的记录 —— 进入战报快照，客户端据此播放技能特效。
 * 依赖：无（纯数据）。
 *
 * <p>客户端<b>不重新判定</b>技能是否触发（跨语言一致性策略第 1 条）：
 * 它只按这份记录播放表现。判定发生在服务端、结果写进快照，所以战报重放必然一致。
 *
 * @param round         回合序号，从 1 开始
 * @param phase         触发时机，决定它在本回合内的播放顺序
 * @param heroId        施放武将
 * @param slot          武将站位。同一时机内按 slot 升序播放
 * @param skillId       技能 id
 * @param effect        效果类型
 * @param valueFixed    配置数值（定点）
 * @param appliedFixed  实际生效数值（定点）。与 valueFixed 可能不同：
 *                      例如 HEAL 的实际回复量受「已损失兵力」上限约束
 */
public record SkillTrigger(
        int round,
        SkillPhase phase,
        String heroId,
        int slot,
        String skillId,
        SkillEffect effect,
        long valueFixed,
        long appliedFixed) {

    public SkillTrigger {
        if (round < 1) {
            throw new IllegalArgumentException("round 必须 >= 1，实际=" + round);
        }
        if (phase == null || effect == null) {
            throw new IllegalArgumentException("phase 与 effect 都不得为 null，skillId=" + skillId);
        }
        if (heroId == null || heroId.isBlank() || skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("heroId 与 skillId 都不得为空");
        }
        if (slot < 0) {
            throw new IllegalArgumentException("slot 不得为负，skillId=" + skillId);
        }
    }
}
