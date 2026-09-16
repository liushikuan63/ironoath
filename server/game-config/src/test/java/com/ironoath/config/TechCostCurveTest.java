package com.ironoath.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.config.cfg.CurveCfg;
import com.ironoath.config.cfg.TechCfg;

/**
 * 职责：守住科技表的两条<b>消耗</b>侧形状 —— 消耗曲线必须是消耗曲线、研究不许免费。
 * 依赖：仓库内真实的 {@code contract/config}（tech / curve 两张表）。
 *
 * <p><b>这个类为什么存在（一条被 #152 从"响亮失败"改成"静默错值"的缺陷）</b>：
 * {@code tech_mil_train} 与 {@code tech_fort_build} 两行的 {@code costCurve} 原本写的是
 * {@code TECH_TIME} —— 那是<b>研究时长</b>曲线，量纲 SECOND。在此之前它不会出事，因为
 * {@code TECH_TIME.base = 0}，谁去读它谁就抛「基数由业务表逐行提供」；#152 把基数按裁决填成
 * 13（秒）之后，读它不抛了，代价是<b>算出 13 木起步的科研消耗</b>（其余九行是 200~600 起步）。
 * 症状从崩溃变成悄悄错，而"悄悄错"的消耗表是没人会去查的 —— 于是量纲这一条必须成为断言。
 *
 * <p>为什么不在 {@code ConfigValidator} 里查：它做的是单行单列的取值检查（见
 * {@code ChestConfigConsistencyTest} 同一处取舍），而"这一列指向的曲线量纲要匹配这一列的用途"
 * 是跨表 + 按列语义的规则，写进校验器会变成一长串硬编码列名清单。
 */
class TechCostCurveTest {

    private static ConfigRegistry configs;

    @BeforeAll
    static void load() {
        configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    }

    @Test
    @DisplayName("costCurve 只能指向消耗曲线（量纲 FIXED），不能指向时长曲线（量纲 SECOND）")
    void costCurvePointsAtACostCurve() {
        for (TechCfg row : configs.all(TechCfg.class)) {
            assertThat(configs.rawTable("curve").has(row.costCurve()))
                    .as("%s 的 costCurve=%s 在 curve 表里不存在，改表时会静默读不到", row.id(), row.costCurve())
                    .isTrue();
            CurveCfg curve = configs.get(CurveCfg.class, row.costCurve());
            assertThat(curve.unit())
                    .as("%s 的消耗曲线 %s 量纲是 %s —— 时长曲线当消耗用会算出 13 木这种数（#152 之后不再抛异常，"
                                    + "只会悄悄错）。消耗曲线一律用 BUILDING_COST 这类 FIXED 曲线",
                            row.id(), row.costCurve(), curve.unit())
                    .isEqualTo(CurveCfg.Unit.FIXED);
        }
    }

    @Test
    @DisplayName("每行至少收一种资源：全零的消耗行等于免费研究，公理四「资源有地方花」当场失效")
    void everyTechCostsSomething() {
        for (TechCfg row : configs.all(TechCfg.class)) {
            long sum = row.costBaseWood() + row.costBaseStone() + row.costBaseIron() + row.costBaseGrain();
            assertThat(sum)
                    .as("%s 的四种资源基数全为 0（costBaseWood/Stone/Iron/Grain）—— "
                                    + "免费的研究不是长线消耗坑，把它填上一个真实的起手价", row.id())
                    .isPositive();
            assertThat(row.costBaseWood()).as("%s 的木消耗不能为负", row.id()).isNotNegative();
            assertThat(row.costBaseStone()).as("%s 的石消耗不能为负", row.id()).isNotNegative();
            assertThat(row.costBaseIron()).as("%s 的铁消耗不能为负", row.id()).isNotNegative();
            assertThat(row.costBaseGrain()).as("%s 的粮消耗不能为负", row.id()).isNotNegative();
        }
    }
}
