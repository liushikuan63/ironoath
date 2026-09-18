package com.ironoath.web.config;

import com.ironoath.web.ws.GameWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 职责：注册 WebSocket 端点。
 * 依赖：Spring WebSocket、GameWebSocketHandler。
 *
 * <p>路径 {@code /ws}。允许任意来源是因为微信小游戏的 Origin 头不稳定
 * （不同基础库版本可能不带或带非标准值），改用 Origin 白名单会在真机上随机连不上。
 * 安全性不靠 Origin，靠握手阶段的身份判定与随后的 bind（见下方"当前的安全边界"）。
 *
 * <p><b>当前的安全边界（2026-09-18 第②批之后）</b>：登录闭环 2026-09-13 落地（收口清单 #113），
 * 握手阶段由 {@code WebSocketAuthInterceptor} 判身份 —— 判不过就不建立连接，
 * 与 HTTP 边界、WS bind 共用同一个 {@code PlayerIdentityVerifier} 端口；
 * prod 下装的若是本地宽松实现会被 {@code ProductionReadiness} 拒绝启动。
 *
 * <p><b>为什么握手判得起</b>：客户端（{@code NetModule#socketUrl}）在<b>每一次</b>开连接时都把
 * {@code ?playerId=&token=} 带上了（第①批），所以"握手即拒"不会把弱网下"连上后稍后才 bind"的
 * 正常路径一起拦掉 —— 那个顾虑成立的前提是客户端不带凭据，而它现在带了。
 * 宽松实现（dev 与全部现有测试）在这里直接放行，一次都不调端口。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** WebSocket 路径。客户端 NetModule 用同一个常量拼接。 */
    public static final String WS_PATH = "/ws";

    private final GameWebSocketHandler handler;
    private final com.ironoath.web.ws.WebSocketAuthInterceptor auth;

    public WebSocketConfig(GameWebSocketHandler handler,
                           com.ironoath.web.ws.WebSocketAuthInterceptor auth) {
        this.handler = handler;
        this.auth = auth;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, WS_PATH).setAllowedOriginPatterns("*").addInterceptors(auth);
    }
}
