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
 * <p><b>当前的安全边界（2026-09-18 现跑核对，原文那句「B01 阶段还没有鉴权体系」已过期）</b>：
 * 登录闭环 2026-09-13 就落地了（收口清单 #113），WS 这一侧也确实在校验身份 ——
 * {@code GameWebSocketHandler} 在 bind 时调 {@code PlayerIdentityVerifier.verify(...)}，
 * 与 HTTP 边界同一个端口，prod 下装的若是本地宽松实现会被 {@code ProductionReadiness} 拒绝启动。
 *
 * <p><b>仍然没做的那一步</b>：握手阶段不校验 token，所以一条<b>匿名连接</b>今天是可以建立的
 * （要等到 bind 才被拒）。要收紧就是在 {@code WebSocketHandlerDecorator} 的 afterConnectionEstablished
 * 之前判掉并直接 close。代价与收益都要先量：小游戏弱网下重连频繁，握手即拒会把"连上后稍后才 bind"
 * 的正常路径也一起拦掉 —— 所以这一条留在上线检查清单 §二 1 的待办里，不在这里顺手改。
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
