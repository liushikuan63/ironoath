package com.ironoath.web;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.GachaCfg;
import com.ironoath.core.gacha.GachaEngine;
import com.ironoath.core.gacha.Tier;
import com.ironoath.web.service.GachaPoolFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：B06 验收 1 —— 抽卡概率实测（十万次模拟，综合概率与公示值误差 &lt; 0.3%），
 * 外加 gacha 表的跨列一致性。
 * 依赖：JUnit + AssertJ + game-config + game-core；<b>不需要 Spring 容器</b>，
 * 因为十万次模拟跑在纯引擎上，起容器只会让它慢十倍。
 *
 * <p><b>为什么这条测试是整个 B06 最重要的一条</b>：抽卡是本项目深度最大的付费点，
 * 而「公示概率」是合规硬要求（B06 §6：不做完不许上线付费）。
 * 保底机制会凭空多造出一部分 SSR/SR，所以「每抽基础概率」必须低于「公示概率」，
 * 两者由 tools/gacha-calibrate 反解校准。这条测试就是校准结果的验收 ——
 * 它跑的是<b>真实配置</b>与<b>真实引擎</b>，任何一边改了而另一边没跟上，这里就会红。
 */
class GachaProbabilityAcceptanceTest {

    /** B06 验收 1 的容差：0.3%（绝对值）。 */
    private static final double TOLERANCE = 0.003d;

    /**
     * 模拟次数。
     *
     * <p>十万次是 B06 验收 1 明写的量级。它也是必需的：
     * SSR 档标准概率 2%，十万次的标准误约 0.044%，容差 0.3% 相当于约 7 个标准误，
     * 误报概率可以忽略。若只跑一万次，标准误升到 0.14%，容差只剩 2 个标准误，
     * 这条测试就会时不时地随机变红 —— 而「偶发变红的测试」最终一定会被人跳过。
     */
    private static final int SIMULATION_DRAWS = 100_000;

    private static ConfigRegistry registry;
    private static GachaPoolFactory factory;

    @BeforeAll
    static void load() {
        registry = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        factory = new GachaPoolFactory(registry);
    }

    @Test
    @DisplayName("验收1：三个卡池各模拟十万次，四档实际概率与公示值误差都 < 0.3%")
    void simulatedRatesMatchDisclosedRates() {
        for (GachaCfg pool : registry.all(GachaCfg.class)) {
            Map<Tier, Long> hits = simulate(pool, SIMULATION_DRAWS);
            for (Tier tier : Tier.values()) {
                double disclosed = GachaPoolFactory.disclosedRate(pool, tier) / (double) FixedPoint.SCALE;
                double actual = hits.get(tier) / (double) SIMULATION_DRAWS;
                assertThat(Math.abs(actual - disclosed))
                        .as("卡池 %s 的 %s 档：公示 %.4f%%，实测 %.4f%%（十万次）",
                                pool.id(), tier, disclosed * 100, actual * 100)
                        .isLessThan(TOLERANCE);
            }
        }
    }

    @Test
    @DisplayName("公示概率与基础概率是两组不同的数：基础概率必须严格低于公示概率（保底会补上差额）")
    void baseRatesAreBelowDisclosedRates() {
        for (GachaCfg pool : registry.all(GachaCfg.class)) {
            for (Tier tier : new Tier[]{Tier.SSR, Tier.SR}) {
                long disclosed = GachaPoolFactory.disclosedRate(pool, tier);
                long base = GachaPoolFactory.baseRate(pool, tier);
                assertThat(base)
                        .as("卡池 %s 的 %s 档：基础概率(%d)必须低于公示概率(%d)，"
                                        + "否则加上保底之后综合概率就会超过公示值（公示不实）",
                                pool.id(), tier, base, disclosed)
                        .isLessThan(disclosed);
            }
            // R/N 两档没有自己的保底，但它们会被 SR 保底「偷走」一部分，
            // 所以基础概率反而要高于公示概率来补偿
            for (Tier tier : new Tier[]{Tier.R, Tier.N}) {
                assertThat(GachaPoolFactory.baseRate(pool, tier))
                        .as("卡池 %s 的 %s 档：基础概率必须高于公示概率，"
                                + "补偿被 SR 保底升级走的那部分", pool.id(), tier)
                        .isGreaterThan(GachaPoolFactory.disclosedRate(pool, tier));
            }
        }
    }

    @Test
    @DisplayName("两组概率各自四档之和都必须恰为 100%（差 1 个定点单位也会把综合概率推出容差）")
    void bothRateSetsSumToExactlyOneHundredPercent() {
        for (GachaCfg pool : registry.all(GachaCfg.class)) {
            long disclosedSum = 0L;
            long baseSum = 0L;
            for (Tier tier : Tier.values()) {
                disclosedSum += GachaPoolFactory.disclosedRate(pool, tier);
                baseSum += GachaPoolFactory.baseRate(pool, tier);
            }
            assertThat(disclosedSum).as("卡池 %s 的公示概率之和", pool.id())
                    .isEqualTo(FixedPoint.SCALE);
            assertThat(baseSum).as("卡池 %s 的基础概率之和", pool.id())
                    .isEqualTo(FixedPoint.SCALE);
        }
    }

    @Test
    @DisplayName("验收2：从空保底进度连抽 ssrPity 次，必然至少出一个 SSR，且计数归零")
    void pityAlwaysFiresWithinConfiguredCount() {
        for (GachaCfg pool : registry.all(GachaCfg.class)) {
            GachaEngine.Batch batch = GachaEngine.draw(
                    factory.pool(pool), 20260906L, (int) pool.ssrPity(), GachaEngine.Counters.fresh());
            assertThat(batch.draws())
                    .as("卡池 %s 抽满 %d 次必须至少出一个 SSR（公示文案的承诺）",
                            pool.id(), pool.ssrPity())
                    .anyMatch(d -> d.tier() == Tier.SSR);
            assertThat(batch.counters().ssr())
                    .as("SSR 计数永远不可能达到保底阈值 %d：一旦达到，保底就该在那一抽触发并清零。"
                            + "计数停在阈值上说明保底没生效", pool.ssrPity())
                    .isLessThan(pool.ssrPity());
        }
    }

    @Test
    @DisplayName("验收11：真实配置下，同 seed + 同 count 两次十连逐条一致")
    void realPoolIsReproducible() {
        GachaCfg pool = registry.get(GachaCfg.class, "gacha_pool_standard");
        GachaEngine.Batch a = GachaEngine.draw(factory.pool(pool), 424242L, 10,
                GachaEngine.Counters.fresh());
        GachaEngine.Batch b = GachaEngine.draw(factory.pool(pool), 424242L, 10,
                GachaEngine.Counters.fresh());
        assertThat(a.draws()).isEqualTo(b.draws());
        assertThat(a.counters()).isEqualTo(b.counters());
    }

    @Test
    @DisplayName("配置一致性：costItemId 与 costResource 恰好填一个（校验器表达不了「二选一」）")
    void costSourceIsExactlyOneOfItemOrResource() {
        for (GachaCfg pool : registry.all(GachaCfg.class)) {
            boolean hasItem = pool.costItemId() != null;
            boolean hasResource = pool.costResource() != null;
            assertThat(hasItem ^ hasResource)
                    .as("卡池 %s 必须恰好指定一种计价方式：costItemId=%s, costResource=%s。"
                                    + "两个都填会让实现去猜扣哪个，都不填则抽卡免费",
                            pool.id(), pool.costItemId(), pool.costResource())
                    .isTrue();
            assertThat(pool.costCount()).as("卡池 %s 的单抽消耗必须为正", pool.id()).isPositive();
        }
    }

    @Test
    @DisplayName("配置一致性：UP 武将必须真实存在且落在本池会抽到的档位里（否则 UP 永不兑现且不报错）")
    void upHeroIsReachableInItsOwnTier() {
        for (GachaCfg pool : registry.all(GachaCfg.class)) {
            if (pool.upHeroId() == null) {
                // 标准池是长期主池，不该有 UP：UP 是限定池的溢价结构（概率更好但目标更窄），
                // 主池带上 UP 会让「非 UP 的 SSR」变成次等品，削弱整个 SSR 档的价值感
                assertThat(pool.poolType())
                        .as("长期主池 %s 不该有 UP 武将", pool.id())
                        .isEqualTo(GachaCfg.PoolType.STANDARD);
                continue;
            }
            var hero = registry.get(com.ironoath.config.cfg.HeroCfg.class, pool.upHeroId());
            GachaEngine.Pool enginePool = factory.pool(pool);
            Tier upTier = enginePool.upTier();
            assertThat(upTier).as("卡池 %s 的 UP 武将 %s 必须落在某一档里", pool.id(), pool.upHeroId())
                    .isNotNull();
            assertThat(enginePool.heroesOf(upTier)).contains(pool.upHeroId());
            assertThat(upTier.name())
                    .as("卡池 %s 的 UP 档位必须与 UP 武将 %s 的稀有度一致", pool.id(), hero.id())
                    .isEqualTo(hero.rarity().name());
        }
    }

    @Test
    @DisplayName("每个稀有度档都必须有武将可选，否则引擎在构造期就会拒绝这个池子")
    void everyTierHasHeroes() {
        Map<Tier, Integer> counts = new EnumMap<>(Tier.class);
        factory.heroesByTier().forEach((tier, list) -> counts.put(tier, list.size()));
        for (Tier tier : Tier.values()) {
            assertThat(counts.get(tier)).as("%s 档必须有武将", tier).isPositive();
        }
    }

    /** 用真实配置模拟 n 次单抽，返回四档命中数。每次都从空保底进度开始（独立同分布）。 */
    private static Map<Tier, Long> simulate(GachaCfg pool, int draws) {
        GachaEngine.Pool enginePool = factory.pool(pool);
        Map<Tier, Long> hits = new EnumMap<>(Tier.class);
        for (Tier tier : Tier.values()) {
            hits.put(tier, 0L);
        }
        // 每次都用不同的 seed 抽 1 次，等价于一个玩家从零开始连抽 draws 次；
        // 但为了让保底真正参与统计，这里改成「一条长链」：连续抽，保底进度自然累积
        GachaEngine.Counters counters = GachaEngine.Counters.fresh();
        int batch = 100;
        int done = 0;
        long seed = 20260906L;
        while (done < draws) {
            int n = Math.min(batch, draws - done);
            GachaEngine.Batch result = GachaEngine.draw(enginePool, seed + done, n, counters);
            counters = result.counters();
            for (GachaEngine.Draw draw : result.draws()) {
                hits.merge(draw.tier(), 1L, Long::sum);
            }
            done += n;
        }
        return hits;
    }
}
