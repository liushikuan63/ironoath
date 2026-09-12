package com.ironoath.config;

import com.ironoath.config.cfg.HeroCfg;
import com.ironoath.config.cfg.HeroRarityCfg;
import com.ironoath.config.cfg.ItemCfg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：hero_rarity 表与 item 表的碎片道具一致性。
 * 依赖：JUnit 5 + AssertJ、生成的 cfg 类型；读仓库内真实的 contract/config。
 *
 * <p><b>这条断言是被一个真实缺口逼出来的</b>：hero_rarity 给四档都配了 dupFragment，
 * 抽卡四档也都会出重复武将，但 item 表原先只有 SR / SSR 两档的碎片道具。
 * 后果是抽到重复 R/N 武将时，发放器按 {@code item_mat_hero_frag_<rarity>} 找不到道具、
 * 抛异常、奖励进补偿队列 —— 玩家看到「+8 碎片」但背包里没有，
 * 而服务端日志里只有一条 ERROR，两张表各自都通过校验，没有任何一处会提前报警。
 *
 * <p>跨表缺口的共同特征就是这样：<b>单表校验全绿，只在真正执行时炸</b>。
 * 所以凡是「A 表的每一行都要求 B 表存在对应行」的关系，都必须写成断言。
 */
class HeroRarityFragmentConsistencyTest {

    private static ConfigRegistry registry;

    @BeforeAll
    static void load() {
        registry = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    }

    @Test
    @DisplayName("hero_rarity 的每一档都必须在 item 表里有对应的碎片道具")
    void everyRarityHasAFragmentItem() {
        List<String> missing = new ArrayList<>();
        for (HeroRarityCfg rarity : registry.all(HeroRarityCfg.class)) {
            String itemId = fragmentItemId(rarity.id());
            if (!registry.rawTable("item").has(itemId)) {
                missing.add(rarity.id() + " -> " + itemId);
            }
        }
        assertThat(missing)
                .as("这些稀有度档配了碎片经济却没有对应的碎片道具：抽到重复武将时发放会失败并进补偿队列，"
                        + "玩家看到「+N 碎片」但背包里没有")
                .isEmpty();
    }

    @Test
    @DisplayName("hero 表出现的每个稀有度都必须在 hero_rarity 表里有经济定义")
    void everyHeroRarityHasEconomy() {
        List<String> missing = new ArrayList<>();
        for (HeroCfg hero : registry.all(HeroCfg.class)) {
            if (!registry.rawTable("hero_rarity").has(hero.rarity().name())) {
                missing.add(hero.id() + " -> " + hero.rarity());
            }
        }
        assertThat(missing)
                .as("这些武将的稀有度在 hero_rarity 表里没有定义，重复获得时无法折算碎片、也无法升星")
                .isEmpty();
    }

    @Test
    @DisplayName("碎片道具本身必须自洽：MATERIAL 类型、稀有度与档位一致、不可出售")
    void fragmentItemsAreSelfConsistent() {
        for (HeroRarityCfg rarity : registry.all(HeroRarityCfg.class)) {
            ItemCfg item = registry.get(ItemCfg.class, fragmentItemId(rarity.id()));
            assertThat(item.type()).as("%s 的碎片必须是 MATERIAL 类型", item.id())
                    .isEqualTo(ItemCfg.Type.MATERIAL);
            assertThat(item.rarity().name())
                    .as("%s 的稀有度必须与它所属的档位一致，否则背包排序与品质框都会错", item.id())
                    .isEqualTo(rarity.id());
            assertThat(item.sellable())
                    .as("%s 不可出售：碎片一旦能卖成金币，玩家就会算出「卖碎片换金币再抽卡」"
                            + "是否比直接升星更优，那条线会从养成变成套利题", item.id())
                    .isFalse();
            assertThat(item.stackMax())
                    .as("%s 的堆叠上限必须容得下一次十连的碎片产出", item.id())
                    .isGreaterThanOrEqualTo(rarity.dupFragment() * 10L);
        }
    }

    @Test
    @DisplayName("碎片经济随稀有度单调：越稀有的档位，重复转碎片与升星消耗都越高")
    void fragmentEconomyIsMonotonicAcrossRarities() {
        // 顺序即稀有度升序，与 hero 表 / ItemRarity 枚举一致
        List<String> order = List.of("N", "R", "SR", "SSR");
        long previousDup = 0L;
        long previousCompose = 0L;
        long previousStar = 0L;
        for (String rarityId : order) {
            HeroRarityCfg row = registry.get(HeroRarityCfg.class, rarityId);
            assertThat(row.dupFragment()).as("%s 的重复转碎片必须高于 %s 档",
                            rarityId, "上一").isGreaterThan(previousDup);
            assertThat(row.composeFragment()).as("%s 的合成消耗必须高于上一档", rarityId)
                    .isGreaterThan(previousCompose);
            assertThat(row.starUpFragment()).as("%s 的升星消耗必须高于上一档", rarityId)
                    .isGreaterThan(previousStar);
            // 合成消耗必须高于重复转碎片的产出，否则「抽到重复 → 转碎片 → 合成」会变成正反馈循环，
            // 玩家越抽越赚，卡池的深度锚（B15 付费点排序第一位）会被套利掏空
            assertThat(row.composeFragment())
                    .as("%s 档：合成消耗必须高于单次重复转碎片，否则重复抽取变成正反馈", rarityId)
                    .isGreaterThan(row.dupFragment());
            previousDup = row.dupFragment();
            previousCompose = row.composeFragment();
            previousStar = row.starUpFragment();
        }
    }

    /** 与 HeroFragmentExtras / HeroStatsService 里的拼法必须一致。 */
    private static String fragmentItemId(String rarityId) {
        return "item_mat_hero_frag_" + rarityId.toLowerCase(Locale.ROOT);
    }
}
