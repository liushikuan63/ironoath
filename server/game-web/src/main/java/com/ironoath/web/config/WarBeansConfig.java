package com.ironoath.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.nation.WarStore;
import com.ironoath.web.store.memory.InMemoryWarStore;

/**
 * 职责：装配国战战事存储。
 * 依赖：Spring Boot、game-web 的内存存储、{@link WarRulesAssembler}。
 *
 * <p>与其它内存实现同一套约定：dev/test 零依赖启动，{@code ironoath.storage=mongo} 时换
 * {@code MongoWarStore}（见 {@code MongoStoreConfig}）。内存 bean 带 {@code @ConditionalOnProperty}
 * 而不是 {@code @Primary}：与 #37 排掉 Primary 那颗同一条理由 ——
 * <b>哪个模式缺实现就让上下文起不来</b>，不许静默退回内存版
 * （{@code BeanAssemblyTest} 与 {@code MongoStorageGuard} 一家守一个模式）。
 *
 * <p><b>这一档装配的存在理由，是把 {@code WarScoreBoard} 从「内核写好了、外层没人接」里拉出来</b>：
 * 该类自交付起在 core 之外的主源码里引用数为 0，于是它的类注释那句「由 game-web 在国战开始时载入、
 * 结束时落盘一次」从来没有执行者，验收 6/7/10 只活在单测里。本格落了承载（端口 + 两套实现 +
 * 这里的装配点 + {@code GET /nation/war} 只读口），
 * 因此 {@code scripts/check-core-wiring.sh} 里那行豁免已按它自己写的撤销条件删除。
 *
 * <p><b>但承载不等于玩法接通</b>：击杀累计、疲劳累积、开战与结算都还没有写入路径，
 * 所以 {@code /nation/war} 在生产上恒回 {@code hasWar=false}。
 * 验收矩阵里那两条（疲劳值上限、国家集结 50 人门槛）因此仍是 ⬜，不因为这个 bean 存在而变。
 * 而国战真上线还压着 B13 的禁止项「没有压测不许开王城战」（B21 验收 10 的压测报告）。
 */
@Configuration
public class WarBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(WarBeansConfig.class);

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage",
            havingValue = GameProperties.STORAGE_MEMORY, matchIfMissing = true)
    public WarStore warStore(WarRulesAssembler rules) {
        LOG.warn("使用内存国战存储：进程重启后战事、积分、疲劳与全服目标的领取记录全部丢失。"
                + "仅限本地开发与单测，生产请设 ironoath.storage=mongo（该实现已存在，"
                + "不用它属于配置遗漏而不是能力缺失）—— "
                + "全服奖励的「每人只领一次」靠的就是那份领取名单，丢了它等于开一个刷金入口");
        return new InMemoryWarStore(rules);
    }
}
