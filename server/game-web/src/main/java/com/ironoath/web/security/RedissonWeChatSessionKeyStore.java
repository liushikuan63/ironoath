package com.ironoath.web.security;

import com.ironoath.common.time.TimeService;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/** Shared Redis-backed session keys, encrypted with a dedicated deployment key before storage. */
public final class RedissonWeChatSessionKeyStore implements WeChatSessionKeyStore {
    private static final Logger LOG = LoggerFactory.getLogger(RedissonWeChatSessionKeyStore.class);
    private static final String PREFIX = "ironoath:wechat-session:v1:";
    private static final int NONCE_BYTES = 12;
    private final RedissonClient redisson;
    private final TimeService time;
    private final SecureRandom random = new SecureRandom();
    private final byte[] encryptionKey;

    public RedissonWeChatSessionKeyStore(RedissonClient redisson, TimeService time, String encodedKey) {
        if (redisson == null || time == null) throw new IllegalArgumentException("Redis 与时间服务不得为空");
        this.redisson = redisson;
        this.time = time;
        this.encryptionKey = decodeKey(encodedKey);
        if (this.encryptionKey == null) {
            LOG.warn("微信 session_key 加密密钥缺失或不是32字节Base64值；支付签名存储不可用于生产");
        }
    }

    @Override
    public void put(String playerId, String sessionKey, long expiresAtMillis) {
        String storageKey = storageKey(playerId);
        long ttlMillis = expiresAtMillis - time.serverNow();
        if (sessionKey == null || sessionKey.isBlank() || ttlMillis <= 0) {
            throw new IllegalArgumentException("微信 session_key 或有效期无效");
        }
        requireKey();
        String encrypted = encrypt(storageKey, sessionKey);
        redisson.getBucket(storageKey, StringCodec.INSTANCE).set(encrypted, Duration.ofMillis(ttlMillis));
    }

    @Override
    public Optional<String> find(String playerId, long nowMillis) {
        if (playerId == null || playerId.isBlank() || nowMillis < 0 || !productionReady()) return Optional.empty();
        String storageKey = storageKey(playerId);
        RBucket<String> bucket = redisson.getBucket(storageKey, StringCodec.INSTANCE);
        String encrypted = bucket.get();
        if (encrypted == null || encrypted.isBlank()) return Optional.empty();
        try {
            return Optional.of(decrypt(storageKey, encrypted));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            LOG.warn("微信 session_key 密文无法解开，将要求玩家重新登录");
            bucket.delete();
            return Optional.empty();
        }
    }

    @Override
    public boolean productionReady() {
        return encryptionKey != null;
    }

    private String encrypt(String storageKey, String sessionKey) {
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(storageKey.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(sessionKey.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
            return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("微信 session_key 加密失败", e);
        }
    }

    private String decrypt(String storageKey, String encrypted) throws GeneralSecurityException {
        requireKey();
        if (!encrypted.startsWith("v1.")) throw new IllegalArgumentException("未知 session_key 密文版本");
        byte[] payload = Base64.getUrlDecoder().decode(encrypted.substring(3));
        if (payload.length <= NONCE_BYTES + 16) throw new IllegalArgumentException("session_key 密文长度无效");
        byte[] nonce = Arrays.copyOfRange(payload, 0, NONCE_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(payload, NONCE_BYTES, payload.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(storageKey.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private static byte[] decodeKey(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            return decoded.length == 32 ? decoded : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void requireKey() {
        if (!productionReady()) throw new IllegalStateException("微信 session_key 加密配置未就绪");
    }

    private static String storageKey(String playerId) {
        if (playerId == null || playerId.isBlank()) throw new IllegalArgumentException("playerId 不得为空");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(playerId.getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}
