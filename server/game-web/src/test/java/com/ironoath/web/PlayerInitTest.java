package com.ironoath.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryPlayerStore;
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 职责：/player/init 全链路集成测试 —— 覆盖 B01 验收 1 与验收 11。
 * 依赖：Spring Boot Test + MockMvc；跑在 test profile 下（内存存储，不需要 MongoDB/Redis）。
 *
 * <p>这个测试证明的是 B01 的一句话交付物：
 * 「客户端登录 → 服务端返回玩家初始存档」这条链路真的能跑通，
 * 而且初始数值全部来自配置表（改配置表就能改结果，不需要改代码 —— 铁律 6）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlayerInitTest {

    private static final String INIT_URL = "/player/init";
    private static final String TIME_SYNC_URL = "/time/sync";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConfigRegistry configs;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private PlayerInitService playerInitService;

    @BeforeEach
    void resetStore() {
        // 内存存储跨测试方法共享，不清空会让「只创建一次玩家」的断言被上一个测试的数据污染
        ((InMemoryPlayerStore) players).clear();
    }

    @Test
    @DisplayName("验收1：POST /player/init 返回 resource 表里全部资源=配置初始值、cityLevel=1")
    void initReturnsConfiguredInitialState() throws Exception {
        MvcResult result = mockMvc.perform(post(INIT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(newInitReq())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("成功"))
                .andExpect(jsonPath("$.data.cityLevel").value(configs.longParam("INIT_CITY_LEVEL")))
                .andExpect(jsonPath("$.data.playerId").isNotEmpty())
                .andExpect(jsonPath("$.data.serverNow").isNumber())
                .andReturn();

        JsonNode data = JsonUtils.readTree(body(result)).get("data");

        // 资源种类与数量必须与配置表完全一致：不多不少
        JsonNode resources = data.get("resources");
        assertThat(resources.size()).isEqualTo(configs.allResources().size());
        assertThat(configs.allResources()).hasSize(6);   // B09 起含 STAMINA（体力）

        long protectRatio = configs.fixedParam("RESOURCE_PROTECT_RATIO");
        for (var cfg : configs.allResources()) {
            JsonNode state = resources.get(cfg.id());
            assertThat(state).as("资源 %s 应出现在响应中", cfg.id()).isNotNull();
            assertThat(state.get("current").asLong())
                    .as("%s 初始量应等于配置 initAmount", cfg.id())
                    .isEqualTo(Math.min(cfg.initAmount(), cfg.initCap()));
            // 新号既没有产出建筑也没有仓库，所以容量与产率就应等于配置表的初始值
            assertThat(state.get("cap").asLong())
                    .as("%s 容量应等于配置 initCap（新号还没建仓库）", cfg.id())
                    .isEqualTo(cfg.initCap());
            assertThat(state.get("perHour").asLong())
                    .as("%s 每小时产量应等于配置 basePerHour（新号还没建产出建筑）", cfg.id())
                    .isEqualTo(cfg.basePerHour());
            // 保护额度 = 容量 × RESOURCE_PROTECT_RATIO，且只对 BASE 资源成立：
            // 付费货币不参与保护（被抢等于直接拿走玩家花的钱），
            // 体力也不参与（体力不在仓库里，「被抢走一部分体力」在物理上说不通）
            long expectedProtected = cfg.kind() == com.ironoath.config.cfg.ResourceCfg.Kind.BASE
                    ? com.ironoath.core.resource.ResourceProtection.protectedAmount(cfg.initCap(), protectRatio)
                    : 0L;
            assertThat(state.get("protectedAmount").asLong())
                    .as("%s 保护额度应等于 cap × RESOURCE_PROTECT_RATIO（非 BASE 类为 0）", cfg.id())
                    .isEqualTo(expectedProtected);
            assertThat(state.get("lastSettle").asLong())
                    .as("%s 的 lastSettle 应等于响应的 serverNow（惰性结算起点）", cfg.id())
                    .isEqualTo(data.get("serverNow").asLong());
        }
    }

    @Test
    @DisplayName("初始战力与新手保护期均来自配置表，代码中无硬编码")
    void initialPowerAndProtectionComeFromConfig() throws Exception {
        MvcResult result = mockMvc.perform(post(INIT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(newInitReq())))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = JsonUtils.readTree(body(result)).get("data");
        long serverNow = data.get("serverNow").asLong();

        long expectedPower = configs.longParam("INIT_MATCH_POWER");
        assertThat(data.get("power").get("displayPower").asLong()).isEqualTo(expectedPower);
        assertThat(data.get("power").get("matchPower").asLong()).isEqualTo(expectedPower);
        assertThat(data.get("power").get("peakPower").asLong()).isEqualTo(expectedPower);

        long expectedProtect = serverNow + configs.longParam("NEWCOMER_PROTECT_SECONDS") * 1000L;
        assertThat(data.get("protectUntil").asLong()).isEqualTo(expectedProtect);
        assertThat(data.get("profile").get("avatarId").asInt())
                .isEqualTo((int) configs.longParam("INIT_AVATAR_ID"));
    }

    @Test
    @DisplayName("验收11：同一 requestId 重复提交，只创建一次玩家")
    void duplicateRequestIdCreatesOnlyOnePlayer() throws Exception {
        PlayerInitReq req = newInitReq();

        String first = postInit(req);
        String second = postInit(req);

        String firstPlayerId = JsonUtils.readTree(first).get("data").get("playerId").asText();
        String secondPlayerId = JsonUtils.readTree(second).get("data").get("playerId").asText();

        assertThat(secondPlayerId).as("重复 requestId 必须返回同一玩家").isEqualTo(firstPlayerId);
        assertThat(((InMemoryPlayerStore) players).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("同一 deviceId 换 requestId 重复 init：等同登录，仍只有一个玩家")
    void sameDeviceWithDifferentRequestIdIsLogin() throws Exception {
        String deviceId = newDeviceId();
        String first = postInit(new PlayerInitReq(newRequestId(), deviceId, "流亡王裔", 1_700_000_000_000L, ""));
        String second = postInit(new PlayerInitReq(newRequestId(), deviceId, "改名试试", 1_700_000_000_500L, ""));

        JsonNode firstData = JsonUtils.readTree(first).get("data");
        JsonNode secondData = JsonUtils.readTree(second).get("data");

        assertThat(secondData.get("playerId").asText()).isEqualTo(firstData.get("playerId").asText());
        assertThat(((InMemoryPlayerStore) players).size()).isEqualTo(1);
        // 重复 init 不得用请求里的新昵称覆盖已有存档（改名是独立功能，B12 交付）
        assertThat(secondData.get("profile").get("nickName").asText()).isEqualTo("流亡王裔");
        // lastLoginAt 应被刷新
        assertThat(secondData.get("profile").get("lastLoginAt").asLong())
                .isGreaterThanOrEqualTo(firstData.get("profile").get("lastLoginAt").asLong());
    }

    @Test
    @DisplayName("登录响应带回挂机期间的产出，且读路径不写存档")
    void loginResponseSettlesResourcesWithoutPersisting() throws Exception {
        String deviceId = newDeviceId();
        JsonNode first = JsonUtils.readTree(postInit(new PlayerInitReq(
                newRequestId(), deviceId, "挂机王裔", 1_700_000_000_000L, ""))).get("data");
        String playerId = first.get("playerId").asText();
        long initWood = first.get("resources").get("WOOD").get("current").asLong();
        long perHour = configs.getResource("WOOD").basePerHour();
        assertThat(perHour).as("夹具前提：木头会产出，否则这条断言证明不了结算发生过").isPositive();

        // 把结算基准拨回一小时 = 模拟「挂机一小时后重新打开游戏」
        com.ironoath.core.player.PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        var wood = save.resources().get("WOOD");
        save.putResource("WOOD", new com.ironoath.core.player.PlayerResourceState(
                wood.current(), wood.cap(), wood.protectedAmount(), wood.perHour(),
                System.currentTimeMillis() - 3_600_000L));
        players.save(save);

        JsonNode again = JsonUtils.readTree(postInit(new PlayerInitReq(
                newRequestId(), deviceId, "挂机王裔", 1_700_000_000_000L, ""))).get("data");

        assertThat(again.get("resources").get("WOOD").get("current").asLong())
                .as("登录载荷必须带上这一小时的产出：B00 要求客户端从不结算，服务端不给它就永远显示旧存量")
                .isEqualTo(initWood + perHour);
        assertThat(players.findByPlayerId(playerId).orElseThrow().resources().get("WOOD").current())
                .as("结算是纯函数、下一次真结算会算出同样的量，所以登录这种高并发读入口刻意不写存档"
                        + "（写了就要撞乐观锁，一次登录会变成可失败的写操作）")
                .isEqualTo(initWood);
    }

    @Test
    @DisplayName("并发建号：20 个线程同时用同一 deviceId 请求，最终只有一份存档")
    void concurrentInitCreatesExactlyOnePlayer() throws Exception {
        String deviceId = newDeviceId();
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<String>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                PlayerInitReq req = new PlayerInitReq(newRequestId(), deviceId, "并发王裔" + i,
                        1_700_000_000_000L + i, "");
                tasks.add(() -> json(playerInitService.init(req)));
            }
            List<Future<String>> futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);

            Set<String> playerIds = new HashSet<>();
            for (Future<String> f : futures) {
                playerIds.add(JsonUtils.readTree(f.get()).get("playerId").asText());
            }
            assertThat(playerIds).as("20 个并发请求必须收敛到同一个玩家").hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(((InMemoryPlayerStore) players).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("服务端独立校验：缺 requestId / deviceId 过短 / 昵称超长 / 含控制字符 均被拒绝")
    void serverSideValidationIsIndependent() throws Exception {
        long maxNick = configs.longParam("NICKNAME_MAX_LENGTH");

        // requestId 缺失
        expectErrorCode(new PlayerInitReq("", newDeviceId(), "王裔", 1L, ""), 1003);
        // requestId 过短
        expectErrorCode(new PlayerInitReq("abc", newDeviceId(), "王裔", 1L, ""), 1003);
        // deviceId 过短
        expectErrorCode(new PlayerInitReq(newRequestId(), "short", "王裔", 1L, ""), 2002);
        // 昵称超长
        expectErrorCode(new PlayerInitReq(newRequestId(), newDeviceId(), "王".repeat((int) maxNick + 1), 1L, ""), 2003);
        // 昵称含换行（可伪造日志行）
        expectErrorCode(new PlayerInitReq(newRequestId(), newDeviceId(), "王裔\n伪造日志", 1L, ""), 2003);
        // clientTime 非法
        expectErrorCode(new PlayerInitReq(newRequestId(), newDeviceId(), "王裔", 0L, ""), 1001);

        assertThat(((InMemoryPlayerStore) players).size()).as("非法请求不得产生任何存档").isZero();
    }

    private void expectErrorCode(PlayerInitReq req, int expectedCode) throws Exception {
        mockMvc.perform(post(INIT_URL).contentType(MediaType.APPLICATION_JSON).content(json(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("每个响应都带 traceId 与 serverNow，且响应头回传 X-Trace-Id（铁律 10）")
    void everyResponseCarriesTraceIdAndServerNow() throws Exception {
        String clientTraceId = "trace-" + UUID.randomUUID();
        mockMvc.perform(post(INIT_URL)
                        .header("X-Trace-Id", clientTraceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(newInitReq())))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", clientTraceId))
                .andExpect(jsonPath("$.traceId").value(clientTraceId))
                .andExpect(jsonPath("$.serverNow").isNumber());
    }

    @Test
    @DisplayName("客户端未带 traceId 时服务端生成一个，并回传给客户端用于报障")
    void serverGeneratesTraceIdWhenAbsent() throws Exception {
        MvcResult result = mockMvc.perform(post(INIT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(newInitReq())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn();
        String header = result.getResponse().getHeader("X-Trace-Id");
        String traceIdInBody = JsonUtils.readTree(body(result)).get("traceId").asText();
        assertThat(header).isEqualTo(traceIdInBody);
    }

    @Test
    @DisplayName("/time/sync 返回 offset = serverNow - clientTime")
    void timeSyncReturnsOffset() throws Exception {
        long clientTime = 1_700_000_000_000L;
        MvcResult result = mockMvc.perform(post(TIME_SYNC_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientTime\":" + clientTime + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sync.syncAt").isNumber())
                .andReturn();

        JsonNode sync = JsonUtils.readTree(body(result))
                .get("data").get("sync");
        // offset 必须等于 syncAt - clientTime（服务端时钟在请求处理期间可能前进几毫秒，故用 syncAt 校验）
        assertThat(sync.get("offset").asLong()).isEqualTo(sync.get("syncAt").asLong() - clientTime);
    }

    @Test
    @DisplayName("请求体格式错误返回 PARAM_INVALID，而不是 500")
    void malformedBodyReturnsParamInvalid() throws Exception {
        mockMvc.perform(post(INIT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{这不是合法JSON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    @DisplayName("不存在的路径返回 404，而不是 500（否则扫路径的机器人会灌满 5xx 告警）")
    void unknownPathReturnsNotFoundNotServerError() throws Exception {
        // 缺这个映射时 Spring 6.1 抛的 NoResourceFoundException 会掉进兜底分支，
        // 客户端看到的是「系统繁忙 + 500」—— 一个永久性的客户端错误被报成服务端故障
        mockMvc.perform(get("/social/helpRequests/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.detail").value("接口不存在"));
    }

    // ---------- 辅助 ----------

    /**
     * 用 UTF-8 解码响应体。
     *
     * <p>MockMvc 的 {@code getContentAsString()} 在响应头没有 charset 参数时默认按 ISO-8859-1 解码，
     * 会把中文变成 mojibake。JSON 按 RFC 8259 就是 UTF-8（且不带 charset 参数），所以必须显式指定。
     */
    private static String body(MvcResult result) throws java.io.UnsupportedEncodingException {
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String postInit(PlayerInitReq req) throws Exception {
        MvcResult result = mockMvc.perform(post(INIT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return body(result);
    }

    private static PlayerInitReq newInitReq() {
        return new PlayerInitReq(newRequestId(), newDeviceId(), "流亡王裔", 1_700_000_000_000L, "");
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private static String newDeviceId() {
        return "dev-" + UUID.randomUUID();
    }

    private static String json(Object value) {
        return JsonUtils.toJson(value);
    }
}
