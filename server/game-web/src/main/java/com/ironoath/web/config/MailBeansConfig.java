package com.ironoath.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.web.mail.MailStore;
import com.ironoath.web.reward.RewardNames;
import com.ironoath.web.reward.StoreMailbox;
import com.ironoath.web.store.memory.InMemoryMailStore;

/**
 * 职责：装配邮件系统的基础件（存储 + 发奖溢出用的真邮箱）。
 * 依赖：Spring Boot、{@link MailStore} 的两份实现之一、{@link StoreMailbox}。
 *
 * <p><b>与其它存储同一套约定</b>：dev/test 用内存版，{@code ironoath.storage=mongo} 时换
 * {@code MongoMailStore}（见 {@code MongoStoreConfig}）。
 *
 * <p><b>本类的 WARN 比别家重一档</b>：内存版的邮件里装着<b>玩家该得而当下没拿到</b>的东西，
 * 重启即丢就是丢玩家资产（不是丢一份可重算的缓存）。所以它说得更长，
 * 而 {@code TransientRewardPorts} 那条「mailId 查不回来」的老缺口由这里的
 * {@link #rewardMailbox} 补上。
 */
@Configuration
public class MailBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(MailBeansConfig.class);

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage",
            havingValue = GameProperties.STORAGE_MEMORY, matchIfMissing = true)
    public MailStore mailStore() {
        LOG.warn("使用内存邮箱：重启后「玩家没领的附件」会一起消失 —— 这里装的不是可重算的缓存，"
                + "而是「该得但当下放不下」的奖励。生产请设 ironoath.storage=mongo（实现已在 MongoMailStore）");
        return new InMemoryMailStore();
    }

    /**
     * 发奖溢出的落地邮箱。B04 验收 2 要的那封「写明溢出数量与原因」的邮件从这里变成查得回来的真记录。
     *
     * <p>刻意<b>不</b>声明成 {@link StoreMailbox}：消费者 {@code RewardGrantor} 按端口
     * {@link RewardPorts.Mailbox} 注入（收口清单 #15 那个坑的方向是反的 —— 出问题的是
     * 「声明成端口而按具体类注入」）。
     */
    @Bean
    public RewardPorts.Mailbox rewardMailbox(MailStore store, RewardNames names,
                                             TimeService timeService, ConfigRegistry configs) {
        LOG.info("发奖溢出已接真邮箱（B12 §2）：溢出邮件带 id、可查询、按 MAIL_RETENTION_DAYS 过期");
        return new StoreMailbox(store, names, timeService, configs);
    }
}
