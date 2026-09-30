package com.ironoath.battle;

import com.ironoath.common.num.FixedPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：乘区 G（国策）与乘区 H（城墙）的隔离验证 —— 2026-09-30 裁决 A5 与 R2。
 * 依赖：JUnit 5 + AssertJ；纯 Java，不读配置表（game-battle 不许依赖 game-config）。
 *
 * <p><b>这一类用例最容易做成假的，所以每条都配了「能失败的另一半」</b>：
 * 断言「G 独立」只写正向（G=+15% 时合乘数 ×1.15）的话，
 * 把 G 直接折进科技乘区也能通过 —— 单项为 0 时两种实现完全等价。
 * 所以每条都同时断言<b>反证</b>：G/H 并进旧池时得到的数<b>不等于</b>独立相乘的数。
 * 少了反证，这些用例就只是在给实现背书。
 *
 * <p><b>最要紧的是第一条等式</b>：两个新乘区为 0 时，
 * {@link DefenseMultipliers#compose()} 必须与「加这两个类之前的扁平加法」逐位相同。
 * 防御侧原本是 {@code 1 + 武将 + 科技 + 装备 + 地形 + 增益 − 削减} 的扁平加法
 * （{@code BattleSimulator.effectiveDefense} 的旧实现），
 * 把它改写成连乘就是一次无声的全局平衡重算 ——
 * 科技防御、装备、地形三条既有加成的相对关系会整体改变，而没有任何测试会红。
 */
class OrgBonusZoneTest {

    /** 乘区 G 的 +15%，即 B21 §五④「骑兵时代」草值。 */
    private static final long POLICY_15 = FixedPoint.parse("0.15");

    // ---------- 第一条：旧算式必须逐位不变 ----------

    @Test
    @DisplayName("两个新乘区为 0 时，防御侧合成与「加这两个类之前的扁平加法」逐位相同")
    void defenseWithNoOrgBonusIsBitIdenticalToTheOldFlatSum() {
        long hero = FixedPoint.parse("0.30");
        long tech = FixedPoint.parse("0.20");
        long equip = FixedPoint.parse("0.05");
        long terrain = FixedPoint.parse("0.15");
        long buff = FixedPoint.parse("0.10");
        long debuff = FixedPoint.parse("0.08");

        // 旧实现就是这个式子（BattleSimulator.effectiveDefense 的改动前形态）
        long oldWay = FixedPoint.ONE + hero + tech + equip + terrain + buff - debuff;

        assertThat(new DefenseMultipliers(hero, tech, equip, terrain, buff, debuff).compose())
                .as("把防御侧改写成 DefenseMultipliers 只是给 G/H 腾位置，旧池的算式必须一字不变")
                .isEqualTo(oldWay);
    }

    @Test
    @DisplayName("削防叠满把旧池夹到 0 时仍然夹 0：国策不能把一个被削光的防御救回来")
    void fullyDebuffedBaseStillClampsToZero() {
        // 削防幅度要超过「1.0 + 全部正向加成」才会真的把旧池压成负数：
        // debuff=0.80 而正向全 0 时旧池是 0.20，压根碰不到那条守卫 —— 那不是夹零，是正常算式。
        long wipedOut = FixedPoint.parse("1.50");
        DefenseMultipliers wiped = new DefenseMultipliers(
                0L, 0L, 0L, 0L, 0L, wipedOut, POLICY_15, POLICY_15);

        assertThat(wiped.compose())
                .as("「削防叠满 → 防御归零」是既有语义；让国策把它救回来等于悄悄改了那条规则")
                .isZero();

        // 边界：正好等于 1.0 时旧池是 0（不夹），乘上两个新乘区后仍然是 0
        assertThat(new DefenseMultipliers(0L, 0L, 0L, 0L, 0L, FixedPoint.ONE,
                POLICY_15, POLICY_15).compose()).isZero();
    }

    // ---------- 乘区 G：攻击侧 ----------

    @Test
    @DisplayName("乘区 G 与其它乘区分别相乘：G 从 0 变成 +15% 时，合乘数精确 ×1.15（且并进科技会得到不同的数）")
    void zoneGMultipliesIndependentlyOnTheAttackSide() {
        long hero = FixedPoint.parse("0.30");
        long tech = FixedPoint.parse("0.20");
        AttackMultipliers withoutG = new AttackMultipliers(hero, tech, 0L, FixedPoint.ONE, 0L, 0L);
        AttackMultipliers withG = new AttackMultipliers(hero, tech, 0L, FixedPoint.ONE, 0L, 0L, POLICY_15);

        assertThat(withG.compose())
                .as("G 必须作为独立乘区参与相乘；先加后乘会小一截（(1.3)(1.2)(1.15) ≠ 1.3+0.2+0.15）")
                .isEqualTo(FixedPoint.mul(withoutG.compose(), FixedPoint.ONE + POLICY_15));

        // 反证：并进科技乘区就不是独立乘区（这正是 B21 §五④ 块③ 禁止的「污染既有乘区」）
        AttackMultipliers merged = new AttackMultipliers(hero, tech + POLICY_15, 0L,
                FixedPoint.ONE, 0L, 0L);
        assertThat(merged.compose())
                .as("把国策并进科技乘区会得到一个更小且无法拆解的数 —— 这就是禁止合并的原因")
                .isNotEqualTo(withG.compose());
    }

    // ---------- 乘区 G / H：防御侧 ----------

    @Test
    @DisplayName("乘区 G 在防御侧同样独立：+15% 精确 ×1.15，并进科技得到不同的数")
    void zoneGMultipliesIndependentlyOnTheDefenseSide() {
        long hero = FixedPoint.parse("0.30");
        long tech = FixedPoint.parse("0.20");
        DefenseMultipliers withoutG = new DefenseMultipliers(hero, tech, 0L, 0L, 0L, 0L);
        DefenseMultipliers withG = new DefenseMultipliers(hero, tech, 0L, 0L, 0L, 0L, POLICY_15, 0L);

        assertThat(withG.compose())
                .isEqualTo(FixedPoint.mul(withoutG.compose(), FixedPoint.ONE + POLICY_15));
        assertThat(new DefenseMultipliers(hero, tech + POLICY_15, 0L, 0L, 0L, 0L).compose())
                .as("并进科技乘区 ≠ 独立乘区")
                .isNotEqualTo(withG.compose());
    }

    @Test
    @DisplayName("乘区 G 与乘区 H 各自相乘：两条各 +15% 是 1.3225 而不是 1.30")
    void zoneGAndZoneHAreTwoSeparateFactors() {
        DefenseMultipliers onlyG = new DefenseMultipliers(0L, 0L, 0L, 0L, 0L, 0L, POLICY_15, 0L);
        DefenseMultipliers onlyH = new DefenseMultipliers(0L, 0L, 0L, 0L, 0L, 0L, 0L, POLICY_15);
        DefenseMultipliers both = new DefenseMultipliers(0L, 0L, 0L, 0L, 0L, 0L,
                POLICY_15, POLICY_15);

        assertThat(both.compose())
                .as("G 与 H 是两条语义（国策 / 城墙），必须分别相乘；先加后乘会偏小")
                .isEqualTo(FixedPoint.mul(onlyG.compose(), onlyH.compose()));
        assertThat(both.compose())
                .as("1.15 × 1.15 = 1.3225，与「1 + 0.15 + 0.15 = 1.30」不同")
                .isEqualTo(FixedPoint.parse("1.3225"));
    }

    // ---------- 值域 ----------

    @Test
    @DisplayName("国策允许为负（它是「增益或减益」），城墙不允许为负（破墙是归零不是取负）")
    void policyMayBeNegativeButWallMayNot() {
        assertThat(new OrgBonus(-POLICY_15, -POLICY_15, 0L).policyAttack())
                .as("国策减益必须在数据层可表达，否则将来只能改内核")
                .isEqualTo(-POLICY_15);
        assertThatThrownBy(() -> new OrgBonus(0L, 0L, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("城墙");
    }

    @Test
    @DisplayName("ArmySide 的七参构造器把组织加成留在 0，八参才带得进去")
    void armySideSevenArgConstructorKeepsOrgBonusAtZero() {
        // 七参：打野 / PVE / 平衡 CLI 的形状，它们本来就不该有国策
        ArmySide seven = new ArmySide("a", List.of(), units(1000L), TechBonus.none(), 0L,
                FormationType.STANDARD, Long.MAX_VALUE / 4);
        assertThat(seven.orgBonus())
                .as("七参构造器必须显式落到 OrgBonus.none()，不能留 null 让下游判空")
                .isEqualTo(OrgBonus.none());
        assertThat(seven.orgBonus().isZero()).isTrue();

        ArmySide eight = army("b", 1000L, new OrgBonus(POLICY_15, 0L, 0L));
        assertThat(eight.orgBonus().policyAttack()).isEqualTo(POLICY_15);
    }

    // ---------- 真的进了战斗内核 ----------

    @Test
    @DisplayName("乘区 G 真的进了结算：同种子下，攻方拿到国策后守方损失上升、自身损失下降")
    void policyBonusActuallyChangesTheBattleOutcome() {
        ArmySide attacker = army("atk", 1000L);
        ArmySide defender = army("def", 1000L);
        ArmySide boosted = new ArmySide("atk", List.of(), units(1000L), TechBonus.none(), 0L,
                FormationType.STANDARD, Long.MAX_VALUE / 4, new OrgBonus(POLICY_15, 0L, 0L));
        long seed = 20260930L;

        BattleResult plain = BattleSimulator.simulate(input(attacker, defender, seed));
        BattleResult withPolicy = BattleSimulator.simulate(input(boosted, defender, seed));

        assertThat(withPolicy.defDead() + withPolicy.defWounded())
                .as("国策 +15% 攻方必须让守方更惨，否则这个乘区就是装饰")
                .isGreaterThan(plain.defDead() + plain.defWounded());
        assertThat(withPolicy.atkDead() + withPolicy.atkWounded())
                .isLessThan(plain.atkDead() + plain.atkWounded());
    }

    @Test
    @DisplayName("城墙只作用于防守方：把城墙加成填到攻方那一份上，这一战与无城墙逐位相同")
    void wallNeverTouchesTheAttackSide() {
        long seed = 20260930L;
        ArmySide plainAttacker = army("atk", 1000L);
        // 装配点填错位置时的样子：城墙挂到了攻方
        ArmySide wrongSide = army("atk", 1000L, new OrgBonus(0L, 0L, POLICY_15));
        ArmySide defender = army("def", 1000L);

        BattleResult plain = BattleSimulator.simulate(input(plainAttacker, defender, seed));
        BattleResult misrouted = BattleSimulator.simulate(input(wrongSide, defender, seed));

        assertThat(misrouted.atkDead()).isEqualTo(plain.atkDead());
        assertThat(misrouted.atkWounded()).isEqualTo(plain.atkWounded());
        assertThat(misrouted.defDead()).isEqualTo(plain.defDead());
        assertThat(misrouted.defWounded()).isEqualTo(plain.defWounded());
    }

    @Test
    @DisplayName("城墙真的进了结算：只有守方拿到城墙加成时，攻方损失才会上升")
    void wallActuallyChangesTheDefendersOutcome() {
        long seed = 20260930L;
        ArmySide attacker = army("atk", 1000L);
        ArmySide plainDefender = army("def", 1000L);
        ArmySide walledDefender = army("def", 1000L, new OrgBonus(0L, 0L, POLICY_15));

        BattleResult plain = BattleSimulator.simulate(input(attacker, plainDefender, seed));
        BattleResult walled = BattleSimulator.simulate(input(attacker, walledDefender, seed));

        assertThat(walled.atkDead() + walled.atkWounded())
                .as("守方城墙 +15% 必须让攻方更惨，否则乘区 H 没有任何消费方")
                .isGreaterThan(plain.atkDead() + plain.atkWounded());
    }

    // ---------- 夹具 ----------

    private static BattleInput input(ArmySide attacker, ArmySide defender, long seed) {
        return new BattleInput(attacker, defender, TerrainType.PLAIN, seed, BattleType.PVP_SOLO,
                BattleModifier.none(), BattleModifier.none(), t1Stats(), rules(), null);
    }

    private static Map<UnitType, Long> units(long infantry) {
        Map<UnitType, Long> units = new EnumMap<>(UnitType.class);
        units.put(UnitType.INFANTRY, infantry);
        return units;
    }

    private static ArmySide army(String id, long infantry) {
        return army(id, infantry, OrgBonus.none());
    }

    private static ArmySide army(String id, long infantry, OrgBonus orgBonus) {
        return new ArmySide(id, List.of(), units(infantry), TechBonus.none(), 0L,
                FormationType.STANDARD, Long.MAX_VALUE / 4, orgBonus);
    }

    private static Map<UnitType, Set<UnitType>> counterMatrix() {
        Map<UnitType, Set<UnitType>> m = new EnumMap<>(UnitType.class);
        m.put(UnitType.INFANTRY, EnumSet.of(UnitType.CAVALRY, UnitType.ARCHER));
        m.put(UnitType.CAVALRY, EnumSet.of(UnitType.INFANTRY, UnitType.ARCHER, UnitType.SIEGE));
        m.put(UnitType.ARCHER, EnumSet.of(UnitType.INFANTRY));
        m.put(UnitType.SIEGE, EnumSet.noneOf(UnitType.class));
        return m;
    }

    private static BattleRules rules() {
        return new BattleRules(
                8,
                FixedPoint.parse("7.0"),
                FixedPoint.parse("0.20"),
                FixedPoint.parse("0.95"),
                FixedPoint.parse("1.05"),
                FixedPoint.parse("0.5"),
                FixedPoint.parse("0.3"),
                FixedPoint.parse("0.2"),
                FixedPoint.parse("0.25"),
                FixedPoint.parse("0.20"),
                FixedPoint.parse("0.05"),
                FixedPoint.parse("0.20"),
                FixedPoint.parse("0.35"),
                FixedPoint.parse("0.15"),
                counterMatrix(), Map.of(), Map.of());
    }

    private static Map<UnitType, UnitStats> t1Stats() {
        Map<UnitType, UnitStats> s = new EnumMap<>(UnitType.class);
        s.put(UnitType.INFANTRY, stats(8, 12, 60, 4, 20, "0"));
        s.put(UnitType.CAVALRY, stats(12, 6, 45, 10, 12, "0"));
        s.put(UnitType.ARCHER, stats(14, 4, 35, 6, 6, "0"));
        s.put(UnitType.SIEGE, stats(7, 8, 80, 2, 40, "3.0"));
        return s;
    }

    private static UnitStats stats(int atk, int def, int hp, int speed, int load, String vsBuilding) {
        return new UnitStats(FixedPoint.of(atk), FixedPoint.of(def), FixedPoint.of(hp),
                speed, load, FixedPoint.parse(vsBuilding));
    }
}
