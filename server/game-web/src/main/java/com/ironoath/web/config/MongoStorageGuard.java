package com.ironoath.web.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 职责：{@code ironoath.storage=mongo} 时，校验<b>每一个存储端口真的换成了 Mongo 实现</b>，
 * 否则拒绝启动并点名是谁还在用内存。
 *
 * <p><b>这道守卫换过一次形态，两段历史都要留着</b>：
 * <ol>
 *   <li>第一形态是 2026-09-09 的 {@code BeanFactoryPostProcessor}：那时 mongo 模式根本起不来
 *       （缺 bean），守卫在任何业务 bean 实例化之前就失败，把「存储层没写完」与
 *       「某个 bean 忘了注入」这两件事分开说。它当时给的是可核对的欠账清单。</li>
 *   <li>2026-09-12 社交存储补完，18 个端口全部有了 Mongo 实现，mongo 模式第一次真的能起来。
 *       但"能起来"不等于"都换了" —— 缺一道检查的话，将来有人新增一个存储端口、
 *       只写内存实现，mongo 模式下上下文照样能起（或者被 @Primary 劫持），
 *       症状又回到"日志说在用 mongo、请求也不报错、那部分状态照样随进程消失"。
 *       所以守卫改成 {@link SmartInitializingSingleton}：等所有单例就绪后按端口逐个核对
 *       实际注入的实现类，只接受 {@code com.ironoath.web.store.mongo.*}。</li>
 * </ol>
 *
 * <p><b>为什么不是删掉守卫</b>：删掉之后，"mongo 模式到底换了哪些存储"这件事就没有唯一的
 * 可执行判定点了。{@code BeanAssemblyTest} 守的是 memory 模式下的条件装配，
 * 本类守的是 mongo 模式下的实现来源，两者缺一不可。
 *
 * <p><b>为什么在 SmartInitializingSingleton 而不是 BeanFactoryPostProcessor</b>：
 * 前者拿得到真正的 bean 实例（包括工厂方法声明的类型与实际实现类的差异），
 * 后者在实例化之前只能看到 bean 定义，{@code @Bean} 工厂方法的 bean 类型往往被声明成端口接口，
 * 看不到背后是内存还是 Mongo。
 */
@Component
public class MongoStorageGuard implements SmartInitializingSingleton, EnvironmentAware, ApplicationContextAware {

    private static final Logger LOG = LoggerFactory.getLogger(MongoStorageGuard.class);

    private static final String STORAGE_KEY = "ironoath.storage";
    /** 只认这个包里的实现。测试传入类名做纯函数判定，所以用字符串前缀而不是 Class 判断。 */
    static final String MONGO_PACKAGE_PREFIX = "com.ironoath.web.store.mongo.";

    /**
     * 全部存储端口。新增一类存储时必须加进来（与 {@code BeanAssemblyTest#STORAGE_PORTS} 同一份名单）——
     * 这份名单本身就是"一个端口都没漏"的判据，漏加会让新增端口静默逃过 mongo 校验。
     */
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
            com.ironoath.web.battle.BattleReportStore.class,
            com.ironoath.web.quest.QuestProgressStore.class,
            com.ironoath.web.mail.MailStore.class,
            com.ironoath.web.reward.RewardCompensationStore.class,
            com.ironoath.web.activity.ActivityProgressStore.class,
            com.ironoath.web.nation.NationStore.class,
            com.ironoath.web.ops.TrackEventStore.class,
            com.ironoath.web.social.SocialStore.class,
            com.ironoath.web.battlepass.BattlePassStore.class);

    private Environment environment;
    private ApplicationContext applicationContext;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        String storage = environment.getProperty(STORAGE_KEY, GameProperties.STORAGE_MEMORY);
        if (!GameProperties.STORAGE_MONGO.equals(storage)) {
            return;
        }
        Map<Class<?>, List<String>> implementationByPort = new LinkedHashMap<>();
        for (Class<?> port : STORAGE_PORTS) {
            List<String> classNames = new ArrayList<>();
            for (Object bean : applicationContext.getBeansOfType(port, true, false).values()) {
                // 代理类名也带包前缀，但取目标类更直白，且不受代理策略变化影响
                classNames.add(AopUtils.getTargetClass(bean).getName());
            }
            implementationByPort.put(port, classNames);
        }
        List<String> offenders = offenders(implementationByPort);
        if (!offenders.isEmpty()) {
            throw new IllegalStateException("生产存储模式（" + STORAGE_KEY + "=" + storage
                    + "）下存在不是 Mongo 实现的存储端口，拒绝启动：\n  "
                    + String.join("\n  ", offenders)
                    + "\n这些端口在 mongo 模式下会「正常启动」但数据随进程消失、"
                    + "且每个实例各算各的 —— 那是比启动失败难得发现得多的错误。\n"
                    + "补齐办法：在 MongoStoreConfig 里为端口加 @Bean，实现放进 "
                    + MONGO_PACKAGE_PREFIX + "*，并补一条跨实现等价测试。");
        }
        LOG.info("mongo 存储自检通过：{} 个存储端口全部是 MongoDB 实现", STORAGE_PORTS.size());
    }

    /**
     * 纯函数判定，便于单测直接喂数据：每个端口至少要有一个实现，且每个实现的类名都要落在
     * Mongo 包里。没有实现同样算不合格 —— 那种情况下上下文通常已经因为缺 bean 起不来，
     * 但把判定写全，错误信息才不会依赖 Spring 的报错顺序。
     */
    static List<String> offenders(Map<Class<?>, List<String>> implementationByPort) {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<Class<?>, List<String>> entry : implementationByPort.entrySet()) {
            List<String> classNames = entry.getValue();
            if (classNames == null || classNames.isEmpty()) {
                offenders.add(entry.getKey().getSimpleName() + "：没有任何实现");
                continue;
            }
            for (String className : classNames) {
                if (className == null || !className.startsWith(MONGO_PACKAGE_PREFIX)) {
                    offenders.add(entry.getKey().getSimpleName() + " → " + className
                            + "（不是 " + MONGO_PACKAGE_PREFIX + "*）");
                }
            }
        }
        return offenders;
    }
}