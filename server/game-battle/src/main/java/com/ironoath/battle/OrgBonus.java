package com.ironoath.battle;

import com.ironoath.common.num.FixedPoint;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

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
 * <p><b>攻击侧为什么是「按兵种」而不是一个数</b>（2026-09-30 裁决 A7：骑兵时代逐档五行）：
 * {@code nation_policy.json} 里 {@code np_cavalry_t1..t5} 各一行、都作用于轻骑兵但各档可单独调。
 * 内核的乘区是在<b>兵种循环里面</b>构造的（{@code BattleSimulator.effectiveAttack}），
 * 所以按兵种给是自然形状；压成一个标量的话五行会相加成 +75% 打给<b>所有</b>兵种 ——
 * 那是一条不报错、只在平衡数字上露出来的错。
 * 而防御侧是<b>一方一个</b>的算法（兰彻斯特里防御不逐兵种算），所以仍是标量 ——
 * 本批四条国策里没有「某个兵种的防御」这种效果。
 *
 * <p><b>两项的语义与「能不能为负」各不相同</b>：
 * <ul>
 *   <li>{@code policyAttackByUnit} / {@code policyDefense} —— 乘区 G。允许为负：国策是
 *       「全国性增益<b>或减益</b>」（role_permission 那行 why 的原话），
 *       锁成非负会让将来一条减益型国策在数据层就表达不了。</li>
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
public record OrgBonus(Map<UnitType, Long> policyAttackByUnit, long policyDefense, long wallDefense) {

    public OrgBonus {
        if (wallDefense < 0L) {
            throw new IllegalArgumentException(
                    "城墙防御加成不得为负（破墙是归零不是取负），实际=" + wallDefense);
        }
        if (policyAttackByUnit == null) {
            throw new IllegalArgumentException("policyAttackByUnit 不得为 null，无加成请用 OrgBonus.none()");
        }
        Map<UnitType, Long> copy = new EnumMap<>(UnitType.class);
        for (Map.Entry<UnitType, Long> entry : policyAttackByUnit.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("按兵种的国策攻击加成不得有 null 项");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        policyAttackByUnit = Collections.unmodifiableMap(copy);
    }

    /** 只给一个兵种的国策攻击加成（表里那种「逐档一行」的形状）。 */
    public static OrgBonus attackOn(UnitType type, long attackFixed) {
        return new OrgBonus(Map.of(type, attackFixed), 0L, 0L);
    }

    /** 全零：没有国家、没有城墙，或这一场与组织无关（打野、PVE）。 */
    public static OrgBonus none() {
        return new OrgBonus(Map.of(), 0L, 0L);
    }

    /** 某个兵种拿到的国策攻击加成（乘区 G 的攻击侧）；没配就是 0。 */
    public long policyAttackFor(UnitType type) {
        return policyAttackByUnit.getOrDefault(type, 0L);
    }

    /** 全部为 0 时返回 true，供装配点跳过读取（省一次查表，语义不变）。 */
    public boolean isZero() {
        return policyAttackByUnit.isEmpty() && policyDefense == 0L && wallDefense == 0L;
    }

    /** 可读形式，用于日志与战报。 */
    public String describe() {
        StringBuilder attack = new StringBuilder();
        for (UnitType type : UnitType.values()) {
            long value = policyAttackByUnit.getOrDefault(type, 0L);
            if (value != 0L) {
                if (attack.length() > 0) {
                    attack.append('/');
                }
                attack.append(type).append('+').append(FixedPoint.format(value));
            }
        }
        return "国策(攻[" + attack + "] 防+" + FixedPoint.format(policyDefense)
                + ") 城墙防+" + FixedPoint.format(wallDefense);
    }
}
