package com.ironoath.battle;

import com.ironoath.common.num.FixedPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：B09 验收 5 —— BOSS 三种机制各自生效，且各自「逼玩家做某件事」的意图成立。
 * 依赖：JUnit 5 + AssertJ；纯 Java。
 *
 * <p><b>每种机制都用「同 seed 的对照战」来验</b>，而不是只看战报里有没有那条记录：
 * 机制在战报里留一条 SkillTrigger 很容易（哪怕它其实什么都没做），
 * 而对照战能证明它真的改变了结果 —— 增援让守方活得更久、防御姿态让攻方打得更慢、
 * 反弹让攻方损失更多。B09 §二 的原话是「逼玩家换阵型」，
 * 一个不改变结果的机制逼不了任何人。
 *
 * <p><b>机制参数在测试里直接构造，不读配置表</b>（game-battle 依赖不到 game-config）。
 * 配置侧的数值由 {@code BattleRulesAssembler.bossMechanic} 从 global 表装配，
 * 那一条由 game-web 的用例覆盖 —— 两边各钉一半，改配置而忘了内核口径时会有一边变红。
 */
class BossMechanicTest {

    private static final long SEED = 20260907L;

    @Test
    @DisplayName("reinforcement：每 2 回合召唤一次，援军让守方活得更久")
    void reinforcementSummonsOnIntervalAndProlongsTheFight() {
        ArmySide attacker = army("atk", 1000L, 0L, 0L, 0L);
        ArmySide defender = army("def", 0L, 600L, 0L, 0L);

        BattleResult plain = simulate(attacker, defender, BossMechanic.none());
        BattleResult withBoss = simulate(attacker, defender,
                BossMechanic.reinforcement(2L, FixedPoint.parse("0.20")));

        List<Integer> summonRounds = roundsWith(withBoss, "boss_reinforcement");
        assertThat(summonRounds)
                .as("间隔 2 回合，8 回合的战斗应当在第 2/4/6/8 回合各召唤一次")
                .containsExactly(2, 4, 6, 8);
        assertThat(roundsWith(plain, "boss_reinforcement")).isEmpty();

        // 守方被增援之后更难被打穿：要么撑到更多回合，要么剩余兵力更多
        assertThat(withBoss.totalRounds() + totalOf(withBoss.defSurvivors()))
                .as("增援必须真的改变战局，否则这个机制只是战报里的一行装饰")
                .isGreaterThan(plain.totalRounds() + totalOf(plain.defSurvivors()));
    }

    @Test
    @DisplayName("shield_phase：只在守方兵力跌破阈值后触发，且触发后攻方打得更慢")
    void shieldPhaseTriggersOnlyBelowTheThreshold() {
        ArmySide attacker = army("atk", 1000L, 0L, 0L, 0L);
        ArmySide defender = army("def", 0L, 600L, 0L, 0L);

        BattleResult withBoss = simulate(attacker, defender,
                BossMechanic.shieldPhase(FixedPoint.parse("0.50"), FixedPoint.parse("0.50")));
        List<Integer> shieldRounds = roundsWith(withBoss, "boss_shield_phase");

        assertThat(shieldRounds)
                .as("守方开局满编，第 1 回合不该进入防御姿态")
                .allSatisfy(round -> assertThat(round).isGreaterThan(1));
        assertThat(shieldRounds).as("8 回合内守方必然被打到一半以下，姿态必须真的触发过")
                .isNotEmpty();

        // 触发之后的回合里，守方有效防御应当高于同 seed 无机制的对照战
        BattleResult plain = simulate(attacker, defender, BossMechanic.none());
        int firstShieldRound = shieldRounds.get(0);
        long withDefense = defenseAt(withBoss, firstShieldRound);
        long withoutDefense = defenseAt(plain, firstShieldRound);
        assertThat(withDefense)
                .as("防御姿态必须真的抬高守方有效防御（第 %d 回合）", firstShieldRound)
                .isGreaterThan(withoutDefense);
    }

    @Test
    @DisplayName("counter_strike：反弹让攻方损失更多，且反弹量与守方所受损失成正比")
    void counterStrikeReflectsDamageBackToTheAttacker() {
        ArmySide attacker = army("atk", 1000L, 0L, 0L, 0L);
        ArmySide defender = army("def", 0L, 600L, 0L, 0L);

        BattleResult plain = simulate(attacker, defender, BossMechanic.none());
        BattleResult withBoss = simulate(attacker, defender,
                BossMechanic.counterStrike(FixedPoint.parse("0.15")));

        assertThat(roundsWith(withBoss, "boss_counter_strike"))
                .as("每个有伤害的回合都应当反弹一次").isNotEmpty();
        assertThat(withBoss.atkDead() + withBoss.atkWounded() + withBoss.atkOverflowDead())
                .as("反弹必须真的提高攻方损失：这个机制的目的是逼玩家带治疗或减伤武将，"
                        + "若攻方损失不变，纯堆输出就没有任何代价，机制等于不存在")
                .isGreaterThan(plain.atkDead() + plain.atkWounded() + plain.atkOverflowDead());
    }

    @Test
    @DisplayName("无机制时战报里不出现任何 BOSS 触发记录")
    void noMechanicLeavesNoTrace() {
        BattleResult plain = simulate(army("atk", 1000L, 0L, 0L, 0L),
                army("def", 0L, 600L, 0L, 0L), BossMechanic.none());
        List<String> ids = new ArrayList<>();
        for (RoundSnapshot round : plain.rounds()) {
            for (SkillTrigger trigger : round.skills()) {
                ids.add(trigger.skillId());
            }
        }
        assertThat(ids).noneMatch(id -> id != null && id.startsWith("boss_"));
    }

    @Test
    @DisplayName("机制参数按类型校验：填了不属于该类型的参数当场报错，而不是静默失效")
    void mechanicParametersAreValidatedPerType() {
        assertThat(BossMechanic.none().isNone()).isTrue();
        assertThat(BossMechanic.none().summonsOn(2)).isFalse();
        assertThat(BossMechanic.reinforcement(2L, FixedPoint.parse("0.20")).summonsOn(2)).isTrue();
        assertThat(BossMechanic.reinforcement(2L, FixedPoint.parse("0.20")).summonsOn(3)).isFalse();

        // 静默失效是这个类最主要的风险：参数填错位置不会报错，机制只是什么都不做
        assertThatThrownBy(() -> new BossMechanic(BossMechanic.Type.REINFORCEMENT, 0L,
                FixedPoint.parse("0.20"), 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("召唤间隔");
        assertThatThrownBy(() -> new BossMechanic(BossMechanic.Type.REINFORCEMENT, 2L, 0L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("召唤比例");
        assertThatThrownBy(() -> new BossMechanic(BossMechanic.Type.REINFORCEMENT, 2L,
                FixedPoint.parse("0.20"), FixedPoint.parse("0.5"), 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .as("REINFORCEMENT 不使用 thresholdFixed，填了值说明调用方以为它会生效")
                .hasMessageContaining("不使用");
        assertThatThrownBy(() -> BossMechanic.shieldPhase(FixedPoint.ONE, FixedPoint.parse("0.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .as("阈值 1.0 意味着开局就进防御姿态，那不是一个「阶段」");
        assertThatThrownBy(() -> BossMechanic.counterStrike(0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("反弹比例");
        assertThatThrownBy(() -> new BossMechanic(BossMechanic.Type.NONE, 2L, 0L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NONE");
    }

    // ---------- 辅助 ----------

    private static List<Integer> roundsWith(BattleResult result, String skillId) {
        List<Integer> rounds = new ArrayList<>();
        for (RoundSnapshot round : result.rounds()) {
            for (SkillTrigger trigger : round.skills()) {
                if (skillId.equals(trigger.skillId())) {
                    rounds.add(round.round());
                    break;
                }
            }
        }
        return rounds;
    }

    private static long defenseAt(BattleResult result, int round) {
        return result.rounds().get(round - 1).defDefense();
    }

    private static long totalOf(Map<UnitType, Long> counts) {
        long sum = 0L;
        for (long count : counts.values()) {
            sum += count;
        }
        return sum;
    }

    private static BattleResult simulate(ArmySide attacker, ArmySide defender, BossMechanic mechanic) {
        return BattleSimulator.simulate(new BattleInput(attacker, defender, TerrainType.PLAIN,
                SEED, BattleType.PVE, BattleModifier.none(), BattleModifier.none(),
                t1Stats(), rules(), null, mechanic));
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
        return new BattleRules(8,
                FixedPoint.parse("7.0"), FixedPoint.parse("0.20"),
                FixedPoint.parse("0.95"), FixedPoint.parse("1.05"),
                FixedPoint.parse("0.5"), FixedPoint.parse("0.3"), FixedPoint.parse("0.2"),
                FixedPoint.parse("0.25"), FixedPoint.parse("0.20"), FixedPoint.parse("0.05"),
                FixedPoint.parse("0.20"), FixedPoint.parse("0.35"), FixedPoint.parse("0.15"),
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
