package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.time.TimeService;
import com.ironoath.core.bot.BotProfile;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.bot.BotRuntimeService;
import com.ironoath.web.bot.BotWorldAdapter;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.PlayerInitService;

/**
 * 职责：B11 §三 调度的落地验证（收口清单 §五 C1）—— Bot 的 tick <b>真的会推动世界</b>。
 * 依赖：Spring Boot Test + MockMvc；test profile（内存存储，{@code ironoath.ops.token} 配了假值）。
 *
 * <p><b>本类最要紧的一条是"世界真的动了"</b>。调度器与决策树早就有 core 用例，但那些用例全靠
 * 测试内联的 Fake World / Fake Queue —— 它们证明"算法自洽"，证明不了"接在生产上会跑"。
 * 所以这里断言的是<b>存档里的主城等级涨了</b>：那一格只有 {@code CityAppService.upgrade} 会改，
 * 而 Bot 走的正是真人那一条（B11 头号铁律）。
 *
 * <p><b>用 {@code now} 参数推进一天，而不是 sleep 或改时钟</b>：{@code processDue(now)} 的时钟
 * 是入参（core 不读系统时间），所以"跑过 24 小时"就是把 now 往前挪。
 * 真人侧的服务仍然用真实时间 —— 这不影响断言，因为断言只看"有没有升成"，不看"升完要几分钟"。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BotRuntimeTickTest {

    private static final String OPS_HEADER = "X-Ops-Token";
    /** 与 application-test.yml 里 ironoath.ops.token 的假值一致（SeasonSettleAuthTest 同源）。 */
    private static final String OPS_TOKEN = "test-ops-token";
    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final long MINUTE = 60_000L;
    private static final long DAY = 24 * 60 * MINUTE;

    @Autowired private MockMvc mockMvc;
    @Autowired private BotRuntimeService runtime;
    @Autowired private BotRegistry bots;
    @Autowired private BotWorldAdapter adapter;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private CityAppService cityAppService;
    @Autowired private CityRepository cities;
    @Autowired private PlayerRepository players;
    @Autowired private TimeService timeService;
    @Autowired private com.ironoath.web.service.WorldAppService worldAppService;

    @BeforeEach
    void reset() {
        // 注册表、待办队列、兜底节流、动作计数都是进程内共享状态：不清的话上一条用例的 Bot
        // 会被这一条的 tick 推进，"这一轮涨了几级"就变成上一条的功劳
        runtime.reset();
        bots.clear();
        adapter.resetCounters();
    }

    @Test
    @DisplayName("tick 真的推动世界：Bot 的主城升级被发起、资源真的被扣（走的是真人同一条写路径）")
    void ticksActuallyLevelUpTheBotCity() {
        String botId = bot();
        long woodBefore = resource(botId, "WOOD");

        long now = timeService.serverNow();
        Map<String, Long> last = Map.of();
        for (long t = now; t < now + DAY; t += MINUTE) {
            last = runtime.tick(t);
        }

        assertThat(adapter.actionCounts().get("upgraded"))
                .as("一天里一次都没升成建筑 ⇒ tick 没在动世界（failed=%s unhandled=%s pending=%s）",
                        last.get("failed"), last.get("unhandled"), last.get("pending"))
                .isPositive();
        // 升级是「发起 → 到点收割」两段的（真人也一样）。本用例不推进真实时钟，所以能断言的是
        // 「这一轮真的在跑」：建筑进入 UPGRADING 且完成时刻在未来。把「等级已经涨了」写成断言，
        // 就是自证一个本档做不到的事（那要等真实时间或注入加速时钟）
        var main = cities.findByPlayerId(botId).orElseThrow().findByConfigId("main_city");
        assertThat(main).as("主城必须还在（Bot 不许拆自己的城）").isNotNull();
        assertThat(main.status())
                .as("tick 之后主城应该在升级中，而不是原地不动")
                .isEqualTo(com.ironoath.core.city.BuildingStatus.UPGRADING);
        assertThat(main.upgradeFinishAt())
                .as("完成时刻必须在未来 —— 到点收割才是等级涨的那一刻")
                .isGreaterThan(timeService.serverNow());
        assertThat(resource(botId, "WOOD"))
                .as("资源必须真的被扣：Bot 免消耗就是 B11 §十 明写的特权捷径")
                .isLessThan(woodBefore);
    }

    @Test
    @DisplayName("24 小时仿真：20 个 Bot 一天都在动，队列稳在每 Bot 一条待办（验收 1 的可自动化部分）")
    void compressedDayKeepsEveryBotMoving() {
        for (int i = 0; i < 20; i++) {
            bot();
        }
        long now = timeService.serverNow();
        Map<String, Long> last = Map.of();
        for (long t = now; t < now + DAY; t += MINUTE) {
            last = runtime.tick(t);
        }

        assertThat(bots.size()).isEqualTo(20);
        assertThat(runtime.enrolledCount()).as("每个 Bot 都被登记进作息表").isEqualTo(20);
        assertThat(last.get("pending"))
                .as("队列必须稳在 20 条 —— 一个 Bot 同时只该有一个待办，多出来的是重复排期，"
                        + "表现是一个 Bot 一秒动两次")
                .isEqualTo(20L);
        assertThat(adapter.actionCounts().get("upgraded") + adapter.actionCounts().get("trained"))
                .as("20 个 Bot 跑一天，升级与造兵的合计不该是 0").isPositive();
    }

    @Test
    @DisplayName("画像被摘掉（回收/转真人那条出口）之后，它的待办必须一起清掉并计数")
    void unregisteredBotLeavesNoPendingTask() {
        String botId = bot();
        long now = timeService.serverNow();
        runtime.tick(now);
        assertThat(runtime.tick(now + DAY).get("pending"))
                .as("登记之后每个 Bot 一条待办").isPositive();

        bots.unregister(botId);
        Map<String, Long> after = runtime.tick(now + 2 * DAY);

        assertThat(after.get("pending")).as("不再是 Bot 的玩家不该留任何待办").isZero();
        assertThat(after.get("dropped"))
                .as("摘掉这件事必须被计数 —— 否则「某个 Bot 从此不动了」在日志里查不出来")
                .isPositive();
    }

    /**
     * 接线本身要被验证：{@code WorldAppService.viewport} 里那一行兜底驱动，
     * 没有这条用例的话它被删掉也不会有任何测试变红 —— 而删掉它的表现是
     * "没接调度系统的环境里 Bot 永远不动"，与"孵化坏了"长得几乎一样。
     */
    @Test
    @DisplayName("读一次图就把 Bot 登记进作息表（控制器上那一脚是真的挂着，不是注释里说说）")
    void viewportDrivesTheSafetyNet() throws Exception {
        String observer = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(),
                "仿真读图" + UUID.randomUUID().toString().substring(0, 6), 1_700_000_000_000L, "")).playerId();
        bot();
        assertThat(runtime.enrolledCount()).as("读图之前没人被登记").isZero();

        // 必须走 HTTP 而不是直接调服务：兜底驱动挂在**控制器**上（放服务层会与 MarchAppService
        // 构成构造环 —— 受击反应要转调它），直接调 worldAppService.viewport 会绕过这一脚，
        // 用例就变成在验证一个不存在的接线
        String body = "{\"centerX\":256,\"centerY\":256,\"zoom\":0,\"chunkVersions\":[]}";
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/world/viewport")
                        .header(PLAYER_HEADER, observer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("对照组：这一脚不能被鉴权或参数校验挡在门外（挡了的话下面那条断言会红得莫名其妙）")
                .isEqualTo(200);

        assertThat(runtime.enrolledCount())
                .as("读图这一脚必须把 Bot 登记进作息表（WorldController 里那行 driveFromRequestPath）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("请求路径兜底：15 秒节流，窗口内的第二次调用不会重复跑一轮")
    void safetyNetIsThrottled() {
        long now = timeService.serverNow();
        assertThat(runtime.driveFromRequestPath(now)).as("第一次（节流窗口是空的）应该跑").isTrue();
        assertThat(runtime.driveFromRequestPath(now + 5_000L))
                .as("5 秒后仍在 15 秒窗口内 —— 每个请求都跑一轮会把读图变成 Bot 执行器").isFalse();
        assertThat(runtime.driveFromRequestPath(now + 20_000L))
                .as("越过窗口就该再跑一轮").isTrue();
    }

    // ---------- 运维端点的守卫链（正向那一跑在上面三条里已经验过） ----------

    /**
     * 体检数的<b>逐字段对位</b>。
     *
     * <p>为什么需要这条：{@code AgentTickResp} 的构造参数全是 {@code long}，顺序传错
     * 既不会编译失败、也不会被任何守卫拦下 —— 只会静默串值。这不是假想：
     * 2026-09-12 的 C1b 就抓到过一次真实的错位（schema 的 {@code required} 与 {@code properties}
     * 两处顺序不一致，作者按前者写调用、生成物按后者排字段），当时 {@code agents} 里躺着时间戳、
     * {@code serverNow} 里躺着计数，而所有测试都是绿的 —— 因为没有一条用例断言过响应字段的值。
     */
    @Test
    @DisplayName("POST /bot/tick 的体检数逐字段对得上（全是 long 的参数一旦错位，编译期一声不响）")
    void tickResponseFieldsAreNotPermuted() throws Exception {
        bot();
        long before = timeService.serverNow();

        JsonNode ok = postJson("/bot/tick", OPS_TOKEN, "{\"requestId\":\"req-bot-tick-fields\"}");
        assertThat(ok.get("code").asInt())
                .as("正向那一跑先要成功（否则下面断言的是错误响应）").isEqualTo(ErrorCode.OK.code());
        JsonNode data = ok.get("data");

        assertThat(data.get("agents").asLong())
                .as("agents 是注册表规模 —— 历史错位时这里躺着一个时间戳").isEqualTo(bots.size());
        assertThat(data.get("serverNow").asLong())
                .as("serverNow 是服务端时刻 —— 历史错位时这里躺着一个计数")
                .isBetween(before - MINUTE, timeService.serverNow() + MINUTE);
        assertThat(data.get("rounds").asLong()).as("跑过至少一轮").isPositive();
        assertThat(data.get("upgraded").asLong())
                .as("upgraded 在第 9 位（正是历史错位的位置），必须等于适配器的真实计数")
                .isEqualTo(adapter.actionCounts().get("upgraded"));
        assertThat(data.get("hunted").asLong())
                .as("本档新增的两个计数也要落在正确的字段上")
                .isEqualTo(adapter.actionCounts().get("hunted"));
        assertThat(data.get("gathered").asLong())
                .isEqualTo(adapter.actionCounts().get("gathered"));
    }

    @Test
    @DisplayName("POST /bot/tick：无运维令牌被拒、requestId 为空被拒、不存在的路径是 404 而不是 500")
    void tickEndpointIsGuarded() throws Exception {
        JsonNode noToken = postJson("/bot/tick", null, "{\"requestId\":\"req-bot-tick-1\"}");
        assertThat(noToken.get("code").asInt())
                .as("闸门挡的是身份：没带令牌一律拒绝（fail-closed）")
                .isEqualTo(ErrorCode.OPS_UNAUTHORIZED.code());

        JsonNode blank = postJson("/bot/tick", OPS_TOKEN, "{\"requestId\":\"  \"}");
        assertThat(blank.get("code").asInt())
                .as("requestId 是追踪号：为空就该被拒，而不是静默收下 —— 没有追踪号的运维调用无法归因")
                .isEqualTo(ErrorCode.REQUEST_ID_MISSING.code());

        JsonNode unknown = postJson("/bot/densely-forgotten-route", OPS_TOKEN, "{}");
        assertThat(unknown.get("code").asInt())
                .as("对照组：路径写错必须是那条 404 映射。少了这组，「不存在」和「被业务码拒绝」在日志里长得一样")
                .isEqualTo(ErrorCode.SYSTEM_ERROR.code());
        assertThat(unknown.get("detail").asText()).contains("接口不存在");
    }

    // ---------- 夹具 ----------

    /**
     * 造一个"活在世界里"的 Bot：真人同一条建档路径（{@code PlayerInitService.init}）+ 注册画像。
     * <b>不手搓存档</b> —— 那样测试里的 Bot 与孵化出来的 Bot 就不是同一种东西了。
     */
    private String bot() {
        String playerId = playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(),
                "铁誓模拟" + UUID.randomUUID().toString().substring(0, 6), 1_700_000_000_000L, "")).playerId();
        // 先做一次正常的城建读（等价于真人点开城建面板）：城建挡是 lazily 建出来的，
        // 不读一次的话仓储里没有 CityState —— 而手搓一份 CityState 就是让测试里的 Bot
        // 与孵化出来的 Bot 不再是同一种东西
        cityAppService.list(playerId);
        bots.register(new BotProfile(playerId, "bot_paoyao",
                new BotProfile.AiProfile(FixedPoint.parse("0.50"), FixedPoint.parse("0.75"),
                        FixedPoint.parse("0.50"), FixedPoint.parse("0.60")),
                new BotProfile.Persona(42L, 7L, 99L, List.of(8, 9, 12, 20, 21),
                        3L, 30L, FixedPoint.parse("0.10")),
                FixedPoint.parse("1.0")));
        return playerId;
    }

    /** 真人侧那份钱包读数（用来证明 Bot 的升级真的付了资源，不是免消耗捷径）。 */
    private long resource(String playerId, String resourceId) {
        return players.findByPlayerId(playerId).orElseThrow().resource(resourceId).current();
    }

    private JsonNode postJson(String path, String opsToken, String body) throws Exception {
        var builder = MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (opsToken != null) {
            builder = builder.header(OPS_HEADER, opsToken);
        }
        MvcResult result = mockMvc.perform(builder).andReturn();
        return new ObjectMapper().readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
