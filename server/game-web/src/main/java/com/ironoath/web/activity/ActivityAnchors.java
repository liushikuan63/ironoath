package com.ironoath.web.activity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.player.PlayerRepository;

/**
 * 职责：活动窗口的两块锚点（B17 §五① 的双锚点）—— 全服锚与玩家锚，各自只有一处实现。
 * 依赖：{@link ConfigRegistry}（部署参数）、{@link PlayerRepository}（建档时刻）。
 *
 * <p><b>为什么单独一个组件而不是在两处各算一遍</b>：锚点决定窗口，窗口决定"这一轮从哪开始"。
 * 监听器（事件路径）与服务（读取路径）各算一份的话，两条路径会在跨轮那一刻用不同的锚 ——
 * 症状是"读的时候显示 0/50，做完一只怪却变成 33/50"，而两处代码看起来都对。
 *
 * <p><b>{@code SERVER_OPEN_AT} 未配置时退回玩家建档时刻</b>（开发期）：
 * 开服时刻是<b>部署参数</b>，必须先 {@code hasParam} 探一次（{@code longParam} 对未知 id 直接抛
 * {@code ConfigException}）。开发期本来就没有"开服"这件事，退回个人建档时刻让八行活动在本地
 * 真的会一轮一轮转起来；上线必须配 {@code SERVER_OPEN_AT}（{@code ProductionReadiness} 的必配清单里）。
 * 回退只在第一次发生时 WARN 一条，不刷屏。
 */
@Component
public class ActivityAnchors {

    private static final Logger LOG = LoggerFactory.getLogger(ActivityAnchors.class);

    private final ConfigRegistry configs;
    private final PlayerRepository players;
    private volatile boolean warnedAboutMissingOpen;

    public ActivityAnchors(ConfigRegistry configs, PlayerRepository players) {
        this.configs = configs;
        this.players = players;
    }

    /**
     * 全服锚（六类全服活动的窗口起点）。
     *
     * @param playerId 未配置开服时刻时用它退回到建档时刻
     */
    public long serverOpenMs(String playerId) {
        if (configs.hasParam("SERVER_OPEN_AT")) {
            long openAt = configs.longParam("SERVER_OPEN_AT");
            if (openAt > 0L) {
                return openAt;
            }
        }
        if (!warnedAboutMissingOpen) {
            warnedAboutMissingOpen = true;
            LOG.warn("未配置 SERVER_OPEN_AT：六类全服活动的窗口退回玩家建档时刻（仅限本地开发）—— "
                    + "上线前必须补上这个部署参数，否则全服活动的轮换是每人一套");
        }
        // 1 是保底：核心要求锚点为正的时间戳（0 会让 ActivityWindow 抛）
        return Math.max(1L, playerAnchorMs(playerId));
    }

    /**
     * 玩家锚（LOGIN_STREAK 两行的窗口起点）= 建档时刻。
     *
     * <p>建档就是首次登录（{@code PlayerInitService} 在同一笔请求里建号），所以这里用的正是裁决里的
     * "玩家首次登录时刻"。读不到存档时返回 0 —— 核心会退化为全服锚（那是"这个人还没有个人锚"）。
     */
    public long playerAnchorMs(String playerId) {
        // 只要建档时刻：走窄读口，不要 findByPlayerId —— 为一位数搬整份存档
        // （资源表 / PVP 账本 / 科技 / 头像框集合），而这条挂在每条活动事件上
        return players.findCreatedAt(playerId).orElse(0L);
    }
}
