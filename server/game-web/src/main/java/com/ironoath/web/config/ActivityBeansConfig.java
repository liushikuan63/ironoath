package com.ironoath.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ironoath.web.activity.ActivityProgressStore;
import com.ironoath.web.store.memory.InMemoryActivityProgressStore;

/**
 * 职责：装配活动系统的存储（dev/test 用内存版；mongo 版在 {@code MongoStoreConfig}）。
 * 依赖：Spring Boot、内存实现。
 *
 * <p><b>活动进度是"丢了就补不回来"的数据</b>：连续签到与"这轮打了 30 只怪"都无法从当前状态反推
 * （日子已经过去、怪已经死了），所以内存版只在本地开发与单测里用 —— 生产必须 {@code ironoath.storage=mongo}。
 * 这与任务进度、抽卡保底是同一档（本仓库把"重启即空"的实现都配了这条告警）。
 *
 * <p><b>事件监听器（{@code ActivityEventListener}）不在这里 new</b>：它是 {@code @Component}，
 * 构造时按 {@code ActivityCondition} 逐个订阅总线 —— 那份清单里少一类，只有一处会知道
 * （而这里 new 一个的话，测试里替换实现要连着搬这份清单）。
 */
@Configuration
public class ActivityBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ActivityBeansConfig.class);

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage",
            havingValue = GameProperties.STORAGE_MEMORY, matchIfMissing = true)
    public ActivityProgressStore activityProgressStore() {
        LOG.warn("使用内存活动进度：重启后连续签到天数与活动进度全部归零 —— "
                + "而它们无法从当前状态反推（日子过去了就补不回来）。"
                + "仅限本地开发与单测，生产请设 ironoath.storage=mongo");
        return new InMemoryActivityProgressStore();
    }
}
