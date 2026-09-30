package com.ironoath.web.config;

import com.ironoath.common.log.TraceContext;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.model.GlobalCfg;
import com.ironoath.core.formula.Formula;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;

/**
 * 职责：核心 Bean 装配 —— 把纯 Java 层（game-config / game-core）接入 Spring 容器。
 * 依赖：Spring Boot、game-config、game-core。
 *
 * <p>装配方向是单向的：纯 Java 层不知道 Spring 存在，本类负责把它们「翻译」成 Bean。
 * 这样才能保证 game-core 的单测不需要启动容器（B01 三条硬要求之 1）。
 *
 * <p>配置表加载失败会直接抛 {@link com.ironoath.config.ConfigException}，
 * Spring 容器启动中断 ⇒ <b>服务端拒绝启动</b>（B01 三条硬要求之 3）。
 * 这是刻意的：带着错误配置启动，比启动失败危险得多。
 */
@Configuration
public class GameBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(GameBeansConfig.class);

    /**
     * 加载并全量校验配置表。
     *
     * <p>Bean 创建期抛异常 ⇒ 容器启动失败 ⇒ 进程退出，天然满足「校验失败拒绝启动」。
     */
    @Bean
    public ConfigRegistry configRegistry(GameProperties properties) {
        Path dir = Path.of(properties.configDir());
        LOG.info("开始加载配置表，目录={}", dir.toAbsolutePath());
        ConfigRegistry registry = ConfigRegistry.loadFromDirectory(dir);
        LOG.info("配置表加载并校验通过：{} 张表，指纹={}", registry.tableNames().size(), registry.fingerprint());

        // 把未定稿的参数全部打出来（B00 输出格式要求：不确定处显式标注，不要静默假设）
        List<GlobalCfg> pending = registry.pendingConfirmations();
        if (!pending.isEmpty()) {
            LOG.warn("以下 {} 个全局参数尚未定稿，需后续批次确认：", pending.size());
            for (GlobalCfg cfg : pending) {
                LOG.warn("  - {} = {} （{}）", cfg.id(), cfg.value(), cfg.todo());
            }
        }
        return registry;
    }

    /**
     * 服务端时间源。异常偏移告警阈值取自配置表，不硬编码（铁律 1）。
     *
     * <p><b>时间源本身可覆盖</b>（{@link ClockSource}，dev profile 有加速实现 {@link DevClockSpeed}）：
     * 国策一轮 48 小时，真链路等不到。默认走 {@link ClockSource#SYSTEM}，
     * 而 prod 上下文里那个加速 bean <b>不存在</b>（{@code @Profile("dev")}）⇒ 走真实时间，
     * 是结构事实而不是「忘了设环境变量」。
     */
    @Bean
    public TimeService timeService(ConfigRegistry configs, ObjectProvider<ClockSource> clocks) {
        ClockSource source = clocks.getIfAvailable(() -> ClockSource.SYSTEM);
        return new TimeService(source::nowMillis, configs.longParam("TIME_SYNC_MAX_SKEW_MS"));
    }

    /** 曲线求值器。ConfigRegistry 实现了 game-common 的 CurveSource 端口，因此 game-core 不需要依赖 game-config。 */
    @Bean
    public Formula formula(ConfigRegistry configs) {
        return new Formula(configs);
    }
}
