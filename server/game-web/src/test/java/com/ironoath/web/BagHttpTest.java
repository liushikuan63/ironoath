package com.ironoath.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ChestCfg;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.dto.generated.ItemUseReq;
import com.ironoath.web.dto.generated.OpenBatchReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryInventoryStore;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 职责：B04 四个端点的 <b>HTTP 层</b>验证 —— 路由、身份头、查询参数、JSON 序列化与中文编码。
 * 依赖：Spring Boot Test + MockMvc；test profile（内存存储，不需要 MongoDB / Redis）。
 *
 * <p><b>为什么服务层已经有 BagEndpointTest 了还要这一层</b>：
 * 服务层测试证明业务规则对，但证明不了「这个规则能通过 HTTP 正确地到达客户端」。
 * 只有走一遍 MVC 才能暴露这几类问题：
 * <ul>
 *   <li>中文序列化乱码（MockMvc 的 getContentAsString 默认 ISO-8859-1，
 *       而产出明细面板的行标签全是中文 ——「农田 Lv8」变成乱码，面板就废了）</li>
 *   <li>枚举与可空字段的 JSON 形状（mailId 为 null 时是省略还是显式 null）</li>
 *   <li>查询参数缺省值与大小写（{@code ?type=speedup} 要不要能work）</li>
 * </ul>
 *
 * <p>B04 验收 3 的判定方式写的是「抓包验证」——「一次开 100 个宝箱只发 1 次网络请求」。
 * 抓包在这里不可行，但 MockMvc 是等价的：本类用<b>一次</b> perform 完成 100 连开，
 * 并断言响应里是聚合后的少量行而不是 100 行。请求次数由测试代码的结构直接保证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BagHttpTest {

    private static final String DETAIL_URL = "/resource/detail";
    private static final String BAG_URL = "/bag/list";
    private static final String USE_URL = "/item/use";
    private static final String OPEN_URL = "/item/openBatch";
    private static final String PLAYER_HEADER = "X-Player-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConfigRegistry configs;

    @Autowired
    private PlayerInitService playerInitService;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private CityRepository cities;

    @Autowired
    private InventoryRepository inventories;

    @BeforeEach
    void resetStores() {
        ((InMemoryPlayerStore) players).clear();
        ((InMemoryCityStore) cities).clear();
        ((InMemoryInventoryStore) inventories).clear();
    }

    @Test
    @DisplayName("GET /resource/detail：中文行标签不乱码，且明细之和精确等于 perHour（验收5）")
    void resourceDetailIsUtf8AndSumsExactly() throws Exception {
        String playerId = newPlayer();

        JsonNode data = get200(DETAIL_URL, playerId);
        JsonNode resources = data.get("resources");
        assertThat(resources).hasSize(configs.allResources().size());

        for (JsonNode detail : resources) {
            JsonNode breakdown = detail.get("breakdown");
            assertThat(breakdown).as("%s 必须有产出分解", detail.get("type").asText()).isNotEmpty();
            long sum = 0L;
            boolean sawChineseLabel = false;
            for (JsonNode line : breakdown) {
                sum += line.get("amount").asLong();
                String source = line.get("source").asText();
                // 中文必须原样到达：乱码会让「转化关键 UI」变成一堆问号
                assertThat(source).as("行标签不得为空").isNotBlank();
                if (source.contains("加成") || source.contains("产出")) {
                    sawChineseLabel = true;
                }
            }
            assertThat(sawChineseLabel).as("面板必须含中文行标签（科技加成/联盟加成/道具 buff）").isTrue();
            assertThat(sum).as("%s 明细之和必须精确等于 perHour（验收 5：误差 0）",
                            detail.get("type").asText())
                    .isEqualTo(detail.get("perHour").asLong());
        }
        assertThat(data.get("serverNow").asLong()).isPositive();
    }

    @Test
    @DisplayName("GET /bag/list?type=… 分页可用，且类型名大小写不敏感；拼错的类型名返回 4xx 业务码")
    void bagListPagingWorksOverHttp() throws Exception {
        String playerId = newPlayer();
        giveItems(playerId, "item_speedup_build_5m", 7L, "item_res_wood_10k", 3L);

        JsonNode all = get200(BAG_URL, playerId);
        assertThat(all.get("items")).hasSize(2);
        assertThat(all.get("capacityUsed").asInt()).isEqualTo(2);
        assertThat(all.get("capacityMax").asInt())
                .isEqualTo((int) configs.longParam("BAG_INITIAL_CAPACITY"));

        JsonNode speedups = get200(BAG_URL + "?type=SPEEDUP", playerId);
        assertThat(speedups.get("items")).hasSize(1);
        assertThat(speedups.get("items").get(0).get("name").asText())
                .as("道具中文名必须原样下发，客户端不得自行翻译")
                .isEqualTo("五分钟建造令");
        assertThat(speedups.get("items").get(0).get("rarity").asText()).isEqualTo("N");
        assertThat(speedups.get("items").get(0).get("obtainFrom").asText()).isNotBlank();
        // 容量不随分页变化：否则玩家会以为翻页能腾出格子
        assertThat(speedups.get("capacityUsed").asInt()).isEqualTo(2);

        JsonNode lowercase = get200(BAG_URL + "?type=speedup", playerId);
        assertThat(lowercase.get("items")).as("类型名大小写不敏感").hasSize(1);

        JsonNode bad = getRoot(BAG_URL + "?type=WEAPON", playerId);
        // Result 信封把业务错误也包成 200 + code，客户端只看 code
        assertThat(bad.get("code").asInt()).isNotZero();
    }

    @Test
    @DisplayName("验收3：一次 HTTP 请求开 100 个宝箱，响应是聚合后的少量行而不是 100 行")
    void openBatchIsOneRequestForHundredChests() throws Exception {
        String playerId = newPlayer();
        giveItems(playerId, "item_chest_resource", 100L);
        long maxBatch = configs.get(ChestCfg.class, "item_chest_resource").maxBatchCount();
        assertThat(maxBatch).as("验收 3 要求单次能开 100 个，配置上限必须 ≥ 100")
                .isGreaterThanOrEqualTo(100L);

        // 就这一次 perform —— 「只发 1 次网络请求」由测试结构本身保证
        JsonNode data = post200(OPEN_URL, playerId,
                new OpenBatchReq(newRequestId(), "item_chest_resource", 100));

        assertThat(data.get("consumed").asLong()).isEqualTo(100L);
        assertThat(data.get("seed").asLong()).as("种子必须回传，否则生产环境无法复现").isNotZero();

        JsonNode results = data.get("results");
        long dropKinds = configs.all(com.ironoath.config.cfg.ChestDropCfg.class).stream()
                .filter(d -> d.chestId().equals("item_chest_resource")).count();
        assertThat(results.size())
                .as("100 次抽取必须聚合到「掉落项种类数」以内，否则响应体会随抽取次数线性膨胀")
                .isLessThanOrEqualTo((int) dropKinds)
                .isPositive();
        for (JsonNode row : results) {
            assertThat(row.get("count").asLong()).isPositive();
            assertThat(row.get("name").asText()).as("奖励名必须由服务端解析后下发").isNotBlank();
        }
        assertThat(inventories.findByPlayerId(playerId).orElseThrow().countOf("item_chest_resource"))
                .isZero();
    }

    @Test
    @DisplayName("POST /item/use：满仓时用资源箱，实发与溢出都在响应里，不静默（验收2）")
    void useItemReportsOverflowOverHttp() throws Exception {
        String playerId = newPlayer();
        giveItems(playerId, "item_res_wood_10k", 3L);

        JsonNode data = post200(USE_URL, playerId,
                new ItemUseReq(newRequestId(), "item_res_wood_10k", 3L, null));

        assertThat(data.get("consumed").asLong()).isEqualTo(3L);
        long granted = sumAmount(data.get("granted"), "WOOD");
        long overflow = sumAmount(data.get("overflow"), "WOOD");
        assertThat(granted + overflow).as("3 万木材必须全部有下落").isEqualTo(30_000L);
        assertThat(overflow).as("WOOD 容量 20000，3 万必然溢出").isPositive();
        assertThat(data.get("mailId").isNull() || data.get("mailId").asText().isBlank())
                .as("溢出必须转邮件，mailId 不得为空").isFalse();
    }

    @Test
    @DisplayName("缺身份头返回 PARAM_INVALID 而不是 5xx：客户端对 5xx 会重试，而缺头重试永远不会成功")
    void missingPlayerHeaderIsRejected() throws Exception {
        MvcResult result = mockMvc.perform(get(DETAIL_URL))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = JsonUtils.readTree(body(result));
        assertThat(root.get("code").asInt())
                .as("缺少 X-Player-Id 属客户端契约错误，应归 PARAM_INVALID(1001)")
                .isEqualTo(1001);
        assertThat(root.get("detail").asText()).contains(PLAYER_HEADER);
    }

    // ---------- 辅助 ----------

    /** GET 并解出 Result.data；code 非 0 直接失败。 */
    private JsonNode get200(String url, String playerId) throws Exception {
        return okData(perform(get(url).header(PLAYER_HEADER, playerId)));
    }

    /** GET 但保留 Result 根节点，用于断言业务错误码。 */
    private JsonNode getRoot(String url, String playerId) throws Exception {
        return perform(get(url).header(PLAYER_HEADER, playerId));
    }

    /** POST 并解出 Result.data；code 非 0 直接失败。 */
    private JsonNode post200(String url, String playerId, Object req) throws Exception {
        return okData(perform(post(url).header(PLAYER_HEADER, playerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(req))));
    }

    private static JsonNode okData(JsonNode root) {
        assertThat(root.get("code").asInt())
                .as("业务码必须为 0，实际响应=%s", root).isZero();
        assertThat(root.get("data")).as("成功响应必须带 data").isNotNull();
        return root.get("data");
    }

    /** 执行请求并返回 Result 信封的根节点（业务错误也是 200 + code，所以不在这里断言 code）。 */
    private JsonNode perform(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        return JsonUtils.readTree(body(result));
    }

    /** MockMvc 默认按 ISO-8859-1 解码响应体，中文会乱码，必须显式指定 UTF-8。 */
    private static String body(MvcResult result) throws java.io.UnsupportedEncodingException {
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), "HTTP 测试", 1_700_000_000_000L, ""))
                .playerId();
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private void giveItems(String playerId, Object... itemIdCountPairs) {
        Inventory bag = Inventory.empty((int) configs.longParam("BAG_INITIAL_CAPACITY"));
        for (int i = 0; i < itemIdCountPairs.length; i += 2) {
            String itemId = (String) itemIdCountPairs[i];
            long count = (Long) itemIdCountPairs[i + 1];
            long added = bag.add(itemId, count, configs.get(ItemCfg.class, itemId).stackMax());
            assertThat(added).as("夹具必须能放下 %s × %d", itemId, count).isEqualTo(count);
        }
        assertThat(inventories.insertIfAbsent(playerId, bag)).isTrue();
    }

    private static long sumAmount(JsonNode amounts, String resourceType) {
        long sum = 0L;
        for (JsonNode amount : amounts) {
            if (amount.get("type").asText().equals(resourceType)) {
                sum += amount.get("amount").asLong();
            }
        }
        return sum;
    }
}
