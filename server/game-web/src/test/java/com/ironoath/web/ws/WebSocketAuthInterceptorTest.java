package com.ironoath.web.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.ironoath.web.config.WebSocketConfig;
import com.ironoath.web.security.PlayerIdentityVerifier;

/**
 * 职责：WebSocket 握手阶段的身份闸门（上线检查清单 §二 1 第②批）。
 * 依赖：spring-test 的 mock 请求/响应，不起容器、不真连 socket。
 *
 * <p><b>本类要钉住的是两件事同时成立</b>：① 严格实现下无凭据的握手<b>真的被拒</b>
 * （一条匿名连接今天能建立、资源已分配、身份还没判 —— 那是这条闸门存在的唯一理由）；
 * ② 本地宽松实现下<b>一个请求都不往端口上发</b>（dev 与现有测试没有真票据，拦它等于把整个环境锁在门外，
 * 而"宽松实现直接放行"必须是端口的属性，不是这里另写一个 {@code if (dev)}）。
 * 只断 ② 的话，一个"永远 return true"的假闸门也能全类绿；只断 ① 的话，
 * 一个"永远 return false"的实现同样绿 —— 所以放行与拒绝都要有独立的那一条。
 */
class WebSocketAuthInterceptorTest {

    /** 记下来自握手的所有声称，用于断"公开/宽松路径根本没调端口"与"票据确实被送到了同一个端口"。 */
    private static final class StrictVerifier implements PlayerIdentityVerifier {
        private final java.util.List<Claim> seen = new java.util.ArrayList<>();

        @Override
        public Verdict verify(Claim claim) {
            seen.add(claim);
            return "good-token".equals(claim.token())
                    ? Verdict.allow() : Verdict.deny("会话票据无效或已过期");
        }

        @Override
        public boolean productionReady() {
            return true;
        }
    }

    /**
     * 与 {@code LocalDevIdentityVerifier} 同形（那个类是 final，这里只能自己复现一份）：
     * 什么都不拒，并且**不声明**自己能上生产 —— 走的是接口那个默认 false。
     */
    private static final class LenientVerifier implements PlayerIdentityVerifier {
        private int calls;

        @Override
        public Verdict verify(Claim claim) {
            calls++;
            return Verdict.allow();
        }
    }

    private static MockHttpServletRequest handshake(String queryString) {
        // 只设 queryString，不把 URI 写成 "/ws?..."：`ServletServerHttpRequest#getURI` 自己会把
        // requestURL 与 queryString 拼起来，这里再拼一遍会得到一个双份 query 的 URI
        MockHttpServletRequest request = new MockHttpServletRequest("GET", WebSocketConfig.WS_PATH);
        request.setQueryString(queryString);
        return request;
    }

    private static boolean before(WebSocketAuthInterceptor guard, String queryString,
                                  ServletServerHttpResponse response, Map<String, Object> attrs) {
        return guard.beforeHandshake(new ServletServerHttpRequest(handshake(queryString)), response,
                null, attrs);
    }

    @Test
    @DisplayName("本地宽松实现下放行且根本不调端口：闸门由实现自己声明能否用于生产，而不是这里再写一个 if (dev)")
    void lenientVerifierSkipsThePortEntirely() {
        LenientVerifier identity = new LenientVerifier();
        WebSocketAuthInterceptor guard = new WebSocketAuthInterceptor(identity);
        ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
        Map<String, Object> attrs = new HashMap<>();

        assertThat(before(guard, null, response, attrs)).isTrue();
        assertThat(before(guard, "playerId=P1&token=whatever", response, attrs)).isTrue();
        assertThat(identity.calls).as("宽松实现下一个声称都不该送过去").isZero();
    }

    @Test
    @DisplayName("严格实现下无凭据握手被拒：403 且不建立连接，而不是等 bind 才发现")
    void strictVerifierRejectsAnonymousHandshake() {
        StrictVerifier identity = new StrictVerifier();
        WebSocketAuthInterceptor guard = new WebSocketAuthInterceptor(identity);
        MockHttpServletResponse raw = new MockHttpServletResponse();
        ServletServerHttpResponse response = new ServletServerHttpResponse(raw);

        assertThat(before(guard, null, response, new HashMap<>())).isFalse();
        assertThat(response.getServletResponse().getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(identity.seen).as("被拒也要走同一个端口，理由由实现给").hasSize(1);
        assertThat(identity.seen.get(0).uri()).startsWith("ws:handshake");
        assertThat(identity.seen.get(0).playerId()).isNull();
    }

    @Test
    @DisplayName("拒绝理由与握手日志里都不许出现票据：它会跟着 access log、网关日志和客户端提示一起漏出去")
    void logDescriptionMasksTheTicket() {
        StrictVerifier identity = new StrictVerifier();
        WebSocketAuthInterceptor guard = new WebSocketAuthInterceptor(identity);
        ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());

        assertThat(before(guard, "playerId=P1&token=stolen-ticket", response, new HashMap<>())).isFalse();
        assertThat(WsCredentials.describeForLog(URI.create("ws://h/ws?playerId=P1&token=stolen-ticket")))
                .contains("playerId=P1")
                .doesNotContain("stolen-ticket");
    }

    @Test
    @DisplayName("严格实现下有票放行，并把握手的 playerId 存进会话属性：bind 只能报同一个人")
    void validTicketPassesAndIsRememberedForBind() {
        StrictVerifier identity = new StrictVerifier();
        WebSocketAuthInterceptor guard = new WebSocketAuthInterceptor(identity);
        ServletServerHttpResponse response = new ServletServerHttpResponse(new MockHttpServletResponse());
        Map<String, Object> attrs = new HashMap<>();

        assertThat(before(guard, "playerId=P1&token=good-token", response, attrs)).isTrue();
        assertThat(attrs).containsEntry(WsCredentials.ATTR_PLAYER_ID, "P1");
        assertThat(identity.seen).hasSize(1);
        assertThat(identity.seen.get(0).token()).isEqualTo("good-token");
    }

    @Test
    @DisplayName("query 解析：编码值、多余参数、缺参数各归各位，不能把 null 当成一个合法身份")
    void parsesTheQueryWithoutInventingIdentities() {
        assertThat(WsCredentials.playerIdOf(URI.create("ws://h/ws?playerId=P%3A1&token=t%2Bx")))
                .as("客户端 encodeURIComponent 出来的百分号编码必须还原")
                .isEqualTo("P:1");
        // 这里刻意不断言 "+" 会变空格：客户端用 encodeURIComponent，空格编成 %20，
        // 而 base64 形式的票据里的 "+" 会编成 %2B —— 按表单那套把 "+" 读成空格反而会把票据读错
        assertThat(WsCredentials.tokenOf(URI.create("ws://h/ws?playerId=P1&token=t%2Bx"))).isEqualTo("t+x");
        assertThat(WsCredentials.tokenOf(URI.create("ws://h/ws?playerId=P1&token=a%20b"))).isEqualTo("a b");
        assertThat(WsCredentials.playerIdOf(URI.create("ws://h/ws"))).isNull();
        assertThat(WsCredentials.tokenOf(URI.create("ws://h/ws?playerId=P1"))).isNull();
        // 空值参数（`playerId=`）不是身份：放行一个空字符串等于放行匿名
        assertThat(WsCredentials.playerIdOf(URI.create("ws://h/ws?playerId=&token="))).isNull();
    }

    @Test
    @DisplayName("bind 报的人必须就是握手报的那个人：否则门刚从握手收紧又从 bind 开回去")
    void bindMustMatchTheHandshakeIdentity() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(WsCredentials.ATTR_PLAYER_ID, "P1");

        assertThat(WsCredentials.bindsConsistentlyWith(attrs, "P1")).isTrue();
        assertThat(WsCredentials.bindsConsistentlyWith(attrs, "P2")).isFalse();
        // 本地宽松实现下握手没有凭据可解析（属性里压根没这个键）—— 此时不设约束，
        // 现有 dev 与测试流程照旧；但**属性里存在空值**仍要拒，那是被改写过的痕迹
        assertThat(WsCredentials.bindsConsistentlyWith(new HashMap<>(), "P9")).isTrue();
        assertThat(WsCredentials.bindsConsistentlyWith(Map.of(WsCredentials.ATTR_PLAYER_ID, ""), "P9")).isFalse();
    }
}
