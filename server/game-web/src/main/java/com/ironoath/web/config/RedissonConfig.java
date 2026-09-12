package com.ironoath.web.config;

import com.ironoath.core.lock.PlayerLock;
import com.ironoath.web.lock.RedissonPlayerLock;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责：Redisson 装配 —— {@code ironoath.lock=redisson} 时生效（生产多实例部署）。
 * 依赖：Redisson、Spring Boot。
 *
 * <p>只在需要时创建客户端：dev/test 用 {@code ironoath.lock=jvm}（缺省），
 * 完全不连 Redis，保证零依赖启动与单测可在没有 Redis 的 CI 机器上跑通。
 */
@Configuration
@ConditionalOnProperty(name = "ironoath.lock", havingValue = "redisson")
public class RedissonConfig {

    private static final Logger LOG = LoggerFactory.getLogger(RedissonConfig.class);

    /**
     * Redisson 客户端。
     *
     * <p>连接池大小按「单实例并发处理请求数」估：每个玩家级锁占用一个连接的时间极短
     * （只在读-改-写期间持有），64 个连接足够支撑数千 QPS。调大只会增加 Redis 的连接开销。
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${ironoath.redis.address:redis://127.0.0.1:6379}") String address,
            @Value("${ironoath.redis.connection-pool-size:64}") int poolSize,
            @Value("${ironoath.redis.timeout-ms:3000}") int timeoutMs) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(address)
                .setConnectionPoolSize(poolSize)
                .setConnectionMinimumIdleSize(Math.min(8, poolSize))
                .setTimeout(timeoutMs)
                // 重试 2 次：Redis 抖动时给一次恢复机会，但不无限重试把请求线程拖死
                .setRetryAttempts(2)
                .setRetryInterval(200);
        LOG.info("初始化 Redisson 分布式锁客户端 address={} poolSize={}", address, poolSize);
        return Redisson.create(config);
    }

    @Bean
    public PlayerLock playerLock(RedissonClient redissonClient) {
        LOG.info("使用 Redisson 分布式玩家锁（多实例安全）");
        return new RedissonPlayerLock(redissonClient);
    }
}
