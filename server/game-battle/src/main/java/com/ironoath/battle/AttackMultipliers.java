package com.ironoath.battle;

import com.ironoath.common.num.FixedPoint;

/**
 * 职责：乘区拆解明细 —— 用于日志、战报与问题排查（B05 §1.3）。
 * 依赖：game-common 的 FixedPoint。
 *
 * <p><b>每个乘区独立存储，禁止合并成一个「总加成」数字</b>。这不是洁癖：
 * 六个乘区来自六个不同系统（武将 / 科技 / 装备 / 克制 / 地形 / 战力圈层），
 * 合并之后任何一方调数值都会影响其余五方的表现，而且出了 bug 无法定位是哪条规则算错。
 * 验收 5 要求「单独调整武将加成，结果与手算乘区 A 一致（误差 0）」——
 * 只有乘区独立存储，这个验收才可执行。
 *
 * <p>每个字段的语义都是<b>增量</b>而不是倍率：0 表示无加成，1500 表示 +15%。
 * 合成时才各自加 1.0 变成倍率相乘。用增量存储是因为配置表里的数值就是增量形式
 * （global.json 的 BONUS_REVENGE = "0.15"），存倍率会多一次转换、多一处出错机会。
 *
 * @param hero     乘区 A：武将加成合计（该方全部上阵武将的 heroBonusFixed 之和）
 * @param tech     乘区 B：科技加成
 * @param equip    乘区 C：装备加成
 * @param counter  乘区 D：克制系数。这一项是<b>倍率</b>而非增量（见类末说明）
 * @param terrain  乘区 E：地形加成
 * @param modifier 乘区 F：战力圈层加成合计（复仇 + 哀兵 + 围剿 + 地形战术）
 */
public record AttackMultipliers(
        long hero,
        long tech,
        long equip,
        long counter,
        long terrain,
        long modifier) {

    /**
     * 合成最终乘数（定点数，全程 long）。
     *
     * <p>逐个 {@code FixedPoint.mul}，<b>不合并源头</b>：先把六个增量相加再乘一次，
     * 与逐个相乘的结果不同（(1+a)(1+b) ≠ 1+a+b），而且合并后就再也拆不回来了。
     *
     * <p>counter 是唯一按倍率存储的项（克制是 +25% / 被克 -20% 的乘法关系，
     * 且可能同时存在相互克制，必须相乘才能抵消），所以它不再加 1.0。
     */
    public long compose() {
        long result = FixedPoint.ONE + hero;
        result = FixedPoint.mul(result, FixedPoint.ONE + tech);
        result = FixedPoint.mul(result, FixedPoint.ONE + equip);
        result = FixedPoint.mul(result, counter);
        result = FixedPoint.mul(result, FixedPoint.ONE + terrain);
        result = FixedPoint.mul(result, FixedPoint.ONE + modifier);
        return result;
    }

    /** 全部乘区为零（无武将、无科技、无装备、无克制、无地形、无圈层加成）。 */
    public static AttackMultipliers neutral() {
        return new AttackMultipliers(0L, 0L, 0L, FixedPoint.ONE, 0L, 0L);
    }

    /** 可读形式，用于战报文本。 */
    public String describe() {
        return "武将+" + FixedPoint.format(hero)
                + " 科技+" + FixedPoint.format(tech)
                + " 装备+" + FixedPoint.format(equip)
                + " 克制×" + FixedPoint.format(counter)
                + " 地形+" + FixedPoint.format(terrain)
                + " 圈层+" + FixedPoint.format(modifier)
                + " ⇒ 合计×" + FixedPoint.format(compose());
    }
}
