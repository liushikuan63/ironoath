package com.ironoath.web.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardType;

/**
 * 职责：钉住奖励展示名的唯一实现（B12 §2 邮件附件要用它，而它此前有两份互不一致的实现）。
 * 依赖：容器（要真配置表与真碎片映射）。
 *
 * <p><b>为什么要单独给它一条用例</b>：把 {@code BagAppService} 与 {@code QuestAppService}
 * 各写一份的 {@code rewardName} 收成一份，最坏的下场不是编译不过，而是<b>某个界面的名字悄悄变了</b>。
 * 而 28 条背包用例一条都没红 —— 因为原来没有任何断言看过那个字符串。
 * "没有读者"正是这条测试存在的全部理由。
 */
@ActiveProfiles("test")
@SpringBootTest
class RewardNamesTest {

    @Autowired private RewardNames names;
    @Autowired private ConfigRegistry configs;
    @Autowired private HeroFragmentExtras fragments;

    @Test
    @DisplayName("资源与道具的名字来自配置表，不是 id")
    void resourceAndItemNamesComeFromTheTables() {
        assertThat(names.nameOf(new RewardItem(RewardType.RESOURCE, "GOLD", 1L)))
                .as("金币的中文名在 resource 表里").isNotBlank().isNotEqualTo("GOLD");
        String itemId = configs.all(ItemCfg.class).get(0).id();
        assertThat(names.nameOf(new RewardItem(RewardType.ITEM, itemId, 1L)))
                .isEqualTo(configs.get(ItemCfg.class, itemId).name());
    }

    @Test
    @DisplayName("武将碎片回的是「该档碎片道具」的名字：映射问的是发放侧，不自己拼")
    void heroFragmentNameComesFromTheGrantingSideMapping() {
        String heroId = "hero_ssr_02";

        assertThat(names.nameOf(new RewardItem(RewardType.HERO_FRAGMENT, heroId, 30L)))
                .as("碎片落在按稀有度的那个道具上，名字就必须是那个道具的名字")
                .isEqualTo(configs.get(ItemCfg.class, fragments.fragmentItemOf(heroId)).name())
                .isNotEqualTo(heroId);
    }

    @Test
    @DisplayName("整卡回武将名；体力与特权尚未落地，如实回 id 而不编一个名字")
    void unlandedTypesReportTheIdRatherThanAnInventedName() {
        String heroId = "hero_ssr_02";
        assertThat(names.nameOf(new RewardItem(RewardType.HERO, heroId, 1L)))
                .isNotBlank().isNotEqualTo(heroId);
        assertThat(names.nameOf(new RewardItem(RewardType.STAMINA, "stamina", 20L)))
                .as("B09 还没落地：给它编一个中文名就是发明").isEqualTo("stamina");

        assertThatThrownBy(() -> names.nameOf(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得为 null");
    }
}
