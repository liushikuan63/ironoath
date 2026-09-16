package com.ironoath.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.cfg.CurveCfg;
import com.ironoath.config.cfg.SeasonCfg;
import com.ironoath.config.cfg.TechCfg;

/**
 * 职责：守住 B20 §五① 那条唯一的外部约束 —— <b>科技全树点满必须落在赛季的 50%~70%</b>，
 * 并且守住「基数是量出来的」这句话本身（表里的 why 引用了校准输出，这里核对它还对不对得上）。
 * 依赖：仓库内真实的 {@code contract/config}（tech / curve / season 三张表）+ {@link FixedPoint}。
 *
 * <p><b>为什么要有这个类，而不是只留 {@code tools/calibrate-tech-time.mjs}</b>：
 * 校准脚本是<b>量具</b>，它算出 13 秒然后退出 0；但没人拦得住第二天有人把基数手改成 3600，
 * 或把 {@code tech_mil_atk.maxLevel} 从 40 削到 5 —— 前一种让科技两小时点满（公理四的坑消失），
 * 后一种让整棵树缩水。这两种改法在 {@code ConfigValidator} 的逐字段检查里都是合法的。
 * 于是区间约束必须是断言：<b>改了表 ⇒ 这条红</b>，红字直接告诉改表的人重跑哪条命令。
 *
 * <p><b>为什么算术写在这里而不是用 {@code Formula}</b>：分层卡口规定 game-core 只依赖 game-common
 * （铁律 2），所以 game-config 的测试引用不到 {@code Formula.evaluateSeconds}。
 * 这里刻意用它同一对原语 —— {@code FixedPoint.geometric} + {@code FixedPoint.round}，
 * 也就是 {@code Formula.computeRaw} 的 GEOMETRIC 分支与 {@code evaluateSeconds} 的落地各一步，
 * 所以两边算出的秒数逐个相等（{@code FormulaTest} 那侧另有对照）。
 *
 * <p><b>为什么第 4 条测试去核对 why 里的数字</b>：公理三要求"数值与推导同源"。
 * 基数改了而 why 还写着旧结论，表就变成了第二真相 —— 那比没有 why 更糟。
 * 这条断言的作用不是保护文案，是让"改基数"这件事必须连带重跑校准。
 */
class TechTimeCalibrationTest {

    private static final String CURVE_ID = "TECH_TIME";
    private static final long SECONDS_PER_DAY = 86_400L;
    /** §五① 裁决区间的两端，原文是「11 行全部点满 ≈ 赛季（45 天）的 50%~70%」。 */
    private static final BigDecimal LOW = new BigDecimal("0.5");
    private static final BigDecimal HIGH = new BigDecimal("0.7");

    private static ConfigRegistry configs;

    @BeforeAll
    static void load() {
        configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    }

    private CurveCfg techTime() {
        return configs.get(CurveCfg.class, CURVE_ID);
    }

    /** 与 {@code Formula.evaluateSeconds("TECH_TIME", level)} 同一条算式：定点几何级数，落地时舍入一次。 */
    private long secondsAt(int level) {
        CurveCfg curve = techTime();
        return FixedPoint.round(FixedPoint.geometric(curve.base(), curve.ratio(), level - 1));
    }

    /** 赛季天数：season_01_* 各阶段 durationDays 之和，与校准脚本同一个取法（不写死 45）。 */
    private long seasonSeconds() {
        long days = configs.all(SeasonCfg.class).stream()
                .filter(r -> r.id().startsWith("season_01"))
                .mapToLong(SeasonCfg::durationDays)
                .sum();
        assertThat(days).as("season_01_* 的 durationDays 之和必须为正，否则区间无从谈起").isPositive();
        return days * SECONDS_PER_DAY;
    }

    @Test
    @DisplayName("基数不再是 0：0 会让 evaluateSeconds 直接抛（曲线注释 base=0 = 基数由业务表提供，而科技表没有这一列）")
    void baseIsFilledSoTheCurveCanActuallyBeUsed() {
        CurveCfg curve = techTime();
        assertThat(curve.base())
                .as("TECH_TIME.base 仍是 0 的话，研究时长算出来是 0 秒或抛异常 —— B20 块① 至今没有可算的时长")
                .isPositive();
        assertThat(FixedPoint.toBigDecimal(curve.base()))
                .as("1 级研究的秒数 = 基数，必须恰好是校准脚本取的那个整秒")
                .isEqualByComparingTo(new BigDecimal("13"));
        assertThat(secondsAt(1)).isEqualTo(13L);
        assertThat(FixedPoint.toBigDecimal(curve.ratio()))
                .as("比率不许另抄一份：§五① 明写用 curve 上已有的 1.28")
                .isEqualByComparingTo(new BigDecimal("1.28"));
        assertThat(secondsAt(2)).isGreaterThan(secondsAt(1));
    }

    @Test
    @DisplayName("全树点满 = 赛季的 50%~70%（§五① 的唯一约束；改表改坏了这里先红）")
    void fullTreeFallsInsideTheAdjudicatedBand() {
        long total = 0L;
        int levels = 0;
        for (TechCfg row : configs.all(TechCfg.class)) {
            assertThat(row.maxLevel()).as("%s 的 maxLevel 必须为正", row.id()).isPositive();
            for (int level = 1; level <= row.maxLevel(); level++) {
                total += secondsAt(level);
                levels++;
            }
        }
        BigDecimal percent = BigDecimal.valueOf(total)
                .divide(BigDecimal.valueOf(seasonSeconds()), 4, java.math.RoundingMode.HALF_UP);
        assertThat(levels)
                .as("Σ maxLevel 变了（designNote 现在写的是 340 级）—— 整棵树的时长要重量："
                                + "跑 `node tools/calibrate-tech-time.mjs` 取新基数，并同步 curve 那行的 why")
                .isEqualTo(340);
        assertThat(percent)
                .as("全树 %d 秒 = 赛季的 %.1f%%，不在裁决的 50%%~70%% 之内 —— "
                                + "重跑 `node tools/calibrate-tech-time.mjs` 取新基数，并把新区间写回该行的 why",
                        total, percent.doubleValue() * 100)
                .isGreaterThanOrEqualTo(LOW)
                .isLessThanOrEqualTo(HIGH);
    }

    @Test
    @DisplayName("最慢一步以天计：否则 why 里「后期单级以天计」是在说谎")
    void slowestStepIsMeasuredInDays() {
        long slowest = 0L;
        for (TechCfg row : configs.all(TechCfg.class)) {
            slowest = Math.max(slowest, secondsAt((int) row.maxLevel()));
        }
        assertThat(slowest)
                .as("最慢一步 %.2f 天 —— 不到一天的话整棵树就没有「挂着等」的长线感，why 那句要改",
                        slowest / (double) SECONDS_PER_DAY)
                .isGreaterThanOrEqualTo(SECONDS_PER_DAY);
    }

    @Test
    @DisplayName("why 里引用的数字必须还是本次算出来的这几个（数值与推导同源的机器化版本）")
    void whyStillQuotesTheCalibratedNumbers() {
        String why = configs.rawTable("curve").row(CURVE_ID).get("why").asText();
        assertThat(why)
                .as("why 里没提校准命令 —— 那意味着这个基数是填进去的，不是量出来的")
                .contains("calibrate-tech-time");
        assertThat(why)
                .as("基数已是 %d 秒，why 还写着别的数：改完表要重跑校准并同步这句话",
                        FixedPoint.round(techTime().base()))
                .contains("TR0=" + FixedPoint.round(techTime().base()));
        long total = configs.all(TechCfg.class).stream()
                .mapToLong(row -> {
                    long sum = 0L;
                    for (int level = 1; level <= row.maxLevel(); level++) {
                        sum += secondsAt(level);
                    }
                    return sum;
                })
                .sum();
        String percent = BigDecimal.valueOf(total)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(seasonSeconds()), 1, java.math.RoundingMode.HALF_UP)
                .toPlainString();
        assertThat(why)
                .as("全树实际占赛季 %s%%，why 里的百分比没跟着改", percent)
                .contains(percent + "%");
    }
}
