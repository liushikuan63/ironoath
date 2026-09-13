package com.ironoath.web.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.web.controller.CityController;
import com.ironoath.web.security.LocalDevIdentityVerifier;
import com.ironoath.web.security.PlayerIdentityVerifier;

/**
 * 职责：身份校验拦截器的行为边界（B15 §三）。
 * 依赖：spring-test 的 mock 请求，不起容器。
 *
 * <p><b>本类最重要的一条是「公开路径不校验」与「严格实现真的会拦」必须同时成立</b>：
 * 前者保证登录前的几个端点不会被自己封死，后者保证这条闸门不是装饰。
 * 只断前者的话，一个"永远 return true"的假实现也能让全类绿。
 */
class PlayerIdentityInterceptorTest {

    /** 记下被校验过的声称，用于断「公开路径根本没有调端口」。 */
    private static final class StrictVerifier implements PlayerIdentityVerifier {
        private final List<Claim> seen = new ArrayList<>();

        @Override
        public Verdict verify(Claim claim) {
            seen.add(claim);
            return "good-token".equals(claim.token())
                    ? Verdict.allow() : Verdict.deny("会话票据无效");
        }

        @Override
        public boolean productionReady() {
            return true;
        }
    }

    @Test
    @DisplayName("本地宽松实现下不拦：开发与单测没有真票据，拦它等于把整个环境锁在门外")
    void lenientVerifierLetsEverythingThrough() {
        PlayerIdentityInterceptor guard = new PlayerIdentityInterceptor(new LocalDevIdentityVerifier());

        assertThatCode(() -> assertThat(guard.preHandle(request("/city/list", null),
                new MockHttpServletResponse(), new Object())).isTrue())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("严格实现下缺 X-Player-Id 直接拒：错误码 2006，而不是让 controller 自己发现")
    void strictVerifierRejectsAnonymousRequest() {
        PlayerIdentityInterceptor guard = new PlayerIdentityInterceptor(new StrictVerifier());

        assertThatThrownBy(() -> guard.preHandle(request("/city/list", null),
                new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PLAYER_IDENTITY_UNVERIFIED);
    }

    @Test
    @DisplayName("报了 playerId 但票据不对同样拒：报 id 不等于证明了自己是谁")
    void strictVerifierRejectsBadToken() {
        StrictVerifier verifier = new StrictVerifier();
        PlayerIdentityInterceptor guard = new PlayerIdentityInterceptor(verifier);

        assertThatThrownBy(() -> guard.preHandle(request("/bag/list", "P1"),
                new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("会话票据无效");
        assertThat(verifier.seen).as("确实把声称交给端口判过，不是拦截器自己拍死").hasSize(1);
        assertThat(verifier.seen.get(0).playerId()).isEqualTo("P1");
    }

    @Test
    @DisplayName("票据正确就放行，且带上 uri 供实现做更细的策略")
    void strictVerifierAllowsValidSession() {
        MockHttpServletRequest request = request("/bag/list", "P1");
        // 与客户端 NetModule 实际发的头一致：Authorization: Bearer <token>。
        // 曾经这里测的是自定义头 X-Auth-Token，于是两端头名不一致也全绿 ——
        // 那正是"本地永远发现不了的 2006"的来源，所以断言必须贴着真实客户端。
        request.addHeader("Authorization", "Bearer good-token");
        PlayerIdentityInterceptor guard = new PlayerIdentityInterceptor(new StrictVerifier());

        assertThat(guard.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    @DisplayName("自定义 X-Auth-Token 头仍被接受：早期联调脚本与灰度期客户端不用改")
    void legacyCustomTokenHeaderStillWorks() {
        MockHttpServletRequest request = request("/bag/list", "P1");
        request.addHeader(PlayerIdentityVerifier.TOKEN_HEADER, "good-token");
        PlayerIdentityInterceptor guard = new PlayerIdentityInterceptor(new StrictVerifier());

        assertThat(guard.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    @DisplayName("Authorization 不是 Bearer 前缀时按无票据处理：不把 Basic 之类当会话")
    void nonBearerAuthorizationIsNotASession() {
        MockHttpServletRequest request = request("/bag/list", "P1");
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        PlayerIdentityInterceptor guard = new PlayerIdentityInterceptor(new StrictVerifier());

        assertThatThrownBy(() -> guard.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("会话票据无效");
    }

    /**
     * 这几条路径在拿到会话之前就必须能调 —— 一旦拦了，表现是「客户端连登录都发不出去」，
     * 而那比漏洞更难排查（整个服看起来是活的，只是没人能进）。
     */
    @Test
    @DisplayName("公开路径清单：严格实现下也不校验，且根本不碰端口")
    void publicPathsBypassTheVerifier() {
        for (String uri : List.of("/time/sync", "/player/init", "/pay/callback",
                "/ops/track/batch", "/ops/crash", "/error")) {
            StrictVerifier verifier = new StrictVerifier();
            PlayerIdentityInterceptor guard = new PlayerIdentityInterceptor(verifier);

            assertThat(guard.preHandle(request(uri, null), new MockHttpServletResponse(), new Object()))
                    .as("公开路径 %s 必须放行", uri).isTrue();
            assertThat(verifier.seen).as("公开路径不该去问端口（它本来就没有身份可查）").isEmpty();
        }
    }

    @Test
    @DisplayName("新端点默认被覆盖：拦截器按 /** 登记，不靠每个 controller 记得加参数")
    void anyNewEndpointIsCovered() {
        PlayerIdentityInterceptor guard = new PlayerIdentityInterceptor(new StrictVerifier());

        assertThatThrownBy(() -> guard.preHandle(request("/brand/new/endpoint", null),
                new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.PLAYER_IDENTITY_UNVERIFIED);
    }

    private static MockHttpServletRequest request(String uri, String playerId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        if (playerId != null) {
            request.addHeader(CityController.PLAYER_HEADER, playerId);
        }
        return request;
    }
}
