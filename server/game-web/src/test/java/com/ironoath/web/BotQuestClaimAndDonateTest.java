package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.core.world.Coord;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.bot.BotDecisionTree;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.city.CityState;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.bot.BotRuntimeService;
import com.ironoath.web.bot.BotWorldAdapter;
import com.ironoath.web.dto.generated.CityUpgradeReq;
import com.ironoath.web.dto.generated.CityUpgradeResp;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.QuestClaimReq;
import com.ironoath.web.quest.QuestAppService;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemoryWorldStore;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.social.Alliance;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.web.dto.generated.AllianceCreateReq;

/**
 * 职责：C18 / C19 的收口验收 —— Bot 只捐免费档、Bot 走真人同一条链路领任务奖励。
 * 依赖：Spring Boot Test；test profile（内存存储）。
 *
 * <p><b>为什么断言「花名册里多了一名候选 SR」而不是「troopCap 变正」</b>：C19 的理由链是
 * 「不领就永远造不出兵」，但 {@code troopCap} 读的是<b>编队</b>统帅值 —— 领到武将而没上阵时它仍是 0。
 * 「Bot 要不要自动把新将上阵」是另一条尚未裁决的口径，所以本用例只收到"领到了将"这一步，
 * 剩下半条链记在收口清单里而不是在这里假装已经通了。
 *
 * <p><b>为什么捐献断言的是精确增量而不是 ≥0</b>：原来那句
 * {@code isGreaterThanOrEqualTo(fundsBefore)} 在 Bot 改捐金币档时<b>照样全绿</b>
 * （高档只会给更多钱），而 C18 拦的恰恰就是"给多了"。一个不会为裁决而红的断言等于没有断言。
 */
@SpringBootTest
@ActiveProfiles("test")
class BotQuestClaimAndDonateTest {

    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private WorldRepository world;
    @Autowired private ArmyRepository armies;
    @Autowired private SocialStore socialStore;
    @Autowired private SocialAppService social;
    @Autowired private CityAppService cityApp;
    @Autowired private com.ironoath.core.city.CityRepository cityStore;
    @Autowired private QuestAppService quests;
    @Autowired private HeroRepository heroStore;
    @Autowired private ConfigRegistry configs;
    @Autowired private TimeService timeService;
    @Autowired private BotRegistry bots;
    @Autowired private BotRuntimeService runtime;
    @Autowired private BotWorldAdapter adapter;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryWorldStore) world).clear();
        ((InMemoryArmyStore) armies).clear();
        socialStore.clear();
        bots.clear();
        runtime.reset();
        adapter.resetCounters();
    }

    @Test
    @DisplayName("C19：Bot 领任务奖走真人同一条 claim —— 领完花名册里真的多了一名候选 SR")
    void botsClaimQuestRewardsThroughTheHumanPath() {
        String botId = botAt(256, 256);
        assertThat(quests.claimableCount(botId)).as("前置：还没做过任务，没有可领的").isZero();
        assertThat(heroStore.findByPlayerId(botId).map(r -> r.heroes().size()).orElse(0))
                .as("前置：还没领过任何赠将，花名册是空的").isZero();

        // 真人那条路把任务做完：quest_main_01 要求把主城升 2 次
        upgradeMainCity(botId);
        upgradeMainCity(botId);
        assertThat(quests.claimableCount(botId)).as("做完就该有红点").isEqualTo(1);

        adapter.execute(botId, new BotDecisionTree.Decision(
                BotDecisionTree.Action.CLAIM_QUEST, false, 0L, "测试：领奖"), timeService.serverNow());

        assertThat(adapter.actionCounts().get("claimed")).as("领奖动作成功了一次").isEqualTo(1L);
        assertThat(adapter.failedCount()).as("领奖失败才算故障，成功不该记成失败").isZero();
        var roster = heroStore.findByPlayerId(botId).orElseThrow();
        assertThat(roster.heroes()).extracting(com.ironoath.core.hero.HeroInstance::heroId)
                .as("C19 要的可观察结果：Bot 真的拿到了主线赠的那名 SR")
                .containsAnyOf("hero_sr_01", "hero_sr_02", "hero_sr_03");
        assertThat(quests.claimableCount(botId)).as("领过就不该再可领（否则红点永远不灭）").isZero();
        // 刻意不断 troopCap：它读的是<b>编队</b>统帅值（HeroAppService#troopCap → stats.troopCap(roster, 0)），
        // 领到将但没上阵时仍然是 0。"Bot 要不要自动把新将上阵"是另一条尚未裁决的口径
        // （收口清单 §三·补 C19 只裁了领奖），在这里断成正数等于替那条裁决抢答。
        // 所以"不领就永远造不出兵"这条链，本轮只收到"领到了将"，剩下半条记在册。
    }

    @Test
    @DisplayName("C19：领奖必须真的被 claim 挡住的情况也不算成功（幂等键与前置校验没被绕过）")
    void repeatedClaimDoesNotDoubleFire() {
        String botId = botAt(258, 258);
        upgradeMainCity(botId);
        upgradeMainCity(botId);

        adapter.execute(botId, new BotDecisionTree.Decision(
                BotDecisionTree.Action.CLAIM_QUEST, false, 0L, "第一次"), timeService.serverNow());
        adapter.execute(botId, new BotDecisionTree.Decision(
                BotDecisionTree.Action.CLAIM_QUEST, false, 0L, "第二次"), timeService.serverNow());

        assertThat(adapter.actionCounts().get("claimed"))
                .as("第二次已经没有可领的了，claimed 不该涨到 2").isEqualTo(1L);
    }

    @Test
    @DisplayName("C18：Bot 只捐免费档 —— 联盟公账的增量必须正好等于免费档那格")
    void botsDonateOnlyTheFreeTier() {
        String botId = botAt(260, 260);
        String allianceId = createAlliance(botId);
        Alliance alliance = socialStore.allianceById(allianceId).orElseThrow();
        long fundsBefore = alliance.fund();
        long freeTierFund = configs.longParam("DONATE_TIER_FREE_FUND");
        long paidTierFund = configs.longParam("DONATE_TIER_RESOURCE_FUND");
        assertThat(freeTierFund).as("免费档入账为正，否则这条断言恒真").isPositive();
        assertThat(paidTierFund).as("资源档给的钱必须与免费档不同，否则这条断言证伪不了改档位")
                .isNotEqualTo(freeTierFund);

        adapter.execute(botId, new BotDecisionTree.Decision(
                BotDecisionTree.Action.ALLIANCE_DONATE, false, 0L, "测试：捐献"), timeService.serverNow());

        assertThat(socialStore.allianceById(allianceId).orElseThrow().fund())
                .as("精确等于免费档那一格。原先断言的是 ≥ 捐献前，Bot 改捐金币档照样全绿 —— "
                        + "而 C18 拦的正是「给多了」")
                .isEqualTo(fundsBefore + freeTierFund);
        assertThat(adapter.actionCounts().get("donated")).isEqualTo(1L);
    }

    // ---------- 夹具 ----------

    /** 一个托管账号：活跃时段给满 24 小时，免得作息相位把行为推走。 */
    private String botAt(int x, int y) {
        String botId = humanAt(x, y);
        java.util.List<Integer> allHours = new java.util.ArrayList<>();
        for (int h = 0; h < 24; h++) {
            allHours.add(h);
        }
        bots.register(new BotProfile(botId, "bot_linju",
                new BotProfile.AiProfile(FixedPoint.parse("0.50"), FixedPoint.parse("0.50"),
                        FixedPoint.parse("0.50"), FixedPoint.parse("1.0")),
                new BotProfile.Persona(42L, 7L, 99L, allHours, 3L, 30L, FixedPoint.parse("0.00")),
                FixedPoint.parse("1.0")));
        return botId;
    }

    private String humanAt(int x, int y) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(),
                "托管" + UUID.randomUUID().toString().substring(0, 4), 1_700_000_000_000L, "")).playerId();
        assertThat(world.placeCity(playerId, Coord.of(x, y)))
                .as("夹具必须能把城放到 (%d,%d)", x, y).isTrue();
        return playerId;
    }

    /**
     * 走真人那条链路完成一次主城升级。
     *
     * <p>三步而不是一步：任务事件是在<b>收割那一刻</b>发的（B12 §1），而收割由读触发 ——
     * 只调 {@code upgrade} 会留下一个进行中的升级，第二次升级直接被「建筑正在升级中」拒掉。
     * 服务端不跑定时器，所以把完成时刻改到过去再读一次城就是"时间过去了"（CityEndpointTest 同源）。
     */
    private void upgradeMainCity(String playerId) {
        CityUpgradeResp started = cityApp.upgrade(playerId,
                new CityUpgradeReq("req-" + UUID.randomUUID(), "main_city", null, null));
        CityState city = cityStore.findByPlayerId(playerId).orElseThrow();
        long version = cityStore.versionOf(playerId);
        var b = city.building(started.buildingId());
        b.restore(b.level(), b.gridX(), b.gridY(),
                com.ironoath.core.city.BuildingStatus.UPGRADING, 1L,
                0L, 1L, 1L, b.helpCount(), b.lastMovedAt(), b.lastFinishedAt());
        cityStore.save(playerId, city, version);
        cityApp.list(playerId);   // 读一次城：结算 → 收割 → 发布任务进度事件
    }

    /** 建一个联盟。解锁条件是主城 10 级 + 一笔金币，夹具必须先把这两样备齐。 */
    private String createAlliance(String leader) {
        PlayerSave save = players.findByPlayerId(leader).orElseThrow();
        save.setCityLevel(16);
        var gold = save.resources().get("GOLD");
        if (gold != null) {
            save.putResource("GOLD", new com.ironoath.core.player.PlayerResourceState(
                    100_000L, gold.cap(), gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        }
        players.save(save);
        social.allianceCreate(leader, new AllianceCreateReq(
                "req-" + UUID.randomUUID(), "捐献观察社",
                "T" + Math.abs(leader.hashCode() % 1000)));
        return socialStore.allianceOf(leader).orElseThrow().id();
    }
}
