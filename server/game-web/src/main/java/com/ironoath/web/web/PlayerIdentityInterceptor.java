package com.ironoath.web.web;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.web.controller.CityController;
import com.ironoath.web.security.PlayerIdentityVerifier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 职责：把所有带玩家身份的 HTTP 请求收口到同一个身份校验点（B15 §三）。
 * 依赖：{@link PlayerIdentityVerifier}。
 *
 * <p><b>为什么挂在 HTTP 边界而不是逐个 controller 里加参数</b>：与 {@code PowerRefreshInterceptor}
 * 同一条理由 —— 需要身份的端点有二十多个，逐个挂钩子必然会漏，而漏掉的那一个就是"能刷任意账号"的洞。
 * 挂在边界上新端点自动被覆盖，不需要写那个端点的人记得这件事。
 *
 * <p><b>拦不拦由端口自己说</b>：只有 {@link PlayerIdentityVerifier#productionReady()} 为 true 的实现
 * 才真的拦。这不是偷懒而是让这条接缝可用：本地开发、单测、以及还没拿到微信密钥的联调环境都没有真票据，
 * 宽松实现照样拦的话表现就是"全都 2006"，那会让第一件真要做的事（接微信登录）被误判成"闸门写坏了"。
 * <b>而宽松实现在 prod 下会被 {@code ProductionReadiness} 拒绝启动</b> —— 所以"不拦"这件事
 * 只可能发生在明知不用于生产的环境里。
 *
 * <p><b>公开路径清单</b>（每条都写清为什么公开，改这张表的人要能看见理由）：
 * <ul>
 *   <li>{@code /time/sync}：登录前就要能调，且不读任何玩家数据；</li>
 *   <li>{@code /player/init}：按 deviceId 建档，此刻还没有可声称的 playerId；</li>
 *   <li>{@code /pay/callback}：调用方是渠道服务器而不是客户端，可信性来自验签，
 *       给它加玩家身份校验既做不到也没意义（见 {@code PayController} 类注释）；</li>
 *   <li>{@code /ops/}：埋点与崩溃上报。<b>故意不鉴权</b> —— 崩溃可能发生在拿到会话之前，
 *       为鉴权丢掉崩溃日志是拿可观测性换一个假安全感；这条路上的刷量口子是
 *       {@code TRACK_BATCH_*} 上限与体积预算，不是身份。</li>
 * </ul>
 */
@Component
public class PlayerIdentityInterceptor implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(PlayerIdentityInterceptor.class);

    private static final List<String> PUBLIC_PATHS = List.of(
            "/time/sync", "/player/init", "/pay/callback", "/ops/", "/error");

    private final PlayerIdentityVerifier verifier;

    public PlayerIdentityInterceptor(PlayerIdentityVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!verifier.productionReady()) {
            return true;
        }
        String uri = request.getRequestURI();
        if (isPublic(uri)) {
            return true;
        }
        String playerId = request.getHeader(CityController.PLAYER_HEADER);
        if (playerId == null || playerId.isBlank()) {
            LOG.warn("严格身份校验下收到没有玩家标识的请求 uri={} 远端={}", uri, request.getRemoteAddr());
            throw new BizException(ErrorCode.PLAYER_IDENTITY_UNVERIFIED,
                    "缺少 " + CityController.PLAYER_HEADER + " 头");
        }
        PlayerIdentityVerifier.Verdict verdict = verifier.verify(new PlayerIdentityVerifier.Claim(
                playerId, bearerToken(request), uri));
        if (!verdict.allowed()) {
            LOG.warn("身份校验未通过 uri={} playerId={} 远端={} 原因={}",
                    uri, playerId, request.getRemoteAddr(), verdict.reason());
            throw new BizException(ErrorCode.PLAYER_IDENTITY_UNVERIFIED, verdict.reason());
        }
        return true;
    }

    private static boolean isPublic(String uri) {
        for (String path : PUBLIC_PATHS) {
            if (uri != null && (uri.equals(path) || uri.startsWith(path))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从标准 {@code Authorization: Bearer &lt;token&gt;} 里取票据。
     *
     * <p><b>为什么用标准头而不是自定义头</b>：客户端 {@code NetModule} 一直发的就是
     * {@code Authorization: Bearer}，而这里原先读的是 {@code X-Auth-Token} ——
     * 两端头名不一致的表现是"严格身份模式下所有请求 2006"，而且本地宽松实现永远不会暴露它。
     * 统一到标准头，同时仍兼容自定义头（早期联调脚本按 {@code TOKEN_HEADER} 发的那种）。
     */
    private static String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }
        return request.getHeader(PlayerIdentityVerifier.TOKEN_HEADER);
    }
}
