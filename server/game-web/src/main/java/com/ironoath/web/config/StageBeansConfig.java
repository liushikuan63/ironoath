package com.ironoath.web.config;

import com.ironoath.core.stage.StageProgressRepository;
import com.ironoath.web.store.memory.InMemoryStageProgressStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责：装配章节副本进度存储。
 * 依赖：Spring Boot、game-web 的内存存储。
 *
 * <p>与其它内存实现同一套约定：dev/test 零依赖启动，{@code ironoath.storage=mongo} 时
 * 换 {@code MongoStageProgressStore}（见 {@code MongoStoreConfig}）。
 *
 * <p><b>进度丢失的后果比资源丢失更严重</b>：资源和兵力可以再攒，
 * 而「我已经三星通关了第 3 章」是玩家花了几小时换来的既成事实。
 * 丢了它，玩家会认为自己的时间被清零 —— 这是最容易让人直接卸载的一类事故。
 * 更硬的一条在 {@code StageAppService}：首通奖发不发，唯一判据就是这份进度里的
 * {@code cleared}，所以重启一次等于全服首通奖重新发一遍 —— 那是经济口子，不只是客诉。
 *
 * <p><b>刻意没有 {@code @Primary}</b>：同 {@code HeroBeansConfig} 那处被排掉的雷 ——
 * 内存 bean 挂着 @Primary 时，mongo 模式下 Spring 仍会挑它，
 * 表现是"日志说在用 mongo，但进度照样随进程消失"。改成条件装配之后，
 * <b>哪个模式缺实现就让上下文起不来</b>，不会静默退回内存版。
 */
@Configuration
public class StageBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(StageBeansConfig.class);

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage",
            havingValue = GameProperties.STORAGE_MEMORY, matchIfMissing = true)
    public StageProgressRepository stageProgressRepository() {
        LOG.warn("使用内存关卡进度存储：进程重启后全部通关记录与星级丢失，"
                + "而首通奖励的判据就在这份进度里，重启一次等于全服重发一遍首通奖。"
                + "仅限本地开发与单测，生产请设 ironoath.storage=mongo（该实现已存在，"
                + "不用它属于配置遗漏而不是能力缺失）");
        return new InMemoryStageProgressStore();
    }
}
