package com.ironoath.web.service;

import com.ironoath.common.time.DayKey;
import com.ironoath.config.ConfigRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 职责：「开服第 N 天」的唯一实现（小队 D1 / 联盟 D3 / 国家 D14 这些解锁门槛的天数轴）。
 * 依赖：game-config 的只读参数。
 *
 * <p><b>为什么必须只有一处</b>：这条口径原先在 {@code SocialAppService} 与 {@code NationAppService}
 * 里各有一份逐字相同的拷贝。两份实现在改掉任何一条时都会漂移成
 * 「联盟按 A 口径解锁而国家按 B 口径解锁」，而这种漂移不会让任何单测变红，
 * 只会表现为同一批玩家在不同入口看到不同的解锁时间。
 *
 * <p><b>这里算的是 UTC+8 自然日</b>：开服当天恒为第 0 天，次日 0 点进入第 1 天，
 * 与 {@link DayKey} 的日限次、赛季阶段共用同一条日历轴。
 * 2026-09-13 裁决：不再按「开服时刻起满 24 小时」计算，否则同一个玩家会在
 * 日限次刚重置后又等一个开服钟点才看到联盟/国家门槛解锁。
 */
public final class ServerCalendar {

    private static final Logger LOG = LoggerFactory.getLogger(ServerCalendar.class);

    /**
     * 未配置开服时刻时返回的「极大天数」。
     *
     * <p><b>放行而不是回退到玩家注册时间</b>：回退会把「开服 D14 解锁国家」变成
     * 「每个人注册后第 14 天」，同批玩家的解锁时刻散落在一个月里，国家永远凑不齐人。
     * 放行是开发期的正确选择：此时本来就没有「开服」这件事，而主城等级那道门槛仍然照常生效。
     */
    private static final long UNBOUNDED_DAYS = Long.MAX_VALUE / 2L;

    private ServerCalendar() {
    }

    /**
     * 开服至今的天数（0 = 开服当天）。
     *
     * <p>⚠️ <b>上线前必须配置 {@code SERVER_OPEN_AT}</b>，否则一切「开服第 N 天」门槛形同虚设。
     * 这条已经写进 B16 上线清单，缺配置时这里会打 DEBUG 而不是静默放行 ——
     * 静默放行是最坏的形态：门槛看起来生效了（没人被拒），实际上一个都没在管。
     */
    public static long daysSinceOpen(ConfigRegistry configs, long now) {
        // 开服时刻属于部署参数而不是游戏数值，所以它不在 global.json 里（那张表放策划要调的数）。
        // 必须先 hasParam 探一次：longParam 对未知 id 直接抛 ConfigException，
        // 把守卫写成 `longParam("SERVER_OPEN_AT") > 0` 根本执行不到比较那一步就会炸
        if (!configs.hasParam("SERVER_OPEN_AT") || configs.longParam("SERVER_OPEN_AT") <= 0L) {
            LOG.debug("未配置 SERVER_OPEN_AT：开服天数门槛一律放行，上线前必须补上这个部署参数");
            return UNBOUNDED_DAYS;
        }
        return daysBetweenOpenAndNow(configs.longParam("SERVER_OPEN_AT"), now);
    }

    /** 纯日期运算，独立出来便于把「开服日 23 点 vs 次日 0 点」钉进单测。 */
    static long daysBetweenOpenAndNow(long openAt, long now) {
        return Math.max(0L, DayKey.daysBetween(openAt, now));
    }

    /**
     * 赛季开始时刻（毫秒）。<b>返回 0 表示赛季规则未启用</b>。
     *
     * <p>与开服时刻同一条口径：都是<b>部署参数</b>而不是游戏数值，所以都不进 global 表，
     * 也都必须先 {@code hasParam} 探一次（{@code longParam} 对未知 id 直接抛 ConfigException）。
     *
     * <p>未配置时选择「不启用」而不是抛，也不是「拿开服时刻顶上」：开发期本来就没有赛季这件事，
     * 若因此把所有 PVP 锁死，症状会是「PVP 功能坏了」，而真相只是少配了一个部署参数。
     * 但它是<b>上线必配项</b> —— 不配就等于赛季的禁战期、王城窗口、结算时点全部不存在。
     */
    public static long seasonStartOrZero(ConfigRegistry configs) {
        if (!configs.hasParam("SEASON_START_AT")) {
            LOG.debug("未配置 SEASON_START_AT：赛季规则未启用（无禁战期、无王城窗口、无结算时点）");
            return 0L;
        }
        long start = configs.longParam("SEASON_START_AT");
        if (start <= 0L) {
            LOG.debug("SEASON_START_AT 配了非正值 {}：按未启用处理", start);
            return 0L;
        }
        return start;
    }
}
