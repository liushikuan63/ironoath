package com.ironoath.web.ws;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import com.ironoath.web.security.PlayerIdentityVerifier;

/**
 * 职责：在 WebSocket 握手阶段判身份 —— 判不过就不建立连接（上线检查清单 §二 1 第②批）。
 * 依赖：{@link PlayerIdentityVerifier}（与 HTTP 边界同一个端口）、{@link WsCredentials}。
 *
 * <p><b>补的是哪个洞</b>：原来 WS 是"先允许连接、等客户端发 {@code bind} 时才验身份"，
 * 于是<b>一条匿名连接可以建立起来</b>：资源已经分配、心跳已经开始、身份还没判。
 * HTTP 侧每一条请求都过 {@code PlayerIdentityInterceptor}，这条通道是整套鉴权里唯一的例外。
 *
 * <p><b>为什么判"能不能上生产"而不是判"profile"</b>：与支付验签、{@code ProductionReadiness} 同一个理由 ——
 * {@code if (dev)} 会随配置漂移，而端口版本要求正式环境必须有一个真实现，
 * 否则服务直接起不来。所以本地宽松实现在这里<b>连一次 {@code verify} 都不调用</b>
 * （dev 与现有测试没有真票据，拦它等于把整个环境锁在门外）。
 *
 * <p><b>放行不等于放手</b>：握手解析出的 playerId 会存进会话属性，
 * {@code GameWebSocketHandler#bind} 用它核对"bind 报的人就是握手报的人"。
 * 少了这一步，攻击者只要握自己的手、再 bind 别人的 id，就能领走那个人的定向推送。
 *
 * <p><b>客户端这一侧的前提</b>（第①批，已落地）：{@code NetModule#socketUrl} 每次开连接现算 URL，
 * 首连与重连都带 {@code ?playerId=&token=}。两批的顺序不可反 ——
 * 服务端单独上线会把 prod 的长连接全断，客户端单独上线则无害（服务端此刻还不读 query）。
 */
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketAuthInterceptor.class);

    /** 送给校验端口的连接标识前缀，让实现能区分"握手"与"bind"两次声称。 */
    static final String HANDSHAKE_URI = "ws:handshake";

    private final PlayerIdentityVerifier identity;

    public WebSocketAuthInterceptor(PlayerIdentityVerifier identity) {
        this.identity = identity;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String playerId = WsCredentials.playerIdOf(request.getURI());
        if (playerId != null) {
            attributes.put(WsCredentials.ATTR_PLAYER_ID, playerId);
        }
        if (!identity.productionReady()) {
            return true;
        }
        PlayerIdentityVerifier.Verdict verdict = identity.verify(new PlayerIdentityVerifier.Claim(
                playerId, WsCredentials.tokenOf(request.getURI()), HANDSHAKE_URI));
        if (!verdict.allowed()) {
            // 只记掩码后的握手描述：URL 里那枚票据能顶替玩家身份，进日志就等于把它抄给所有看日志的人
            LOG.warn("WebSocket 握手被拒 {} 原因={}", WsCredentials.describeForLog(request.getURI()), verdict.reason());
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无收尾动作：判定与状态码都在 beforeHandshake 里做完了
    }
}
