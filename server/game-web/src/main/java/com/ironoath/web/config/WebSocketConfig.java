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
 * 安全性由「连接后必须 bind 已鉴权的 playerId」保证，而不是靠 Origin。
 *
 * <p>TODO(需确认): 接入微信登录鉴权后（B15 商业化与合规批次会做实名与登录），
 * 应在握手阶段校验 token，未通过直接拒绝升级，而不是先连上再等 bind。
 * B01 阶段还没有鉴权体系，先保持通道可用。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** WebSocket 路径。客户端 NetModule 用同一个常量拼接。 */
    public static final String WS_PATH = "/ws";

    private final GameWebSocketHandler handler;

    public WebSocketConfig(GameWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, WS_PATH).setAllowedOriginPatterns("*");
    }
}
