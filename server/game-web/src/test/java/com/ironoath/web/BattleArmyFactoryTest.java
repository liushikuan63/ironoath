package com.ironoath.web;

import com.ironoath.battle.ArmySide;
import com.ironoath.battle.FormationType;
import com.ironoath.battle.TechBonus;
import com.ironoath.battle.UnitStats;
import com.ironoath.battle.UnitType;
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.UnitCfg;
import com.ironoath.web.battle.BattleArmyFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：验证「混合阶级按数量加权平均折成每兵种一份属性」是<b>精确等价</b>而不是近似。
 * 依赖：Spring Boot Test（要真实的 unit 表）；test profile。
 *
 * <p><b>这是 B07 把 ATTACK 推迟到 B09 时不敢拍的那个口径</b>，所以本类的核心断言不是
 * 「折算出来的数字好不好看」，而是「折算前后的总属性质量是否守恒」：
 * <pre>
 *   |折算后数量 × 折算后属性 − Σ(各阶级数量 × 各阶级属性)| ≤ 总数量 / 2
 * </pre>
 * 右边那一项是「最后一次除法四舍五入」的误差上界（每个兵最多半个定点单位）。
 * 之所以这条断言就等于「精确等价」，是因为战斗内核的两个公式都对「数量 × 属性」线性：
 * <pre>
 *   有效攻击 = Σ(数量 × 单位攻击 × 乘区)
 *   有效防御 = Σ(数量 × (单位防御 + 单位生命 × HP_DEFENSE_WEIGHT) × 加成)
 * </pre>
 * 质量守恒 ⇒ 有效攻击与有效防御逐一相等 ⇒ 内核看到的是一支完全等价的军队。
 *
 * <p>反过来，另外两种口径都能被这条断言当场否掉：「按最高阶级」会让质量凭空变大
 * （混编队伍白拿高阶属性），「按最低阶级」会让质量凭空变小（高阶兵白练）。
 */
@SpringBootTest
@ActiveProfiles("test")
class BattleArmyFactoryTest {

    @Autowired private BattleArmyFactory factory;
    @Autowired private ConfigRegistry configs;

    @Test
    @DisplayName("单一阶级：折算结果就是 unit 表原值，数量原样保留")
    void singleTierFoldsToTheTableRowItself() {
        var folded = factory.fold(Map.of("unit_infantry_t3", 250L));

        assertThat(folded.counts()).containsOnly(
                Map.entry(UnitType.INFANTRY, 250L),
                Map.entry(UnitType.CAVALRY, 0L),
                Map.entry(UnitType.ARCHER, 0L),
                Map.entry(UnitType.SIEGE, 0L));
        UnitCfg cfg = configs.get(UnitCfg.class, "unit_infantry_t3");
        UnitStats stats = folded.stats().get(UnitType.INFANTRY);
        assertThat(stats.attackFixed()).isEqualTo(FixedPoint.of(cfg.attack()));
        assertThat(stats.defenseFixed()).isEqualTo(FixedPoint.of(cfg.defense()));
        assertThat(stats.hpFixed()).isEqualTo(FixedPoint.of(cfg.hp()));
        assertThat(stats.load()).isEqualTo(cfg.load());
        assertThat(stats.speed()).isEqualTo(cfg.speed());
    }

    @Test
    @DisplayName("混合阶级的总属性质量守恒：攻击/防御/生命/负载/对建筑逐项验，误差只在最后一次取整")
    void mixedTiersConserveTotalAttributeMass() {
        // 刻意用不能整除的数量组合：能整除时取整误差为 0，测不出误差处理是否正确
        Map<String, Long> units = new LinkedHashMap<>();
        units.put("unit_infantry_t1", 3L);
        units.put("unit_infantry_t3", 5L);
        units.put("unit_infantry_t5", 7L);
        units.put("unit_cavalry_t2", 11L);
        units.put("unit_cavalry_t4", 13L);
        units.put("unit_siege_t1", 1L);
        units.put("unit_siege_t5", 2L);

        var folded = factory.fold(units);
        for (UnitType type : UnitType.values()) {
            long total = folded.counts().get(type);
            if (total == 0L) {
                continue;
            }
            UnitStats stats = folded.stats().get(type);
            assertMassConserved(type, units, total, stats.attackFixed(), UnitCfg::attack, true);
            assertMassConserved(type, units, total, stats.defenseFixed(), UnitCfg::defense, true);
            assertMassConserved(type, units, total, stats.hpFixed(), UnitCfg::hp, true);
            assertMassConserved(type, units, total, stats.load(), UnitCfg::load, false);
            assertMassConserved(type, units, total, stats.vsBuildingBonusFixed(),
                    UnitCfg::vsBuildingBonus, false);
        }
        assertThat(folded.totalUnits()).isEqualTo(3 + 5 + 7 + 11 + 13 + 1 + 2);
    }

    @Test
    @DisplayName("同兵种内速度取最慢的阶级，与「队伍速度取最慢兵种」是同一条口径的两个层级")
    void speedWithinATypeTakesTheSlowestTier() {
        // 骑兵各阶级速度本就相同，所以用攻城器（若表里各阶级同速，则断言退化为等于表值，
        // 但「取 min」这个实现仍然被覆盖：任何一级更快都会让 min 选到更小的那个）
        var folded = factory.fold(Map.of("unit_cavalry_t1", 10L, "unit_cavalry_t5", 10L));
        long t1 = configs.get(UnitCfg.class, "unit_cavalry_t1").speed();
        long t5 = configs.get(UnitCfg.class, "unit_cavalry_t5").speed();
        assertThat(folded.stats().get(UnitType.CAVALRY).speed()).isEqualTo(Math.min(t1, t5));
    }

    @Test
    @DisplayName("缺席的兵种也必须有合法属性：BattleInput 要求四个兵种齐全，缺一个就在内核里 NPE")
    void absentTypesStillGetLegalPlaceholderStats() {
        var folded = factory.fold(Map.of("unit_infantry_t1", 10L));
        assertThat(folded.counts()).containsOnlyKeys(UnitType.values());
        assertThat(folded.stats()).containsOnlyKeys(UnitType.values());
        for (UnitType type : UnitType.values()) {
            UnitStats stats = folded.stats().get(type);
            assertThat(stats.attackFixed()).as("%s 的攻击必须为正", type).isPositive();
            assertThat(stats.defenseFixed()).as("%s 的防御必须为正", type).isPositive();
            assertThat(stats.hpFixed()).as("%s 的生命必须为正", type).isPositive();
        }
        // 占位取的是该兵种阶级最低的那一行
        UnitCfg siegeT1 = configs.get(UnitCfg.class, "unit_siege_t1");
        assertThat(folded.stats().get(UnitType.SIEGE).attackFixed())
                .isEqualTo(FixedPoint.of(siegeT1.attack()));
    }

    @Test
    @DisplayName("折算结果与入参 map 的顺序无关：同一支队伍在两台机器上必须折成同一份属性")
    void foldingIsIndependentOfInputOrder() {
        Map<String, Long> a = new LinkedHashMap<>();
        a.put("unit_infantry_t1", 100L);
        a.put("unit_infantry_t5", 37L);
        a.put("unit_archer_t3", 11L);
        Map<String, Long> b = new LinkedHashMap<>();
        b.put("unit_archer_t3", 11L);
        b.put("unit_infantry_t5", 37L);
        b.put("unit_infantry_t1", 100L);

        assertThat(factory.fold(b).stats()).isEqualTo(factory.fold(a).stats());
        assertThat(factory.fold(b).counts()).isEqualTo(factory.fold(a).counts());
    }

    @Test
    @DisplayName("空队伍与 0 数量条目被忽略；伪造的 unitId 当场拒绝而不是当成 0 攻 0 防")
    void emptyAndUnknownUnitsAreHandled() {
        var empty = factory.fold(Map.of());
        assertThat(empty.totalUnits()).isZero();
        assertThat(empty.counts()).containsOnlyKeys(UnitType.values());

        var zeros = factory.fold(Map.of("unit_infantry_t1", 0L, "unit_cavalry_t2", -5L));
        assertThat(zeros.totalUnits()).as("0 与负数都不算随行").isZero();

        assertThatThrownBy(() -> factory.fold(Map.of("unit_infantry_t99", 10L)))
                .isInstanceOf(BizException.class)
                .as("客户端可以随便编一个 unitId 发过来，所以这是入口校验")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.CONFIG_NOT_FOUND);
        assertThatThrownBy(() -> factory.fold(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toSide 保持三个乘区各自独立：装备归装备、科技按传入值落进 ArmySide、阵型恒为 STANDARD")
    void toSideKeepsEquipmentInItsOwnMultiplierZone() {
        var folded = factory.fold(Map.of("unit_infantry_t1", 100L));
        long equipBonus = FixedPoint.parse("0.15");
        ArmySide side = factory.toSide("attacker", folded, List.of(), equipBonus,
                new TechBonus(450L, 300L), 500L);

        assertThat(side.sideId()).isEqualTo("attacker");
        assertThat(side.units()).isEqualTo(folded.counts());
        assertThat(side.equipBonusFixed())
                .as("装备套装必须是独立乘区：合进武将乘区就违反 B06 禁止项「不要让武将加成污染其他乘区」")
                .isEqualTo(equipBonus);
        assertThat(side.techBonus().attackFixed())
                .as("乘区 B 必须原样带进内核，否则「联盟科技生效了吗」在战报里无从追溯")
                .isEqualTo(450L);
        assertThat(side.techBonus().defenseFixed()).isEqualTo(300L);
        ArmySide noTech = factory.toSide("attacker", folded, List.of(), 0L, null, 0L);
        assertThat(noTech.techBonus().attackFixed())
                .as("没有科技时是明确的 0，而不是 1.0 之类的假值 —— 传假值会让人分不清"
                        + "「科技生效了」和「一直有个常数在加」")
                .isZero();
        assertThat(noTech.techBonus().defenseFixed()).isZero();
        assertThat(side.formation()).isEqualTo(FormationType.STANDARD);
        assertThat(side.hospitalCapacity()).isEqualTo(500L);
        assertThat(side.heroes()).isEmpty();
    }

    // ---------- 辅助 ----------

    /**
     * 断言「折算后数量 × 折算后属性」与「各阶级数量 × 各阶级属性之和」的差不超过总数量的一半。
     *
     * @param toFixed 该字段在 unit 表里是否以定点存储（攻击/防御/生命是整数需要 ×10000，
     *                负载与对建筑加成已经是最终量纲）
     */
    private void assertMassConserved(UnitType type, Map<String, Long> units, long total,
                                     long foldedStat,
                                     java.util.function.ToLongFunction<UnitCfg> getter,
                                     boolean scaleUp) {
        long exactMass = 0L;
        for (Map.Entry<String, Long> entry : units.entrySet()) {
            if (entry.getValue() <= 0L) {
                continue;
            }
            UnitCfg cfg = configs.get(UnitCfg.class, entry.getKey());
            if (UnitType.valueOf(cfg.type().name()) != type) {
                continue;
            }
            long stat = scaleUp ? FixedPoint.of(getter.applyAsLong(cfg)) : getter.applyAsLong(cfg);
            exactMass += entry.getValue() * stat;
        }
        long foldedMass = total * foldedStat;
        assertThat(Math.abs(foldedMass - exactMass))
                .as("%s 的折算必须守恒：折算后质量=%d，逐阶级求和=%d，允许误差=%d（最后一次取整，每兵最多半个单位）",
                        type, foldedMass, exactMass, total / 2)
                .isLessThanOrEqualTo(total / 2);
    }
}
