package com.ironoath.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.config.cfg.PayProductCfg;
import com.ironoath.config.cfg.ProductRewardCfg;
import com.ironoath.config.cfg.QuestCfg;
import com.ironoath.config.cfg.ShopCfg;

/**
 * 职责：付费商品的**数字自洽**（B19-S1）。三类商品的总价、倍率、期数与发货组成必须互相算得回来。
 * 依赖：仓库内真实的 {@code contract/config}（{@link ConfigRegistry#loadFromDirectory}）。
 *
 * <p><b>为什么这些是测试而不是运行时校验</b>：商品表与奖励表分家之后，任何单独一张表都是合法的，
 * 只有放在一起才看得出「日包加起来 120 而月卡只值 100/天」这种错。校验器（{@code ConfigValidator}）
 * 做的是逐字段检查，跨表算总账不在它射程内 —— 与 {@code ChestConfigConsistencyTest} 同一处取舍。
 *
 * <p><b>为什么值得单独钉</b>：这是付费。组成与标价不一致的症状不是崩溃，而是
 * 「玩家按 A 的价钱买到了 B 的货」或反方向，而两边日志都写着成功。提审前的合规复核也查不出来 ——
 * 它查的是概率公示与限额，不是月卡日包加总。
 */
class ProductRewardConsistencyTest {

    private static ConfigRegistry configs;

    @BeforeAll
    static void load() {
        configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    }

    private List<PayProductCfg> products() {
        return configs.all(PayProductCfg.class);
    }

    private List<ProductRewardCfg> rewards() {
        return configs.all(ProductRewardCfg.class);
    }

    private PayProductCfg product(String id) {
        return products().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    /** 价格只有一个家：本表存参数名，数字住在 global。 */
    private long priceOf(PayProductCfg row) {
        return param(row.priceCentsParam());
    }

    private long param(String id) {
        return configs.longParam(id);
    }

    /** 道具的金币等值：取商店里那一件以 GOLD 计的售价；表里没有第二处定价（一个数一个家）。 */
    private long goldValueOfItem(String itemId) {
        List<ShopCfg> priced = configs.all(ShopCfg.class).stream()
                .filter(row -> itemId.equals(row.itemId())
                        && row.priceCurrency() == ShopCfg.PriceCurrency.GOLD)
                .toList();
        assertThat(priced).as("道具 %s 的金币等值只应以 shop 表的 GOLD 价为准", itemId)
                .hasSize(1);
        return priced.get(0).price();
    }

    /** 一行奖励值多少金币：RESOURCE 直接算，ITEM 走商店价。 */
    private long goldValueOf(ProductRewardCfg row) {
        return switch (row.rewardType()) {
            case RESOURCE -> {
                assertThat(row.rewardId()).as("RESOURCE 的 rewardId 必须是 resource 表的行")
                        .isEqualTo("GOLD");
                yield row.count();
            }
            case ITEM -> goldValueOfItem(row.rewardId()) * row.count();
        };
    }

    @Test
    @DisplayName("反空转：两张表都读到东西（读空了下面的断言会全部真通过，那是假绿）")
    void bothTablesActuallyLoad() {
        assertThat(products()).as("商品行数低于下限").hasSizeGreaterThanOrEqualTo(3);
        assertThat(rewards()).as("奖励行数低于下限").hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("一个 kind 只有一行商品，且 priceCentsParam 指的是真实存在的 global 参数")
    void oneRowPerKindAndPriceIsAPointerNotACopy() {
        Map<String, Long> byKind = products().stream()
                .collect(Collectors.groupingBy(p -> p.kind().name(), Collectors.counting()));
        assertThat(byKind.values()).as("同一类商品出现两行 ⇒ 下单时按哪个发货没有答案")
                .allMatch(n -> n == 1L);
        assertThat(byKind.keySet()).containsExactlyInAnyOrder("MONTHLY_CARD", "GROWTH_FUND", "FIRST_CHARGE");

        for (PayProductCfg row : products()) {
            assertThat(configs.hasParam(row.priceCentsParam()))
                    .as("%s 的价格只能有一个家：本表存参数名，数字住在 global", row.id())
                    .isTrue();
            assertThat(param(row.priceCentsParam())).as("标价必须为正（%s）", row.id()).isPositive();
        }
    }

    @Test
    @DisplayName("月卡：日包组成加起来 = 标价 × 基准 × 倍率 ÷ 天数，一分不多一分不少")
    void monthlyCardDailyPackMatchesTheDerivedRate() {
        PayProductCfg card = product("monthly_card");
        long days = card.durationDays();
        long derivedPerDay = priceOf(card) / 100L * param("PAY_BASE_GOLD_PER_YUAN")
                * param("MONTHLY_CARD_DAILY_MULTIPLIER") / days;
        long composed = sumOfMonthlyDailyLines();

        assertThat(days).as("月卡必须带有效期（DAILY 发放没有期数就没有「日」）").isEqualTo(30L);
        assertThat(composed)
                .as("日包组成必须等于按 #145 换算基准推出来的每日额度，否则玩家拿到的与他付的钱不成比例")
                .isEqualTo(derivedPerDay);
        assertThat(card.grantOccasion()).isEqualTo(PayProductCfg.GrantOccasion.DAILY);
        assertThat(card.adFree()).as("月卡三件套之一：免广告播放").isTrue();
        assertThat(card.extraQueues()).as("月卡三件套之二：建造队列 +1（到期收回由实现负责）").isEqualTo(1L);
    }

    /** 月卡的日包行 = 挂在这件商品下、且没有等级门槛的那些行。 */
    private long sumOfMonthlyDailyLines() {
        return rewards().stream().filter(r -> "monthly_card".equals(r.productId()))
                .peek(r -> assertThat(r.requireMainLevel())
                        .as("日包行不该带等级门槛（带了的语义是「按档领」，那是基金那一族）").isNull())
                .mapToLong(this::goldValueOf).sum();
    }

    @Test
    @DisplayName("首充：金币按倍率算得回来，三选一的候选与主线赠送同一批、且每个 id 真在武将表里")
    void firstChargeGoldAndHeroChoiceSetAreBothDerivedFromTheAdjudicatedNumbers() {
        PayProductCfg first = product("first_charge");
        long derived = priceOf(first) / 100L * param("PAY_BASE_GOLD_PER_YUAN")
                * param("FIRST_CHARGE_MULTIPLIER");
        long goldLines = rewards().stream().filter(r -> "first_charge".equals(r.productId()))
                .mapToLong(this::goldValueOf).sum();

        assertThat(goldLines).as("首充给的金币必须等于「双倍」这两个字的算法值").isEqualTo(derived);
        assertThat(first.grantOccasion()).as("首充是买完立即发，不是每天领")
                .isEqualTo(PayProductCfg.GrantOccasion.ON_PURCHASE);

        List<String> choices = List.of(first.heroChoices().split(","));
        assertThat(choices).as("三选一就是三个候选，多一个少一个都要改文案").hasSize(3);
        Set<String> heroIds = configs.all(com.ironoath.config.cfg.HeroCfg.class).stream()
                .map(com.ironoath.config.cfg.HeroCfg::id).collect(Collectors.toSet());
        assertThat(heroIds).containsAll(choices);

        QuestCfg questOne = configs.all(QuestCfg.class).stream()
                .filter(q -> q.id().equals("quest_main_01")).findFirst().orElseThrow();
        assertThat(first.heroChoices())
                .as("付费与主线送的是同一批武将：两份字符串靠这条用例同源，不靠运行时互相引用")
                .isEqualTo(questOne.rewardHeroChoices());
    }

    @Test
    @DisplayName("成长基金：六档严格递增、加起来等于返还倍率算出的总额，最后一档是尾款")
    void growthFundTiersSumToTheReturnRatio() {
        PayProductCfg fund = product("growth_fund");
        assertThat(fund.durationDays()).as("基金永久有效（§五②d）：没有到期日就不该有 durationDays").isNull();
        assertThat(fund.grantOccasion()).isEqualTo(PayProductCfg.GrantOccasion.TIER);

        List<ProductRewardCfg> tiers = new ArrayList<>(rewards().stream()
                .filter(r -> "growth_fund".equals(r.productId())).toList());
        assertThat(tiers).hasSize(6);
        tiers.sort((a, b) -> Long.compare(a.requireMainLevel(), b.requireMainLevel()));

        long previousLevel = 0L;
        for (ProductRewardCfg tier : tiers) {
            assertThat(tier.requireMainLevel()).as("档位必须有主城等级门槛：" + tier.id())
                    .isNotNull();
            assertThat(tier.requireMainLevel())
                    .as("门槛必须严格递增，否则两档同时解锁、玩家不知道第二档存在过").isGreaterThan(previousLevel);
            assertThat(goldValueOf(tier)).as("档位金额必须为正（%s）", tier.id()).isPositive();
            previousLevel = tier.requireMainLevel();
        }

        long total = tiers.stream().mapToLong(this::goldValueOf).sum();
        long derived = priceOf(fund) / 100L * param("PAY_BASE_GOLD_PER_YUAN")
                * param("PRODUCT_GROWTH_FUND_RETURN_RATIO");
        assertThat(total).as("六档加总必须等于「98 元 × 基准 × 返还倍率」").isEqualTo(derived);
        assertThat(tiers.get(tiers.size() - 1).count())
                .as("尾档给得略多（§五②d 的 1600×5 + 1800）").isGreaterThan(tiers.get(0).count());
    }

    @Test
    @DisplayName("每个商品都得有发货内容；没有奖励行的商品就是「收了钱不发东西」")
    void everyProductHasRewardLines() {
        for (PayProductCfg row : products()) {
            assertThat(rewards()).filteredOn(r -> r.productId().equals(row.id()))
                    .as("%s 没有任何奖励行：下单会成功、发货会发出一份空清单", row.id())
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("rewardId 必须指向真实存在的行（REF 只能表达一表，另一表由 shop/item 存在性核）")
    void rewardIdsPointAtRealRows() {
        Set<String> itemIds = configs.all(com.ironoath.config.cfg.ItemCfg.class).stream()
                .map(com.ironoath.config.cfg.ItemCfg::id).collect(Collectors.toSet());
        Set<String> resourceIds = configs.all(com.ironoath.config.cfg.ResourceCfg.class).stream()
                .map(com.ironoath.config.cfg.ResourceCfg::id).collect(Collectors.toSet());
        for (ProductRewardCfg row : rewards()) {
            Set<String> universe = switch (row.rewardType()) {
                case ITEM -> itemIds;
                case RESOURCE -> resourceIds;
            };
            assertThat(universe).as("%s 的 %s=%s 在那张表里不存在", row.id(), row.rewardType(), row.rewardId())
                    .contains(row.rewardId());
        }
    }

    @Test
    @DisplayName("外键不存在的奖励行会被启动期校验直接拒（本表能挂上去是因为配置外键不是装饰）")
    void danglingProductForeignKeyIsRejectedAtLoad() {
        Map<String, String> jsonByTable = new java.util.LinkedHashMap<>();
        jsonByTable.put("pay_product", "{\"table\":\"pay_product\",\"version\":1,\"fieldTypes\":{"
                + "\"id\":\"STRING_KEY\",\"name\":\"STRING\",\"kind\":\"ENUM:MONTHLY_CARD,GROWTH_FUND,FIRST_CHARGE\","
                + "\"priceCentsParam\":\"STRING\",\"grantOccasion\":\"ENUM:ON_PURCHASE,DAILY,TIER\","
                + "\"durationDays\":\"?LONG_POS\",\"adFree\":\"BOOL\",\"extraQueues\":\"LONG_NONNEG\","
                + "\"heroChoices\":\"?STRING\",\"why\":\"?STRING\"},\"rows\":["
                + "{\"id\":\"monthly_card\",\"name\":\"x\",\"kind\":\"MONTHLY_CARD\","
                + "\"priceCentsParam\":\"PRODUCT_MONTHLY_CARD_CENTS\",\"grantOccasion\":\"DAILY\","
                + "\"durationDays\":30,\"adFree\":true,\"extraQueues\":1,\"heroChoices\":null}]}");
        jsonByTable.put("product_reward", "{\"table\":\"product_reward\",\"version\":1,\"fieldTypes\":{"
                + "\"id\":\"STRING_KEY\",\"productId\":\"REF:pay_product\",\"requireMainLevel\":\"?LONG_POS\","
                + "\"rewardType\":\"ENUM:RESOURCE,ITEM\",\"rewardId\":\"STRING\",\"count\":\"LONG_POS\","
                + "\"why\":\"?STRING\"},\"rows\":["
                + "{\"id\":\"pr_orphan\",\"productId\":\"no_such_product\",\"requireMainLevel\":null,"
                + "\"rewardType\":\"RESOURCE\",\"rewardId\":\"GOLD\",\"count\":1}]}");

        assertThatThrownBy(() -> ConfigRegistry.loadFromJson(jsonByTable))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("no_such_product");
    }

    @Test
    @DisplayName("日包组成的金币等值只从 shop 表读，读不到定价的道具当场失败而不是算成 0")
    void itemWithoutShopGoldPriceFailsLoudly() {
        Optional<ProductRewardCfg> any = rewards().stream().filter(r -> r.rewardType()
                == ProductRewardCfg.RewardType.ITEM).findFirst();
        assertThat(any).isPresent();
        assertThatThrownBy(() -> goldValueOfItem("item_not_in_shop_at_all"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("金币等值");
    }
}
