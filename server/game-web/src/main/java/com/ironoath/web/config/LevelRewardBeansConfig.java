package com.ironoath.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ironoath.web.levelreward.LevelRewardClaimStore;
import com.ironoath.web.store.memory.InMemoryLevelRewardClaimStore;

/**
 * 职责：装配等级奖励域的存储端口（收口清单 #829）。
 * 依赖：Spring Boot、内存账本实现。
 *
 * <p>与其它存储同一套约定：dev/test 零依赖用内存版，{@code ironoath.storage=mongo} 时换
 * {@code MongoLevelRewardClaimStore}（见 {@code MongoStoreConfig}）。
 */
@Configuration
public class LevelRewardBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(LevelRewardBeansConfig.class);

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage",
            havingValue = GameProperties.STORAGE_MEMORY, matchIfMissing = true)
    public LevelRewardClaimStore levelRewardClaimStore() {
        LOG.warn("使用内存等级奖励账本：重启后「这一级领过了」就没了 —— 而已领是历史事实，"
                + "当前存档反推不出来（资源早被花掉），所以丢了的症状是同一级能再领一遍。"
                + "仅限本地开发与单测，生产请设 ironoath.storage=mongo");
        return new InMemoryLevelRewardClaimStore();
    }
}
