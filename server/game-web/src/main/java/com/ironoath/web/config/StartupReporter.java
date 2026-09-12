package com.ironoath.web.config;

import com.ironoath.common.log.TraceContext;
import com.ironoath.config.ConfigRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 职责：容器就绪后打印一条启动自检汇总日志。
 * 依赖：Spring Boot、game-config。
 *
 * <p>把关键装配结果一次性打出来，是为了让「服务端起来了但行为不对」这类问题
 * 在第一行日志里就能定位：是配置指纹不对（读了旧表）、还是存储实现选错了（dev 用了 memory）。
 */
@Component
public class StartupReporter {

    private static final Logger LOG = LoggerFactory.getLogger(StartupReporter.class);

    private final ConfigRegistry configs;
    private final GameProperties properties;

    public StartupReporter(ConfigRegistry configs, GameProperties properties) {
        this.configs = configs;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        LOG.info("PROJECT_IRON_OATH 服务端启动完成 | traceId自检={} | 存储={} | 配置指纹={} | detail下发={}",
                TraceContext.newTraceId().substring(0, 8),
                properties.storage(),
                configs.fingerprint(),
                properties.exposeDetail() ? "开启（仅限非生产环境）" : "关闭");
        if (properties.exposeDetail()) {
            LOG.warn("ironoath.expose-detail=true：错误详情会下发给客户端，生产环境必须关闭");
        }
    }
}
