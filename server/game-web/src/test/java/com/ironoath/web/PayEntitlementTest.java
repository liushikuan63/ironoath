package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
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
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.PayProductCfg;
import com.ironoath.config.cfg.ProductRewardCfg;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.pay.PayOrder;
import com.ironoath.core.player.PlayerPaid;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.core.reward.RewardService;
import com.ironoath.web.dto.generated.CardClaimReq;
import com.ironoath.web.dto.generated.CardClaimResp;
import com.ironoath.web.dto.generated.CardStatusResp;
import com.ironoath.web.dto.generated.CreateOrderReq;
import com.ironoath.web.dto.generated.CreateOrderResp;
import com.ironoath.web.dto.generated.FundClaimReq;
import com.ironoath.web.dto.generated.FundClaimResp;
import com.ironoath.web.dto.generated.FundStatusResp;
import com.ironoath.web.dto.generated.FundTier;
import com.ironoath.web.dto.generated.PayCallbackReq;
import com.ironoath.web.dto.generated.PayRetryReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.pay.MinorPaymentPolicy;
import com.ironoath.web.pay.PaidClaimsAppService;
import com.ironoath.web.pay.PaidProducts;
import com.ironoath.web.service.CityAppService;
import com.ironoath.web.service.PayAppService;
import com.ironoath.web.service.PlayerInitService;

/**
 * 职责：B19 三类商品的<b>真发货 + 真领取</b>端到端（验收 1 / 3 / 5 的付费部分）。
 * 依赖：完整上下文（内存存储 profile）、生产那个真的 {@code ProductFulfilment}、真的发放器。
 *
 * <p><b>与 {@code PayEndpointTest} 的分工</b>：那个类换一个必然失败的发货桩，测订单状态机
 * 与补单/负债出口；本类用生产实现，测「钱进来之后玩家到底拿到了什么」。两者互相替代不了 ——
 * 真实现造不出「发货失败」那条路径，而桩发不出月卡。
 *
 * <p><b>需要跨日的用例自己造 {@link PaidClaimsAppService} 并注入可控时钟</b>：上下文里那份
 * {@code TimeService} 是全服唯一时间基准，拨它等于把所有别的用例扔进未来。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PayEntitlementTest {

    private static final String PLAYER_HEADER = "X-Player-Id";
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    @Autowired private MockMvc mockMvc;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private ConfigRegistry configs;
    @Autowired private PayOrder.Registry orders;
    @Autowired private PayAppService pay;
    @Autowired private PayAppService.SignatureVerifier verifier;
    @Autowired private PayAppService.ProductFulfiller fulfiller;
    @Autowired private PaidClaimsAppService claims;
    @Autowired private PlayerLock playerLock;
    @Autowired private IdempotencyStore idempotency;
    @Autowired private Environment environment;
    @org.springframework.boot.test.mock.mockito.SpyBean private PlayerRepository players;
    @Autowired private RewardService rewardService;
    @Autowired private com.ironoath.web.reward.RewardNames rewardNames;
    @Autowired private PaidProducts catalog;
    @Autowired private CityAppService city;
    @Autowired private HeroRepository heroes;
    @Autowired private RewardPorts.Bag bag;
    @org.springframework.boot.test.mock.mockito.SpyBean private com.ironoath.core.bag.InventoryRepository inventories;

    // ---------- 月卡：发货只买回有效期 ----------

    @Test
    @DisplayName("验收1：买月卡只延一期有效期，日包不在发货时预发（§五②a 要的就是每天点一下）")
    void purchasingCardOnlyBuysValidity() throws Exception {
        String playerId = newPlayer();
        long before = System.currentTimeMillis();
        long goldBefore = gold(playerId);

        String orderId = order(playerId, monthlyId(), null);
        callback(orderId);

        CardStatusResp status = claims.cardStatus(playerId);
        assertThat(status.active()).as("付完款当场就有效").isTrue();
        assertThat(status.expireAt())
                .as("到期时刻 = 购买时刻 + durationDays（30 天）")
                .isBetween(before + 30 * DAY - HOUR, before + 31 * DAY);
        assertThat(status.claimedToday()).as("买卡不等于已经领过今天的日包").isFalse();
        assertThat(status.claimableDays()).as("今天点一下能领一天").isEqualTo(1L);
        assertThat(gold(playerId)).as("发货这一步一分金币都没发").isEqualTo(goldBefore);
        assertThat(orders.get(orderId).status()).isEqualTo(PayOrder.Status.SUCCESS);
        assertThat(orders.get(orderId).rewards())
                .as("订单上记的那一项是「有效期」，不是三件日包货物")
                .singleElement()
                .extracting(PayOrder.RewardRow::type).isEqualTo("PRIVILEGE");
    }

    @Test
    @DisplayName("领日包：内容与 product_reward 逐行一致，同日第二次点被拒")
    void dailyPackMatchesTheTableAndIsOncePerDay() throws Exception {
        String playerId = buyCard(newPlayer());
        long goldBefore = gold(playerId);
        long speedupBefore = bag.countOf(playerId, "item_speedup_build_1h");
        long woodBefore = bag.countOf(playerId, "item_res_wood_10k");

        CardClaimResp resp = claims.claimCard(playerId, new CardClaimReq(newRequestId(),null));

        assertThat(resp.claimedDays()).isEqualTo(1L);
        assertThat(gold(playerId) - goldBefore)
                .as("金币入账 = 表里的 pr_monthly_gold").isEqualTo(rowCount("pr_monthly_gold"));
        assertThat(bag.countOf(playerId, "item_speedup_build_1h") - speedupBefore)
                .as("建造令 = 表里的 pr_monthly_build").isEqualTo(rowCount("pr_monthly_build"));
        assertThat(bag.countOf(playerId, "item_res_wood_10k") - woodBefore)
                .as("木材包 = 表里的 pr_monthly_wood").isEqualTo(rowCount("pr_monthly_wood"));
        assertThat(resp.rewards()).as("响应回的就是实际发出的那几行")
                .hasSize(catalog.alwaysRows(monthlyId()).size());

        assertThatThrownBy(() -> claims.claimCard(playerId, new CardClaimReq(newRequestId(),null)))
                .as("同日再领必须被拒：挡不住就是一天领无限份日包")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PAY_ALREADY_CLAIMED);
    }

    @Test
    @DisplayName("隔两天再来：漏领的日子一起补上，日包按天数翻倍")
    void missedDaysArePaidTogether() {
        String playerId = newPlayer();
        long base = System.currentTimeMillis();
        seedPaid(playerId, PlayerPaid.empty().withCardExtended(base, 30 * DAY)
                .withCardSettledThrough(DayKey.startOfDayPlusDays(base, 8)));
        long goldBefore = gold(playerId);

        CardClaimResp resp = claimsAt(base + 10 * DAY)
                .claimCard(playerId, new CardClaimReq(newRequestId(),null));

        assertThat(resp.claimedDays()).as("第 9、10 两天都没领 ⇒ 这次一起补").isEqualTo(2L);
        assertThat(gold(playerId) - goldBefore)
                .as("金币按天数乘：2 × 表里那一行")
                .isEqualTo(2 * rowCount("pr_monthly_gold"));
    }

    @Test
    @DisplayName("补领的上限是剩余有效期：二十五天没来也只发得出剩下的那六天")
    void makeUpIsCappedByRemainingValidity() {
        String playerId = newPlayer();
        long base = System.currentTimeMillis();
        seedPaid(playerId, PlayerPaid.empty().withCardExtended(base, 30 * DAY)
                .withCardSettledThrough(DayKey.startOfDayPlusDays(base, -1)));

        CardClaimResp resp = claimsAt(base + 25 * DAY)
                .claimCard(playerId, new CardClaimReq(newRequestId(),null));

        assertThat(resp.claimedDays())
                .as("欠 25 天，卡只剩 6 天（含今天）⇒ 只发 6 天。"
                        + "没有这个上限，「两周后回来一次领完」就把每日回线的留存设计废掉了")
                .isEqualTo(6L);
    }

    @Test
    @DisplayName("没买过卡来领：说的是「这张卡没有」，而不是「今天领过了」")
    void claimWithoutCardSaysWhy() {
        String playerId = newPlayer();
        assertThat(claims.cardStatus(playerId).active()).isFalse();
        assertThatThrownBy(() -> claims.claimCard(playerId, new CardClaimReq(newRequestId(),null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PAY_CARD_INACTIVE);
    }

    // ---------- 月卡的两项权益 ----------

    @Test
    @DisplayName("验收5：建造队列 +1 只在有效期内存在，到期之后不必任何回收动作就回到原上限")
    void queueBonusFollowsTheCardAndDisappearsAtExpiry() {
        String playerId = newPlayer();
        long base = System.currentTimeMillis();
        PlayerPaid bought = PlayerPaid.empty().withCardExtended(base, 30 * DAY);
        seedPaid(playerId, bought);
        long extra = monthlyRow().extraQueues();

        PlayerSave withCard = player(playerId);
        assertThat(city.cityRules(withCard, base).maxQueueCount()
                - city.cityRules(withCard, bought.cardExpireAt() + HOUR).maxQueueCount())
                .as("激活期间比到期之后多出的正好是表里那一格；而同一份存档、同一段代码，"
                        + "到期之后自动回落 —— 这一格是推出来的，没有「忘了回收」这件事")
                .isEqualTo(extra);
    }

    @Test
    @DisplayName("免广告免的是播放不是次数：广告加速的每日上限一位没动（§五②b）")
    void adFreeDoesNotLiftTheDailyAdCap() {
        String playerId = newPlayer();
        long base = System.currentTimeMillis();
        PlayerPaid bought = PlayerPaid.empty().withCardExtended(base, 30 * DAY);
        seedPaid(playerId, bought);
        PlayerSave save = player(playerId);

        assertThat(catalog.entitlements(save.paid(), base).adFree())
                .as("月卡确实带着免广告这一位").isTrue();
        assertThat(city.cityRules(save, base).adSpeedupDailyLimit())
                .as("上限与没卡时完全相同：免掉次数等于让「时间」这个核心卡点被付费绕过")
                .isEqualTo(city.cityRules(save, bought.cardExpireAt() + HOUR).adSpeedupDailyLimit());
    }

    // ---------- 首充 ----------

    @Test
    @DisplayName("验收1：首充发货 = 表里的金币 + 玩家自己挑的那名 SR，并当场登记「已首充」")
    void firstChargeGrantsGoldAndTheChosenHero() throws Exception {
        String playerId = newPlayer();
        long goldBefore = gold(playerId);

        String orderId = order(playerId, "first_charge", "hero_sr_02");
        callback(orderId);

        assertThat(gold(playerId) - goldBefore)
                .as("金币 = pr_first_gold 那一行（6 元 × 基准 10 × 倍率 2）")
                .isEqualTo(rowCount("pr_first_gold"));
        assertThat(heroes.findByPlayerId(playerId).orElseThrow().owns("hero_sr_02"))
                .as("发给玩家挑的那位，而不是候选里的第一个").isTrue();
        assertThat(orders.get(orderId).rewards())
                .extracting(PayOrder.RewardRow::id)
                .as("清单里武将、金币与那条 PRIVILEGE 都在")
                .contains("hero_sr_02", "GOLD", "first_charge");
        assertThat(player(playerId).paid().firstCharged()).isTrue();
    }

    @Test
    @DisplayName("验收1：首充只送一次，第二笔在下单处就拒，不等玩家付完钱再说没有")
    void secondFirstChargeOrderIsRefusedAtOrderTime() throws Exception {
        String playerId = newPlayer();
        callback(order(playerId, "first_charge", "hero_sr_01"));
        assertThat(player(playerId).paid().firstCharged())
                .as("第一笔首充的货发出去之后，账号上必须留下首充标记 —— 第二笔的拒单全靠它")
                .isTrue();
        int ledgerBefore = player(playerId).paid().fulfilledOrderIds().size();
        assertThat(catalog.alreadyOwned(playerId, player(playerId).paid(), catalog.require("first_charge")))
                .as("判定本身要能认出「这一档本账号已经用掉」，否则问题在读法而不是规则")
                .isNotNull();

        assertThatThrownBy(() -> pay.createOrder(playerId,
                new CreateOrderReq(newRequestId(), "first_charge", 1, "hero_sr_03")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PAY_NOT_ENTITLED);
        assertThat(player(playerId).paid().fulfilledOrderIds())
                .as("被拒的那一笔没有留下任何发货记录").hasSize(ledgerBefore);
    }

    @Test
    @DisplayName("选将两头都要判：该挑没挑拒、不该挑却带了值也拒、候选之外的值也拒")
    void heroChoiceIsValidatedInBothDirections() {
        String playerId = newPlayer();

        assertThatThrownBy(() -> pay.createOrder(playerId,
                new CreateOrderReq(newRequestId(), "first_charge", 1, null)))
                .as("不替玩家默认挑：缺选择就当场拒")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThatThrownBy(() -> pay.createOrder(playerId,
                new CreateOrderReq(newRequestId(), "monthly_gold_card", 1, "hero_sr_01")))
                .as("月卡没有可挑的武将，带了 heroChoice 说明客户端拿错了商品")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThatThrownBy(() -> pay.createOrder(playerId,
                new CreateOrderReq(newRequestId(), "first_charge", 1, "hero_not_in_candidates")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    // ---------- 成长基金 ----------

    @Test
    @DisplayName("验收1：基金发货只登记「买过」，六档按主城等级分批解锁、领过即永久")
    void fundTiersUnlockByMainLevelAndAreOneShot() throws Exception {
        String playerId = newPlayer();
        PayProductCfg fund = catalog.requireKind(PayProductCfg.Kind.GROWTH_FUND);
        callback(order(playerId, fund.id(), null));

        FundStatusResp bought = claims.fundStatus(playerId);
        assertThat(bought.purchased()).isTrue();
        assertThat(bought.tiers()).as("档位数来自表，不是代码里写死的六")
                .hasSize(catalog.fundTiers(fund.id()).size());
        assertThat(bought.tiers()).as("一级主城什么都领不到，但每一档都看得见")
                .allMatch(t -> !t.claimed() && !t.claimable());
        FundTier first = bought.tiers().get(0);
        String tierId = first.tierId();
        long goldBefore = gold(playerId);

        assertThatThrownBy(() -> claims.claimFund(playerId, new FundClaimReq(newRequestId(), tierId)))
                .as("等级不够要说成「等级不够」，不能说成「没有这一档」")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PAY_TIER_LOCKED);
        assertThat(gold(playerId)).as("被拒的那一次一分钱都没发").isEqualTo(goldBefore);

        raiseMainLevel(playerId, first.requireMainLevel());
        FundClaimResp resp = claims.claimFund(playerId, new FundClaimReq(newRequestId(), tierId));
        assertThat(resp.tierId()).isEqualTo(tierId);
        assertThat(gold(playerId) - goldBefore).as("到账的就是那一行的 count")
                .isEqualTo(first.count());
        assertThat(claims.fundStatus(playerId).tiers())
                .anySatisfy(t -> {
                    assertThat(t.tierId()).isEqualTo(tierId);
                    assertThat(t.claimed()).as("领过的档位此后永远是已领").isTrue();
                    assertThat(t.claimable()).isFalse();
                });

        assertThatThrownBy(() -> claims.claimFund(playerId, new FundClaimReq(newRequestId(), tierId)))
                .as("同一档领第二次必须被拒：基金是永久权益，重复领是能刷的")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PAY_ALREADY_CLAIMED);

        // 这句会进 Result.detail，而客户端展示的正是 detail ?? msg（AppRoot.ts:1720）⇒
        // 「屏上不出现裸 id」这一维必须管到错误文案，而不是只管面板上的名字（台账 #821）
        BizException rejected = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> claims.claimFund(playerId, new FundClaimReq(newRequestId(), tierId)), BizException.class);
        assertThat(rejected.detail()).as("给玩家的话要说「主城几级那一档」，不是配置表行 id")
                .doesNotContain(tierId).contains("那一档已经领过了");
    }

    @Test
    @DisplayName("没买过基金来领档位：拒，而且不给任何奖励")
    void fundClaimWithoutPurchaseIsRefused() {
        String playerId = newPlayer();
        PayProductCfg fund = catalog.requireKind(PayProductCfg.Kind.GROWTH_FUND);
        String tierId = catalog.fundTiers(fund.id()).get(0).id();
        long goldBefore = gold(playerId);

        assertThat(claims.fundStatus(playerId).purchased()).isFalse();
        assertThatThrownBy(() -> claims.claimFund(playerId, new FundClaimReq(newRequestId(), tierId)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PAY_NOT_ENTITLED);
        assertThat(gold(playerId)).isEqualTo(goldBefore);
    }

    @Test
    @DisplayName("把日包那一行当基金档位领也拒：两族行的区别就是 requireMainLevel 有没有值")
    void dailyPackRowIsNotAFundTier() throws Exception {
        String playerId = newPlayer();
        callback(order(playerId, catalog.requireKind(PayProductCfg.Kind.GROWTH_FUND).id(), null));
        raiseMainLevel(playerId, 40);

        assertThatThrownBy(() -> claims.claimFund(playerId,
                new FundClaimReq(newRequestId(), "pr_monthly_gold")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PAY_NOT_ENTITLED);
    }

    // ---------- 验收 3：补单只发一份 ----------

    @Test
    @DisplayName("验收3：第一次失败、第二次成功 ⇒ 订单从 PAID_UNFULFILLED 到 SUCCESS，权益只加一期")
    void retryAfterFirstFailureDeliversExactlyOnce() {
        String playerId = newPlayer();
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        AtomicInteger deliverCalls = new AtomicInteger();
        PayAppService.ProductFulfiller flaky = (target, orderId, line, now) -> {
            if (deliverCalls.incrementAndGet() == 1) {
                return PayAppService.ProductFulfiller.Result.failure("测试桩：第一次故意失败");
            }
            return fulfiller.deliver(target, orderId, line, now);
        };
        PayAppService service = serviceWith(clock, flaky);

        String legacyOrderId = legacyOrder(playerId, clock.get());
        service.callback(new PayCallbackReq(legacyOrderId, "txn-1", "sign-1", true));

        assertThat(orders.get(legacyOrderId).status())
                .as("钱收了而货没发出去 ⇒ 必须留在补单队列里").isEqualTo(PayOrder.Status.PAID_UNFULFILLED);
        assertThat(player(playerId).paid().cardExpireAt())
                .as("失败的那一次不该留下任何权益").isNull();

        service.retry(playerId, new PayRetryReq(newRequestId(), legacyOrderId));

        assertThat(orders.get(legacyOrderId).status()).isEqualTo(PayOrder.Status.SUCCESS);
        assertThat(deliverCalls.get()).as("实现被调了两次，货只有一份").isEqualTo(2);
        Long expireAt = player(playerId).paid().cardExpireAt();
        assertThat(expireAt)
                .as("只延一期：幂等键是 orderId，不是「调用了几次」。"
                        + "延期时刻由发放器自己的时钟起算，所以按一秒容差比，而不是逐毫秒")
                .isBetween(clock.get() + 30 * DAY - HOUR, clock.get() + 31 * DAY);
        assertThat(player(playerId).paid().fulfilledOrderIds()).hasSize(1);
    }

    @Test
    @DisplayName("查单端点回放发货清单：玩家轮询到的「我拿到了什么」不能每次不一样")
    void orderStatusReplaysTheRewardList() throws Exception {
        String playerId = newPlayer();
        String orderId = order(playerId, "first_charge", "hero_sr_01");
        callback(orderId);

        int first = rewardsOf(orderId, playerId);
        assertThat(first).as("首充至少发出金币、武将与一条 PRIVILEGE")
                .isGreaterThanOrEqualTo(3);
        assertThat(rewardsOf(orderId, playerId)).as("再查一次，清单既没变多也没变少")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("验收9：改 pay_product 就换一份价格表——加一行商品、reload，下发立刻多一条")
    void priceTableFollowsTheProductTableAfterReload() throws Exception {
        int before = okData(perform(get("/pay/prices"))).get("products").size();
        String original = readContractTable("pay_product.json");
        // 与生产热更同一条入口（ConfigRegistry.reload），不是自己造一份注册表：
        // 只有这样才测得出"运行中的那个注册表真的换了"
        configs.reload("pay_product", PayProductCfg.class, withAnExtraProduct(original));
        try {
            JsonNode products = okData(perform(get("/pay/prices"))).get("products");
            assertThat(products.size()).as("表里多一行，价格表就多一条：没有第二份清单要改")
                    .isEqualTo(before + 1);
            JsonNode added = null;
            for (JsonNode node : products) {
                if ("weekly_card".equals(node.get("productId").asText())) {
                    added = node;
                }
            }
            assertThat(added).as("新增的那一行确实下发了").isNotNull();
            assertThat(added.get("cents").asLong())
                    .as("价格读的是行里那个参数名指的值，不是写死的三档之一")
                    .isEqualTo(configs.longParam("PRODUCT_FIRST_CHARGE_CENTS"));
        } finally {
            configs.reload("pay_product", PayProductCfg.class, original);
        }
        assertThat(okData(perform(get("/pay/prices"))).get("products").size())
                .as("还原之后回到原样（别把上下文留给后面的用例）").isEqualTo(before);
    }

    /** surefire 的工作目录是被测模块目录，按仓库根相对路径读会 NoSuchFile —— 向上找到 contract/ 再拼。 */
    private static String readContractTable(String fileName) throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath();
        while (dir != null && !java.nio.file.Files.isDirectory(dir.resolve("contract"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("从 " + java.nio.file.Path.of("").toAbsolutePath()
                + " 往上找不到仓库根的 contract/ 目录").isNotNull();
        return java.nio.file.Files.readString(dir.resolve("contract/config").resolve(fileName),
                StandardCharsets.UTF_8);
    }

    /** 在原文本的 rows 数组尾部追加一行商品（不另写一份表：字段顺序与 why 都由表本身负责）。 */
    private static String withAnExtraProduct(String json) {
        int rowsEnd = json.lastIndexOf(']');
        int lastRowBrace = json.lastIndexOf('}', rowsEnd);
        String extra = ",\n    {\"id\": \"weekly_card\", \"name\": \"测试周卡\", \"kind\": \"MONTHLY_CARD\","
                + " \"priceCentsParam\": \"PRODUCT_FIRST_CHARGE_CENTS\", \"grantOccasion\": \"DAILY\","
                + " \"durationDays\": 7, \"adFree\": false, \"extraQueues\": 0,"
                + " \"heroChoices\": null, \"why\": \"探针行\"}";
        return json.substring(0, lastRowBrace + 1) + extra + json.substring(lastRowBrace + 1);
    }

    // ---------- 夹具 ----------

    private String newPlayer() {
        return playerInitService.init(new PlayerInitReq(newRequestId(), "dev-" + UUID.randomUUID(),
                "付费测试", System.currentTimeMillis(), "")).playerId();
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }

    /** 新售商品走HTTP下单；旧monthly_card仅构造退售前存量订单，再走真实HTTP回调/补单。 */
    private String order(String playerId, String productId, String heroChoice) throws Exception {
        if (productId.equals(monthlyId())) return legacyOrder(playerId, System.currentTimeMillis());
        JsonNode data = okData(perform(post("/pay/order")
                .header(PLAYER_HEADER, playerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(new CreateOrderReq(newRequestId(), productId, 1, heroChoice)))));
        return data.get("orderId").asText();
    }

    /** 走 HTTP 回调确认收款（回调端点不带玩家身份，与渠道侧同一条路径）。 */
    private void callback(String orderId) throws Exception {
        okData(perform(post("/pay/callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonUtils.toJson(new PayCallbackReq(orderId, "txn-" + orderId, "s", true)))));
    }

    private int rewardsOf(String orderId, String playerId) throws Exception {
        JsonNode data = okData(perform(get("/pay/order?orderId=" + orderId).header(PLAYER_HEADER, playerId)));
        return data.get("rewards").size();
    }

    private String buyCard(String playerId) throws Exception {
        callback(order(playerId, monthlyId(), null));
        return playerId;
    }

    private String monthlyId() {
        return monthlyRow().id();
    }

    private PayProductCfg monthlyRow() {
        return catalog.requireKind(PayProductCfg.Kind.MONTHLY_CARD);
    }

    /** 表里某一行的 count：把「实际到账」与「配置值」对上，而不是在测试里再抄一份数字。 */
    private long rowCount(String rewardRowId) {
        return configs.get(ProductRewardCfg.class, rewardRowId).count();
    }

    private PlayerSave player(String playerId) {
        return players.findByPlayerId(playerId).orElseThrow();
    }

    private long gold(String playerId) {
        return player(playerId).resource("GOLD").current();
    }

    private void seedPaid(String playerId, PlayerPaid paid) {
        PlayerSave save = player(playerId);
        save.setPaid(paid);
        players.save(save);
    }

    /** 只动存档上的主城等级：本用例不碰城建，等级判定读的就是这一位。 */
    private void raiseMainLevel(String playerId, int level) {
        PlayerSave save = player(playerId);
        save.setCityLevel(level);
        players.save(save);
    }

    /** 时钟可控的领取服务：只为这一条用例造时间，绝不去动上下文那份 TimeService。 */
    private PaidClaimsAppService claimsAt(long now) {
        return new PaidClaimsAppService(players, playerLock, idempotency,
                new TimeService(() -> now), configs, rewardService, catalog,inventories,rewardNames);
    }

    /** 换掉发货实现的服务实例（验收 3 要的「第一次失败」只有这条路做得到）。 */
    private PayAppService serviceWith(AtomicLong clock, PayAppService.ProductFulfiller replacement) {
        return new PayAppService(orders, verifier, replacement, configs, playerLock, idempotency,
                new TimeService(clock::get), environment,
                new com.ironoath.core.pay.PopupThrottle(new com.ironoath.core.pay.PopupThrottle.Rules(
                        configs.longParam("PAY_FIRST_PURCHASE_QUIET_HOURS") * 3_600_000L,
                        (int) configs.longParam("PAY_POPUP_PER_GIFT_DAILY_MAX"),
                        configs.longParam("PAY_POPUP_GLOBAL_COOLDOWN_MINUTES") * 60_000L)),
                MinorPaymentPolicy.UNKNOWN, catalog, players);
    }

    private JsonNode okData(JsonNode root) {
        assertThat(root.get("code").asInt())
                .as("期望成功，但服务端回了错误：%s", root).isEqualTo(ErrorCode.OK.code());
        return root.get("data");
    }

    private JsonNode perform(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        // MockMvc 默认按 ISO-8859-1 解码响应体，中文提示会变乱码
        return JsonUtils.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test void legacyMonthlySkuIsReadableButNewOrdersAreDenied() {
        String uid=newPlayer();assertThat(catalog.require("monthly_card")).isNotNull();
        assertThat(catalog.onSale().stream().map(PaidProducts.PriceEntry::productId)).doesNotContain("monthly_card");
        assertThatThrownBy(()->pay.createOrder(uid,new com.ironoath.web.dto.generated.CreateOrderReq(newRequestId(),"monthly_card",1,null)))
                .isInstanceOf(BizException.class).extracting(e->((BizException)e).errorCode()).isEqualTo(ErrorCode.PAY_PRODUCT_OFFLINE);
    }
    @Test void splitCardsUseFakePaymentAndGoldUpfrontIsSeparateFromMaterialRights() throws Exception {
        String uid=newPlayer();long before=gold(uid);
        String goldOrder=order(uid,"monthly_gold_card",null);callback(goldOrder);callback(goldOrder);
        assertThat(gold(uid)).isEqualTo(before+300);
        var goldRights=catalog.entitlements(player(uid).paid(),System.currentTimeMillis());
        assertThat(goldRights.adFree()).isFalse();assertThat(goldRights.bonusQueues()).isZero();
        String materialOrder=order(uid,"monthly_material_card",null);callback(materialOrder);callback(materialOrder);
        assertThat(gold(uid)).isEqualTo(before+300);
        var combined=catalog.entitlements(player(uid).paid(),System.currentTimeMillis());
        assertThat(combined.adFree()).isTrue();assertThat(combined.bonusQueues()).isZero();
        assertThat(player(uid).paid().cardExpireAt()).as("new cards must not overwrite the legacy expiry field").isNull();
    }


    /** 明确的退售前PENDING订单夹具，保旧价格与旧无条款快照；不再伪称退售后能新买。 */
    private String legacyOrder(String uid, long now) {
        String orderId="old-monthly-"+UUID.randomUUID();
        var monthly=catalog.requireKind(PayProductCfg.Kind.MONTHLY_CARD);
        orders.insert(PayOrder.create(orderId,uid,new PayOrder.Line(monthly.id(),1,catalog.priceCents(monthly),now,null)));
        return orderId;
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"purchase","initial","finished"})
    void newCardAcknowledgementLossNeverExtendsOrPaysAgainAndHistoryKeepsInitialGold(String stage) {
        String uid=newPlayer();long before=gold(uid);
        var created=pay.createOrder(uid,new CreateOrderReq(newRequestId(),"monthly_gold_card",1,null));
        String orderId=created.orderId();var lost=new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(call->{
            PlayerSave save=call.getArgument(0);boolean purchase=save.paid().monthlyPurchases().containsKey(orderId);
            boolean initial=save.goldClaims().containsKey("monthly:purchase:"+orderId),finished=save.paid().fulfilled(orderId);
            boolean target=purchase&&switch(stage){case "purchase"->!initial&&!finished;case "initial"->initial&&!finished;case "finished"->finished;default->false;};
            Object result=call.callRealMethod();
            if(target&&lost.compareAndSet(true,false))throw new IllegalStateException("fixture accepted save but acknowledgement lost:"+stage);
            return result;
        }).when(players).save(org.mockito.ArgumentMatchers.argThat(save->save!=null&&save.playerId().equals(uid)));
        pay.callback(new PayCallbackReq(orderId,"fixture-txn","s",true));
        assertThat(orders.get(orderId).status()).isEqualTo(PayOrder.Status.PAID_UNFULFILLED);
        var accepted=player(uid).paid().monthlyPurchases().get(orderId);assertThat(accepted).isNotNull();
        pay.retry(uid,new PayRetryReq(newRequestId(),orderId));
        assertThat(orders.get(orderId).status()).isEqualTo(PayOrder.Status.SUCCESS);
        assertThat(gold(uid)).isEqualTo(before+300);
        assertThat(player(uid).paid().monthlyPurchases()).hasSize(1);
        assertThat(player(uid).paid().monthlyPurchases().get(orderId).expiresAt()).isEqualTo(accepted.expiresAt());
        assertThat(orders.get(orderId).rewards().stream().filter(r->r.type().equals("RESOURCE")&&r.id().equals("GOLD")))
                .as("Order history must include already accepted initial300 after lost acknowledgement")
                .singleElement().satisfies(r->assertThat(r.count()).isEqualTo(300));
    }
    @Test void pendingNewCardUsesOrderTermsAfterConfigReloadAndRepeatedDeliveryAfterLedgerTrim() throws Exception {
        String uid=newPlayer();long before=gold(uid);
        var created=pay.createOrder(uid,new CreateOrderReq(newRequestId(),"monthly_gold_card",1,null));
        var originalLine=orders.get(created.orderId()).line();String original=readContractTable("pay_product.json");
        var tree=new com.fasterxml.jackson.databind.ObjectMapper().readTree(original);
        for(var row:tree.get("rows"))if(row.get("id").asText().equals("monthly_gold_card")) {
            var object=(com.fasterxml.jackson.databind.node.ObjectNode)row;
            object.put("name","changed fixture").put("initialGold",999).put("durationDays",60).put("adFree",true).put("extraQueues",5);
        }
        configs.reload("pay_product",PayProductCfg.class,tree.toString());
        try {
            callback(created.orderId());assertThat(gold(uid)).isEqualTo(before+300);
            var accepted=player(uid).paid().monthlyPurchases().get(created.orderId());
            assertThat(accepted.terms()).isEqualTo(originalLine.cardTerms());assertThat(accepted.terms().name()).isEqualTo("金币月卡");
            assertThat(accepted.expiresAt()-accepted.startsAt()).isEqualTo(30*DAY);
            assertThat(catalog.entitlements(player(uid).paid(),System.currentTimeMillis()).adFree()).isFalse();
            assertThat(catalog.entitlements(player(uid).paid(),System.currentTimeMillis()).bonusQueues()).isZero();
            var save=player(uid);var paid=save.paid();for(int i=0;i<40;i++)paid=paid.withFulfilledOrder("fixture-other-order-"+i);
            assertThat(paid.fulfilled(created.orderId())).isFalse();save.setPaid(paid);players.save(save);
            assertThat(fulfiller.deliver(uid,created.orderId(),originalLine,System.currentTimeMillis()).delivered()).isTrue();
            assertThat(gold(uid)).isEqualTo(before+300);
            assertThat(player(uid).paid().monthlyPurchases().get(created.orderId()).expiresAt()).isEqualTo(accepted.expiresAt());
        } finally {configs.reload("pay_product",PayProductCfg.class,original);}
    }


    private JsonNode claimMonthlyHttp(String uid,String sku) throws Exception {
        return perform(post("/pay/card/claim").header(PLAYER_HEADER,uid).contentType(MediaType.APPLICATION_JSON)
            .content(JsonUtils.toJson(java.util.Map.of("requestId",newRequestId(),"productId",sku))));
    }
    @Test void eachNewMonthlySkuClaimsItsOwnActualDailyPackAndRepeatIsDenied() throws Exception {
        String uid=newPlayer();callback(order(uid,"monthly_gold_card",null));callback(order(uid,"monthly_material_card",null));
        long before=gold(uid);long speed=bag.countOf(uid,"item_speedup_build_1h"),wood=bag.countOf(uid,"item_res_wood_10k");
        var goldDaily=okData(claimMonthlyHttp(uid,"monthly_gold_card"));assertThat(goldDaily.get("claimedDays").asLong()).isEqualTo(1);
        assertThat(gold(uid)).isEqualTo(before+90);assertThat(bag.countOf(uid,"item_speedup_build_1h")).isEqualTo(speed);
        var materialDaily=okData(claimMonthlyHttp(uid,"monthly_material_card"));assertThat(materialDaily.get("claimedDays").asLong()).isEqualTo(1);
        assertThat(gold(uid)).isEqualTo(before+90);assertThat(bag.countOf(uid,"item_speedup_build_1h")).isEqualTo(speed+1);assertThat(bag.countOf(uid,"item_res_wood_10k")).isEqualTo(wood+1);
        assertThat(claimMonthlyHttp(uid,"monthly_gold_card").get("code").asInt()).isEqualTo(ErrorCode.PAY_ALREADY_CLAIMED.code());
        assertThat(claimMonthlyHttp(uid,"monthly_material_card").get("code").asInt()).isEqualTo(ErrorCode.PAY_ALREADY_CLAIMED.code());
    }
    @Test void monthlyStatusHasServerOffersAndRetainedLegacyEntitlementOnlyWhenOwned() throws Exception {
        String uid=newPlayer();callback(order(uid,"monthly_gold_card",null));
        var cards=okData(perform(get("/pay/card").header(PLAYER_HEADER,uid))).get("cards");assertThat(cards).isNotNull();assertThat(cards.size()).isEqualTo(2);
        var bySku=new java.util.HashMap<String,JsonNode>();cards.forEach(c->bySku.put(c.get("productId").asText(),c));
        assertThat(bySku.get("monthly_material_card").get("priceCents").asLong()).isEqualTo(1800);assertThat(bySku.get("monthly_gold_card").get("priceCents").asLong()).isEqualTo(3000);
        assertThat(bySku.get("monthly_material_card").get("offerAdFree")).isNotNull();assertThat(bySku.get("monthly_material_card").get("offerAdFree").asBoolean()).isTrue();
        assertThat(bySku.get("monthly_material_card").get("offerEarlyCollectAll").asBoolean()).isTrue();
        assertThat(bySku.get("monthly_gold_card").get("dailyRewards").get(0).get("name").asText()).isEqualTo("金币");
        assertThat(bySku.get("monthly_gold_card").get("initialGold").asLong()).isEqualTo(300);assertThat(bySku.get("monthly_gold_card").get("active").asBoolean()).isTrue();
        var save=player(uid);seedPaid(uid,save.paid().withCardExtended(System.currentTimeMillis(),30*DAY));
        cards=okData(perform(get("/pay/card").header(PLAYER_HEADER,uid))).get("cards");assertThat(cards.size()).isEqualTo(3);
        for(var c:cards)if(c.get("productId").asText().equals("monthly_card")){assertThat(c.get("saleable").asBoolean()).isFalse();assertThat(c.get("active").asBoolean()).isTrue();}
    }
    @Test void absentProductIdStaysLegacyOnlyAndAnotherAccountCannotClaimNewEntitlement() throws Exception {
        String a=newPlayer(),b=newPlayer();callback(order(a,"monthly_gold_card",null));long before=gold(a);
        assertThatThrownBy(()->claims.claimCard(a,new CardClaimReq(newRequestId(),null))).isInstanceOf(BizException.class).extracting(e->((BizException)e).errorCode()).isEqualTo(ErrorCode.PAY_CARD_INACTIVE);
        assertThat(claimMonthlyHttp(b,"monthly_gold_card").get("code").asInt()).isEqualTo(ErrorCode.PAY_CARD_INACTIVE.code());
        assertThat(claimMonthlyHttp(a,"growth_fund").get("code").asInt()).isEqualTo(ErrorCode.PAY_NOT_ENTITLED.code());assertThat(gold(a)).isEqualTo(before);
    }

    private static final long MONTHLY_START=java.time.Instant.parse("2030-03-02T15:55:00Z").toEpochMilli();
    /** Only purchase timing/reward snapshot is controlled; callback and grant/store/receipt paths are real. */
    private com.ironoath.core.pay.MonthlyCardPurchase timedMonthly(String uid,String sku,long start,com.ironoath.core.pay.MonthlyCardTerms override) {
        var saved=player(uid);var paid=saved.paid();var original=paid.monthlyPurchases().values().stream().filter(x->x.productId().equals(sku)).findFirst().orElseThrow();
        var period=new com.ironoath.core.pay.MonthlyCardPurchase(original.orderId(),sku,override==null?original.terms():override,start,start+30*DAY,1,0);
        var map=new java.util.LinkedHashMap<>(paid.monthlyPurchases());map.put(period.orderId(),period);
        seedPaid(uid,new PlayerPaid(paid.cardExpireAt(),paid.cardClaimedThroughAt(),paid.fundPurchasedAt(),paid.fundClaimedTiers(),paid.firstChargedAt(),paid.fulfilledOrderIds(),map));return period;
    }
    @Test void goldCardActualUpfrontPlusThirtyBusinessDayClaimsIsExactlyThreeThousand() throws Exception {
        String uid=newPlayer();long before=gold(uid);callback(order(uid,"monthly_gold_card",null));assertThat(gold(uid)).isEqualTo(before+300);
        timedMonthly(uid,"monthly_gold_card",MONTHLY_START,null);
        for(int n=0;n<30;n++){
            var response=claimsAt(MONTHLY_START+n*DAY).claimCard(uid,new CardClaimReq(newRequestId(),"monthly_gold_card"));
            assertThat(response.claimedDays()).isEqualTo(1);assertThat(gold(uid)).isEqualTo(before+300+(n+1)*90);
            if(n==0){assertThatThrownBy(()->claimsAt(MONTHLY_START+10*60_000).claimCard(uid,new CardClaimReq(newRequestId(),"monthly_gold_card"))).isInstanceOf(BizException.class).extracting(e->((BizException)e).errorCode()).isEqualTo(ErrorCode.PAY_ALREADY_CLAIMED);
                assertThat(claimsAt(java.time.Instant.parse("2030-03-02T19:59:59Z").toEpochMilli()).cardStatus(uid).cards().stream().filter(c->c.productId().equals("monthly_gold_card")).findFirst().orElseThrow().claimableDays()).isZero();
                assertThat(claimsAt(java.time.Instant.parse("2030-03-02T20:00:00Z").toEpochMilli()).cardStatus(uid).cards().stream().filter(c->c.productId().equals("monthly_gold_card")).findFirst().orElseThrow().claimableDays()).isEqualTo(1);
            }
        }
        assertThat(gold(uid)-before).isEqualTo(3000);
        assertThatThrownBy(()->claimsAt(MONTHLY_START+30*DAY-1).claimCard(uid,new CardClaimReq(newRequestId(),"monthly_gold_card"))).isInstanceOf(BizException.class).extracting(e->((BizException)e).errorCode()).isEqualTo(ErrorCode.PAY_ALREADY_CLAIMED);
        assertThatThrownBy(()->claimsAt(MONTHLY_START+30*DAY).claimCard(uid,new CardClaimReq(newRequestId(),"monthly_gold_card"))).isInstanceOf(BizException.class).extracting(e->((BizException)e).errorCode()).isEqualTo(ErrorCode.PAY_CARD_INACTIVE);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"monthly_gold_card","monthly_material_card"})
    void monthlyCatchUpAcceptsAllSelectedDailyPacksInOneDurableReceipt(String sku) throws Exception {
        String uid=newPlayer();callback(order(uid,sku,null));var period=timedMonthly(uid,sku,MONTHLY_START,null);
        long before=gold(uid),speed=bag.countOf(uid,"item_speedup_build_1h"),wood=bag.countOf(uid,"item_res_wood_10k");
        var response=claimsAt(MONTHLY_START+28*DAY).claimCard(uid,new CardClaimReq(newRequestId(),sku));
        assertThat(response.claimedDays()).isEqualTo(3);
        assertMonthlyCatchUpAmounts(uid,sku,before,speed,wood,3);
        String key="monthly:daily:"+period.orderId()+":29";
        if(sku.equals("monthly_gold_card")) {
            var receipts=player(uid).goldClaims().values().stream().filter(r->r.businessKey().startsWith("monthly:daily:"+period.orderId()+":")).toList();
            assertThat(receipts).singleElement().satisfies(r->{assertThat(r.businessKey()).isEqualTo(key);assertThat(r.requested()).isEqualTo(270);assertThat(r.facts()).contains("monthly-day:29");});
        } else {
            var receipts=inventories.findByPlayerId(uid).orElseThrow().itemClaims().values();
            assertThat(receipts).singleElement().satisfies(r->{assertThat(r.businessKey()).isEqualTo(key);assertThat(r.requested()).containsEntry("item_speedup_build_1h",3L).containsEntry("item_res_wood_10k",3L);assertThat(r.facts()).contains("monthly-day:29");});
        }
        assertThat(player(uid).paid().monthlyPurchases().get(period.orderId()).settledDays()).isEqualTo(29);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"monthly_gold_card,next-day","monthly_material_card,next-day","monthly_gold_card,expired","monthly_material_card,expired"})
    void monthlyCatchUpLostAcknowledgementRecoversWholeBatchAcrossDayOrExpiry(String sku,String resume) throws Exception {
        String uid=newPlayer();callback(order(uid,sku,null));var period=timedMonthly(uid,sku,MONTHLY_START,null);
        long before=gold(uid),speed=bag.countOf(uid,"item_speedup_build_1h"),wood=bag.countOf(uid,"item_res_wood_10k");
        String prefix="monthly:daily:"+period.orderId()+":";var once=new java.util.concurrent.atomic.AtomicBoolean();
        if(sku.equals("monthly_gold_card")) {
            org.mockito.Mockito.doAnswer(inv->{Object result=inv.callRealMethod();var saved=inv.getArgument(0,PlayerSave.class);if(uid.equals(saved.playerId())&&saved.goldClaims().keySet().stream().anyMatch(k->k.startsWith(prefix))&&once.compareAndSet(false,true))throw new IllegalStateException("fixture: catch-up accepted then acknowledgement lost");return result;})
                .when(players).save(org.mockito.ArgumentMatchers.any(PlayerSave.class));
        } else {
            org.mockito.Mockito.doAnswer(inv->{Object result=inv.callRealMethod();var saved=inv.getArgument(1,com.ironoath.core.bag.Inventory.class);if(saved.itemClaims().keySet().stream().anyMatch(k->k.startsWith(prefix))&&once.compareAndSet(false,true))throw new IllegalStateException("fixture: catch-up accepted then acknowledgement lost");return result;})
                .when(inventories).save(org.mockito.ArgumentMatchers.eq(uid),org.mockito.ArgumentMatchers.any(com.ironoath.core.bag.Inventory.class),org.mockito.ArgumentMatchers.anyLong());
        }
        assertThatThrownBy(()->claimsAt(MONTHLY_START+28*DAY).claimCard(uid,new CardClaimReq(newRequestId(),sku))).isInstanceOf(IllegalStateException.class).hasMessageContaining("catch-up accepted");
        assertThat(once).isTrue();
        long later=MONTHLY_START+(resume.equals("expired")?30:29)*DAY;
        claimsAt(later).cardStatus(uid);claimsAt(later).cardStatus(uid);
        assertThat(player(uid).paid().monthlyPurchases().get(period.orderId()).settledDays()).as("the accepted three-day batch survives the new cutoff").isEqualTo(29);
        assertMonthlyCatchUpAmounts(uid,sku,before,speed,wood,3);
        if(resume.equals("next-day")) {
            assertThat(claimsAt(later).claimCard(uid,new CardClaimReq(newRequestId(),sku)).claimedDays()).isEqualTo(1);
            assertMonthlyCatchUpAmounts(uid,sku,before,speed,wood,4);
        } else {
            assertThatThrownBy(()->claimsAt(later).claimCard(uid,new CardClaimReq(newRequestId(),sku))).isInstanceOf(BizException.class).extracting(e->((BizException)e).errorCode()).isEqualTo(ErrorCode.PAY_CARD_INACTIVE);
            assertMonthlyCatchUpAmounts(uid,sku,before,speed,wood,3);
        }
    }
    private void assertMonthlyCatchUpAmounts(String uid,String sku,long before,long speed,long wood,long days) {
        boolean goldCard=sku.equals("monthly_gold_card");
        assertThat(gold(uid)).isEqualTo(before+(goldCard?days*90:0));
        assertThat(bag.countOf(uid,"item_speedup_build_1h")).isEqualTo(speed+(goldCard?0:days));
        assertThat(bag.countOf(uid,"item_res_wood_10k")).isEqualTo(wood+(goldCard?0:days));
    }
    @Test void acceptedDailyGoldWriteWithLostAcknowledgementRestoresCursorWithoutPayingAgain() throws Exception {
        String uid=newPlayer();callback(order(uid,"monthly_gold_card",null));var period=timedMonthly(uid,"monthly_gold_card",MONTHLY_START,null);long before=gold(uid);
        String key="monthly:daily:"+period.orderId()+":1";var once=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(inv->{Object result=inv.callRealMethod();var saved=inv.getArgument(0,PlayerSave.class);if(uid.equals(saved.playerId())&&saved.goldClaims().containsKey(key)&&once.compareAndSet(false,true))throw new IllegalStateException("fixture: daily gold accepted then acknowledgement lost");return result;})
            .when(players).save(org.mockito.ArgumentMatchers.any(PlayerSave.class));
        assertThatThrownBy(()->claimsAt(MONTHLY_START).claimCard(uid,new CardClaimReq(newRequestId(),"monthly_gold_card"))).isInstanceOf(IllegalStateException.class);
        assertThat(gold(uid)).isEqualTo(before+90);assertThat(player(uid).paid().monthlyPurchases().get(period.orderId()).settledDays()).isZero();
        claimsAt(MONTHLY_START).cardStatus(uid);claimsAt(MONTHLY_START).cardStatus(uid);
        assertThat(player(uid).paid().monthlyPurchases().get(period.orderId()).settledDays()).isEqualTo(1);assertThat(gold(uid)).isEqualTo(before+90);
        assertThatThrownBy(()->claimsAt(MONTHLY_START).claimCard(uid,new CardClaimReq(newRequestId(),"monthly_gold_card"))).isInstanceOf(BizException.class).extracting(e->((BizException)e).errorCode()).isEqualTo(ErrorCode.PAY_ALREADY_CLAIMED);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"before-cursor","after-cursor"})
    void acceptedMaterialPairThenCursorFaultRecoversWithoutRegrant(String stage) throws Exception {
        String uid=newPlayer();callback(order(uid,"monthly_material_card",null));var period=timedMonthly(uid,"monthly_material_card",MONTHLY_START,null);long before=gold(uid),speed=bag.countOf(uid,"item_speedup_build_1h"),wood=bag.countOf(uid,"item_res_wood_10k");var once=new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(inv->{var save=inv.getArgument(0,PlayerSave.class);boolean fail=uid.equals(save.playerId())&&save.paid().monthlyPurchases().get(period.orderId()).settledDays()==1&&once.compareAndSet(false,true);if(fail&&stage.equals("before-cursor"))throw new IllegalStateException("fixture: cursor write not accepted");Object result=inv.callRealMethod();if(fail)throw new IllegalStateException("fixture: cursor accepted then acknowledgement lost");return result;})
            .when(players).save(org.mockito.ArgumentMatchers.any(PlayerSave.class));
        assertThatThrownBy(()->claimsAt(MONTHLY_START).claimCard(uid,new CardClaimReq(newRequestId(),"monthly_material_card"))).isInstanceOf(IllegalStateException.class);
        claimsAt(MONTHLY_START).cardStatus(uid);assertThat(player(uid).paid().monthlyPurchases().get(period.orderId()).settledDays()).isEqualTo(1);
        assertThat(gold(uid)).isEqualTo(before);assertThat(bag.countOf(uid,"item_speedup_build_1h")).isEqualTo(speed+1);assertThat(bag.countOf(uid,"item_res_wood_10k")).isEqualTo(wood+1);
        assertThatThrownBy(()->claimsAt(MONTHLY_START).claimCard(uid,new CardClaimReq(newRequestId(),"monthly_material_card"))).isInstanceOf(BizException.class).extracting(e->((BizException)e).errorCode()).isEqualTo(ErrorCode.PAY_ALREADY_CLAIMED);
    }
    @Test void paidDailyRewardsUseOwnedOrderSnapshotAndCurrentOfferIsShownSeparately() throws Exception {
        String uid=newPlayer();callback(order(uid,"monthly_gold_card",null));var owned=new com.ironoath.core.pay.MonthlyCardTerms("旧购买时快照",30,300,java.util.List.of(new com.ironoath.core.reward.RewardItem(com.ironoath.core.reward.RewardType.RESOURCE,"GOLD",7)),false,0,false);
        timedMonthly(uid,"monthly_gold_card",MONTHLY_START,owned);long before=gold(uid);
        var view=claimsAt(MONTHLY_START).cardStatus(uid).cards().stream().filter(c->c.productId().equals("monthly_gold_card")).findFirst().orElseThrow();
        assertThat(view.dailyRewards().get(0).count()).isEqualTo(90);assertThat(view.ownedDailyRewards().get(0).count()).isEqualTo(7);
        claimsAt(MONTHLY_START).claimCard(uid,new CardClaimReq(newRequestId(),"monthly_gold_card"));assertThat(gold(uid)).isEqualTo(before+7);
    }
    @Test void oldJsonMissingProductIdRemainsLegacyAndNewDailyCursorsCannotSettleLegacy() throws Exception {
        var oldReq=JsonUtils.fromJson("{\"requestId\":\"legacy-missing-sku\"}",CardClaimReq.class);assertThat(oldReq.productId()).isNull();
        String uid=newPlayer();callback(order(uid,"monthly_gold_card",null));callback(order(uid,"monthly_material_card",null));long base=System.currentTimeMillis();seedPaid(uid,player(uid).paid().withCardExtended(base,30*DAY));
        claims.claimCard(uid,new CardClaimReq(newRequestId(),"monthly_gold_card"));claims.claimCard(uid,new CardClaimReq(newRequestId(),"monthly_material_card"));
        assertThat(player(uid).paid().cardClaimedThroughAt()).isNotNull(); // original pre-purchase cursor is retained
        assertThat(claims.cardStatus(uid).claimableDays()).isEqualTo(1);long before=gold(uid);
        claims.claimCard(uid,new CardClaimReq(newRequestId(),null));assertThat(gold(uid)-before).isEqualTo(50);
    }
}
