package com.ironoath.web.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 职责：本地开发与单测用的 code 兑换器 —— 把 code 确定性地映射成一个 openid，不调微信。
 * 依赖：JDK（MessageDigest）。
 *
 * <p><b>确定性是它的全部意义</b>：同一个 code 永远得到同一个 openid，
 * 于是「同一个微信用户第二次登录拿到同一份存档」这条链路在本地就能被端到端验证，
 * 而这一点恰恰是接真实现时最容易写错的（把 code 当账号键，而 code 一次性）。
 *
 * <p>{@link #productionReady()} 固定 false：prod 下本 bean 在场会让服务拒绝启动
 * （{@code ProductionReadiness}），避免"忘了配 AppSecret 就上线"。
 */
public final class LocalDevWeChatCodeExchanger implements WeChatCodeExchanger {

    private static final Logger LOG = LoggerFactory.getLogger(LocalDevWeChatCodeExchanger.class);

    public LocalDevWeChatCodeExchanger() {
        LOG.warn("微信登录使用本地开发实现：任何 code 都会被映射成一个稳定的 openid，"
                + "不会真的调用微信服务器。上线前必须配置 WECHAT_APP_ID / WECHAT_APP_SECRET "
                + "并切到 WeChatSessionCodeExchanger（prod 下本实现会让服务拒绝启动）");
    }

    @Override
    public Identity exchange(String code) {
        if (code == null || code.isBlank()) {
            throw new WeChatLoginException("缺少 wx.login 返回的 code");
        }
        String openId = "dev-openid-" + sha256Hex(code).substring(0, 24);
        // sessionKey 在本地没有意义；给一个固定值以保持字段非空，真实实现才填微信返回值
        return new Identity(openId, null, "dev-session-key");
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是所有 JDK 必须提供的算法；走到这里说明运行环境本身坏了
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
