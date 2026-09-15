package com.ironoath.web.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信内容安全实现的证据（B15 §3 / 上线检查清单 §二 7）。
 *
 * <p><b>为什么对着本机桩服务测</b>：这个客户端有三处只在"微信回了什么"上才有区别的分支
 * （{@code 87014} / {@code review} / token 失效），而它们恰好是三处会**静默退化成无人送检**的地方。
 * 连真实微信测它们既不可重复（要真凭据、要能构造违规词），也把"验过了"变成"碰巧是通的"；
 * 桩服务能把每一条分支单独摆出来。
 */
class WeChatContentSecurityClientTest {

    private HttpServer server;
    private String apiBase;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger checkCalls = new AtomicInteger();

    /** 送检接口这一档要回什么（每次用例自己改）。 */
    private volatile String checkResponse = "{\"errcode\":0,\"result\":{\"suggest\":\"pass\",\"label\":100}}";
    /** token 前 N 次回失效码，用来验"换一次再试"。 */
    private volatile int tokenFailuresLeft;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/token", exchange -> {
            tokenCalls.incrementAndGet();
            String tokenJson = "{\"access_token\":\"tok-" + tokenCalls.get() + "\",\"expires_in\":7200}";
            byte[] body = tokenJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/wxa/msg_sec_check", exchange -> {
            checkCalls.incrementAndGet();
            byte[] body = (tokenFailuresLeft-- > 0
                    ? "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}"
                    : checkResponse).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        apiBase = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private WeChatContentSecurityClient client() {
        return new WeChatContentSecurityClient("appid", "secret", apiBase);
    }

    @Test
    @DisplayName("suggest=pass 才叫通过；review 与 risky 都按违规处置（不把「需人工审核」当通过）")
    void onlyPassCountsAsAllowed() {
        WeChatContentSecurityClient c = client();

        checkResponse = "{\"errcode\":0,\"result\":{\"suggest\":\"pass\"}}";
        assertThat(c.check("openid-1", ContentSecurityClient.Scene.SOCIAL_LOG, "今天天气不错"))
                .isEqualTo(ContentSecurityClient.Verdict.ALLOWED);

        checkResponse = "{\"errcode\":0,\"result\":{\"suggest\":\"review\"}}";
        assertThat(c.check("openid-1", ContentSecurityClient.Scene.SOCIAL_LOG, "擦边内容"))
                .as("review 是「需人工审核」，把它当成通过等于把监管要求交给没人看的队列")
                .isEqualTo(ContentSecurityClient.Verdict.RISKY);

        checkResponse = "{\"errcode\":0,\"result\":{\"suggest\":\"risky\"}}";
        assertThat(c.check("openid-1", ContentSecurityClient.Scene.SOCIAL_LOG, "违规内容"))
                .isEqualTo(ContentSecurityClient.Verdict.RISKY);
    }

    @Test
    @DisplayName("87014 是内容违规，直接判 RISKY")
    void riskyErrcodeIsRisky() {
        checkResponse = "{\"errcode\":87014,\"errmsg\":\"risky content\"}";
        assertThat(client().check("openid-1", ContentSecurityClient.Scene.PROFILE, "违规昵称"))
                .isEqualTo(ContentSecurityClient.Verdict.RISKY);
    }

    @Test
    @DisplayName("token 失效换一次再试，不算「没问成」")
    void expiredTokenIsRefreshedOnce() {
        tokenFailuresLeft = 1; // 第一次送检回 40001
        checkResponse = "{\"errcode\":0,\"result\":{\"suggest\":\"pass\"}}";

        assertThat(client().check("openid-1", ContentSecurityClient.Scene.PROFILE, "正常昵称"))
                .as("只是 token 过期，不该表现成内容安全不可用")
                .isEqualTo(ContentSecurityClient.Verdict.ALLOWED);
        assertThat(tokenCalls.get()).as("换了一次新 token").isEqualTo(2);
    }

    @Test
    @DisplayName("access_token 有缓存：连送三次只换一次 token")
    void accessTokenIsCached() {
        WeChatContentSecurityClient c = client();
        c.check("openid-1", ContentSecurityClient.Scene.PROFILE, "a");
        c.check("openid-1", ContentSecurityClient.Scene.PROFILE, "b");
        c.check("openid-1", ContentSecurityClient.Scene.PROFILE, "c");

        assertThat(checkCalls.get()).isEqualTo(3);
        assertThat(tokenCalls.get())
                .as("每条内容都换一次 token 会很快撞上微信的频率限制，"
                        + "而那时的表现是所有内容都送检失败")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("问不成（连不上）判 UNAVAILABLE，不是判违规")
    void unreachableServiceIsUnavailableNotRisky() {
        WeChatContentSecurityClient c = client();
        stopStub(); // 关掉桩服务：连接被拒
        assertThat(c.check("openid-1", ContentSecurityClient.Scene.SOCIAL_LOG, "随便说点什么"))
                .as("把「没问成」判成违规会让一次外部故障变成全服封禁")
                .isEqualTo(ContentSecurityClient.Verdict.UNAVAILABLE);
    }

    @Test
    @DisplayName("没有 openid 就不送检：这是「这一档问不了」，不是违规")
    void blankOpenIdIsUnavailable() {
        assertThat(client().check(null, ContentSecurityClient.Scene.PROFILE, "昵称"))
                .isEqualTo(ContentSecurityClient.Verdict.UNAVAILABLE);
        assertThat(client().check("  ", ContentSecurityClient.Scene.PROFILE, "昵称"))
                .isEqualTo(ContentSecurityClient.Verdict.UNAVAILABLE);
        assertThat(checkCalls.get()).as("没送检就不该产生送检请求").isZero();
    }
}
