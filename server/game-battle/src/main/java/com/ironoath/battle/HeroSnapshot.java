package com.ironoath.battle;

import java.util.List;

/**
 * 职责：一个上阵武将的战前快照（不可变）。
 * 依赖：无（纯数据）。
 *
 * <p><b>slot 是确定性的关键</b>：同一时机内按 slot 升序触发技能（B05 §1.4）。
 * 概率判定走 {@code rng.fork(slot)}，所以调整武将站位会改变技能触发序列 ——
 * 这是刻意的，站位本身就是策略；但同一份 input 必须永远得到同一份 output。
 *
 * @param heroId         武将 id，对应 contract/config/hero.json
 * @param slot           站位序号，从 0 开始，决定技能触发顺序
 * @param heroBonusFixed 该武将提供的攻击加成（定点，乘区 A 的一份）。
 *                       由调用方用 Formula.heroGrowth 从三维与等级算出，内核不重复计算
 * @param defBonusFixed  该武将提供的防御加成（定点）
 * @param skills         携带的技能，主技能在前
 */
public record HeroSnapshot(
        String heroId,
        int slot,
        long heroBonusFixed,
        long defBonusFixed,
        List<SkillSnapshot> skills) {

    public HeroSnapshot {
        if (heroId == null || heroId.isBlank()) {
            throw new IllegalArgumentException("heroId 不得为空");
        }
        if (slot < 0) {
            throw new IllegalArgumentException("slot 不得为负，heroId=" + heroId);
        }
        if (heroBonusFixed < 0L || defBonusFixed < 0L) {
            throw new IllegalArgumentException("武将加成不得为负，heroId=" + heroId);
        }
        skills = List.copyOf(skills);
    }
}
