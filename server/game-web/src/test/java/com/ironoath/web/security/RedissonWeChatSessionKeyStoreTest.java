package com.ironoath.web.security;

import com.ironoath.common.time.TimeService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Redis check; all keys are derived from this test's UUID and individually deleted. */
@Timeout(15)
class RedissonWeChatSessionKeyStoreTest {
    private static RedissonClient client;
    private static final long NOW = 1_700_000_000_000L;
    private final List<String> ownedKeys = new ArrayList<>();

    @BeforeAll
    static void openRedis() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(System.getProperty("ironoath.test.redis.address", "redis://127.0.0.1:6379"))
                .setConnectionPoolSize(4)
                .setConnectionMinimumIdleSize(1)
                .setConnectTimeout(1000)
                .setTimeout(1000)
                .setRetryAttempts(1);
        client = Redisson.create(config);
    }

    @AfterAll
    static void closeRedis() {
        if (client != null) client.shutdown();
    }

    @AfterEach
    void deleteOnlyOwnedKeys() {
        for (String key : ownedKeys) {
            assertThat(key).startsWith("ironoath:wechat-session:v1:");
            client.getBucket(key, StringCodec.INSTANCE).delete();
        }
        ownedKeys.clear();
    }

    @Test
    void sessionKeyIsEncryptedNamespacedAndReadableOnlyForItsPlayer() throws Exception {
        String playerId = "session-test-" + UUID.randomUUID();
        String redisKey = storageKey(playerId);
        ownedKeys.add(redisKey);
        String testKey = Base64.getEncoder().encodeToString(new byte[32]);
        var store = new RedissonWeChatSessionKeyStore(client, new TimeService(() -> NOW), testKey);

        store.put(playerId, "test-only-session-key", NOW + AuthSessionService.DEFAULT_TTL_MS);

        org.redisson.api.RBucket<String> bucket = client.getBucket(redisKey, StringCodec.INSTANCE);
        String stored = bucket.get();
        assertThat(store.productionReady()).isTrue();
        assertThat(stored).startsWith("v1.").doesNotContain("test-only-session-key");
        assertThat(bucket.remainTimeToLive()).isPositive();
        assertThat(store.find(playerId, NOW)).contains("test-only-session-key");
        assertThat(store.find("other-" + playerId, NOW)).isEmpty();
    }

    @Test
    void inMemoryStoreExpiresSessionKeysAtTheBoundedSessionDeadline() {
        var store = new InMemoryWeChatSessionKeyStore();
        store.put("player-session-test", "test-only-session-key", NOW + 10);

        assertThat(store.find("player-session-test", NOW)).contains("test-only-session-key");
        assertThat(store.find("player-session-test", NOW + 10)).isEmpty();
        assertThat(store.productionReady()).isFalse();
    }

    private static String storageKey(String playerId) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(playerId.getBytes(StandardCharsets.UTF_8));
        return "ironoath:wechat-session:v1:" + HexFormat.of().formatHex(hash);
    }
}
