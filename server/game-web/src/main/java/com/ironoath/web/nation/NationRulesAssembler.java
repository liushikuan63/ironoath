package com.ironoath.web.nation;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.NationConfigCfg;
import com.ironoath.core.nation.Nation;

/**
 * 职责：把 nation_config 表与 global 参数装配成 {@link Nation.Rules}（铁律 1：数值零硬编码）。
 * 依赖：game-config、game-core。
 *
 * <p>与 {@code SocialRulesAssembler}、{@code ReleaseRulesAssembler} 是同一种东西：
 * game-core 按 B00 分层规则读不到配置表，所以所有数值都必须由外层解析好再传进去。
 *
 * <p><b>单位换算的两处，漏一处就是静默失效</b>：
 * {@code NATION_JOIN_COOLDOWN_HOURS} 是小时而 {@code Rules.joinCooldownMillis} 要毫秒（×3600000），
 * {@code warCooldownHours} 则原样传（{@code LevelRule} 收的就是小时）。
 * 两个相邻的字段一个要换算一个不要，是最容易搞混的形状 ——
 * 漏乘的症状是「退国冷却只有 24 毫秒」，功能测试完全看不出来。
 *
 * <p><b>每次调用都重新装配，不缓存</b>：配置表支持热更，缓存会让热更在国家这条路径上失效。
 */
@Component
public class NationRulesAssembler {

    private static final long MILLIS_PER_HOUR = 3600L * 1000L;

    private final ConfigRegistry configs;

    public NationRulesAssembler(ConfigRegistry configs) {
        this.configs = configs;
    }

    public Nation.Rules rules() {
        List<NationConfigCfg> rows = configs.all(NationConfigCfg.class);
        if (rows.isEmpty()) {
            throw new IllegalStateException("nation_config 表为空：算不出人数上限与国库上限，国家功能无法装配");
        }
        List<Nation.LevelRule> levels = new ArrayList<>(rows.size());
        for (NationConfigCfg row : rows) {
            levels.add(new Nation.LevelRule(row.nationLevel(), row.memberCap(), row.officeCount(),
                    row.treasuryCap(), row.policySlotCount(), row.warCooldownHours()));
        }
        // 解锁前置在每一行都是同一份（16 级 / D14），取第一行；
        // 若将来按等级分档，这里要改成「取 1 级那一行」而不是「取第一行」
        NationConfigCfg first = rows.get(0);
        return new Nation.Rules(levels,
                first.unlockMainLevel(),
                first.unlockDayOffset(),
                (int) configs.longParam("NATION_MAX_PER_KINGDOM"),
                configs.longParam("NATION_JOIN_COOLDOWN_HOURS") * MILLIS_PER_HOUR,
                configs.longParam("NATION_TAX_WEEKLY_PER_ALLIANCE"),
                (int) configs.longParam("NATION_TREASURY_LOG_RETENTION"),
                (int) configs.longParam("NATION_OFFICE_SEAT_TOTAL"),
                // DECIMAL 已由 fixedParam 给成定点（10000=1.0），这里不能再乘一次
                configs.fixedParam("NATION_OFFICER_SPEND_WEEKLY_RATIO"));
    }
}
