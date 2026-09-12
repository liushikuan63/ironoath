package com.ironoath.battle;

/**
 * 职责：技能触发时机。
 * 依赖：无。
 *
 * <p>取值与 contract/config/skill.json 的 trigger 字段一一对应（B02 原文的四种时机）。
 *
 * <p><b>声明顺序即结算顺序</b>，这是确定性的关键（B05 §1.4：技能触发顺序固定不可乱序）：
 * 回合开始 → 攻击结算 → 受击结算 → 回合结束。ROUND_START 与 EVERY_ROUND 都在攻击结算之前触发，
 * 两者的区别是 EVERY_ROUND 在每回合都判定、ROUND_START 只在判定成功后按持续回合数生效。
 */
public enum SkillPhase {
    /** 回合开始时判定，成功后按 durationRounds 持续生效。 */
    ROUND_START,
    /** 每回合都判定并当回合生效。 */
    EVERY_ROUND,
    /** 本方受到攻击时判定。 */
    ON_HIT,
    /** 本方有单位阵亡时判定。 */
    ON_DEATH
}
