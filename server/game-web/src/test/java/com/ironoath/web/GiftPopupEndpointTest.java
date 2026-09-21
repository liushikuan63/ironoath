package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.GiftCfg;
import com.ironoath.config.cfg.PayProductCfg;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.pay.GiftTriggerMarks;
import com.ironoath.web.service.PlayerInitService;

/**
 * 职责：`GET /gift/popup` 的端到端（B19 S3-ii：验收 6 与 7 在这里真跑一遍）。
 * 依赖：MockMvc + 内存存储。
 *
 * <p><b>本类钉住的是"读时判定"这条设计</b>：触发只记时刻，弹不弹是读时算的 ——
 * 所以第一次问允许、紧接着再问被全局冷却挡住（10 分钟），而不是"触发那一刻弹一次就没了"。
 *
 * <p><b>触发是怎么造的</b>：走产品代码里的打标器 `GiftTriggerMarks.markOn(save, ...)` +
 * 调用方自己 `players.save(save)` —— 与三个真实调用点（城建结算 / 战报 / 关卡失败）同一形状。
 * 那三处业务路径的端到端（验收 8）不在本类射程内，见 B19 §6.1 的未验证清单。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GiftPopupEndpointTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private ConfigRegistry configs;

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "礼包测试",
                1_700_000_000_000L, "")).playerId();
    }

    /** 按真实调用点的形状打一次触发：改调用方手里那份 save，再由调用方保存。 */
    private void mark(String playerId, GiftCfg.Trigger trigger) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        GiftTriggerMarks.markOn(save, trigger, System.currentTimeMillis());
        players.save(save);
    }

    private JsonNode popup(String playerId) throws Exception {
        // **按 UTF-8 读响应体**：MockMvc 的 getContentAsString() 默认用 ISO-8859-1，
        // 中文字段会被解成 "è½æè´ºç¤¼" —— 本类此前只断言 ASCII 的 id，所以一直没暴露；
        // 一旦断言显示名（本条用例），读侧编码就成了判据的一部分。
        String body = mockMvc.perform(get("/gift/popup")
                        .header("X-Player-Id", playerId)
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = JsonUtils.readTree(body);
        assertThat(root.get("code").asInt()).as("端点必须回业务码 0，实际：" + body).isZero();
        return root.get("data");
    }

    @Test
    @DisplayName("没有任何触发时不弹：popup=false，三样带值的字段全为 null（客户端不许自己找上一个弹）")
    void noTriggerMeansNoPopup() throws Exception {
        JsonNode data = popup(newPlayer());

        assertThat(data.get("popup").asBoolean()).isFalse();
        assertThat(data.get("giftId").isNull()).isTrue();
        assertThat(data.get("productId").isNull()).isTrue();
        assertThat(data.get("offerExpireAt").isNull()).isTrue();
        assertThat(data.get("cooldownSec").asLong()).as("没有待弹的触发时不必节流").isZero();
        assertThat(data.get("serverNow").asLong()).isPositive();
    }

    @Test
    @DisplayName("触发了就弹：只回礼包 id 与商品 id，报价到期 = 触发时刻 + 表的 TTL")
    void triggerLeadsToAPopupAndThenCooldown() throws Exception {
        String playerId = newPlayer();
        mark(playerId, GiftCfg.Trigger.BUILDING_DONE);

        JsonNode first = popup(playerId);
        assertThat(first.get("popup").asBoolean()).as("刚触发过就该弹").isTrue();
        assertThat(first.get("giftId").asText()).isEqualTo("popup_building_celebration");
        assertThat(first.get("productId").asText()).as("价格与内容都按这一档现查")
                .isEqualTo("gift_building_celebration");
        long ttlMillis = 60L * 60_000L;
        assertThat(first.get("offerExpireAt").asLong() - first.get("serverNow").asLong())
                .as("报价到期 = 触发时刻 + offerTtlMinutes（容 5 秒执行误差）")
                .isBetween(ttlMillis - 5_000L, ttlMillis);

        JsonNode second = popup(playerId);
        assertThat(second.get("popup").asBoolean()).isFalse();
        assertThat(second.get("cooldownSec").asLong()).as("要告诉客户端多久后再问").isPositive();
    }

    @Test
    @DisplayName("弹窗必须下发商品的中文显示名：缺了它客户端只能把 productId 印给玩家（#255/#268 同族）")
    void popupCarriesTheProductDisplayName() throws Exception {
        String playerId = newPlayer();
        mark(playerId, GiftCfg.Trigger.BUILDING_DONE);

        JsonNode data = popup(playerId);
        assertThat(data.get("popup").asBoolean()).isTrue();
        assertThat(data.has("productName")).as("字段必须在（nullable 也要在）").isTrue();

        String productId = data.get("productId").asText();
        String productName = data.get("productName").asText();
        String configured = configs.all(PayProductCfg.class).stream()
                .filter(row -> row.id().equals(productId))
                .map(PayProductCfg::name)
                .findFirst().orElseThrow();
        assertThat(productName).as("显示名就是配置表那一行的 name，服务端不另造一份")
                .isEqualTo(configured);
        assertThat(productName).as("名字不许等于 id —— 那正是印内部编号的形态")
                .isNotEqualTo(productId);
    }

    @Test
    @DisplayName("验收6：首充后 24 小时内不弹（首充时刻读的是 PlayerPaid 那位持久真值）")
    void firstPurchaseQuietPeriodBlocksThePopup() throws Exception {
        String playerId = newPlayer();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setPaid(new com.ironoath.core.player.PlayerPaid(null, null, null, java.util.Set.of(),
                System.currentTimeMillis(), java.util.Set.of()));
        players.save(save);

        mark(playerId, GiftCfg.Trigger.BUILDING_DONE);

        assertThat(popup(playerId).get("popup").asBoolean()).as("刚付过钱的人最不该被打扰").isFalse();
    }
}
