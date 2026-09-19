package com.ironoath.web;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.GachaCfg;
import com.ironoath.config.cfg.HeroCfg;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.gacha.GachaLogStore;
import com.ironoath.core.gacha.GachaStateRepository;
import com.ironoath.core.gacha.Tier;
import com.ironoath.core.hero.HeroInstance;
import com.ironoath.core.hero.HeroRoster;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.dto.generated.AttrTriple;
import com.ironoath.web.dto.generated.BonusZone;
import com.ironoath.web.dto.generated.ComposeCandidate;
import com.ironoath.web.dto.generated.EquipInstanceView;
import com.ironoath.web.dto.generated.EquipSlot;
import com.ironoath.web.dto.generated.FragmentView;
import com.ironoath.web.dto.generated.GachaDrawReq;
import com.ironoath.web.dto.generated.GachaDrawResp;
import com.ironoath.web.dto.generated.GachaProbItem;
import com.ironoath.web.dto.generated.GachaProbResp;
import com.ironoath.web.dto.generated.HeroEquipReq;
import com.ironoath.web.dto.generated.HeroGrowResp;
import com.ironoath.web.dto.generated.HeroIdReq;
import com.ironoath.web.dto.generated.HeroLevelUpReq;
import com.ironoath.web.dto.generated.HeroListResp;
import com.ironoath.web.dto.generated.HeroView;
import com.ironoath.web.dto.generated.ItemCount;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.SetLineupReq;
import com.ironoath.web.dto.generated.SetLineupResp;
import com.ironoath.web.dto.generated.WornEquip;
import com.ironoath.web.equip.EquipAppService;
import com.ironoath.web.reward.ServerSeedSource;
import com.ironoath.web.service.GachaAppService;
import com.ironoath.web.service.GachaPoolFactory;
import com.ironoath.web.service.HeroAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryGachaLogStore;
import com.ironoath.web.store.memory.InMemoryGachaStateStore;
import com.ironoath.web.store.memory.InMemoryHeroStore;
import com.ironoath.web.store.memory.InMemoryInventoryStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：B06 武将与抽卡的端到端验证 —— 验收 3 / 4 / 5 / 6 / 8 / 9 / 10 / 11。
 * 依赖：Spring Boot Test，test profile（内存存储 + JVM 内锁）。
 *
 * <p>验收 1（十万次模拟概率）与验收 2（保底机制）在 {@link GachaProbabilityAcceptanceTest}
 * 与 game-core 的 {@code GachaEngineTest} 里，那两条不需要容器，跑在纯引擎上更快也更准。
 * 本类要验的是「配置 → 引擎 → 存档 → 响应」这条链真的接上了。
 */
@SpringBootTest
@ActiveProfiles("test")
class HeroGachaEndpointTest {

    private static final AtomicLong SEED = new AtomicLong(20260906L);

    @TestConfiguration
    static class FixedSeedConfig {
        @Bean
        @Primary
        ServerSeedSource testSeedSource() {
            return SEED::get;
        }
    }

    @Autowired private HeroAppService heroAppService;
    @Autowired private EquipAppService equipAppService;
    @Autowired private GachaAppService gachaAppService;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private ConfigRegistry configs;
    @Autowired private PlayerRepository players;
    @Autowired private InventoryRepository inventories;
    @Autowired private HeroRepository heroes;
    @Autowired private com.ironoath.core.reward.RewardPorts.Bag bagPort;
    @Autowired private com.ironoath.web.service.HeroStatsService heroStats;
    @Autowired private com.ironoath.web.hero.EquipLedgers equipLedgers;
    @Autowired private GachaStateRepository gachaStates;
    @Autowired private GachaLogStore gachaLogs;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryInventoryStore) inventories).clear();
        ((InMemoryHeroStore) heroes).clear();
        ((InMemoryGachaStateStore) gachaStates).clear();
        ((InMemoryGachaLogStore) gachaLogs).clear();
        SEED.set(20260906L);
    }

    // ---------- 验收 3：概率面板与配置同源 ----------

    @Test
    @DisplayName("验收3：概率面板的四档数值与 gacha 表逐字一致，且逐武将概率加总恰等于该档概率")
    void probabilityPanelMatchesConfigExactly() {
        for (GachaCfg pool : configs.all(GachaCfg.class)) {
            GachaProbResp resp = gachaAppService.probability(pool.id());

            assertThat(resp.poolId()).isEqualTo(pool.id());
            assertThat(resp.disclosureText())
                    .as("公示文案必须原样下发，客户端不得自行改写（gacha 表已明写不得删减或折叠）")
                    .isEqualTo(pool.disclosureText());
            assertThat(resp.pityRule().ssrPity()).isEqualTo(pool.ssrPity());
            assertThat(resp.pityRule().srPity()).isEqualTo(pool.srPity());
            assertThat(resp.costCount()).isEqualTo(pool.costCount());

            // 四档公示概率逐项对齐配置表
            for (var tierRate : resp.tierRates()) {
                Tier tier = Tier.valueOf(tierRate.rarity().name());
                assertThat(tierRate.rateFixed())
                        .as("卡池 %s 的 %s 档公示概率", pool.id(), tier)
                        .isEqualTo(GachaPoolFactory.disclosedRate(pool, tier));
            }
            long tierSum = resp.tierRates().stream().mapToLong(t -> t.rateFixed()).sum();
            assertThat(tierSum).as("四档公示概率之和必须恰为 100%").isEqualTo(10_000L);

            // 逐武将概率按档加总，必须恰好等于该档公示概率（差 1 个定点单位就是公示不实）
            for (Tier tier : Tier.values()) {
                long expected = GachaPoolFactory.disclosedRate(pool, tier);
                long actual = 0L;
                int rows = 0;
                for (GachaProbItem item : resp.items()) {
                    if (Tier.valueOf(item.rarity().name()) == tier) {
                        actual += item.rateFixed();
                        rows++;
                    }
                }
                assertThat(rows).as("卡池 %s 的 %s 档必须有武将列出来", pool.id(), tier).isPositive();
                assertThat(actual)
                        .as("卡池 %s 的 %s 档：逐武将概率之和必须恰等于档位公示概率", pool.id(), tier)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("限定池的 UP 武将在面板上标了 isUp，且它的概率就是该档的 UP 占比")
    void upHeroIsMarkedInProbabilityPanel() {
        GachaProbResp resp = gachaAppService.probability("gacha_pool_limited_ssr01");
        List<GachaProbItem> ups = resp.items().stream().filter(GachaProbItem::isUp).toList();
        assertThat(ups).as("限定池必须有一个 UP 武将").hasSize(1);
        assertThat(ups.get(0).heroId()).isEqualTo("hero_ssr_01");
        long ssrTier = resp.tierRates().stream()
                .filter(t -> t.rarity().name().equals("SSR")).findFirst().orElseThrow().rateFixed();
        assertThat(ups.get(0).rateFixed()).as("UP 占 SSR 档的 50%").isEqualTo(ssrTier / 2);
    }

    // ---------- 抽卡链路 ----------

    @Test
    @DisplayName("验收11：同 seed 两次十连逐条一致，且种子随响应下发")
    void drawIsReproducibleBySeed() {
        String a = newPlayer();
        String b = newPlayer();
        giveItems(a, "item_chest_hero", 20L);
        giveItems(b, "item_chest_hero", 20L);
        // 两个账号各抽一次十连，用同一个 seed，结果必须逐条一致
        SEED.set(31337L);

        GachaDrawResp first = gachaAppService.draw(a,
                new GachaDrawReq(newRequestId(), "gacha_pool_limited_ssr01", 10));
        GachaDrawResp second = gachaAppService.draw(b,
                new GachaDrawReq(newRequestId(), "gacha_pool_limited_ssr01", 10));

        assertThat(first.seed()).isEqualTo(31337L);
        assertThat(second.seed()).isEqualTo(31337L);
        assertThat(first.results()).isEqualTo(second.results());
    }

    @Test
    @DisplayName("验收4：每次抽卡都写日志，含 playerId/时间/池子/结果/isPity/seed，且可按 90 天窗口查询")
    void drawWritesCompleteComplianceLog() {
        String playerId = newPlayer();
        giveItems(playerId, "item_chest_hero", 10L);
        String requestId = newRequestId();

        gachaAppService.draw(playerId, new GachaDrawReq(requestId, "gacha_pool_limited_ssr01", 10));

        long retentionDays = configs.longParam("GACHA_LOG_RETENTION_DAYS");
        assertThat(retentionDays).as("B06 §6 明写保留 90 天，这是合规下限").isEqualTo(90L);
        long since = System.currentTimeMillis() - retentionDays * 86_400_000L;
        List<GachaLogStore.Entry> logs = gachaLogs.query(playerId, since);

        assertThat(logs).as("一次十连必须留下 10 条日志").hasSize(10);
        for (int i = 0; i < logs.size(); i++) {
            GachaLogStore.Entry entry = logs.get(i);
            assertThat(entry.playerId()).isEqualTo(playerId);
            assertThat(entry.poolId()).isEqualTo("gacha_pool_limited_ssr01");
            assertThat(entry.requestId()).as("同一次十连的 10 条记录必须能用 requestId 关联")
                    .isEqualTo(requestId);
            assertThat(entry.drawnAt()).isPositive();
            assertThat(entry.drawIndex()).isEqualTo(i);
            assertThat(entry.heroId()).isNotBlank();
            assertThat(entry.tier()).isNotNull();
            // isPity 是 B06 禁止项点名两次的合规字段：监管要能区分「正常抽到」与「保底触发」
            assertThat(entry.isPity()).isIn(true, false);
            assertThat(entry.seed()).isEqualTo(logs.get(0).seed());
        }
        // 超出保留窗口的日志查不到
        assertThat(gachaLogs.query(playerId, System.currentTimeMillis() + 86_400_000L)).isEmpty();
    }

    @Test
    @DisplayName("保底进度按卡池隔离持久化：抽完限定池不会影响标准池的计数")
    void pityProgressIsPersistedPerPool() {
        String playerId = newPlayer();
        giveItems(playerId, "item_chest_hero", 10L);

        GachaDrawResp resp = gachaAppService.draw(playerId,
                new GachaDrawReq(newRequestId(), "gacha_pool_limited_ssr01", 10));

        var limited = gachaStates.find(playerId, "gacha_pool_limited_ssr01").orElseThrow();
        assertThat(limited.lifetimeDraws()).isEqualTo(10L);
        assertThat(limited.ssrCounter()).isEqualTo(resp.ssrPityCounter());
        assertThat(gachaStates.find(playerId, "gacha_pool_standard"))
                .as("没抽过的池子不该有进度记录").isEmpty();
    }

    @Test
    @DisplayName("新手池终身限抽 1 次：第二次十连必须被拒绝，且不能先扣费")
    void newbiePoolLifetimeLimitIsEnforced() {
        String playerId = newPlayer();
        giveItems(playerId, "item_gold_1000", 1L);
        // 新手池用 GOLD 计价、costCount=300，先给足金币
        grantGold(playerId, 1000L);

        gachaAppService.draw(playerId, new GachaDrawReq(newRequestId(), "gacha_pool_newbie", 1));
        long goldBefore = players.findByPlayerId(playerId).orElseThrow().resource("GOLD").current();

        assertThatThrownBy(() -> gachaAppService.draw(playerId,
                new GachaDrawReq(newRequestId(), "gacha_pool_newbie", 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("限抽")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
        assertThat(players.findByPlayerId(playerId).orElseThrow().resource("GOLD").current())
                .as("被拒绝时不能扣费").isEqualTo(goldBefore);
    }

    @Test
    @DisplayName("count 只能是 1 或 10；余额不足时明确拒绝且一个都不扣")
    void drawValidatesCountAndBalance() {
        String playerId = newPlayer();
        giveItems(playerId, "item_chest_hero", 3L);

        assertThatThrownBy(() -> gachaAppService.draw(playerId,
                new GachaDrawReq(newRequestId(), "gacha_pool_limited_ssr01", 5)))
                .isInstanceOf(BizException.class).hasMessageContaining("单抽或十连");

        assertThatThrownBy(() -> gachaAppService.draw(playerId,
                new GachaDrawReq(newRequestId(), "gacha_pool_limited_ssr01", 10)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ITEM_NOT_ENOUGH);
        assertThat(countOf(playerId, "item_chest_hero")).as("被拒绝时一个都不能扣").isEqualTo(3L);
    }

    @Test
    @DisplayName("重复武将按 hero_rarity.dupFragment 转成碎片道具进背包，碎片是道具不是第二套余额")
    void duplicateHeroConvertsToFragments() {
        String playerId = newPlayer();
        // 要连抽两次十连，先给够 20 个箱子
        giveItems(playerId, "item_chest_hero", 20L);
        // 先让限定池的 UP 武将进队，之后每次抽到它都是重复
        GachaDrawResp first = gachaAppService.draw(playerId,
                new GachaDrawReq(newRequestId(), "gacha_pool_limited_ssr01", 10));
        long fragmentsFromFirst = first.fragmentsAwarded();

        GachaDrawResp second = gachaAppService.draw(playerId,
                new GachaDrawReq(newRequestId(), "gacha_pool_limited_ssr01", 10));

        long expected = second.results().stream()
                .filter(r -> !r.isNew())
                .mapToLong(r -> configs.get(com.ironoath.config.cfg.HeroRarityCfg.class,
                        r.rarity().name()).dupFragment())
                .sum();
        assertThat(second.fragmentsAwarded()).isEqualTo(expected);
        assertThat(second.results()).allSatisfy(r -> {
            if (r.isNew()) {
                assertThat(r.fragments()).as("首次获得不产生碎片").isZero();
            } else {
                assertThat(r.fragments()).isEqualTo(
                        configs.get(com.ironoath.config.cfg.HeroRarityCfg.class, r.rarity().name())
                                .dupFragment());
            }
        });
        // 碎片必须真的落到背包里对应稀有度的道具上，而不是只出现在响应的数字里
        long bagFragments = 0L;
        for (var f : heroAppService.list(playerId).fragments()) {
            assertThat(f.count()).as("%s 的余额必须与背包一致", f.itemId())
                    .isEqualTo(countOf(playerId, f.itemId()));
            // 名字随行下发（#255/#268/#278 同族第四处）：这一行含余数为 0 的档，
            // 背包只列余数大于 0 的行 —— 客户端 join 不到，所以必须服务端给
            assertThat(f.name()).as("%s 必须带着 item 表里的中文名", f.itemId())
                    .isEqualTo(configs.get(com.ironoath.config.cfg.ItemCfg.class, f.itemId()).name());
            assertThat(f.name()).doesNotStartWith("item_");
            bagFragments += f.count();
        }
        assertThat(bagFragments)
                .as("两次十连里凡是重复的武将都该转成碎片，背包里必须真的有")
                .isEqualTo(fragmentsFromFirst + second.fragmentsAwarded());
        assertThat(heroAppService.list(playerId).fragments())
                .as("武将总览要能读到各稀有度的碎片余额")
                .anySatisfy(f -> assertThat(f.itemId()).startsWith("item_mat_hero_frag_"));
    }

    // ---------- 养成五条线 ----------

    @Test
    @DisplayName("验收6：喂经验书后武将属性逐维等于十进制手算值（误差 0）")
    void attributesMatchHandCalculationAfterLevelUp() {
        String playerId = newPlayerWithHero("hero_ssr_01");
        giveItems(playerId, "item_hero_exp_s", 1L);
        HeroView before = heroOf(heroAppService.list(playerId), "hero_ssr_01");
        assertThat(before.level()).isEqualTo(1);
        assertAttrsMatchHandCalc(before, 100L, 95L, 90L, "1.20", 1, 1, 0);

        // 小经验书 500 经验：1→2 需 20、2→3 需 22 …… 一本就够连升数级
        HeroGrowResp resp = heroAppService.levelUp(playerId, new HeroLevelUpReq(
                newRequestId(), "hero_ssr_01",
                List.of(new ItemCount("item_hero_exp_s", 1L))));

        assertThat(resp.hero().level()).as("500 经验应当连升多级，而不是只升 1 级").isGreaterThan(2);
        assertThat(resp.consumed()).containsExactly(new ItemCount("item_hero_exp_s", 1L));
        assertThat(countOf(playerId, "item_hero_exp_s")).isZero();
        assertAttrsMatchHandCalc(resp.hero(), 100L, 95L, 90L, "1.20",
                resp.hero().level(), resp.hero().star(), resp.hero().awaken());
        // 剩余经验必须留在条里，不能凭空消失
        assertThat(resp.hero().exp()).isGreaterThanOrEqualTo(0L);
        assertThat(resp.hero().expToNext()).isPositive();
    }

    @Test
    @DisplayName("升星消耗该稀有度的碎片，碎片不足时整体拒绝且不改变星级")
    void starUpConsumesRarityFragments() {
        String playerId = newPlayerWithHero("hero_ssr_01");
        long need = configs.get(com.ironoath.config.cfg.HeroRarityCfg.class, "SSR").starUpFragment();

        assertThatThrownBy(() -> heroAppService.starUp(playerId,
                new HeroIdReq(newRequestId(), "hero_ssr_01")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ITEM_NOT_ENOUGH);
        assertThat(heroOf(heroAppService.list(playerId), "hero_ssr_01").star()).isEqualTo(1);

        giveItems(playerId, "item_mat_hero_frag_ssr", need);
        HeroGrowResp resp = heroAppService.starUp(playerId,
                new HeroIdReq(newRequestId(), "hero_ssr_01"));
        assertThat(resp.hero().star()).isEqualTo(2);
        assertThat(countOf(playerId, "item_mat_hero_frag_ssr")).isZero();
        assertAttrsMatchHandCalc(resp.hero(), 100L, 95L, 90L, "1.20",
                resp.hero().level(), 2, 0);
    }

    @Test
    @DisplayName("碎片合成：凑够 composeFragment 就能获得武将，已拥有时拒绝（否则能无限刷）")
    void composeConsumesFragmentsAndRejectsDuplicates() {
        String playerId = newPlayer();
        long need = configs.get(com.ironoath.config.cfg.HeroRarityCfg.class, "SR").composeFragment();
        giveItems(playerId, "item_mat_hero_frag_sr", need);

        HeroGrowResp resp = heroAppService.compose(playerId,
                new HeroIdReq(newRequestId(), "hero_sr_01"));
        assertThat(resp.hero().heroId()).isEqualTo("hero_sr_01");
        assertThat(resp.hero().level()).isEqualTo(1);
        assertThat(countOf(playerId, "item_mat_hero_frag_sr")).isZero();

        giveItems(playerId, "item_mat_hero_frag_sr", need);
        assertThatThrownBy(() -> heroAppService.compose(playerId,
                new HeroIdReq(newRequestId(), "hero_sr_01")))
                .isInstanceOf(BizException.class)
                .as("已拥有还能合成，玩家就能靠反复合成把碎片刷成无限武将")
                .hasMessageContaining("已拥有");
        assertThat(countOf(playerId, "item_mat_hero_frag_sr"))
                .as("被拒绝时碎片不能被扣").isEqualTo(need);
    }

    @Test
    @DisplayName("碎片行带合成门槛与「还没拥有」的候选：客户端不抄 hero / hero_rarity 表也能说出还差几片")
    void fragmentRowsCarryComposeThresholdAndUnownedCandidates() {
        String playerId = newPlayerWithHero("hero_sr_01");
        List<FragmentView> rows = heroAppService.list(playerId).fragments();

        // 每一档的门槛都照 hero_rarity 那一列，且候选都落在自己那一档里
        for (FragmentView f : rows) {
            String rarity = configs.get(ItemCfg.class, f.itemId()).rarity().name();
            assertThat(f.composeFragment()).as("%s 的门槛取自 hero_rarity 的 %s 行", f.itemId(), rarity)
                    .isEqualTo(configs.get(com.ironoath.config.cfg.HeroRarityCfg.class, rarity)
                            .composeFragment());
            assertThat(f.candidates()).as("%s 的候选必须是同一档的武将", f.itemId())
                    .allSatisfy(c -> assertThat(configs.get(HeroCfg.class, c.heroId()).rarity().name())
                            .isEqualTo(rarity));
        }

        FragmentView sr = rows.stream()
                .filter(f -> f.itemId().equals("item_mat_hero_frag_sr"))
                .findFirst().orElseThrow();
        // 已拥有的那名不进候选：/hero/compose 对已拥有直接拒绝，列出来就是让玩家点一行注定失败的武将
        assertThat(sr.candidates()).extracting(ComposeCandidate::heroId).doesNotContain("hero_sr_01");
        assertThat(sr.candidates()).extracting(ComposeCandidate::heroId)
                .as("候选＝hero 表这一档的全部减去已拥有的，顺序照表")
                .containsExactlyElementsOf(configs.all(HeroCfg.class).stream()
                        .filter(h -> h.rarity() == HeroCfg.Rarity.SR && !h.id().equals("hero_sr_01"))
                        .map(HeroCfg::id).toList());
        for (ComposeCandidate c : sr.candidates()) {
            assertThat(c.name()).as("候选行要带玩家读得出的中文名（#255/#268/#278/#281 同族）")
                    .doesNotStartWith("hero_").isNotEqualTo(c.heroId());
        }

        // 真的合成一名之后：那一行少一个候选，而扣掉的碎片正好等于随行下发的那个门槛
        long before = countOf(playerId, "item_mat_hero_frag_sr");
        giveItems(playerId, "item_mat_hero_frag_sr", sr.composeFragment());
        String composed = sr.candidates().get(0).heroId();
        heroAppService.compose(playerId, new HeroIdReq(newRequestId(), composed));
        FragmentView after = heroAppService.list(playerId).fragments().stream()
                .filter(f -> f.itemId().equals("item_mat_hero_frag_sr")).findFirst().orElseThrow();
        assertThat(after.candidates()).extracting(ComposeCandidate::heroId).doesNotContain(composed);
        assertThat(countOf(playerId, "item_mat_hero_frag_sr"))
                .as("下发给客户端的门槛必须等于实扣的数，否则「还差几片」是句假话")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("武将视图带的是技能中文名（skill 表的 name 列），不是行 id —— #255 同族第三处")
    void heroViewCarriesSkillNamesNotIds() {
        String playerId = newPlayerWithHero("hero_ssr_01");
        HeroView view = heroOf(heroAppService.list(playerId), "hero_ssr_01");
        com.ironoath.config.cfg.SkillCfg main =
                configs.get(com.ironoath.config.cfg.SkillCfg.class, view.mainSkillId());
        com.ironoath.config.cfg.SkillCfg sub =
                configs.get(com.ironoath.config.cfg.SkillCfg.class, view.subSkillId());

        assertThat(view.mainSkillName()).isEqualTo(main.name());
        assertThat(view.subSkillName()).isEqualTo(sub.name());
        // 名字必须是玩家读得出的中文：退回成 id 的那条兜底路径不该在正常配置下被走到
        assertThat(view.mainSkillName()).as("主技能名里不该有 skill_ 前缀的行 id")
                .doesNotStartWith("skill_").isNotEqualTo(view.mainSkillId());
        assertThat(view.subSkillName()).doesNotStartWith("skill_").isNotEqualTo(view.subSkillId());
    }

    @Test
    @DisplayName("觉醒分初阶/高阶石：最后一阶只能用高阶石，用错明确拒绝")
    void awakenRequiresTheRightStoneTier() {
        String playerId = newPlayerWithHero("hero_ssr_01");   // awakenMax = 3
        giveItems(playerId, "item_hero_awaken_1", 5L, "item_hero_awaken_2", 5L);

        heroAppService.awaken(playerId, newHeroItemReq(playerId, "hero_ssr_01", "item_hero_awaken_1", null));
        heroAppService.awaken(playerId, newHeroItemReq(playerId, "hero_ssr_01", "item_hero_awaken_1", null));
        assertThat(heroOf(heroAppService.list(playerId), "hero_ssr_01").awaken()).isEqualTo(2);

        // 第 3 阶（最后一阶）必须用高阶石
        assertThatThrownBy(() -> heroAppService.awaken(playerId,
                newHeroItemReq(playerId, "hero_ssr_01", "item_hero_awaken_1", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("高阶觉醒石");
        assertThat(countOf(playerId, "item_hero_awaken_1")).as("被拒绝时不能扣石头").isEqualTo(3L);

        heroAppService.awaken(playerId, newHeroItemReq(playerId, "hero_ssr_01", "item_hero_awaken_2", null));
        HeroView maxed = heroOf(heroAppService.list(playerId), "hero_ssr_01");
        assertThat(maxed.awaken()).isEqualTo(3);
        assertThatThrownBy(() -> heroAppService.awaken(playerId,
                newHeroItemReq(playerId, "hero_ssr_01", "item_hero_awaken_2", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("觉醒上限");
    }

    @Test
    @DisplayName("技能书的 effectTarget 必须与请求的 skillSlot 一致，串了要明确拒绝而不是猜")
    void skillBookMustMatchRequestedSlot() {
        String playerId = newPlayerWithHero("hero_ssr_01");
        giveItems(playerId, "item_hero_skillbook_main", 2L, "item_hero_skillbook_sub", 2L);

        HeroGrowResp main = heroAppService.skillUp(playerId,
                newHeroItemReq(playerId, "hero_ssr_01", "item_hero_skillbook_main", "MAIN"));
        assertThat(main.hero().mainSkillLevel()).isEqualTo(2);
        assertThat(main.hero().subSkillLevel()).as("升主技能不该动副技能").isEqualTo(1);

        assertThatThrownBy(() -> heroAppService.skillUp(playerId,
                newHeroItemReq(playerId, "hero_ssr_01", "item_hero_skillbook_main", "SUB")))
                .isInstanceOf(BizException.class).hasMessageContaining("只能升 MAIN");
        assertThat(countOf(playerId, "item_hero_skillbook_main")).as("被拒绝时不能扣书").isEqualTo(1L);

        assertThatThrownBy(() -> heroAppService.skillUp(playerId,
                newHeroItemReq(playerId, "hero_ssr_01", "item_hero_skillbook_sub", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("skillSlot");
    }

    // ---------- 验收 10：装备与套装 ----------

    @Test
    @DisplayName("验收10：凑齐 4 件破军套触发 +15%，卸下一件降到 2 件的 +6%，全卸下归零")
    void equipSetBonusAppearsAndDisappears() {
        String playerId = newPlayerWithThreeHeroes();
        String main = "hero_ssr_01";
        levelHeroToMidTier(playerId, main);
        giveItems(playerId,
                "eq_pojun_blade", 1L, "eq_pojun_plate", 1L,
                "eq_pojun_warhorse", 1L, "eq_pojun_tiger_tally", 1L);
        heroAppService.setLineup(playerId, new SetLineupReq(newRequestId(), 0, main, null, null));

        // 套装百分比按乘区隔离，单独放在 equipSetAtk 里，不会混进 atkFixed（武将乘区）。
        // atkFixed 本身会因为装备的固定属性值上升，那是加算区的正常行为，两者要分开看。
        long setWithNothing = bonusOf(heroAppService.list(playerId), 0).equipSetAtkFixedOf();
        long heroZoneWithNothing = bonusOf(heroAppService.list(playerId), 0).atkFixed();
        assertThat(setWithNothing).as("没穿套装时套装乘区必须是 0").isZero();
        assertThat(heroZoneWithNothing).as("主将裸装时也应有武将乘区加成").isPositive();

        // 装备是武将身上的，套装按整支队伍统计；四件都穿在主将身上即可凑齐 4 件套
        heroAppService.equip(playerId, new HeroEquipReq(newRequestId(), main,
                com.ironoath.web.dto.generated.EquipSlot.WEAPON, "eq_pojun_blade"));
        heroAppService.equip(playerId, new HeroEquipReq(newRequestId(), main,
                com.ironoath.web.dto.generated.EquipSlot.ARMOR, "eq_pojun_plate"));
        heroAppService.equip(playerId, new HeroEquipReq(newRequestId(), main,
                com.ironoath.web.dto.generated.EquipSlot.MOUNT, "eq_pojun_warhorse"));
        long setWith3 = bonusOf(heroAppService.list(playerId), 0).equipSetAtkFixedOf();

        HeroGrowResp fourth = heroAppService.equip(playerId, new HeroEquipReq(newRequestId(), main,
                com.ironoath.web.dto.generated.EquipSlot.ACCESSORY, "eq_pojun_tiger_tally"));
        var fourPiece = fourth.hero();
        assertThat(fourPiece.equips())
                .as("四个槽位都该穿上，每项自带 slot")
                .extracting(WornEquip::slot)
                .containsExactlyInAnyOrder(EquipSlot.values());
        long setWith4 = bonusOf(heroAppService.list(playerId), 0).equipSetAtkFixedOf();

        assertThat(setWith3).as("3 件只触发 2 件套（+6%）").isEqualTo(600L);
        assertThat(setWith4).as("4 件套是 +15%，且不与 2 件套叠加（叠加会是 21%）").isEqualTo(1500L);
        // 乘区隔离：套装的 15% 绝不能被折进武将乘区，否则以后想单独调装备线就会连带影响武将线
        assertThat(bonusOf(heroAppService.list(playerId), 0).atkFixed())
                .as("武将乘区只应因为装备的固定属性值上升，不含套装百分比")
                .isLessThan(heroZoneWithNothing + 1500L);

        // 卸下一件 → 掉回 2 件套
        heroAppService.equip(playerId, new HeroEquipReq(newRequestId(), main,
                com.ironoath.web.dto.generated.EquipSlot.ACCESSORY, null));
        assertThat(bonusOf(heroAppService.list(playerId), 0).equipSetAtkFixedOf())
                .as("B06 验收 10：卸下后加成必须消失").isEqualTo(600L);
        assertThat(countOf(playerId, "eq_pojun_tiger_tally"))
                .as("卸下的装备必须回到背包，否则等于没收玩家财产").isEqualTo(1L);

        // 全部卸下 → 归零
        for (var slot : com.ironoath.web.dto.generated.EquipSlot.values()) {
            heroAppService.equip(playerId, new HeroEquipReq(newRequestId(), main, slot, null));
        }
        assertThat(bonusOf(heroAppService.list(playerId), 0).equipSetAtkFixedOf()).isZero();
        assertThat(countOf(playerId, "eq_pojun_blade")).isEqualTo(1L);
    }

    @Test
    @DisplayName("装备有槽位与等级门槛：穿错槽位或等级不够都明确拒绝，且装备不被扣")
    void equipEnforcesSlotAndLevelRequirements() {
        String playerId = newPlayerWithHero("hero_ssr_01");
        giveItems(playerId, "eq_pojun_blade", 1L, "eq_wenqu_jade", 1L);

        assertThatThrownBy(() -> heroAppService.equip(playerId, new HeroEquipReq(
                newRequestId(), "hero_ssr_01",
                com.ironoath.web.dto.generated.EquipSlot.ARMOR, "eq_pojun_blade")))
                .isInstanceOf(BizException.class).hasMessageContaining("槽位");
        assertThat(countOf(playerId, "eq_pojun_blade")).isEqualTo(1L);

        // eq_wenqu_jade 要求武将 40 级，新号只有 1 级
        assertThatThrownBy(() -> heroAppService.equip(playerId, new HeroEquipReq(
                newRequestId(), "hero_ssr_01",
                com.ironoath.web.dto.generated.EquipSlot.ACCESSORY, "eq_wenqu_jade")))
                .isInstanceOf(BizException.class).hasMessageContaining("级才能穿");
        assertThat(countOf(playerId, "eq_wenqu_jade")).isEqualTo(1L);
    }

    @Test
    @DisplayName("换装时被替换下来的那一件必须回到背包")
    void swappingEquipmentReturnsTheOldPiece() {
        String playerId = newPlayerWithHero("hero_ssr_01");
        levelHeroToMidTier(playerId, "hero_ssr_01");
        giveItems(playerId, "eq_iron_sword", 1L, "eq_pojun_blade", 1L);
        var slot = com.ironoath.web.dto.generated.EquipSlot.WEAPON;

        String swordUid = heroAppService.equip(playerId, new HeroEquipReq(
                newRequestId(), "hero_ssr_01", slot, "eq_iron_sword")).hero().equips().get(0).uid();
        assertThat(countOf(playerId, "eq_iron_sword"))
                .as("穿上不等于消失：那一件还在账本里，它带着强化等级，是玩家的财产（§五⑤）")
                .isEqualTo(1L);
        assertThat(unwornCount(playerId, "eq_iron_sword"))
                .as("但它已经不再占背包格子").isZero();
        assertThat(slotOf(playerId, "hero_ssr_01")).isEqualTo(swordUid);

        String bladeUid = heroAppService.equip(playerId, new HeroEquipReq(
                newRequestId(), "hero_ssr_01", slot, "eq_pojun_blade")).hero().equips().get(0).uid();
        assertThat(slotOf(playerId, "hero_ssr_01")).as("槽位换成了另一件（uid 不同）").isEqualTo(bladeUid);
        assertThat(unwornCount(playerId, "eq_iron_sword"))
                .as("换下来的铁剑重新回到可穿状态并占回那一格").isEqualTo(1L);
        assertThat(unwornCount(playerId, "eq_pojun_blade")).isZero();
        assertThat(bagOf(playerId).capacityUsed())
                .as("两件都在手上、一件穿着 ⇒ 格子数与只有一件未穿时相同")
                .isEqualTo(1 + nonEquipSlots(playerId));
    }

    @Test
    @DisplayName("武将页的装备语义与 /equip/instances 那一行逐字一致：名字、强化等级、槽位都不是客户端自己猜的")
    void wornEquipmentCarriesPlayerFacingNameAndForgeLevel() {
        String playerId = newPlayerWithHero("hero_ssr_01");
        levelHeroToMidTier(playerId, "hero_ssr_01");
        Inventory bag = bagOf(playerId);
        bag.restore(bag.snapshot(),
                List.of(new Inventory.EquipInstance("e1", "eq_iron_sword", 2, false)),
                bag.capacityMax(), 2, id -> false);
        inventories.save(playerId, bag, inventories.versionOf(playerId));

        WornEquip worn = heroAppService.equip(playerId, new HeroEquipReq(
                newRequestId(), "hero_ssr_01", EquipSlot.WEAPON, "e1")).hero().equips().get(0);
        EquipInstanceView row = equipAppService.list(playerId).instances().stream()
                .filter(instance -> "e1".equals(instance.uid()))
                .findFirst().orElseThrow();

        assertThat(worn.uid()).isEqualTo(row.uid()).isEqualTo("e1");
        assertThat(worn.slot()).isEqualTo(row.slot()).isEqualTo(EquipSlot.WEAPON);
        assertThat(worn.equipId()).isEqualTo(row.equipId()).isEqualTo("eq_iron_sword");
        assertThat(worn.name()).as("玩家看到的是配置表里的装备中文名")
                .isEqualTo(row.name()).isEqualTo("铁剑");
        assertThat(worn.name()).doesNotContain("eq_").doesNotContain("equip_");
        assertThat(worn.forgeLevel()).isEqualTo(row.forgeLevel()).isEqualTo(2);
    }

    // ---------- 验收 5 / 8 / 9：编队 ----------

    @Test
    @DisplayName("验收5：下阵之后队伍加成与带兵上限立即归零，没有任何残留")
    void unequippingLineupLeavesNoResidue() {
        String playerId = newPlayerWithThreeHeroes();
        SetLineupResp formed = heroAppService.setLineup(playerId, new SetLineupReq(
                newRequestId(), 0, "hero_ssr_01", "hero_ssr_03", "hero_sr_01"));
        assertThat(formed.lineup().bonus().atkFixed()).isPositive();
        assertThat(formed.lineup().bonus().commandValue()).isPositive();
        assertThat(formed.troopCap()).isPositive();

        SetLineupResp cleared = heroAppService.setLineup(playerId, new SetLineupReq(
                newRequestId(), 0, null, null, null));
        assertThat(cleared.lineup().bonus().atkFixed()).isZero();
        assertThat(cleared.lineup().bonus().defFixed()).isZero();
        assertThat(cleared.lineup().bonus().commandValue()).isZero();
        assertThat(cleared.lineup().bonus().breakdown()).isEmpty();
        assertThat(cleared.troopCap()).as("统帅值归零后带兵上限必须立刻归零").isZero();
        assertThat(heroAppService.list(playerId).troopCap()).isZero();
    }

    @Test
    @DisplayName("验收8：带兵上限 = 队伍统帅值 × TROOP_PER_COMMAND，换更强的武将上限就涨")
    void troopCapFollowsLineupCommand() {
        String playerId = newPlayerWithThreeHeroes();
        long perCommand = configs.longParam("TROOP_PER_COMMAND");

        SetLineupResp weak = heroAppService.setLineup(playerId, new SetLineupReq(
                newRequestId(), 0, "hero_sr_01", null, null));
        SetLineupResp strong = heroAppService.setLineup(playerId, new SetLineupReq(
                newRequestId(), 1, "hero_ssr_03", null, null));

        assertThat(weak.troopCap()).isEqualTo(weak.lineup().bonus().commandValue() * perCommand);
        assertThat(strong.troopCap()).isEqualTo(strong.lineup().bonus().commandValue() * perCommand);
        assertThat(strong.troopCap())
                .as("燕孤鸿(SSR) 统率 95 > 卫无咎(SR) 统率 65，带兵上限必须更高")
                .isGreaterThan(weak.troopCap());
    }

    @Test
    @DisplayName("验收9：裴惊澜与燕孤鸿同队时激活一条缘分，加成恰好等于 HERO_BOND_BONUS")
    void bondActivationMatchesConfigExactly() {
        String playerId = newPlayerWithThreeHeroes();
        long bondFixed = configs.fixedParam("HERO_BOND_BONUS");

        SetLineupResp noBond = heroAppService.setLineup(playerId, new SetLineupReq(
                newRequestId(), 0, "hero_ssr_01", "hero_sr_01", null));
        assertThat(noBond.lineup().activeBonds()).isEmpty();

        SetLineupResp withBond = heroAppService.setLineup(playerId, new SetLineupReq(
                newRequestId(), 0, "hero_ssr_01", "hero_ssr_03", null));
        assertThat(withBond.lineup().activeBonds()).as("两人互为缘分，同队激活一条").hasSize(1);
        assertThat(withBond.lineup().bonus().atkFixed() - noBond.lineup().bonus().atkFixed())
                .as("缘分加成必须精确等于配置值（误差 0），差值里还含武将本身的属性变化，"
                        + "所以这里比对的是明细里的 BOND 行")
                .isNotZero();
        assertThat(withBond.lineup().bonus().breakdown())
                .filteredOn(b -> b.zone() == BonusZone.BOND)
                .hasSize(1)
                .allSatisfy(b -> assertThat(b.value()).isEqualTo(bondFixed));
    }

    @Test
    @DisplayName("上阵未拥有的武将、同队重复、预设越界都被服务端拒绝")
    void lineupRejectsInvalidRequests() {
        String playerId = newPlayerWithHero("hero_ssr_01");

        assertThatThrownBy(() -> heroAppService.setLineup(playerId, new SetLineupReq(
                newRequestId(), 0, "hero_ssr_02", null, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("尚未拥有");
        assertThatThrownBy(() -> heroAppService.setLineup(playerId, new SetLineupReq(
                newRequestId(), 0, "hero_ssr_01", "hero_ssr_01", null)))
                .isInstanceOf(BizException.class).hasMessageContaining("两次");
        assertThatThrownBy(() -> heroAppService.setLineup(playerId, new SetLineupReq(
                newRequestId(), 99, "hero_ssr_01", null, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("presetIndex");
    }

    @Test
    @DisplayName("三套预设互不影响，各自记住自己的编队")
    void presetsAreIndependent() {
        String playerId = newPlayerWithThreeHeroes();
        heroAppService.setLineup(playerId, new SetLineupReq(newRequestId(), 0, "hero_ssr_01", null, null));
        heroAppService.setLineup(playerId, new SetLineupReq(newRequestId(), 2, "hero_sr_01", null, null));

        HeroListResp list = heroAppService.list(playerId);
        assertThat(list.lineups()).hasSize((int) configs.longParam("LINEUP_PRESET_COUNT"));
        assertThat(list.lineups().get(0).main()).isEqualTo("hero_ssr_01");
        assertThat(list.lineups().get(1).main()).as("没编过的预设应是空位").isNull();
        assertThat(list.lineups().get(2).main()).isEqualTo("hero_sr_01");
    }

    // ---------- 辅助 ----------

    /** 用 BigDecimal 独立算一遍属性，不复用实现的定点链（否则测试只是在断言实现等于自己）。 */
    private static void assertAttrsMatchHandCalc(HeroView view, long might, long command, long wisdom,
                                                 String growthRate, int level, int star, int awaken) {
        AttrTriple expected = new AttrTriple(
                handCalc(might, growthRate, level, star, awaken),
                handCalc(command, growthRate, level, star, awaken),
                handCalc(wisdom, growthRate, level, star, awaken));
        // 装备固定值直接相加（不被养成因子放大），裸装时为 0
        assertThat(view.finalAttrs().might() >= expected.might())
                .as("裸装时应当恰好相等；穿了装备则高出装备的固定值").isTrue();
        if (view.equips().isEmpty()) {
            assertThat(view.finalAttrs()).isEqualTo(expected);
        }
        assertThat(view.baseAttrs()).isEqualTo(new AttrTriple(might, command, wisdom));
    }

    private static long handCalc(long base, String growthRate, int level, int star, int awaken) {
        BigDecimal levelFactor = BigDecimal.ONE.add(
                new BigDecimal(level - 1).multiply(new BigDecimal("0.02")));
        BigDecimal starFactor = BigDecimal.ONE.add(
                new BigDecimal(star - 1).multiply(new BigDecimal("0.10")));
        BigDecimal awakenFactor = BigDecimal.ONE.add(
                new BigDecimal(awaken).multiply(new BigDecimal("0.08")));
        return BigDecimal.valueOf(base).multiply(new BigDecimal(growthRate))
                .multiply(levelFactor).multiply(starFactor).multiply(awakenFactor)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 队伍加成的可读包装：把 HeroBonus 的三个乘区数值取出来。 */
    private record BonusView(long atkFixed, long defFixed, long commandValue, long equipSetAtk) {
        long equipSetAtkFixedOf() {
            return equipSetAtk;
        }
    }

    private BonusView bonusOf(HeroListResp list, int presetIndex) {
        var bonus = list.lineups().get(presetIndex).bonus();
        // 装备套装的攻击加成在明细里以 EQUIP_SET 行给出（HeroBonus 没有单列字段，
        // 因为 B06 契约只要求「按乘区返回明细」）
        long equipAtk = 0L;
        boolean seen = false;
        for (var b : bonus.breakdown()) {
            if (b.zone() == BonusZone.EQUIP_SET && !seen) {
                equipAtk = b.value();
                seen = true;
            }
        }
        return new BonusView(bonus.atkFixed(), bonus.defFixed(), bonus.commandValue(), equipAtk);
    }

    private static HeroView heroOf(HeroListResp list, String heroId) {
        return list.heroes().stream().filter(h -> h.heroId().equals(heroId))
                .findFirst().orElseThrow(() -> new AssertionError("响应里没有武将 " + heroId));
    }

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "武将测试", 1_700_000_000_000L, ""))
                .playerId();
    }

    /** 建号并用碎片合成一名武将，绕过抽卡的随机性以便精确断言养成数值。 */
    private String newPlayerWithHero(String heroId) {
        String playerId = newPlayer();
        HeroCfg cfg = configs.get(HeroCfg.class, heroId);
        long need = configs.get(com.ironoath.config.cfg.HeroRarityCfg.class, cfg.rarity().name())
                .composeFragment();
        giveItems(playerId, fragmentItemOf(cfg.rarity()), need);
        heroAppService.compose(playerId, new HeroIdReq(newRequestId(), heroId));
        return playerId;
    }

    /** 建号并合成三名武将：两名互为缘分的 SSR + 一名 SR。 */
    private String newPlayerWithThreeHeroes() {
        String playerId = newPlayer();
        for (String heroId : List.of("hero_ssr_01", "hero_ssr_03", "hero_sr_01")) {
            HeroCfg cfg = configs.get(HeroCfg.class, heroId);
            long need = configs.get(com.ironoath.config.cfg.HeroRarityCfg.class, cfg.rarity().name())
                    .composeFragment();
            giveItems(playerId, fragmentItemOf(cfg.rarity()), need);
            heroAppService.compose(playerId, new HeroIdReq(newRequestId(), heroId));
        }
        return playerId;
    }

    /**
     * 把武将练到中阶（约 29 级），以便穿 20 级门槛的破军套但仍穿不了 40 级门槛的文曲套。
     *
     * <p>用中经验书（2000 经验）而不是大经验书（10000）：经验曲线是 20 × 1.08^(n-1)，
     * 累计到 L 级需要 250 × (1.08^(L-1) - 1)，2000 经验落在约 29 级、10000 经验会冲到约 49 级，
     * 那样「等级不够穿文曲套」这条断言就测不到了。
     */
    private void levelHeroToMidTier(String playerId, String heroId) {
        giveItems(playerId, "item_hero_exp_m", 1L);
        HeroGrowResp resp = heroAppService.levelUp(playerId, new HeroLevelUpReq(
                newRequestId(), heroId, List.of(new ItemCount("item_hero_exp_m", 1L))));
        assertThat(resp.hero().level())
                .as("1 本中经验书应当把武将练到 20~39 级之间：够穿破军套（20 级），够不着文曲套（40 级）")
                .isBetween(20, 39);
    }

    private String fragmentItemOf(HeroCfg.Rarity rarity) {
        return "item_mat_hero_frag_" + rarity.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private com.ironoath.web.dto.generated.HeroItemReq newHeroItemReq(
            String playerId, String heroId, String itemId, String slot) {
        return new com.ironoath.web.dto.generated.HeroItemReq(newRequestId(), heroId, itemId,
                slot == null ? null : com.ironoath.web.dto.generated.SkillSlot.valueOf(slot));
    }

    /**
     * 夹具发东西。<b>必须走 {@code RewardPorts.Bag}（生产那唯一一条发放口），不能直接改聚合</b>：
     * 装备的"按件铸实例"分流住在 {@code PlayerBag} 里，绕过它就等于所有装备用例都在测一个
     * 生产已经不会再产生的形状（{@code eq_xxx: 2} 这种数量条目），
     * 于是"测试全绿而玩家穿不上装备"。
     */
    private void giveItems(String playerId, Object... itemIdCountPairs) {
        for (int i = 0; i < itemIdCountPairs.length; i += 2) {
            String itemId = (String) itemIdCountPairs[i];
            long count = (Long) itemIdCountPairs[i + 1];
            long added = bagPort.add(playerId, itemId, count);
            assertThat(added).as("夹具必须能放下 %s × %d", itemId, count).isEqualTo(count);
        }
    }

    private void grantGold(String playerId, long amount) {
        var save = players.findByPlayerId(playerId).orElseThrow();
        var gold = save.resource("GOLD");
        save.putResource("GOLD", new com.ironoath.core.player.PlayerResourceState(
                Math.min(gold.cap(), gold.current() + amount), gold.cap(),
                gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        players.save(save);
    }

    @Test
    @DisplayName("发两件同名装备得到两个实例、两个 uid（数量模型从此不成立）")
    void grantingEquipmentMintsOneInstancePerCopy() {
        String playerId = newPlayer();
        giveItems(playerId, "eq_iron_sword", 2L);

        Inventory bag = bagOf(playerId);
        assertThat(bag.equipInstances("eq_iron_sword")).as("两件就是两条实例")
                .hasSize(2)
                .extracting(Inventory.EquipInstance::uid).doesNotHaveDuplicates();
        assertThat(bag.countOf("eq_iron_sword")).as("对外仍然报件数，背包列表不必改").isEqualTo(2L);
        assertThat(bag.snapshot()).as("装备绝不写进 counts 那张表").doesNotContainKey("eq_iron_sword");
        assertThat(bag.capacityUsed()).as("按件占格：2 件装备 2 格").isEqualTo(2);
    }

    @Test
    @DisplayName("强化等级作用在装备自己的属性上，且是精确定点值（+4 破军刃武力 45→54）")
    void forgeLevelRaisesTheOwnStatsExactly() {
        String playerId = newPlayerWithHero("hero_ssr_01");
        levelHeroToMidTier(playerId, "hero_ssr_01");
        // 今天还没有 /equip/forge（S3），所以直接按账本的形状摆一件 +4 的破军刃进去：
        // 断言的是"等级如何变成属性"，与"谁把等级抬上去"无关，两件事分属两个单元
        Inventory bag = bagOf(playerId);
        bag.restore(bag.snapshot(),
                List.of(new Inventory.EquipInstance("e1", "eq_pojun_blade", 4, false)),
                bag.capacityMax(), 2, id -> false);
        inventories.save(playerId, bag, inventories.versionOf(playerId));

        heroAppService.equip(playerId, new HeroEquipReq(newRequestId(), "hero_ssr_01",
                com.ironoath.web.dto.generated.EquipSlot.WEAPON, "e1"));

        HeroInstance hero = heroes.findByPlayerId(playerId).orElseThrow().hero("hero_ssr_01");
        var flat = heroStats.equipFlat(equipLedgers.of(playerId), hero);
        assertThat(flat.might())
                .as("45 × (1 + 5% × 4) = 54，必须正好是这个数而不是「变大了」")
                .isEqualTo(54L);

        // 换一件 +3 的：45 × 1.15 = 51.75 ⇒ 向下取整 51（向上等于凭空多给，属性会进战力）
        Inventory bag2 = bagOf(playerId);
        bag2.restore(bag2.snapshot(),
                List.of(new Inventory.EquipInstance("e1", "eq_pojun_blade", 3, true)),
                bag2.capacityMax(), 2, id -> false);
        inventories.save(playerId, bag2, inventories.versionOf(playerId));
        assertThat(heroStats.equipFlat(equipLedgers.of(playerId), hero).might())
                .as("定点乘完只落地一次，且向下").isEqualTo(51L);
    }

    @Test
    @DisplayName("老存档槽位里写的是配置行 id：按 +0 计入属性，绝不读成没穿装备（§五⑤）")
    void legacySlotValueStillCountsAsEquipment() {
        String playerId = newPlayerWithHero("hero_ssr_01");
        HeroRoster roster = heroes.findByPlayerId(playerId).orElseThrow();
        roster.hero("hero_ssr_01").equip(com.ironoath.core.hero.EquipSlot.WEAPON, "eq_pojun_blade");
        heroes.save(playerId, roster, heroes.versionOf(playerId));

        var hero = heroes.findByPlayerId(playerId).orElseThrow().hero("hero_ssr_01");
        assertThat(heroStats.equipFlat(equipLedgers.of(playerId), hero).might())
                .as("这条链上任何一次读取都不许把装备读成 0 —— 那会让人战力凭空掉一截，"
                        + "而这份档再落一次库，那件装备就真的没了")
                .isEqualTo(45L);
        assertThat(heroAppService.list(playerId).heroes())
                .as("面板照常出得来，老行 id 被解析成可读装备语义，但不会把内部 id 当名字画出去")
                .anyMatch(view -> view.equips().stream().anyMatch(equip ->
                        "eq_pojun_blade".equals(equip.equipId())
                                && "破军刃".equals(equip.name())
                                && equip.forgeLevel() == 0));
    }

    @Test
    @DisplayName("槽位里的 uid 在账本中不存在：这一格按 0 计，但不抛异常（不让人登录不了）")
    void danglingUidCountsAsNothingButDoesNotThrow() {
        String playerId = newPlayerWithHero("hero_ssr_01");
        HeroRoster roster = heroes.findByPlayerId(playerId).orElseThrow();
        roster.hero("hero_ssr_01").equip(com.ironoath.core.hero.EquipSlot.WEAPON, "e404");
        heroes.save(playerId, roster, heroes.versionOf(playerId));

        var hero = heroes.findByPlayerId(playerId).orElseThrow().hero("hero_ssr_01");
        assertThat(heroStats.equipFlat(equipLedgers.of(playerId), hero).might())
                .as("悬空 uid 不是老档形状，不能凭空按某一行计").isZero();
        assertThat(heroAppService.list(playerId).heroes()).hasSize(1);
    }

    private Inventory bagOf(String playerId) {
        return inventories.findByPlayerId(playerId).orElseThrow();
    }

    /** 该行还有几件没穿在身上（= 还能穿的件数）。 */
    private long unwornCount(String playerId, String equipId) {
        return bagOf(playerId).equipInstances(equipId).stream()
                .filter(instance -> !instance.worn()).count();
    }

    private String slotOf(String playerId, String heroId) {
        return heroes.findByPlayerId(playerId).orElseThrow().hero(heroId)
                .equipOf(com.ironoath.core.hero.EquipSlot.WEAPON);
    }

    /** 背包里非装备占的格子数（容量断言要把它扣掉，否则会被同文件其它道具干扰）。 */
    private int nonEquipSlots(String playerId) {
        return (int) bagOf(playerId).snapshot().size();
    }

    private long countOf(String playerId, String itemId) {
        return inventories.findByPlayerId(playerId).map(b -> b.countOf(itemId)).orElse(0L);
    }

    /** 让编译器知道 Tier 在本文件里确实被用到（概率断言里按档位遍历）。 */
    @SuppressWarnings("unused")
    private static final List<Tier> TIERS = new ArrayList<>(List.of(Tier.values()));
}
