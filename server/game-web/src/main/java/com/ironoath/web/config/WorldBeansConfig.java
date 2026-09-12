package com.ironoath.web.config;

import com.ironoath.core.march.MarchDueQueue;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.store.memory.InMemoryMarchStore;
import com.ironoath.web.store.memory.InMemoryWorldStore;
import com.ironoath.web.store.memory.SortedMarchDueQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责：装配 B07 世界地图与行军所需的端口实现。
 * 依赖：Spring Boot、game-core 的端口、game-web 的内存存储、game-config（读 chunk 尺寸）。
 *
 * <p><b>三个内存实现都会在重启后丢数据，而世界状态是跨会话的</b>：
 * 玩家的城不会因为他下线就消失，行军也不会（B07 验收 1：杀进程重进后所有队伍
 * 按真实剩余时间继续）。所以生产必须用 MongoDB 版（B16 交付），
 * 这里的 WARN 日志就是为了让任何人在启动服务端时都看到这个缺口。
 *
 * <p><b>{@link SortedMarchDueQueue} 里一个定时器都没有</b>，这是 B07 头号红线的落地：
 * 到期由请求驱动地扫描（与产出结算的惰性推进同一套纪律，见 B03 验收 9），
 * 生产环境换成 Redisson {@code RDelayedQueue} 或 Redis ZSET ——
 * 两者都是「一个队列 + 一个消费线程」，不是每支行军一个定时器，因此不违反红线。
 * CI 的 check-layering.sh 会扫全服务端运行期模块，出现 {@code new Timer(} /
 * {@code newScheduledThreadPool} / {@code scheduleAtFixedRate} 就直接失败。
 */
@Configuration
public class WorldBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(WorldBeansConfig.class);

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage", havingValue = GameProperties.STORAGE_MEMORY,
            matchIfMissing = true)
    public MarchRepository marchRepository(com.ironoath.config.ConfigRegistry configs) {
        LOG.warn("使用内存行军存储：进程重启后所有行军队伍丢失，而 B07 验收 1 要求"
                + "「杀进程重进后按真实剩余时间继续」。仅限本地开发与单测，"
                + "生产请设 ironoath.storage=mongo（MongoDB 版由 B16 交付）");
        return new InMemoryMarchStore((int) configs.longParam("WORLD_CHUNK_SIZE"));
    }

    /** 到期队列的内存实现：mongo 模式下由 {@code MongoStoreConfig.marchDueQueue} 接管。 */
    @Bean
    @ConditionalOnProperty(name = "ironoath.storage", havingValue = GameProperties.STORAGE_MEMORY,
            matchIfMissing = true)
    public MarchDueQueue marchDueQueue() {
        LOG.info("使用内存行军到期队列（TreeMap 按到期时刻排序，请求驱动扫描，无任何定时器）");
        return new SortedMarchDueQueue();
    }

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage", havingValue = GameProperties.STORAGE_MEMORY,
            matchIfMissing = true)
    public WorldRepository worldRepository(MarchRepository marchRepository,
                                          com.ironoath.config.ConfigRegistry configs) {
        LOG.warn("使用内存世界存储：进程重启后玩家城位置、已探索迷雾与侦查报告全部丢失，"
                + "而被采空的资源点与被打掉的野怪会原地复活（B07 明写刷新必须是显式运营行为）。"
                + "仅限本地开发与单测，生产请设 ironoath.storage=mongo（该实现已存在，"
                + "不用它属于配置遗漏而不是能力缺失）");
        return new InMemoryWorldStore(marchRepository, (int) configs.longParam("WORLD_CHUNK_SIZE"));
    }
}
