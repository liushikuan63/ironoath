package com.ironoath.web.config;

import com.ironoath.web.battle.BattleReportStore;
import com.ironoath.web.store.memory.InMemoryBattleReportStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责：装配战报存储。
 * 依赖：Spring Boot、game-web 的内存存储。
 *
 * <p>与 {@code WorldBeansConfig} 同一套约定：内存实现供 dev 与单测零依赖启动，
 * {@code ironoath.storage=mongo} 时换 {@code MongoBattleReportStore}（见 {@code MongoStoreConfig}）。
 * 内存 bean 因此带 {@code @ConditionalOnProperty}，与 #37 排掉 {@code @Primary} 那颗雷同一条理由：
 * <b>哪个模式缺实现就让上下文起不来</b>，不许静默退回内存版。
 *
 * <p>战报丢失的后果比资源丢失轻（不影响数值正确性），但玩家会失去
 * 「昨天那场是怎么输的」这条线索 —— 而复盘失败原因正是他变强的路径，
 * 也是 B05 交付 BattlePlayback 的全部意义。所以它不是「可以晚点做的存储」。
 */
@Configuration
public class BattleBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(BattleBeansConfig.class);

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage",
            havingValue = GameProperties.STORAGE_MEMORY, matchIfMissing = true)
    public BattleReportStore battleReportStore() {
        LOG.warn("使用内存战报存储：进程重启后全部战报丢失，客户端的 BattlePlayback 将无内容可放"
                + "（列表空了不会报错，只会被当成「这人没打过仗」）。"
                + "仅限本地开发与单测，生产请设 ironoath.storage=mongo（该实现已存在，"
                + "不用它属于配置遗漏而不是能力缺失）");
        return new InMemoryBattleReportStore();
    }
}
