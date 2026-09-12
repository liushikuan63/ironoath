package com.ironoath.web.season;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.season.SeasonTimeline;
import com.ironoath.web.dto.generated.SeasonGloryView;
import com.ironoath.web.dto.generated.SeasonPhase;
import com.ironoath.web.dto.generated.SeasonStatusResp;
import com.ironoath.web.service.ServerCalendar;
import org.springframework.stereotype.Service;

/**
 * 职责：赛季状态查询与「本赛季当前允不允许玩家间攻击」这一条判定的唯一出口（B14 §一）。
 * 依赖：game-config（时间轴来自 season 表）、game-core 的 {@link SeasonTimeline}、
 *       {@link ServerCalendar}（赛季锚点是部署参数）。
 *
 * <p><b>为什么判定要放在这里而不是各入口自己算</b>：备战期禁战、问鼎期开王城、休赛期只读，
 * 这三条都是同一条时间轴上的读数。每个入口自己 {@code if (now > x)} 一份，改一次轴就要改多处，
 * 而漏掉的那处的表现是「备战期还能打人」——不报错，只在战报里多出一堆本该不存在的仗。
 */
@Service
public class SeasonAppService {

    private final ConfigRegistry configs;
    private final TimeService timeService;
    private final SeasonRulesAssembler assembler;
    /** 实时榜名次的数据源。方向安全：结算服务不反向依赖本类。 */
    private final SeasonSettlementService settlements;

    public SeasonAppService(ConfigRegistry configs, TimeService timeService,
                            SeasonRulesAssembler assembler, SeasonSettlementService settlements) {
        this.configs = configs;
        this.timeService = timeService;
        this.assembler = assembler;
        this.settlements = settlements;
    }

    /**
     * GET /season/status。
     *
     * <p>未配置 {@code SEASON_START_AT} 时 phase / seasonStartAt / dayIndex / phaseEndAt / myRank
     * 一律为 <b>null 而不是 0</b>：0 会被读成「赛季第 1 天、扩张期、第 0 名」，
     * 而真相是「这个服还没启用赛季」。客户端按 phase 为 null 把赛季面板整块藏起来。
     *
     * @param playerId 可空。带身份时才回 {@code myRank}（实时榜名次，0 = 未上榜）；
     *                 不带也允许 —— 赛季阶段是全服信息，不该被身份门槛挡住
     */
    public SeasonStatusResp status(String playerId) {
        long now = timeService.serverNow();
        SeasonTimeline timeline = timeline();
        long totalDays = timeline.rules().totalDays();
        long start = ServerCalendar.seasonStartOrZero(configs);
        if (start == 0L) {
            // 赛季没启用就没有荣耀可展示：glory 与 myRank 同一条口径，回 null 而不是回一个全零对象
            return new SeasonStatusResp("", null, null, null, totalDays, null,
                    true, false, false, null, null, now);
        }
        SeasonTimeline.Phase phase = timeline.phaseAtTime(now, start);
        boolean identified = playerId != null && !playerId.isBlank();
        return new SeasonStatusResp(timeline.seasonId(), SeasonPhase.valueOf(phase.name()), start,
                (int) SeasonTimeline.dayIndexOf(now, start), totalDays,
                timeline.phaseEndAt(now, start),
                phase.allowsPvp(), phase.allowsCapitalWar(), phase.readOnly(),
                identified ? gloryView(playerId) : null,
                identified ? settlements.liveRank(playerId) : null, now);
    }

    /**
     * 我的荣耀视图。<b>答案取自 {@code settlements.gloryOf}，不是直接读主存档那一份</b>：
     * 账本是真相、主存档是缓存，两者之间只留一个写者（见
     * {@link SeasonSettlementService#gloryOf}）。所以"面板显示的和账本对得上"这件事
     * 不需要额外维护 —— 读一次就顺带对一次账。
     */
    private SeasonGloryView gloryView(String playerId) {
        com.ironoath.core.player.PlayerGlory glory = settlements.gloryOf(playerId);
        return new SeasonGloryView(glory.gloryLevel(),
                com.ironoath.web.dto.generated.SeasonTier.valueOf(glory.highestTier().name()),
                glory.badges());
    }

    /**
     * 玩家间攻击是否被当前赛季阶段放行。<b>由 {@code AttackGuardService} 在每个 PVP 入口调用</b>
     * （单人攻击、集结攻击、拦截采集队都走那个漏斗，所以这里不需要第二道检查）。
     *
     * <p>只作用于 PVP：打野不受赛季阶段限制 —— 备战期禁止的是「人对人」，
     * 把开服头几天的进攻性玩法全砍掉只会让新号无事可做。
     *
     * <p>未配置赛季锚点 ⇒ 放行（见 {@link ServerCalendar#seasonStartOrZero}）。
     */
    public void requirePvpAllowed(long now) {
        long start = ServerCalendar.seasonStartOrZero(configs);
        if (start == 0L) {
            return;
        }
        SeasonTimeline timeline = timeline();
        SeasonTimeline.Phase phase = timeline.phaseAtTime(now, start);
        if (phase.allowsPvp()) {
            return;
        }
        throw new BizException(ErrorCode.SEASON_PVP_LOCKED, message(timeline, phase, start, now));
    }

    /**
     * 提示要说清「什么时候开始能打」，而不是只说一句「不允许」。
     * 玩家在备战期真正的问题是「我还要等多久」，答不上来就会去问客服或重启游戏试。
     *
     * <p><b>两种禁战对应两种答案</b>：备战期等的是<b>本赛季内</b>的下一个阶段（第 N 天起），
     * 而休赛期等的是<b>下一赛季</b> —— 本赛季自己的阶段表已经走完了。给休赛期回一句
     * 「第 8 天起开放」等于把本赛季当成循环，玩家真会等到第 8 天然后发现还锁着。
     */
    private static String message(SeasonTimeline timeline, SeasonTimeline.Phase phase,
                                  long seasonStart, long now) {
        if (phase == SeasonTimeline.Phase.REST) {
            return "本赛季已结束（休赛期），玩家间攻击已停止，等待下一赛季开启。打野不受限制";
        }
        String when = "本赛季后续阶段不再开放玩家间攻击";
        for (SeasonTimeline.Stage s : timeline.rules().stages()) {
            if (s.phase().allowsPvp()) {
                when = "第 " + (s.startDayOffset() + 1) + " 天起开放";
                break;
            }
        }
        long day = SeasonTimeline.dayIndexOf(now, seasonStart) + 1;
        return "当前处于赛季备战期，禁止玩家间攻击（现在是赛季第 " + day + " 天，" + when
                + "）。打野不受限制";
    }

    /**
     * 每次都重新装配，不缓存。
     *
     * <p>与其余装配器同一条理由：配置支持热更，而时间轴恰恰是「赛季中途发现禁战期太长要缩短」
     * 那一类会被改的东西。缓存的表现是改了表却没生效，而这件事没有任何报错。
     */
    private SeasonTimeline timeline() {
        return new SeasonTimeline(assembler.timelineRules());
    }
}
