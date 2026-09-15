package com.ironoath.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ironoath.web.ops.TrackEventStore;
import com.ironoath.web.release.ReleaseRulesAssembler;
import com.ironoath.web.release.SupportConfig;
import com.ironoath.web.store.memory.InMemoryTrackStore;

/**
 * 职责：装配埋点与崩溃上报的存储。
 * 依赖：Spring Boot、{@link ReleaseRulesAssembler}、game-web 的内存存储。
 *
 * <p>与 {@code BattleBeansConfig} 同一套约定：内存实现供 dev 与单测零依赖启动，
 * 启动时的 WARN 让任何人在跑服务端时都看到这个缺口。
 *
 * <p><b>埋点存储的缺失后果与战报不同，所以 WARN 的措辞也不同</b>：
 * 战报丢了玩家会失去复盘线索，埋点丢了则是<b>运营侧瞎了</b> ——
 * 上线第一天最需要的正是「玩家卡在哪一步走的」，而那批数据只在埋点里。
 * 换句话说：战报存储缺失影响的是玩家的明天，埋点存储缺失影响的是我们能不能看懂今天。
 */
@Configuration
public class OpsBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(OpsBeansConfig.class);

    /**
     * 客服与退款入口的配置。两项都来自环境变量：corpId 与 AppID 同族（微信账号侧的东西），
     * 进版本库等于把账号配置公开，进配置表则会让「表随包下发」这条约定多一类不该外泄的内容。
     * 未配置时下发 null，客户端仍显示入口并说明"本环境未配置客服" —— 藏起来等于提审时没有这个入口。
     */
    @Bean
    public SupportConfig supportConfig(
            @Value("${WECHAT_SUPPORT_CORP_ID:}") String corpId,
            @Value("${WECHAT_SUPPORT_URL:}") String url) {
        SupportConfig config = new SupportConfig(corpId, url);
        if (config.configured()) {
            LOG.info("客服入口已配置（corpId 非空），设置页一级可见");
        } else {
            LOG.warn("客服/退款入口未配置：设置页仍会显示入口，但点下去只会说明未配置。"
                    + "提审会查这一项，上线前请设置 WECHAT_SUPPORT_CORP_ID / WECHAT_SUPPORT_URL"
                    + "（prod 下缺它会让服务拒绝启动）");
        }
        return config;
    }

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage",
            havingValue = GameProperties.STORAGE_MEMORY, matchIfMissing = true)
    public TrackEventStore trackEventStore(ReleaseRulesAssembler assembler) {
        int cap = assembler.trackStoreMaxEvents();
        LOG.warn("使用内存埋点存储：进程重启后全部埋点与崩溃记录丢失，保留上限 {} 条（来源 "
                + "global.TRACK_STORE_MAX_EVENTS，那只是 dev 的堆兜底；生产按时间保留，"
                + "不得短于 DASHBOARD_RETENTION_DAYS 的最大值）。仅限本地开发与单测，"
                + "生产请设 ironoath.storage=mongo（该实现已存在，不用它属于配置遗漏而不是能力缺失）—— "
                + "上线后没有埋点数据等于运营侧瞎了，而 D1/D3/D7/D30 留存与卡点流失率全部依赖它", cap);
        return new InMemoryTrackStore(cap);
    }
}
