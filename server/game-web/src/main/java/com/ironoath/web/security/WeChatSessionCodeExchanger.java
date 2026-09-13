package com.ironoath.web.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 职责：真实微信登录实现 —— 调 {@code https://api.weixin.qq.com/sns/jscode2session} 换 openid。
 * 依赖：JDK HttpClient、Jackson（只解析微信返回的 JSON）。
 *
 * <p><b>这个类只能由配置装配</b>（见 {@code SecurityBeansConfig}）：没有 AppID/AppSecret 时
 * 构造器直接抛异常，于是"配置缺失"是启动失败而不是运行时每个玩家都登不进去。
 *
 * <p><b>微信接口的两种失败长什么样</b>（都必须在日志里留下能定位的原始信息）：
 * <ul>
 *   <li>HTTP 非 200：网络/网关问题，可重试；</li>
 *   <li>HTTP 200 但 JSON 带 {@code errcode}：业务失败，最常见的是
 *       {@code 40029 invalid code}（客户端旧 code 重放）与 {@code 40163 code been used}
 *       （同一 code 换了两次）——这两种都要让客户端重新 {@code wx.login}，不是服务端故障。</li>
 * </ul>
 * 因此对外统一抛 {@link WeChatLoginException}，由客户端提示"请重试登录"。
 */
public final class WeChatSessionCodeExchanger implements WeChatCodeExchanger {

    private static final Logger LOG = LoggerFactory.getLogger(WeChatSessionCodeExchanger.class);
    private static final String ENDPOINT = "https://api.weixin.qq.com/sns/jscode2session";

    private final String appId;
    private final String appSecret;
    private final HttpClient http;
    private final ObjectMapper json;

    public WeChatSessionCodeExchanger(String appId, String appSecret) {
        this(appId, appSecret, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build(), new ObjectMapper());
    }

    WeChatSessionCodeExchanger(String appId, String appSecret, HttpClient http, ObjectMapper json) {
        if (appId == null || appId.isBlank()) {
            throw new IllegalStateException("微信登录缺少 WECHAT_APP_ID");
        }
        if (appSecret == null || appSecret.isBlank()) {
            throw new IllegalStateException("微信登录缺少 WECHAT_APP_SECRET");
        }
        this.appId = appId;
        this.appSecret = appSecret;
        this.http = http;
        this.json = json;
    }

    @Override
    public Identity exchange(String code) {
        if (code == null || code.isBlank()) {
            throw new WeChatLoginException("缺少 wx.login 返回的 code");
        }
        String url = ENDPOINT
                + "?appid=" + encode(appId)
                + "&secret=" + encode(appSecret)
                + "&js_code=" + encode(code)
                + "&grant_type=authorization_code";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WeChatLoginException("调用微信登录接口被中断");
        } catch (Exception e) {
            LOG.warn("调用微信登录接口失败", e);
            throw new WeChatLoginException("微信登录服务暂时不可用");
        }
        if (response.statusCode() != 200) {
            LOG.warn("微信登录接口返回非 200：status={}", response.statusCode());
            throw new WeChatLoginException("微信登录服务返回异常状态：" + response.statusCode());
        }
        JsonNode body;
        try {
            body = json.readTree(response.body());
        } catch (Exception e) {
            LOG.warn("微信登录接口返回了无法解析的响应", e);
            throw new WeChatLoginException("微信登录响应无法解析");
        }
        int errCode = body.path("errcode").asInt(0);
        if (errCode != 0) {
            // errmsg 会带 "invalid code" / "code been used" 这类可定位信息，照原样记日志
            LOG.warn("微信登录失败：errcode={} errmsg={}", errCode, body.path("errmsg").asText(""));
            throw new WeChatLoginException("微信登录失败（" + errCode + "）");
        }
        String openId = body.path("openid").asText("");
        if (openId.isBlank()) {
            throw new WeChatLoginException("微信登录响应缺少 openid");
        }
        String unionId = body.path("unionid").isMissingNode() || body.path("unionid").isNull()
                ? null : body.path("unionid").asText(null);
        String sessionKey = body.path("session_key").asText("");
        return new Identity(openId, unionId, sessionKey);
    }

    @Override
    public boolean productionReady() {
        return true;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
