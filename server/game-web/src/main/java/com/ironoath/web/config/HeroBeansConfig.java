package com.ironoath.web.config;

import com.ironoath.core.gacha.GachaLogStore;
import com.ironoath.core.gacha.GachaStateRepository;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.web.config.GameProperties;
import com.ironoath.web.reward.HeroFragmentExtras;
import com.ironoath.web.store.memory.InMemoryGachaLogStore;
import com.ironoath.web.store.memory.InMemoryGachaStateStore;
import com.ironoath.web.store.memory.InMemoryHeroStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 职责：装配 B06 武将与抽卡所需的端口实现。
 * 依赖：Spring Boot、game-core 的端口、game-web 的内存存储。
 *
 * <p><b>两个内存实现带着合规风险，必须在启动时喊出来</b>：
 * <ul>
 *   <li>保底进度（{@link InMemoryGachaStateStore}）重启即丢，而 gacha 表的公示文案承诺
 *       「保底计数不因赛季或版本更新而清零」。丢了就是公示不实。</li>
 *   <li>抽卡日志（{@link InMemoryGachaLogStore}）重启即丢，而 B06 §6 要求保留 90 天
 *       （监管会查）。丢了就是拿不出合规凭证。</li>
 * </ul>
 * 所以生产环境必须设 {@code ironoath.storage=mongo} 并提供 MongoDB 版实现（B16）。
 * 这两条 WARN 日志的存在，就是为了让任何人在启动服务端时都看到这两个缺口。
 */
@Configuration
public class HeroBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(HeroBeansConfig.class);

    /**
     * 武将 / 抽卡保底 / 抽卡日志三类状态的内存实现。
     *
     * <p><b>装配条件从"@Bean + @Primary"改成"只在 storage=memory 时存在"</b>：那几个
     * {@code @Primary} 当年不解决任何歧义（同类型只有它一个 bean），却会埋一颗雷 ——
     * 一旦 Mongo 版补上，mongo 模式下 Spring 仍会挑 {@code @Primary} 那个内存实现，
     * 表现是"服务起来了、日志也说在用 mongo，但武将与保底数据还是随进程消失"。
     * #16 要防的正是这种"起得来但没换存储"，所以这里与城建/背包用同一套条件装配：
     * <b>哪个存储模式缺实现，就让上下文直接起不来</b>，而不是悄悄退回内存。
     */
    @Bean
    @ConditionalOnProperty(name = "ironoath.storage", havingValue = GameProperties.STORAGE_MEMORY,
            matchIfMissing = true)
    public HeroRepository heroRepository() {
        LOG.warn("使用内存武将存储：进程重启后武将、养成进度与编队全部丢失，仅限本地开发与单测。"
                + "生产请设 ironoath.storage=mongo（MongoDB 版由 B16 交付）");
        return new InMemoryHeroStore();
    }

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage", havingValue = GameProperties.STORAGE_MEMORY,
            matchIfMissing = true)
    public GachaStateRepository gachaStateRepository() {
        LOG.warn("使用内存抽卡保底进度：进程重启后保底计数归零，而 gacha 表的公示文案承诺"
                + "「保底计数不因赛季或版本更新而清零」—— 生产环境必须换成持久化实现（B16），"
                + "否则构成公示不实");
        return new InMemoryGachaStateStore();
    }

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage", havingValue = GameProperties.STORAGE_MEMORY,
            matchIfMissing = true)
    public GachaLogStore gachaLogStore() {
        LOG.warn("使用内存抽卡日志：进程重启即丢，而 B06 §6 要求保留 {} 天且监管会查。"
                + "生产环境必须换成带 (playerId, drawnAt) 复合索引的持久化实现（B16）",
                90);
        return new InMemoryGachaLogStore();
    }

    /**
     * 用 {@link HeroFragmentExtras} 替换 B04 那个「三类奖励一律抛异常」的临时实现。
     *
     * <p>{@code @Primary} 是必需的：{@code RewardBeansConfig} 里已经注册了一个
     * {@code TransientRewardPorts.UnsupportedExtras} 类型的 bean，
     * 不加 Primary 就会因为同类型两个候选而启动失败。
     * 体力与特权仍然由 HeroFragmentExtras 抛异常（B09 / B15 落地），
     * 所以这次替换只解锁了武将碎片与整卡两类，缺口没有变大。
     *
     * <p><b>2026-09-12 起多一个 {@code HeroRepository} 参数</b>：整卡武将（{@code RewardType.HERO}）
     * 要写进武将册而不是背包 —— B06 §1「主线赠送：首日必得 1 名 SR」这条链路此前零实现，
     * 表现是 troopCap 恒为 0（新号没武将 ⇒ 训不了兵 ⇒ 主线第 3 步死锁）。
     */
    @Bean
    @Primary
    public RewardPorts.Extras rewardExtras(RewardPorts.Bag playerBag,
                                          com.ironoath.config.ConfigRegistry configs,
                                          HeroRepository heroRepo) {
        LOG.warn("武将碎片与整卡武将已接通真实存储（整卡写武将册、重复按档折碎片）；"
                + "体力（B09）与特权（B15）仍会响亮失败并进补偿队列");
        return new HeroFragmentExtras(playerBag, configs, heroRepo);
    }
    @Bean
    @ConditionalOnProperty(name = "ironoath.storage", havingValue = GameProperties.STORAGE_MEMORY,
            matchIfMissing = true)
    public com.ironoath.core.army.ArmyRepository armyRepository() {
        LOG.warn("使用内存军队存储：进程重启后兵力、训练队列与伤兵全部丢失，仅限本地开发与单测。"
                + "生产请设 ironoath.storage=mongo（MongoDB 版由 B16 交付）");
        return new com.ironoath.web.store.memory.InMemoryArmyStore();
    }
}
