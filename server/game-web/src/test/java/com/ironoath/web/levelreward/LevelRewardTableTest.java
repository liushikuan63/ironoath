package com.ironoath.web.levelreward;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.BuildingCfg;
import com.ironoath.config.cfg.ChapterCfg;
import com.ironoath.config.cfg.CurveCfg;
import com.ironoath.config.cfg.LevelRewardCfg;
import com.ironoath.core.formula.Formula;

/**
 * 职责：钉住 {@code level_reward} 表本身的三条口径（不是领取链路，那是 {@code LevelRewardClaimTest}）。
 * 依赖：Spring 上下文里的 ConfigRegistry（现读 contract/config）、game-core 的 Formula。
 *
 * <p><b>为什么比值判据必须现算造价而不是抄表注释</b>：表里只有绝对值，「木石 = 造价 × 5%/25%」这句话
 * 是产品裁决（收口清单 #829 同轮弹窗），代码里除了这张表没有第二处存着它。于是这一族的读数只能这样得到：
 * 造价从 {@code building.main_city} 的基数 + {@code curve.BUILDING_COST} 的比率由 Formula 现算，
 * 奖励从表里现读，两边加起来比。这样做的直接收益是 <b>curve 或造价基数一改，这里立刻红</b> ——
 * 表不会静默过期（这是"每行存绝对值而不存比例"这个选择唯一真正的代价，用它来抵）。
 *
 * <p><b>反证（本轮实测，别只信"绿"）</b>：把某一段的奖励整段 ×2 之后，本类的比值用例必须翻红；
 * 读数记在收口清单对应条目里。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("level_reward 表：行数 / 两段同比例 / 金币档价锚")
class LevelRewardTableTest {

    /** 裁决的分段边界：属性增长到 30 级为止（#591），31~40 只给资源奖励。 */
    private static final long SEGMENT_SPLIT = 30L;
    /** 两段的目标比例（2026-10-08 弹窗裁决：1→30 用 5%、31→40 用 25%）。 */
    private static final BigDecimal RATIO_LOW = new BigDecimal("0.05");
    private static final BigDecimal RATIO_HIGH = new BigDecimal("0.25");
    /**
     * 容差 0.0005（相对 5% 是 1%）：表里的每行奖励是按 HALF_UP 落到整数的，
     * 单行偏离最多半个单位，30 行累计偏离相对造价总量是 1e-5 量级 —— 留出一个数量级的余量，
     * 但比「某段 ×2」的偏离（整整一倍）小两个数量级，所以植入一定翻红。
     */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.0005");

    @Autowired private ConfigRegistry configs;

    @Test
    @DisplayName("行数与主城上限同界：1..maxLevel 逐级都有行，且三列奖励非负")
    void rowsCoverEveryLevelUpToMainCityCap() {
        long maxLevel = configs.get(BuildingCfg.class, "main_city").maxLevel();
        List<LevelRewardCfg> rows = levelRewardRows();

        assertThat(rows).as("level_reward 行数必须等于主城 maxLevel（现读，不抄文档）")
                .hasSize((int) maxLevel);
        assertThat(rows.stream().map(LevelRewardCfg::level).toList())
                .as("等级必须 1..maxLevel 逐级连续，不能有跳级或重复")
                .containsExactlyElementsOf(
                        java.util.stream.LongStream.rangeClosed(1, maxLevel).boxed().toList());
        for (LevelRewardCfg row : rows) {
            assertThat(row.rewardWood()).as("level %s 的木奖励不得为负", row.level()).isGreaterThanOrEqualTo(0L);
            assertThat(row.rewardStone()).as("level %s 的石奖励不得为负", row.level()).isGreaterThanOrEqualTo(0L);
            assertThat(row.rewardGold()).as("level %s 的金币不得为负", row.level()).isGreaterThanOrEqualTo(0L);
            assertThat(row.name()).as("level %s 必须有下发给玩家的中文名", row.level()).isNotBlank();
        }
    }

    @Test
    @DisplayName("两段各自同比例：累计木石奖励 ÷ 累计主城造价 = 5% 与 25%（造价由 Formula 现算）")
    void woodAndStoneTrackCostAtFiveThenTwentyFivePercent() {
        BigDecimal lowWood = ratioOfSegment(1L, SEGMENT_SPLIT, ResourceCost.WOOD, RATIO_LOW);
        BigDecimal highWood = ratioOfSegment(SEGMENT_SPLIT + 1, maxLevel(), ResourceCost.WOOD, RATIO_HIGH);
        BigDecimal lowStone = ratioOfSegment(1L, SEGMENT_SPLIT, ResourceCost.STONE, RATIO_LOW);
        BigDecimal highStone = ratioOfSegment(SEGMENT_SPLIT + 1, maxLevel(), ResourceCost.STONE, RATIO_HIGH);

        assertThat(highWood.subtract(lowWood).abs())
                .as("裁决是「分段」而不是全表统一：段间比例差必须等于 25%%−5%%＝20 个百分点，"
                        + "实测低段 %s、高段 %s", lowWood, highWood)
                .isCloseTo(new BigDecimal("0.20"), org.assertj.core.api.Assertions.within(TOLERANCE));
        assertThat(highWood.divide(lowWood, 4, RoundingMode.HALF_UP))
                .as("高段比例必须是低段的 5 倍（5% → 25%），实测 %s", highWood.divide(lowWood, 4, RoundingMode.HALF_UP))
                .isCloseTo(new BigDecimal("5.0000"), org.assertj.core.api.Assertions.within(new BigDecimal("0.05")));
        // 木与石必须同比例：主城两维的基数都是 1000，奖励若一边倒说明表是手抄的而不是算出来的
        assertThat(lowStone).as("低段石奖励比例必须与木一致").isCloseTo(lowWood, org.assertj.core.api.Assertions.within(TOLERANCE));
        assertThat(highStone).as("高段石奖励比例必须与木一致").isCloseTo(highWood, org.assertj.core.api.Assertions.within(TOLERANCE));
    }

    @Test
    @DisplayName("金币档价只能来自 chapter.rewardGold：逐个档价与档位边界都由章节表现推")
    void goldUsesOnlyChapterRewardGoldTiers() {
        List<ChapterCfg> chapters = configs.all(ChapterCfg.class).stream()
                .sorted(Comparator.comparingLong(ChapterCfg::chapterNo))
                .toList();
        List<Long> tierGolds = chapters.stream().map(ChapterCfg::rewardGold).toList();
        long maxLevel = maxLevel();
        long bandSize = (maxLevel + chapters.size() - 1) / chapters.size();

        assertThat(tierGolds).as("章节档价必须非负且单调不降，否则下面的推法不成立")
                .isSorted();
        for (LevelRewardCfg row : levelRewardRows()) {
            long index = Math.min(chapters.size() - 1, (row.level() - 1) / bandSize);
            assertThat(row.rewardGold())
                    .as("level %s 的金币必须等于第 %s 档章节档价（档位边界 = maxLevel ÷ 章节数 = %s 级一档）",
                            row.level(), index + 1, bandSize)
                    .isEqualTo(tierGolds.get((int) index));
        }
    }

    // ---------- 内部 ----------

    private enum ResourceCost { WOOD, STONE }

    private long maxLevel() {
        return configs.get(BuildingCfg.class, "main_city").maxLevel();
    }

    private List<LevelRewardCfg> levelRewardRows() {
        return configs.all(LevelRewardCfg.class).stream()
                .sorted(Comparator.comparingLong(LevelRewardCfg::level))
                .collect(Collectors.toList());
    }

    /** 段内「累计奖励 ÷ 累计造价」，造价用 Formula 现算（与建造扣资源同一个函数）。 */
    private BigDecimal ratioOfSegment(long from, long to, ResourceCost which, BigDecimal expected) {
        BuildingCfg main = configs.get(BuildingCfg.class, "main_city");
        long ratioFixed = configs.get(CurveCfg.class, "BUILDING_COST").ratio();
        long costBase = which == ResourceCost.WOOD ? main.costBaseWood() : main.costBaseStone();
        assertThat(costBase).as("主城 %s 造价基数必须为正，否则比值无意义", which).isPositive();

        List<LevelRewardCfg> rows = levelRewardRows().stream()
                .filter(r -> r.level() >= from && r.level() <= to).toList();
        assertThat(rows).as("段 %s..%s 必须覆盖每一级", from, to).hasSize((int) (to - from + 1));

        long costSum = 0L;
        long rewardSum = 0L;
        List<Long> perLevelCost = new ArrayList<>();
        for (LevelRewardCfg row : rows) {
            long cost = Formula.buildingCost(costBase, (int) row.level(), ratioFixed);
            perLevelCost.add(cost);
            costSum += cost;
            rewardSum += which == ResourceCost.WOOD ? row.rewardWood() : row.rewardStone();
        }
        assertThat(costSum).as("累计造价必须为正").isPositive();

        BigDecimal ratio = BigDecimal.valueOf(rewardSum)
                .divide(BigDecimal.valueOf(costSum), 8, RoundingMode.HALF_UP);
        assertThat(ratio)
                .as("段 %s..%s 的累计奖励÷累计造价必须落在 %s ± %s 内（实测 %s，奖励合计 %s，造价合计 %s）",
                        from, to, expected, TOLERANCE, ratio, rewardSum, costSum)
                .isBetween(expected.subtract(TOLERANCE), expected.add(TOLERANCE));
        // 每一级的奖励都必须严格跟随该级造价（不是只在合计上凑对）：
        // 只比合计的话，「前半段给多点、后半段给少点」也能过，而那不是同比例
        for (int i = 0; i < rows.size(); i++) {
            LevelRewardCfg row = rows.get(i);
            long reward = which == ResourceCost.WOOD ? row.rewardWood() : row.rewardStone();
            BigDecimal expectedRow = BigDecimal.valueOf(perLevelCost.get(i))
                    .multiply(expected).setScale(0, RoundingMode.HALF_UP);
            assertThat(reward)
                    .as("level %s 的 %s 奖励必须等于该级造价 × 段比例（造价 %s）",
                            row.level(), which, perLevelCost.get(i))
                    .isCloseTo(expectedRow.longValue(), org.assertj.core.api.Assertions.within(1L));
        }
        return ratio;
    }
}
