package com.ironoath.web.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.ironoath.common.json.JsonUtils;
import com.ironoath.common.log.TraceContext;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 职责：WebSocket 连接管理、心跳应答与主动推送（B01 只交付通道本身，业务推送由后续批次接入）。
 * 依赖：Spring WebSocket、game-common、game-config。
 *
 * <p>协议形态：文本帧，JSON。客户端发 {@code {"type":"ping"}}，服务端回
 * {@code {"type":"pong","serverNow":...}}；连接建立时服务端主动下发
 * {@code {"type":"connected","serverNow":...,"heartbeatSeconds":30}}。
 *
 * <p>心跳间隔<b>由服务端下发</b>而不是客户端硬编码：改心跳频率是运维动作
 * （弱网地区可能要调密），不该需要客户端发版（铁律 1 的延伸）。
 *
 * <p>发送必须同步：{@link WebSocketSession#sendMessage} 不是线程安全的，
 * 心跳应答与业务推送可能来自不同线程，并发写同一连接会抛
 * {@code TEXT_PARTIAL_WRITING}。这里对 session 加锁串行化。
 */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler implements PushGateway {

    private static final Logger LOG = LoggerFactory.getLogger(GameWebSocketHandler.class);

    private static final String TYPE_CONNECTED = "connected";
    private static final String TYPE_PING = "ping";
    private static final String TYPE_PONG = "pong";
    private static final String TYPE_BIND = "bind";
    private static final String TYPE_BOUND = "bound";
    private static final String TYPE_ERROR = "error";

    /** playerId → session。一个玩家多端登录时后者顶掉前者。 */
    private final Map<String, WebSocketSession> sessionsByPlayer = new ConcurrentHashMap<>();

    /** sessionId → playerId，用于连接关闭时反查清理。 */
    private final Map<String, String> playerBySession = new ConcurrentHashMap<>();

    private final TimeService timeService;
    private final ConfigRegistry configs;
    /**
     * 与 HTTP 侧 {@code PlayerIdentityInterceptor} 同一个端口。
     *
     * <p>WS 这条路比 HTTP 更值得校验：bind 成功的后果是"别人的战报、集结、联盟消息被推到我这条连接上"，
     * 那是只读的外泄，连"操作失败"的报错都不会有。
     */
    private final com.ironoath.web.security.PlayerIdentityVerifier identity;

    public GameWebSocketHandler(TimeService timeService, ConfigRegistry configs,
                                com.ironoath.web.security.PlayerIdentityVerifier identity) {
        this.timeService = timeService;
        this.configs = configs;
        this.identity = identity;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        long heartbeatSeconds = configs.longParam("WS_HEARTBEAT_SECONDS");
        LOG.info("WebSocket 连接建立 sessionId={} 心跳间隔={}s", session.getId(), heartbeatSeconds);
        send(session, Map.of(
                "type", TYPE_CONNECTED,
                "serverNow", timeService.serverNow(),
                "heartbeatSeconds", heartbeatSeconds));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        JsonNode node;
        try {
            node = JsonUtils.readTree(payload);
        } catch (RuntimeException e) {
            LOG.warn("{} WebSocket 收到非法 JSON，已忽略：{}", TraceContext.prefix(), abbreviate(payload));
            send(session, Map.of("type", TYPE_ERROR, "msg", "非法的 JSON 消息"));
            return;
        }
        String type = node.hasNonNull("type") ? node.get("type").asText() : "";
        switch (type) {
            case TYPE_PING -> send(session, Map.of(
                    "type", TYPE_PONG,
                    "serverNow", timeService.serverNow()));
            case TYPE_BIND -> bind(session, node);
            default -> LOG.warn("{} WebSocket 收到未知消息类型 type={} sessionId={}",
                    TraceContext.prefix(), type, session.getId());
        }
    }

    /** 客户端登录后绑定 playerId，之后服务端才能定向推送。 */
    private void bind(WebSocketSession session, JsonNode node) {
        String playerId = node.hasNonNull("playerId") ? node.get("playerId").asText() : null;
        if (playerId == null || playerId.isBlank()) {
            send(session, Map.of("type", TYPE_ERROR, "msg", "bind 缺少 playerId"));
            return;
        }
        if (!WsCredentials.bindsConsistentlyWith(session.getAttributes(), playerId)) {
            // 握手已经判过身份了，这里再允许 bind 换成别人 = 门从握手收紧、从 bind 开回去：
            // 握着属于自己的连接，领走别人的定向推送（那是只读外泄，连报错都不会有）
            LOG.warn("WebSocket 绑定被拒：bind 的 playerId 与握手声明的不是同一个人 bind={} sessionId={}",
                    playerId, session.getId());
            send(session, Map.of("type", TYPE_ERROR, "msg", "绑定身份与握手身份不一致"));
            return;
        }
        if (identity.productionReady()) {
            // 与 HTTP 侧同一件事：严格实现下，报一个 playerId 不再等于拿到那个人的推送通道。
            // 票据走 bind 消息的 token 字段，客户端在每条连接建立时自动带上（NetModule#sendBind），
            // 与 HTTP 用的是同一枚会话票据（HTTP 那边优先读 Authorization: Bearer）
            var verdict = identity.verify(new com.ironoath.web.security.PlayerIdentityVerifier.Claim(
                    playerId, node.hasNonNull("token") ? node.get("token").asText() : null,
                    "ws:bind/" + session.getId()));
            if (!verdict.allowed()) {
                LOG.warn("WebSocket 绑定被拒 playerId={} sessionId={} 原因={}",
                        playerId, session.getId(), verdict.reason());
                send(session, Map.of("type", TYPE_ERROR, "msg", "身份校验未通过"));
                return;
            }
        }
        WebSocketSession previous = sessionsByPlayer.put(playerId, session);
        playerBySession.put(session.getId(), playerId);
        if (previous != null && !previous.getId().equals(session.getId())) {
            // 顶号：同一账号在新设备登录，旧连接必须关掉，否则推送会随机落到某一端
            LOG.info("玩家多端登录，顶掉旧连接 playerId={} 旧sessionId={} 新sessionId={}",
                    playerId, previous.getId(), session.getId());
            closeQuietly(previous, CloseStatus.NORMAL.withReason("已在其他设备登录"));
        }
        TraceContext.bindPlayer(playerId);
        LOG.info("WebSocket 绑定玩家成功 playerId={} sessionId={} 当前在线={}",
                playerId, session.getId(), sessionsByPlayer.size());
        send(session, Map.of("type", TYPE_BOUND, "playerId", playerId, "serverNow", timeService.serverNow()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String playerId = playerBySession.remove(session.getId());
        if (playerId != null) {
            // 只在映射仍指向本连接时才移除，避免顶号场景下误删新连接的映射
            sessionsByPlayer.remove(playerId, session);
        }
        LOG.info("WebSocket 连接关闭 sessionId={} playerId={} status={} 剩余在线={}",
                session.getId(), playerId, status, sessionsByPlayer.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        LOG.warn("WebSocket 传输异常 sessionId={} 原因={}", session.getId(), exception.getMessage());
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    @Override
    public boolean pushToPlayer(String playerId, String type, Object payload) {
        WebSocketSession session = playerId == null ? null : sessionsByPlayer.get(playerId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        return send(session, Map.of(
                "type", type,
                "serverNow", timeService.serverNow(),
                "data", payload == null ? Map.of() : payload));
    }

    @Override
    public int onlineCount() {
        return sessionsByPlayer.size();
    }

    /**
     * 在线玩家 id 的<b>快照</b>。返回拷贝而不是 {@code keySet()} 视图：
     * 调用方是推送线程池，它遍历的时候连接正在随时增删，
     * 拿着活视图遍历会撞上并发修改，而那种异常发生在异步线程里、只进日志不进响应，
     * 表现就是「广播偶发地少发给几个人」。
     */
    @Override
    public java.util.Collection<String> onlinePlayerIds() {
        return java.util.List.copyOf(sessionsByPlayer.keySet());
    }

    @Override
    public boolean isOnline(String playerId) {
        WebSocketSession session = playerId == null ? null : sessionsByPlayer.get(playerId);
        return session != null && session.isOpen();
    }

    /** 串行化发送，避免并发写同一连接。返回是否发送成功。 */
    private boolean send(WebSocketSession session, Object body) {
        String text = JsonUtils.toJson(body);
        synchronized (session) {
            if (!session.isOpen()) {
                return false;
            }
            try {
                session.sendMessage(new TextMessage(text));
                return true;
            } catch (IOException e) {
                LOG.warn("WebSocket 发送失败 sessionId={} 原因={}", session.getId(), e.getMessage());
                return false;
            }
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            LOG.debug("关闭 WebSocket 连接时出错 sessionId={} 原因={}", session.getId(), e.getMessage());
        }
    }

    /** 日志里截断超长消息，防止玩家发一个几 MB 的帧把日志刷爆。 */
    private static String abbreviate(String text) {
        int max = 200;
        return text.length() <= max ? text : text.substring(0, max) + "…(共" + text.length() + "字符)";
    }
}
