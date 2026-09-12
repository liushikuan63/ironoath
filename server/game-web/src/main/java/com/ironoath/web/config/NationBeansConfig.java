package com.ironoath.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ironoath.web.nation.NationRulesAssembler;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.store.memory.InMemoryNationStore;

/**
 * 职责：装配国家存储。
 * 依赖：Spring Boot、game-web 的内存存储、{@code NationRulesAssembler}。
 *
 * <p>与其它内存实现同一套约定：dev/test 零依赖启动，{@code ironoath.storage=mongo} 时换
 * {@code MongoNationStore}（见 {@code MongoStoreConfig}）。内存 bean 带
 * {@code @ConditionalOnProperty}，与 #37 排掉 {@code @Primary} 那颗雷同一条理由：
 * <b>哪个模式缺实现就让上下文起不来</b>，不许静默退回内存版。
 *
 * <p><b>为什么要往存储里注一个规则装配器</b>：规则（等级上限、国库容量、日志保留条数）
 * <b>不是存档的一部分</b>，它来自配置表且会热更；而两套实现的读都返回副本，
 * 重建一个"能用的国家"就必须拿到规则。让存储自己抄一份规则值，
 * 等于把某次热更冻进存档 —— 表现是改了 {@code nation_config} 对已有国家不生效且不报错。
 *
 * <p><b>国家数据丢失的后果比战报重</b>：战报丢了玩家失去复盘线索，
 * 国家丢了是<b>一批人的政治关系归零</b> —— 官职、外交、国库账目全部消失，
 * 而国库账目关系到玩家之间真金白银的贡献（B13 §3 明写「防贪污引发现实纠纷」）。
 * 所以这一份存储在生产上不能是内存实现，也不是「可以晚点做」的那一类。
 */
@Configuration
public class NationBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(NationBeansConfig.class);

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage",
            havingValue = GameProperties.STORAGE_MEMORY, matchIfMissing = true)
    public NationStore nationStore(NationRulesAssembler rules) {
        LOG.warn("使用内存国家存储：进程重启后国家、官职、外交关系与国库账目全部丢失。"
                + "仅限本地开发与单测，生产请设 ironoath.storage=mongo（该实现已存在，"
                + "不用它属于配置遗漏而不是能力缺失）—— "
                + "国库账目关系到玩家之间的真实贡献，丢一次就是无法对账的纠纷");
        return new InMemoryNationStore(rules);
    }
}
