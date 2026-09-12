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

/**
 * 职责：钉住「治疗能把本场累计损失压到 0，因此阵亡为 0、无损可达」这条不变量。
 * 依赖：JUnit 5 + AssertJ；纯 Java。
 *
 * <p><b>为什么这条不变量值得单独一个测试类</b>：B09 的三星里有一颗是「无损」（己方阵亡为 0），
 * 而战斗内核的攻方损失约等于「敌方总兵力 / LANCHESTER_K」，与自己带多少兵无关 ——
 * 也就是说<b>靠堆兵永远拿不到无损</b>（敌方超过约 17 兵时阵亡就不为 0）。
 * 无损唯一的通路是 HEAL 技能：{@code applyHeal} 不只是把兵加回 counts，
 * 还会从 {@code cumulativeLoss} 这个「本场累计损失池」里扣减，
 * 而战后的死亡/伤兵拆算读的正是这个池子。
 *
 * <p>于是整条设计闭环成立：无损三星 ⇒ 必须带治疗武将 ⇒ 正是 B09 §二 想要的
 * 「卡住玩家的原因是阵容不对而不是练度不够」（换武将免费，堆练度要花钱）。
 *
 * <p><b>但它极其脆弱</b>：如果有人把 applyHeal「简化」成只恢复 counts、不减 cumulativeLoss，
 * 治疗看起来仍然有效（兵确实回来了），战报的回合快照也仍然对，
 * 唯一的变化是「无损」这一星从此永久不可达 —— 而没有任何其它测试会失败。
 * 玩家只会说「三星是不是有 bug」，而排查的人会先怀疑关卡数值。
 * 所以这条不变量必须有自己的断言。
 */
class HealEnablesNoLossTest {

    @Test
    @DisplayName("没有治疗时会阵亡；带满额治疗时累计损失被压到 0，阵亡与伤兵都为 0")
    void healingDrainsTheCumulativeLossPoolSoNoLossIsReachable() {
        ArmySide attacker = army("atk", 1000L);
        ArmySide defender = army("def", 1000L);
        long seed = 20260907L;

        BattleResult withoutHeal = BattleSimulator.simulate(input(attacker, defender,
                List.of(), seed));
        assertThat(withoutHeal.atkDead() + withoutHeal.atkWounded())
                .as("夹具前提：势均力敌时必须真的有损失，否则本用例测不出任何东西")
                .isPositive();

        // 每回合必定触发、回复比例 100%（= 把当回合累计损失全部捞回来）
        HeroSnapshot healer = new HeroSnapshot("hero_healer", 0, 0L, 0L, List.of(
                new SkillSnapshot("skill_heal_test", SkillPhase.EVERY_ROUND,
                        FixedPoint.ONE, SkillEffect.HEAL, FixedPoint.ONE, 1)));
        BattleResult withHeal = BattleSimulator.simulate(input(attacker, defender,
                List.of(healer), seed));

        assertThat(withHeal.atkDead())
                .as("治疗必须把「本场累计损失池」抽干，而不只是把兵加回当前数量 —— "
                        + "战后的死亡/伤兵拆算读的是那个池子。若这里不为 0，"
                        + "说明 applyHeal 少了扣减 cumulativeLoss 的那一步，"
                        + "于是 B09 的「无损」三星从此永久不可达")
                .isZero();
        assertThat(withHeal.atkWounded()).isZero();
        assertThat(withHeal.atkOverflowDead()).isZero();

        // 治疗不该让战斗变成「打不死人」：守方仍然要损失，胜负仍然要分出来
        assertThat(withHeal.defDead() + withHeal.defWounded())
                .as("治疗只作用于己方损失池，不该影响对方的伤亡")
                .isPositive();
        assertThat(withHeal.atkSurvivors())
                .as("治疗之后攻方应当几乎满编")
                .containsEntry(UnitType.INFANTRY, 1000L);
    }

    @Test
    @DisplayName("治疗量受「本场累计损失」上限约束：不会凭空造兵")
    void healingCannotCreateTroopsOutOfNothing() {
        // 守方极弱、一回合就被打光：攻方几乎没有损失，此时 100% 治疗也只能捞回 0
        ArmySide attacker = army("atk", 1000L);
        ArmySide weakDefender = army("def", 5L);
        HeroSnapshot healer = new HeroSnapshot("hero_healer", 0, 0L, 0L, List.of(
                new SkillSnapshot("skill_heal_test", SkillPhase.EVERY_ROUND,
                        FixedPoint.ONE, SkillEffect.HEAL, FixedPoint.ONE, 1)));

        BattleResult result = BattleSimulator.simulate(input(attacker, weakDefender,
                List.of(healer), 20260907L));
        long survivors = result.atkSurvivors().values().stream().mapToLong(Long::longValue).sum();
        assertThat(survivors)
                .as("HEAL 只能从累计损失池里往回捞，不能凭空造兵，所以存活数不得超过出征数")
                .isLessThanOrEqualTo(1000L);
        assertThat(result.winner()).isEqualTo(Winner.ATTACKER);
    }

    // ---------- 夹具 ----------

    private static BattleInput input(ArmySide attacker, ArmySide defender,
                                     List<HeroSnapshot> attackerHeroes, long seed) {
        ArmySide atk = new ArmySide(attacker.sideId(), attackerHeroes, attacker.units(),
                TechBonus.none(), 0L, FormationType.STANDARD, attacker.hospitalCapacity());
        return new BattleInput(atk, defender, TerrainType.PLAIN, seed, BattleType.PVE,
                BattleModifier.none(), BattleModifier.none(), t1Stats(), rules(), null);
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

    private static ArmySide army(String id, long infantry) {
        Map<UnitType, Long> units = new EnumMap<>(UnitType.class);
        units.put(UnitType.INFANTRY, infantry);
        units.put(UnitType.CAVALRY, 0L);
        units.put(UnitType.ARCHER, 0L);
        units.put(UnitType.SIEGE, 0L);
        return new ArmySide(id, List.of(), units, TechBonus.none(), 0L,
                FormationType.STANDARD, Long.MAX_VALUE / 4);
    }
}
