package com.ironoath.web.config;

import com.ironoath.web.social.SocialStore;
import com.ironoath.web.store.memory.InMemorySocialStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责：装配 B10 社交域存储 —— {@code ironoath.storage=memory} 时生效（缺省即此，便于零依赖启动与单测）。
 * 依赖：Spring Boot、game-web 的内存存储。
 *
 * <p><b>为什么条件装配这条一开始就写死</b>：曾经这里无条件 {@code new InMemorySocialStore()}，
 * 而它是全项目最后一个没有端口的存储 —— 后果不是"启动失败"，而是
 * {@code storage=mongo} 下服务照常起来、社交数据照样随进程消失且每个实例各算各的。
 * 抽端口 + 条件装配之后，缺生产实现会让上下文起不来，问题从"静默"变成"响亮"
 * （由 {@code BeanAssemblyTest} 与 {@code MongoStorageGuard} 双重守住，见收口清单 #16）。
 *
 * <p><b>聊天记录丢失的后果比存档丢失更直观</b>：玩家上线发现联盟频道空了，
 * 会认为「昨晚说的话没人看到」—— 而社交系统一旦让人觉得说了也没用，
 * 他就不会再说了，组织随之散掉。B10 把联盟定位成「庇护」，
 * 而庇护的前提是成员之间还在说话。所以这一条 WARN 不是形式。
 */
@Configuration
@ConditionalOnProperty(name = "ironoath.storage", havingValue = GameProperties.STORAGE_MEMORY,
        matchIfMissing = true)
public class SocialBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SocialBeansConfig.class);

    @Bean
    public SocialStore socialStore() {
        LOG.warn("使用内存社交存储：进程重启后小队、联盟、入盟申请、帮助请求、社交事件与聊天记录全部丢失。"
                + "仅限本地开发与单测，生产请设 ironoath.storage=mongo");
        return new InMemorySocialStore();
    }
}