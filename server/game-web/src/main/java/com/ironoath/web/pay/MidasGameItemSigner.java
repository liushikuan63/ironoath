package com.ironoath.web.pay;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironoath.core.pay.PayOrder;
import com.ironoath.web.security.WeChatSessionKeyStore;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Cipher;

/** Builds the signed, immutable GameItem raw string without exposing either signing key. */
@Service
public final class MidasGameItemSigner {
    private static final String PAY_SIG_PREFIX = "requestMidasPaymentGameItem&";
    private final Environment environment;
    private final WeChatSessionKeyStore sessionKeys;
    private final ObjectMapper json;

    public MidasGameItemSigner(Environment environment, WeChatSessionKeyStore sessionKeys, ObjectMapper json) {
        if (environment == null || sessionKeys == null || json == null)
            throw new IllegalArgumentException("GameItem signer dependencies are required");
        this.environment = environment;
        this.sessionKeys = sessionKeys;
        this.json = json;
    }

    public record Signed(String signData, String paySig, String signature) {}

    /**
     * Missing/expired session keys or merchant configuration produce no launchable parameters.
     * The linked MiniGame signature guide defines signature as HMAC(session_key, rawData); for the
     * GameItem API, signData is the payment raw string passed unchanged to the client.
     */
    public Optional<Signed> sign(String playerId, PayOrder.Line line, PayOrder.Quote quote, long now) {
        if (playerId == null || playerId.isBlank() || line == null || quote == null
                || !"goods".equals(quote.mode()) || quote.outTradeNo() == null) return Optional.empty();
        String appKey = appKeyFor(quote.env());
        String sessionKey = sessionKeys.find(playerId, now).orElse(null);
        if (appKey == null || appKey.isBlank() || sessionKey == null || sessionKey.isBlank())
            return Optional.empty();
        final int env;
        try {
            env = Integer.parseInt(quote.env());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (env != 0 && env != 1) return Optional.empty();

        GameItemSignData payload = new GameItemSignData("goods", quote.offerId(), line.count(), env,
                quote.currencyType(), quote.platform(), line.productId(),
                line.unitPriceCents(), quote.outTradeNo());
        final String signData;
        try {
            signData = json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("GameItem signData serialization failed", e);
        }
        return Optional.of(new Signed(signData,
                hmacHex(appKey, PAY_SIG_PREFIX + signData),
                // Official generic login-state signature: HMAC-SHA256(session_key, rawData).
                hmacHex(sessionKey, signData)));
    }

    /** Verify the official GameItem delivery event over its exact event and Payload strings. */
    public boolean verifyPayEventSig(String event, String payload, String signature, String env) {
        String appKey = appKeyFor(env);
        if (appKey == null || appKey.isBlank() || event == null || event.isBlank()
                || payload == null || signature == null || !signature.matches("[0-9a-f]{64}")) return false;
        String expected = hmacHex(appKey, event + "&" + payload);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    /** Tencent Superapp callback header: AES-ECB-PKCS padding of the exact TC-Timestamp string. */
    public boolean verifyPlatformCallbackSignature(String timestamp, String signature) {
        String secret = environment.getProperty("ironoath.pay.superapp-signature-secret", "");
        if (secret == null || !validAesKey(secret) || timestamp == null || timestamp.isBlank()
                || signature == null || !signature.matches("[0-9a-fA-F]+")) return false;
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "AES"));
            String expected = HexFormat.of().formatHex(cipher.doFinal(timestamp.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    signature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** The timestamp signature does not bind these required callback identity headers; pin them to the order environment. */
    public boolean verifyCallbackIdentity(String applicationId, String backendConfigId, String env) {
        String expectedApplicationId = trimToNull(environment.getProperty("ironoath.pay.superapp-application-id", ""));
        String expectedBackendConfigId = trimToNull(switch (env == null ? "" : env) {
            case "0" -> environment.getProperty("ironoath.pay.superapp-backend-config-id-prod", "");
            case "1" -> environment.getProperty("ironoath.pay.superapp-backend-config-id-sandbox", "");
            default -> null;
        });
        return expectedApplicationId != null && expectedBackendConfigId != null
                && expectedApplicationId.equals(applicationId)
                && expectedBackendConfigId.equals(backendConfigId);
    }

    /** True only when both merchant AppKeys and the encrypted shared session-key store are configured. */
    public boolean productionReady() {
        return appKeyFor("0") != null && !appKeyFor("0").isBlank()
                && appKeyFor("1") != null && !appKeyFor("1").isBlank()
                && sessionKeys.productionReady()
                && validAesKey(environment.getProperty("ironoath.pay.superapp-signature-secret", ""))
                && trimToNull(environment.getProperty("ironoath.pay.superapp-application-id", "")) != null
                && trimToNull(environment.getProperty("ironoath.pay.superapp-backend-config-id-prod", "")) != null
                && trimToNull(environment.getProperty("ironoath.pay.superapp-backend-config-id-sandbox", "")) != null;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean validAesKey(String secret) {
        if (secret == null) return false;
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        return bytes == 16 || bytes == 24 || bytes == 32;
    }

    private String appKeyFor(String env) {
        return switch (env == null ? "" : env) {
            case "0" -> environment.getProperty("ironoath.pay.app-key-prod", "");
            case "1" -> environment.getProperty("ironoath.pay.app-key-sandbox", "");
            default -> null;
        };
    }

    private static String hmacHex(String key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK HmacSHA256 is unavailable", e);
        }
    }

    @JsonPropertyOrder({"mode", "offerId", "buyQuantity", "env", "currencyType", "platform",
            "productId", "goodsPrice", "outTradeNo"})
    private record GameItemSignData(String mode, String offerId, int buyQuantity, int env,
                                    String currencyType, String platform, String productId,
                                    long goodsPrice, String outTradeNo) {}
}
