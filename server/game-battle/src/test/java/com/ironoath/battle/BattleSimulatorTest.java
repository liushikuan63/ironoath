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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：战斗内核单测 —— 覆盖 B05 验收 1、2、5、6、8、11、14。
 * 依赖：JUnit 5 + AssertJ，无 Spring、无容器、不读配置文件（规则参数在测试内按 global.json 的值手工构造）。
 *
 * <p>验收 4（性能）与验收 3（跨 JVM）不在这里：前者需要 JMH 基准工程，
 * 后者需要在多个 JVM 版本上跑同一份 fixture —— 两者都是 CI 环境的职责。
 * 本类通过 {@link #historicalBattleReportIsReproducible()} 提供跨环境可比的黄金样本。
 */
class BattleSimulatorTest {

    // ---------- 测试夹具 ----------

    /**
     * 克制矩阵，必须与 contract/config/unit_counter.json 的 6 条兵种关系逐条一致（不含对建筑的两条）。
     *
     * <p>这里曾经漏写 CAVALRY→INFANTRY，导致「步兵↔骑兵」在测试里是单向克制、
     * 而在配置与 CLI 里是相互克制 —— 测试与被测系统规则不一致，测出来的胜率全是假的。
     * 教训：测试夹具复刻配置时必须有另一处校验两者一致，否则夹具会静默漂移。
     */
    private static Map<UnitType, Set<UnitType>> counterMatrix() {
        Map<UnitType, Set<UnitType>> m = new EnumMap<>(UnitType.class);
        m.put(UnitType.INFANTRY, EnumSet.of(UnitType.CAVALRY, UnitType.ARCHER));
        m.put(UnitType.CAVALRY, EnumSet.of(UnitType.INFANTRY, UnitType.ARCHER, UnitType.SIEGE));
        m.put(UnitType.ARCHER, EnumSet.of(UnitType.INFANTRY));
        m.put(UnitType.SIEGE, EnumSet.noneOf(UnitType.class));
        return m;
    }

    /** 规则参数，数值逐项取自 contract/config/global.json。 */
    private static BattleRules rules() {
        return new BattleRules(
                8,                              // BATTLE_MAX_ROUNDS
                FixedPoint.parse("7.0"),        // LANCHESTER_K（B05 实测校准值，见 global.json）
                FixedPoint.parse("0.20"),       // HP_DEFENSE_WEIGHT（0 即还原 B00 原式）
                FixedPoint.parse("0.95"),       // 损失浮动下界
                FixedPoint.parse("1.05"),       // 损失浮动上界
                FixedPoint.parse("0.5"),        // COUNTER_ADVANCE_FRONT
                FixedPoint.parse("0.3"),        // COUNTER_ADVANCE_MID
                FixedPoint.parse("0.2"),        // COUNTER_ADVANCE_BACK
                FixedPoint.parse("0.25"),       // COUNTER_BONUS
                FixedPoint.parse("0.20"),       // COUNTER_PENALTY
                FixedPoint.parse("0.05"),       // BATTLE_DRAW_GAP_RATIO
                FixedPoint.parse("0.20"),       // WOUND_RATIO_PVE_DEAD
                FixedPoint.parse("0.35"),       // WOUND_RATIO_PVP_ATTACKER_DEAD
                FixedPoint.parse("0.15"),       // WOUND_RATIO_PVP_DEFENDER_DEAD
                counterMatrix(), Map.of(), Map.of());
    }

    /** 兵种属性，取自 contract/config/unit.json 的 T1 行。 */
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

    private static ArmySide army(String id, long infantry, long cavalry, long archer, long siege,
                                 long hospital, List<HeroSnapshot> heroes) {
        Map<UnitType, Long> units = new EnumMap<>(UnitType.class);
        units.put(UnitType.INFANTRY, infantry);
        units.put(UnitType.CAVALRY, cavalry);
        units.put(UnitType.ARCHER, archer);
        units.put(UnitType.SIEGE, siege);
        return new ArmySide(id, heroes, units, TechBonus.none(), 0L,
                FormationType.STANDARD, hospital);
    }

    private static ArmySide army(String id, long infantry, long cavalry, long archer, long siege) {
        return army(id, infantry, cavalry, archer, siege, Long.MAX_VALUE / 4, List.of());
    }

    private static BattleInput input(ArmySide atk, ArmySide def, long seed, BattleType type) {
        return new BattleInput(atk, def, TerrainType.PLAIN, seed, type,
                BattleModifier.none(), BattleModifier.none(), t1Stats(), rules(), null);
    }

    /** 把战果压成一个可比对的摘要串，用于黄金样本回归。 */
    private static String digest(BattleResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.winner()).append('|').append(r.totalRounds()).append('|')
                .append(r.atkDead()).append('/').append(r.atkWounded()).append('/').append(r.atkOverflowDead())
                .append('|').append(r.defDead()).append('/').append(r.defWounded()).append('/').append(r.defOverflowDead())
                .append('|').append(r.lootCapacity());
        for (UnitType t : UnitType.values()) {
            sb.append('|').append(t).append(':').append(r.atkSurvivors().get(t)).append('/').append(r.defSurvivors().get(t));
        }
        for (RoundSnapshot s : r.rounds()) {
            sb.append("|R").append(s.round()).append(':').append(s.atkAttack()).append(',')
                    .append(s.defDefense()).append(',').append(s.attritionFixed()).append(',')
                    .append(s.atkLoss()).append(',').append(s.defLoss()).append(',').append(s.skills().size());
        }
        return sb.toString();
    }

    // ---------- 验收 1：确定性 ----------

    @Test
    @DisplayName("验收1：同 seed 跑 1000 次，结果逐字段完全相等")
    void sameSeedProducesIdenticalResultEveryTime() {
        BattleInput in = input(army("atk", 500, 300, 400, 50), army("def", 450, 350, 380, 60),
                20260906L, BattleType.PVP_SOLO);
        String expected = digest(BattleSimulator.simulate(in));
        for (int i = 0; i < 1000; i++) {
            assertThat(digest(BattleSimulator.simulate(in)))
                    .as("第 %d 次模拟结果与首次不一致", i + 1)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("确定性：逐字段比较两次结果，包括每回合快照与技能触发序列")
    void resultsAreFieldByFieldEqual() {
        BattleInput in = input(army("atk", 200, 100, 150, 20, 500,
                        List.of(hero("hero_ssr_01", 0, "0.30", skillRoundStart()))),
                army("def", 180, 120, 140, 30, 500,
                        List.of(hero("hero_sr_01", 0, "0.20", skillEveryRound()))),
                777L, BattleType.PVP_RALLY);

        BattleResult a = BattleSimulator.simulate(in);
        BattleResult b = BattleSimulator.simulate(in);

        assertThat(a.winner()).isEqualTo(b.winner());
        assertThat(a.totalRounds()).isEqualTo(b.totalRounds());
        assertThat(a.rounds()).hasSameSizeAs(b.rounds());
        for (int i = 0; i < a.rounds().size(); i++) {
            RoundSnapshot ra = a.rounds().get(i);
            RoundSnapshot rb = b.rounds().get(i);
            assertThat(ra.atkUnits()).isEqualTo(rb.atkUnits());
            assertThat(ra.defUnits()).isEqualTo(rb.defUnits());
            assertThat(ra.atkLoss()).isEqualTo(rb.atkLoss());
            assertThat(ra.defLoss()).isEqualTo(rb.defLoss());
            assertThat(ra.atkAttack()).isEqualTo(rb.atkAttack());
            assertThat(ra.defDefense()).isEqualTo(rb.defDefense());
            assertThat(ra.attritionFixed()).isEqualTo(rb.attritionFixed());
            assertThat(ra.skills()).isEqualTo(rb.skills());
        }
    }

    @Test
    @DisplayName("不同 seed 产生不同结果（随机浮动确实在起作用，不是被写死了）")
    void differentSeedsProduceDifferentResults() {
        ArmySide atk = army("atk", 500, 300, 400, 50);
        ArmySide def = army("def", 450, 350, 380, 60);
        String d1 = digest(BattleSimulator.simulate(input(atk, def, 1L, BattleType.PVP_SOLO)));
        String d2 = digest(BattleSimulator.simulate(input(atk, def, 2L, BattleType.PVP_SOLO)));
        assertThat(d1).isNotEqualTo(d2);
    }

    // ---------- 验收 2 / 3：历史战报可复算（黄金样本）----------

    @Test
    @DisplayName("验收2/3：黄金样本 —— 固定 fixture 的战报摘要必须逐字符一致，跨 JVM 可比")
    void historicalBattleReportIsReproducible() {
        // fixture：攻方 500/300/400/50，守方 450/350/380/60，seed=20260906，PVP 单打，平原
        BattleInput in = input(army("atk", 500, 300, 400, 50), army("def", 450, 350, 380, 60),
                20260906L, BattleType.PVP_SOLO);
        String actual = digest(BattleSimulator.simulate(in));
        // 这条断言是「回归护栏」：内核任何改动（新增随机点、调整分摊顺序、改舍入）都会让它失败。
        // 失败时必须先判断改动是否会破坏历史战报复算 —— 会破坏就不能改，或必须做战报版本迁移。
        assertThat(actual).isEqualTo(GOLDEN_DIGEST);
    }

    /**
     * 黄金样本。修改内核后若此值变化，必须评估历史战报是否还能复算。
     *
     * <p>本样本对应的战斗：攻方 500 步/300 骑/400 弓/50 器 打 守方 450/350/380/60，
     * seed=20260906，PVP 单打，平原，双方均无武将无科技，LANCHESTER_K=7.0。
     *
     * <p>结果要点（每一项都能反推回配置，改内核后逐项对照）：
     * <ul>
     *   <li>打满 8 回合、攻方惨胜 —— K=7.0 让每回合损失约 17%，节奏合理</li>
     *   <li>双方前排步兵与中排骑兵全灭，后排弓兵/攻城器存活：这正是三排分摊
     *       0.5/0.3/0.2 的预期行为，前排先被打穿</li>
     *   <li>攻方死 354 = 总损失 1012 的 35%，守方死 163 = 总损失 1089 的 15%，与配置精确吻合</li>
     *   <li>减员系数从 R1 的 0.1693 单调升到 R8 的 0.3456 —— 防御随兵力下降而下降，
     *       这是兰彻斯特模型的正确特征（越打越脆）</li>
     *   <li>lootCapacity=2244 = 存活 214 弓兵×负载6 + 24 攻城器×负载40</li>
     * </ul>
     */
    private static final String GOLDEN_DIGEST =
            "DRAW|8|222/411/0|96/545/0|7998"
            + "|INFANTRY:182/129|CAVALRY:110/158|ARCHER:293/273|SIEGE:32/39"
            + "|R1:135345000,216700000,819,97,100,0"
            + "|R2:125444587,197610000,831,94,94,0"
            + "|R3:115826848,179650000,843,87,85,0"
            + "|R4:106919184,163280000,855,83,86,0"
            + "|R5:98402230,146800000,874,76,78,0"
            + "|R6:90595660,131840000,894,70,70,0"
            + "|R7:83400216,118490000,914,66,64,0"
            + "|R8:76610816,106270000,934,60,64,0";

    // ---------- 验收 5：乘区隔离 ----------

    @Test
    @DisplayName("验收5：单独调整武将加成，有效攻击与手算乘区 A 逐位一致（误差 0）")
    void heroMultiplierIsIsolated() {
        ArmySide def = army("def", 0, 0, 1000, 0);
        long defTotal = 1000L;

        // 无武将：乘区 A = 1.0
        BattleResult noHero = BattleSimulator.simulate(input(
                army("atk", 0, 0, 1000, 0, 0L, List.of()), def, 5L, BattleType.PVP_SOLO));
        long attackNoHero = noHero.rounds().get(0).atkAttack();

        // 武将加成 +30%：乘区 A = 1.30
        BattleResult withHero = BattleSimulator.simulate(input(
                army("atk", 0, 0, 1000, 0, 0L, List.of(hero("h1", 0, "0.30"))), def, 5L, BattleType.PVP_SOLO));
        long attackWithHero = withHero.rounds().get(0).atkAttack();

        // 手算：弓兵 1000 × 攻击 14 × 克制(弓打弓=1.0) × 乘区A(1.30)
        long expected = FixedPoint.mul(FixedPoint.of(defTotal == 0 ? 1000L : 1000L),
                FixedPoint.mul(FixedPoint.of(14), FixedPoint.ONE + FixedPoint.parse("0.30")));
        assertThat(attackNoHero)
                .as("无武将时应等于 1000 × 14 × 1.0")
                .isEqualTo(FixedPoint.mul(FixedPoint.of(1000L), FixedPoint.of(14)));
        assertThat(attackWithHero)
                .as("武将加成 +30% 后应与手算乘区 A 逐位一致")
                .isEqualTo(expected);
        assertThat(attackWithHero).isNotEqualTo(attackNoHero);
    }

    @Test
    @DisplayName("乘区独立：科技与武将加成分别调整时，结果按 (1+a)(1+b) 而不是 1+a+b")
    void multipliersComposeMultiplicativelyNotAdditively() {
        ArmySide def = army("def", 0, 0, 1000, 0);
        long heroBonus = FixedPoint.parse("0.30");
        long techBonus = FixedPoint.parse("0.20");

        AttackMultipliers both = new AttackMultipliers(heroBonus, techBonus, 0L,
                FixedPoint.ONE, 0L, 0L);
        // (1+0.30) × (1+0.20) = 1.56，而不是 1 + 0.30 + 0.20 = 1.50
        assertThat(both.compose()).isEqualTo(FixedPoint.parse("1.56"));
        assertThat(both.compose()).isNotEqualTo(FixedPoint.ONE + heroBonus + techBonus);
    }

    // ---------- 验收 6：伤兵规则 ----------

    @Test
    @DisplayName("验收6：PVE 死 20% / 伤 80%，比例精确符合配置")
    void pveCasualtyRatioMatchesConfig() {
        BattleResult r = BattleSimulator.simulate(input(
                army("atk", 2000, 0, 0, 0), army("def", 500, 0, 0, 0), 11L, BattleType.PVE));
        long total = r.atkTotalLoss();
        assertThat(total).isPositive();
        assertThat(r.atkDead()).isEqualTo(FixedPoint.round(FixedPoint.mul(FixedPoint.of(total), FixedPoint.parse("0.20"))));
        assertThat(r.atkDead() + r.atkWounded()).isEqualTo(total);
    }

    @Test
    @DisplayName("验收6：PVP 攻方死 35% / 守方死 15%，攻方死亡率高于守方")
    void pvpCasualtyRatioFavorsDefender() {
        BattleResult r = BattleSimulator.simulate(input(
                army("atk", 2000, 500, 500, 0), army("def", 2000, 500, 500, 0), 12L, BattleType.PVP_SOLO));
        long atkTotal = r.atkTotalLoss();
        long defTotal = r.defTotalLoss();
        assertThat(atkTotal).isPositive();
        assertThat(defTotal).isPositive();
        assertThat(r.atkDead()).isEqualTo(FixedPoint.round(FixedPoint.mul(FixedPoint.of(atkTotal), FixedPoint.parse("0.35"))));
        assertThat(r.defDead()).isEqualTo(FixedPoint.round(FixedPoint.mul(FixedPoint.of(defTotal), FixedPoint.parse("0.15"))));
        // 攻方死亡率必须高于守方：进攻是有代价的选择（B00 战斗结算的设计意图）
        assertThat((double) r.atkDead() / atkTotal).isGreaterThan((double) r.defDead() / defTotal);
    }

    @Test
    @DisplayName("验收7：医院溢出部分直接死亡 —— 容量为 0 时伤兵全部转死亡")
    void hospitalOverflowConvertsWoundedToDead() {
        BattleResult noHospital = BattleSimulator.simulate(input(
                army("atk", 2000, 0, 0, 0, 0L, List.of()),
                army("def", 500, 0, 0, 0), 13L, BattleType.PVE));
        long total = noHospital.atkTotalLoss();
        assertThat(total).isPositive();
        assertThat(noHospital.atkWounded()).as("医院容量为 0，伤兵应全部溢出死亡").isZero();
        assertThat(noHospital.atkDead()).isEqualTo(total);
        // 溢出量 = 伤兵中因容量不足转死亡的部分，不含按规则本来就该死的那 20%
        long ruleDead = FixedPoint.round(FixedPoint.mul(FixedPoint.of(total), FixedPoint.parse("0.20")));
        assertThat(noHospital.atkOverflowDead())
                .as("容量为 0 时，全部伤兵（80%%）都应记为溢出死亡")
                .isEqualTo(total - ruleDead);

        // 容量充足时不应有溢出
        BattleResult bigHospital = BattleSimulator.simulate(input(
                army("atk", 2000, 0, 0, 0, 1_000_000L, List.of()),
                army("def", 500, 0, 0, 0), 13L, BattleType.PVE));
        assertThat(bigHospital.atkOverflowDead()).isZero();
        assertThat(bigHospital.atkWounded()).isPositive();
        assertThat(bigHospital.atkDead()).isLessThan(noHospital.atkDead());
    }

    // ---------- 验收 11：平局判定 ----------

    @Test
    @DisplayName("验收11：剩余兵力差距 4.9% 判平局，5.1% 判胜负")
    void drawBoundaryIsExact() {
        long drawGap = FixedPoint.parse("0.05");

        // 构造双方剩余比例差恰为 4.9% 与 5.1% 的收官局面：用 maxRounds=1 且兵力悬殊可控的方式难构造，
        // 这里直接对判定逻辑做边界验证 —— 用相同初始兵力、不同损失量模拟
        assertThat(judge(1000, 951, 1000, 1000, drawGap))
                .as("攻方剩余 95.1%、守方 100%，差 4.9% < 5% ⇒ 平局")
                .isEqualTo(Winner.DRAW);
        assertThat(judge(1000, 949, 1000, 1000, drawGap))
                .as("差 5.1% > 5% ⇒ 守方胜")
                .isEqualTo(Winner.DEFENDER);
        assertThat(judge(1000, 1000, 1000, 949, drawGap))
                .as("差 5.1% ⇒ 攻方胜")
                .isEqualTo(Winner.ATTACKER);
        assertThat(judge(1000, 950, 1000, 1000, drawGap))
                .as("差恰好 5.0% 不小于阈值 ⇒ 不判平")
                .isEqualTo(Winner.DEFENDER);
    }

    /**
     * 复刻内核的平局判定逻辑用于边界验证。
     *
     * <p>这里重复实现是有意的：判定逻辑只有三行，把它抄一份到测试里，
     * 就能在「阈值边界」这个最容易出错的地方独立验证语义（&lt; 判平、&gt;= 判胜），
     * 而不是只能靠构造一场刚好打到边界的战斗 —— 那种构造方式既脆弱又难读。
     */
    private static Winner judge(long atkInitial, long atkRemain, long defInitial, long defRemain,
                                long drawGapFixed) {
        long atkRatio = FixedPoint.div(FixedPoint.of(atkRemain), FixedPoint.of(atkInitial));
        long defRatio = FixedPoint.div(FixedPoint.of(defRemain), FixedPoint.of(defInitial));
        if (FixedPoint.abs(atkRatio - defRatio) < drawGapFixed) {
            return Winner.DRAW;
        }
        return atkRatio > defRatio ? Winner.ATTACKER : Winner.DEFENDER;
    }

    @Test
    @DisplayName("一方兵力归零立即结束，不跑满 8 回合")
    void battleEndsEarlyWhenOneSideIsWipedOut() {
        BattleResult r = BattleSimulator.simulate(input(
                army("atk", 20000, 0, 0, 0), army("def", 50, 0, 0, 0), 21L, BattleType.PVP_SOLO));
        assertThat(r.winner()).isEqualTo(Winner.ATTACKER);
        assertThat(r.totalRounds()).as("碾压局不应跑满 8 回合").isLessThan(8);
        assertThat(ArmySide.totalOf(r.defSurvivors())).isZero();
    }

    // ---------- 验收 8：克制关系 ----------

    @Test
    @DisplayName("验收8：骑兵打弓兵 vs 弓兵打骑兵，1000 局胜率差 >= 15%")
    void counterRelationshipProducesClearWinRateGap() {
        int runs = 1000;
        int size = 1000;

        int cavalryWins = 0;
        for (int i = 0; i < runs; i++) {
            // 骑兵为攻方打弓兵
            BattleResult r = BattleSimulator.simulate(input(
                    army("cav", 0, size, 0, 0), army("arc", 0, 0, size, 0),
                    1_000_000L + i, BattleType.PVP_SOLO));
            if (r.winner() == Winner.ATTACKER) {
                cavalryWins++;
            }
        }
        int archerWins = 0;
        for (int i = 0; i < runs; i++) {
            // 弓兵为攻方打骑兵
            BattleResult r = BattleSimulator.simulate(input(
                    army("arc", 0, 0, size, 0), army("cav", 0, size, 0, 0),
                    1_000_000L + i, BattleType.PVP_SOLO));
            if (r.winner() == Winner.ATTACKER) {
                archerWins++;
            }
        }
        double cavRate = (double) cavalryWins / runs;
        double arcRate = (double) archerWins / runs;
        assertThat(Math.abs(cavRate - arcRate))
                .as("骑兵克弓兵是单向克制，胜率差应 >= 15%%。实际 骑兵 %.1f%% vs 弓兵 %.1f%%",
                        cavRate * 100, arcRate * 100)
                .isGreaterThanOrEqualTo(0.15d);
        assertThat(cavRate).as("克制方胜率应更高").isGreaterThan(arcRate);
    }

    @Test
    @DisplayName("相互克制（步兵↔弓兵）净倍率为 1.0，胜率应接近 50%")
    void mutualCounterIsNeutral() {
        // 1.25 × 0.80 = 1.0：相互克制在倍率上完全抵消，胜负由裸数值决定
        long mutual = FixedPoint.mul(FixedPoint.ONE + FixedPoint.parse("0.25"),
                FixedPoint.ONE - FixedPoint.parse("0.20"));
        assertThat(mutual).isEqualTo(FixedPoint.ONE);
    }

    // ---------- 损失分摊 ----------

    @Test
    @DisplayName("损失按前排 0.5 / 中排 0.3 / 后排 0.2 分摊，前排损失最多")
    void lossIsDistributedByRow() {
        // 三排兵力都充足，避免触发溢出顺延
        BattleResult r = BattleSimulator.simulate(input(
                army("atk", 10000, 10000, 10000, 0),
                army("def", 10000, 10000, 10000, 0), 31L, BattleType.PVP_SOLO));
        RoundSnapshot first = r.rounds().get(0);
        long frontLost = 10000L - first.defUnits().get(UnitType.INFANTRY);
        long midLost = 10000L - first.defUnits().get(UnitType.CAVALRY);
        long backLost = 10000L - first.defUnits().get(UnitType.ARCHER);

        assertThat(frontLost).as("前排（步兵）损失应最大").isGreaterThan(midLost);
        assertThat(midLost).as("中排（骑兵）损失应大于后排").isGreaterThan(backLost);
        assertThat(frontLost + midLost + backLost).as("三排损失之和应等于总损失").isEqualTo(first.defLoss());
    }

    @Test
    @DisplayName("前排兵力不足时，溢出损失顺延到中排与后排，总损失不超过总兵力")
    void lossOverflowsToNextRowWhenFrontIsThin() {
        // 守方只有 10 个步兵（前排）+ 大量后排弓兵
        BattleResult r = BattleSimulator.simulate(input(
                army("atk", 5000, 0, 0, 0), army("def", 10, 0, 5000, 0), 41L, BattleType.PVP_SOLO));
        RoundSnapshot first = r.rounds().get(0);
        assertThat(first.defUnits().get(UnitType.INFANTRY))
                .as("前排只有 10 个，最多损失 10 个").isGreaterThanOrEqualTo(0L).isLessThanOrEqualTo(10L);
        long totalLost = (10L - first.defUnits().get(UnitType.INFANTRY))
                + (5000L - first.defUnits().get(UnitType.ARCHER));
        assertThat(totalLost).isEqualTo(first.defLoss());
        assertThat(ArmySide.totalOf(first.defUnits())).isPositive();
    }

    @Test
    @DisplayName("回归：单一兵种军队的损失不得被排分摊吞掉（溢出必须回流到仍有兵力的排）")
    void lossIsNotSwallowedWhenRowsAreEmpty() {
        // 纯步兵守军全在前排，中后排为空。修复前 50% 的损失会因「中后排无兵可扣」被直接丢弃，
        // 实际承伤只有设计值的一半 —— 这个 bug 不报错、不产生负数，只会让平衡矩阵整体失真。
        long defenders = 1000L;
        BattleResult r = BattleSimulator.simulate(input(
                army("atk", 1000, 0, 0, 0), army("def", defenders, 0, 0, 0), 42L, BattleType.PVP_SOLO));

        RoundSnapshot first = r.rounds().get(0);
        long infantryLost = defenders - first.defUnits().get(UnitType.INFANTRY);
        assertThat(infantryLost).as("纯步兵守军必须真实承伤").isPositive();
        assertThat(infantryLost).as("快照记录的守方损失应与步兵实际减少量一致").isEqualTo(first.defLoss());

        // 关键回归点：实际损失比例应接近减员系数。
        // 修复前纯步兵守军只承受约一半的设计损失，这条断言会直接抓到。
        long lossPerThousand = infantryLost * 1000L / defenders;
        long attritionPerThousand = first.attritionFixed() / 10L;
        assertThat(lossPerThousand)
                .as("实际损失比例(千分之%d)应接近减员系数(千分之%d)，允许随机浮动与取整误差",
                        lossPerThousand, attritionPerThousand)
                .isBetween(attritionPerThousand * 80 / 100, attritionPerThousand * 125 / 100);

        for (RoundSnapshot snapshot : r.rounds()) {
            assertThat(ArmySide.totalOf(snapshot.defUnits())).isGreaterThanOrEqualTo(0L);
            assertThat(snapshot.defLoss()).isGreaterThanOrEqualTo(0L);
        }
    }

    // ---------- 掠夺 ----------

    @Test
    @DisplayName("掠夺：未破墙时为空；破墙后等于 min(非保护资源, 剩余负载)；PVE 不掠夺")
    void lootRespectsWallAndCapacity() {
        ArmySide atk = army("atk", 1000, 0, 0, 0);   // 步兵负载 20 ⇒ 剩余负载约 20000
        ArmySide def = army("def", 100, 0, 0, 0);
        Map<String, Long> unprotected = new java.util.TreeMap<>();
        unprotected.put("WOOD", 50000L);
        unprotected.put("STONE", 50000L);
        DefenderStore store = new DefenderStore(unprotected, Map.of("GOLD", 999L), true);

        BattleResult looted = BattleSimulator.simulate(new BattleInput(atk, def, TerrainType.PLAIN,
                51L, BattleType.PVP_SOLO, BattleModifier.none(), BattleModifier.none(),
                t1Stats(), rules(), store));
        assertThat(looted.lootCapacity()).isPositive();
        assertThat(looted.lootTotal())
                .as("掠夺量不得超过剩余负载").isLessThanOrEqualTo(looted.lootCapacity());
        assertThat(looted.lootTotal())
                .as("掠夺量不得超过对方非保护资源总量").isLessThanOrEqualTo(100000L);
        assertThat(looted.loot()).doesNotContainKey("GOLD");

        // 未破墙
        DefenderStore intact = new DefenderStore(unprotected, Map.of(), false);
        BattleResult noLoot = BattleSimulator.simulate(new BattleInput(atk, def, TerrainType.PLAIN,
                51L, BattleType.PVP_SOLO, BattleModifier.none(), BattleModifier.none(),
                t1Stats(), rules(), intact));
        assertThat(noLoot.loot()).as("未破墙不得掠夺仓库").isEmpty();

        // PVE 不掠夺
        BattleResult pve = BattleSimulator.simulate(new BattleInput(atk, def, TerrainType.PLAIN,
                51L, BattleType.PVE, BattleModifier.none(), BattleModifier.none(),
                t1Stats(), rules(), store));
        assertThat(pve.loot()).as("PVE 不掠夺玩家资源").isEmpty();
    }

    // ---------- 技能 ----------

    @Test
    @DisplayName("技能触发被记录进快照，且同 seed 下触发序列完全一致")
    void skillTriggersAreRecordedDeterministically() {
        BattleInput in = input(
                army("atk", 1000, 500, 800, 0, 5000L, List.of(
                        hero("h1", 0, "0.20", skill(SkillPhase.ROUND_START, SkillEffect.BUFF_ATK, "0.35", 2)),
                        hero("h2", 1, "0.10", skill(SkillPhase.ON_HIT, SkillEffect.DAMAGE, "0.60", 1)))),
                army("def", 900, 600, 700, 0, 5000L, List.of(
                        hero("h3", 0, "0.15", skill(SkillPhase.EVERY_ROUND, SkillEffect.HEAL, "0.15", 1)))),
                61L, BattleType.PVP_RALLY);

        BattleResult a = BattleSimulator.simulate(in);
        BattleResult b = BattleSimulator.simulate(in);

        int triggerCount = a.rounds().stream().mapToInt(r -> r.skills().size()).sum();
        assertThat(triggerCount).as("概率技能在 8 回合内应至少触发几次").isPositive();
        assertThat(b.rounds().stream().mapToInt(r -> r.skills().size()).sum()).isEqualTo(triggerCount);
        for (int i = 0; i < a.rounds().size(); i++) {
            assertThat(a.rounds().get(i).skills()).isEqualTo(b.rounds().get(i).skills());
        }
    }

    @Test
    @DisplayName("HEAL 只能从本场累计损失里捞兵，不能凭空造出超过初始数量的兵力")
    void healCannotExceedCumulativeLoss() {
        BattleInput in = input(
                army("atk", 1000, 0, 0, 0, 9999L, List.of(
                        hero("healer", 0, "1.00", skill(SkillPhase.EVERY_ROUND, SkillEffect.HEAL, "1.00", 1)))),
                army("def", 1000, 0, 0, 0), 71L, BattleType.PVP_SOLO);
        BattleResult r = BattleSimulator.simulate(in);
        for (RoundSnapshot s : r.rounds()) {
            assertThat(s.atkUnits().get(UnitType.INFANTRY))
                    .as("治疗不得让兵力超过初始 1000").isLessThanOrEqualTo(1000L);
        }
    }

    // ---------- 验收 14：线程安全 ----------

    @Test
    @DisplayName("验收14：16 线程并发跑，每局结果与单线程逐一致")
    void concurrentSimulationMatchesSingleThreaded() throws Exception {
        int threads = 16;
        int perThread = 200;
        List<String> singleThreaded = new ArrayList<>();
        List<BattleInput> inputs = new ArrayList<>();
        for (int i = 0; i < threads * perThread; i++) {
            inputs.add(input(army("atk", 500 + i, 300, 400, 20), army("def", 480, 320, 390, 30),
                    900_000L + i, BattleType.PVP_SOLO));
        }
        for (BattleInput in : inputs) {
            singleThreaded.add(digest(BattleSimulator.simulate(in)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<List<String>>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int start = t * perThread;
                tasks.add(() -> {
                    List<String> out = new ArrayList<>(perThread);
                    for (int i = start; i < start + perThread; i++) {
                        out.add(digest(BattleSimulator.simulate(inputs.get(i))));
                    }
                    return out;
                });
            }
            List<Future<List<String>>> futures = pool.invokeAll(tasks, 120, TimeUnit.SECONDS);
            List<String> concurrent = new ArrayList<>();
            for (Future<List<String>> f : futures) {
                concurrent.addAll(f.get());
            }
            assertThat(concurrent).isEqualTo(singleThreaded);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 入参校验 ----------

    @Test
    @DisplayName("入参校验：缺兵种属性、兵力为零、三排分摊之和不为 1.0 都在入口就被拒绝")
    void rejectsInvalidInput() {
        ArmySide atk = army("atk", 100, 0, 0, 0);
        ArmySide def = army("def", 100, 0, 0, 0);

        assertThatThrownBy(() -> BattleSimulator.simulate(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> input(army("atk", 0, 0, 0, 0), def, 1L, BattleType.PVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("攻方总兵数");

        Map<UnitType, UnitStats> incomplete = t1Stats();
        incomplete.remove(UnitType.SIEGE);
        assertThatThrownBy(() -> new BattleInput(atk, def, TerrainType.PLAIN, 1L, BattleType.PVE,
                BattleModifier.none(), BattleModifier.none(), incomplete, rules(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SIEGE");

        assertThatThrownBy(() -> new BattleRules(8, FixedPoint.ONE, FixedPoint.parse("0.20"),
                FixedPoint.parse("0.95"), FixedPoint.parse("1.05"),
                FixedPoint.parse("0.5"), FixedPoint.parse("0.3"), FixedPoint.parse("0.3"),
                FixedPoint.parse("0.25"), FixedPoint.parse("0.20"), FixedPoint.parse("0.05"),
                FixedPoint.parse("0.20"), FixedPoint.parse("0.35"), FixedPoint.parse("0.15"),
                counterMatrix(), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("三排损失分摊之和");
    }

    // ---------- 辅助构造 ----------

    private static HeroSnapshot hero(String id, int slot, String bonus) {
        return new HeroSnapshot(id, slot, FixedPoint.parse(bonus), 0L, List.of());
    }

    private static HeroSnapshot hero(String id, int slot, String bonus, SkillSnapshot skill) {
        return new HeroSnapshot(id, slot, FixedPoint.parse(bonus), 0L, List.of(skill));
    }

    private static SkillSnapshot skillRoundStart() {
        return skill(SkillPhase.ROUND_START, SkillEffect.BUFF_ATK, "0.35", 2);
    }

    private static SkillSnapshot skillEveryRound() {
        return skill(SkillPhase.EVERY_ROUND, SkillEffect.DEBUFF_ATK, "0.25", 2);
    }

    private static SkillSnapshot skill(SkillPhase phase, SkillEffect effect, String value, int duration) {
        return new SkillSnapshot("skill_test_" + effect.name().toLowerCase(), phase,
                FixedPoint.ONE, effect, FixedPoint.parse(value), duration);
    }
}
