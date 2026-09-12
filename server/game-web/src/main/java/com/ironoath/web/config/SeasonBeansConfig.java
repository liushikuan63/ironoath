package com.ironoath.web.config;

import com.ironoath.web.season.SeasonBoardStore;
import com.ironoath.web.season.SeasonLedgerStore;
import com.ironoath.web.store.memory.InMemorySeasonBoardStore;
import com.ironoath.web.store.memory.InMemorySeasonLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责：装配赛季账本。
 * 依赖：Spring Boot、game-web 的内存账本。
 *
 * <p>与其它内存实现同一套约定：dev/test 零依赖启动，{@code ironoath.storage=mongo} 时换
 * {@code MongoSeasonLedger}（见 {@code MongoStoreConfig}），内存 bean 带
 * {@code @ConditionalOnProperty}（与 #37 排掉 {@code @Primary} 那颗雷同一条理由：
 * 缺实现就要上下文起不来，不许静默退回内存版）。
 *
 * <p><b>这一档的丢失后果与其它存储不同量级</b>：账本记的是"这一季这个人已经发过钱"。
 * 内存版重启即空，之后任何人再点一次结算（{@code POST /season/settle} 用的是新 requestId，
 * 幂等键只挡同一个请求的重放），就会给同一批人<b>再发一遍金币</b> ——
 * 而发出去的奖励收不回来。所以它不是"晚点做的存储"，是重复发钱的闸门。
 */
@Configuration
public class SeasonBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SeasonBeansConfig.class);

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage",
            havingValue = GameProperties.STORAGE_MEMORY, matchIfMissing = true)
    public SeasonLedgerStore seasonLedgerStore() {
        LOG.warn("使用内存赛季账本：重启后「这一季已经发过钱」这份凭据就没了，"
                + "再点一次结算会把金币重复发出去（发出去收不回来）。"
                + "仅限本地开发与单测，生产请设 ironoath.storage=mongo（该实现已存在，"
                + "不用它属于配置遗漏而不是能力缺失）");
        return new InMemorySeasonLedger();
    }

    /**
     * 赛季榜与快照（结算依据）。
     *
     * <p><b>它与账本是两个不同的洞</b>：账本丢了是"重复发钱"，榜丢了是"按一张冷榜结算" ——
     * 重启后再点结算会从空榜拍一张快照（快照不可重拍），而账本会把这次算错的名次记成
     * "已经付过"，于是本该拿奖的人永久拿不到。所以生产同样必须用 mongo 模式。
     */
    @Bean
    @ConditionalOnProperty(name = "ironoath.storage",
            havingValue = GameProperties.STORAGE_MEMORY, matchIfMissing = true)
    public SeasonBoardStore seasonBoardStore() {
        LOG.warn("使用内存赛季榜与快照：重启后结算依据是空的，此刻再点一次结算会按冷榜发奖"
                + "并被账本永久固化（本该拿奖的人拿不到）。仅限本地开发与单测，生产请设 ironoath.storage=mongo");
        return new InMemorySeasonBoardStore();
    }
}
