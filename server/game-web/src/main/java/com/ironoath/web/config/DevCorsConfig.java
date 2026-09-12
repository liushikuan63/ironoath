package com.ironoath.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 职责：本地开发期的跨域放行 —— <b>只在 {@code dev} profile 生效</b>。
 * 依赖：Spring Web MVC。
 *
 * <p><b>为什么需要它</b>：Cocos 预览服务器（编辑器内预览、或本地静态服务器托管
 * {@code client/build/web-mobile}）与服务端不同源，浏览器会对 {@code /player/init}
 * 这类请求先发 OPTIONS 预检；服务端不返回 CORS 头时，页面表现为「一片红」而服务端日志
 * 里什么都看不到（请求根本没发出去）。WebSocket 那一侧早已
 * {@code setAllowedOriginPatterns("*")}，HTTP 这一侧此前一直是缺的。
 *
 * <p><b>为什么挂在 profile 而不是全局</b>：生产（{@code prod}）与测试（{@code test}）都不该
 * 允许任意来源 —— 放开 CORS 等于把「谁都能从任意网页调这个服务端」变成默认行为。
 * 这里只放行 localhost 的任意端口（Vite/静态服务器/编辑器预览端口每次都不同），
 * 且不放开 credentials：本项目用 {@code X-Player-Id} 头而不是 Cookie 鉴权，不需要它。
 */
@Configuration
@Profile("dev")
public class DevCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                // 客户端排查问题时会把 X-Trace-Id 打出来；不暴露的话浏览器读不到这个响应头
                .exposedHeaders("X-Trace-Id")
                .maxAge(3600L);
    }
}