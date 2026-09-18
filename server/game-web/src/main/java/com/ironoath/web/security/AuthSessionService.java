package com.ironoath.web.security;

import com.ironoath.web.security.PlayerIdentityVerifier.Claim;
import com.ironoath.web.security.PlayerIdentityVerifier.Verdict;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 职责：会话票据的签发与校验（B15 §三 的可上线实现）。
 * 依赖：JDK（HmacSHA256）。
 *
 * <p><b>为什么是 HMAC 无状态票据而不是"服务端 session 表"</b>：多实例部署下，
 * 票据要么存进共享存储（多一份每次请求都要读的热数据），要么让实例本地存
 * （负载均衡一轮询就随机掉线）。而票据要携带的信息只有 {@code playerId + 过期时刻}，
 * 用密钥签名就能自证，重启服务不掉线、水平扩容无需同步。
 *
 * <p><b>票据格式</b>：{@code base64url(playerId).expiresAt.signature}。
 * 三段都用 URL 安全字符，可以直接放进 HTTP 头与 WebSocket 查询参数。
 * 签名覆盖前两段（含分隔符），任何一段被改动都会验签失败。
 *
 * <p><b>密钥从哪来</b>：生产必须由环境变量提供（见 {@code SecurityBeansConfig}）。
 * 开发环境用固定字符串，只为了本地能跑通；一旦有人拿开发密钥上生产，
 * 任何人都能伪造票据 —— 所以 {@code ProductionReadiness} 会检查密钥来源。
 */
public final class AuthSessionService {

    /** 票据有效期。微信侧 session_key 的失效由重新 wx.login 处理，与票据解耦。 */
    public static final long DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    private final byte[] secret;
    private final long ttlMs;

    public AuthSessionService(String secret) {
        this(secret, DEFAULT_TTL_MS);
    }

    public AuthSessionService(String secret, long ttlMs) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("会话票据密钥不得为空");
        }
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("票据有效期必须为正数：ttlMs=" + ttlMs);
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlMs = ttlMs;
    }

    /** 签发票据。调用方负责把 playerId 从存档里取出来（本类不认识玩家）。 */
    public String issue(String playerId, long now) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        long expiresAt = now + ttlMs;
        String payload = encode(playerId) + "." + expiresAt;
        return payload + "." + sign(payload);
    }

    /**
     * 校验票据。失败原因必须能直接给玩家看（"登录已过期，请重新进入游戏" 之类）。
     *
     * @param claim 请求声称的身份（playerId 来自 {@code X-Player-Id}；token 由
     *              {@code PlayerIdentityInterceptor} 取：优先标准头 {@code Authorization: Bearer}，
     *              回退到 {@code X-Auth-Token}。WS 的 bind 消息里也带同一枚，字段名 {@code token}）
     * @param now   当前服务端时间
     */
    public Verdict verify(Claim claim, long now) {
        String token = claim.token();
        if (token == null || token.isBlank()) {
            return Verdict.deny("缺少会话票据，请重新登录");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return Verdict.deny("会话票据格式不正确，请重新登录");
        }
        String payload = parts[0] + "." + parts[1];
        if (!constantTimeEquals(sign(payload), parts[2])) {
            return Verdict.deny("会话票据无效，请重新登录");
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return Verdict.deny("会话票据已损坏，请重新登录");
        }
        if (now > expiresAt) {
            return Verdict.deny("登录已过期，请重新进入游戏");
        }
        String playerId;
        try {
            playerId = decode(parts[0]);
        } catch (IllegalArgumentException e) {
            return Verdict.deny("会话票据已损坏，请重新登录");
        }
        // 票据本身证明了"这个人是谁"；与请求头不一致说明客户端在拿别人的 id 试探
        if (!playerId.equals(claim.playerId())) {
            return Verdict.deny("会话身份与请求身份不一致");
        }
        return Verdict.allow();
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // 算法是 JDK 必备；走到这里说明运行环境坏了，不能悄悄放过
            throw new IllegalStateException("签发会话票据失败", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
