package com.ironoath.web.store.memory;

import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.config.GameProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责：内存存储装配 —— {@code ironoath.storage=memory} 时生效（缺省即此，便于零依赖启动与单测）。
 * 依赖：Spring Boot。
 */
@Configuration
@ConditionalOnProperty(name = "ironoath.storage", havingValue = GameProperties.STORAGE_MEMORY,
        matchIfMissing = true)
public class MemoryStoreConfig {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryStoreConfig.class);

    @Bean
    public PlayerRepository playerRepository() {
        LOG.warn("使用内存玩家存储：进程重启后存档全部丢失，仅限本地开发与单测。生产请设 ironoath.storage=mongo");
        return new InMemoryPlayerStore();
    }

    @Bean
    public IdempotencyStore idempotencyStore() {
        return new InMemoryIdempotencyStore();
    }
}
