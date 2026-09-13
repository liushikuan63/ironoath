package com.ironoath.web.security;

import com.ironoath.common.time.TimeService;

/**
 * 职责：上线可用的身份校验 —— 校验 {@link AuthSessionService} 签发的会话票据。
 * 依赖：{@link AuthSessionService}、game-common 的 {@link TimeService}。
 *
 * <p>它替代 {@link LocalDevIdentityVerifier}，并且 {@link #productionReady()} 返回 true：
 * 于是 {@code PlayerIdentityInterceptor} 与 WebSocket bind 会真的拦请求，
 * 而 {@code ProductionReadiness} 只在票据密钥来自环境变量时才放行（见装配）。
 */
public final class SessionIdentityVerifier implements PlayerIdentityVerifier {

    private final AuthSessionService sessions;
    private final TimeService time;
    /**
     * 是否强制要求票据。开发/联调环境默认关（否则本地浏览器与既有单测全会 2006），
     * 生产必须开 —— 见 {@code SecurityBeansConfig} 的装配与 {@code ProductionReadiness} 的检查。
     */
    private final boolean enforce;

    public SessionIdentityVerifier(AuthSessionService sessions, TimeService time, boolean enforce) {
        this.sessions = sessions;
        this.time = time;
        this.enforce = enforce;
    }

    @Override
    public Verdict verify(Claim claim) {
        if (!enforce) {
            return Verdict.allow();
        }
        if (claim == null || claim.playerId() == null || claim.playerId().isBlank()) {
            return Verdict.deny("缺少玩家标识");
        }
        return sessions.verify(claim, time.serverNow());
    }

    @Override
    public boolean productionReady() {
        return true;
    }
}
