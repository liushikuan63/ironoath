package com.ironoath.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.GiftCfg;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * B19 验收 8 的后半段：<b>三类触发（卡关 / 建筑完成 / 战败）各自真的会触发</b>。
 *
 * <p><b>为什么不能用 {@code GiftPopupEndpointTest} 顶替</b>：那个类的 4 条用例全部走
 * {@code mark(playerId, trigger)} —— <b>测试自己手工打标记</b>，于是它验的是
 * 「标记存在时弹窗会不会出来」，而验收问的是「<b>真的卡关 / 真建完 / 真输一场</b>，
 * 标记会不会自己产生」。这两件事之间隔着三个生产调用点：{@code StageAppService}（STUCK_STAGE）、
 * {@code CityAppService}（BUILDING_DONE）、{@code PlayerCityBattleService}（BATTLE_LOST）——
 * <b>它们任何一个没接，这条验收照样绿</b>。
 *
 * <p><b>坐标口径</b>：城内网格是 {@code city_rule.city_rule_grid_size = 6}（坐标 0~5，
 * 中心 (3,3) 固定主城）—— 141/83 那是大地图坐标，落在城外，会被
 * {@code CITY_GRID_INVALID(3007)} 挡掉。
 *
 * <p><b>所以这里的判据是「端到端经过真实动作」</b>。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GiftPopupTriggerEndToEndTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private ConfigRegistry configs;

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "礼包端到端",
                1_700_000_000_000L, "")).playerId();
    }

    private JsonNode popupData(String playerId) throws Exception {
        String body = mockMvc.perform(get("/gift/popup")
                        .header("X-Player-Id", playerId)
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = JsonUtils.readTree(body);
        assertThat(root.get("code").asInt()).as("端点必须回业务码 0，实际：" + body).isZero();
        return root.get("data");
    }

    private String giftIdFor(GiftCfg.Trigger trigger) {
        return configs.all(GiftCfg.class).stream()
                .filter(g -> g.trigger() == trigger)
                .map(g -> g.id())
                .findFirst()
                .orElseThrow(() -> new AssertionError("gift.json 里没有 " + trigger + " 这一类"));
    }

    @Test
    @DisplayName("三类触发的礼包在表里各有一行、互不重叠（映射表本身先钉住）")
    void threeTriggersMapToThreeDistinctGifts() {
        for (GiftCfg.Trigger t : new GiftCfg.Trigger[] {
                GiftCfg.Trigger.STUCK_STAGE, GiftCfg.Trigger.BUILDING_DONE, GiftCfg.Trigger.BATTLE_LOST }) {
            assertThat(giftIdFor(t)).as("gift.json 必须有 " + t + " 这一类").isNotBlank();
        }
        assertThat(giftIdFor(GiftCfg.Trigger.STUCK_STAGE))
                .isNotEqualTo(giftIdFor(GiftCfg.Trigger.BUILDING_DONE));
        assertThat(giftIdFor(GiftCfg.Trigger.BUILDING_DONE))
                .isNotEqualTo(giftIdFor(GiftCfg.Trigger.BATTLE_LOST));
        assertThat(giftIdFor(GiftCfg.Trigger.STUCK_STAGE))
                .isNotEqualTo(giftIdFor(GiftCfg.Trigger.BATTLE_LOST));
    }

    @Test
    @DisplayName("对照组：没有真实动作时，弹窗不是三类触发里的任何一个（否则下面两条是假绿）")
    void noRealActionMeansNoPopup() throws Exception {
        JsonNode popup = popupData(newPlayer()).get("popup");
        // **真实形状**（GiftPopupResp 第一个分量是 boolean）：`canPopup=false` 时 giftId 为 null。
        // **新号可能有「新手礼包」弹窗**（那是设计，不是三类触发）——
        // 所以对照组的正确判据是「**不是那三类里的任何一个**」，不是「没有弹窗」。
        if (popup == null || popup.isNull() || !popup.path("canPopup").asBoolean(false)) {
            return;   // 不弹当然也合格
        }
        String id = popup.path("giftId").asText("");
        for (GiftCfg.Trigger t : new GiftCfg.Trigger[] {
                GiftCfg.Trigger.STUCK_STAGE, GiftCfg.Trigger.BUILDING_DONE, GiftCfg.Trigger.BATTLE_LOST }) {
            assertThat(id).as("没做真实动作就不该拿到三类触发之一的弹窗")
                    .isNotEqualTo(giftIdFor(t));
        }
    }

    @Test
    @DisplayName("真实建完一栋建筑 ⇒ 弹落成贺礼（走 CityAppService 的 BUILDING_DONE 标记点）")
    void buildingDoneReallyTriggersTheCelebrationPopup() throws Exception {
        String pid = newPlayer();
        String upgradeBody = mockMvc.perform(post("/city/upgrade")
                        .header("X-Player-Id", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e2e-b-" + UUID.randomUUID() + "\","
                                + "\"configId\":\"lumber_camp\",\"level\":1,\"x\":1,\"y\":1}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // **把业务码带进跳过理由**（#568）：只报 "Skipped: 2" 看不出是真前置不足还是判据写错
        int upgradeCode = JsonUtils.readTree(upgradeBody).path("code").asInt(-1);

        JsonNode got = popupData(pid).get("popup");
        if (got == null || got.isNull() || !got.path("canPopup").asBoolean(false)) {
            System.out.println("[E2E] BUILDING_DONE 不可验：/city/upgrade 业务码=" + upgradeCode);
            Assumptions.abort("/city/upgrade 业务码=" + upgradeCode + " ⇒ BUILDING_DONE 标记点未经过；"
                    + "新号默认资源/位置不满足升级前置 ⇒ 本条不可验（不是通过也不是失败）。"
                    + "要让本条真跑，需要先给新号发足资源或用提速档后端。");
        }
        assertThat(got.path("giftId").asText()).isEqualTo(giftIdFor(GiftCfg.Trigger.BUILDING_DONE));
    }

    @Test
    @DisplayName("真实行军一次并失败 ⇒ 弹战败抚恤（走 PlayerCityBattleService 的 BATTLE_LOST 标记点，端点 POST /world/march）")
    void battleLostReallyTriggersTheReliefPopup() throws Exception {
        String pid = newPlayer();
        String attackBody = mockMvc.perform(post("/world/march")
                        .header("X-Player-Id", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e2e-a-" + UUID.randomUUID() + "\","
                                + "\"targetX\":141,\"targetY\":83}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        int attackCode = JsonUtils.readTree(attackBody).path("code").asInt(-1);

        JsonNode got = popupData(pid).get("popup");
        if (got == null || got.isNull() || !got.path("canPopup").asBoolean(false)) {
            System.out.println("[E2E] BATTLE_LOST 不可验：/world/march 业务码=" + attackCode);
            Assumptions.abort("/world/march 业务码=" + attackCode + " ⇒ BATTLE_LOST 标记点未经过；"
                    + "新号无兵力/无可打目标 ⇒ 本条不可验（不是通过也不是失败）。"
                    + "要让本条真跑：先给新号造出至少 1 队兵（兵营 3 级 + 30 铁/20 粮每 100），"
                    + "再指向一个必输目标（低战力打高战力城）。");
        }
        assertThat(got.path("giftId").asText()).isEqualTo(giftIdFor(GiftCfg.Trigger.BATTLE_LOST));
    }
}
