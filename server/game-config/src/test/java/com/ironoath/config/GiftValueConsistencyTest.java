package com.ironoath.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.config.cfg.GiftCfg;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.config.cfg.PayProductCfg;
import com.ironoath.config.cfg.ProductRewardCfg;
import com.ironoath.config.cfg.ShopCfg;

/**
 * 职责：守住 B19 §五⑤ 那三个礼包的三件事 —— <b>价值凑得满、触发不重复、发货走的是同一条链路</b>。
 * 依赖：仓库内真实的 {@code contract/config}（gift / pay_product / product_reward / item 四张表）+ global 参数。
 *
 * <p><b>为什么"价值 180"必须是断言而不是文档里的一句话</b>：礼包是弹窗推给未付费玩家的转化位，
 * 它的宣传口径就是那一眼能看到的内容。凑不满 180 不会让任何东西报错，只会让
 * 「弹窗写着 180 等值、实际到手 150」这一句话在三个月后变成投诉记录里的一行 ——
 * 而且那时没人能回答"是哪一次改表改少的"。与 #152（时长基数）/#165（强化基数）同一条判断：
 * <b>量出来的数要变成回归卡口，否则它就只是一句现在成立的话</b>。
 *
 * <p><b>折算口径 2026-09-19 换成商店的货架价</b>（B24 裁决④把 item 表的 {@code sellable}/{@code sellPriceGold}
 * 两列彻底删了：出售整条撤下）：原先拿"回收价"折算的理由是"货架价与回收价刻意同值、且玩家看得见"，
 * 现在回收价不存在了，而**货架价正好接下这两个性质** —— 它同样是玩家在商店里能看到的数，
 * 折算出来的等值仍然可自查。换锚后数字不变（换锚时现跑核过：四个礼包道具的货架价与当年的回收价逐个相等），
 * 但判据更强了一点：以前那个锚允许"货架价与回收价不一致"（只要有回收价就能过），现在锚就是货架价本身。
 */
class GiftValueConsistencyTest {

    private static ConfigRegistry configs;

    @BeforeAll
    static void load() {
        configs = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    }

    private List<ProductRewardCfg> rowsOf(String productId) {
        return configs.all(ProductRewardCfg.class).stream()
                .filter(r -> productId.equals(r.productId()))
                .toList();
    }

    /** 一档商品的金币等值：ITEM 按商店货架价、RESOURCE 里的金币按 1:1，其他资源不计（礼包内容里不该出现）。 */
    private long goldEquivalent(ProductRewardCfg row) {
        if (row.rewardType() == ProductRewardCfg.RewardType.RESOURCE) {
            assertThat(row.rewardId()).as("礼包内容里的资源只允许 GOLD（别的资源没有统一的金币价）")
                    .isEqualTo("GOLD");
            return row.count();
        }
        ItemCfg item = configs.get(ItemCfg.class, row.rewardId());
        long shelfPrice = goldShelfPriceOf(item.id());
        assertThat(shelfPrice)
                .as("道具 %s 不在金币货架上就没法折算，等值算式会静默按 0 计", item.id())
                .isPositive();
        return shelfPrice * row.count();
    }

    /**
     * 某个道具的金币货架价。礼包的价值口径必须落在"玩家能在商店里看到的那个数"上 ——
     * 换锚前用的是回收价，而那一列已经随 B24 裁决④ 删除（出售整条撤下）。
     */
    private long goldShelfPriceOf(String itemId) {
        for (ShopCfg row : configs.all(ShopCfg.class)) {
            if (row.priceCurrency() == ShopCfg.PriceCurrency.GOLD && itemId.equals(row.itemId())) {
                return row.price();
            }
        }
        return 0L;
    }

    /** 裁决给的应有价值：600 分 × 基准 10 金币/元 × 礼包倍率 3 = 180（三个参数全现读，不写死）。 */
    private long expectedValue() {
        long cents = configs.longParam("PRODUCT_GIFT_CENTS");
        long perYuan = configs.longParam("PAY_BASE_GOLD_PER_YUAN");
        long multiplier = configs.longParam("GIFT_VALUE_MULTIPLIER");
        assertThat(cents).as("礼包价参数必须为正，否则等值无从算起").isPositive();
        assertThat(perYuan).isPositive();
        assertThat(multiplier).isPositive();
        return Math.multiplyExact(Math.multiplyExact(cents / 100L, perYuan), multiplier);
    }

    @Test
    @DisplayName("三档礼包的内容各自精确等于 180 金币等值（不是「约等于」）")
    void everyGiftIsWorthExactlyWhatTheRulingSays() {
        long expected = expectedValue();
        assertThat(expected).as("§五⑤ 定的是 6 元 × 10 × 3 = 180").isEqualTo(180L);
        for (GiftCfg gift : configs.all(GiftCfg.class)) {
            List<ProductRewardCfg> rows = rowsOf(gift.productId());
            assertThat(rows).as("礼包 %s 指向的商品 %s 没有发货内容行：弹窗会推一个买了什么都拿不到的东西",
                    gift.id(), gift.productId()).isNotEmpty();
            long value = rows.stream().mapToLong(this::goldEquivalent).sum();
            assertThat(value)
                    .as("礼包「%s」内容折算 %d 金币等值，与裁决的 %d 不等（差额=%d）—— "
                                    + "改内容要重算三行，改倍率要重算全部礼包：见 B19 §五⑤",
                            gift.name(), value, expected, value - expected)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("三类触发一一不同且与表里的 ENUM 声明同形（弹窗接线靠它分派，重了就是同一件事推两次）")
    void triggersAreDistinctAndMatchTheDeclaredVocabulary() {
        List<GiftCfg> gifts = configs.all(GiftCfg.class);
        assertThat(gifts).as("首批 3 个礼包（§五⑤）").hasSize(3);
        Set<GiftCfg.Trigger> seen = new LinkedHashSet<>();
        for (GiftCfg gift : gifts) {
            assertThat(gift.trigger()).as("礼包 %s 没填触发条件", gift.id()).isNotNull();
            assertThat(gift.name()).as("礼包 %s 的名称为空（弹窗标题直接用它）", gift.id())
                    .isNotBlank();
            seen.add(gift.trigger());
        }
        assertThat(seen).as("三类触发各一个礼包，不许两档共用一个触发点")
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(GiftCfg.Trigger.class));
    }

    @Test
    @DisplayName("gift.productId 必须指向 kind=GIFT 的商品：混进月卡/首充会让礼包买到一份 30 天权益")
    void giftsPointAtGiftKindProductsOnly() {
        Map<String, PayProductCfg> products = configs.all(PayProductCfg.class).stream()
                .collect(Collectors.toMap(PayProductCfg::id, r -> r));
        List<String> wrong = new ArrayList<>();
        for (GiftCfg gift : configs.all(GiftCfg.class)) {
            PayProductCfg product = products.get(gift.productId());
            if (product == null) {
                wrong.add(gift.id() + " → 商品不存在 " + gift.productId());
            } else if (product.kind() != PayProductCfg.Kind.GIFT) {
                wrong.add(gift.id() + " → kind=" + product.kind());
            } else if (!"PRODUCT_GIFT_CENTS".equals(product.priceCentsParam())) {
                wrong.add(gift.id() + " → 价格参数不是 PRODUCT_GIFT_CENTS 而是 " + product.priceCentsParam());
            }
        }
        assertThat(wrong).as("礼包与三档永久商品（月卡/基金/首充）必须是不同的 kind").isEmpty();
    }

    @Test
    @DisplayName("限购与倒计时：每礼包每日 1 次、触发后 60 分钟；两者都要为正（0 会被当成「不限」）")
    void purchaseLimitsAndOfferWindowsAreStated() {
        for (GiftCfg gift : configs.all(GiftCfg.class)) {
            assertThat(gift.limitCount())
                    .as("%s 的每日限购数（§五⑤ 定的是每日 1 次）", gift.id()).isEqualTo(1L);
            assertThat(gift.offerTtlMinutes())
                    .as("%s 的触发后有效时长（§五⑤ 定的是 60 分钟）", gift.id()).isPositive();
        }
    }

    @Test
    @DisplayName("三档礼包共用同一个价格参数：改价的语义是「三个一起改」，那正是 §五⑤ 要的形状")
    void allGiftsShareOnePriceParam() {
        List<String> params = configs.all(PayProductCfg.class).stream()
                .filter(r -> r.kind() == PayProductCfg.Kind.GIFT)
                .map(PayProductCfg::priceCentsParam).distinct().toList();
        assertThat(params).containsExactly("PRODUCT_GIFT_CENTS");
        assertThat(BigDecimal.valueOf(configs.longParam("PRODUCT_GIFT_CENTS")))
                .as("§五⑤：6 元 = 600 分")
                .isEqualByComparingTo(BigDecimal.valueOf(600));
    }

    @Test
    @DisplayName("发货内容不许出现在 gift 表里（B19 §四：礼包不得新开第二条发放通路）")
    void giftTableCarriesNoRewardColumnsAtAll() {
        // 列名取生成物的 record 分量：那才是"这张表对外暴露哪些列"的真相，
        // 而且它不会因为 ConfigRegistry 换内部 API 而碎
        List<String> fields = java.util.Arrays.stream(GiftCfg.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(fields)
                .as("gift 表出现了带奖励语义的列，那条内容就会与 product_reward 分叉：" + fields)
                // 精确列名黑名单，不做子串匹配：limitCount 里的 "count" 是限购数，不是发货数量，
                // 用子串判会把这条卡口变成"永远红"或者"改了也没用"
                .doesNotContainAnyElementsOf(List.of("rewardType", "rewardId", "count",
                        "itemId", "itemIds", "quantity", "gold", "rewards"));
    }
}
