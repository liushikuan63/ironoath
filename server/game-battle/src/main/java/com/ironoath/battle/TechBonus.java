package com.ironoath.battle;

/**
 * 职责：科技加成 —— 乘区 B 的来源（由调用方从 contract/config/tech.json 聚合后传入）。
 * 依赖：无（纯数据）。
 *
 * <p>攻击与防御分开存，不合并成一个数：B05 §1.3 要求每个乘区独立存储，
 * 否则后期调数值时无法定位是哪个系统出了问题。
 *
 * @param attackFixed  攻击加成（定点）。tech_mil_atk 满级 +120% ⇒ 12000
 * @param defenseFixed 防御加成（定点）。tech_mil_def 满级 +120% ⇒ 12000
 */
public record TechBonus(long attackFixed, long defenseFixed) {

    public TechBonus {
        if (attackFixed < 0L || defenseFixed < 0L) {
            throw new IllegalArgumentException("科技加成不得为负：atk=" + attackFixed
                    + ", def=" + defenseFixed);
        }
    }

    /** 无科技加成。 */
    public static TechBonus none() {
        return new TechBonus(0L, 0L);
    }
}
