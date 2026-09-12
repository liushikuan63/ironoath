package com.ironoath.web.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 职责：本地开发与单测用的身份校验 —— <b>任何声称都放行</b>，并且如实声明自己不能上生产。
 * 依赖：无。
 *
 * <p><b>它存在的意义就是让自己不可用于生产</b>：{@link #productionReady()} 固定 false，
 * 于是 {@code ProductionReadiness} 在 prod profile 下会因为这个 bean 在场而拒绝启动。
 * 换句话说，"上线时忘了接真实登录" 的表现不是"谁能刷别人的号"，而是"服务起不来" ——
 * 后者是可发现的故障，前者是要等投诉才知道的事故。
 *
 * <p>也不在这里加"看起来更安全"的伪校验（比如"playerId 必须存在于存档里"）：
 * 服务层本来就会因为 {@code PLAYER_NOT_FOUND} 而拒掉不存在的玩家，
 * 在这里再判一次只会让以后接真实现的人以为"这里已经在做身份校验了"。
 */
public final class LocalDevIdentityVerifier implements PlayerIdentityVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(LocalDevIdentityVerifier.class);

    public LocalDevIdentityVerifier() {
        LOG.warn("身份校验使用本地开发实现：任何 X-Player-Id 都会被判定为可信，"
                + "也就是说当前任何人都能操作任何账号。上线前必须替换为微信登录会话校验"
                + "（B15 §三；prod 下本实现会让服务直接拒绝启动）");
    }

    @Override
    public Verdict verify(Claim claim) {
        return Verdict.allow();
    }

    @Override
    public boolean productionReady() {
        return false;
    }
}
