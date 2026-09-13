package com.ironoath.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ironoath.common.time.TimeService;
import com.ironoath.web.security.AuthSessionService;
import com.ironoath.web.security.LocalDevIdentityVerifier;
import com.ironoath.web.security.LocalDevWeChatCodeExchanger;
import com.ironoath.web.security.PlayerIdentityVerifier;
import com.ironoath.web.security.SessionIdentityVerifier;
import com.ironoath.web.security.WeChatCodeExchanger;
import com.ironoath.web.security.WeChatSessionCodeExchanger;

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

    private static final Logger LOG = LoggerFactory.getLogger(SecurityBeansConfig.class);

    /** 本地开发用的票据密钥。生产必须由环境变量覆盖，否则 prod 启动会被拒绝。 */
    public static final String DEV_SESSION_SECRET = "ironoath-dev-session-secret";

    /**
     * 微信 code 兑换器：配了 AppID + AppSecret 就真的调微信，否则退到本地确定性实现。
     *
     * <p>为什么用环境变量而不是配置文件：AppSecret 是密钥，进版本库等于公开。
     * prod 下缺它时本地实现会让 {@code ProductionReadiness} 拒绝启动。
     */
    @Bean
    public WeChatCodeExchanger weChatCodeExchanger(
            @Value("${WECHAT_APP_ID:}") String appId,
            @Value("${WECHAT_APP_SECRET:}") String appSecret) {
        if (!appId.isBlank() && !appSecret.isBlank()) {
            LOG.info("微信登录：使用真实 code2session 兑换器（appId={}）", appId);
            return new WeChatSessionCodeExchanger(appId, appSecret);
        }
        return new LocalDevWeChatCodeExchanger();
    }

    /**
     * 会话票据服务。密钥同样来自环境变量；开发用固定值（不能上生产）。
     */
    @Bean
    public AuthSessionService authSessionService(
            @Value("${WECHAT_SESSION_SECRET:}") String sessionSecret) {
        if (sessionSecret.isBlank()) {
            LOG.warn("会话票据使用开发密钥：任何人都能伪造票据，仅限本地开发与单测。"
                    + "生产请设置 WECHAT_SESSION_SECRET（prod 下缺它会让服务拒绝启动）");
            return new AuthSessionService(DEV_SESSION_SECRET);
        }
        return new AuthSessionService(sessionSecret);
    }

    /**
     * 身份校验端口。
     *
     * <p><b>装配规则</b>（与 {@link #weChatCodeExchanger} 同一判据）：
     * <ul>
     *   <li>有微信凭据 ⇒ {@link SessionIdentityVerifier}（{@code productionReady=true}，
     *       真的校验票据；是否强制由 {@code ironoath.identity.enforce} 决定，默认 prod 开、dev 关）；</li>
     *   <li>无微信凭据 ⇒ {@link LocalDevIdentityVerifier}（{@code productionReady=false}，
     *       prod 启动直接被 {@code ProductionReadiness} 拒绝）。</li>
     * </ul>
     * 这样"忘了配密钥"的表现是服务起不来，而"本地开发"的表现是一切照常。
     */
    @Bean
    public PlayerIdentityVerifier playerIdentityVerifier(
            WeChatCodeExchanger exchanger,
            AuthSessionService sessions,
            TimeService time,
            @Value("${ironoath.identity.enforce:false}") boolean enforce) {
        if (exchanger.productionReady()) {
            LOG.info("身份校验：启用会话票据校验（enforce={}）", enforce);
            return new SessionIdentityVerifier(sessions, time, enforce);
        }
        return new LocalDevIdentityVerifier();
    }
}
