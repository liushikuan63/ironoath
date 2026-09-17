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

    /**
     * 合并两个来源的同类加成：<b>相加成一个总率</b>（B20 §五④ 裁决）。
     *
     * <p>为什么不各乘一次：乘区 B 在战斗内核里只有一份攻击、一份防御
     * （{@code ×(1 + 科技加成)}），若联盟科技与个人科技各自成为一个乘区，
     * 两者相乘会让"两条线都点满"的玩家拿到 1+0.6+0.6+0.36 而不是 1+1.2，
     * 而这条差异在胜率里看不出来、只在数值追溯时说不清（C01 反直觉条款 7 要防的就是这个）。
     */
    public TechBonus plus(TechBonus other) {
        if (other == null) {
            return this;
        }
        return new TechBonus(Math.addExact(attackFixed, other.attackFixed),
                Math.addExact(defenseFixed, other.defenseFixed));
    }
}
