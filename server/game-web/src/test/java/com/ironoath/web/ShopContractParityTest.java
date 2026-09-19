package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.AvatarFrameCfg;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.config.cfg.ShopCfg;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.dto.generated.ShopCurrency;
import com.ironoath.web.dto.generated.ShopRefresh;
import com.ironoath.web.dto.generated.ShopRowView;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.ShopAppService;

/**
 * 职责：商店协议枚举与配置表枚举的一致性（协议那份是重新声明的，会漂移）。
 * 依赖：Spring Boot Test（要真实配置表与一个真实玩家）。
 *
 * <p><b>为什么这一族漂移值得单独钉</b>：商店的货币与刷新口径在三个地方各写了一遍 ——
 * 表里的 {@code fieldTypes} 字符串、配置生成器产出的 {@code ShopCfg} 内嵌枚举、
 * 协议里的 {@code ShopCurrency} / {@code ShopRefresh}。前两者由配置校验器在启动时对齐
 * （不一致就不启动），<b>而第三者没有任何机械检查</b>：协议里少一个取值时，
 * 症状是那一页货架永远空着、或者 {@code valueOf} 在运行时抛 ——
 * 而玩家看到的是「商店没货」，不是报错。
 */
@SpringBootTest
@ActiveProfiles("test")
class ShopContractParityTest {

    @Autowired private ShopAppService shop;
    @Autowired private ConfigRegistry configs;
    @Autowired private PlayerInitService playerInitService;

    @Test
    @DisplayName("ShopCurrency 与 ShopCfg.PriceCurrency 必须逐字同名同集合")
    void currencyEnumsMatch() {
        List<String> protocol = Arrays.stream(ShopCurrency.values()).map(Enum::name)
                .collect(Collectors.toList());
        List<String> domain = Arrays.stream(ShopCfg.PriceCurrency.values()).map(Enum::name)
                .collect(Collectors.toList());
        assertThat(protocol).as("协议少一个币种 = 那一页货架永远出不来；多一个 = valueOf 在运行时抛")
                .containsExactlyInAnyOrderElementsOf(domain);
    }

    @Test
    @DisplayName("ShopRefresh 与 ShopCfg.RefreshType 必须逐字同名同集合")
    void refreshEnumsMatch() {
        List<String> protocol = Arrays.stream(ShopRefresh.values()).map(Enum::name)
                .collect(Collectors.toList());
        List<String> domain = Arrays.stream(ShopCfg.RefreshType.values()).map(Enum::name)
                .collect(Collectors.toList());
        assertThat(protocol).containsExactlyInAnyOrderElementsOf(domain);
    }

    /**
     * 每个币种页都必须可列，且所有页合起来正好覆盖整张表。
     *
     * <p>这条兜的是「新增一个枚举取值」那类改动：它会让服务端某个 switch 少一个分支而当场抛，
     * 也会让某一行商品不属于任何一页而永远没人看得见。两种结果都要在这一条里红，
     * 而不是等到某个玩家发现自己花钱上架的东西不见了。
     */
    @Test
    @DisplayName("四种货币逐页可列、页内不混币种；所有页合起来不重不漏地覆盖 shop 表")
    void everyPageIsListableAndCoversTheWholeTable() {
        String playerId = player("货架一致性");
        Set<String> listed = new HashSet<>();

        for (ShopCurrency currency : ShopCurrency.values()) {
            // 故意不加 try/catch：某个 switch 少了分支时就该在这里抛出来
            for (ShopRowView row : shop.list(playerId, currency).rows()) {
                assertThat(row.currency()).as("页签 %s 里混进了别的币种的商品", currency)
                        .isEqualTo(currency);
                assertThat(listed).as("同一行商品出现在两页里（两页的价格口径会互相打架）")
                        .doesNotContain(row.rowId());
                listed.add(row.rowId());
            }
        }

        Set<String> inTable = configs.all(ShopCfg.class).stream().map(ShopCfg::id)
                .collect(Collectors.toSet());
        assertThat(listed).as("有商品行不属于任何一页，玩家永远看不见它")
                .containsExactlyInAnyOrderElementsOf(inTable);
    }

    /**
     * 每一行必须**恰好**指向一个真实存在的交付物：要么是道具（itemId），要么是外观（frameId）。
     *
     * <p>这条从「指向真道具」扩成「指向真交付物」的理由就是外观那一行：头像框不是道具，
     * 把它塞进 itemId 里伪造一个道具正是 B24 验收 5 说的假指向（`shop_season_skin` 当年
     * 名字说皮肤、给的却是集结加成道具）。所以这里同时钉三件事：两种指向**互斥**
     * （都填 = 买一次发两份，都不填 = 买了什么都拿不到）、各自必须能在对应的表里查到。
     */
    @Test
    @DisplayName("每一行要么指向真道具、要么指向真头像框，且不能既指道具又指外观或两者都不指")
    void everyRowPointsAtSomethingReal() {
        Set<String> items = configs.all(ItemCfg.class).stream().map(ItemCfg::id)
                .collect(Collectors.toSet());
        Set<String> frames = configs.all(AvatarFrameCfg.class).stream().map(AvatarFrameCfg::id)
                .collect(Collectors.toSet());
        for (ShopCfg row : configs.all(ShopCfg.class)) {
            boolean hasItem = row.itemId() != null && !row.itemId().isBlank();
            boolean hasFrame = row.frameId() != null && !row.frameId().isBlank();
            assertThat(hasItem ^ hasFrame)
                    .as("商店行 %s 的交付物指向不唯一（itemId=%s, frameId=%s）："
                            + "两者都填等于一次购买发两份，都不填等于花钱什么都拿不到",
                            row.id(), row.itemId(), row.frameId())
                    .isTrue();
            if (hasItem) {
                assertThat(items).as("商店行 %s 指向一个不存在的道具：买了会发不出东西", row.id())
                        .contains(row.itemId());
            } else {
                assertThat(frames).as("商店行 %s 指向一个不存在的头像框：买了会戴不上", row.id())
                        .contains(row.frameId());
            }
        }
    }

    /**
     * 金币页不得依赖任何组织身份。
     *
     * <p>防的是一个具体的错法：把「在不在联盟/小队」这类判定写成对所有货币通用，
     * 会让一个没加入组织的人在用金币买东西时被要求先入盟 —— 而金币页是 13 行里的 10 行，
     * 那是商店的主路径，出错时表现是「商店一直提示我没资格」。
     */
    @Test
    @DisplayName("金币行的不可买原因里不出现组织身份")
    void goldRowsDoNotRequireMembership() {
        for (ShopRowView row : shop.list(player("独行客"), ShopCurrency.GOLD).rows()) {
            if (row.lockReason() == null) {
                continue;
            }
            assertThat(row.lockReason())
                    .as("金币行 %s 的不可买原因里出现了组织身份：%s", row.rowId(), row.lockReason())
                    .doesNotContain("联盟").doesNotContain("小队");
        }
    }

    private String player(String nickName) {
        return playerInitService.init(new PlayerInitReq(
                "req-" + UUID.randomUUID(), "dev-" + UUID.randomUUID(), nickName, 1_700_000_000_000L, ""))
                .playerId();
    }
}
