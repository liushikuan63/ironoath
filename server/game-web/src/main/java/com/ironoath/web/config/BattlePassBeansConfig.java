package com.ironoath.web.config;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.web.battlepass.BattlePassRules;
import com.ironoath.web.battlepass.BattlePassStore;
import com.ironoath.web.season.SeasonRulesAssembler;
import com.ironoath.web.store.memory.InMemoryBattlePassStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责：战令域的装配（存储端口 + 读表规则）。
 * 依赖：{@link GameProperties} 的存储开关、{@link InMemoryBattlePassStore}（内存实现）。
 *
 * <p>与其它域同一套约定：dev/test 零依赖启动，{@code ironoath.storage=mongo} 时换
 * {@code MongoBattlePassStore}（见 {@code MongoStoreConfig}），内存 bean 带
 * {@code @ConditionalOnProperty} —— 缺实现就该上下文起不来，不许静默退回内存版。
 */
@Configuration
public class BattlePassBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(BattlePassBeansConfig.class);

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage",
            havingValue = GameProperties.STORAGE_MEMORY, matchIfMissing = true)
    public BattlePassStore battlePassStore() {
        LOG.warn("使用内存战令进度：重启后「这一季打过的分」与「哪些档领过」就没了 —— "
                + "已领过的档位会重新变成可领，等于把奖励再发一遍。仅限本地开发与单测，"
                + "生产请设 ironoath.storage=mongo（该实现已存在，不用它属于配置遗漏而不是能力缺失）");
        return new InMemoryBattlePassStore();
    }

    @Bean
    public BattlePassRules battlePassRules(ConfigRegistry configs, SeasonRulesAssembler seasons) {
        return new BattlePassRules(configs, seasons);
    }
}
