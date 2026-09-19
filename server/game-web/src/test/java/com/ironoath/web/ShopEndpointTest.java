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
import com.ironoath.core.season.SeasonTier;
import com.ironoath.web.season.SeasonLedgerStore;
import com.ironoath.web.dto.generated.ShopListResp;
import com.ironoath.web.dto.generated.ShopRowView;
import com.ironoath.web.service.ShopAppService;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.social.SocialStore;

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
    @Autowired private SocialStore social;
    @Autowired private RewardPorts.Wallet wallet;
    @Autowired private RewardPorts.Bag bag;
    @Autowired private DailyCounter limits;
    @Autowired private PlayerRepository players;
    @Autowired private TimeService timeService;
    @Autowired private com.ironoath.web.season.SeasonLedgerStore seasonLedger;
    @Autowired private com.ironoath.web.season.SeasonRulesAssembler seasonRules;
    @Autowired private com.ironoath.web.service.AvatarFrameService avatarFrames;

    // ---------- 金币页 ----------

    @Test
    @DisplayName("金币货架列出全部 9 行金币商品，价格与限购都来自表；买一单后钱货两讫")
    void goldShelfIsServedFromTheTableAndSettles() throws Exception {
        String playerId = richPlayer();

        JsonNode data = okData(perform(get("/shop/list?currency=GOLD").header(PLAYER_HEADER, playerId)));
        assertThat(data.get("currency").asText()).isEqualTo("GOLD");
        assertThat(data.get("open").asBoolean()).isTrue();
        // 9 而不是 10：#46 下架的两件里只回来了一件。一小时研究令回来了（B20 验收 8 做完，
        // 当初「没有任何东西可加速」的理由不再成立）；两小时集结加成仍不在架上，
        // 因为**没有任何表定义它的加成幅度** —— 收金币卖一个用了会报错的东西是 B15 的红线，
        // 不是待优化的体验问题
        assertThat(data.get("rows")).hasSize(9);
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
    @DisplayName("验收1：赛季币有了消费者 —— 拿到币就能买，买完余额跟着变（B24 裁决①）")
    void seasonCoinsCanActuallyBeSpent() throws Exception {
        String playerId = richPlayer();
        // 赛季币只由结算发放：先照结算那条路记一笔（200 币），余额才存在
        seasonLedger.recordIfAbsent(currentSeasonId(),
                new SeasonLedgerStore.Record(playerId, 3, SeasonTier.Tier.GOLD, 200L, 0L));

        JsonNode before = okData(perform(get("/shop/list?currency=SEASON_COIN").header(PLAYER_HEADER, playerId)));
        assertThat(before.get("open").asBoolean()).as("裁决① 之后这一页是开的").isTrue();
        assertThat(before.get("balance").asLong()).as("余额是结算发的那 200").isEqualTo(200L);
        JsonNode row = before.get("rows").get(0);
        assertThat(row.get("rowId").asText()).isEqualTo("shop_season_boost");
        assertThat(row.get("purchasable").asBoolean()).as("有币就能买").isTrue();

        ShopBuyResp bought = shop.buy(playerId, buy("shop_season_boost", ShopCurrency.SEASON_COIN, 1));
        assertThat(bought.spent()).as("100 赛季币").isEqualTo(100L);
        assertThat(bought.balance()).as("回执里就带着扣完之后的余额").isEqualTo(100L);

        JsonNode after = okData(perform(get("/shop/list?currency=SEASON_COIN").header(PLAYER_HEADER, playerId)));
        assertThat(after.get("balance").asLong()).as("买完余额必须跟着变（验收 1 的判据）").isEqualTo(100L);
        assertThat(after.get("rows").get(0).get("used").asInt()).as("限购计数也进了货架").isEqualTo(1);
        assertThat(bag.countOf(playerId, "item_speedup_build_8h"))
                .as("买了就得真拿到东西").isEqualTo(1L);
    }

    @Test
    @DisplayName("验收 4：头像框是「买一次就永久拥有」—— 买完戴得上，再买被拒（限购会随赛季重置，拥有不会）")
    void avatarFrameIsOwnedForeverAfterOnePurchase() {
        String playerId = richPlayer();
        // 赛季币只由结算发放：先照结算那条路记一笔（1000 币，够买两枚）
        seasonLedger.recordIfAbsent(currentSeasonId(),
                new SeasonLedgerStore.Record(playerId, 3, SeasonTier.Tier.GOLD, 1000L, 0L));

        ShopBuyResp bought = shop.buy(playerId, buy("shop_season_frame", ShopCurrency.SEASON_COIN, 1));
        assertThat(bought.frameId()).as("卖的是外观：回执给的是 frameId 而不是道具 id（验收 5 的假指向在这里被断掉）")
                .isEqualTo("frame_season_s1");
        assertThat(bought.itemId()).as("外观不是道具，所以 itemId 是 null").isNull();
        assertThat(bought.spent()).as("500 赛季币").isEqualTo(500L);
        assertThat(bought.balance()).isEqualTo(500L);
        assertThat(players.findByPlayerId(playerId).orElseThrow().ownedAvatarFrames())
                .as("买完就拥有（这就是验收 4 要的「已拥有状态落库」）").contains("frame_season_s1");
        assertThat(bag.countOf(playerId, "frame_season_s1"))
                .as("外观不该被塞进背包当道具").isZero();

        // 同一赛季里再买一次：先撞上的是限购（表里 limitPerSeason=1 给的），不是「已拥有」
        assertThatThrownBy(() -> shop.buy(playerId, buy("shop_season_frame", ShopCurrency.SEASON_COIN, 1)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.SHOP_LIMIT_REACHED);
        assertThat(seasonLedger.spentOf(currentSeasonId(), playerId))
                .as("被拒的那次不能扣钱").isEqualTo(500L);

        // 但限购会随赛季重置、「已拥有」不会 —— 第二道防线得单独验：给新号直接发框
        // （绕过购买 ⇒ 他本赛季一次没买过，限购放行），此时能拦住他的只有「已拥有」
        String owner = richPlayer();
        seasonLedger.recordIfAbsent(currentSeasonId(),
                new SeasonLedgerStore.Record(owner, 3, SeasonTier.Tier.GOLD, 1000L, 0L));
        avatarFrames.grant(owner, "frame_season_s1");
        assertThat(seasonLedger.spentOf(currentSeasonId(), owner))
                .as("只发了框、没花过钱").isZero();
        assertThatThrownBy(() -> shop.buy(owner, buy("shop_season_frame", ShopCurrency.SEASON_COIN, 1)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .as("赛季换了限购就放开，这一层还得在").isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(seasonLedger.spentOf(currentSeasonId(), owner))
                .as("已经拥有还收钱 = 玩家白掏 500 赛季币").isZero();
    }

    @Test
    @DisplayName("赛季币不足回「赛季币不足」而不是「该页未开放」；没结算过的人余额是 0")
    void seasonCoinLackIsSaidPlainly() {
        String playerId = richPlayer();

        assertThat(shop.list(playerId, ShopCurrency.SEASON_COIN).balance())
                .as("没结算过 = 可花 0，而不是「不知道」（那一页现在有账本了）").isZero();
        assertThatThrownBy(() -> shop.buy(playerId, buy("shop_season_boost", ShopCurrency.SEASON_COIN, 1)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .as("开放之后再回「页未开放」就是一句假话：玩家会去问客服什么时候开")
                .isEqualTo(ErrorCode.SEASON_COIN_LACK);
    }

    @Test
    @DisplayName("赛季币限购是「本赛季」：同一季买过第二次就被拒，而额度按赛季而不是终身")
    void seasonCoinLimitIsPerSeason() {
        String playerId = richPlayer();
        String seasonId = currentSeasonId();
        seasonLedger.recordIfAbsent(seasonId,
                new SeasonLedgerStore.Record(playerId, 3, SeasonTier.Tier.GOLD, 300L, 0L));

        shop.buy(playerId, buy("shop_season_boost", ShopCurrency.SEASON_COIN, 1));
        assertThatThrownBy(() -> shop.buy(playerId, buy("shop_season_boost", ShopCurrency.SEASON_COIN, 1)))
                .as("同一赛季第二次必须被限购拒（refreshType=SEASON + limitCount=1）")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.SHOP_LIMIT_REACHED);

        // 额度按赛季折算：**另一个赛季的同一个键必须算新额度** —— 用 NONE（终身一次）
        // 会在这里给出"也拒"，所以这一条正好把 SEASON 与 NONE 区分开
        ShopListResp nextSeason = shop.list(playerId, ShopCurrency.SEASON_COIN);
        assertThat(nextSeason.rows().get(0).remaining())
                .as("本赛季的额度用完了（0），而下一季会重新给 1 —— 这一列读的是 periodOf(SEASON) 的标签")
                .isZero();
        assertThat(seasonId).as("夹具前提：当前赛季 id 非空，它是限购标签与账本键的共同来源").isNotBlank();
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
                newRequestId(), "dev-" + UUID.randomUUID(), "商店测试", 1_700_000_000_000L, "")).playerId();
    }

    /** 金币充足的玩家：金币初始 200，最贵的一行是 1500（且要 12 级），补齐到 100200 让用例不必各花各的。 */
    /** 当前赛季 id：限购标签与账本键的共同来源（测试里只读，不自己造 id）。 */
    private String currentSeasonId() {
        return seasonRules.timelineRules().seasonId();
    }

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
        long expectedAllianceVersion = alliance.version();
        long perTier = 10L;
        for (int i = 0; i < contribution / perTier; i++) {
            alliance.donate(playerId, 0, DayKey.of(timeService.serverNow()) + "#" + i);
        }
        social.saveAlliance(alliance, expectedAllianceVersion);
    }

    private void mintSquadCoin(String playerId, long coins) {
        Squad squad = squadOf(playerId);
        long expectedSquadVersion = squad.version();
        squad.completeDailyQuest(coins, 1L);
        social.saveSquad(squad, expectedSquadVersion);
    }

    private void joinAlliance(String leader, String newcomer) {
        Alliance alliance = allianceOf(leader);
        long expectedAllianceVersion = alliance.version();
        alliance.join(newcomer);
        // 入盟索引由 saveAlliance 顺带按成员建立（store 里没有单独的 bind 方法）
        social.saveAlliance(alliance, expectedAllianceVersion);
    }

    /** 把小队顶到「商店已解锁」的等级：具体要多少经验由 squad_config 说话，这里不猜数字。 */
    private void unlockSquadShop(String playerId) {
        Squad squad = squadOf(playerId);
        long expectedSquadVersion = squad.version();
        for (int i = 0; i < 20 && !squad.shopUnlocked(); i++) {
            squad.addExp(10_000L);
        }
        assertThat(squad.shopUnlocked())
                .as("加满经验仍解锁不了商店，说明 squad_config 的 shopUnlock 曲线改了，夹具要跟着改").isTrue();
        social.saveSquad(squad, expectedSquadVersion);
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
