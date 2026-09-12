package com.ironoath.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ironoath.core.event.GameEventBus;
import com.ironoath.web.quest.QuestProgressStore;
import com.ironoath.web.store.memory.InMemoryQuestProgressStore;

/**
 * 职责：装配任务系统的基础件（事件总线 + 进度账本）。
 * 依赖：Spring Boot、game-core 的总线、内存账本。
 *
 * <p><b>事件总线是 game-core 的纯 Java 类，所以在这里手工 new 一个单例</b>：
 * 它刻意不依赖 Spring（B12 验收 7「模拟 100 个事件，进度累加正确无遗漏」要在不带容器的单测里跑），
 * 而生产里它必须是<b>同一个实例</b> —— 各 new 一个的话，发布方与订阅方会连到两条总线上，
 * 表现为「事件发出去了、任务进度一动不动」，且没有任何报错。
 *
 * <p>与其它存储同一套约定：dev/test 零依赖用内存版，{@code ironoath.storage=mongo} 时换
 * {@code MongoQuestProgressStore}（见 {@code MongoStoreConfig}）。
 */
@Configuration
public class QuestBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(QuestBeansConfig.class);

    /** 事件总线：全进程唯一。见类注释。 */
    @Bean
    public GameEventBus gameEventBus() {
        return new GameEventBus();
    }

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage",
            havingValue = GameProperties.STORAGE_MEMORY, matchIfMissing = true)
    public QuestProgressStore questProgressStore() {
        LOG.warn("使用内存任务进度：重启后「累计训练 20 个兵」这类进度就没了 —— "
                + "而累加型进度无法从当前状态反推（兵可能已经战死），所以它是丢了就补不回来的数据。"
                + "仅限本地开发与单测，生产请设 ironoath.storage=mongo");
        return new InMemoryQuestProgressStore();
    }
}
