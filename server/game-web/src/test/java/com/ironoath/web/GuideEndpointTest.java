package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.GuideCfg;
import com.ironoath.core.guide.GuideScript;
import com.ironoath.core.player.PlayerGuide;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.quest.GoalType;
import com.ironoath.web.dto.generated.GuideAction;
import com.ironoath.web.dto.generated.GuideProgressReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.QuestClaimReq;
import com.ironoath.web.quest.QuestEvents;
import com.ironoath.web.config.ClockSource;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：引导两个端点的端到端验收（B18 验收 1 的服务端面、验收 5、验收 8，加续传与闸门）。
 * 依赖：Spring 测试上下文（内存存储）。
 *
 * <p><b>判据一律用真路径喂</b>：第 1 步用 {@link QuestEvents}（各业务服务写路径上调的同一个入口）
 * 把 {@code quest_main_01} 做到，第 2 步用真的 {@code /quest/claim} 领奖。
 * 直接改存档里的 stepIndex 只能验"读得到"，验不到"服务端真的按任务状态判" —— 那正是验收 5 的全部内容。
 *
 * <p><b>「杀进程重进回到当前步骤」（验收 2）在这里验的是服务端那一半</b>：推进之后重新读一次存档与脚本，
 * 位置必须还在第 N 步；跨进程那一半由 {@code PlayerGuideEquivalenceTest} 在真 MongoDB 上钉。
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration")
@AutoConfigureMockMvc
@Import(GuideEndpointTest.FixedClock.class)
class GuideEndpointTest {

    /** 不推进时间：赠粮断言只量实际奖励入账，不把自然产粮算成赠礼。不开端口或 Mongo。 */
    @TestConfiguration
    static class FixedClock {
        static final long NOW = System.currentTimeMillis();

        @Bean
        @Primary
        ClockSource guideTestClock() {
            return () -> NOW;
        }
    }

    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final String FIRST = "guide_01_upgrade_main";
    private static final String SECOND = "guide_02_claim_hero";
    private static final String THIRD = "guide_03_stock_grain";

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private QuestEvents events;
    @Autowired private ConfigRegistry configs;
    @Autowired private HeroRepository heroes;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
    }

    // ---------- 验收 1：步骤全部来自表，且判据不下发 ----------

    @Test
    @DisplayName("验收 1（服务端面）：7 步的 id/顺序/文案/skippable 全部等于 guide.json 的行，且判据两个字段不在协议里")
    void scriptComesFromTheTableAndLeavesTheJudgesOut() throws Exception {
        String playerId = newPlayer();
        JsonNode steps = dataOf(request(get("/guide/script").header(PLAYER_HEADER, playerId)))
                .get("steps");
        List<GuideCfg> rows = configs.all(GuideCfg.class);

        assertThat(steps).hasSize(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            GuideCfg row = rows.get(i);
            JsonNode step = steps.get(i);
            assertThat(step.get("id").asText()).as("顺序必须等于表序（服务端不另排）").isEqualTo(row.id());
            assertThat(step.get("stepIndex").asLong()).isEqualTo(row.stepIndex());
            assertThat(step.get("text").asText()).as("文案只在表里存一份").isEqualTo(row.text());
            assertThat(step.get("skippable").asBoolean()).isEqualTo(row.skippable());
            assertThat(step.get("trigger").asText()).isEqualTo(row.trigger().name());
            assertThat(step.get("maskArea").asText()).isEqualTo(row.maskArea());
            assertThat(step.get("panelKey").asText()).isEqualTo(row.panelKey());
            assertThat(step.fieldNames()).toIterable()
                    .as("判据下发出去，将来就会有人在客户端\"顺手\"判完成（B00 铁律 3 的滑坡起点）")
                    .doesNotContain("judge", "judgeTarget", "saveProgress");
        }
    }

    @Test
    @DisplayName("拉脚本是只读的：读一次不该在存档上留下\"走过引导\"的痕迹")
    void readingTheScriptDoesNotWriteProgress() throws Exception {
        String playerId = newPlayer();
        request(get("/guide/script").header(PLAYER_HEADER, playerId));

        assertThat(guideOf(playerId)).as("读路径写进度 = 登录路径多一个存档写入者，老号会凭空长出记录")
                .isEqualTo(PlayerGuide.empty());
    }

    // ---------- 验收 5：完成判定在服务端 ----------

    @Test
    @DisplayName("验收 5：喊\"我做完了\"而任务没做到 —— 不推进、也不是错误（advanced=false 留在原步）")
    void fakeCompleteIsHeldNotRejected() throws Exception {
        String playerId = newPlayer();
        JsonNode resp = progress(playerId, FIRST, GuideAction.COMPLETE);

        assertThat(resp.get("advanced").asBoolean()).as("没升过城就喊做完：不算推进").isFalse();
        assertThat(resp.get("finished").asBoolean()).isFalse();
        assertThat(resp.get("nextStepIndex").asLong()).as("留在第 1 步等他").isEqualTo(1L);
        assertThat(guideOf(playerId).stepIndex()).as("被 held 的上报不该改动存档").isZero();
    }

    @Test
    @DisplayName("验收 5：判据成立之后同一步才真的推进（读的是任务账本，不是客户端说了什么）")
    void realProgressMakesTheSameReportAdvance() throws Exception {
        String playerId = newPlayer();
        assertThat(progress(playerId, FIRST, GuideAction.COMPLETE).get("advanced").asBoolean()).isFalse();

        upgradeMainCityTwice(playerId);

        JsonNode resp = progress(playerId, FIRST, GuideAction.COMPLETE);
        assertThat(resp.get("advanced").asBoolean()).as("主城真升过两次了才该放行").isTrue();
        assertThat(resp.get("nextStepIndex").asLong()).isEqualTo(2L);
        assertThat(guideOf(playerId)).isEqualTo(new PlayerGuide(2, null));
    }

    @Test
    @DisplayName("续传（服务端那一半）：推进之后重新读脚本，位置还在第 2 步而不是从头")
    void resumeLandsOnTheStoredStep() throws Exception {
        String playerId = newPlayer();
        upgradeMainCityTwice(playerId);
        progress(playerId, FIRST, GuideAction.COMPLETE);

        JsonNode script = dataOf(request(get("/guide/script").header(PLAYER_HEADER, playerId)));
        assertThat(script.get("nextStepIndex").asLong()).isEqualTo(2L);
        assertThat(script.get("applies").asBoolean()).as("正在走引导的人一定续得下去").isTrue();
    }

    @Test
    @DisplayName("第 2 步的判据是\"奖已领\"而不是\"任务已完成\"：没领奖就上报会被按住")
    void claimingIsWhatSatisfiesStepTwo() throws Exception {
        String playerId = newPlayer();
        upgradeMainCityTwice(playerId);
        progress(playerId, FIRST, GuideAction.COMPLETE);

        assertThat(progress(playerId, SECOND, GuideAction.COMPLETE).get("advanced").asBoolean())
                .as("任务已达标但奖还没领 —— 用 QUEST_DONE 判这一步会当场放过一个空手玩家")
                .isFalse();

        claimMainCityQuest(playerId);

        assertThat(progress(playerId, SECOND, GuideAction.COMPLETE).get("advanced").asBoolean())
                .as("领了赠将才推进（武将进册 = 主线赠送那条链的落点）").isTrue();
    }

    // ---------- 验收 8：不可跳过的步 ----------

    @Test
    @DisplayName("验收 8：对强制步发 SKIP 被 GUIDE_STEP_NOT_SKIPPABLE 拒，detail 说清是哪一步")
    void mandatoryStepRefusesSkip() throws Exception {
        String playerId = newPlayer();
        JsonNode root = request(progressBuilder(playerId, FIRST, GuideAction.SKIP));

        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.GUIDE_STEP_NOT_SKIPPABLE.code());
        // 这条断言原先写的是 contains(FIRST)（FIRST 就是步骤 id "guide_01_upgrade_main"），
        // 也就是**把红线违规钉成了规格**：detail 会经 Result 上屏（客户端读 detail ?? msg）。
        // 现在钉的是"序号要说清是哪一步"+"屏上不许出现步骤 id"，一正一负。
        assertThat(root.get("detail").asText())
                .as("错误要用序号说清是哪一步：客服拿到的不该是一句\"不能跳过\"")
                .containsPattern("第 \\d+ 步");
        assertThat(root.get("detail").asText())
                .as("内部步骤 id 不得出现在给玩家的文案里（台账 #821）")
                .doesNotContain(FIRST);
        assertThat(guideOf(playerId).stepIndex()).as("被拒之后位置不动").isZero();
    }

    @Test
    @DisplayName("可跳的步（第 3 步，被动攒粮）SKIP 直接推进，不看判据")
    void skippableStepSkipsWithoutTheJudge() throws Exception {
        String playerId = newPlayer();
        upgradeMainCityTwice(playerId);
        progress(playerId, FIRST, GuideAction.COMPLETE);
        claimMainCityQuest(playerId);
        progress(playerId, SECOND, GuideAction.COMPLETE);

        assertThat(progress(playerId, THIRD, GuideAction.SKIP).get("nextStepIndex").asLong())
                .as("SKIP 不要求粮食任务达标或领奖").isEqualTo(4L);
        assertThat(guideOf(playerId).stepIndex()).isEqualTo(4);
    }

    @Test
    @DisplayName("一次性免费新手礼：普通选将领奖令初粮 8000→16000；同键重放和第二次领奖都不再发")
    void firstHeroClaimGrantsGrainExactlyOnce() throws Exception {
        String playerId = newPlayer();
        assertThat(grainOf(playerId)).as("普通新号，未开启资源提速").isEqualTo(8000L);
        upgradeMainCityTwice(playerId);
        String claimId = requestId();

        JsonNode claimed = dataOf(claimQuest(playerId, "quest_main_01", "hero_sr_02", claimId));
        assertThat(grainOf(playerId)).as("免费赠粮必须由真实奖励发放器入玩家钱包").isEqualTo(16000L);
        assertThat(claimed.get("rewards").findValuesAsText("id")).containsExactly("GRAIN", "hero_sr_02");
        assertThat(claimed.get("rewards").get(0).get("type").asText()).isEqualTo("RESOURCE");
        assertThat(claimed.get("rewards").get(0).get("count").asLong()).isEqualTo(8000L);
        assertThat(heroes.findByPlayerId(playerId).orElseThrow().heroes())
                .extracting(com.ironoath.core.hero.HeroInstance::heroId).containsExactly("hero_sr_02");

        assertThat(code(claimQuest(playerId, "quest_main_01", "hero_sr_02", claimId)))
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED.code());
        assertThat(code(claimQuest(playerId, "quest_main_01", "hero_sr_02", requestId())))
                .isEqualTo(ErrorCode.QUEST_ALREADY_CLAIMED.code());
        assertThat(grainOf(playerId)).as("两种重复请求均不得再次赠粮").isEqualTo(16000L);
        assertThat(heroes.findByPlayerId(playerId).orElseThrow().heroes()).hasSize(1);
    }

    @Test
    @DisplayName("未完成或未作合法选将的失败领奖不能赠粮；选择合法后才发免费新手礼")
    void rejectedHeroClaimDoesNotGrantTheGift() throws Exception {
        String playerId = newPlayer();
        assertThat(code(claimQuest(playerId, "quest_main_01", "hero_sr_02", requestId())))
                .isEqualTo(ErrorCode.QUEST_NOT_COMPLETE.code());
        assertThat(grainOf(playerId)).isEqualTo(8000L);
        upgradeMainCityTwice(playerId);
        assertThat(code(claimQuest(playerId, "quest_main_01", null, requestId()))).isNotZero();
        assertThat(code(claimQuest(playerId, "quest_main_01", "hero_ssr_01", requestId()))).isNotZero();
        assertThat(grainOf(playerId)).as("选将失败不得提前发放这份资源礼").isEqualTo(8000L);
        assertThat(heroes.findByPlayerId(playerId).map(r -> r.heroes().size()).orElse(0)).isZero();
        assertThat(code(claimQuest(playerId, "quest_main_01", "hero_sr_02", requestId()))).isZero();
        assertThat(grainOf(playerId)).isEqualTo(16000L);
    }

    @Test
    @DisplayName("赠粮不代做资源教学或领粮；四种手动与领02完成后继续训20和普通领03")
    void giftLeavesTrainingAndBattleClaimPrerequisitesIntact() throws Exception {
        String playerId = reachGrainGuide();
        assertThat(questOf(playerId, "quest_main_02").get("complete").asBoolean()).isTrue();
        assertThat(questOf(playerId, "quest_main_02").get("claimed").asBoolean()).isFalse();
        assertThat(progress(playerId, THIRD, GuideAction.COMPLETE).get("nextStepIndex").asLong())
                .isEqualTo(3L);
        assertTrainingIsLockedUntilGrainClaim(playerId);

        assertThat(code(claimQuest(playerId, "quest_main_02", null, requestId()))).isZero();
        assertThat(progress(playerId, THIRD, GuideAction.COMPLETE).get("advanced").asBoolean()).isFalse();
        // 本例聚焦赠粮与领奖前置；真实逐种collect和持久化由CityHarvestLearningTest覆盖。
        var teaching = players.findByPlayerId(playerId).orElseThrow();
        teaching.recordProducedOutput(java.util.Map.of("WOOD", 1L, "STONE", 1L, "IRON", 1L, "GRAIN", 1L));
        for (String type : java.util.List.of("WOOD", "STONE", "IRON", "GRAIN")) teaching.confirmManualOutput(type);
        players.save(teaching);
        assertThat(progress(playerId, THIRD, GuideAction.COMPLETE).get("nextStepIndex").asLong()).isEqualTo(4L);
        assertThat(questOf(playerId, "quest_main_03").get("locked").asBoolean()).isFalse();
        events.progress(playerId, GoalType.TRAIN_UNIT, "unit_infantry_t1", 20L, FixedClock.NOW);
        assertThat(questOf(playerId, "quest_main_03").get("current").asLong()).isEqualTo(20L);
        assertThat(questOf(playerId, "quest_main_03").get("claimed").asBoolean()).isFalse();
        assertThat(progress(playerId, "guide_04_barracks_train", GuideAction.COMPLETE)
                .get("nextStepIndex").asLong()).isEqualTo(5L);

        events.progress(playerId, GoalType.KILL_MONSTER, "mapmonster_lv01", 1L, FixedClock.NOW);
        assertThat(questOf(playerId, "quest_main_04").get("current").asLong())
                .as("Guide4 的 QUEST_DONE 不能替普通领取03，锁着的野战事件不计入04").isZero();
        assertThat(progress(playerId, "guide_05_first_battle", GuideAction.COMPLETE)
                .get("advanced").asBoolean()).isFalse();
        assertThat(code(claimQuest(playerId, "quest_main_03", null, requestId()))).isZero();
        events.progress(playerId, GoalType.KILL_MONSTER, "mapmonster_lv01", 1L, FixedClock.NOW);
        assertThat(questOf(playerId, "quest_main_04").get("current").asLong()).isEqualTo(1L);
        assertThat(progress(playerId, "guide_05_first_battle", GuideAction.COMPLETE)
                .get("nextStepIndex").asLong()).isEqualTo(6L);
    }

    @Test
    @DisplayName("Guide3 SKIP 同样不代领02：粮食充足时训练仍锁，普通领取02后才接收训练事件")
    void skipStillRequiresTheOrdinaryGrainClaim() throws Exception {
        String playerId = reachGrainGuide();
        assertThat(progress(playerId, THIRD, GuideAction.SKIP).get("nextStepIndex").asLong()).isEqualTo(4L);
        assertThat(players.findByPlayerId(playerId).orElseThrow().manuallyCollected()).isEmpty();
        assertTrainingIsLockedUntilGrainClaim(playerId);
        assertThat(code(claimQuest(playerId, "quest_main_02", null, requestId()))).isZero();
        events.progress(playerId, GoalType.TRAIN_UNIT, "unit_infantry_t1", 20L, FixedClock.NOW);
        assertThat(progress(playerId, "guide_04_barracks_train", GuideAction.COMPLETE)
                .get("advanced").asBoolean()).isTrue();
    }

    private void assertTrainingIsLockedUntilGrainClaim(String playerId) throws Exception {
        assertThat(questOf(playerId, "quest_main_03").get("preQuestId").asText()).isEqualTo("quest_main_02");
        assertThat(questOf(playerId, "quest_main_03").get("locked").asBoolean()).isTrue();
        events.progress(playerId, GoalType.TRAIN_UNIT, "unit_infantry_t1", 20L, FixedClock.NOW);
        assertThat(questOf(playerId, "quest_main_03").get("current").asLong()).isZero();
        assertThat(code(claimQuest(playerId, "quest_main_03", null, requestId())))
                .isEqualTo(ErrorCode.QUEST_LOCKED.code());
        String currentStep = players.findByPlayerId(playerId).orElseThrow().guide().stepIndex() == 3
                ? THIRD : "guide_04_barracks_train";
        assertThat(progress(playerId, currentStep, GuideAction.COMPLETE)
                .get("advanced").asBoolean()).isFalse();
    }

    private String reachGrainGuide() throws Exception {
        String playerId = newPlayer();
        upgradeMainCityTwice(playerId);
        progress(playerId, FIRST, GuideAction.COMPLETE);
        claimMainCityQuest(playerId);
        progress(playerId, SECOND, GuideAction.COMPLETE);
        return playerId;
    }

    private long grainOf(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow().resource("GRAIN").current();
    }

    private JsonNode questOf(String playerId, String questId) throws Exception {
        for (JsonNode quest : dataOf(request(get("/quest/list").header(PLAYER_HEADER, playerId))).get("quests")) {
            if (quest.get("questId").asText().equals(questId)) return quest;
        }
        throw new AssertionError("任务未下发：" + questId);
    }

    private JsonNode claimQuest(String playerId, String questId, String heroChoice, String requestId)
            throws Exception {
        return request(post("/quest/claim").header(PLAYER_HEADER, playerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(new QuestClaimReq(requestId, questId, heroChoice))));
    }

    @Test
    @DisplayName("不按序上报回 GUIDE_STEP_OUT_OF_ORDER，脚本里没有的 id 回 GUIDE_STEP_NOT_FOUND（两种处置不同）")
    void orderingAndUnknownStepsHaveDifferentCodes() throws Exception {
        String playerId = newPlayer();

        assertThat(request(progressBuilder(playerId, THIRD, GuideAction.SKIP)).get("code").asInt())
                .as("跳过第 3 步本身合法，但现在还轮不到它")
                .isEqualTo(ErrorCode.GUIDE_STEP_OUT_OF_ORDER.code());
        assertThat(request(progressBuilder(playerId, "guide_from_an_older_script", GuideAction.COMPLETE))
                .get("code").asInt())
                .as("id 不存在说的是\"客户端脚本过期\"，与\"时机不对\"必须分得开")
                .isEqualTo(ErrorCode.GUIDE_STEP_NOT_FOUND.code());
    }

    // ---------- 走完 / 闸门 / 幂等 ----------

    /**
     * 「走完」那一位单独验：末步（第 7 步）按裁决②可跳，所以夹具把进度放到末步，再真的 SKIP 一次。
     *
     * <p>此夹具只负责结束语义，不替代训练与首战流程。首个选将任务的一次性免费赠粮、
     * 普通领取前置、训练与野战事件以及服务端 Guide4/5 判据由上面的真实领取用例覆盖。
     */
    @Test
    @DisplayName("走到末步再跳过：finished=true、nextStepIndex=null、存档写下结束时刻，重放不再推进")
    void skippingTheLastStepEndsTheGuide() throws Exception {
        String playerId = newPlayer();
        putProgress(playerId, new PlayerGuide(7, null));

        JsonNode last = progress(playerId, "guide_07_join_squad", GuideAction.SKIP);
        assertThat(last.get("finished").asBoolean()).isTrue();
        assertThat(last.get("nextStepIndex").isNull()).as("结束后不再有该做的步").isTrue();

        PlayerGuide done = guideOf(playerId);
        assertThat(done.finished()).as("结束时刻必须显式落档").isTrue();
        assertThat(done.stepIndex()).isEqualTo(7);
        assertThat(request(progressBuilder(playerId, "guide_07_join_squad", GuideAction.SKIP))
                .get("data").get("advanced").asBoolean())
                .as("重放一次结束不该再推进").isFalse();
        JsonNode after = dataOf(request(get("/guide/script").header(PLAYER_HEADER, playerId)));
        assertThat(after.get("applies").asBoolean())
                .as("走完的号不再被判定为\"该看引导\"").isFalse();
        assertThat(after.get("nextStepIndex").isNull()).isTrue();
    }

    @Test
    @DisplayName("闸门：主城超过上界且从没开始过 → applies=false 且没有续传位置；开始过则一律续得下去")
    void veteranAccountsDoNotGetTheGuide() throws Exception {
        String playerId = newPlayer();
        long cap = configs.longParam("GUIDE_APPLIES_CITY_LEVEL_MAX");
        setCityLevel(playerId, (int) cap + 1);

        JsonNode veteran = dataOf(request(get("/guide/script").header(PLAYER_HEADER, playerId)));
        assertThat(veteran.get("applies").asBoolean()).as("老号不补引导（裁决③）").isFalse();
        assertThat(veteran.get("nextStepIndex").isNull()).as("没人该做的事不下发位置").isTrue();
        assertThat(veteran.get("steps")).as("步骤照样下发：客户端不必分两套代码").isNotEmpty();

        upgradeMainCityTwice(playerId);
        progress(playerId, FIRST, GuideAction.COMPLETE);
        JsonNode inProgress = dataOf(request(get("/guide/script").header(PLAYER_HEADER, playerId)));
        assertThat(inProgress.get("applies").asBoolean())
                .as("闸门管的是\"要不要开始\"，不是\"要不要放弃\"：半路的号必须续得下去").isTrue();
        assertThat(inProgress.get("nextStepIndex").asLong()).isEqualTo(2L);
    }

    @Test
    @DisplayName("幂等键：缺键回 1003，同键重投回 1002（弱网重投不该把两步并作一步）")
    void progressRequiresAnIdempotencyKey() throws Exception {
        String playerId = newPlayer();
        String requestId = requestId();

        assertThat(code(request(playerId, FIRST, GuideAction.COMPLETE, requestId))).isZero();
        assertThat(code(request(playerId, FIRST, GuideAction.COMPLETE, requestId)))
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED.code());
        assertThat(code(request(playerId, FIRST, GuideAction.COMPLETE, "  ")))
                .isEqualTo(ErrorCode.REQUEST_ID_MISSING.code());
    }

    // ---------- 夹具 ----------

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(requestId(),
                "dev-" + UUID.randomUUID(), "引导测试", 1_700_000_000_000L, "")).playerId();
    }

    private static String requestId() {
        return "req-" + UUID.randomUUID();
    }

    /** 第 1 步的判据就是这条任务的达标：用业务服务在写路径上调的同一个入口发两次升级。 */
    private void upgradeMainCityTwice(String playerId) {
        long now = System.currentTimeMillis();
        events.progress(playerId, GoalType.UPGRADE_BUILDING, "main_city", 1L, now);
        events.progress(playerId, GoalType.UPGRADE_BUILDING, "main_city", 1L, now);
    }

    /** 领掉带三选一的那条主线任务 —— 第 2 步的判据是\"奖已领\"，所以必须真领。 */
    private void claimMainCityQuest(String playerId) throws Exception {
        JsonNode root = request(post("/quest/claim").header(PLAYER_HEADER, playerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(new QuestClaimReq(requestId(), "quest_main_01", "hero_sr_02"))));
        assertThat(root.get("code").asInt()).as("领取失败则第 2 步的判据永远不成立").isZero();
    }

    /**
     * 夹具：直接把进度写到存档上。只用于「走完」那一条（其余用例一律走真判据）。
     *
     * <p>它改的是测试进程内的内存存储 —— 生产里没有对应的写口，也没有开这种端点
     * （B00：缺口的正确表达是响亮的失败，不是后门）。
     */
    private void putProgress(String playerId, PlayerGuide guide) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setGuide(guide);
        players.save(save);
    }

    /** 夹具直接改存档等级（测试进程内的内存存储）—— 生产里没有这种改法，也没有开这种端点。 */
    private void setCityLevel(String playerId, int level) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(level);
        players.save(save);
    }

    private PlayerGuide guideOf(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow().guide();
    }

    private JsonNode progress(String playerId, String stepId, GuideAction action) throws Exception {
        return dataOf(request(progressBuilder(playerId, stepId, action)));
    }

    private int code(JsonNode root) {
        return root.get("code").asInt();
    }

    private JsonNode request(String playerId, String stepId, GuideAction action, String requestId)
            throws Exception {
        return request(post("/guide/progress").header(PLAYER_HEADER, playerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(new GuideProgressReq(stepId, action, requestId))));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder progressBuilder(
            String playerId, String stepId, GuideAction action) {
        return post("/guide/progress").header(PLAYER_HEADER, playerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(new GuideProgressReq(stepId, action, requestId())));
    }

    private JsonNode request(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode dataOf(JsonNode root) {
        return root.get("data");
    }
}
