package com.ironoath.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.cfg.BuildingCfg;
import com.ironoath.config.cfg.CurveCfg;
import com.ironoath.config.cfg.EquipCfg;

/**
 * 职责：守住 B20 块② 装备强化的三条数值约束，并守住「基数是量出来的」这句话本身。
 * 依赖：仓库内真实的 {@code contract/config}（equip / curve / building 三张表）+ {@link FixedPoint}。
 *
 * <p><b>为什么要有这个类，而不只留 {@code tools/calibrate-equip-forge.mjs}</b>：校准脚本是<b>量具</b>，
 * 它算出 70 然后退出 0；但没人拦得住第二天有人把 {@code curve.EQUIP_FORGE_COST.base} 手改成 70000，
 * 或给某一行的 {@code forgeMax} 单独改成 999 —— 前者让强化一夜满级（公理四的坑消失），
 * 后者让一件装备变成一条与稀有度无关的特例。这两种改法在 {@code ConfigValidator} 的逐字段检查里都合法。
 * 于是区间必须是断言：<b>改了表 ⇒ 这里红</b>，红字直接告诉改表的人重跑哪条命令。
 *
 * <p><b>三条判据与脚本一一对应</b>（口径抄自 §五② 与脚本头注）：
 * ① 一级铁耗 ≥ 参考时点（iron_mine Lv10）<b>0.5 小时</b>产量 —— 坑不许太浅；
 * ② 一件入门档（N）从 +1 点满的总铁耗 ≤ 同一时点 <b>5 天</b>产量 —— 新手够得着；
 * ③ {@code forgeMax} 只由稀有度决定 —— 价格是「属性总和 × 基数」，稀有度只管能点几级。
 * 判据③不在脚本里（脚本用它当输入），所以只有这里能发现「有人给某一行开了小灶」。
 *
 * <p><b>为什么算术写在这里而不用 {@code Formula}</b>：分层卡口规定 game-core 只依赖 game-common（铁律 2），
 * game-config 的测试引用不到 {@code Formula}。这里用同一对原语 {@code FixedPoint.geometric} +
 * {@code FixedPoint.round}，也就是生产算式的两步，所以两边算出的铁数逐个相等。
 */
class EquipForgeCostCalibrationTest {

    private static final String CURVE_ID = "EQUIP_FORGE_COST";
    /** 校准锚点所在时点：铁矿产 Lv10（脚本与本类必须一致，否则两条判据量的不是同一件事）。 */
    private static final int REFERENCE_MINE_LEVEL = 10;
    private static final String MINE_ID = "iron_mine";

    private static ConfigRegistry configs;

    @BeforeAll
    static void load() {
        configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    }

    private CurveCfg forge() {
        return configs.get(CurveCfg.class, CURVE_ID);
    }

    /** 该行买得起的强度：三维同价（装备三维在武将算式里是并列的加算项）。 */
    private static long attrPoints(EquipCfg equip) {
        return equip.might() + equip.command() + equip.wisdom();
    }

    /** 从 +0 点一级到 level 级的铁耗 = 属性总和 × 基数 × ratio^(level-1)，与生产算式同为两步定点。 */
    private long costAt(EquipCfg equip, int level) {
        CurveCfg curve = forge();
        // curve.base() 已是定点（70 → 700000），属性点数是普通整数：直接相乘就是行价的定点基数，
        // 再套一次 FixedPoint.of 会把价钱放大一万倍
        long rowBaseFixed = attrPoints(equip) * curve.base();
        return FixedPoint.round(FixedPoint.geometric(rowBaseFixed, curve.ratio(), level - 1));
    }

    /** 参考时点的铁产量：iron_mine 的 P0 按 BUILDING_OUTPUT 的幂函数放大。 */
    private BigDecimal ironPerHour() {
        BuildingCfg mine = configs.get(BuildingCfg.class, MINE_ID);
        CurveCfg output = configs.get(CurveCfg.class, "BUILDING_OUTPUT");
        double exponent = FixedPoint.toBigDecimal(output.exponent()).doubleValue();
        return BigDecimal.valueOf(mine.outputBasePerHour())
                .multiply(BigDecimal.valueOf(Math.pow(REFERENCE_MINE_LEVEL, exponent)));
    }

    @Test
    @DisplayName("基数与比率：base=量出来的那个数，ratio 与 BUILDING_COST 同一条（§五② 明写不另抄）")
    void baseIsFilledAndRatioIsShared() {
        CurveCfg curve = forge();
        assertThat(curve.kind()).as("强化消耗是几何递增").isEqualTo(CurveCfg.Kind.GEOMETRIC);
        assertThat(curve.unit()).as("量纲是「多少个铁」，不是秒也不是百分比").isEqualTo(CurveCfg.Unit.FIXED);
        assertThat(curve.base())
                .as("base 仍是 0 的话行价算出来是 0，等于免费强化 —— §五② 说 base 由表给，就得真给")
                .isPositive();
        assertThat(FixedPoint.toBigDecimal(curve.base()))
                .as("1 点属性的一级铁价必须恰好是校准脚本取的那个数")
                .isEqualByComparingTo(new BigDecimal("70"));
        assertThat(curve.ratio())
                .as("比率另抄一份 = 两条曲线将来各自漂移；§五② 要求与 BUILDING_COST 同值")
                .isEqualTo(configs.get(CurveCfg.class, "BUILDING_COST").ratio());
        List<EquipCfg> equips = new ArrayList<>(configs.all(EquipCfg.class));
        assertThat(equips).as("装备表不能空，空表会让下面所有判据真空通过").isNotEmpty();
        assertThat(costAt(equips.get(0), 2)).isGreaterThan(costAt(equips.get(0), 1));
    }

    @Test
    @DisplayName("C1 一级强化 ≥ 参考时点 0.5 小时的铁产量：否则「资源有地方花」这个目的没达成")
    void oneForgeLevelIsWorthAtLeastHalfAnHourOfIron() {
        BigDecimal perHour = ironPerHour();
        assertThat(perHour).as("%s Lv%d 的每小时产量必须为正", MINE_ID, REFERENCE_MINE_LEVEL).isPositive();
        BigDecimal floor = perHour.multiply(new BigDecimal("0.5"));
        EquipCfg cheapest = null;
        for (EquipCfg equip : configs.all(EquipCfg.class)) {
            assertThat(attrPoints(equip))
                    .as("装备 %s 三维全为 0：它既买不到强度也付不出价钱（价格是属性总和乘出来的）", equip.id())
                    .isPositive();
            long cost = costAt(equip, 1);
            if (cheapest == null || cost < costAt(cheapest, 1)) {
                cheapest = equip;
            }
            assertThat(BigDecimal.valueOf(cost))
                    .as("装备 %s 一级只要 %d 铁，低于 0.5 小时产量 %s",
                            equip.id(), cost, floor.toPlainString())
                    .isGreaterThanOrEqualTo(floor);
        }
        assertThat(cheapest).isNotNull();
    }

    @Test
    @DisplayName("C2 一件入门装点满 ≤ 参考时点 5 天的铁产量：N 档是第一天就穿满 4 槽的装，不许是无底洞")
    void entryTierCanBeFullyForgedWithinFiveDays() {
        BigDecimal perHour = ironPerHour();
        BigDecimal ceiling = perHour.multiply(new BigDecimal("5")).multiply(new BigDecimal("24"));
        for (EquipCfg equip : configs.all(EquipCfg.class)) {
            if (!isEntryTier(equip)) {
                continue;
            }
            long total = 0L;
            for (int level = 1; level <= equip.forgeMax(); level++) {
                total += costAt(equip, level);
            }
            assertThat(BigDecimal.valueOf(total))
                    .as("入门装 %s 点满 +%d 要 %d 铁 = %.1f 天产量，超过 5 天这条线新手就碰不到装备强化",
                            equip.id(), equip.forgeMax(), total,
                            BigDecimal.valueOf(total).divide(perHour, 1, java.math.RoundingMode.HALF_UP)
                                    .doubleValue() / 24)
                    .isLessThanOrEqualTo(ceiling);
        }
    }

    @Test
    @DisplayName("C3 forgeMax 只由稀有度决定（N 一档 SR 一档），一件都不许多")
    void forgeCapTracksRarityOnly() {
        List<String> offGrid = new ArrayList<>();
        long nCap = 0L;
        for (EquipCfg equip : configs.all(EquipCfg.class)) {
            if (equip.rarity() == EquipCfg.Rarity.N) {
                if (nCap == 0L) {
                    nCap = equip.forgeMax();
                }
                if (equip.forgeMax() != nCap) {
                    offGrid.add(equip.id() + "(N=" + equip.forgeMax() + "，同档其他行=" + nCap + ")");
                }
            } else if (equip.forgeMax() <= nCap) {
                // 高档上限不高过低档 = 稀有度这条线在强化里白标
                offGrid.add(equip.id() + "(" + equip.rarity() + "=" + equip.forgeMax() + "，不高于 N 档的 " + nCap + ")");
            }
        }
        assertThat(nCap).as("必须至少有一行 N 档装备，否则 C2 那条判据真空通过").isPositive();
        assertThat(offGrid)
                .as("§五② 定的是「上限与稀有度挂钩（N=10 / SR=15）」，现在出现了特例")
                .isEmpty();
        assertThat(configs.all(EquipCfg.class).stream()
                .filter(e -> e.rarity() != EquipCfg.Rarity.N)
                .mapToLong(EquipCfg::forgeMax).max().orElse(0L))
                .as("非 N 档的上限必须真的更高，否则这条分档只是注释")
                .isGreaterThan(nCap);
    }

    @Test
    @DisplayName("why 里引用的数字必须还是本次算出来的这几个（数值与推导同源的机器化版本）")
    void whyStillQuotesTheCalibratedNumbers() {
        String why = configs.rawTable("curve").row(CURVE_ID).get("why").asText();
        assertThat(why)
                .as("why 里没提校准命令 —— 那意味着这个基数是填进去的，不是量出来的")
                .contains("calibrate-equip-forge");
        assertThat(why)
                .as("基数已是 %d，why 还写着别的数：改完表要重跑校准并同步这句话",
                        FixedPoint.round(forge().base()))
                .contains("base=" + FixedPoint.round(forge().base()));
        assertThat(why)
                .as("比率已是 %s，why 里那句「沿用 BUILDING_COST」就不成立了",
                        FixedPoint.toBigDecimal(forge().ratio()).toPlainString())
                .contains("1.22");
    }

    /** 入门档 = 稀有度最低的那一批（脚本用同一个定义：rarity==N）。 */
    private boolean isEntryTier(EquipCfg equip) {
        return equip.rarity() == EquipCfg.Rarity.N;
    }
}
