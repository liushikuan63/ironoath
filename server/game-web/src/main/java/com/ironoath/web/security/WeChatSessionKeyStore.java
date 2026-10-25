package com.ironoath.web.security;

import java.util.Optional;

/**
 * Server-only storage for the current WeChat session key. The key must never enter a DTO,
 * client token, log message, or payment-order document.
 */
public interface WeChatSessionKeyStore {
    /** Replace the player's previous key after a successful wx.login exchange. */
    void put(String playerId, String sessionKey, long expiresAtMillis);

    /** Expired or missing keys are a normal no-payment state. */
    Optional<String> find(String playerId, long nowMillis);

    /** Only a shared, encrypted-at-rest implementation is suitable for production. */
    default boolean productionReady() {
        return false;
    }
}
