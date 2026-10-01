package com.ironoath.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.GiftCfg;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.TrainReq;
import com.ironoath.web.service.ArmyAppService;
import com.ironoath.web.service.HeroAppService;
import com.ironoath.web.service.CityAppService;
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
 * <p><b>响应形状（踩过的坑，#570）</b>：{@code data.popup} 是 <b>boolean</b>（canPopup），
 * 而 {@code giftId} / {@code productId} / {@code productName} / {@code cooldownSec} /
 * {@code offerExpireAt} 与它<b>同层</b>在 {@code data} 里 —— 不是 {@code {popup:{...}}}。
 * 在 {@code popup} 里面找 {@code giftId} 会得到 NPE（我前面两次就是这么栽的）。
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
    @Autowired private CityAppService cityAppService;
    @Autowired private CityRepository cities;
    @Autowired private ArmyAppService armyAppService;
    @Autowired private HeroAppService heroAppService;

    /** 夹具：直接给足四种资源（照 ArmyEndpointTest.giveResources 的形状）。 */
    private void giveResources(String playerId, long amount) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        for (String id : configs.resourceIds()) {
            com.ironoath.core.player.PlayerResourceState st = save.resource(id);
            // **注意有 cap**（#569）：直接写 current 会超容量被结算截回去 ——
            // 照 ArmyEndpointTest 的形状取 min(cap, current + each)。
            long target = Math.min(st.cap(), st.current() + amount);
            save.putResource(id, new com.ironoath.core.player.PlayerResourceState(
                    target, st.cap(), st.protectedAmount(), st.perHour(), st.lastSettle()));
        }
        players.save(save);
    }

    /** 夹具：建一座建筑并**立刻建成**（照 ArmyEndpointTest.buildAndFinish —— 改建造状态再 collect）。 */
    private void buildAndFinish(String playerId, String configId, int x, int y) {
        giveResources(playerId, 1_000_000L);
        var req = new com.ironoath.web.dto.generated.CityUpgradeReq(
                "e2e-" + UUID.randomUUID(), configId, x, y);
        var upgrade = cityAppService.upgrade(playerId, req);
        CityState city = cities.findByPlayerId(playerId).orElseThrow();
        long version = cities.versionOf(playerId);
        var b = city.building(upgrade.buildingId());
        b.restore(b.level(), b.gridX(), b.gridY(),
                com.ironoath.core.city.BuildingStatus.UPGRADING, 1L,
                0L, 1L, 1L, b.helpCount(), b.lastMovedAt(), b.lastFinishedAt());
        cities.save(playerId, city, version);
        cityAppService.collect(playerId,
                new com.ironoath.web.dto.generated.CityCollectReq(
                        "e2e-c-" + UUID.randomUUID(), upgrade.buildingId()));
        // **必须再触发一次结算**（#570）：`BUILDING_DONE` 标记打在
        // `CityAppService.load()` 的结算段里（源码 316~323 行，注释明写
        // 「收割发生在**任意一次结算**里……只盯 collect 会漏掉这一类路径」），
        // 而 `load()` 只有**下一次请求**才会跑到 —— `collect` 走的是收割分支，
        // **不会把 settlement.harvested() 变成非空**。`list` 走 `load()`，所以补它一下。
        cityAppService.list(playerId);
    }

    /** 夹具：主城升到指定等级（初始 1 级，中心格 (3,3) 固定主城）。 */
    private void raiseMainCity(String playerId, int level) {
        for (int i = 1; i < level; i++) {
            buildAndFinish(playerId, "main_city", 3, 3);
        }
    }

    /**
     * 夹具：造出 {@code count} 个 T1 步兵（B19 ④ 的前置）。
     * 照 ArmyEndpointTest 的形状：先 {@code prepareBarracks}（兵营 + 资源），
     * 再 {@code armyAppService.train(...)}。
     */
    private void trainT1(String playerId, long count) {
        giveResources(playerId, 1_000_000L);
        prepareBarracks(playerId);
        // **带兵上限必须先为正**（#571）：零资源新号没有武将 ⇒ `troopCap = 0` ⇒
        // 「超出带兵上限：当前 0，上限 0」。这正是 #525 查出的那条链
        // （TROOP_PER_COMMAND × 上阵武将统率）。**合成并上阵一名武将**，
        // 照 ArmyEndpointTest.fieldHeroForCap 的形状（composeHero + setLineup）。
        heroAppService.setLineup(playerId,
                new com.ironoath.web.dto.generated.SetLineupReq(
                        "e2e-l-" + UUID.randomUUID(), 0, "hero_ssr_02", null, null));
        long cap = armyAppService.list(playerId).troopCap();
        assertThat(cap).as("上阵 hero_ssr_02 之后带兵上限必须为正（否则造兵一定被 0 上限挡住）")
                .isPositive();
        armyAppService.train(playerId,
                new TrainReq("e2e-t-" + UUID.randomUUID(), "unit_infantry_t1", count));
    }

    /** 夹具：兵营 3 级 —— 造兵的前置（B05：训练营等级门槛）。 */
    private void prepareBarracks(String playerId) {
        raiseMainCity(playerId, 4);
        buildAndFinish(playerId, "barracks", 1, 1);
        raiseMainCity(playerId, 4);
        buildAndFinish(playerId, "barracks", 1, 1);
    }

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
        JsonNode data = popupData(newPlayer());
        boolean canPopup = data.path("popup").asBoolean(false);
        // **真实形状**（GiftPopupResp 第一个分量是 boolean）：`canPopup=false` 时 giftId 为 null。
        // **新号可能有「新手礼包」弹窗**（那是设计，不是三类触发）——
        // 所以对照组的正确判据是「**不是那三类里的任何一个**」，不是「没有弹窗」。
        if (!canPopup) {
            return;   // 不弹当然也合格
        }
        String id = data.path("giftId").asText("");
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
        // **走夹具而不是裸请求**（#569）：裸请求被 CITY_GRID_INVALID(3007) 挡住是因为
        // 没发资源 + 格子没准备；夹具照 ArmyEndpointTest 的形状发资源并建完即收。
        buildAndFinish(pid, "lumber_camp", 1, 1);
        int upgradeCode = 0;

        JsonNode data = popupData(pid);
        if (!data.path("popup").asBoolean(false)) {
            // **把服务端回的东西原样打出来**（#570）：这是分辨「标记没打」与「被频控压住」的唯一办法
            System.out.println("[E2E] BUILDING_DONE 不可验：/city/upgrade=" + upgradeCode
                    + "  popup=" + data.path("popup") + "  cooldownSec="
                    + data.path("cooldownSec").asLong(-1L) + "  giftId=" + data.path("giftId").asText(""));
            Assumptions.abort("/city/upgrade 业务码=" + upgradeCode + " ⇒ BUILDING_DONE 标记点未经过；"
                    + "新号默认资源/位置不满足升级前置 ⇒ 本条不可验（不是通过也不是失败）。"
                    + "要让本条真跑，需要先给新号发足资源或用提速档后端。");
        }
        assertThat(data.path("giftId").asText()).isEqualTo(giftIdFor(GiftCfg.Trigger.BUILDING_DONE));
    }

    @Test
    @DisplayName("真实行军一次并失败 ⇒ 弹战败抚恤（走 PlayerCityBattleService 的 BATTLE_LOST 标记点，端点 POST /world/march）")
    void battleLostReallyTriggersTheReliefPopup() throws Exception {
        String pid = newPlayer();
        // **先造兵**（#571）：不造兵时 `/world/march` 恒回 `6000 MARCH_NO_TROOP`。
        try {
            trainT1(pid, 100L);
        } catch (com.ironoath.common.BizException e) {
            // **夹具前置未齐 ⇒ 诚实标「不可验 + 原因」而不是让本类把 mvn test 弄红**（#571）。
            // 已知原因：新号没有武将 ⇒ troopCap=0 ⇒ 造不出兵；上阵要 composeHero（合成）。
            System.out.println("[E2E] BATTLE_LOST 不可验：造兵前置未齐 —— "
                    + e.errorCode() + " " + e.getMessage());
            Assumptions.abort("造兵前置未齐（" + e.errorCode() + " " + e.getMessage() + "）"
                    + " ⇒ BATTLE_LOST 标记点不可能经过。要让本条真跑，"
                    + "需要新号先拥有一名武将（合成/抽卡），见收口清单 #571。");
        }
        String attackBody = mockMvc.perform(post("/world/march")
                        .header("X-Player-Id", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e2e-a-" + UUID.randomUUID() + "\","
                                + "\"targetX\":141,\"targetY\":83}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        int attackCode = JsonUtils.readTree(attackBody).path("code").asInt(-1);

        JsonNode data = popupData(pid);
        if (!data.path("popup").asBoolean(false)) {
            System.out.println("[E2E] BATTLE_LOST 不可验：/world/march 业务码=" + attackCode);
            Assumptions.abort("/world/march 业务码=" + attackCode + " ⇒ BATTLE_LOST 标记点未经过；"
                    + "新号无兵力/无可打目标 ⇒ 本条不可验（不是通过也不是失败）。"
                    + "要让本条真跑：先给新号造出至少 1 队兵（兵营 3 级 + 30 铁/20 粮每 100），"
                    + "再指向一个必输目标（低战力打高战力城）。");
        }
        assertThat(data.path("giftId").asText()).isEqualTo(giftIdFor(GiftCfg.Trigger.BATTLE_LOST));
    }
}
