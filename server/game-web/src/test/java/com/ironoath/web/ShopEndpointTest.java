package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

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
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.common.time.DayKey;
import com.ironoath.common.time.TimeService;
import com.ironoath.common.time.WeekKey;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.limit.DailyCounter;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.core.social.Alliance;
import com.ironoath.core.social.Squad;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.ShopBuyReq;
import com.ironoath.web.dto.generated.ShopBuyResp;
import com.ironoath.web.dto.generated.ShopCurrency;
import com.ironoath.web.dto.generated.ShopListResp;
import com.ironoath.web.dto.generated.ShopRowView;
import com.ironoath.web.service.ShopAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.store.memory.InMemorySocialStore;

/**
 * 职责：商店兑换的端到端验证（B02 商店表 + B10 验收 8「商店兑换正确扣减」）。
 * 依赖：Spring Boot Test + MockMvc；test profile（内存存储）。
 *
 * <p><b>本类的重心是三种货币各自的账本，以及限购的周期口径</b>。商店这条链路的钱货两讫
 * 一旦出错，症状都不是报错而是「玩家少拿了东西」或「玩家多花了钱」，
 * 而这两种都只在账目上可见，功能测试点一次是点不出来的。
 *
 * <p>几条刻意的设计：
 * <ul>
 *   <li>联盟/小队的余额用<b>领域方法直接铺</b>（donate / completeDailyQuest），
 *       因为捐献与互助任务各自有自己的用例，本类只验兑换；</li>
 *   <li>主城门槛用<b>同一行的两端</b>来验（1 级被拒、8 级放行）——
 *       只断言被拒的那一端会漏掉「门槛写反」这种错法；</li>
 *   <li>周限购直接断言计数器落在 {@code WeekKey} 而不是 {@code DayKey} 上 ——
 *       这条断言在实现错成日切时会立刻红。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShopEndpointTest {

    private static final String PLAYER_HEADER = "X-Player-Id";

    @Autowired private MockMvc mockMvc;
    @Autowired private ShopAppService shop;
    @Autowired private SocialAppService socialAppService;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private InMemorySocialStore social;
    @Autowired private RewardPorts.Wallet wallet;
    @Autowired private RewardPorts.Bag bag;
    @Autowired private DailyCounter limits;
    @Autowired private PlayerRepository players;
    @Autowired private TimeService timeService;

    // ---------- 金币页 ----------

    @Test
    @DisplayName("金币货架列出全部 8 行金币商品，价格与限购都来自表；买一单后钱货两讫")
    void goldShelfIsServedFromTheTableAndSettles() throws Exception {
        String playerId = richPlayer();

        JsonNode data = okData(perform(get("/shop/list?currency=GOLD").header(PLAYER_HEADER, playerId)));
        assertThat(data.get("currency").asText()).isEqualTo("GOLD");
        assertThat(data.get("open").asBoolean()).isTrue();
        // 8 而不是 10：下架了两件"用了必失败"的道具（一小时研究加速抛 NOT_IMPLEMENTED，
        // 两小时集结加成在没有任何表定义它的加成幅度时抛 ITEM_CANNOT_USE）。
        // 收金币卖一个用了会报错的东西是 B15 的红线，不是待优化的体验问题
        assertThat(data.get("rows")).hasSize(8);
        assertThat(data.get("balance").asLong()).isEqualTo(100_200L);
        JsonNode first = row(data, "shop_speedup_build_1h");
        assertThat(first.get("price").asLong())
                .as("价格必须由服务端从表里下发，客户端内置价格的话调价要发版").isEqualTo(40L);
        assertThat(first.get("itemId").asText()).isEqualTo("item_speedup_build_1h");
        assertThat(first.get("used").asInt()).isZero();
        assertThat(first.get("remaining").asInt()).isEqualTo(20);

        ShopBuyResp resp = shop.buy(playerId, buy("shop_speedup_build_1h", ShopCurrency.GOLD, 1));
        assertThat(resp.spent()).as("总价 = 40 × 1，由服务端算").isEqualTo(40L);
        assertThat(resp.balance()).isEqualTo(100_160L);
        assertThat(resp.used()).isEqualTo(1);
        assertThat(resp.remaining()).isEqualTo(19);
        assertThat(wallet.available(playerId, ResourceIds.GOLD, timeService.serverNow())).isEqualTo(100_160L);
        assertThat(bag.countOf(playerId, "item_speedup_build_1h")).isEqualTo(1L);
    }

    @Test
    @DisplayName("买多个只按「单价 × 个数」扣一次，响应回显服务端算的总价")
    void multiUnitPurchaseChargesTheServerSideTotal() {
        String playerId = richPlayer();

        ShopBuyResp resp = shop.buy(playerId, buy("shop_speedup_build_1h", ShopCurrency.GOLD, 3));

        assertThat(resp.count()).isEqualTo(3);
        assertThat(resp.spent()).isEqualTo(120L);
        assertThat(resp.used()).as("一次买 3 个占 3 个额度，不是占 1 次").isEqualTo(3);
        assertThat(bag.countOf(playerId, "item_speedup_build_1h")).isEqualTo(3L);
    }

    @Test
    @DisplayName("同一 requestId 重放只兑换一次：第二次报 REQUEST_DUPLICATED，道具不翻倍")
    void replayedRequestIdBuysOnce() {
        String playerId = richPlayer();
        String requestId = newRequestId();

        shop.buy(playerId, new ShopBuyReq(requestId, ShopCurrency.GOLD, "shop_res_wood_10k", 2));
        assertThatThrownBy(() -> shop.buy(playerId,
                new ShopBuyReq(requestId, ShopCurrency.GOLD, "shop_res_wood_10k", 2)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.REQUEST_DUPLICATED);
        assertThat(bag.countOf(playerId, "item_res_wood_10k")).as("重放等于刷道具").isEqualTo(2L);
        assertThat(limits.used("shop:shop_res_wood_10k", playerId, today())).isEqualTo(2L);
    }

    @Test
    @DisplayName("日限购按行独立计数，用完之后当场拒绝而不是允许买了再退钱")
    void dailyLimitIsPerRowAndEnforced() {
        String playerId = richPlayer();

        // shop_speedup_build_8h：300 金币、每日 5 个
        for (int i = 0; i < 5; i++) {
            shop.buy(playerId, buy("shop_speedup_build_8h", ShopCurrency.GOLD, 1));
        }
        assertThatThrownBy(() -> shop.buy(playerId, buy("shop_speedup_build_8h", ShopCurrency.GOLD, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("今日限购 5 个")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.SHOP_LIMIT_REACHED);

        // 判别性：另一行的额度不受影响（共用一个 scope 就会连带冻住整个商店）
        assertThat(shop.buy(playerId, buy("shop_speedup_build_1h", ShopCurrency.GOLD, 1)).used()).isEqualTo(1);
        assertThat(limits.used("shop:shop_speedup_build_8h", playerId, today())).isEqualTo(5L);
        ShopRowView sold = findRow(shop.list(playerId, ShopCurrency.GOLD), "shop_speedup_build_8h");
        assertThat(sold.purchasable()).as("用完之后货架必须自己说不能买，而不是亮着按钮等玩家报错")
                .isFalse();
        assertThat(sold.lockReason()).contains("限购");
    }

    @Test
    @DisplayName("一次买超过剩余额度时，占掉的额度会退回，钱一分不扣")
    void overLimitRequestRollsBackSlotsAndMoney() {
        String playerId = richPlayer();
        long goldBefore = wallet.available(playerId, ResourceIds.GOLD, timeService.serverNow());

        assertThatThrownBy(() -> shop.buy(playerId, buy("shop_speedup_build_8h", ShopCurrency.GOLD, 6)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.SHOP_LIMIT_REACHED);

        assertThat(limits.used("shop:shop_speedup_build_8h", playerId, today()))
                .as("占了 5 个又被拒，必须全退；留着的话玩家白少 5 次额度").isZero();
        assertThat(wallet.available(playerId, ResourceIds.GOLD, timeService.serverNow())).isEqualTo(goldBefore);
        assertThat(bag.countOf(playerId, "item_speedup_build_8h")).isZero();
    }

    // ---------- 主城门槛 ----------

    @Test
    @DisplayName("同一行的两端：主城 1 级被拒、8 级放行（只断被拒那端会漏掉门槛写反）")
    void mainLevelGateIsCheckedBothWays() {
        String low = richPlayer();
        ShopListResp shelf = shop.list(low, ShopCurrency.GOLD);
        ShopRowView peace = findRow(shelf, "shop_peace_24h");
        assertThat(peace.purchasable()).isFalse();
        assertThat(peace.lockReason()).as("等级不够的商品不能消失，要显示成「几级解锁」").contains("主城 8 级解锁");
        assertThatThrownBy(() -> shop.buy(low, buy("shop_peace_24h", ShopCurrency.GOLD, 1)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.CITY_MAIN_LEVEL_LOW);
        assertThat(bag.countOf(low, "item_buff_peace_24h")).isZero();

        String high = richPlayer();
        raiseCityLevel(high, 8);
        assertThat(shop.buy(high, buy("shop_peace_24h", ShopCurrency.GOLD, 1)).spent()).isEqualTo(200L);
        assertThat(bag.countOf(high, "item_buff_peace_24h")).isEqualTo(1L);
    }

    // ---------- 联盟贡献值 ----------

    @Test
    @DisplayName("验收8：兑换扣的是本人的贡献值，联盟资金与别人的贡献值一分不动")
    void allianceShopSpendsOnlyTheBuyerContribution() {
        String leader = richPlayer();   // 建盟要 500 金币，新号的 200 不够
        raiseCityLevel(leader, 10);   // 建盟门槛：主城 10 级（来自联盟解锁规则，不是商店规则）
        socialAppService.allianceCreate(leader,
                new AllianceCreateReq(newRequestId(), "兑换盟", "EXCH"));
        mintContribution(leader, 250L);
        String other = player();
        joinAlliance(leader, other);
        mintContribution(other, 100L);
        long fundBefore = allianceOf(leader).fund();

        ShopBuyResp resp = shop.buy(leader, buy("shop_alliance_res", ShopCurrency.ALLIANCE_COIN, 2));

        assertThat(resp.spent()).isEqualTo(60L);
        assertThat(allianceOf(leader).contributionOf(leader)).isEqualTo(190L);
        assertThat(allianceOf(leader).contributionOf(other)).as("别人的账不能被动").isEqualTo(100L);
        assertThat(allianceOf(leader).fund()).as("兑换花的是成员贡献值，不是联盟公账").isEqualTo(fundBefore);
        assertThat(bag.countOf(leader, "item_res_wood_10k")).isEqualTo(2L);
    }

    @Test
    @DisplayName("周限购落在 WeekKey 上而不是 DayKey：切错口径等于每周多卖一次")
    void weeklyLimitUsesTheWeekKey() {
        String leader = richPlayer();
        raiseCityLevel(leader, 10);
        socialAppService.allianceCreate(leader, new AllianceCreateReq(newRequestId(), "周期盟", "WEEK"));
        mintContribution(leader, 300L);
        long now = timeService.serverNow();

        shop.buy(leader, buy("shop_alliance_res", ShopCurrency.ALLIANCE_COIN, 1));

        assertThat(limits.used("shop:shop_alliance_res", leader, WeekKey.of(now)))
                .as("这一行 refreshType=WEEKLY，额度必须记在周键上").isEqualTo(1L);
        assertThat(limits.used("shop:shop_alliance_res", leader, DayKey.of(now)))
                .as("记到日键上的话，明天就能再买 10 个 —— 那正是把周限购做成日限购的后果").isZero();
    }

    @Test
    @DisplayName("没加入联盟的人看到的是「先加入联盟」，而不是「贡献值不足」")
    void nonMemberGetsTheQualificationReason() {
        String playerId = richPlayer();

        assertThatThrownBy(() -> shop.buy(playerId, buy("shop_alliance_res", ShopCurrency.ALLIANCE_COIN, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("需要先加入联盟")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ALLIANCE_NOT_FOUND);
        assertThat(shop.list(playerId, ShopCurrency.GOLD).rows()).isNotEmpty();
    }

    // ---------- 小队币 ----------

    @Test
    @DisplayName("小队币兑换扣本人小队币；余额不足时点名「小队币不足」")
    void squadCoinShopSpendsTheMemberWallet() {
        String playerId = player();
        raiseCityLevel(playerId, 8);   // 小队解锁要主城 5 级
        socialAppService.squadCreate(playerId,
                new com.ironoath.web.dto.generated.SquadCreateReq(newRequestId(), "商店队"));
        mintSquadCoin(playerId, 40L);
        // 判别性：1 级小队没有商店（squad_config 的 shopUnlock=false），只有成员身份不够
        assertThatThrownBy(() -> shop.buy(playerId, buy("shop_squad_speedup", ShopCurrency.SQUAD_COIN, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("小队商店尚未解锁")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.SQUAD_LOCKED);
        assertThat(bag.countOf(playerId, "item_speedup_build_5m")).as("未解锁时一分不扣").isZero();
        unlockSquadShop(playerId);

        ShopBuyResp resp = shop.buy(playerId, buy("shop_squad_speedup", ShopCurrency.SQUAD_COIN, 2));
        assertThat(resp.spent()).isEqualTo(30L);
        assertThat(squadOf(playerId).squadCoinOf(playerId)).isEqualTo(10L);
        assertThat(bag.countOf(playerId, "item_speedup_build_5m")).isEqualTo(2L);

        assertThatThrownBy(() -> shop.buy(playerId, buy("shop_squad_speedup", ShopCurrency.SQUAD_COIN, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("小队币不足")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.SOCIAL_SQUAD_COIN_LACK);
        assertThat(bag.countOf(playerId, "item_speedup_build_5m")).as("失败不得白给一个").isEqualTo(2L);
    }

    // ---------- 赛季币页（尚未开放） ----------

    /**
     * 「还没开」与「不存在」必须是两句话：货架照给、每行不可买、余额回 null。
     * 把整页从枚举里删掉是最省事的写法，但客户端就只能显示「没有这个商店」，
     * 而赛季币本身是真实存在的（赛季结算会发）。
     */
    @Test
    @DisplayName("赛季币页：货架可见、每行都不可买、余额是 null 而不是假的 0")
    void seasonPageIsListedButClosed() throws Exception {
        String playerId = richPlayer();

        JsonNode data = okData(perform(get("/shop/list?currency=SEASON_COIN").header(PLAYER_HEADER, playerId)));
        assertThat(data.get("open").asBoolean()).isFalse();
        assertThat(data.get("notice").asText()).contains("赛季币");
        assertThat(data.get("rows")).hasSize(1);
        assertThat(data.get("rows").get(0).get("purchasable").asBoolean()).isFalse();
        JsonNode balance = data.get("balance");
        assertThat(balance == null || balance.isNull())
                .as("商店没有这个币种的账本，余额只能是「不知道」而不是 0").isTrue();

        ShopListResp typed = shop.list(playerId, ShopCurrency.SEASON_COIN);
        assertThat(typed.balance()).isNull();
        assertThatThrownBy(() -> shop.buy(playerId, buy("shop_season_skin", ShopCurrency.SEASON_COIN, 1)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.SHOP_CURRENCY_CLOSED);
    }

    // ---------- 请求收窄 ----------

    @Test
    @DisplayName("请求声明的币种与行里的不符时直接拒绝：默默按行里的币种扣钱会扣错钱包")
    void currencyMustMatchTheRow() {
        String playerId = richPlayer();

        assertThatThrownBy(() -> shop.buy(playerId, buy("shop_speedup_build_1h", ShopCurrency.SQUAD_COIN, 1)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("与请求声明的")
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.SHOP_ROW_NOT_FOUND);
        assertThat(bag.countOf(playerId, "item_speedup_build_1h")).isZero();
    }

    @Test
    @DisplayName("count 为 0 或负数当场拒绝：负数会让「limit - count」变成买得越多剩得越多")
    void nonPositiveCountIsRejected() {
        String playerId = richPlayer();

        assertThatThrownBy(() -> shop.buy(playerId, buy("shop_speedup_build_1h", ShopCurrency.GOLD, 0)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThatThrownBy(() -> shop.buy(playerId, buy("shop_speedup_build_1h", ShopCurrency.GOLD, -5)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(limits.used("shop:shop_speedup_build_1h", playerId, today()))
                .as("被拒的请求不能占额度").isZero();
    }

    @Test
    @DisplayName("币种参数拼错回 PARAM_INVALID 并列出取值，而不是一个没有错误码的 400")
    void unknownCurrencyParameterIsNarrowedByHand() throws Exception {
        String playerId = richPlayer();
        JsonNode root = perform(get("/shop/list?currency=DIAMOND").header(PLAYER_HEADER, playerId));
        assertThat(root.get("code").asInt()).isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(root.get("detail").asText()).contains("GOLD").contains("SEASON_COIN");
    }

    // ---------- 夹具 ----------

    private String player() {
        return playerInitService.init(new PlayerInitReq(
                newRequestId(), "dev-" + UUID.randomUUID(), "商店测试", 1_700_000_000_000L)).playerId();
    }

    /** 金币充足的玩家：金币初始 200，最贵的一行是 1500（且要 12 级），补齐到 100200 让用例不必各花各的。 */
    private String richPlayer() {
        String playerId = player();
        wallet.grant(playerId, ResourceIds.GOLD, 100_000L, timeService.serverNow());
        return playerId;
    }

    private static ShopBuyReq buy(String rowId, ShopCurrency currency, int count) {
        return new ShopBuyReq(newRequestId(), currency, rowId, count);
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    private String today() {
        return DayKey.of(timeService.serverNow());
    }

    private void raiseCityLevel(String playerId, int level) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(level);
        players.save(save);
    }

    /**
     * 直接铺贡献值：捐献本身的扣款与档位上限由 {@code SocialEndpointTest} 覆盖，
     * 本类只验「兑换会不会正确扣减」。
     */
    private void mintContribution(String playerId, long contribution) {
        Alliance alliance = allianceOf(playerId);
        long perTier = 10L;
        for (int i = 0; i < contribution / perTier; i++) {
            alliance.donate(playerId, 0, DayKey.of(timeService.serverNow()) + "#" + i);
        }
        social.saveAlliance(alliance);
    }

    private void mintSquadCoin(String playerId, long coins) {
        Squad squad = squadOf(playerId);
        squad.completeDailyQuest(coins, 1L);
        social.saveSquad(squad);
    }

    private void joinAlliance(String leader, String newcomer) {
        Alliance alliance = allianceOf(leader);
        alliance.join(newcomer);
        // 入盟索引由 saveAlliance 顺带按成员建立（store 里没有单独的 bind 方法）
        social.saveAlliance(alliance);
    }

    /** 把小队顶到「商店已解锁」的等级：具体要多少经验由 squad_config 说话，这里不猜数字。 */
    private void unlockSquadShop(String playerId) {
        Squad squad = squadOf(playerId);
        for (int i = 0; i < 20 && !squad.shopUnlocked(); i++) {
            squad.addExp(10_000L);
        }
        assertThat(squad.shopUnlocked())
                .as("加满经验仍解锁不了商店，说明 squad_config 的 shopUnlock 曲线改了，夹具要跟着改").isTrue();
        social.saveSquad(squad);
    }

    private Alliance allianceOf(String playerId) {
        return social.allianceOf(playerId).orElseThrow();
    }

    private Squad squadOf(String playerId) {
        return social.squadOf(playerId).orElseThrow();
    }

    private static ShopRowView findRow(ShopListResp shelf, String rowId) {
        List<ShopRowView> hits = shelf.rows().stream().filter(row -> row.rowId().equals(rowId)).toList();
        if (hits.size() != 1) {
            throw new AssertionError("货架里应当恰好有一行 " + rowId + "，实际 " + hits.size()
                    + " 行（表被改过，或用例挑错了行）");
        }
        return hits.get(0);
    }

    private static JsonNode row(JsonNode shelfData, String rowId) {
        for (JsonNode node : shelfData.get("rows")) {
            if (rowId.equals(node.get("rowId").asText())) {
                return node;
            }
        }
        throw new AssertionError("响应里没有行 " + rowId);
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
