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
 * 职责：真实内容安全实现 —— 调微信 {@code msg_sec_check} 送检（B15 §3 / 上线检查清单 §二 7）。
 * 依赖：JDK HttpClient、Jackson（只解析微信返回的 JSON）。
 *
 * <p><b>只能由配置装配</b>（见 {@code SecurityBeansConfig}）：没有 AppID/AppSecret 时构造器直接抛异常，
 * 于是"配置缺失"是启动失败而不是运行时全服内容无人送检。
 *
 * <p><b>三处容易被做成假绿的地方</b>：
 * <ol>
 *   <li><b>access_token 必须缓存</b>：微信对换 token 有频率限制，每条消息换一次会很快撞上，
 *       而那时的表现是"所有内容都送检失败"（退化成无人送检），不是报错；</li>
 *   <li><b>token 失效要重试一次而不是当成内容问题</b>：{@code 40001}/{@code 42001} 说明 token 过期
 *       （多实例各持一份 token 时很常见），重换一次即可；把它算进"没问成"会让内容安全在一次抖动后
 *       长时间空转；</li>
 *   <li><b>{@code review} 不是 {@code pass}</b>：微信给 {@code result.suggest} 三档，把"需人工审核"
 *       当成通过等于把监管要求交给"没人会去看的队列"，所以这里只有 {@code pass} 算通过。</li>
 * </ol>
 *
 * <p>{@code apiBase} 是构造参数而不是常量：单测要对着本机的一个桩服务验上面那三条，
 * 写死常量就只能靠连真实微信来验 —— 那既不可重复，也会把"验过了"变成"碰巧是通的"。
 */
public final class WeChatContentSecurityClient implements ContentSecurityClient {

    private static final Logger LOG = LoggerFactory.getLogger(WeChatContentSecurityClient.class);

    /** 微信侧的默认入口；单测传本机桩地址。 */
    public static final String DEFAULT_API_BASE = "https://api.weixin.qq.com";

    /** 判定内容违规的错误码（微信原文：risky content）。 */
    private static final int ERR_RISKY = 87014;
    /** token 失效的两个错误码：前者通用、后者是 access_token 过期。 */
    private static final int ERR_INVALID_TOKEN = 40001;
    private static final int ERR_TOKEN_EXPIRED = 42001;
    /** 提前 5 分钟判过期：卡着 expires_in 用，等于每次都在赌两边的时钟一致。 */
    private static final long TOKEN_EARLY_REFRESH_MILLIS = 5 * 60 * 1000L;

    private final String appId;
    private final String appSecret;
    private final String apiBase;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    private String accessToken;
    private long accessTokenExpireAt;

    public WeChatContentSecurityClient(String appId, String appSecret) {
        this(appId, appSecret, DEFAULT_API_BASE);
    }

    public WeChatContentSecurityClient(String appId, String appSecret, String apiBase) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalStateException(
                    "内容安全需要 WECHAT_APP_ID / WECHAT_APP_SECRET，缺任意一项都不能构造真实实现");
        }
        this.appId = appId;
        this.appSecret = appSecret;
        this.apiBase = apiBase;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public boolean productionReady() {
        return true;
    }

    @Override
    public Verdict check(String openId, Scene scene, String content) {
        if (openId == null || openId.isBlank()) {
            // 没有 openid 就没有送检对象。这不是错误，是"这一档问不了" —— 由调用方决定怎么处置。
            return Verdict.UNAVAILABLE;
        }
        try {
            Verdict verdict = send(openId, scene, content, token(false));
            if (verdict != null) {
                return verdict;
            }
            // token 失效：换一次再试。这只是"token 过期"而不是内容问题，
            // 把它算进"没问成"会让内容安全在一次抖动之后长时间空转。
            verdict = send(openId, scene, content, token(true));
            return verdict == null ? Verdict.UNAVAILABLE : verdict;
        } catch (Exception e) {
            LOG.warn("内容送检失败（不是内容问题，是没问成）：scene={} err={}", scene, e.toString());
            return Verdict.UNAVAILABLE;
        }
    }

    /**
     * 送检一次。
     *
     * @return 结论；{@code null} 表示 token 失效、调用方该换 token 重试
     */
    private Verdict send(String openId, Scene scene, String content, String token) throws Exception {
        String body = mapper.createObjectNode()
                .put("openid", openId)
                .put("scene", scene.code())
                .put("version", 2)
                .put("content", content == null ? "" : content)
                .toString();
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(apiBase + "/wxa/msg_sec_check?access_token="
                                + URLEncoder.encode(token, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json = mapper.readTree(resp.body());
        int errcode = json.path("errcode").asInt(-1);
        if (errcode == ERR_INVALID_TOKEN || errcode == ERR_TOKEN_EXPIRED) {
            return null;
        }
        if (errcode == ERR_RISKY) {
            return Verdict.RISKY;
        }
        if (errcode != 0) {
            LOG.warn("内容送检返回非内容类错误码 {}：{}", errcode, json.path("errmsg").asText(""));
            return Verdict.UNAVAILABLE;
        }
        // errcode=0 时看 result.suggest：只有 pass 算通过，review 与 risky 都按违规处置
        String suggest = json.path("result").path("suggest").asText("");
        return "pass".equals(suggest) ? Verdict.ALLOWED : Verdict.RISKY;
    }

    /** 取 access_token（带缓存）。{@code force} 为真时强制重换。 */
    private synchronized String token(boolean force) throws Exception {
        long now = System.currentTimeMillis();
        if (!force && accessToken != null && now < accessTokenExpireAt) {
            return accessToken;
        }
        String url = apiBase + "/cgi-bin/token?grant_type=client_credential&appid="
                + URLEncoder.encode(appId, StandardCharsets.UTF_8) + "&secret="
                + URLEncoder.encode(appSecret, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json = mapper.readTree(resp.body());
        String token = json.path("access_token").asText("");
        if (token.isEmpty()) {
            throw new IllegalStateException("换 access_token 失败：errcode=" + json.path("errcode").asInt()
                    + " errmsg=" + json.path("errmsg").asText(""));
        }
        long expiresInSeconds = json.path("expires_in").asLong(7200L);
        this.accessToken = token;
        this.accessTokenExpireAt = now + expiresInSeconds * 1000L - TOKEN_EARLY_REFRESH_MILLIS;
        return token;
    }
}
