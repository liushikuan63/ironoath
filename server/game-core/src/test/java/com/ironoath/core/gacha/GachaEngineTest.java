package com.ironoath.core.gacha;

import com.ironoath.common.num.FixedPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：抽卡引擎的机制验证（B06 验收 2 / 11，以及 UP 大保底与参数校验）。
 * 依赖：纯 JUnit，不需要容器、不需要配置表（铁律 2）。
 *
 * <p>验收 1（十万次模拟、综合概率与公示值误差 &lt; 0.3%）需要读真实的 gacha.json，
 * 而 game-core 不许依赖 game-config，所以那条放在 game-web 的
 * {@code GachaProbabilityAcceptanceTest} 里跑。本类用合成池验证机制本身。
 */
class GachaEngineTest {

    private static final Map<Tier, List<String>> HEROES = GachaEngine.heroes(
            List.of("hero_ssr_01", "hero_ssr_02"),
            List.of("hero_sr_01", "hero_sr_02"),
            List.of("hero_r_01", "hero_r_02"),
            List.of("hero_n_01", "hero_n_02"));

    /** 无 UP 的标准型池子：SSR 2%、SR 10%、R 30%、N 58%，保底 80 / 10。 */
    private static GachaEngine.Pool standardPool() {
        return new GachaEngine.Pool("pool_test",
                GachaEngine.chances(200L, 1000L, 3000L, 5800L),
                80L, 10L, HEROES, null, 0L, 0L);
    }

    /** 限定池：SSR 中 50% 为 UP，连续 2 次非 UP 后第 3 次 SSR 必为 UP。 */
    private static GachaEngine.Pool limitedPool() {
        return new GachaEngine.Pool("pool_limited",
                GachaEngine.chances(300L, 1200L, 3000L, 5500L),
                60L, 10L, HEROES, "hero_ssr_01", FixedPoint.parse("0.50"), 3L);
    }

    @Test
    @DisplayName("验收11：同 seed + 同 count + 同保底进度 ⇒ 逐条完全一致")
    void sameSeedProducesIdenticalResults() {
        GachaEngine.Pool pool = standardPool();
        GachaEngine.Batch a = GachaEngine.draw(pool, 20260906L, 10, GachaEngine.Counters.fresh());
        GachaEngine.Batch b = GachaEngine.draw(pool, 20260906L, 10, GachaEngine.Counters.fresh());
        assertThat(a.draws()).isEqualTo(b.draws());
        assertThat(a.counters()).isEqualTo(b.counters());
    }

    @Test
    @DisplayName("换 seed 结果就变：证明抽取真的走了随机而不是每次都返回同一份")
    void differentSeedProducesDifferentResults() {
        GachaEngine.Pool pool = standardPool();
        GachaEngine.Batch a = GachaEngine.draw(pool, 1L, 10, GachaEngine.Counters.fresh());
        GachaEngine.Batch b = GachaEngine.draw(pool, 2L, 10, GachaEngine.Counters.fresh());
        assertThat(a.draws()).isNotEqualTo(b.draws());
    }

    @Test
    @DisplayName("子流隔离：抽 10 次的前 3 条，与只抽 3 次的结果完全一致")
    void prefixIsStableAcrossBatchSize() {
        GachaEngine.Pool pool = standardPool();
        List<GachaEngine.Draw> ten = GachaEngine.draw(pool, 777L, 10, GachaEngine.Counters.fresh()).draws();
        List<GachaEngine.Draw> three = GachaEngine.draw(pool, 777L, 3, GachaEngine.Counters.fresh()).draws();
        assertThat(ten.subList(0, 3))
                .as("每抽用 fork(i) 取独立子流，所以批次长度不能影响前面各抽的结果")
                .isEqualTo(three);
    }

    @Test
    @DisplayName("验收2：SSR 计数到保底次数时必然出 SSR，且出完立刻清零")
    void ssrPityTriggersAndResets() {
        GachaEngine.Pool pool = standardPool();
        // 从「已经 79 抽没出 SSR」的状态开始，下一抽必须出 SSR
        GachaEngine.Counters atPity = new GachaEngine.Counters(79L, 0L, 0L);
        GachaEngine.Batch batch = GachaEngine.draw(pool, 42L, 1, atPity);

        assertThat(batch.draws()).hasSize(1);
        assertThat(batch.draws().get(0).tier()).isEqualTo(Tier.SSR);
        assertThat(batch.draws().get(0).pity()).as("保底触发必须标 isPity（合规字段）").isTrue();
        assertThat(batch.counters().ssr()).as("出 SSR 后计数必须清零").isZero();
        assertThat(batch.counters().sr()).as("SSR 也算「SR 及以上」，SR 计数一并清零").isZero();
    }

    @Test
    @DisplayName("验收2：保底在任何 seed 下都必然触发 —— 遍历 200 个 seed 各抽到第 80 抽")
    void ssrPityNeverFailsRegardlessOfSeed() {
        GachaEngine.Pool pool = standardPool();
        for (long seed = 0; seed < 200; seed++) {
            GachaEngine.Batch batch = GachaEngine.draw(pool, seed, 80, GachaEngine.Counters.fresh());
            boolean gotSsr = batch.draws().stream().anyMatch(d -> d.tier() == Tier.SSR);
            assertThat(gotSsr).as("seed=%d 抽满 80 次必须至少出一个 SSR", seed).isTrue();
        }
    }

    @Test
    @DisplayName("验收2：SR 保底把 R/N 升级为 SR，且不会凭空造出 SSR")
    void srPityUpgradesWithoutCreatingSsr() {
        // SSR 基础概率设成 0，这样任何 SSR 都只可能来自 SSR 保底；
        // 把 SSR 保底放到 80，只抽 10 次 ⇒ 全程不可能出 SSR
        GachaEngine.Pool pool = new GachaEngine.Pool("pool_sr_only",
                GachaEngine.chances(0L, 0L, 5000L, 5000L),
                80L, 10L, HEROES, null, 0L, 0L);

        GachaEngine.Batch batch = GachaEngine.draw(pool, 5L, 10, GachaEngine.Counters.fresh());
        assertThat(batch.draws()).noneMatch(d -> d.tier() == Tier.SSR);
        assertThat(batch.draws().get(9).tier())
                .as("前 9 抽都是 R/N，第 10 抽必须被 SR 保底升级")
                .isEqualTo(Tier.SR);
        assertThat(batch.draws().get(9).pity()).isTrue();
        assertThat(batch.counters().sr()).isZero();
    }

    @Test
    @DisplayName("计数规则：出 SR 只清 SR 计数，SSR 计数继续累加")
    void srHitDoesNotResetSsrCounter() {
        GachaEngine.Pool pool = standardPool();
        // 从「SSR 计数 50、SR 计数 9」开始：下一抽必被 SR 保底升级成 SR
        GachaEngine.Batch batch = GachaEngine.draw(pool, 9L, 1, new GachaEngine.Counters(50L, 9L, 0L));
        assertThat(batch.draws().get(0).tier()).isEqualTo(Tier.SR);
        assertThat(batch.counters().sr()).as("出 SR 清 SR 计数").isZero();
        assertThat(batch.counters().ssr()).as("出 SR 不能清 SSR 计数，否则保底永远到不了").isEqualTo(51L);
    }

    @Test
    @DisplayName("限定池：连续 2 次非 UP 的 SSR 之后，第 3 次 SSR 必为 UP（公示文案里的承诺）")
    void upGuaranteeFiresAfterTwoNonUpSsr() {
        GachaEngine.Pool pool = limitedPool();
        GachaEngine.Counters streak2 = new GachaEngine.Counters(59L, 0L, 2L);
        GachaEngine.Batch batch = GachaEngine.draw(pool, 123L, 1, streak2);

        assertThat(batch.draws().get(0).tier()).isEqualTo(Tier.SSR);
        assertThat(batch.draws().get(0).heroId())
                .as("已有 2 次非 UP 的 SSR，第 3 次必须是 UP 武将")
                .isEqualTo("hero_ssr_01");
        assertThat(batch.counters().nonUpStreak()).as("出 UP 后连击计数清零").isZero();
    }

    @Test
    @DisplayName("限定池：UP 占比生效 —— 大量 SSR 里 UP 与非 UP 的比例接近配置值")
    void upShareIsRespected() {
        GachaEngine.Pool pool = limitedPool();
        int up = 0;
        int total = 0;
        // 直接从「SSR 计数已到保底」的状态反复单抽，每次都会出 SSR，省掉大量无效抽取
        for (long seed = 0; seed < 4000; seed++) {
            GachaEngine.Batch batch = GachaEngine.draw(pool, seed, 1,
                    new GachaEngine.Counters(59L, 0L, 0L));
            GachaEngine.Draw draw = batch.draws().get(0);
            if (draw.tier() != Tier.SSR) {
                continue;
            }
            total++;
            if (draw.heroId().equals("hero_ssr_01")) {
                up++;
            }
        }
        assertThat(total).as("样本量必须足够").isGreaterThan(3000);
        double share = (double) up / total;
        assertThat(share).as("UP 占比配置为 50%，实测应接近").isBetween(0.45d, 0.55d);
    }

    @Test
    @DisplayName("同档内多名武将都会被抽到，且分布大致均匀（没有某个人被吃掉）")
    void heroesWithinTierAreReachable() {
        GachaEngine.Pool pool = standardPool();
        Map<String, Integer> hits = new HashMap<>();
        for (long seed = 0; seed < 3000; seed++) {
            GachaEngine.Batch batch = GachaEngine.draw(pool, seed, 1,
                    new GachaEngine.Counters(79L, 0L, 0L));
            hits.merge(batch.draws().get(0).heroId(), 1, Integer::sum);
        }
        assertThat(hits).as("SSR 档的两名武将都必须能抽到").containsKeys("hero_ssr_01", "hero_ssr_02");
        assertThat(hits.get("hero_ssr_01")).isBetween(1200, 1800);
        assertThat(hits.get("hero_ssr_02")).isBetween(1200, 1800);
    }

    @Test
    @DisplayName("四档基础概率之和不为 1 时在构造期就拒绝（差 1 也会把综合概率推出容差）")
    void chancesMustSumToOne() {
        assertThatThrownBy(() -> new GachaEngine.Pool("bad",
                GachaEngine.chances(200L, 1000L, 3000L, 5799L),
                80L, 10L, HEROES, null, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100%");
        assertThatThrownBy(() -> new GachaEngine.Pool("bad",
                GachaEngine.chances(200L, 1000L, 3000L, 5801L),
                80L, 10L, HEROES, null, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("别把公示值填进来");
    }

    @Test
    @DisplayName("配置不自洽时在构造期就拒绝：缺档、空名单、SR 保底严于 SSR、UP 武将不在 SSR 名单")
    void poolRejectsInconsistentConfig() {
        assertThatThrownBy(() -> new GachaEngine.Pool("bad", null, 80L, 10L, HEROES, null, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GachaEngine.Pool("bad",
                GachaEngine.chances(200L, 1000L, 3000L, 5800L), 80L, 10L,
                GachaEngine.heroes(List.of(), List.of("a"), List.of("b"), List.of("c")),
                null, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("没有任何武将");
        assertThatThrownBy(() -> new GachaEngine.Pool("bad",
                GachaEngine.chances(200L, 1000L, 3000L, 5800L), 10L, 80L, HEROES, null, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SR 保底不得严于");
        // UP 机制已泛化到任意档位，所以 hero_r_01 是合法的 UP 武将（它在 R 档名单里）。
        // 要触发这条校验必须用一个不在任何档位名单里的 id
        assertThatThrownBy(() -> new GachaEngine.Pool("bad",
                GachaEngine.chances(200L, 1000L, 3000L, 5800L), 80L, 10L, HEROES,
                "hero_not_exists", FixedPoint.parse("0.50"), 3L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UP 永远不会兑现");
        assertThatThrownBy(() -> new GachaEngine.Pool("bad",
                GachaEngine.chances(200L, 1000L, 3000L, 5800L), 80L, 10L, HEROES,
                null, FixedPoint.parse("0.50"), 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("没有 upHeroId");
    }

    @Test
    @DisplayName("抽取次数与保底计数的非法入参在调用点就拒绝")
    void rejectsInvalidDrawArguments() {
        GachaEngine.Pool pool = standardPool();
        assertThatThrownBy(() -> GachaEngine.draw(pool, 1L, 0, GachaEngine.Counters.fresh()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("必须为正");
        assertThatThrownBy(() -> GachaEngine.draw(pool, 1L, 1, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Counters.fresh");
        assertThatThrownBy(() -> new GachaEngine.Counters(-1L, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为负");
    }
}
