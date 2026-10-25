package com.ironoath.web.config;

import com.ironoath.common.time.TimeService;
import com.ironoath.web.security.InMemoryWeChatSessionKeyStore;
import com.ironoath.web.security.RedissonWeChatSessionKeyStore;
import com.ironoath.web.security.WeChatSessionKeyStore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Session keys stay server-side; production uses a shared encrypted Redis store. */
@Configuration
public class WeChatSessionKeyBeansConfig {
    @Bean
    @ConditionalOnProperty(name = "ironoath.lock", havingValue = "redisson")
    public WeChatSessionKeyStore redissonWeChatSessionKeyStore(
            RedissonClient redisson, TimeService time,
            @Value("${ironoath.wechat.session-key-encryption-key:}") String encryptionKey) {
        return new RedissonWeChatSessionKeyStore(redisson, time, encryptionKey);
    }

    @Bean
    @ConditionalOnProperty(name = "ironoath.lock", havingValue = "jvm", matchIfMissing = true)
    public WeChatSessionKeyStore inMemoryWeChatSessionKeyStore() {
        return new InMemoryWeChatSessionKeyStore();
    }
}
