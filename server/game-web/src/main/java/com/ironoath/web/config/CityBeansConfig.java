package com.ironoath.web.config;

import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.city.CityRepository;
import com.ironoath.core.limit.DailyCounter;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.web.limit.InMemoryDailyCounter;
import com.ironoath.web.limit.RedissonDailyCounter;
import com.ironoath.web.lock.InJvmPlayerLock;
import com.ironoath.web.store.memory.InMemoryCityStore;
import com.ironoath.web.store.memory.InMemoryInventoryStore;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责：城建相关基础设施的 Bean 装配（存储、玩家锁、日计数器），按部署形态切换实现。
 * 依赖：Spring Boot、game-core 的端口。
 *
 * <p>三个组件都有「单实例」与「多实例」两套实现，用 {@code ironoath.storage} / {@code ironoath.lock}
 * 切换。缺省一律走单实例实现，保证 dev 与单测零外部依赖 ——
 * 「跑一次测试要起三个容器」会让人干脆不跑测试。
 *
 * <p>⚠️ 单实例实现在水平扩容后<b>全部失效</b>：
 * <ul>
 *   <li>{@link InJvmPlayerLock} 跨进程不互斥 ⇒ 并发扣资源的漏洞复现</li>
 *   <li>{@link InMemoryDailyCounter} 每实例各算各的 ⇒ 「每日 5 次」变成 5 × 实例数 次</li>
 *   <li>{@link InMemoryCityStore} 进程重启即丢档</li>
 * </ul>
 * 生产必须同时设 {@code ironoath.storage=mongo} 与 {@code ironoath.lock=redisson}。
 * 两处启动日志都会打 WARN 提醒。
 */
@Configuration
public class CityBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(CityBeansConfig.class);

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage", havingValue = GameProperties.STORAGE_MEMORY,
            matchIfMissing = true)
    public CityRepository cityRepository() {
        LOG.warn("使用内存城建存储：进程重启后城内建筑与队列全部丢失，仅限本地开发与单测");
        return new InMemoryCityStore();
    }

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage", havingValue = GameProperties.STORAGE_MEMORY,
            matchIfMissing = true)
    public InventoryRepository inventoryRepository() {
        LOG.warn("使用内存背包存储：进程重启后道具全部丢失，仅限本地开发与单测");
        return new InMemoryInventoryStore();
    }

    @Bean
    @ConditionalOnProperty(name = "ironoath.lock", havingValue = "jvm", matchIfMissing = true)
    public PlayerLock playerLock() {
        LOG.warn("使用 JVM 内玩家锁：仅单实例有效。多实例部署必须设 ironoath.lock=redisson，"
                + "否则并发扣资源的防护会失效");
        return new InJvmPlayerLock();
    }

    @Bean
    @ConditionalOnProperty(name = "ironoath.lock", havingValue = "jvm", matchIfMissing = true)
    public DailyCounter dailyCounter() {
        LOG.warn("使用内存日计数器：多实例下「每日 N 次」会变成 N × 实例数 次");
        return new InMemoryDailyCounter();
    }

    /** 多实例下的日计数器。与 {@link RedissonConfig} 同开关联动，避免出现「锁是分布式的、计数是单机的」这种半吊子组合。 */
    @Bean
    @ConditionalOnProperty(name = "ironoath.lock", havingValue = "redisson")
    public DailyCounter redissonDailyCounter(RedissonClient redissonClient) {
        LOG.info("使用 Redis 日计数器（多实例安全）");
        return new RedissonDailyCounter(redissonClient);
    }
}
