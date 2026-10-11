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
 * <p>本地无法从一次性 code 得知真实微信身份。登录调用使用持久设备号维持同机存档，
 * 不模拟跨设备找回微信账号；正式实现仍由微信返回的 openid 决定身份。
 *
 * <p>{@link #productionReady()} 固定 false：prod 下本 bean 在场会让服务拒绝启动
 * （{@code ProductionReadiness}），避免"忘了配 AppSecret 就上线"。
 */
public final class LocalDevWeChatCodeExchanger implements WeChatCodeExchanger {

    private static final Logger LOG = LoggerFactory.getLogger(LocalDevWeChatCodeExchanger.class);

    public LocalDevWeChatCodeExchanger() {
        LOG.warn("微信登录使用本地开发实现：登录按持久设备号维持同机存档，不能验证跨设备微信身份，"
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

    @Override
    public Identity exchange(String code, String deviceId) {
        if (code == null || code.isBlank()) {
            throw new WeChatLoginException("缺少 wx.login 返回的 code");
        }
        if (deviceId == null || deviceId.isBlank()) {
            throw new WeChatLoginException("缺少本地持久设备号");
        }
        return new Identity("dev-device-" + sha256Hex(deviceId).substring(0, 24), null, "dev-session-key");
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
