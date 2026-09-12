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
 * 职责：B08 验收 9 —— 复仇 +15% / 哀兵 +10% / 围剿 +15%，且落在<b>独立</b>的乘区 F。
 * 依赖：JUnit 5 + AssertJ；纯 Java，不读配置表（game-battle 不许依赖 game-config）。
 *
 * <p><b>「独立乘区」是这条验收的全部内容</b>，而它最容易被做成假的：
 * 只要有人图省事把六项加成先相加再乘一次，结果就会偏小（(1+a)(1+b) ≠ 1+a+b），
 * 而且从此再也拆不回来 —— 战报里只剩一个「总加成」，
 * 出了数值争议没人能说出到底是哪一条规则算错了。
 * 所以这里既验证数值，也验证「F 与其它乘区是分别相乘的」。
 *
 * <p>配置侧的三个数值（BONUS_REVENGE / BONUS_MOURNING / BONUS_SIEGE_PUBLIC_ENEMY）
 * 由 game-web 的 {@code PowerContractParityTest} 对着 global 表断言，
 * 本类用的是它们对应的定点字面量 —— 两处各钉一半，改配置而忘了内核口径时会有一边变红。
 *
 * <p><b>本类不验证「谁该拿到哪个加成」</b>：复仇要有 24h 内被同一人攻击的记录（B09 的战报），
 * 哀兵要参与防守集结（B10），围剿要看目标的暴虐档位（{@code Tyranny.triggersCrusade}，已交付）。
 * 判定归调用方，内核只做乘法 —— 这正是 {@link BattleInput} 把 modifier 拆成攻守两份的原因。
 */
class BattleModifierTest {

    /** B08 §6 给的三项加成，定点形式。 */
    private static final long REVENGE = 1500L;      // +15%
    private static final long AGGRIEVED = 1000L;    // +10%
    private static final long CRUSADE = 1500L;      // +15%

    @Test
    @DisplayName("验收9：三项加成的数值与 B08 §6 一致，且四项各自独立存储")
    void theThreeBonusesCarryTheSpecifiedValues() {
        BattleModifier revengeOnly = new BattleModifier(REVENGE, 0L, 0L, 0L);
        assertThat(revengeOnly.totalFixed()).isEqualTo(REVENGE);
        assertThat(new BattleModifier(0L, AGGRIEVED, 0L, 0L).totalFixed()).isEqualTo(AGGRIEVED);
        assertThat(new BattleModifier(0L, 0L, CRUSADE, 0L).totalFixed()).isEqualTo(CRUSADE);
        // 三项同时生效（复仇 + 围剿公敌，防守方另有哀兵）时按增量相加，
        // 相加只发生在乘区 F 内部 —— F 之外的五个乘区仍然各自相乘
        assertThat(new BattleModifier(REVENGE, AGGRIEVED, CRUSADE, 0L).totalFixed())
                .isEqualTo(4000L);
        assertThat(BattleModifier.none().totalFixed()).isZero();
    }

    @Test
    @DisplayName("乘区 F 与其它乘区分别相乘：F 从 0 变成 +15% 时，合乘数精确变为 1.15 倍")
    void zoneFMultipliesIndependentlyOfOtherZones() {
        AttackMultipliers withoutF = new AttackMultipliers(
                FixedPoint.parse("0.10"),   // 武将 +10%
                FixedPoint.parse("0.05"),   // 科技 +5%
                0L, FixedPoint.ONE, 0L, 0L);
        AttackMultipliers withF = new AttackMultipliers(
                FixedPoint.parse("0.10"), FixedPoint.parse("0.05"),
                0L, FixedPoint.ONE, 0L, REVENGE);

        long expected = FixedPoint.mul(withoutF.compose(), FixedPoint.ONE + REVENGE);
        assertThat(withF.compose())
                .as("F 必须作为独立乘区参与相乘；若六项先加后乘，这里会小一截且再也拆不回来")
                .isEqualTo(expected);
        assertThat(withF.compose()).isGreaterThan(withoutF.compose());

        // 反证：把 F 的增量并入武将乘区，结果与「先加后乘」一样偏小 —— 那就是错误的实现
        AttackMultipliers merged = new AttackMultipliers(
                FixedPoint.parse("0.10") + REVENGE, FixedPoint.parse("0.05"),
                0L, FixedPoint.ONE, 0L, 0L);
        assertThat(merged.compose())
                .as("合并源头会得到一个更小且无法拆解的数，这正是禁止合并的原因")
                .isNotEqualTo(withF.compose());
    }

    @Test
    @DisplayName("乘区 F 真的进了战斗内核：同种子下，拿到复仇加成的一方损失更少")
    void revengeBonusActuallyChangesTheBattleOutcome() {
        ArmySide attacker = army("atk", 1000L, 0L, 0L, 0L);
        ArmySide defender = army("def", 1000L, 0L, 0L, 0L);

        BattleResult plain = BattleSimulator.simulate(input(attacker, defender,
                BattleModifier.none(), BattleModifier.none(), 20260907L));
        BattleResult revenged = BattleSimulator.simulate(input(attacker, defender,
                new BattleModifier(REVENGE, 0L, 0L, 0L), BattleModifier.none(), 20260907L));

        assertThat(revenged.atkDead() + revenged.atkWounded())
                .as("复仇 +15% 必须让攻方损失下降，否则这个加成就是装饰（B08 §6：只奖励行为）")
                .isLessThan(plain.atkDead() + plain.atkWounded());
        assertThat(revenged.defDead() + revenged.defWounded())
                .as("同一场战斗里守方损失应当上升")
                .isGreaterThan(plain.defDead() + plain.defWounded());
    }

    @Test
    @DisplayName("哀兵加成给防守方，围剿加成给攻方：两份 modifier 互不串台")
    void attackerAndDefenderModifiersAreIndependent() {
        ArmySide attacker = army("atk", 1000L, 0L, 0L, 0L);
        ArmySide defender = army("def", 1000L, 0L, 0L, 0L);
        long seed = 20260907L;

        BattleResult plain = BattleSimulator.simulate(input(attacker, defender,
                BattleModifier.none(), BattleModifier.none(), seed));
        // 哀兵 +10% 只对防守集结成立，所以它必须挂在守方那一份上
        BattleResult mourning = BattleSimulator.simulate(input(attacker, defender,
                BattleModifier.none(), new BattleModifier(0L, AGGRIEVED, 0L, 0L), seed));
        assertThat(mourning.defDead() + mourning.defWounded())
                .as("守方拿到哀兵加成后损失应当下降")
                .isLessThan(plain.defDead() + plain.defWounded());

        // 围剿公敌 +15% 只对攻方成立
        BattleResult crusade = BattleSimulator.simulate(input(attacker, defender,
                new BattleModifier(0L, 0L, CRUSADE, 0L), BattleModifier.none(), seed));
        assertThat(crusade.atkDead() + crusade.atkWounded())
                .as("攻方拿到围剿加成后损失应当下降")
                .isLessThan(plain.atkDead() + plain.atkWounded());
        assertThat(crusade.defDead() + crusade.defWounded())
                .as("攻方的围剿加成不该同时给守方带来任何好处")
                .isGreaterThan(plain.defDead() + plain.defWounded());

        // 双方各拿一份。这里只与「单侧用例」比较，不与无加成比较：
        // 每次比较都只改了一侧的乘区，所以方向是机制上确定的。
        // 与 plain 比较则不确定 —— 守方的加成会通过伤害反过来抬高攻方的损失，
        // 净方向取决于回合数与伤害曲线，那不是本条验收要验的东西
        BattleResult both = BattleSimulator.simulate(input(attacker, defender,
                new BattleModifier(0L, 0L, CRUSADE, 0L),
                new BattleModifier(0L, AGGRIEVED, 0L, 0L), seed));
        assertThat(both.atkDead() + both.atkWounded())
                .as("在「守方哀兵」之上再给攻方围剿，攻方损失必须下降")
                .isLessThan(mourning.atkDead() + mourning.atkWounded());
        assertThat(both.defDead() + both.defWounded())
                .as("在「守方哀兵」之上再给攻方围剿，守方损失必须上升")
                .isGreaterThan(mourning.defDead() + mourning.defWounded());
        assertThat(both.atkDead() + both.atkWounded())
                .as("在「攻方围剿」之上再给守方哀兵，攻方损失必须上升")
                .isGreaterThan(crusade.atkDead() + crusade.atkWounded());
        assertThat(both.defDead() + both.defWounded())
                .as("在「攻方围剿」之上再给守方哀兵，守方损失必须下降")
                .isLessThan(crusade.defDead() + crusade.defWounded());
    }

    @Test
    @DisplayName("加成不得为负：一个负的「加成」就是基于战力差的惩罚，属于 B08 头号禁止项")
    void negativeBonusIsRejected() {
        assertThatThrownBy(() -> new BattleModifier(-1L, 0L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("revenge");
        assertThatThrownBy(() -> new BattleModifier(0L, 0L, 0L, -1500L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("terrainAdv");
    }

    // ---------- 夹具（与 BattleSimulatorTest 同一套口径） ----------

    private static BattleInput input(ArmySide attacker, ArmySide defender,
                                     BattleModifier attackerModifier, BattleModifier defenderModifier,
                                     long seed) {
        return new BattleInput(attacker, defender, TerrainType.PLAIN, seed, BattleType.PVP_SOLO,
                attackerModifier, defenderModifier, t1Stats(), rules(), null);
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

    private static ArmySide army(String id, long infantry, long cavalry, long archer, long siege) {
        Map<UnitType, Long> units = new EnumMap<>(UnitType.class);
        units.put(UnitType.INFANTRY, infantry);
        units.put(UnitType.CAVALRY, cavalry);
        units.put(UnitType.ARCHER, archer);
        units.put(UnitType.SIEGE, siege);
        return new ArmySide(id, List.of(), units, TechBonus.none(), 0L,
                FormationType.STANDARD, Long.MAX_VALUE / 4);
    }
}
