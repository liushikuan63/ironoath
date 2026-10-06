package com.ironoath.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 职责：装配层的两条不变量 —— 都是"读代码看不出来、跑起来也不报错"的形状。
 * 依赖：真实的 test 上下文。
 *
 * <p><b>为什么把 {@code @Primary} 单独钉一条</b>（收口清单 #37 第一次排掉那颗雷）：
 * 存储端口的内存实现挂着 {@code @Primary} 时，今天同类型只有它一个，所以它不解决任何问题；
 * 而一旦有人补上生产实现，mongo 模式下 Spring 仍会挑这个 {@code @Primary} 的内存 bean。
 * 表现是"日志说在用 mongo、上下文起得来、请求也不报错"，但那部分状态照样随进程消失 ——
 * 正是 #16 全篇要防的"起得来但没换存储"。改成 {@code @ConditionalOnProperty} 之后，
 * 缺实现会让上下文起不来，问题从"静默"变成"响亮"。
 *
 * <p><b>为什么这条只查存储端口而不是"全项目不许 @Primary"</b>：确实存在合法的 Primary ——
 * {@code HeroBeansConfig#rewardExtras} 与 {@code RewardBeansConfig} 里的
 * {@code UnsupportedExtras} 同为 {@code RewardPorts.Extras} 的两个候选，
 * 那里去掉 Primary 会直接造成注入歧义、启动失败。一条会误伤合法用法的断言，
 * 下一个人的处置是关掉它，那就白写了。
 */
@SpringBootTest
@ActiveProfiles("test")
class BeanAssemblyTest {

    @Autowired private ConfigurableListableBeanFactory beanFactory;
    @Autowired private org.springframework.core.env.Environment environment;

    /** 全部存储端口。新增一类存储时必须加进来，否则"漏标条件装配"这件事没人查。 */
    private static final List<Class<?>> STORAGE_PORTS = List.of(
            com.ironoath.core.player.PlayerRepository.class,
            com.ironoath.core.idempotency.IdempotencyStore.class,
            com.ironoath.core.city.CityRepository.class,
            com.ironoath.core.bag.InventoryRepository.class,
            com.ironoath.core.hero.HeroRepository.class,
            com.ironoath.core.army.ArmyRepository.class,
            com.ironoath.core.gacha.GachaStateRepository.class,
            com.ironoath.core.gacha.GachaLogStore.class,
            com.ironoath.core.march.MarchRepository.class,
            com.ironoath.core.march.MarchDueQueue.class,
            com.ironoath.core.world.WorldRepository.class,
            com.ironoath.core.stage.StageProgressRepository.class,
            com.ironoath.core.pay.PayOrderStore.class,
            com.ironoath.web.season.SeasonLedgerStore.class,
            com.ironoath.web.battlepass.BattlePassStore.class,
            com.ironoath.web.battle.BattleReportStore.class,
            com.ironoath.web.quest.QuestProgressStore.class,
            com.ironoath.web.mail.MailStore.class,
            com.ironoath.web.reward.RewardCompensationStore.class,
            com.ironoath.web.activity.ActivityProgressStore.class,
            com.ironoath.web.nation.NationStore.class,
            com.ironoath.web.nation.WarStore.class,
            com.ironoath.web.ops.TrackEventStore.class,
            com.ironoath.web.social.SocialStore.class);

    @Test
    @DisplayName("存储端口的实现不许挂 @Primary：它今天不解决任何歧义，补上生产实现时会劫持装配")
    void storagePortsMustNotBeWiredWithPrimary() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> port : STORAGE_PORTS) {
            for (String name : beanFactory.getBeanNamesForType(port, true, false)) {
                if (beanFactory.getBeanDefinition(name).isPrimary()) {
                    offenders.add(name + " : " + port.getSimpleName());
                }
            }
        }
        assertThat(offenders)
                .as("这些存储 bean 挂着 @Primary。正确写法是 @ConditionalOnProperty"
                        + "（storage=memory, matchIfMissing=true），见 HeroBeansConfig 与 StageBeansConfig 的类注释")
                .isEmpty();
    }

    /**
     * 每个存储端口在当前模式下恰好一个实现。
     *
     * <p>条件装配写错方向（{@code havingValue} 打错、或两边都注册）时，Spring 报的是
     * "某个 service 的构造参数缺 bean"，读的人会以为某个 {@code @Bean} 忘了写 ——
     * #16 最初就是这么被误诊的。这里直接按"恰好一个"检查，点名是哪个端口多了或不见了。
     */
    @Test
    @DisplayName("memory 模式下每个存储端口恰好一个实现：条件装配不许写反方向")
    void everyStoragePortHasExactlyOneImplementation() {
        List<String> wrong = new ArrayList<>();
        for (Class<?> port : STORAGE_PORTS) {
            int found = beanFactory.getBeanNamesForType(port, true, false).length;
            if (found != 1) {
                wrong.add(port.getSimpleName() + "=" + found);
            }
        }
        assertThat(wrong)
                .as("实现数不等于 1 的那些（当前 ironoath.storage="
                        + environment.getProperty("ironoath.storage", "memory") + "）")
                .isEmpty();
    }
}
