package com.ironoath.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ResourceCfg;
import com.ironoath.core.city.BuildingInstance;
import com.ironoath.core.city.BuildingStatus;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.city.CityState;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.StaminaBuyReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.StaminaService;
import com.ironoath.web.store.memory.InMemoryCityStore;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 职责：B09 §5 体力系统的端到端验证 —— 验收 6（溢出不超上限、恢复时间计算正确）与购买路径。
 * 依赖：Spring Boot Test + MockMvc；test profile（内存存储）。
 *
 * <p><b>体力做成 resource 表的一行是有意的</b>，本类的用例因此同时验证了「复用是对的」：
 * 恢复靠惰性结算、溢出靠容量截断、上限靠产率服务重算 —— 三件事都不是体力专属代码。
 * 如果哪天有人把体力拆成独立系统，这里最先变红的会是恢复与溢出两条，
 * 而那两条恰恰是最容易在新实现里写错、又最难被玩家自己发现的（少恢复 1 点没人会报 bug）。
 *
 * <p><b>零头结转是体力能工作的前提</b>：每 6 分钟恢复 1 点，而玩家的上线间隔通常短于 6 分钟。
 * 若结算把不足 1 点的时间零头丢掉（{@code ResourceSettlement} 曾经就是这样），
 * 频繁上线的玩家体力会永远停在 0。本类的恢复用例就是那条修复的端到端证据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaminaEndpointTest {

    private static final String VIEW_URL = "/stamina";
    private static final String BUY_URL = "/stamina/buy";
    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 3_600_000L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ConfigRegistry configs;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private StaminaService staminaService;
    @Autowired private PlayerRepository players;
    @Autowired private CityRepository cities;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
    }

    // ---------- 验收 6 ----------

    @Test
    @DisplayName("新号体力等于基准上限，且 resource.initCap 与 STAMINA_CAP_BASE 一致")
    void newPlayerStartsAtBaseCapAndConfigIsConsistent() throws Exception {
        String playerId = newPlayer();
        JsonNode data = get200(VIEW_URL, playerId);

        ResourceCfg stamina = configs.getResource(StaminaService.RESOURCE_ID);
        assertThat(stamina.kind()).isEqualTo(ResourceCfg.Kind.STAMINA);
        assertThat(data.get("cap").asLong())
                .as("1 级主城时上限必须正好等于基准值，否则新号第一次打开面板就看到容量跳变")
                .isEqualTo(configs.longParam("STAMINA_CAP_BASE"));
        assertThat(stamina.initCap())
                .as("建档用的 initCap 与随等级成长的公式常数项必须是同一个数")
                .isEqualTo(configs.longParam("STAMINA_CAP_BASE"));
        assertThat(data.get("current").asLong()).isEqualTo(stamina.initAmount());
        assertThat(data.get("recoverPerHour").asLong())
                .as("恢复速率来自 resource 表的 basePerHour，不在代码里另写一份")
                .isEqualTo(stamina.basePerHour());
        assertThat(data.get("nextPointAt").isNull())
                .as("已满时不该给倒计时：一个永远走不完的进度条比没有进度条更让人困惑")
                .isTrue();
    }

    @Test
    @DisplayName("验收6：恢复时间计算正确 —— 每 6 分钟 1 点，且下一点时刻可被客户端直接用于倒计时")
    void recoveryAccruesAtTheConfiguredRate() throws Exception {
        String playerId = newPlayer();
        drainStamina(playerId);

        rewindStamina(playerId, 6 * MINUTE);
        JsonNode afterSix = get200(VIEW_URL, playerId);
        assertThat(afterSix.get("current").asLong()).as("6 分钟恢复 1 点").isEqualTo(1L);
        long serverNow = afterSix.get("serverNow").asLong();
        long period = HOUR / afterSix.get("recoverPerHour").asLong();
        long nextPointAt = afterSix.get("nextPointAt").asLong();
        // 不断言成 serverNow + period：夹具回拨与这次请求之间隔着几百微秒的测试执行时间，
        // 那段时间已经计入 lastSettle，于是 nextPointAt 会比 serverNow + period 早一点点。
        // 真正要保证的性质是「倒计时为正、且不超过一个恢复周期」——
        // 为负会让客户端显示一个已经过期却还在走的进度条，超过一个周期则说明恢复速率算错了
        assertThat(nextPointAt).as("下一点必须在未来").isGreaterThan(serverNow);
        assertThat(nextPointAt).as("下一点不得超过一个恢复周期").isLessThanOrEqualTo(serverNow + period);

        // 从零开始挂机一小时：10 点/小时 ⇒ 10 点。
        // 这里刻意分 12 次、每次 5 分钟地读 —— 读取频率高于恢复周期，
        // 若零头不结转，每次读都会把不足 1 点的时间丢掉，一小时后仍然是 0
        drainStamina(playerId);
        for (int i = 0; i < 12; i++) {
            rewindStamina(playerId, 5 * MINUTE);
            get200(VIEW_URL, playerId);
        }
        JsonNode afterHour = get200(VIEW_URL, playerId);
        assertThat(afterHour.get("current").asLong())
                .as("上线频率高于恢复周期不该让恢复停下来")
                .isEqualTo(10L);
    }

    @Test
    @DisplayName("验收6：溢出不超上限 —— 挂机一百小时也只到 cap，且满了之后不再给倒计时")
    void recoveryNeverExceedsCap() throws Exception {
        String playerId = newPlayer();
        drainStamina(playerId);
        rewindStamina(playerId, 100 * HOUR);

        JsonNode data = get200(VIEW_URL, playerId);
        assertThat(data.get("current").asLong())
                .as("B09 §5 禁止项：不要让体力溢出超过上限")
                .isEqualTo(data.get("cap").asLong());
        assertThat(data.get("nextPointAt").isNull()).isTrue();

        // 再挂一小时：仍然不能超上限（满仓停产，且不结转）
        rewindStamina(playerId, HOUR);
        JsonNode stillFull = get200(VIEW_URL, playerId);
        assertThat(stillFull.get("current").asLong()).isEqualTo(stillFull.get("cap").asLong());
    }

    @Test
    @DisplayName("上限随主城等级提升：升级中的那一级不算，与「升级中的建筑不产资源」同一口径")
    void capGrowsWithMainCityLevel() throws Exception {
        String playerId = newPlayer();
        // 城建存档是惰性创建的：玩家第一次碰城建或体力端点时才有。
        // 直接改主城等级会拿到一个空 Optional
        get200(VIEW_URL, playerId);
        long perLevel = configs.longParam("STAMINA_CAP_PER_LEVEL");
        long base = configs.longParam("STAMINA_CAP_BASE");

        setMainCityLevel(playerId, 5, BuildingStatus.IDLE);
        assertThat(get200(VIEW_URL, playerId).get("cap").asLong())
                .as("5 级主城 = 基准 + 每级增量 × 4")
                .isEqualTo(base + perLevel * 4L);

        // 5 级、正在升往 6 级：level() 在升级期间就是「已达到的等级」，完成后才 +1，
        // 所以上限必须停在 5 级的值。这条断言真正防的是另一种写法 ——
        // 「跳过升级中的建筑」，那会让玩家一开工升级主城，体力上限就掉回 1 级，
        // 而且不报错，只表现为「升级期间体力上限莫名其妙变少了」
        setMainCityLevel(playerId, 5, BuildingStatus.UPGRADING);
        assertThat(get200(VIEW_URL, playerId).get("cap").asLong())
                .as("升级中不抬高上限，也不该掉回 1 级")
                .isEqualTo(base + perLevel * 4L);

        setMainCityLevel(playerId, 6, BuildingStatus.IDLE);
        assertThat(get200(VIEW_URL, playerId).get("cap").asLong())
                .as("升级完成后上限才跟着抬高")
                .isEqualTo(base + perLevel * 5L);
    }

    // ---------- 购买 ----------

    @Test
    @DisplayName("购买体力：扣金币、加体力、单价按当日已购次数递增")
    void buyingStaminaCostsEscalatingGold() throws Exception {
        String playerId = newPlayer();
        drainStamina(playerId);
        // 递增单价下连买 4 次要 355 金币，而新号只有 200。
        // 本用例验的是单价递增，不是金币经济，所以直接把钱充够
        setGold(playerId, 1_000_000L);
        long goldBefore = goldOf(playerId);
        long perAmount = configs.longParam("STAMINA_BUY_AMOUNT");

        JsonNode first = post200(BUY_URL, playerId, new StaminaBuyReq(newRequestId(), null));
        assertThat(first.get("granted").asLong()).isEqualTo(perAmount);
        assertThat(first.get("costGold").asLong()).isEqualTo(staminaService.nextCostGold(0));
        assertThat(first.get("boughtToday").asLong()).isEqualTo(1L);
        assertThat(goldOf(playerId)).isEqualTo(goldBefore - first.get("costGold").asLong());
        assertThat(first.get("stamina").get("current").asLong()).isEqualTo(perAmount);
        assertThat(first.get("stamina").get("buyCostGold").asLong())
                .as("第二次购买的单价必须高于第一次")
                .isEqualTo(staminaService.nextCostGold(1))
                .isGreaterThan(first.get("costGold").asLong());

        // 一次买 3 次：单价逐次递增，所以总价不是「首个单价 × 3」
        JsonNode bulk = post200(BUY_URL, playerId, new StaminaBuyReq(newRequestId(), 3));
        long expected = staminaService.nextCostGold(1) + staminaService.nextCostGold(2)
                + staminaService.nextCostGold(3);
        assertThat(bulk.get("costGold").asLong())
                .as("连买必须按递增单价逐次累加；合并成一次请求是为了只扣一次锁")
                .isEqualTo(expected);
        assertThat(bulk.get("boughtToday").asLong()).isEqualTo(4L);
    }

    @Test
    @DisplayName("达到每日上限后给明确提示「明日重置」，而不是静默失败或一个空列表")
    void dailyLimitIsAnnouncedNotSilent() throws Exception {
        String playerId = newPlayer();
        drainStamina(playerId);
        // 买满 10 次按 1.5 递增要 5663 金币，同样先充够
        setGold(playerId, 1_000_000L);
        long limit = configs.longParam("STAMINA_BUY_DAILY_LIMIT");
        // 一次性买到上限：请求更多时服务端截断到剩余次数，不报错
        JsonNode bulk = post200(BUY_URL, playerId, new StaminaBuyReq(newRequestId(), (int) limit + 5));
        assertThat(bulk.get("boughtToday").asLong()).isEqualTo(limit);
        assertThat(bulk.get("stamina").get("buyCostGold").asLong())
                .as("达到上限后单价下发 0，客户端据此把按钮置灰而不是隐藏")
                .isZero();

        JsonNode root = postRoot(BUY_URL, playerId, new StaminaBuyReq(newRequestId(), 1));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.RATE_LIMITED.code());
        assertThat(root.get("msg").asText() + root.get("detail").asText())
                .as("B09 验收 3 的口径：次数耗尽必须明确说明何时重置")
                .contains("明日");
    }

    @Test
    @DisplayName("金币不足时不扣次数：一次余额不足不该让玩家白白损失一次购买机会")
    void insufficientGoldDoesNotConsumeTheDailyQuota() throws Exception {
        String playerId = newPlayer();
        drainStamina(playerId);
        drainGold(playerId);
        long boughtBefore = get200(VIEW_URL, playerId).get("boughtToday").asLong();

        JsonNode root = postRoot(BUY_URL, playerId, new StaminaBuyReq(newRequestId(), 1));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.RESOURCE_NOT_ENOUGH.code());
        assertThat(get200(VIEW_URL, playerId).get("boughtToday").asLong())
                .as("失败后次数必须退还")
                .isEqualTo(boughtBefore);
    }

    @Test
    @DisplayName("接近上限时购买：超出部分永久损失，但响应里照实说明给了多少")
    void buyingNearCapReportsTheOverflowHonestly() throws Exception {
        String playerId = newPlayer();
        long cap = get200(VIEW_URL, playerId).get("cap").asLong();
        long perAmount = configs.longParam("STAMINA_BUY_AMOUNT");
        setStamina(playerId, cap - 5L);

        JsonNode resp = post200(BUY_URL, playerId, new StaminaBuyReq(newRequestId(), 1));
        assertThat(resp.get("granted").asLong())
                .as("请求 %d 点但只剩 5 点空间，超出部分不结转（B09 §5：溢出不超上限）", perAmount)
                .isEqualTo(5L);
        assertThat(resp.get("stamina").get("current").asLong()).isEqualTo(cap);
        assertThat(resp.get("costGold").asLong())
                .as("金币照扣：溢出是玩家自己的选择，但客户端应当在购买前用 cap 提示他")
                .isEqualTo(staminaService.nextCostGold(0));
    }

    @Test
    @DisplayName("同一个 requestId 只生效一次：买体力要扣金币，没有幂等就等于允许重放刷体力")
    void buyIsIdempotentByRequestId() throws Exception {
        String playerId = newPlayer();
        drainStamina(playerId);
        String requestId = newRequestId();
        long goldBefore = goldOf(playerId);

        JsonNode first = post200(BUY_URL, playerId, new StaminaBuyReq(requestId, 1));
        JsonNode replay = postRoot(BUY_URL, playerId, new StaminaBuyReq(requestId, 1));
        assertThat(replay.get("code").asInt()).isEqualTo(ErrorCode.REQUEST_DUPLICATED.code());
        assertThat(goldOf(playerId))
                .as("重放不得二次扣款").isEqualTo(goldBefore - first.get("costGold").asLong());
        assertThat(get200(VIEW_URL, playerId).get("boughtToday").asLong()).isEqualTo(1L);
    }

    // ---------- 夹具 ----------

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "体力测试", 1_700_000_000_000L))
                .playerId();
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private void setStamina(String playerId, long current) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        PlayerResourceState s = save.resource(StaminaService.RESOURCE_ID);
        save.putResource(StaminaService.RESOURCE_ID, new PlayerResourceState(
                current, s.cap(), s.protectedAmount(), s.perHour(), s.lastSettle()));
        players.save(save);
    }

    private void drainStamina(String playerId) {
        setStamina(playerId, 0L);
    }

    /** 把体力的结算基准往回拨，模拟「已经过了这么久」。 */
    private void rewindStamina(String playerId, long millis) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        PlayerResourceState s = save.resource(StaminaService.RESOURCE_ID);
        save.putResource(StaminaService.RESOURCE_ID, new PlayerResourceState(
                s.current(), s.cap(), s.protectedAmount(), s.perHour(), s.lastSettle() - millis));
        players.save(save);
    }

    private long goldOf(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow()
                .resource(StaminaService.GOLD_ID).current();
    }

    private void setGold(String playerId, long amount) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        PlayerResourceState s = save.resource(StaminaService.GOLD_ID);
        save.putResource(StaminaService.GOLD_ID, new PlayerResourceState(
                amount, Math.max(s.cap(), amount), s.protectedAmount(), s.perHour(), s.lastSettle()));
        players.save(save);
    }

    private void drainGold(String playerId) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        PlayerResourceState s = save.resource(StaminaService.GOLD_ID);
        save.putResource(StaminaService.GOLD_ID, new PlayerResourceState(
                0L, s.cap(), s.protectedAmount(), s.perHour(), s.lastSettle()));
        players.save(save);
    }

    /** 直接改主城等级，跳过资源与时间：本类验的是体力上限公式，不是升级流程。 */
    private void setMainCityLevel(String playerId, int level, BuildingStatus status) {
        CityState city = cities.findByPlayerId(playerId).orElseThrow();
        long version = cities.versionOf(playerId);
        for (BuildingInstance b : city.buildings()) {
            if (configs.get(com.ironoath.config.cfg.BuildingCfg.class, b.configId()).type()
                    != com.ironoath.config.cfg.BuildingCfg.Type.CORE) {
                continue;
            }
            b.restore(level, b.gridX(), b.gridY(), status,
                    status == BuildingStatus.UPGRADING ? System.currentTimeMillis() + HOUR : null,
                    0L, 1L, 1L, b.helpCount(), b.lastMovedAt(), b.lastFinishedAt());
        }
        cities.save(playerId, city, version);
    }

    private JsonNode get200(String url, String playerId) throws Exception {
        MockHttpServletRequestBuilder builder = get(url).header(PLAYER_HEADER, playerId);
        return okData(perform(builder));
    }

    private JsonNode post200(String url, String playerId, Object req) throws Exception {
        return okData(postRoot(url, playerId, req));
    }

    private JsonNode postRoot(String url, String playerId, Object req) throws Exception {
        MockHttpServletRequestBuilder builder = post(url)
                .header(PLAYER_HEADER, playerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(req));
        return perform(builder);
    }

    private JsonNode perform(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        // MockMvc 默认按 ISO-8859-1 解码响应体，中文提示会变乱码
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode okData(JsonNode root) {
        assertThat(root.get("code").asInt())
                .as("业务码必须为 0，实际响应=%s", root).isZero();
        return root.get("data");
    }
}
