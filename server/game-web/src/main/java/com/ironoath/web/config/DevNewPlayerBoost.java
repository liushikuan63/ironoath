package com.ironoath.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 职责：<b>只在 dev profile</b> 生效的新号开局提速（见 {@link NewPlayerBoost}）。
 * 依赖：Spring 的 {@code @Profile} 与 {@code @Component}；数值从环境变量读。
 *
 * <p><b>两个环境变量</b>（都不设 = 与改动前完全一致，"模式隔离"那条纪律的硬要求）：
 * <ul>
 *   <li>{@code IRONOATH_DEV_CITY_LEVEL} —— 新号主城等级。设成 16 就能建国家（B13 的前置）。</li>
 *   <li>{@code IRONOATH_DEV_START_AMOUNT} —— 新号每种资源的初始数量。
 *       建盟要 500 金币而金币 perHour=0，脚本攒不出来，所以这一步是必需的。</li>
 * </ul>
 *
 * <p><b>为什么 {@code @Profile("dev")} 是承重的那一层</b>：prod 与 test 上下文里这个 bean 根本不存在，
 * {@code ObjectProvider.getIfAvailable()} 返回 null ⇒ 走 {@link NewPlayerBoost#NONE} ⇒ 走配置表。
 * 于是"prod 读不到它"是**结构事实**，不是"忘了设环境变量"。
 * {@code DevNewPlayerBoostTest} 用反射钉住这条注解 —— 有人把它摘掉，测试立刻红。
 *
 * <p><b>开档时会打一行 INFO</b>：本地起服务的人一眼能看到"新号是提速档"，
 * 而这行日志在 prod 不可能出现（bean 不存在）。审计一条比注释一条可靠。
 */
@Component
@Profile("dev")
public class DevNewPlayerBoost implements NewPlayerBoost {

    private static final Logger LOG = LoggerFactory.getLogger(DevNewPlayerBoost.class);

    /** 新号主城等级的环境变量名。 */
    public static final String CITY_LEVEL_ENV = "IRONOATH_DEV_CITY_LEVEL";
    /** 新号资源初始数量的环境变量名。 */
    public static final String START_AMOUNT_ENV = "IRONOATH_DEV_START_AMOUNT";

    private final int cityLevel;
    private final long startAmount;

    public DevNewPlayerBoost() {
        this.cityLevel = readPositiveInt(CITY_LEVEL_ENV);
        this.startAmount = readPositiveLong(START_AMOUNT_ENV);
        if (cityLevel > 0 || startAmount > 0) {
            LOG.info("dev 提速档已开：新号主城等级={}（0=按配置表）、每种资源初始={}（0=按配置表）。"
                            + "环境变量 {} / {}；prod 读不到这一档（@Profile(\"dev\")）",
                    cityLevel, startAmount, CITY_LEVEL_ENV, START_AMOUNT_ENV);
        }
    }

    @Override
    public int cityLevel() {
        return cityLevel;
    }

    @Override
    public long startAmount() {
        return startAmount;
    }

    /**
     * 读一个正整数环境变量。**解析失败与"没设"同归为 0（不覆盖）**：
     * 一个写错的环境变量（{@code abc}、负数、0）不该让新号变成 0 级主城或开局一个亿资源。
     * 拼错名字的代价是"提速没生效"，而那会在开档时少打一行 INFO，人能看出来。
     */
    private static int readPositiveInt(String name) {
        long value = readPositiveLong(name);
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    private static long readPositiveLong(String name) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0L ? value : 0L;
        } catch (NumberFormatException e) {
            LOG.warn("环境变量 {} 的值「{}」不是整数，按不覆盖处理", name, raw);
            return 0L;
        }
    }
}
