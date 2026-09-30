package com.ironoath.battle;

import com.ironoath.common.num.FixedPoint;

/**
 * 职责：组织侧给这一方的战斗加成 —— 乘区 G（国策）与乘区 H（城墙）在本战中的取值。
 * 依赖：无（纯数据）。
 *
 * <p><b>为什么它挂在 {@link ArmySide} 上而不是 {@code BattleInput} 或 {@code BattleRules}</b>：
 * <ul>
 *   <li>不是 rules：{@code BattleInput} 的注释写明「rules 是这一场战斗适用的全局规则，由配置表装配、
 *       对所有战斗相同；机制是这个守方是谁的属性，逐场不同。混进 rules 会让同一份配置跑出不同结果变成可能」。
 *       国策 buff 与城墙等级正是逐场不同（谁当的国策、这一场守的是谁的家）。</li>
 *   <li>不是 input 的独立字段：{@code ArmySide} 已经是「组织侧加成」的既有容器 ——
 *       {@code techBonus} 装的就是**联盟**科技（个人科技那一份在别处相加），
 *       {@code equipBonusFixed} 装的是装备乘区。组织加成在这一层，与它同族。</li>
 * </ul>
 *
 * <p><b>三项的语义与「能不能为负」各不相同</b>：
 * <ul>
 *   <li>{@code policyAttack} / {@code policyDefense} —— 乘区 G。允许为负：国策是「全国性增益<b>或减益</b>」
 *       （role_permission 那行 why 的原话），锁成非负会让将来一条减益型国策在数据层就表达不了。</li>
 *   <li>{@code wallDefense} —— 乘区 H，城墙等级带来的防守方防御加成。<b>不允许为负</b>：
 *       城墙只会让守方更强，攻城器对它有克制（走乘区 D 的 {@code vsBuildingBonus}），
 *       破墙之后的效果是「这一项归零」而不是「变成负数」—— 归零与取负是两件不同的事，
 *       混在一起的那天就再也说不清「破墙了没有」。</li>
 * </ul>
 *
 * <p><b>三项都恒为 0 时的行为必须与加这个类之前逐位一致</b>（乘区隔离的硬要求：
 * 加一条新加成线不许移动任何一条既有加成线的表现）。这一条由
 * {@code DefenseMultipliers} 的「扁平加法池 + 两个独立乘区」结构保证，并由
 * {@code OrgBonusZoneTest} 钉住。
 */
public record OrgBonus(long policyAttack, long policyDefense, long wallDefense) {

    public OrgBonus {
        if (wallDefense < 0L) {
            throw new IllegalArgumentException(
                    "城墙防御加成不得为负（破墙是归零不是取负），实际=" + wallDefense);
        }
    }

    /** 全零：没有国家、没有城墙，或这一场与组织无关（打野、PVE）。 */
    public static OrgBonus none() {
        return new OrgBonus(0L, 0L, 0L);
    }

    /** 三项全为 0 时返回 true，供装配点跳过读取（省一次查表，语义不变）。 */
    public boolean isZero() {
        return policyAttack == 0L && policyDefense == 0L && wallDefense == 0L;
    }

    /** 可读形式，用于日志与战报。 */
    public String describe() {
        return "国策(攻+" + FixedPoint.format(policyAttack)
                + " 防+" + FixedPoint.format(policyDefense)
                + ") 城墙防+" + FixedPoint.format(wallDefense);
    }
}
