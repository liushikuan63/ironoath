package com.ironoath.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ironoath.web.security.LocalDevIdentityVerifier;
import com.ironoath.web.security.PlayerIdentityVerifier;

/**
 * 职责：身份校验端口的装配（B15 §三）。
 * 依赖：{@link LocalDevIdentityVerifier}。
 *
 * <p><b>这里给的是"任何声称都放行"的本地实现，而且它 {@code productionReady() = false}</b>，
 * 于是 prod profile 下 {@code ProductionReadiness} 会因为它在场而拒绝启动。
 * 真实实现（微信登录换 openid + 会话票据）落地时有两种接管方式，二者都可行且都会被同一道闸门检查：
 * <ul>
 *   <li>把真实现标 {@code @Primary} —— 本 bean 仍在，但注入点拿到的是真实现；</li>
 *   <li>或直接把本方法换成真实现（更干净：不留一个永远不该被用的 bean）。</li>
 * </ul>
 *
 * <p>刻意<b>不</b>用 {@code @ConditionalOnMissingBean}：那个条件按 bean 的<b>注册顺序</b>求值，
 * 放在普通 {@code @Configuration} 里会出现"谁先被扫描到谁赢"，
 * 而这里的失败方向必须是"忘了接真实现时根本起不来"，不能取决于扫描顺序。
 */
@Configuration
public class SecurityBeansConfig {

    @Bean
    public PlayerIdentityVerifier playerIdentityVerifier() {
        return new LocalDevIdentityVerifier();
    }
}
