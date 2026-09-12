package com.ironoath.web.config;

import com.ironoath.web.store.memory.InMemorySocialStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责：装配 B10 社交域的内存存储。
 * 依赖：Spring Boot、game-web 的内存存储。
 *
 * <p>与其它内存实现同一套约定：dev/test 零依赖启动，MongoDB 版由 B16 补，
 * 启动时的 WARN 让任何人在跑服务端时都看到这个缺口。
 *
 * <p><b>聊天记录丢失的后果比存档丢失更直观</b>：玩家上线发现联盟频道空了，
 * 会认为「昨晚说的话没人看到」—— 而社交系统一旦让人觉得说了也没用，
 * 他就不会再说了，组织随之散掉。B10 把联盟定位成「庇护」，
 * 而庇护的前提是成员之间还在说话。所以这一条 WARN 不是形式。
 */
@Configuration
public class SocialBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SocialBeansConfig.class);

    @Bean
    public InMemorySocialStore socialStore() {
        LOG.warn("使用内存社交存储：进程重启后小队、联盟、入盟申请、帮助请求、社交事件与聊天记录全部丢失。"
                + "仅限本地开发与单测，生产请设 ironoath.storage=mongo（MongoDB 版由 B16 交付）");
        return new InMemorySocialStore();
    }
}
