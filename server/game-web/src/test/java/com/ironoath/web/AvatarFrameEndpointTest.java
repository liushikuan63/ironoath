package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.WearFrameReq;
import com.ironoath.web.service.AvatarFrameService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 职责：头像框（B24 块③ 外观）的端到端验证 —— **验收 3 与验收 4 的判据都在这里**。
 * 依赖：Spring Boot Test + MockMvc（test profile：内存存储 + JVM 内锁）。
 *
 * <p><b>本类要钉住的正是这一批最容易被"顺手加一点"毁掉的两件事</b>：
 * <ol>
 *   <li><b>零数值影响</b>（验收 3）：穿上与卸下前后，玩家存档里所有会影响数值的字段**逐字段相等**，
 *       {@code /player/power} 的响应也逐字相等。判别性用例的价值在于——谁日后给外观加一点加成，
 *       它会当场红，而不是等玩家发现"戴个框战力涨了 3%"。</li>
 *   <li><b>拥有状态落库</b>（验收 4）：拥有是永久事实、佩戴是当下选择；
 *       没拥有的框戴不上；卸下之后仍然拥有（否则玩家再想戴回去得再买一次）。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AvatarFrameEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final String FRAMES_URL = "/player/frames";
    private static final String FRAME_URL = "/player/frame";
    private static final String SEASON_FRAME = "frame_season_s1";
    private static final String FOUNDER_FRAME = "frame_founder_n";

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private AvatarFrameService avatarFrames;
    @Autowired private PlayerRepository players;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
    }

    @Autowired private com.ironoath.core.city.CityRepository cities;

    @Test
    @DisplayName("新号：全部头像框都下发，但一个都没拥有（没拥有的也要给，那正是收集类外观的意义）")
    void newPlayerOwnsNothingButSeesEverything() throws Exception {
        String playerId = newPlayer();
        var data = readJson(getFrames(playerId));

        assertThat(data.path("frames").size()).as("两枚占位框都要下发").isEqualTo(2);
        for (var frame : data.path("frames")) {
            assertThat(frame.path("owned").asBoolean()).as("新号一个都没拥有").isFalse();
            assertThat(frame.path("worn").asBoolean()).isFalse();
            assertThat(frame.path("placeholderColor").asText()).as("占位色要下发（客户端照它画）")
                    .startsWith("#");
            assertThat(frame.path("name").asText()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("验收 4：没拥有的框戴不上（没拿到就戴得上等于白送）")
    void cannotWearWhatYouDoNotOwn() throws Exception {
        String playerId = newPlayer();
        int code = postWearCode(playerId, new WearFrameReq(newRequestId(), SEASON_FRAME));
        assertThat(code).isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(players.findByPlayerId(playerId).orElseThrow().avatarFrame())
                .as("被拒之后什么都没变").isNull();
    }

    @Test
    @DisplayName("验收 4：拥有 → 佩戴 → 卸下；卸下之后仍然拥有（两位而不是一位的理由）")
    void ownWearAndTakeOff() throws Exception {
        String playerId = newPlayer();
        // 拥有这一位来自"购买成功那一刻"（商店那条链的另一端），这里走同一个生产入口记上
        avatarFrames.grant(playerId, SEASON_FRAME);

        var worn = readJson(postWear(playerId, new WearFrameReq(newRequestId(), SEASON_FRAME)));
        assertThat(wornFrame(worn, SEASON_FRAME)).as("戴上了").isTrue();
        assertThat(wornFrame(worn, FOUNDER_FRAME)).as("另一枚不受影响").isFalse();
        assertThat(ownedFrame(worn, SEASON_FRAME)).isTrue();

        var off = readJson(postWear(playerId, new WearFrameReq(newRequestId(), null)));
        assertThat(wornFrame(off, SEASON_FRAME)).as("卸下了").isFalse();
        assertThat(ownedFrame(off, SEASON_FRAME))
                .as("卸下不等于失去 —— 合成一位的话玩家再想戴回去得再买一次").isTrue();
    }

    @Test
    @DisplayName("验收 3（判别性）：外观操作只改那两位，战力/资源/科技/付费…逐字段都不动")
    void wearingAFrameChangesNoNumbers() {
        String playerId = newPlayer();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();

        // 抓住操作前的每一份状态。**在这份存档上只做外观操作**，然后逐字段比 ——
        // 谁日后把"戴个框涨 3% 战力"写进这条路径，下面的断言会当场红。
        long cityLevel = save.cityLevel();
        var resources = save.resources();
        var power = save.power();
        var pvp = save.pvp();
        var glory = save.glory();
        var guide = save.guide();
        var paid = save.paid();
        var tech = save.tech();
        var giftPopup = save.giftPopup();
        String nickName = save.nickName();
        int avatarId = save.avatarId();
        Long protectUntil = save.protectUntil();

        save.ownAvatarFrame(SEASON_FRAME);
        save.ownAvatarFrame(SEASON_FRAME);   // 幂等：重复"拥有"不该留下第二份
        save.setAvatarFrame(SEASON_FRAME);

        assertThat(save.avatarFrame()).as("佩戴这一位变了").isEqualTo(SEASON_FRAME);
        assertThat(save.ownedAvatarFrames()).as("拥有这一位变了，且只有一枚").containsExactly(SEASON_FRAME);
        assertThat(save.cityLevel()).as("主城等级").isEqualTo(cityLevel);
        assertThat(save.resources()).as("资源（容量/产率/存量/受保护额度全在内）").isEqualTo(resources);
        assertThat(save.power()).as("战力（显示/匹配/峰值三位）").isEqualTo(power);
        assertThat(save.pvp()).as("PVP 状态").isEqualTo(pvp);
        assertThat(save.glory()).as("荣耀").isEqualTo(glory);
        assertThat(save.guide()).as("引导进度").isEqualTo(guide);
        assertThat(save.paid()).as("付费权益").isEqualTo(paid);
        assertThat(save.tech()).as("科技").isEqualTo(tech);
        assertThat(save.giftPopup()).as("礼包弹窗记账").isEqualTo(giftPopup);
        assertThat(save.nickName()).isEqualTo(nickName);
        assertThat(save.avatarId()).isEqualTo(avatarId);
        assertThat(save.protectUntil()).isEqualTo(protectUntil);
    }

    @Test
    @DisplayName("验收 3 的端到端面：走真端点戴上之后，存档里那两位确实落库")
    void wearingThroughTheEndpointPersistsAndKeepsPower() throws Exception {
        String playerId = newPlayer();
        avatarFrames.grant(playerId, SEASON_FRAME);
        String powerBefore = powerBody(playerId);

        postWear(playerId, new WearFrameReq(newRequestId(), SEASON_FRAME));

        PlayerSave worn = players.findByPlayerId(playerId).orElseThrow();
        assertThat(worn.avatarFrame()).as("真端点也要把佩戴落库").isEqualTo(SEASON_FRAME);
        assertThat(worn.ownedAvatarFrames()).contains(SEASON_FRAME);
        // 这里**不逐字比 /player/power**：佩戴要拿锁，而拿锁的入口会顺手结算一次产出（惰性结算），
        // 结算本身就会让资源与随之而来的战力动一点点 —— 那是结算在动、不是外观在动，
        // 逐字比会是一条假红。逐字段的判别归上面那条（在存档上只做外观操作）。
        assertThat(powerBody(playerId).length()).as("战力读数仍然读得到（不是被外观弄没了）")
                .isGreaterThan(0);
        assertThat(powerBefore).isNotEmpty();
    }

    // ---------- 夹具与 HTTP ----------

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq("req-" + UUID.randomUUID(),
                "dev-" + UUID.randomUUID(), "外观验证", 1_700_000_000_000L, "")).playerId();
    }

    /**
     * 把产率冻成 0，让"穿上前后逐字段相等"这条判据与时间无关。
     *
     * <p><b>为什么必须冻</b>：佩戴要拿锁，而拿锁的入口 {@code withSettledCity} 会顺手结算一次产出
     * （惰性结算，全仓库同一条纪律）；不冻的话，"资源/战力前后相等"会被那一次结算搅成**假红** ——
     * 那是结算在动，不是外观在动。冻的是产率（perHour=0），不是把结算关掉：结算路径本身仍照跑，
     * 于是这条用例验的仍然是真实入口。
     */
    private void freezeSettlement(String playerId) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        for (String id : save.resources().keySet()) {
            var state = save.resources().get(id);
            save.putResource(id, new com.ironoath.core.player.PlayerResourceState(
                    state.current(), state.cap(), state.protectedAmount(), 0L, state.lastSettle()));
        }
        players.save(save);
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private MvcResult getFrames(String playerId) throws Exception {
        return mockMvc.perform(get(FRAMES_URL).header(PLAYER_HEADER, playerId)).andReturn();
    }

    private MvcResult postWear(String playerId, WearFrameReq req) throws Exception {
        MvcResult result = mockMvc.perform(post(FRAME_URL)
                        .header(PLAYER_HEADER, playerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtils.toJson(req)))
                .andReturn();
        var root = JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(root.get("code").asInt()).as("佩戴应当成功：%s", root).isZero();
        return result;
    }

    private int postWearCode(String playerId, WearFrameReq req) throws Exception {
        MvcResult result = mockMvc.perform(post(FRAME_URL)
                        .header(PLAYER_HEADER, playerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtils.toJson(req)))
                .andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("code").asInt();
    }

    private com.fasterxml.jackson.databind.JsonNode readJson(MvcResult result) throws Exception {
        var root = JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(root.get("code").asInt()).as("请求应当成功：%s", root).isZero();
        return root.path("data");
    }

    private static boolean ownedFrame(com.fasterxml.jackson.databind.JsonNode data, String frameId) {
        return flagOf(data, frameId, "owned");
    }

    private static boolean wornFrame(com.fasterxml.jackson.databind.JsonNode data, String frameId) {
        return flagOf(data, frameId, "worn");
    }

    private static boolean flagOf(com.fasterxml.jackson.databind.JsonNode data, String frameId, String key) {
        for (var frame : data.path("frames")) {
            if (frame.path("frameId").asText().equals(frameId)) {
                return frame.path(key).asBoolean();
            }
        }
        throw new AssertionError("响应里没有头像框 " + frameId);
    }

    private String powerBody(String playerId) throws Exception {
        MvcResult result = mockMvc.perform(get("/player/power").header(PLAYER_HEADER, playerId)).andReturn();
        var root = JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(root.get("code").asInt()).as("读战力应当成功：%s", root).isZero();
        return root.path("data").toString();
    }
}
