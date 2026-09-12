package com.ironoath.web.config;

import com.ironoath.web.web.PlayerIdentityInterceptor;
import com.ironoath.web.web.PowerRefreshInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 职责：Spring MVC 的装配（拦截器注册）。
 * 依赖：{@link PlayerIdentityInterceptor}、{@link PowerRefreshInterceptor}。
 *
 * <p>单独一个配置类而不是把 {@code WebMvcConfigurer} 塞进已有的 {@code GameBeansConfig}：
 * 那个类装的是玩法侧的 Bean（仓储、锁、种子源），本类装的是 HTTP 层的横切关注点。
 * 混在一起的后果是「改 MVC 行为要在一个上百行的 Bean 工厂里找」，
 * 而横切关注点恰恰是最需要一眼看到全部的地方 —— 漏看一个拦截器，排查会非常痛苦。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final PowerRefreshInterceptor powerRefreshInterceptor;
    private final PlayerIdentityInterceptor playerIdentityInterceptor;

    public WebMvcConfig(PowerRefreshInterceptor powerRefreshInterceptor,
                        PlayerIdentityInterceptor playerIdentityInterceptor) {
        this.powerRefreshInterceptor = powerRefreshInterceptor;
        this.playerIdentityInterceptor = playerIdentityInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 身份校验登记在战力重算之前：Spring 按登记顺序执行 preHandle，
        // 而一个身份不成立的请求不该产生任何写副作用（也就没有战力可重算）
        registry.addInterceptor(playerIdentityInterceptor).addPathPatterns("/**");
        // 覆盖全部路径：新增端点时不需要回来登记，
        // 而这正是把战力重算挂到边界上的全部意义（见 PowerRefreshInterceptor 的类注释）
        registry.addInterceptor(powerRefreshInterceptor).addPathPatterns("/**");
    }
}
