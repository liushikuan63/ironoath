package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.cfg.HeroCfg;
import com.ironoath.config.cfg.SkillCfg;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.DayKey;
import com.ironoath.common.time.TimeService;
import com.ironoath.common.time.WeekKey;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.quest.GoalType;
import com.ironoath.core.quest.QuestProgress;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.QuestClaimReq;
import com.ironoath.web.dto.generated.QuestClaimResp;
import com.ironoath.web.dto.generated.QuestListResp;
import com.ironoath.web.dto.generated.QuestView;
import com.ironoath.web.quest.QuestAppService;
import com.ironoath.web.quest.QuestProgressStore;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemoryQuestProgressStore;
import com.ironoath.web.social.TestSquads;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：B12 §1 任务系统的端到端验证 —— 面板、领取、跨期清零、状态型目标的数据源覆盖。
 * 依赖：Spring Boot Test + test profile（内存存储）。
 *
 * <p><b>本类盯的是三件"没接上也不会报错"的事</b>：
 * <ol>
 *   <li><b>每个状态型目标都有数据源</b>：没有的话那个任务的进度永远是 0，
 *       而玩家看到的是一个永远做不完的任务（今天只有个人科技是这种：它没有规格，见收口清单 #6）</li>
 *   <li><b>领奖真的发东西、且只发一次</b>：只推进状态不发放 = 玩家点了一次"领了"，背包里什么都没有；
 *       发放不只推进状态 = 可以反复领</li>
 *   <li><b>跨期清零真的发生</b>：每日任务的进度与领取状态在跨天后必须归零，
 *       否则昨天的 3/3 会被当成今天的（白送一次完成态）</li>
 * </ol>
 *
 * <p><b>累加型目标的进度由事件推动</b>（B12 禁止项：不得轮询），所以本类用"直接往账本里播种"
 * 的方式制造"已完成"的前置状态 —— 事件链本身由 game-core 的 {@code QuestSystemTest} 覆盖
 * （含验收 7 的 100 个事件），而各业务动作真的发出事件这件事由各自端点测试的进度断言覆盖。
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class QuestEndpointTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private QuestAppService quests;
    @Autowired private QuestProgressStore questStore;
    @Autowired private SocialStore socialStore;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private InventoryRepository inventories;
    @Autowired private TimeService timeService;
    @Autowired private com.ironoath.config.ConfigRegistry configs;
    @Autowired private com.ironoath.web.battlepass.BattlePassService battlePass;
    @Autowired private com.ironoath.core.event.GameEventBus bus;
    @Autowired private com.ironoath.web.service.GachaAppService gachaAppService;
    @Autowired private com.ironoath.web.service.SocialAppService social;
    @Autowired private com.ironoath.web.social.HelpRequestRegistrar helpRegistrar;
    @Autowired private com.ironoath.web.quest.QuestRulesAssembler assembler;
    @Autowired private com.ironoath.core.hero.HeroRepository heroes;
    @Autowired private com.ironoath.web.service.HeroAppService heroAppService;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryQuestProgressStore) questStore).clear();
    }

    @Test
    @DisplayName("状态型目标必须有数据源：缺一个，那个任务的进度就永远是 0（B20 块① 之后已无有意缺口）")
    void everyStateTargetHasASource() {
        Set<GoalType> stateTypes = Arrays.stream(GoalType.values())
                .filter(type -> !type.accumulates())
                .collect(Collectors.toSet());
        Set<GoalType> accounted = new HashSet<>(QuestAppService.STATE_TYPES_WITH_SOURCE);
        // 这里**不再有任何"有意缺的那一个"**：个人科技的数据源是 B20 块① 接上的（读 PlayerTech 账本）。
        // 以后新增状态型目标却没接数据源时，这条断言会红 —— 而那正是"任务静默不可完成"的形状
        assertThat(stateTypes)
                .as("状态型目标（进度=当前状态）共 %d 个，接上数据源的 %d 个：两边必须一个不差",
                        stateTypes.size(), QuestAppService.STATE_TYPES_WITH_SOURCE.size())
                .containsExactlyInAnyOrderElementsOf(accounted);
    }

    @Test
    @DisplayName("面板：主线首条不锁、次条锁；未做任何事时可领数为 0")
    void freshPlayerSeesTheMainChainLocked() {
        String playerId = newPlayer();
        QuestListResp resp = quests.list(playerId);

        assertThat(resp.quests()).as("quest 表 76 行（九项成长成就及50等级独立首杀）全部下发").hasSize(76);
        QuestView first = byId(resp, "quest_main_01");
        assertThat(first.name()).as("名字来自表，不是 id").isNotBlank().isNotEqualTo(first.questId());
        assertThat(first.locked()).as("首条没有前置").isFalse();
        assertThat(first.current()).isZero();
        assertThat(first.complete()).isFalse();
        assertThat(first.claimable()).isFalse();
        assertThat(byId(resp, "quest_main_02").locked())
                .as("第二条的前置是首条 —— 没领前置就该锁着").isTrue();
        assertThat(resp.claimableCount()).as("什么都没做，没有可领的").isZero();
    }

    @Test
    @DisplayName("领奖：真的发东西（金币入账）、状态推进、且只发一次")
    void claimPaysExactlyOnce() {
        String playerId = newPlayer();
        long goldBefore = goldOf(playerId);
        long pointsBefore = battlePass.status(playerId).points();
        seedComplete(playerId, "quest_side_01");   // 支线、无前置、奖励 50 金

        QuestListResp before = quests.list(playerId);
        assertThat(before.claimableCount()).as("播种出来的完成态应当可领").isEqualTo(1);
        assertThat(byId(before, "quest_side_01").claimable()).isTrue();

        QuestClaimResp claimed = quests.claim(playerId,
                new QuestClaimReq(newId(), "quest_side_01", null));
        assertThat(claimed.rewards()).as("回执里有实际发放的明细").hasSize(1);
        assertThat(goldOf(playerId)).as("奖励必须真的到账 —— 只推进状态等于玩家点了个寂寞")
                .isEqualTo(goldBefore + 50L);
        long pointsAfter = battlePass.status(playerId).points();
        assertThat(pointsAfter - pointsBefore)
                .as("B24 S-d-c：领一次任务就给战令加分，加多少由 quest 表那一列给")
                .isEqualTo(configs.get(com.ironoath.config.cfg.QuestCfg.class, "quest_side_01").battlePassPoints());
        assertThat(claimed.claimableCount()).as("领完就没有可领的了").isZero();
        assertThat(quests.list(playerId).claimableCount()).isZero();

        assertThatThrownBy(() -> quests.claim(playerId, new QuestClaimReq(newId(), "quest_side_01", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).code())
                .as("重复领取必须被拒（与「没完成」分开的码）")
                .isEqualTo(ErrorCode.QUEST_ALREADY_CLAIMED.code());
        assertThat(goldOf(playerId)).as("被拒的那次不能发钱").isEqualTo(goldBefore + 50L);
        assertThat(battlePass.status(playerId).points()).as("被拒的那次也不能加分").isEqualTo(pointsAfter);
    }

    @Test
    @DisplayName("碎片奖励按表里的稀有度发放：quest_main_09 是 SR、其余是 N")
    void fragmentRewardsFollowTheConfiguredRarity() {
        String playerId = newPlayer();
        // quest_weekly_02 无前置：奖励 300 金 + 1 个 N 档碎片（见 quest 表的 rewardFragmentRarity）
        seedComplete(playerId, "quest_weekly_02");
        QuestClaimResp claimed = quests.claim(playerId, new QuestClaimReq(newId(), "quest_weekly_02", null));
        assertThat(claimed.rewards()).as("金币 + 碎片两条").hasSize(2);
        assertThat(claimed.rewards().stream().filter(r -> r.type().equals("HERO_FRAGMENT")).count())
                .as("碎片那一条要带上正确的类型").isEqualTo(1L);
        assertThat(countOf(playerId, "item_mat_hero_frag_n"))
                .as("落库的是 N 档碎片道具（表里写哪一档就发哪一档）").isEqualTo(1L);
        assertThat(countOf(playerId, "item_mat_hero_frag_sr")).as("不能发错档").isZero();

        // 表里 quest_main_09 自带 why 写着「奖励 500 金 + 2 个 SR 碎片」：装配器必须按那一档挑载体武将
        var rewards = assembler.rewardsByQuest().get("quest_main_09");
        var fragment = rewards.stream().filter(r -> r.type().name().equals("HERO_FRAGMENT"))
                .findFirst().orElseThrow(() -> new AssertionError("quest_main_09 应当有碎片奖励"));
        assertThat(fragment.count()).isEqualTo(2L);
        assertThat(configs.get(com.ironoath.config.cfg.HeroCfg.class, fragment.id()).rarity().name())
                .as("quest_main_09 的碎片载体必须是 SR 档武将（表里填的是 SR）").isEqualTo("SR");
        assertThat(rewards.stream().filter(r -> r.type().name().equals("RESOURCE")).count())
                .as("金币那条也在（它的 why 写着 500 金）").isEqualTo(1L);
    }

    @Test
    @DisplayName("三种拒绝各有各的码：没完成 / 已领过 / 前置没领；不存在的任务另有一码")
    void claimRejectionsHaveTheirOwnCodes() {
        String playerId = newPlayer();

        assertThatThrownBy(() -> quests.claim(playerId, new QuestClaimReq(newId(), "quest_side_01", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).code())
                .as("没完成就领（quest_side_01 无前置，所以它只可能是「没完成」）")
                .isEqualTo(ErrorCode.QUEST_NOT_COMPLETE.code());

        assertThatThrownBy(() -> quests.claim(playerId, new QuestClaimReq(newId(), "quest_main_02", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).code())
                .as("前置没领 ⇒ 未解锁（客户端据此提示「先完成 X」）")
                .isEqualTo(ErrorCode.QUEST_LOCKED.code());

        assertThatThrownBy(() -> quests.claim(playerId, new QuestClaimReq(newId(), "quest_no_such", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).code())
                .isEqualTo(ErrorCode.QUEST_NOT_FOUND.code());

        // 同一个 requestId 重放：被幂等键挡下（领奖是"会发东西"的写操作）
        String requestId = newId();
        seedComplete(playerId, "quest_side_01");
        quests.claim(playerId, new QuestClaimReq(requestId, "quest_side_01", null));
        assertThatThrownBy(() -> quests.claim(playerId, new QuestClaimReq(requestId, "quest_side_01", null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).code())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED.code());
    }

    @Test
    @DisplayName("跨天清零：昨天领过的每日任务，今天应当回到 0/未领（否则白送一次完成态）")
    void dailyQuestResetsAfterADayBoundary() {
        String playerId = newPlayer();
        long now = timeService.serverNow();
        // 播种一份"昨天已经做完并领过"的账本：每日任务的进度与领取状态都在，但 dayKey 是昨天的
        QuestProgressStore.State stale = new QuestProgressStore.State(playerId,
                List.of(new QuestProgress.Entry("quest_daily_01", QuestProgress.QuestType.DAILY,
                        GoalType.KILL_MONSTER, null, 3L, null, 3L, true)),
                DayKey.of(now - 2L * 24 * 3600L * 1000L),
                WeekKey.of(now - 8L * 24 * 3600L * 1000L));
        questStore.save(playerId, stale);

        QuestView daily = byId(quests.list(playerId), "quest_daily_01");
        assertThat(daily.current()).as("每日任务跨天清零").isZero();
        assertThat(daily.claimed()).as("领取状态也要清 —— 不清的话今天就不能再领了").isFalse();
        assertThat(daily.complete()).isFalse();
    }

    @Test
    @DisplayName("抽卡按次数发布 GACHA_PULL（十连 += 10，目标=池 id）")
    void gachaPublishesPullProgress() {
        String playerId = newPlayer();
        fundGold(playerId, 10_000L);
        List<com.ironoath.core.event.GameEvent> captured = new java.util.ArrayList<>();
        bus.subscribe(GoalType.GACHA_PULL, captured::add);

        gachaAppService.draw(playerId, new com.ironoath.web.dto.generated.GachaDrawReq(
                newId(), "gacha_pool_newbie", 1));

        assertThat(captured).as("一次单抽 = 一个事件").hasSize(1);
        assertThat(captured.get(0).playerId()).isEqualTo(playerId);
        assertThat(captured.get(0).targetId())
                .as("目标必须是这一池：主线「寻访第一位武将」盯的就是新手池").isEqualTo("gacha_pool_newbie");
        assertThat(captured.get(0).amount()).as("单抽 +1").isEqualTo(1L);
    }

    @Test
    @DisplayName("互助帮助发布 HELP_SQUAD（帮一次 +1，目标是帮人的人）")
    void helpPublishesHelpProgress() {
        String helper = newPlayer();
        String owner = newPlayer();
        TestSquads.leaderOf(socialStore, owner);
        long now = timeService.serverNow();
        helpRegistrar.register("help-quest-test", owner,
                com.ironoath.web.dto.generated.HelpTargetKind.BUILDING, "inst_quest",
                "伐木场升到 2 级", now + 600_000L, now);
        List<com.ironoath.core.event.GameEvent> captured = new java.util.ArrayList<>();
        bus.subscribe(GoalType.HELP_SQUAD, captured::add);

        social.help(helper, "help-quest-test", now);

        assertThat(captured).as("帮了一次就发一个事件").hasSize(1);
        assertThat(captured.get(0).playerId())
                .as("进度记在帮忙的人头上（不是被帮的人）").isEqualTo(helper);
        assertThat(captured.get(0).amount()).isEqualTo(1L);
    }

    // ---------- 首日武将（B06 §1「主线赠送：首日必得 1 名 SR」） ----------

    @Test
    @DisplayName("选将 HTTP 下发三候选真实基础三维、技能中文效果及特点，不复制设计 why")
    void heroChoicesExposeRealBaseStatsAndPlayerSkillInformation() throws Exception {
        String playerId = newPlayer();
        JsonNode choices = firstHeroChoicesHttp(playerId);
        assertThat(choices.size()).isEqualTo(3);
        long[][] expectedStats = {{70, 65, 60}, {65, 70, 60}, {60, 65, 70}};
        String[] expectedNames = {"卫无咎", "沈砚秋", "崔明烛"};
        String[] expectedMain = {"无咎守", "砚秋雨", "明烛照"};
        String[] expectedSub = {"顽抗", "包扎", "破甲"};
        String[] expectedDescriptions = {
                "回合开始时，35%概率：提升己方防御25%；持续2回合。",
                "每回合，30%概率：回复己方损失兵力的15%。",
                "受击时，25%概率：额外伤害系数60%。"};
        String[] expectedFeatures = {
                "主技能：提升防御；副技能：提升防御",
                "主技能：回复损失兵力；副技能：回复损失兵力",
                "主技能：额外伤害；副技能：降低敌方防御"};
        for (int i = 0; i < choices.size(); i++) {
            JsonNode choice = choices.get(i);
            assertThat(choice.path("heroId").asText()).isEqualTo("hero_sr_0" + (i + 1));
            assertThat(choice.path("name").asText()).isEqualTo(expectedNames[i]);
            assertThat(choice.path("rarity").asText()).isEqualTo("SR");
            assertThat(choice.path("might").longValue()).isEqualTo(expectedStats[i][0]);
            assertThat(choice.path("command").longValue()).isEqualTo(expectedStats[i][1]);
            assertThat(choice.path("wisdom").longValue()).isEqualTo(expectedStats[i][2]);
            assertThat(choice.path("mainSkillName").asText()).isEqualTo(expectedMain[i]);
            assertThat(choice.path("subSkillName").asText()).isEqualTo(expectedSub[i]);
            assertThat(choice.path("mainSkillDescription").asText()).isEqualTo(expectedDescriptions[i]);
            assertThat(choice.path("subSkillDescription").asText()).isNotBlank();
            assertThat(choice.path("featureText").asText()).isEqualTo(expectedFeatures[i]);
            assertThat(choice.has("why")).isFalse();
            assertSafeHeroChoiceDisplay(choice);
        }
        assertThat(heroes.findByPlayerId(playerId))
                .as("打开候选不会自动发将").isEmpty();
    }

    @Test
    @DisplayName("候选显示响应读取实际配置变更，属性非静态姓名映射、定点百分比不舍入丢精度")
    void heroChoiceInformationFollowsRuntimeHeroAndSkillConfiguration() throws Exception {
        ObjectNode originalHero = configTableForChoiceTest("hero");
        ObjectNode originalSkill = configTableForChoiceTest("skill");
        ObjectNode changedHero = originalHero.deepCopy();
        ObjectNode changedSkill = originalSkill.deepCopy();
        ObjectNode hero = configRowForChoiceTest(changedHero, "hero_sr_01");
        hero.put("name", "临时候选名").put("might", 77L).put("command", 66L).put("wisdom", 55L);
        ObjectNode skill = configRowForChoiceTest(changedSkill, "skill_sr_wu_jiu_shou");
        skill.put("name", "临时守护").put("chance", "0.1234").put("value", "0.2345");
        try {
            configs.reload("hero", HeroCfg.class, changedHero.toString());
            configs.reload("skill", SkillCfg.class, changedSkill.toString());
            JsonNode choice = firstHeroChoicesHttp(newPlayer()).get(0);
            assertThat(choice.path("name").asText()).isEqualTo("临时候选名");
            assertThat(choice.path("might").longValue()).isEqualTo(77L);
            assertThat(choice.path("command").longValue()).isEqualTo(66L);
            assertThat(choice.path("wisdom").longValue()).isEqualTo(55L);
            assertThat(choice.path("mainSkillName").asText()).isEqualTo("临时守护");
            assertThat(choice.path("mainSkillDescription").asText())
                    .isEqualTo("回合开始时，12.34%概率：提升己方防御23.45%；持续2回合。");
            assertSafeHeroChoiceDisplay(choice);
        } finally {
            configs.reload("hero", HeroCfg.class, originalHero.toString());
            configs.reload("skill", SkillCfg.class, originalSkill.toString());
        }
    }

    @Test
    @DisplayName("真实 HTTP 拒印 raw-id 名字；缺武将的显示工厂回退不替代装配器合法候选校验")
    void missingHeroChoiceConfigurationNeverDisplaysAnInternalId() throws Exception {
        ObjectNode original = configTableForChoiceTest("hero");
        ObjectNode changed = original.deepCopy();
        configRowForChoiceTest(changed, "hero_sr_01").put("name", "hero_sr_01");
        try {
            configs.reload("hero", HeroCfg.class, changed.toString());
            JsonNode choices = firstHeroChoicesHttp(newPlayer());
            assertThat(choices.get(0).path("heroId").asText()).isEqualTo("hero_sr_01");
            assertThat(choices.get(0).path("name").asText()).isEqualTo("未知武将");
            assertThat(choices.get(0).path("might").longValue()).isEqualTo(70L);
            assertSafeHeroChoiceDisplay(choices.get(0));
            assertThat(choices.get(1).path("name").asText()).isEqualTo("沈砚秋");

            // 非法删候选仍被原装配器拒绝。此处只验实际生产显示工厂的 missing-config 分支，
            // 不把工厂单测冒充非法配置整页 HTTP 可用或领奖合法。
            configRowForChoiceTest(changed, "hero_sr_01").put("id", "hero_unavailable_choice");
            configs.reload("hero", HeroCfg.class, changed.toString());
            Object view = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    quests, "heroChoiceView", "hero_sr_01", "quest_main_01");
            JsonNode choice = JsonUtils.readTree(JsonUtils.toJson(view));
            assertThat(choice.path("heroId").asText()).isEqualTo("hero_sr_01");
            assertThat(choice.path("name").asText()).isEqualTo("未知武将");
            for (String field : List.of("rarity", "might", "command", "wisdom")) {
                assertThat(choice.path(field).isNull()).as("缺真实值不编造属性：%s", field).isTrue();
            }
            assertThat(choice.path("mainSkillDescription").asText()).isEqualTo("技能信息暂不可用");
            assertThat(choice.path("featureText").asText()).isEqualTo("技能信息暂不可用");
            assertSafeHeroChoiceDisplay(choice);
        } finally {
            configs.reload("hero", HeroCfg.class, original.toString());
        }
    }

    @Test
    @DisplayName("缺单技能与未实现硬控只回退该说明，另一技能及真实三维仍可比较")
    void unavailableSkillInformationDoesNotInventAnEffectOrHideOtherStats() throws Exception {
        ObjectNode original = configTableForChoiceTest("skill");
        ObjectNode changed = original.deepCopy();
        configRowForChoiceTest(changed, "skill_sr_wu_jiu_shou").put("id", "skill_unavailable_choice");
        configRowForChoiceTest(changed, "skill_sr_yan_qiu_yu").put("effect", "SKIP_TURN");
        try {
            configs.reload("skill", SkillCfg.class, changed.toString());
            JsonNode choices = firstHeroChoicesHttp(newPlayer());
            JsonNode first = choices.get(0);
            assertThat(first.path("name").asText()).isEqualTo("卫无咎");
            assertThat(first.path("might").longValue()).isEqualTo(70L);
            assertThat(first.path("mainSkillName").asText()).isEqualTo("未知技能");
            assertThat(first.path("mainSkillDescription").asText()).isEqualTo("技能信息暂不可用");
            assertThat(first.path("subSkillName").asText()).isEqualTo("顽抗");
            assertThat(first.path("subSkillDescription").asText())
                    .isEqualTo("受击时，15%概率：提升己方防御12%；持续1回合。");
            assertThat(choices.get(1).path("mainSkillDescription").asText()).isEqualTo("技能信息暂不可用");
            assertSafeHeroChoiceDisplay(first);
            assertSafeHeroChoiceDisplay(choices.get(1));
        } finally {
            configs.reload("skill", SkillCfg.class, original.toString());
        }
    }

    @Test
    @DisplayName("首日送将：领 quest_main_01 时从候选里挑一名，那名武将真的进武将册（这条链此前是死的）")
    void claimingTheFirstMainQuestGrantsTheChosenHero() {
        String playerId = newPlayer();
        // 前置：这条链路的关键在「拿到武将之前训不了兵」，所以先确认新号确实是 troopCap=0
        assertThat(heroes.findByPlayerId(playerId).map(r -> r.heroes().size()).orElse(0))
                .as("新号一个武将都没有 —— 这正是 quest_main_03（训练 20 个 T1）做不完的原因")
                .isZero();
        assertThat(heroAppService.troopCap(playerId))
                .as("没有武将 ⇒ 带兵上限为 0（canTrain 会以「超出带兵上限」拒绝）").isZero();

        List<com.ironoath.web.dto.generated.HeroChoice> candidates =
                byId(quests.list(playerId), "quest_main_01").heroChoices();
        assertThat(candidates).as("首条主线必须带候选列表（否则客户端根本不知道要挑）")
                .extracting(com.ironoath.web.dto.generated.HeroChoice::heroId)
                .containsExactly("hero_sr_01", "hero_sr_02", "hero_sr_03");
        assertThat(candidates)
                .as("候选必须带名字（客户端不查表、不翻译 —— 名字来自 hero 表）")
                .extracting(com.ironoath.web.dto.generated.HeroChoice::name)
                .containsExactly("卫无咎", "沈砚秋", "崔明烛");

        seedComplete(playerId, "quest_main_01");
        String picked = candidates.get(1).heroId();
        QuestClaimResp claimed = quests.claim(playerId,
                new QuestClaimReq(newId(), "quest_main_01", picked));

        assertThat(heroes.findByPlayerId(playerId).orElseThrow().heroes().stream()
                .map(com.ironoath.core.hero.HeroInstance::heroId))
                .as("选中的武将必须真的进武将册 —— 只回执不落库就是假发放")
                .containsExactly(picked);
        assertThat(claimed.rewards().stream().filter(r -> r.type().equals("HERO")).count())
                .as("回执里要有那条整卡奖励（客户端据此弹「获得 沈砚秋」）").isEqualTo(1L);
        // 注意这里不断言 troopCap：带兵上限取的是「上阵」武将的统帅值（B06 §4），
        // 而拿到武将 != 已上阵（上阵是玩家自己的编队动作）。首日链路要打通还差「自动上阵」这一步 ——
        // 见收口清单 #98，本用例只钉住「赠送真的落地」这一段
    }

    @Test
    @DisplayName("三选一的三条拒绝：有候选不挑 / 挑不在候选里的 / 没候选却挑 —— 一律不许默认替玩家选")
    void heroChoiceIsValidatedInsteadOfDefaulted() {
        String playerId = newPlayer();
        // 一次播完两条再断言：seedComplete 是按"空账本 + 一条完成态"写的，
        // 分两次调会把前一次的完成态覆盖掉（夹具的形状，不是实现的行为）
        seedComplete(playerId, "quest_main_01", "quest_side_01");

        assertThatThrownBy(() -> quests.claim(playerId,
                new QuestClaimReq(newId(), "quest_main_01", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("候选")
                .extracting(e -> ((BizException) e).code())
                .as("有候选却不挑：替玩家默认挑一个等于「三选一」变成「系统选中一个」")
                .isEqualTo(ErrorCode.PARAM_INVALID.code());

        assertThatThrownBy(() -> quests.claim(playerId,
                new QuestClaimReq(newId(), "quest_main_01", "hero_ssr_01")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).code())
                .as("挑了不在候选里的（SSR 不在首日候选内）：放行等于候选列表形同虚设")
                .isEqualTo(ErrorCode.PARAM_INVALID.code());

        // 没候选的任务传了选择：也拒（静默忽略会让客户端以为自己选上了）
        assertThatThrownBy(() -> quests.claim(playerId,
                new QuestClaimReq(newId(), "quest_side_01", "hero_sr_01")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).code())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());

        // 被拒的这几次都不能留下任何痕迹：武将没进册、任务仍可再领
        assertThat(heroes.findByPlayerId(playerId).map(r -> r.heroes().size()).orElse(0))
                .as("三次被拒之后仍是一名武将都没有").isZero();
        assertThat(byId(quests.list(playerId), "quest_main_01").claimable())
                .as("被拒的领取不能把任务标成已领（否则玩家就永远拿不到这名 SR 了）")
                .isTrue();
    }

    @Test
    @DisplayName("重复获得转碎片：同一名武将第二次到手时按 hero_rarity.dupFragment 折算，不静默丢弃")
    void duplicateHeroGiftConvertsToFragments() {
        String playerId = newPlayer();
        // 先让这名武将已在册（等价于"他自己抽到过"），再走三选一送同一名
        var roster = heroes.findByPlayerId(playerId).orElseGet(com.ironoath.core.hero.HeroRoster::new);
        roster.obtain("hero_sr_02");
        if (heroes.findByPlayerId(playerId).isEmpty()) {
            heroes.insertIfAbsent(playerId, roster);
        } else {
            heroes.save(playerId, roster, heroes.versionOf(playerId));
        }

        seedComplete(playerId, "quest_main_01");
        quests.claim(playerId, new QuestClaimReq(newId(), "quest_main_01", "hero_sr_02"));

        // SR 的 dupFragment = 15（hero_rarity 表）
        assertThat(countOf(playerId, "item_mat_hero_frag_sr"))
                .as("重复获得要按档折成碎片而不是丢弃 —— 丢弃=玩家看到「获得 ×1」而册子里毫无变化")
                .isEqualTo(15L);
    }

    @Test
    @DisplayName("新手池定价：新号初始金币就抽得起首抽（300→150），不必等主线发钱")
    void newbiePoolIsAffordableForAFreshAccount() {
        String playerId = newPlayer();
        long gold = goldOf(playerId);
        long cost = configs.get(com.ironoath.config.cfg.GachaCfg.class, "gacha_pool_newbie").costCount();

        assertThat(gold).as("初始金币仍要为正（下一条是反空转下限）").isPositive();
        assertThat(cost)
                .as("新手池价格必须 <= 新号初始金币（%s），否则「抽卡解没武将」与「武将解任务卡死」互相锁死",
                        gold)
                .isLessThanOrEqualTo(gold);
    }

    @Test
    @DisplayName("新号没有武将档时也能领整卡：建档动作顺序错了会让武将进补偿队列而回执照样显示成功")
    void grantsHeroEvenWhenTheRosterFileDoesNotExistYet() {
        String playerId = newPlayer();
        // 真机实测过的一个 bug：先 insertIfAbsent(新档) 再对入参 obtain，而仓储读写都返回副本 ——
        // 落库的那份看不到这次 obtain，随后的 save 抛「武将存档不存在」，
        // 于是玩家看到「领取成功」而武将进了补偿队列。单测里夹具通常已经建好档，
        // 所以这条必须显式断言"档不存在"这个前提，否则它验不到建档那一步
        assertThat(heroes.findByPlayerId(playerId))
                .as("前提：这个号连武将档都还没有（否则本条用例验不到建档那一步）").isEmpty();

        seedComplete(playerId, "quest_main_01");
        QuestClaimResp claimed = quests.claim(playerId,
                new QuestClaimReq(newId(), "quest_main_01", "hero_sr_01"));

        assertThat(claimed.rewards().stream().filter(r -> r.type().equals("HERO")))
                .as("整卡必须真的发出去；本任务另含一次免费粮礼，不能把全部奖励条数当整卡数")
                .singleElement().satisfies(reward -> {
                    assertThat(reward.id()).isEqualTo("hero_sr_01");
                    assertThat(reward.count()).isEqualTo(1L);
                });
        assertThat(heroes.findByPlayerId(playerId).orElseThrow().heroes().stream()
                .map(com.ironoath.core.hero.HeroInstance::heroId))
                .as("武将必须落在册子里").containsExactly("hero_sr_01");
    }

    // ---------- 辅助 ----------

    private JsonNode firstHeroChoicesHttp(String playerId) throws Exception {
        String body = mockMvc.perform(get("/quest/list").header("X-Player-Id", playerId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode response = JsonUtils.readTree(body);
        assertThat(response.path("code").asInt()).isZero();
        for (JsonNode quest : response.path("data").path("quests")) {
            if (quest.path("questId").asText().equals("quest_main_01")) return quest.path("heroChoices");
        }
        throw new AssertionError("真实 HTTP 任务面板缺首条主线");
    }

    private static void assertSafeHeroChoiceDisplay(JsonNode choice) {
        for (String field : List.of("name", "mainSkillName", "subSkillName", "mainSkillDescription",
                "subSkillDescription", "featureText")) {
            assertThat(choice.path(field).asText()).as("玩家文案：%s", field)
                    .doesNotContain("hero_", "skill_", "ROUND_START", "ON_HIT", "BUFF_DEF", "SKIP_TURN", "零氪");
        }
    }

    private static ObjectNode configTableForChoiceTest(String name) throws java.io.IOException {
        java.nio.file.Path cursor = java.nio.file.Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cursor != null; i++, cursor = cursor.getParent()) {
            java.nio.file.Path file = cursor.resolve("contract/config/" + name + ".json");
            if (java.nio.file.Files.isRegularFile(file)) {
                return (ObjectNode) JsonUtils.readTree(java.nio.file.Files.readString(file));
            }
        }
        throw new AssertionError("隔离 worktree 缺配置表 " + name);
    }

    private static ObjectNode configRowForChoiceTest(ObjectNode table, String id) {
        for (JsonNode row : (ArrayNode) table.path("rows")) {
            if (row.path("id").asText().equals(id)) return (ObjectNode) row;
        }
        throw new AssertionError("缺测试配置行 " + id);
    }

    /**
     * 把某条任务播成"已完成未领取"。累加型的进度由事件推动，这里只制造前置状态。
     *
     * <p>两类目标要用<b>不同的工厂</b>构造事件（{@code GameEvent} 在构造期就拒绝混用），
     * 这也是播种时必须按类型分派的原因。
     *
     * <p><b>可变参数是必需的</b>：本方法每次都从"空账本"起播，分两次调用会把前一次的完成态覆盖掉
     * （{@code openFresh} 是空账本）。要多条任务同时处于完成态，只能一次传完。
     */
    private void seedComplete(String playerId, String... questIds) {
        long now = timeService.serverNow();
        QuestProgress progress = openFresh(playerId);
        for (String questId : questIds) {
            QuestProgress.Entry entry = progress.entry(questId);
            long target = Math.max(1L, entry.goalValue());
            progress.onEvent(entry.goalType().accumulates()
                    ? com.ironoath.core.event.GameEvent.progress(playerId, entry.goalType(),
                            entry.goalTarget(), target, now)
                    : com.ironoath.core.event.GameEvent.state(playerId, entry.goalType(),
                            entry.goalTarget(), target, now));
        }
        questStore.save(playerId, new QuestProgressStore.State(playerId, progress.entries(),
                progress.dayKey(), progress.weekKey()));
    }

    /** 播种用的空账本（按当前表装配 + 当前日/周键）。 */
    private QuestProgress openFresh(String playerId) {
        long now = timeService.serverNow();
        return QuestProgress.open(playerId, assembler.quests().stream()
                .map(com.ironoath.web.quest.QuestRulesAssembler.QuestDef::def).toList(),
                DayKey.of(now), WeekKey.of(now));
    }

    private static QuestView byId(QuestListResp resp, String questId) {
        return resp.quests().stream().filter(row -> row.questId().equals(questId))
                .findFirst().orElseThrow(() -> new AssertionError("面板里没有任务 " + questId));
    }

    private String newPlayer() {
        String playerId = playerInitService.init(new PlayerInitReq(
                newId(), "dev-" + UUID.randomUUID(), "任务测试", 1_700_000_000_000L, "")).playerId();
        return playerId;
    }

    private static String newId() {
        return "req-" + UUID.randomUUID();
    }

    /** 给足金币（抽卡与商店都要花它）。 */
    private void fundGold(String playerId, long amount) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        var gold = save.resource("GOLD");
        save.putResource("GOLD", new com.ironoath.core.player.PlayerResourceState(
                gold.current() + amount, gold.cap(), gold.protectedAmount(), gold.perHour(),
                gold.lastSettle()));
        players.save(save);
    }

    private long goldOf(String playerId) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        return save.resource("GOLD").current();
    }

    private long countOf(String playerId, String itemId) {
        return inventories.findByPlayerId(playerId).map(inv -> inv.countOf(itemId)).orElse(0L);
    }
}
