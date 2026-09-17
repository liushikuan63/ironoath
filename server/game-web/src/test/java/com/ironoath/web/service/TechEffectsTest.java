package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.core.city.BuildingInstance;
import com.ironoath.core.city.CityState;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.player.PlayerTech;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.resource.ResourceOutputCalculator;
import com.ironoath.web.dto.generated.CityUpgradeReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.tech.TechEffects;

/**
 * 职责：科技效果接入的消费点（产量、建造速度、训练速度）的定点值验证（B20 验收 3）。
 * 依赖：Spring 上下文（要真配置表）；不碰 MongoDB。
 *
 * <p><b>为什么断精确数而不是"产率变大了"</b>：B20 §一 明写"每处效果用精确定点值断言
 * （照 {@code AllianceTechBonusTest} 的形状：断 150/300 而不是'变大了'）"。
 * 更实际的理由是这一族缺陷的形状：加成接错槽位（把科技算进联盟那一行）、
 * 或者在两个地方各乘一次，都不会让"变大了"这条断言变红。
 *
 * <p><b>本文件测的是"接线"，不是取整口径本身</b>：ceil / HALF_UP 那张表住在
 * {@code game-common} 的 {@code RatesTest}（口径只有一个家，测试也跟着只写一处）。
 * 这里测的是「表里的幅度 → 合计率 → 真的落进 finishAt 与产率明细」这条链，
 * 所以建造速度那一条走的是<b>真升级</b>而不是直接调算式。
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
    @Autowired
    private CityAppService cityAppService;
    @Autowired
    private PlayerInitService playerInitService;
    @Autowired
    private PlayerRepository players;

    private static PlayerTech techOf(String techId, int level) {
        return new PlayerTech(Map.of(techId, level), null, null, 0L, 0L);
    }

    private static String requestId() {
        return "req-" + UUID.randomUUID();
    }

    /** 新号 + 足够的石头（伐木场 1→2 级只吃石，成本基数 400）。 */
    private String newPlayerWithStone() {
        String playerId = playerInitService.init(new PlayerInitReq(
                requestId(), "dev-" + UUID.randomUUID(), "效果接线", 1_700_000_000_000L, "")).playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        PlayerResourceState stone = save.resource(ResourceIds.STONE);
        save.putResource(ResourceIds.STONE, new PlayerResourceState(9_000L, Math.max(stone.cap(), 9_000L),
                Math.min(stone.protectedAmount(), 9_000L), stone.perHour(), stone.lastSettle()));
        players.save(save);
        return playerId;
    }

    private void putTech(String playerId, Map<String, Integer> levels) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setTech(new PlayerTech(levels, null, null, 0L, 0L));
        players.save(save);
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
    @DisplayName("训练速度读的是募兵令那一行（2%/级 × 5 = 1000 万分比）")
    void trainSpeedPercentReadsItsOwnRow() {
        assertThat(techEffects.trainSpeedPercent(techOf("tech_mil_train", 5)))
                .as("募兵令是 +2%/级（tech.json），不是工役的 +3%")
                .isEqualTo(200L * 5L);
        assertThat(techEffects.buildSpeedPercent(techOf("tech_mil_train", 5)))
                .as("两条速度各归各的属性，混了就等于把同一份钱花两次")
                .isZero();
    }

    @Test
    @DisplayName("一次真实升级：工役 10 级把伐木场 1→2 级的 20 秒压成 14 秒（表 → 合计率 → ceil → finishAt）")
    void buildSpeedActuallyShortensARealUpgrade() {
        String plain = newPlayerWithStone();
        long plainFinishAt = cityAppService.upgrade(plain,
                new CityUpgradeReq(requestId(), "lumber_camp", 2, 2)).finishAt();
        long plainSeconds = (plainFinishAt - System.currentTimeMillis()) / 1000L;
        assertThat(plainSeconds)
                .as("伐木场 timeBaseSec=20，1→2 级取曲线第 1 项 = 20 秒（没有加成时的基数）")
                .isBetween(19L, 21L);

        String boosted = newPlayerWithStone();
        putTech(boosted, Map.of("tech_fort_build", 10));
        long boostedFinishAt = cityAppService.upgrade(boosted,
                new CityUpgradeReq(requestId(), "lumber_camp", 2, 2)).finishAt();
        long boostedSeconds = (boostedFinishAt - System.currentTimeMillis()) / 1000L;
        assertThat(boostedSeconds)
                .as("工役 10 级 = +30%，ceil(20 × 0.7) = 14 秒：从表到完成时刻只有这一条算式")
                .isBetween(13L, 15L);
    }
}
