package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.core.city.BuildingInstance;
import com.ironoath.core.city.CityState;
import com.ironoath.core.player.PlayerTech;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.resource.ResourceOutputCalculator;
import com.ironoath.web.tech.TechEffects;

/**
 * 职责：科技效果接入的两个消费点（<b>产量</b>与<b>建造速度</b>）的定点值验证（B20 验收 3 的前两格）。
 * 依赖：Spring 上下文（要真配置表）；不碰 MongoDB。
 *
 * <p><b>为什么断精确数而不是"产率变大了"</b>：B20 §一 明写"每处效果用精确定点值断言
 * （照 {@code AllianceTechBonusTest} 的形状：断 150/300 而不是'变大了'）"。
 * 更实际的理由是这一族缺陷的形状：加成接错槽位（把科技算进联盟那一行）、
 * 或者在两个地方各乘一次，都不会让"变大了"这条断言变红。
 *
 * <p><b>取整口径也在射程内</b>：{@code shortenByPercent} 那几条用例（7 秒减 50% 得 4 秒、
 * 100% 以上仍留 1 秒）是 §五④ 那句"缩短时长统一 ceil，防 0 秒完成"的唯一机器化表达。
 * 训练速度与行军速度接入时必须复用同一个方法而不是各写一份。
 */
@SpringBootTest
@ActiveProfiles("test")
class TechEffectsTest {

    /** 屯田令：木产量 +4%/级（tech.json 的 effectValue=0.04）。 */
    private static final long WOOD_PER_LEVEL_FIXED = 400L;
    /** 工役：建造速度 +3%/级（effectValue=0.03）。 */
    private static final long BUILD_PER_LEVEL_FIXED = 300L;

    @Autowired
    private TechEffects techEffects;
    @Autowired
    private ResourceRateService resourceRates;

    private static PlayerTech techOf(String techId, int level) {
        return new PlayerTech(Map.of(techId, level), null, null, 0L, 0L);
    }

    /** 有一座伐木场（等级 5）的城建状态：木头的<b>基础</b>产出因此非零，百分比那一行才有东西可乘。 */
    private CityState cityWithLumberCamp() {
        CityState city = new CityState();
        city.restoreBuilding(new BuildingInstance("b-lumber", "lumber_camp", 5, 2, 2));
        return city;
    }

    private ResourceOutputCalculator.Breakdown woodBreakdown(PlayerTech tech) {
        return resourceRates.compute(cityWithLumberCamp(), tech).breakdowns().get(ResourceIds.WOOD);
    }

    private ResourceOutputCalculator.Line lineOf(ResourceOutputCalculator.Breakdown b, String source) {
        return b.lines().stream().filter(l -> source.equals(l.source())).findFirst().orElseThrow();
    }

    // ---------- 产量 ----------

    @Test
    @DisplayName("产量加成 = 每级幅度 × 当前等级（4%/级 × 5 级 = 2000 万分比），没研究过就是 0")
    void outputPercentIsValueTimesLevel() {
        assertThat(techEffects.outputPercent(ResourceIds.WOOD, techOf("tech_agri_wood", 5)))
                .as("5 级屯田令应该是 +20%（万分比 2000），不是 +4%、也不是 20")
                .isEqualTo(WOOD_PER_LEVEL_FIXED * 5L);
        assertThat(techEffects.outputPercent(ResourceIds.WOOD, PlayerTech.empty()))
                .as("账本里没有这一行 = 0 级，加成必须是 0 而不是缺字段")
                .isZero();
        assertThat(techEffects.outputPercent(ResourceIds.GRAIN, techOf("tech_agri_wood", 5)))
                .as("屯田令只加木头，不该顺手加粮食（加成互相污染是 B20 §四 的禁止项）")
                .isZero();
        assertThat(techEffects.outputPercent(ResourceIds.GOLD, techOf("tech_agri_wood", 5)))
                .as("金币没有 *_OUTPUT 行")
                .isZero();
    }

    @Test
    @DisplayName("科技加成进的是「科技加成」那一行：联盟与道具两行保持 0，总量恰等于基础 + 该行")
    void breakdownFillsOnlyTheTechSlot() {
        ResourceOutputCalculator.Breakdown plain = woodBreakdown(PlayerTech.empty());
        ResourceOutputCalculator.Breakdown boosted = woodBreakdown(techOf("tech_agri_wood", 5));

        long base = plain.baseSubtotal();
        assertThat(base).as("有一座 5 级伐木场，基础产出非零，否则这组断言会空转").isPositive();

        // HALF_UP(base × 20%)：这里用 BigDecimal 独立算一遍，不去调生产的 percentOf
        long expected = new BigDecimal(base).multiply(new BigDecimal("0.2"))
                .setScale(0, RoundingMode.HALF_UP).longValue();
        assertThat(lineOf(boosted, "科技加成").percentFixed())
                .as("这一行显示的就是 20%")
                .isEqualTo(2000L);
        assertThat(lineOf(boosted, "科技加成").amount())
                .as("加成额恰等于基础产出的 20%（HALF_UP），不多乘一次也不少乘")
                .isEqualTo(expected);
        assertThat(lineOf(boosted, "联盟加成").amount()).isZero();
        assertThat(lineOf(boosted, "道具 buff").amount()).isZero();
        assertThat(boosted.totalPerHour())
                .as("总量 = 基础 + 科技一行；Breakdown 构造器已保证 Σ 各行 == 总量，这里钉的是「只加了一格」")
                .isEqualTo(base + expected);
        assertThat(boosted.totalPerHour()).isGreaterThan(plain.totalPerHour());
    }

    @Test
    @DisplayName("表里没有的科技 id 不会凭空产生加成")
    void unknownTechIdContributesNothing() {
        assertThat(techEffects.outputPercent(ResourceIds.WOOD,
                techOf("tech_does_not_exist", 30)))
                .as("账本按 id 存，属性折算是遍历表 —— 表里没有的 id 就不该被算进来")
                .isZero();
    }

    // ---------- 建造速度 ----------

    @Test
    @DisplayName("建造速度读的是工役那一行，且不会漏进产量槽")
    void buildSpeedPercentReadsItsOwnRow() {
        assertThat(techEffects.buildSpeedPercent(techOf("tech_fort_build", 10)))
                .isEqualTo(BUILD_PER_LEVEL_FIXED * 10L);
        assertThat(techEffects.outputPercent(ResourceIds.WOOD, techOf("tech_fort_build", 10)))
                .as("建造速度科技不该改木头产量")
                .isZero();
    }

    @Test
    @DisplayName("缩短按 ceil 取整、最多压到 1 秒，加成非正时原样返回")
    void shortenRoundsUpAndNeverReachesZero() {
        assertThat(CityAppService.shortenByPercent(100L, 0L)).as("没有加成就不该动").isEqualTo(100L);
        assertThat(CityAppService.shortenByPercent(100L, 900L))
                .as("减 9%：100 × 0.91 = 91 整")
                .isEqualTo(91L);
        assertThat(CityAppService.shortenByPercent(100L, 3300L))
                .as("减 33%：67 整")
                .isEqualTo(67L);
        assertThat(CityAppService.shortenByPercent(7L, 5000L))
                .as("减 50%：3.5 秒 → ceil 成 4 秒（向下取整会少给玩家半秒，而这是每次升级都发生的事）")
                .isEqualTo(4L);
        assertThat(CityAppService.shortenByPercent(1L, 5000L))
                .as("1 秒减一半还是 1 秒：ceil 而不是 0")
                .isEqualTo(1L);
        assertThat(CityAppService.shortenByPercent(10L, FixedPoint.ONE))
                .as("加成 100% 也只压到 1 秒 —— 0 秒队列等于没有队列")
                .isEqualTo(1L);
        assertThat(CityAppService.shortenByPercent(10L, FixedPoint.ONE * 3L))
                .as("配错成 300% 也不该出负数或 0")
                .isEqualTo(1L);
        assertThatThrownBy(() -> CityAppService.shortenByPercent(0L, 500L))
                .as("基础时长为 0 是调用方算错了，不是「这条升级本来就瞬间完成」")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
