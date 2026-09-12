package com.ironoath.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ironoath.core.bot.BotScheduler;
import com.ironoath.web.store.memory.InMemoryBotTaskQueue;

/**
 * 职责：Bot 运行时的装配（收口清单 §五 C1）—— 目前只有一件：待办队列。
 * 依赖：game-core 的端口 {@link BotScheduler.TaskQueue} 与其内存实现。
 *
 * <p><b>为什么队列做成 bean 而调度器不做</b>：调度器的两份规则（一轮预算、一轮条数）跟着配置表走，
 * 做成单例就等于把热更关掉 —— 那段推理写在 {@code BotRuntimeService} 的类注释里。
 * 而队列是<b>状态</b>（谁排在什么时刻），必须在重建之间存活，所以它是独立的一个 bean。
 *
 * <p><b>内存实现的代价（记录在案，不是注释里的一句客气话）</b>：重启后队列是空的，
 * 所有 Bot 的下一次 tick 由 {@code BotRuntimeService} 在新一轮里重新登记（推后一个基准间隔）。
 * 换 Redis / 落库的那一档与「多实例不重复调度」是同一笔债（#84 丙、B11 §三），
 * 现在留的口子就是这个端口本身 —— 换实现不需要动调度器一行。
 */
@Configuration
public class BotRuntimeBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(BotRuntimeBeansConfig.class);

    @Bean
    public BotScheduler.TaskQueue botTaskQueue() {
        LOG.warn("Bot 待办队列使用内存实现：重启后排期全部重建（每个 Bot 的下一次 tick 被推后一个基准间隔），"
                + "多实例部署会各跑各的 —— 换 Redis 的那一档见收口清单 #84 丙与 §五 C5");
        return new InMemoryBotTaskQueue();
    }
}
