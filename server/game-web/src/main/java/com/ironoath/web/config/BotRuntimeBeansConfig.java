package com.ironoath.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.redisson.api.RedissonClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.ironoath.core.bot.BotScheduler;
import com.ironoath.web.store.memory.InMemoryBotTaskQueue;
import com.ironoath.web.store.redis.RedissonBotTaskQueue;

/**
 * 职责：Bot运行时队列装配。dev/test内存；生产Redis ZSET与完整动作载荷。
 * 依赖：game-core的队列端口，复用已配置Redisson，不起Bot消费线程。
 *
 * <p><b>为什么队列做成 bean 而调度器不做</b>：调度器的两份规则（一轮预算、一轮条数）跟着配置表走，
 * 做成单例就等于把热更关掉 —— 那段推理写在 {@code BotRuntimeService} 的类注释里。
 * 而队列是<b>状态</b>（谁排在什么时刻），必须在重建之间存活，所以它是独立的一个 bean。
 *
 * <p>内存模式重启丢排期；Redis模式首次登记保留已有pending与dueAt，多实例原子领取。
 * 拉取即摘除，取出后崩溃不承诺重放；配置要求Redis而依赖缺失时启动失败，禁止静默降级。
 */
@Configuration
public class BotRuntimeBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(BotRuntimeBeansConfig.class);

    @Bean
    @ConditionalOnProperty(name="ironoath.bot.task-queue",havingValue="memory",matchIfMissing=true)
    public BotScheduler.TaskQueue botTaskQueue() {
        LOG.warn("Bot 待办队列使用内存实现：重启后排期全部重建（每个 Bot 的下一次 tick 被推后一个基准间隔），"
                + "多实例部署须启用ironoath.bot.task-queue=redis及ironoath.lock=redisson");
        return new InMemoryBotTaskQueue();
    }

    @Bean
    @ConditionalOnProperty(name="ironoath.bot.task-queue",havingValue="redis")
    public BotScheduler.TaskQueue redissonBotTaskQueue(RedissonClient client,ObjectMapper mapper,MongoTemplate mongo) {
        LOG.info("Bot待办使用Redis ZSET，命名空间取实际Mongo库；首次排期保留已恢复动作");
        return new RedissonBotTaskQueue(client,mapper,mongo.getDb().getName());
    }
}
