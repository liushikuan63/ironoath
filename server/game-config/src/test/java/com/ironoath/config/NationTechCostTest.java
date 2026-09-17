package com.ironoath.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.cfg.CurveCfg;
import com.ironoath.config.cfg.NationConfigCfg;
import com.ironoath.config.cfg.NationTechCfg;
import com.ironoath.config.cfg.TechCfg;

/**
 * 职责：国家科技表（{@code nation_tech.json}）的<b>形状与成本推导</b>回归卡口（B20 块③ S1）。
 * 依赖：仓库内真实的 contract/config（nation_tech / tech / curve / nation_config 四张表）；不起 Spring、不碰 Mongo。
 *
 * <p><b>为什么这张表一落地就要有卡口</b>：S1 只交表与契约，读它的生产代码在 S2 才出现 ——
 * 也就是说从现在到 S2 之间，<b>没有任何一条别处的测试会因为这张表被改坏而变红</b>。
 * 「表做了机制没做」这一族（{@code check-config-consumers} 的 UNWIRED 表）登记的是"没读者"，
 * 不登记"没人核形状"，所以形状得由本类自己钉住。
 *
 * <p><b>成本那两条是重点，因为它是一个推导而非一个偏好</b>：表里 {@code costBaseTreasury=1050} 的来由写在
 * {@code designNote}（一系点满 = Lv1 国库容量 50 万的 = 25 万 = 12.5 周税收，曲线复用 {@code BUILDING_COST}
 * 的 1.22，20 级等比和约 238 倍 ⇒ 基数约 1050）。本类用生产同一个定点算式把这句话重算一遍并断区间 ——
 * 改比率、改级数、改基数都会让它红，数字才不会烂成第二真相（与 {@code TechTimeCalibrationTest} 同一条做法）。
 */
class NationTechCostTest {

    /** designNote 里的目标：一系点满 = Lv1 国库容量（500,000）的一半，容 ±4% 的取整余量。 */
    private static final long TARGET_TOTAL = 250_000L;
    private static final long TOTAL_LOW = 240_000L;
    private static final long TOTAL_HIGH = 260_000L;
    /** 曲线刻意复用既有那条：新造一条只是把 1.22 抄第二份，两份迟早分叉。 */
    private static final String REUSED_CURVE = "BUILDING_COST";

    private static ConfigRegistry configs;

    @BeforeAll
    static void load() {
        configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    }

    private List<NationTechCfg> rows() {
        return new ArrayList<>(configs.all(NationTechCfg.class));
    }

    /** 个人科技里用同一 effectAttr 的那一行（没有就返回 null）。 */
    private TechCfg personalWithSameAttr(String attrName) {
        return configs.all(TechCfg.class).stream()
                .filter(t -> t.effectAttr().name().equals(attrName))
                .findFirst().orElse(null);
    }

    /** 第 k 级（k 从 1 起）的花费：{@code round(costBaseTreasury × ratio^(k-1))}，与 S2 要用的算式同一条。 */
    private long costAt(NationTechCfg row, int level) {
        long ratio = configs.get(CurveCfg.class, row.costCurve()).ratio();
        return FixedPoint.round(FixedPoint.mul(FixedPoint.of(row.costBaseTreasury()),
                FixedPoint.geometric(FixedPoint.ONE, ratio, level - 1)));
    }

    private long totalOf(NationTechCfg row) {
        long sum = 0L;
        for (int k = 1; k <= row.maxLevel(); k++) {
            sum += costAt(row, k);
        }
        return sum;
    }

    @Test
    @DisplayName("反空转：表真读到东西，且每学派恰好一行（读空了下面每条断言都会真通过，那是假绿）")
    void exactlyOneRowPerSchool() {
        List<NationTechCfg> rows = rows();
        assertThat(rows).as("§五③ 定的是四行（每学派一行）").hasSize(4);
        Set<NationTechCfg.School> schools = new LinkedHashSet<>();
        for (NationTechCfg row : rows) {
            assertThat(schools.add(row.school()))
                    .as("%s 与已有行同属 %s 学派：一系多行会把「每学派一行」这条裁决悄悄改掉",
                            row.id(), row.school());
            assertThat(row.id()).as("行 id 不得为空").isNotBlank();
            assertThat(row.name()).as("%s 的中文名不得为空（客户端不硬编码名字）", row.id()).isNotBlank();
        }
        assertThat(schools).containsExactlyInAnyOrder(NationTechCfg.School.values());
    }

    @Test
    @DisplayName("effectAttr 只能是个人科技已有的取值：国家这张表不许新造属性词汇")
    void effectAttributesAreNotInvented() {
        Set<String> personalAttrs = new LinkedHashSet<>();
        configs.all(TechCfg.class).forEach(t -> personalAttrs.add(t.effectAttr().name()));
        for (NationTechCfg row : rows()) {
            assertThat(personalAttrs)
                    .as("%s 的 effectAttr=%s 不在 tech.json 的属性词汇里 —— 新造一个取值就意味着还要再造一个"
                                    + "消费点，而 S2 的计划是复用已有的四个消费者",
                            row.id(), row.effectAttr())
                    .contains(row.effectAttr().name());
        }
    }

    @Test
    @DisplayName("每行在同属性个人科技之下：每级同量级而总上限更低（联盟 atech_atk 那条先例，国家不许盖过个人养成）")
    void nationNeverOutgrowsPersonalOnTheSameAttribute() {
        for (NationTechCfg row : rows()) {
            TechCfg personal = personalWithSameAttr(row.effectAttr().name());
            assertThat(personal).as("找不到同属性的个人科技行，上一条断言就形同虚设").isNotNull();
            long personalTotal = personal.effectValue() * personal.maxLevel();
            long nationTotal = row.effectValue() * row.maxLevel();
            assertThat(nationTotal)
                    .as("%s 满级合计 %s 万分比不该 ≥ 个人 %s 的 %s 万分比（定点值直接比，不换算成百分数）",
                            row.id(), nationTotal, personal.id(), personalTotal)
                    .isLessThan(personalTotal);
            assertThat(row.effectValue())
                    .as("%s 的每级幅度必须为正，否则这一行研究到满也不改任何数", row.id())
                    .isPositive();
        }
    }

    @Test
    @DisplayName("成本曲线复用 BUILDING_COST 且量纲是 FIXED：不新造比率，也不拿时长曲线当消耗用（#152 那一族）")
    void costCurveIsTheReusedFixedCurve() {
        for (NationTechCfg row : rows()) {
            assertThat(row.costCurve())
                    .as("%s 的 costCurve 必须是既有那条 %s；另起一条就是把比率抄第二份", row.id(), REUSED_CURVE)
                    .isEqualTo(REUSED_CURVE);
            CurveCfg curve = configs.get(CurveCfg.class, row.costCurve());
            assertThat(curve.unit())
                    .as("%s 指向的曲线量纲是 %s，消耗必须是 FIXED", row.id(), curve.unit())
                    .isEqualTo(CurveCfg.Unit.FIXED);
            assertThat(FixedPoint.toBigDecimal(curve.ratio()))
                    .as("比率仍是 1.22（§五②③ 两次都说「复用同一条比率」）")
                    .isEqualByComparingTo(new BigDecimal("1.22"));
        }
    }

    @Test
    @DisplayName("基数是推导出来的：一系点满的总价必须落在 designNote 说的那个区间，末级不能越过国库容量")
    void totalCostFollowsTheDerivation() {
        long lv1Cap = configs.all(NationConfigCfg.class).stream()
                .filter(r -> r.nationLevel() == 1L)
                .mapToLong(NationConfigCfg::treasuryCap)
                .findFirst().orElseThrow();
        assertThat(lv1Cap).as("nation_config Lv1 的 treasuryCap 是这次推导的锚，读不到就没法核对").isPositive();

        for (NationTechCfg row : rows()) {
            long total = totalOf(row);
            assertThat(total)
                    .as("%s 点满总价 %d 不在 [%d, %d]（锚 = Lv1 容量 %d 的一半）—— 改级数或比率之后要重新推导基数",
                            row.id(), total, TOTAL_LOW, TOTAL_HIGH, lv1Cap)
                    .isBetween(TOTAL_LOW, TOTAL_HIGH);
            // 总价对得上锚，说明"一半容量"这句话仍然成立；单独钉一次，免得区间被人放宽成没意义的数
            assertThat(total)
                    .as("%s 的总价应当约等于 Lv1 国库容量的一半", row.id())
                    .isBetween(TARGET_TOTAL * 96L / 100L, TARGET_TOTAL * 104L / 100L);
            long last = costAt(row, (int) row.maxLevel());
            assertThat(last)
                    .as("%s 末级要花 %d，一旦越过 Lv1 容量(%d) 就会出现「攒得起来却永远花不掉最后一级」的死账",
                            row.id(), last, lv1Cap)
                    .isLessThan(lv1Cap);
            assertThat(costAt(row, 1))
                    .as("首级必须就是表里的基数（错位一档的缺陷在 #154 记过：1 级与 2 级花一样的钱）")
                    .isEqualTo(row.costBaseTreasury());
        }
    }

    @Test
    @DisplayName("requireNationLevel 只能是 nation_config 真有的那几个等级：不存在的门槛=永远研究不了")
    void prerequisiteLevelsExistInTheNationTable() {
        Set<Long> defined = new LinkedHashSet<>();
        configs.all(NationConfigCfg.class).forEach(r -> defined.add(r.nationLevel()));
        assertThat(defined).as("nation_config 读空了，这条断言就只剩形").isNotEmpty();
        for (NationTechCfg row : rows()) {
            assertThat(defined)
                    .as("%s 的 requireNationLevel=%d 在 nation_config 里没有这一级",
                            row.id(), row.requireNationLevel())
                    .contains(row.requireNationLevel());
        }
    }
}
