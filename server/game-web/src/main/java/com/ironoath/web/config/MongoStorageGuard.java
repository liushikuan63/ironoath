package com.ironoath.web.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 职责：{@code ironoath.storage=mongo} 时<b>立刻</b>失败，并把还差什么说清楚。
 * 依赖：Spring 的 {@link Environment}；不碰任何业务 bean。
 *
 * <p><b>为什么不直接让 Spring 报它自己的错</b>：这条守卫当初要解决的第一个症状确实是
 * {@code Parameter 3 of constructor in CityAppService required a bean of type CityRepository} ——
 * 它把「存储层没写完」说成「某个 bean 忘了注入」，读的人会以为加个 @Bean 就修好了。
 * 那句话现在已经被修掉了（城建与背包都有了 Mongo 实现），但守卫必须留着，而且更危险的情形才刚出现：
 * <b>补上剩下的实现之前，服务会"起得来"</b> —— 而起得来的 mongo 模式意味着一部分状态进 Mongo、
 * 一部分状态仍随进程消失且每个实例各算各的。那比"起不来"难得发现得多，所以这道闸门
 * 要一直守到最后一类内存仓储被换掉，而不是只在缺 bean 时才有意义。
 *
 * <p><b>为什么是 {@link BeanFactoryPostProcessor}</b>：它跑在任何业务 bean 实例化之前，
 * 所以这条消息一定比 Spring 的缺 bean 报错先出现。放进 {@code InitializingBean} 不行 ——
 * 那个 bean 可能排在失败点之后，永远轮不到它说话。
 *
 * <p><b>本类不修问题，只是拒绝假装没问题。</b>补齐计划见 {@code 收口清单.md} 第 16 项；
 * 每补一类仓储，就要多一条对应的证明 —— 有乐观锁的走 {@code VersionedStoreContractTest} 子类，
 * 没有乐观锁的（支付订单、抽卡两类）走跨实现等价测试。于是"内存与 Mongo 语义等价"的
 * 覆盖范围是可数的，不是一句形容词。
 */
@Component
public class MongoStorageGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final String STORAGE_KEY = "ironoath.storage";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        String storage = environment.getProperty(STORAGE_KEY, GameProperties.STORAGE_MEMORY);
        if (!GameProperties.STORAGE_MONGO.equals(storage)) {
            return;
        }
        throw new IllegalStateException("生产存储模式（" + STORAGE_KEY + "=" + storage + "）当前不可用。\n"
                + "  已有 Mongo 实现、且各有契约测试兜着的：player、幂等键、城建档、背包、武将、军队、"
                + "保底进度、抽卡日志、行军、支付订单、关卡进度、战报、国家、赛季账本、赛季榜与快照、"
                + "世界、埋点。\n"
                + "  仍然是「无条件装配的内存实现」的：只剩 social（联盟、小队、聊天、互助）"
                + "—— 它还没有端口（今天各处直接注入 {@code InMemorySocialStore}），"
                + "所以这一步是「先抽端口、再补实现」两件事，见收口清单 #16 与最新一档。\n"
                + "  后果不是起不来而是更坏的：mongo 模式会「正常启动」，但上面这些数据随进程消失，"
                + "且每个实例各算各的 —— 每日限额、在途队伍与联盟账目在两个实例上会算出不同结果。"
                + "「内存与 Mongo 语义等价」这条承诺目前只覆盖上面那十七项。\n"
                + "补齐计划见 收口清单.md 第 16 项；在那之前请用 dev profile 验证，不要用 prod。");
    }
}
