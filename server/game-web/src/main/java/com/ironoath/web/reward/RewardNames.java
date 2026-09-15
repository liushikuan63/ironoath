package com.ironoath.web.reward;

import org.springframework.stereotype.Component;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.HeroCfg;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.core.reward.RewardItem;

/**
 * 职责：一条奖励的<b>展示名</b>的唯一实现。
 * 依赖：game-config（查名字）、{@link HeroFragmentExtras}（碎片 → 稀有度碎片道具那条映射）。
 *
 * <p><b>本轮为什么把它抽出来</b>：邮件的附件要在列表里显示名字，那就是第三份需要这条规则的地方。
 * 而在抽出来之前，仓库里已经有两份<b>互相不一致</b>的实现：
 * {@code QuestAppService.rewardName} 把 HERO_FRAGMENT 拼成「SSR 武将碎片」，
 * 而 {@code BagAppService.rewardName} 直接回 id（玩家看到的是 {@code hero_ssr_01}）。
 * 更糟的是前者注释自称「与 BagAppService 同一条口径」—— <b>注释说一致而代码不一致</b>，
 * 是这一族最难的形状：读代码的人会按注释去信任，而没有任何卡口会发现两边不同。
 *
 * <p><b>统一到哪一边</b>：碎片的名字要经过「武将 → 稀有度 → 碎片道具」那条映射，
 * 而那条映射的<b>家在发放侧</b>（{@link HeroFragmentExtras#fragmentItemOf}，它自己的注释就写着
 * 「三个地方各拼一次，迟早有一处拼错」）。所以这里问它要道具 id，再取 item 表的 {@code name}。
 * 结果与 quest 侧原来手拼的字符串逐字相同（item 表里那四行就叫「SSR 武将碎片」这种形态），
 * <b>所以任务面板显示不变，变的是背包侧从裸 id 变成人名可读的名字</b> —— 那是一次修正，
 * 不是一处回归：同一条奖励在两个界面本来就该同名。
 *
 * <p>体力与特权回 id：这两类奖励今天还没有落地实现（发放时响亮地失败并进补偿队列），
 * 给一个还不存在的系统编一个中文名就是发明。
 */
@Component
public class RewardNames {

    private final ConfigRegistry configs;
    private final HeroFragmentExtras fragments;

    public RewardNames(ConfigRegistry configs, HeroFragmentExtras fragments) {
        this.configs = configs;
        this.fragments = fragments;
    }

    /** 一条奖励的人看得懂的名字。 */
    public String nameOf(RewardItem reward) {
        if (reward == null) {
            throw new IllegalArgumentException("reward 不得为 null：没有奖励就没什么名字可回");
        }
        return switch (reward.type()) {
            case RESOURCE -> configs.getResource(reward.id()).name();
            case ITEM -> configs.get(ItemCfg.class, reward.id()).name();
            case HERO -> configs.get(HeroCfg.class, reward.id()).name();
            case HERO_FRAGMENT -> configs.get(ItemCfg.class, fragments.fragmentItemOf(reward.id())).name();
            case STAMINA, PRIVILEGE -> reward.id();
        };
    }
}
