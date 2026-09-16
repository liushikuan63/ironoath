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
import com.ironoath.core.quest.GoalType;
import com.ironoath.web.dto.generated.GuideAction;
import com.ironoath.web.dto.generated.GuideProgressReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.QuestClaimReq;
import com.ironoath.web.quest.QuestEvents;
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
@SpringBootTest
@AutoConfigureMockMvc
class GuideEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final String FIRST = "guide_01_upgrade_main";
    private static final String SECOND = "guide_02_claim_hero";
    private static final String THIRD = "guide_03_stock_grain";

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private QuestEvents events;
    @Autowired private ConfigRegistry configs;

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
        assertThat(root.get("detail").asText())
                .as("错误要说清是哪一步：客服拿到的不该是一句\"不能跳过\"").contains(FIRST);
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
                .as("粮食一枚没攒也能跳过去").isEqualTo(4L);
        assertThat(guideOf(playerId).stepIndex()).isEqualTo(4);
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
     * <p><b>为什么不是从头跳</b> —— 从头跳不过去（强制步不许跳），而真做过去今天做不到：
     * 主线任务的 {@code preQuest} 是链式的，第 4 步的判据 {@code quest_main_03} 的前置是
     * {@code quest_main_02}（攒 1 万粮），而新号粮食底产 {@code basePerHour=400} ⇒ 单靠自然产出约 25 小时。
     * 也就是说 B00「五分钟体验」与 B18 §五① 那份七步表在**现状配置下走不完** ——
     * 这条已记进收口清单等裁决（要么给首日一份够 1 万粮的开局库存/章节奖，要么改那条前置）。
     * 判据链本身（未达成按住、达成才推进、领奖才算第 2 步）由上面三条用例覆盖，本条只负责结束语义。
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
