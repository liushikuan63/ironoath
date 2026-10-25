package com.ironoath.web.security;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Dev/test store. It is process-local and deliberately never declares production readiness. */
public final class InMemoryWeChatSessionKeyStore implements WeChatSessionKeyStore {
    private record Entry(String sessionKey, long expiresAtMillis) {}

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public void put(String playerId, String sessionKey, long expiresAtMillis) {
        if (playerId == null || playerId.isBlank() || sessionKey == null || sessionKey.isBlank()
                || expiresAtMillis <= 0) {
            throw new IllegalArgumentException("微信会话密钥存储参数无效");
        }
        entries.put(playerId, new Entry(sessionKey, expiresAtMillis));
    }

    @Override
    public Optional<String> find(String playerId, long nowMillis) {
        if (playerId == null || playerId.isBlank() || nowMillis < 0) return Optional.empty();
        Entry entry = entries.get(playerId);
        if (entry == null) return Optional.empty();
        if (nowMillis >= entry.expiresAtMillis()) {
            entries.remove(playerId, entry);
            return Optional.empty();
        }
        return Optional.of(entry.sessionKey());
    }
}
