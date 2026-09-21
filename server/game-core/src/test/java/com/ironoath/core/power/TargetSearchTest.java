package com.ironoath.core.power;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.rng.Rng;
import com.ironoath.core.world.Coord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：B08 §8 目标搜索的验证 —— 验收 11（1000 玩家采样）与各项过滤、分档、确定性。
 * 依赖：JUnit 5 + AssertJ；纯 Java，不启动容器（这正是把搜索放在 game-core 的理由）。
 *
 * <p><b>验收 11 的两半强度不同，本类分开对待</b>：
 * <ul>
 *   <li>「2.1x 对手绝不出现」是<b>硬约束</b>：无论战力分布多宽都必须成立，
 *       因为它是圈层机制本身。任何一次泄漏都意味着 PowerBandGuard 被绕过了。</li>
 *   <li>「同段位占比 ≥ 60%」是<b>排序质量</b>指标：它取决于候选池的战力分布。
 *       同区域玩家的战力本就集中（落位按注册时间螺旋铺开，邻居的游玩时长相近），
 *       本类用这个真实分布验证 60%；另外用一个刻意拉宽的对抗分布验证
 *       「排序确实把同段位往前推了」，而不是硬要求 60% —— 在 2.1 倍宽的分布里
 *       同段位本来就只占三分之一，要求 60% 等于要求战力权重压过其余三项之和，
 *       而 B08 明确给的是 0.35 对 0.65。</li>
 * </ul>
 */
class TargetSearchTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final int WORLD = 512;
    private static final long SELF_POWER = 10_000L;
    private static final Coord SELF_COORD = Coord.of(256, 256);

    /** 与 global 表 v18 一致的搜索规则。 */
    private static TargetSearch.Rules rules() {
        return new TargetSearch.Rules(
                128, 48, 48L * 3600_000L, 20, 50,
                FixedPoint.parse("0.35"), FixedPoint.parse("0.25"),
                FixedPoint.parse("0.25"), FixedPoint.parse("0.15"),
                FixedPoint.parse("0.25"), FixedPoint.parse("0.60"),
                FixedPoint.parse("0.70"), FixedPoint.parse("0.30"),
                FixedPoint.parse("0.71"), FixedPoint.parse("1.41"));
    }

    /** 单人区间 [0.5x, 2.0x]，与 PVP_POWER_MIN/MAX_RATIO 一致。 */
    private static PowerBandGuard.Rules band() {
        return new PowerBandGuard.Rules(FixedPoint.parse("0.5"), FixedPoint.parse("2.0"), true);
    }

    private static TargetSearch.Candidate self() {
        return new TargetSearch.Candidate("self", SELF_COORD, SELF_POWER, false, null,
                NOW, FixedPoint.parse("0.5"), 0L);
    }

    // ---------- 验收 11 ----------

    @Test
    @DisplayName("验收11：1000 玩家采样，2.1x 对手绝不出现，同段位占比 >= 60%")
    void samplingAThousandPlayersNeverLeaksOutOfBandAndFavorsPeers() {
        TargetSearch.Rules rules = rules();
        int peerTotal = 0;
        int resultTotal = 0;
        int outOfBandInPool = 0;
        int sampled = 20;

        for (int round = 0; round < sampled; round++) {
            // 每轮换一个种子：单一轮次凑巧达标没有意义，验收要的是稳定行为
            Rng rng = Rng.of(20260907L + round);
            List<TargetSearch.Candidate> pool = new ArrayList<>(1000);
            for (int i = 0; i < 1000; i++) {
                // 同区域玩家的战力集中在自己上下 40% 以内：落位按注册时间螺旋铺开，
                // 邻居的游玩时长相近，因此战力也相近。这是圈层机制能成立的现实前提
                long ratio = rng.nextFixed(FixedPoint.parse("0.30"), FixedPoint.parse("3.00"));
                long power = FixedPoint.round(FixedPoint.mul(FixedPoint.of(SELF_POWER), ratio));
                int dx = (int) rng.range(-64, 64);
                int dy = (int) rng.range(-64, 64);
                pool.add(new TargetSearch.Candidate("p_" + round + "_" + i,
                        Coord.of(clamp(SELF_COORD.x() + dx), clamp(SELF_COORD.y() + dy)),
                        power, false, null, NOW,
                        rng.nextFixed(0L, FixedPoint.SCALE), 0L));
                if (!PowerBandGuard.check(SELF_POWER, power, 1, band()).allowed()) {
                    outOfBandInPool++;
                }
            }

            List<TargetSearch.Scored> result = TargetSearch.search(self(), pool,
                    new TargetSearch.Request(128, 50, rules.activeCutoff(NOW)),
                    rules, band(), Rng.of(99L + round));
            for (TargetSearch.Scored item : result) {
                // 硬约束：任何一个超出 [0.5x, 2.0x] 的目标都是圈层被绕过
                assertThat(PowerBandGuard.check(SELF_POWER, item.candidate().matchPower(), 1, band())
                        .allowed())
                        .as("超出圈层的目标出现在搜索结果里，round=%d power=%d ratio=%d",
                                round, item.candidate().matchPower(), item.powerRatio())
                        .isTrue();
                assertThat(item.powerRatio())
                        .as("2.1x 的对手绝不能出现（验收 11 原文）")
                        .isLessThanOrEqualTo(FixedPoint.parse("2.0"));
                assertThat(item.candidate().playerId()).isNotEqualTo("self");
                resultTotal++;
                if (item.peer()) {
                    peerTotal++;
                }
            }
        }

        assertThat(outOfBandInPool)
                .as("候选池里必须真的有超出圈层的玩家，否则「2.1x 绝不出现」这条断言是空的")
                .isGreaterThan(1000);
        assertThat(resultTotal).as("20 轮搜索应当都有结果").isPositive();
        double peerShare = (double) peerTotal / resultTotal;
        assertThat(peerShare)
                .as("同段位占比必须 >= 60%%（验收 11）。实际 %d/%d = %.3f", peerTotal, resultTotal, peerShare)
                .isGreaterThanOrEqualTo(0.60d);
    }

    @Test
    @DisplayName("战力分布被刻意拉宽时，排序仍然把同段位推到前面（占比高于随机基线）")
    void rankingLiftsPeerShareAboveBaselineOnWideDistribution() {
        TargetSearch.Rules rules = rules();
        Rng rng = Rng.of(4242L);
        List<TargetSearch.Candidate> pool = new ArrayList<>(1000);
        int inBand = 0;
        int peerInBand = 0;
        for (int i = 0; i < 1000; i++) {
            // 0.1x ~ 10x 的均匀分布：这是一个「圈层机制几乎不起作用」的极端服，
            // 用来验证排序本身有没有方向性，而不是验证 60% 这个数字
            long ratio = rng.nextFixed(FixedPoint.parse("0.10"), FixedPoint.parse("10.00"));
            long power = FixedPoint.round(FixedPoint.mul(FixedPoint.of(SELF_POWER), ratio));
            pool.add(new TargetSearch.Candidate("w_" + i,
                    Coord.of(clamp(SELF_COORD.x() + (int) rng.range(-64, 64)),
                            clamp(SELF_COORD.y() + (int) rng.range(-64, 64))),
                    power, false, null, NOW, rng.nextFixed(0L, FixedPoint.SCALE), 0L));
            if (PowerBandGuard.check(SELF_POWER, power, 1, band()).allowed()) {
                inBand++;
                if (TargetSearch.isPeer(FixedPoint.div(FixedPoint.of(power), FixedPoint.of(SELF_POWER)),
                        rules)) {
                    peerInBand++;
                }
            }
        }
        List<TargetSearch.Scored> result = TargetSearch.search(self(), pool,
                new TargetSearch.Request(128, 50, rules.activeCutoff(NOW)),
                rules, band(), Rng.of(7L));

        assertThat(result).as("拉宽分布下依然要有结果").isNotEmpty();
        double baseline = (double) peerInBand / Math.max(inBand, 1);
        double actual = result.stream().filter(TargetSearch.Scored::peer).count() / (double) result.size();
        assertThat(actual)
                .as("排序必须把同段位往前推：基线 %.3f（候选池里的同段位占比），实际 %.3f。"
                        + "若两者接近，说明 0.35 的战力权重被其余三项淹没了", baseline, actual)
                .isGreaterThan(baseline * 1.5d);
        assertThat(result).allMatch(item ->
                PowerBandGuard.check(SELF_POWER, item.candidate().matchPower(), 1, band()).allowed());
    }

    // ---------- 过滤 ----------

    @Test
    @DisplayName("护盾、死号、自己、同盟、半径外的候选一律不进结果")
    void poolFiltersExcludeShieldedInactiveSelfAllianceAndFaraway() {
        TargetSearch.Rules rules = rules();
        long activeCutoff = rules.activeCutoff(NOW);
        List<TargetSearch.Candidate> pool = List.of(
                new TargetSearch.Candidate("ok", Coord.of(266, 256), SELF_POWER, false, null,
                        NOW, FixedPoint.parse("0.5"), 0L),
                new TargetSearch.Candidate("shielded", Coord.of(267, 256), SELF_POWER, true, null,
                        NOW, FixedPoint.parse("0.5"), 0L),
                new TargetSearch.Candidate("dead", Coord.of(268, 256), SELF_POWER, false, null,
                        activeCutoff - 1L, FixedPoint.parse("0.5"), 0L),
                new TargetSearch.Candidate("self", SELF_COORD, SELF_POWER, false, null,
                        NOW, FixedPoint.parse("0.5"), 0L),
                new TargetSearch.Candidate("far", Coord.of(400, 400), SELF_POWER, false, null,
                        NOW, FixedPoint.parse("0.5"), 0L),
                new TargetSearch.Candidate("tooStrong", Coord.of(269, 256), SELF_POWER * 21 / 10,
                        false, null, NOW, FixedPoint.parse("0.5"), 0L),
                new TargetSearch.Candidate("tooWeak", Coord.of(270, 256), SELF_POWER * 3 / 10,
                        false, null, NOW, FixedPoint.parse("0.5"), 0L));

        List<TargetSearch.Scored> result = TargetSearch.search(self(), pool,
                new TargetSearch.Request(128, 50, activeCutoff), rules, band(), Rng.of(1L));
        assertThat(result).extracting(item -> item.candidate().playerId()).containsExactly("ok");
    }

    @Test
    @DisplayName("同盟过滤只在两边都有联盟时生效：无联盟玩家之间可以互相搜到")
    void allianceFilterOnlyAppliesWhenBothSidesHaveAlliance() {
        TargetSearch.Rules rules = rules();
        TargetSearch.Candidate loneSelf = new TargetSearch.Candidate("self", SELF_COORD,
                SELF_POWER, false, null, NOW, FixedPoint.parse("0.5"), 0L);
        List<TargetSearch.Candidate> pool = List.of(
                new TargetSearch.Candidate("ally", Coord.of(266, 256), SELF_POWER, false, "alliance_1",
                        NOW, FixedPoint.parse("0.5"), 0L),
                new TargetSearch.Candidate("other", Coord.of(267, 256), SELF_POWER, false, "alliance_2",
                        NOW, FixedPoint.parse("0.5"), 0L),
                new TargetSearch.Candidate("lone", Coord.of(268, 256), SELF_POWER, false, null,
                        NOW, FixedPoint.parse("0.5"), 0L));

        // 自己无联盟：谁都能打。用空串而不是 null 表示「无联盟」会让所有无联盟玩家被判成同盟，
        // 于是新号谁都搜不到 —— 这是 B10 落地前最容易踩的一个坑
        assertThat(TargetSearch.search(loneSelf, pool,
                new TargetSearch.Request(128, 50, rules.activeCutoff(NOW)), rules, band(), Rng.of(1L)))
                .extracting(item -> item.candidate().playerId())
                .containsExactlyInAnyOrder("ally", "other", "lone");

        TargetSearch.Candidate alliedSelf = new TargetSearch.Candidate("self", SELF_COORD,
                SELF_POWER, false, "alliance_1", NOW, FixedPoint.parse("0.5"), 0L);
        assertThat(TargetSearch.search(alliedSelf, pool,
                new TargetSearch.Request(128, 50, rules.activeCutoff(NOW)), rules, band(), Rng.of(1L)))
                .as("自己有联盟时，同联盟的人不可攻击")
                .extracting(item -> item.candidate().playerId())
                .containsExactlyInAnyOrder("other", "lone");
    }

    @Test
    @DisplayName("半径与数量都被截断而不是拒绝：玩家拖滑块越界时不该收到错误码")
    void radiusAndCountAreClampedNotRejected() {
        TargetSearch.Rules rules = rules();
        List<TargetSearch.Candidate> pool = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            pool.add(new TargetSearch.Candidate("p" + i, Coord.of(256 + (i % 20), 256 + (i / 20)),
                    SELF_POWER, false, null, NOW, FixedPoint.parse("0.5"), 0L));
        }
        List<TargetSearch.Scored> clampedRadius = TargetSearch.search(self(), pool,
                new TargetSearch.Request(99999, 50, rules.activeCutoff(NOW)), rules, band(), Rng.of(1L));
        List<TargetSearch.Scored> atCap = TargetSearch.search(self(), pool,
                new TargetSearch.Request(128, 50, rules.activeCutoff(NOW)), rules, band(), Rng.of(1L));
        assertThat(clampedRadius)
                .as("半径超上限被截断到 128，结果与直接传 128 完全一致")
                .hasSameElementsAs(atCap);
        assertThat(atCap).hasSize(50);

        List<TargetSearch.Scored> defaulted = TargetSearch.search(self(), pool,
                new TargetSearch.Request(128, null, rules.activeCutoff(NOW)), rules, band(), Rng.of(1L));
        assertThat(defaulted).as("maxCount 缺省用 SEARCH_DEFAULT_COUNT").hasSize(20);
        assertThat(TargetSearch.search(self(), pool,
                new TargetSearch.Request(128, 9999, rules.activeCutoff(NOW)), rules, band(), Rng.of(1L)))
                .as("maxCount 超上限被截断").hasSize(50);
    }

    @Test
    @DisplayName("请求没带半径时用 SEARCH_DEFAULT_RADIUS —— 夹成 1 格会让第一次搜索必然空手")
    void nullRadiusUsesTheDefaultRatherThanCollapsingToOneTile() {
        TargetSearch.Rules rules = rules();
        assertThat(rules.resolveRadius(null)).as("null 用默认半径，与 maxCount 同一条口径")
                .isEqualTo(48);
        assertThat(rules.resolveRadius(0))
                .as("0 不是「没给」，它是一个越界值：夹到地板值。0 格只有自家那一格，而自己会被剔除")
                .isEqualTo(TargetSearch.MIN_RADIUS);
        assertThat(rules.resolveRadius(99999)).as("超上限夹到 maxRadius").isEqualTo(128);

        List<TargetSearch.Candidate> pool = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            pool.add(new TargetSearch.Candidate("p" + i, Coord.of(256 + (i % 20), 256 + (i / 20)),
                    SELF_POWER, false, null, NOW, FixedPoint.parse("0.5"), 0L));
        }
        List<String> byDefault = idsOf(TargetSearch.search(self(), pool,
                new TargetSearch.Request(null, 50, rules.activeCutoff(NOW)), rules, band(), Rng.of(1L)));
        List<String> byExplicit = idsOf(TargetSearch.search(self(), pool,
                new TargetSearch.Request(48, 50, rules.activeCutoff(NOW)), rules, band(), Rng.of(1L)));

        assertThat(byDefault)
                .as("默认半径那一相必须搜得到人：客户端在拿到第一份响应之前发不出数字，"
                        + "这条落空就等于「玩家点搜索 → 永远空列表」")
                .isNotEmpty();
        assertThat(byDefault)
                .as("null 与显式传默认值必须逐条同形（同种子同顺序）")
                .isEqualTo(byExplicit);
        assertThat(idsOf(TargetSearch.search(self(), pool,
                new TargetSearch.Request(0, 50, rules.activeCutoff(NOW)), rules, band(), Rng.of(1L))))
                .as("旧客户端那种「半径 0」只能摸到紧邻四格 —— 这就是面板永远空着的成因")
                .hasSizeLessThan(byDefault.size());
    }

    // ---------- 分档 ----------

    @Test
    @DisplayName("距离只给三档：25% 半径内 NEAR，60% 内 MID，其余 FAR（验收 12 的一半）")
    void distanceIsBandedNotExact() {
        TargetSearch.Rules rules = rules();
        assertThat(TargetSearch.distanceBand(32, 128, rules)).isEqualTo(TargetSearch.DistanceBand.NEAR);
        assertThat(TargetSearch.distanceBand(33, 128, rules)).isEqualTo(TargetSearch.DistanceBand.MID);
        // 60% × 128 = 76.8，四舍五入到 77，所以 MID 的上边界是 77 而不是 76。
        // 把边界钉在这里，改分档比例时才会立刻看到影响面
        assertThat(TargetSearch.distanceBand(77, 128, rules)).isEqualTo(TargetSearch.DistanceBand.MID);
        assertThat(TargetSearch.distanceBand(78, 128, rules)).isEqualTo(TargetSearch.DistanceBand.FAR);
        assertThat(TargetSearch.DistanceBand.values())
                .as("多一档就等于开始下发精确距离")
                .hasSize(3);
    }

    @Test
    @DisplayName("资源只给三档：70% 以上 RICH，30% 以下 POOR，中间 NORMAL")
    void resourceIsBandedNotExact() {
        TargetSearch.Rules rules = rules();
        assertThat(TargetSearch.resourceHint(FixedPoint.parse("0.95"), rules))
                .isEqualTo(TargetSearch.ResourceHint.RICH);
        assertThat(TargetSearch.resourceHint(FixedPoint.parse("0.70"), rules))
                .isEqualTo(TargetSearch.ResourceHint.RICH);
        assertThat(TargetSearch.resourceHint(FixedPoint.parse("0.50"), rules))
                .isEqualTo(TargetSearch.ResourceHint.NORMAL);
        assertThat(TargetSearch.resourceHint(FixedPoint.parse("0.30"), rules))
                .isEqualTo(TargetSearch.ResourceHint.POOR);
        assertThat(TargetSearch.ResourceHint.values()).hasSize(3);
    }

    @Test
    @DisplayName("仓库溢出（比值 > 1.0）被截到 1.0，不会让一个提示字段打断整次搜索")
    void resourceFillAboveCapacityIsClamped() {
        TargetSearch.Candidate overflow = new TargetSearch.Candidate("rich", Coord.of(266, 256),
                SELF_POWER, false, null, NOW, FixedPoint.parse("3.0"), 0L);
        assertThat(overflow.resourceFillFixed()).isEqualTo(FixedPoint.SCALE);
        assertThat(TargetSearch.resourceHint(overflow.resourceFillFixed(), rules()))
                .isEqualTo(TargetSearch.ResourceHint.RICH);
    }

    // ---------- 确定性 ----------

    @Test
    @DisplayName("结果只取决于候选集合，不取决于遍历顺序：随机项按 playerId fork 子流")
    void resultIsIndependentOfPoolOrder() {
        TargetSearch.Rules rules = rules();
        List<TargetSearch.Candidate> pool = new ArrayList<>();
        Rng rng = Rng.of(31337L);
        for (int i = 0; i < 300; i++) {
            pool.add(new TargetSearch.Candidate("p" + i,
                    Coord.of(clamp(256 + (int) rng.range(-64, 64)), clamp(256 + (int) rng.range(-64, 64))),
                    rng.range(6000L, 16000L), false, null, NOW,
                    rng.nextFixed(0L, FixedPoint.SCALE), 0L));
        }
        List<String> first = idsOf(TargetSearch.search(self(), pool,
                new TargetSearch.Request(128, 20, rules.activeCutoff(NOW)), rules, band(), Rng.of(5L)));

        List<TargetSearch.Candidate> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, new java.util.Random(9L));
        List<String> second = idsOf(TargetSearch.search(self(), shuffled,
                new TargetSearch.Request(128, 20, rules.activeCutoff(NOW)), rules, band(), Rng.of(5L)));

        assertThat(second)
                .as("候选池顺序变了结果就变，意味着随机项在推进主流 —— 换成 MongoDB 之后"
                        + "遍历顺序不保证，「同一张地图给出同一批目标」会悄悄失效")
                .containsExactlyElementsOf(first);
    }

    @Test
    @DisplayName("得分相同时按 playerId 兜底排序，不会出现「刷新一下换一批人」")
    void tiesAreBrokenDeterministically() {
        TargetSearch.Rules rules = rules();
        List<TargetSearch.Candidate> pool = List.of(
                new TargetSearch.Candidate("zzz", Coord.of(260, 256), SELF_POWER, false, null,
                        NOW, FixedPoint.parse("0.5"), 0L),
                new TargetSearch.Candidate("aaa", Coord.of(260, 256), SELF_POWER, false, null,
                        NOW, FixedPoint.parse("0.5"), 0L),
                new TargetSearch.Candidate("mmm", Coord.of(260, 256), SELF_POWER, false, null,
                        NOW, FixedPoint.parse("0.5"), 0L));
        // 三个候选除 id 外完全相同，随机项也按 id fork，所以得分不同但可复现；
        // 关键是「同一份输入永远给出同一个顺序」
        List<String> first = idsOf(TargetSearch.search(self(), pool,
                new TargetSearch.Request(128, 20, rules.activeCutoff(NOW)), rules, band(), Rng.of(2L)));
        List<String> second = idsOf(TargetSearch.search(self(), pool,
                new TargetSearch.Request(128, 20, rules.activeCutoff(NOW)), rules, band(), Rng.of(2L)));
        assertThat(second).containsExactlyElementsOf(first);
        assertThat(first).containsExactlyInAnyOrder("aaa", "mmm", "zzz");
    }

    // ---------- 规则校验 ----------

    @Test
    @DisplayName("四项权重之和必须正好是 1.0，否则调一个权重的效果取决于其余三个的和")
    void rulesRejectUnbalancedWeights() {
        assertThatThrownBy(() -> new TargetSearch.Rules(
                128, 48, 48L * 3600_000L, 20, 50,
                FixedPoint.parse("0.35"), FixedPoint.parse("0.25"),
                FixedPoint.parse("0.25"), FixedPoint.parse("0.20"),
                FixedPoint.parse("0.25"), FixedPoint.parse("0.60"),
                FixedPoint.parse("0.70"), FixedPoint.parse("0.30"),
                FixedPoint.parse("0.71"), FixedPoint.parse("1.41")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("之和必须为 1.0");
    }

    @Test
    @DisplayName("默认半径必须落在 [1, maxRadius]：配错成 0 就是一次永远为空的搜索")
    void rulesRejectDefaultRadiusOutsideTheRange() {
        assertThatThrownBy(() -> new TargetSearch.Rules(
                128, 0, 48L * 3600_000L, 20, 50,
                FixedPoint.parse("0.35"), FixedPoint.parse("0.25"),
                FixedPoint.parse("0.25"), FixedPoint.parse("0.15"),
                FixedPoint.parse("0.25"), FixedPoint.parse("0.60"),
                FixedPoint.parse("0.70"), FixedPoint.parse("0.30"),
                FixedPoint.parse("0.71"), FixedPoint.parse("1.41")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("默认半径");
        assertThatThrownBy(() -> new TargetSearch.Rules(
                128, 129, 48L * 3600_000L, 20, 50,
                FixedPoint.parse("0.35"), FixedPoint.parse("0.25"),
                FixedPoint.parse("0.25"), FixedPoint.parse("0.15"),
                FixedPoint.parse("0.25"), FixedPoint.parse("0.60"),
                FixedPoint.parse("0.70"), FixedPoint.parse("0.30"),
                FixedPoint.parse("0.71"), FixedPoint.parse("1.41")))
                .as("默认值比上限还大不会报错给运营，只会被夹到上限 —— 于是「默认」永远是「最大」")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("默认半径");
    }

    @Test
    @DisplayName("同段位区间必须包含 1.0，活跃窗口必须为正，档位阈值必须递增")
    void rulesRejectDegenerateConfiguration() {
        assertThatThrownBy(() -> new TargetSearch.Rules(
                128, 48, 48L * 3600_000L, 20, 50,
                FixedPoint.parse("0.35"), FixedPoint.parse("0.25"),
                FixedPoint.parse("0.25"), FixedPoint.parse("0.15"),
                FixedPoint.parse("0.25"), FixedPoint.parse("0.60"),
                FixedPoint.parse("0.70"), FixedPoint.parse("0.30"),
                FixedPoint.parse("1.50"), FixedPoint.parse("2.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .as("同段位区间不含 1.0 的话，「势均力敌」永远不算同段位，验收 11 的占比会恒为 0")
                .hasMessageContaining("1.0");
        assertThatThrownBy(() -> new TargetSearch.Rules(
                128, 48, 0L, 20, 50,
                FixedPoint.parse("0.35"), FixedPoint.parse("0.25"),
                FixedPoint.parse("0.25"), FixedPoint.parse("0.15"),
                FixedPoint.parse("0.25"), FixedPoint.parse("0.60"),
                FixedPoint.parse("0.70"), FixedPoint.parse("0.30"),
                FixedPoint.parse("0.71"), FixedPoint.parse("1.41")))
                .isInstanceOf(IllegalArgumentException.class)
                .as("窗口为 0 会把所有候选判成不活跃，搜索永远返回空列表且没有任何异常")
                .hasMessageContaining("活跃窗口");
        assertThatThrownBy(() -> new TargetSearch.Rules(
                128, 48, 48L * 3600_000L, 50, 20,
                FixedPoint.parse("0.35"), FixedPoint.parse("0.25"),
                FixedPoint.parse("0.25"), FixedPoint.parse("0.15"),
                FixedPoint.parse("0.25"), FixedPoint.parse("0.60"),
                FixedPoint.parse("0.70"), FixedPoint.parse("0.30"),
                FixedPoint.parse("0.71"), FixedPoint.parse("1.41")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("返回数量");
    }

    @Test
    @DisplayName("同段位区间关于 1.0 对称：打 1.41 倍与被 0.71 倍打是同一件事")
    void peerBandIsSymmetricOnLogAxis() {
        TargetSearch.Rules rules = rules();
        assertThat(TargetSearch.isPeer(FixedPoint.parse("1.00"), rules)).isTrue();
        assertThat(TargetSearch.isPeer(FixedPoint.parse("1.41"), rules)).isTrue();
        assertThat(TargetSearch.isPeer(FixedPoint.parse("0.71"), rules)).isTrue();
        assertThat(TargetSearch.isPeer(FixedPoint.parse("1.42"), rules)).isFalse();
        assertThat(TargetSearch.isPeer(FixedPoint.parse("0.70"), rules)).isFalse();
        // 圈层两端都不算同段位
        assertThat(TargetSearch.isPeer(FixedPoint.parse("2.00"), rules)).isFalse();
        assertThat(TargetSearch.isPeer(FixedPoint.parse("0.50"), rules)).isFalse();
    }

    @Test
    @DisplayName("空池与 null 池都返回空列表，而不是抛异常")
    void emptyPoolYieldsEmptyResult() {
        TargetSearch.Rules rules = rules();
        TargetSearch.Request request = new TargetSearch.Request(128, 20, rules.activeCutoff(NOW));
        assertThat(TargetSearch.search(self(), List.of(), request, rules, band(), Rng.of(1L))).isEmpty();
        assertThat(TargetSearch.search(self(), null, request, rules, band(), Rng.of(1L))).isEmpty();
    }

    // ---------- 辅助 ----------

    private static List<String> idsOf(List<TargetSearch.Scored> scored) {
        List<String> ids = new ArrayList<>(scored.size());
        for (TargetSearch.Scored item : scored) {
            ids.add(item.candidate().playerId());
        }
        return ids;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(WORLD - 1, value));
    }
}
