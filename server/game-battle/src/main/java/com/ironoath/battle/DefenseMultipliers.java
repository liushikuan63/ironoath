package com.ironoath.battle;

import com.ironoath.common.num.FixedPoint;

/**
 * 职责：防御侧的乘区合成 —— 2026-09-30 为「乘区 G 国策 / 乘区 H 城墙」新增（裁决 A5 + R2）。
 * 依赖：game-common 的 FixedPoint。
 *
 * <p><b>为什么防御侧要单开一个类，而不是照抄 {@link AttackMultipliers} 那六个区</b>：
 * 加这个类之前，{@code effectiveDefense} 是一条<b>扁平加法</b>：
 * <pre>{@code 1 + 武将防御 + 科技防御 + 装备 + 地形防御 + 防御增益 - 削减}</pre>
 * 也就是说防御侧<b>从来没有乘区隔离</b>，六条线是加在一起的。
 * 如果为了加国策而把它改写成 {@code (1+a)(1+b)(1+c)…}，科技防御、装备、地形三条既有加成
 * 的相对关系会整体改变 —— 那是**一次无声的全局平衡重算**，而 B05 验收 5 的口径
 * 「单独调整某一条，结果与手算逐位一致」正是用来钉住这种无声漂移的。
 * 「模式隔离：不开该模式的路径必须与改动前完全一致」这条纪律在这里就是硬约束。
 *
 * <p><b>所以本类采取「旧池原样 + 两个新乘区」的形状</b>：
 * <pre>{@code (1 + 武将 + 科技 + 装备 + 地形 + 增益 - 削减) × (1 + 国策) × (1 + 城墙)}</pre>
 * 括号里那一大坨就是改动前<b>逐位相同</b>的旧算式；乘区 G 与 H 各自独立相乘。
 * 两个新乘区都是 0 时，{@link #compose()} 与旧算式<b>完全相等</b>——
 * 这条等式由 {@code OrgBonusZoneTest} 直接断言，不是靠注释承诺。
 *
 * <p><b>为什么不把 G/H 也塞进那个括号里</b>：那正是 B21 §五④ 块③ 禁止的
 * 「污染既有乘区」—— 并进去之后战报与日志里就分不出「这 15% 是国策还是科技」，
 * 而玩家争议数值时唯一能做的事就是问「这一条是谁给的」。
 *
 * <p><b>城墙为什么只在这里而不在 {@link AttackMultipliers}</b>：城墙只让<b>守方</b>更强，
 * 攻方那一侧不存在「城墙加成」这个量。给它留一个恒为 0 的攻击侧字段，
 * 只会诱使下一个人把「守方城墙」错填到攻方去。
 * 至于「攻方那一侧携带的 {@code wall} 会不会生效」—— 本类不知道也不关心，
 * 那是调用方的责任；{@code BattleSimulator.effectiveDefense} 已经在内核里强制
 * 「攻方一律取 0」，并由 {@code OrgBonusZoneTest.wallNeverTouchesTheAttackSide} 钉住。
 */
public record DefenseMultipliers(
        long hero,
        long tech,
        long equip,
        long terrain,
        long buff,
        long debuff,
        long policy,
        long wall) {

    /** 旧算式那一坨（改动前的逐位同形）+ 两个新乘区为 0。 */
    public DefenseMultipliers(long hero, long tech, long equip, long terrain, long buff, long debuff) {
        this(hero, tech, equip, terrain, buff, debuff, 0L, 0L);
    }

    /**
     * 合成最终防御乘数。
     *
     * <p>旧池的系数<b>先夹到非负再相乘</b>：削防叠满时旧算式允许系数落到 0 甚至负数，
     * 而 {@code BattleSimulator.effectiveDefense} 原有的守卫是「小于 0 就取 0」
     * —— 那条守卫必须留在这里，否则一个 −150% 的削减会让减员系数超过 1、损失多于总兵数。
     * 保留它同时保证了「两个新乘区为 0 时与改动前逐位相同」。
     */
    public long compose() {
        long base = FixedPoint.ONE
                + hero + tech + equip + terrain
                + buff - debuff;
        if (base < 0L) {
            base = 0L;
        }
        long result = FixedPoint.mul(base, FixedPoint.ONE + policy);
        result = FixedPoint.mul(result, FixedPoint.ONE + wall);
        return result;
    }

    /** 可读形式，用于战报文本。 */
    public String describe() {
        return "旧池(武将+" + FixedPoint.format(hero)
                + " 科技+" + FixedPoint.format(tech)
                + " 装备+" + FixedPoint.format(equip)
                + " 地形+" + FixedPoint.format(terrain)
                + " 增益+" + FixedPoint.format(buff)
                + " 削减-" + FixedPoint.format(debuff)
                + ") ×国策+" + FixedPoint.format(policy)
                + " ×城墙+" + FixedPoint.format(wall)
                + " ⇒ 合计×" + FixedPoint.format(compose());
    }
}
