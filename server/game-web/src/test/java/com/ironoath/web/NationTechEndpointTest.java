package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.NationTechCfg;
import com.ironoath.core.city.CityState;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.player.PlayerTech;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.resource.ResourceOutputCalculator;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceIdReq;
import com.ironoath.web.dto.generated.AllianceReviewReq;
import com.ironoath.web.dto.generated.NationFoundReq;
import com.ironoath.web.dto.generated.NationTechResearchReq;
import com.ironoath.web.dto.generated.NationTreasurySpendReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.TreasuryPayeeType;
import com.ironoath.web.dto.generated.TreasurySink;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.ResourceRateService;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemoryNationStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

/**
 * 职责：国家科技两个端点的集成验收（B20 块③ 验收 5：国库扣减 + 核销日志 + 全成员生效）。
 * 依赖：MockMvc + test profile（内存存储）；{@link ResourceRateService}（用来证明"加成真的走到了成员身上"）。
 *
 * <p><b>为什么这一族断的全是精确数</b>（1050、1281、400 万分比、余额少 1050）：花的是<b>公共钱</b>。
 * "扣了钱"与"等级 +1"这两条各自都成立、但金额算错的那种缺陷，只有把金额钉成数才会红 ——
 * 而公共账目算错的代价是国与国之间的强弱变了，没人能从"看起来对"的数字里看出来。
 *
 * <p><b>为什么这里能验正向链路而科技那一域不能</b>：个人科技要学院（主城 6 级 + 资源 + 真等），
 * 而国家科技的门槛是"建国"，夹具里 16 级 + 一个联盟就到位了 —— 所以国库这条链在 MockMvc 里全程真跑，
 * 不是靠后门绕开的（仓库不许有跳门槛的端点）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NationTechEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";
    /** 农学那一行：国家 Lv1 就开放，每级 +4% 粮产，基数 1050（costBaseTreasury）。 */
    private static final String GRAIN_ROW = "nt_agri_grain";
    /** 军事那一行：要国家 Lv2。 */
    private static final String TRAIN_ROW = "nt_mil_train";
    private static final long LEVEL_ONE_COST = 1_050L;
    /** 1050 × 1.22 = 1281 —— 曲线的第二项，钉住"花费随等级递增"这件事真的来自表与曲线。 */
    private static final long LEVEL_TWO_COST = 1_281L;
    /** 每级 +4% = 400 万分比（表里的 effectValue 原样，一级的合计）。 */
    private static final long GRAIN_PERCENT_ONE_LEVEL = 400L;

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private SocialStore socialStore;
    @Autowired private InMemoryNationStore nationStore;
    @Autowired private ResourceRateService resourceRates;
    @Autowired private ConfigRegistry configs;

    private int allianceSeq;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        socialStore.clear();
        nationStore.clear();
        allianceSeq = 0;
    }

    // ---------- 读 ----------

    @Test
    @DisplayName("整棵树来自表：4 行、花费取自该行基数与曲线，Lv2 那两行的拦因是国家等级而不是国库")
    void treeComesFromTheTableAndLvGatedRowsSayWhyTheyAreLocked() throws Exception {
        Fixture f = nation();

        JsonNode view = get200("/nation/tech", f.king);
        assertThat(view.get("techs")).hasSize(configs.all(NationTechCfg.class).size()).hasSize(4);
        assertThat(view.get("nationLevel").asInt()).as("刚建起来的国家是 1 级").isEqualTo(1);
        assertThat(view.get("treasury").asLong()).as("读之前顺带结清了周税").isPositive();
        assertThat(view.get("serverNow").asLong()).isPositive();

        JsonNode grain = row(view, GRAIN_ROW);
        assertThat(grain.get("level").asInt()).isZero();
        assertThat(grain.get("name").asText())
                .as("名字从表里来，客户端不硬编码").isEqualTo(table(GRAIN_ROW).name());
        assertThat(grain.get("nextCostTreasury").asLong())
                .as("1 级的价格就是表里的 costBaseTreasury，代码里不该有第二个 1050")
                .isEqualTo(LEVEL_ONE_COST);
        assertThat(grain.get("canResearch").asBoolean()).isTrue();
        assertThat(grain.get("blockedReason").asText()).isEqualTo("NONE");

        JsonNode train = row(view, TRAIN_ROW);
        assertThat(train.get("requireNationLevel").asInt()).isEqualTo(2);
        assertThat(train.get("canResearch").asBoolean())
                .as("钱够不够与这一行无关：它连等级门槛都没过").isFalse();
        assertThat(train.get("blockedReason").asText()).isEqualTo("NATION_LOW");
    }

    // ---------- 写：花的是公共钱 ----------

    @Test
    @DisplayName("研究一级：国库精确少 1050、流水落点是 sink:NATIONAL_TECH、等级进账本、下一级价按曲线涨")
    void researchPaysTheTreasuryThroughTheNationalTechSink() throws Exception {
        Fixture f = nation();
        long before = treasury(f.king);

        JsonNode resp = post200("/nation/tech/research", f.king,
                new NationTechResearchReq(newRequestId(), GRAIN_ROW));
        assertThat(resp.get("techId").asText()).isEqualTo(GRAIN_ROW);
        assertThat(resp.get("level").asInt()).isEqualTo(1);
        assertThat(resp.get("costTreasury").asLong()).isEqualTo(LEVEL_ONE_COST);
        assertThat(resp.get("treasuryAfter").asLong()).isEqualTo(before - LEVEL_ONE_COST);

        assertThat(treasury(f.king))
                .as("响应里的余额与国库读到的必须是同一个数（两处各读一次就会有一个是旧的）")
                .isEqualTo(before - LEVEL_ONE_COST);
        JsonNode log = latestLog(f.king);
        assertThat(log.get("counterparty").asText())
                .as("流水里那一列叫 counterparty，落点必须是核销而不是某个玩家")
                .isEqualTo("sink:NATIONAL_TECH");
        assertThat(log.get("amount").asLong()).isEqualTo(LEVEL_ONE_COST);
        assertThat(log.get("reason").asText())
                .as("用途里必须写明研究的是哪一行，否则这本账无法追责")
                .contains(GRAIN_ROW);

        JsonNode again = row(get200("/nation/tech", f.king), GRAIN_ROW);
        assertThat(again.get("level").asInt()).isEqualTo(1);
        assertThat(again.get("nextCostTreasury").asLong())
                .as("第 2 级的价格是 1050 × 1.22 = 1281：花费随等级递增，且只有一条算式").isEqualTo(LEVEL_TWO_COST);
    }

    @Test
    @DisplayName("同一个 requestId 重投只研究一级、只扣一次国库")
    void researchIsIdempotentOnRequestId() throws Exception {
        Fixture f = nation();
        long before = treasury(f.king);
        String key = newRequestId();

        post200("/nation/tech/research", f.king, new NationTechResearchReq(key, GRAIN_ROW));
        assertThat(treasury(f.king)).isEqualTo(before - LEVEL_ONE_COST);

        JsonNode second = postRaw("/nation/tech/research", f.king,
                new NationTechResearchReq(key, GRAIN_ROW));
        assertThat(second.get("code").asInt())
                .as("弱网重投不该把公共钱再扣一次").isEqualTo(ErrorCode.REQUEST_DUPLICATED.code());
        assertThat(treasury(f.king)).as("余额不再动").isEqualTo(before - LEVEL_ONE_COST);
        assertThat(row(get200("/nation/tech", f.king), GRAIN_ROW).get("level").asInt())
                .as("等级也不该被抬两次").isEqualTo(1);
    }

    @Test
    @DisplayName("无官职的成员：面板每行都写 NOT_OFFICER，点下去被权限码拒绝，国库一分不少")
    void memberWithoutOfficeCannotResearch() throws Exception {
        Fixture f = nation();
        long before = treasury(f.king);

        JsonNode asMate = get200("/nation/tech", f.mate);
        // 先钉住"有行"，下面那条逐行断言才不是空判：表里 4 项，成员看到的也必须是 4 项
        // （与上面 `treeComesFromTheTable…` 那条同一口径，#342 同族）。
        assertThat(asMate.get("techs")).as("面板必须把 4 项都画出来").hasSize(4);
        assertThat(asMate.get("techs")).allSatisfy(one -> assertThat(one.get("blockedReason").asText())
                .as("对没有权限的人，每一行的拦因都是权限而不是钱（他连花的资格都没有）").isNotEqualTo("NONE"));
        assertThat(row(asMate, GRAIN_ROW).get("canResearch").asBoolean()).isFalse();

        JsonNode denied = postRaw("/nation/tech/research", f.mate,
                new NationTechResearchReq(newRequestId(), GRAIN_ROW));
        assertThat(denied.get("code").asInt())
                .as("权限失败走社交域那枚既有码，而不是凭「研究」新造一枚")
                .isEqualTo(ErrorCode.SOCIAL_PERMISSION_DENIED.code());
        assertThat(denied.get("detail").asText()).contains("RESEARCH_NATION_TECH");
        assertThat(treasury(f.king)).as("被拒的写不能留下任何痕迹").isEqualTo(before);
        assertThat(row(get200("/nation/tech", f.king), GRAIN_ROW).get("level").asInt()).isZero();
    }

    @Test
    @DisplayName("国家等级不足的那两行：拒绝码专门一枚，且国库分文未动")
    void aLockedRowFailsWithItsOwnCodeAndPaysNothing() throws Exception {
        Fixture f = nation();
        long before = treasury(f.king);

        JsonNode denied = postRaw("/nation/tech/research", f.king,
                new NationTechResearchReq(newRequestId(), TRAIN_ROW));
        assertThat(denied.get("code").asInt())
                .as("与「国库没钱」分开：这人该去做的是把联盟拉进来，而不是等国库进钱")
                .isEqualTo(ErrorCode.NATION_TECH_NATION_LEVEL_LOW.code());
        assertThat(denied.get("detail").asText()).contains("2 级国家");
        assertThat(treasury(f.king)).isEqualTo(before);
    }

    @Test
    @DisplayName("国库不够这一笔时复用既有那枚国库码（不为同一件事再造第二枚）")
    void anEmptyTreasuryRefusesWithTheExistingTreasuryCode() throws Exception {
        Fixture f = nation();
        long balance = treasury(f.king);
        // 国王不受周限额，所以这一笔清空能走通：把"没钱"与"超额度"两种失败干净地分开
        post200("/nation/treasury/spend", f.king, new NationTreasurySpendReq(
                newRequestId(), TreasuryPayeeType.SINK, null, TreasurySink.WAR_BOOST,
                balance, "清空国库以验证余额不足那一支"));
        assertThat(treasury(f.king)).isZero();

        JsonNode denied = postRaw("/nation/tech/research", f.king,
                new NationTechResearchReq(newRequestId(), GRAIN_ROW));
        assertThat(denied.get("code").asInt())
                .as("§五③ 要求「复用既有国库码时先对齐语义」，对齐结果就是这一枚")
                .isEqualTo(ErrorCode.NATION_TREASURY_NOT_ENOUGH.code());
        assertThat(row(get200("/nation/tech", f.king), GRAIN_ROW).get("blockedReason").asText())
                .as("面板与写路径读的是同一个判定，所以这里也必须是 TREASURY_LOW")
                .isEqualTo("TREASURY_LOW");
    }

    // ---------- 全成员生效 ----------

    @Test
    @DisplayName("研究完的粮产加成落到普通成员身上：产率明细里的科技行就是 400 万分比")
    void theNationalGrainBonusReachesAnOrdinaryMember() throws Exception {
        Fixture f = nation();
        assertThat(grainTechPercent(f.mate)).as("研究之前成员没有这一档").isZero();

        post200("/nation/tech/research", f.king, new NationTechResearchReq(newRequestId(), GRAIN_ROW));

        assertThat(grainTechPercent(f.mate))
                .as("国王花的公共钱，加成落在每个成员头上 —— 这就是「全成员生效」的可执行表达")
                .isEqualTo(GRAIN_PERCENT_ONE_LEVEL);
        assertThat(grainTechPercent(f.king))
                .as("发起人也走同一条读取口，不是给自己单开一份").isEqualTo(GRAIN_PERCENT_ONE_LEVEL);
        assertThat(grainTechPercent(nation().mate))
                .as("另一个国家的成员不该吃到这份（读的是自己那本账）").isZero();
    }

    // ---------- 夹具 ----------

    private record Fixture(String king, String mate, String nationId) {
    }

    /** 一个刚建好、还没研究过任何科技的国家：盟主 + 一名无官职成员。 */
    private Fixture nation() throws Exception {
        String king = newPlayer(16);
        String mate = newPlayer(16);
        createAlliance(king, mate);
        String nationId = post200("/nation/found", king,
                new NationFoundReq(newRequestId(), "工造王国" + (++allianceSeq), 100L, 200L))
                .get("nation").get("nationId").asText();
        return new Fixture(king, mate, nationId);
    }

    /**
     * 某个玩家眼里"粮产的科技加成行是多少万分比"。
     *
     * <p>走的是生产的 {@link ResourceRateService#compute}（不是直接调那个折叠器）：
     * 这样断的是<b>产率算式真的把国家那一份加进去了</b>，而不是折叠器自己返回了一个数。
     */
    private long grainTechPercent(String playerId) {
        ResourceOutputCalculator.Breakdown grain = resourceRates
                .compute(new CityState(), PlayerTech.empty(), playerId)
                .breakdowns().get(ResourceIds.GRAIN);
        return grain.lines().stream().filter(l -> "科技加成".equals(l.source()))
                .findFirst().orElseThrow().percentFixed();
    }

    private NationTechCfg table(String rowId) {
        return configs.get(NationTechCfg.class, rowId);
    }

    private long treasury(String playerId) throws Exception {
        return get200("/nation/treasury", playerId).get("balance").asLong();
    }

    private JsonNode latestLog(String playerId) throws Exception {
        JsonNode logs = get200("/nation/treasury", playerId).get("logs");
        assertThat(logs).as("国库流水不能是空的：那本账的存在理由就是看得见").isNotEmpty();
        return logs.get(0);
    }

    private JsonNode row(JsonNode view, String techId) {
        for (JsonNode node : view.get("techs")) {
            if (techId.equals(node.get("techId").asText())) {
                return node;
            }
        }
        throw new AssertionError("下发的树里没有这一行: " + techId);
    }

    private String newPlayer(int cityLevel) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "国家科技测试",
                1_700_000_000_000L, "")).playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(cityLevel);
        PlayerResourceState gold = save.resources().get("GOLD");
        if (gold != null) {
            save.putResource("GOLD", new PlayerResourceState(100_000L, gold.cap(),
                    gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        }
        players.save(save);
        return playerId;
    }

    private String createAlliance(String leader, String mate) throws Exception {
        allianceSeq++;
        String allianceId = post200("/alliance/create", leader, new AllianceCreateReq(
                newRequestId(), "科技国联盟" + allianceSeq,
                String.format("T%03d", allianceSeq % 1000))).get("alliance").get("id").asText();
        post200("/alliance/apply", mate, new AllianceIdReq(newRequestId(), allianceId));
        post200("/alliance/review", leader, new AllianceReviewReq(newRequestId(), mate, true));
        return allianceId;
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private JsonNode post200(String url, String playerId, Object req) throws Exception {
        return okData(postRaw(url, playerId, req));
    }

    private JsonNode postRaw(String url, String playerId, Object req) throws Exception {
        return perform(post(url).header(PLAYER_HEADER, playerId)
                .contentType(MediaType.APPLICATION_JSON).content(JsonUtils.toJson(req)));
    }

    private JsonNode get200(String url, String playerId) throws Exception {
        return okData(perform(get(url).header(PLAYER_HEADER, playerId)));
    }

    private JsonNode perform(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode okData(JsonNode root) {
        assertThat(root.get("code").asInt()).as("业务码必须为 0，实际响应=%s", root).isZero();
        return root.get("data");
    }

}
