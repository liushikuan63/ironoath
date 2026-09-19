package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
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

import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.world.Coord;
import com.ironoath.web.dto.generated.MarchAction;
import com.ironoath.web.dto.generated.MarchReq;
import com.ironoath.web.dto.generated.MarchUnit;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryArmyStore;
import com.ironoath.web.store.memory.InMemoryHeroStore;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
import com.ironoath.web.store.memory.InMemoryWorldStore;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 职责：B25-S1 的两条**服务端红线**在 HTTP 层成立 —— 「一键重复出征」不许绕开既有的闸门。
 * 依赖：Spring Boot Test + MockMvc（与 {@code PowerBandEndpointTest} 同一套夹具手法）。
 *
 * <p><b>为什么先写这一层再写客户端</b>：一键重复出征的价值全在"它和真人点两次是同一件事"。
 * 而这句话能不能成立，取决于服务端的闸门长什么样 —— 本轮现跑读代码的结论是：
 * 出征**没有逐次频控**，真正的闸门是 ①**并发上限**（{@code MARCH_QUEUE_FULL}）、
 * ②**幂等键**（同一 requestId 再发 ⇒ {@code REQUEST_DUPLICATED}）与 ③目标/兵种/武将校验。
 * 于是"重复出征"的正确口径是：**每次点击用一个新的 requestId**（与真人连点同一形状），
 * 而"重复"这件事本身**不享有任何豁免**。这几条不写下来，客户端就会有人顺手复用一个 requestId
 * 做出「点一次出发两支队伍」或者相反「点两次只出一支」的假象。
 *
 * <p><b>本类不测客户端</b>：客户端入口（目标 → 编成 → 发）依赖 B25 §四 裁决④（编成从哪来），
 * 未裁决前不动。这里钉住的是无论客户端怎么做都必须成立的那一半。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MarchRepeatInvariantsTest {

    private static final String MARCH_URL = "/world/march";
    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final String UNIT = "unit_infantry_t1";

    @Autowired private MockMvc mockMvc;
    @Autowired private ConfigRegistry configs;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private com.ironoath.core.player.PlayerRepository players;
    @Autowired private com.ironoath.core.army.ArmyRepository armies;
    @Autowired private com.ironoath.core.world.WorldRepository world;
    @Autowired private com.ironoath.core.march.MarchRepository marches;
    @Autowired private com.ironoath.core.hero.HeroRepository heroes;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryArmyStore) armies).clear();
        ((InMemoryWorldStore) world).clear();
        ((InMemoryMarchStore) marches).clear();
        ((InMemoryHeroStore) heroes).clear();
    }

    @Test
    @DisplayName("同参数两次、两个新的 requestId ⇒ 真的出发两支队伍（重复出征与真人点两次同形）")
    void twoRequestsWithFreshIdsAreTwoMarches() throws Exception {
        String self = newPlayerAt(256, 256);
        giveTroops(self, 100L);
        String enemy = newPlayerAt(262, 256, "靶子", true);
        giveTroops(enemy, 1L);
        setMatchPower(enemy, 6_000L);   // 落进自己的可攻击区间（新号带 100 兵约 4000）
        MarchReq template = new MarchReq("placeholder", 262, 256,
                List.of(new MarchUnit(UNIT, 10L)), List.of(), MarchAction.ATTACK);

        String first = march(self, withRequestId(template, newRequestId()));
        assertThat(first).as("第一次必须成功").isNotEmpty();
        String second = march(self, withRequestId(template, newRequestId()));
        assertThat(second).as("第二次也必须成功 —— 「重复」不该被当成异常").isNotEmpty();
        assertThat(second).as("两次是两支不同的队伍（同一个 marchId 说明服务端把第二次吞了）")
                .isNotEqualTo(first);
        assertThat(marches.activeCountOf(self)).as("在途两支").isEqualTo(2);
    }

    @Test
    @DisplayName("同一个 requestId 再发 ⇒ REQUEST_DUPLICATED，且不会多出第二支队伍")
    void reusingTheRequestIdIsRejectedAndDoesNotAddASecondMarch() throws Exception {
        String self = newPlayerAt(256, 256);
        giveTroops(self, 100L);
        String enemy = newPlayerAt(262, 256, "靶子", true);
        giveTroops(enemy, 1L);
        setMatchPower(enemy, 6_000L);   // 落进自己的可攻击区间（新号带 100 兵约 4000）
        String requestId = newRequestId();
        MarchReq req = new MarchReq(requestId, 262, 256,
                List.of(new MarchUnit(UNIT, 10L)), List.of(), MarchAction.ATTACK);

        assertThat(march(self, req)).as("第一次成功").isNotEmpty();
        int code = postCode(self, req);
        assertThat(code)
                .as("同一个 requestId 再发就是重放：必须被拒（不允许悄悄出发第二支）")
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED.code());
        assertThat(marches.activeCountOf(self)).as("被拒的那次没有留下行军").isEqualTo(1);
        assertThat(armies.findByPlayerId(self).orElseThrow().countOf(UNIT))
                .as("被拒的那次也没有再扣一次兵").isEqualTo(90L);
    }

    @Test
    @DisplayName("并发上限就是重复出征要受的那道闸：占满之后再发一支回 MARCH_QUEUE_FULL")
    void theConcurrencyCapStillBitesOnRepeats() throws Exception {
        String self = newPlayerAt(256, 256);
        giveTroops(self, 100L);
        String enemy = newPlayerAt(262, 256, "靶子", true);
        giveTroops(enemy, 1L);
        setMatchPower(enemy, 6_000L);   // 落进自己的可攻击区间（新号带 100 兵约 4000）

        // **不覆写配置**：上限就按表里的值（现跑 3 支），占满它比改它更接近真人的处境 ——
        // 而这个类的夹具**不该动共享的 ConfigRegistry**：上一版把 MARCH_MAX_CONCURRENT 覆写成 1
        // 且没有还原，同一个 JVM 里后面跑的 OpsEndpointTest 立刻红在"没有文件改动就该是空数组"
        // （配置指纹多出一个 global）——跨用例污染，全量轮才逮到，单跑这个类永远看不见
        long cap = configs.longParam("MARCH_MAX_CONCURRENT");
        for (long i = 0; i < cap; i++) {
            assertThat(march(self, new MarchReq(newRequestId(), 262, 256,
                    List.of(new MarchUnit(UNIT, 10L)), List.of(), MarchAction.ATTACK)))
                    .as("第 %d 支（上限 %d）应当出发", i + 1, cap).isNotEmpty();
        }
        assertThat(marches.activeCountOf(self)).as("已经占满上限").isEqualTo(cap);

        int code = postCode(self, new MarchReq(newRequestId(), 262, 256,
                List.of(new MarchUnit(UNIT, 10L)), List.of(), MarchAction.ATTACK));
        assertThat(code)
                .as("占满上限之后再发一支必须是 MARCH_QUEUE_FULL：重复出征不享有任何豁免")
                .isEqualTo(ErrorCode.MARCH_QUEUE_FULL.code());
        assertThat(marches.activeCountOf(self)).as("被拒的那支没有挤进来").isEqualTo(cap);
    }

    @Test
    @DisplayName("被拒的那次不会把 requestId 烧掉：改个目标用同一个键再发就能成")
    void aRejectedMarchDoesNotBurnTheRequestId() throws Exception {
        String self = newPlayerAt(256, 256);
        giveTroops(self, 100L);
        String enemy = newPlayerAt(262, 256, "靶子", true);
        giveTroops(enemy, 1L);
        setMatchPower(enemy, 6_000L);   // 落进自己的可攻击区间（新号带 100 兵约 4000）
        String requestId = newRequestId();

        // 往自己家的坐标发：目标校验必须拒（这也是玩家最容易撞到的一种"上一次参数已失效"）
        int bad = postCode(self, new MarchReq(requestId, 256, 256,
                List.of(new MarchUnit(UNIT, 10L)), List.of(), MarchAction.ATTACK));
        assertThat(bad).as("打自己的城要被目标校验拒绝").isEqualTo(ErrorCode.WORLD_TARGET_INVALID.code());

        // 同一个 requestId 换一个合法目标重发：这正是客户端「重试」的形状
        String ok = march(self, new MarchReq(requestId, 262, 256,
                List.of(new MarchUnit(UNIT, 10L)), List.of(), MarchAction.ATTACK));
        assertThat(ok).as("失败的那次必须把幂等键让出来，否则玩家点了被拒就再也发不出去")
                .isNotEmpty();
        assertThat(marches.activeCountOf(self)).as("改目标后真的出发了").isEqualTo(1);
    }

    // ---------- 夹具 ----------

    private static MarchReq withRequestId(MarchReq template, String requestId) {
        return new MarchReq(requestId, template.toX(), template.toY(), template.units(),
                template.heroes(), template.action());
    }

    private String newPlayerAt(int x, int y) {
        return newPlayerAt(x, y, "重复出征", true);
    }

    private String newPlayerAt(int x, int y, String nickName, boolean liftProtect) {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), nickName,
                1_700_000_000_000L, "")).playerId();
        assertThat(world.placeCity(playerId, Coord.of(x, y)))
                .as("夹具必须能把城放到 (%d,%d)，占位说明坐标与别的用例撞了", x, y).isTrue();
        if (liftProtect) {
            var save = players.findByPlayerId(playerId).orElseThrow();
            save.setProtectUntil(null);
            players.save(save);
        }
        return playerId;
    }

    /** 设匹配战力：靶子不落在圈层里的话，攻击会被 B08 的闸门拦在重复出征之前。 */
    private void setMatchPower(String playerId, long matchPower) {
        var save = players.findByPlayerId(playerId).orElseThrow();
        save.setPower(new com.ironoath.core.player.PlayerPower(matchPower * 2, matchPower, matchPower));
        players.save(save);
    }

    private void giveTroops(String playerId, long count) {
        if (armies.findByPlayerId(playerId).isEmpty()) {
            armies.insertIfAbsent(playerId, new com.ironoath.core.army.ArmyState());
        }
        var army = armies.findByPlayerId(playerId).orElseThrow();
        long version = armies.versionOf(playerId);
        army.add(UNIT, count);
        armies.save(playerId, army, version);
    }

    private String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    /** 发一次出征，成功回 marchId；被拒时抛（用例自己用 {@link #postCode} 拿码）。 */
    private String march(String playerId, MarchReq req) throws Exception {
        MvcResult result = mockMvc.perform(post(MARCH_URL)
                        .header(PLAYER_HEADER, playerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtils.toJson(req)))
                .andReturn();
        var root = JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(root.get("code").asInt()).as("出征失败：%s", root).isZero();
        // MarchResp 里是嵌套的 march 对象（不是平的 marchId）：响应形状照契约走
        return root.path("data").path("march").path("marchId").asText();
    }

    private int postCode(String playerId, MarchReq req) throws Exception {
        MvcResult result = mockMvc.perform(post(MARCH_URL)
                        .header(PLAYER_HEADER, playerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtils.toJson(req)))
                .andReturn();
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("code").asInt();
    }
}
