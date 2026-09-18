package com.ironoath.web.ws;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.web.util.UriUtils;

/**
 * 职责：解析 WebSocket 握手 URL 上的客户端凭据，并把它交给 {@code PlayerIdentityVerifier} 判。
 * 依赖：spring-web 的 URI 解析，零框架侵入（纯静态函数，好测）。
 *
 * <p><b>为什么凭据走 query 而不是请求头</b>：小游戏与浏览器的 WebSocket 都**设不了自定义头**
 * （HTTP 侧那套 {@code Authorization: Bearer} 在这里用不上），而服务端必须在握手阶段就认出是谁 ——
 * 否则一条匿名连接先把资源分配掉，身份要等到客户端事后发 {@code bind} 才判（那正是本类要收掉的口子）。
 *
 * <p><b>本类是"票据绝不进日志"这条红线的落点</b>：URL 里带着能顶替玩家身份的票据，
 * 它会原样落进 access log 与网关日志。所以任何要写进日志的握手描述都必须过
 * {@link #describeForLog(URI)}；直接把 {@code uri.toString()} 打进日志就是漏凭证。
 */
public final class WsCredentials {

    /** 握手参数：声称的玩家 id。客户端 {@code NetModule#socketUrl} 用同名参数。 */
    public static final String PARAM_PLAYER_ID = "playerId";

    /** 握手参数：会话票据（与 HTTP 侧同一枚）。 */
    public static final String PARAM_TOKEN = "token";

    /**
     * 会话属性键：握手阶段解析出来的 playerId。
     *
     * <p>存在属性里是为了让 {@code GameWebSocketHandler.bind} 能核对"bind 报的人就是握手报的人"，
     * 否则门刚从握手收紧、又从 bind 开回去（换成别人的 playerId 就能领走他的推送）。
     */
    public static final String ATTR_PLAYER_ID = "ironoath.ws.playerId";

    private WsCredentials() {
    }

    /** 握手 URL 上声称的玩家 id；缺参或空值都返回 null（空串不是身份，放行它等于放行匿名）。 */
    public static String playerIdOf(URI uri) {
        return paramOf(uri, PARAM_PLAYER_ID);
    }

    /** 握手 URL 上的会话票据；缺参或空值返回 null，交由校验实现判"无票"。 */
    public static String tokenOf(URI uri) {
        return paramOf(uri, PARAM_TOKEN);
    }

    private static String paramOf(URI uri, String name) {
        String query = uri == null ? null : uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        // 自己切 rawQuery 而不是借 UriComponentsBuilder：客户端用 encodeURIComponent 拼参数，
        // 票据与 id 里的特殊字符是百分号形式，不解码就会拿 "P%3A1" 去查存档、永远查不到；
        // 而 "+" 必须**按字面量保留**（base64 形式的票据里有 "+"，按表单那套读成空格会把票据读错）
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            if (name.equals(UriUtils.decode(pair.substring(0, eq), StandardCharsets.UTF_8))) {
                String value = UriUtils.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                // 空值参数（`playerId=`）不是身份：放行空串等于放行匿名
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    /**
     * bind 申报的身份与握手申报的是否同一个人。
     *
     * <p>属性里<b>没有</b>这个键时返回 true：本地宽松实现不做握手校验（生产闸门在
     * {@code productionReady()} 那一头），此时不设约束，dev 与现有流程照旧。
     * 但键存在而值为空仍判不一致 —— 那说明握手属性被改写过，不是"没带凭据"。
     */
    public static boolean bindsConsistentlyWith(Map<String, Object> handshakeAttributes, String claimedPlayerId) {
        Object handshaked = handshakeAttributes == null
                ? null : handshakeAttributes.get(ATTR_PLAYER_ID);
        if (handshaked == null) {
            return true;
        }
        return handshaked.equals(claimedPlayerId);
    }

    /** 给日志用的握手描述：保留 playerId，票据换成掩码。 */
    public static String describeForLog(URI uri) {
        if (uri == null) {
            return "（无握手 URI）";
        }
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return uri.getPath();
        }
        StringBuilder masked = new StringBuilder(uri.getPath()).append('?');
        for (String pair : query.split("&")) {
            if (masked.charAt(masked.length() - 1) != '?') {
                masked.append('&');
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            masked.append(name).append('=')
                    .append(PARAM_TOKEN.equals(name) ? "***" : pair.substring(eq + 1));
        }
        return masked.toString();
    }
}
