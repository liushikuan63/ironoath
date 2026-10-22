package com.ironoath.web.season;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.season.SeasonTimeline;
import com.ironoath.web.service.ServerCalendar;
import org.springframework.stereotype.Component;

/**
 * 当前赛季王城战窗口的唯一读口：宣战时冻结终点，筹备到点作废。
 * 规则阶段与 UTC+8 日界都读既有 season 表/时间轴，不在国战域另抄天数。
 */
@Component
public final class CapitalWarWindow {

    private final ConfigRegistry configs;
    private final SeasonRulesAssembler assembler;

    public CapitalWarWindow(ConfigRegistry configs, SeasonRulesAssembler assembler) {
        this.configs = configs;
        this.assembler = assembler;
    }

    /** 未启用赛季的 dev/test 返回 null；已启用赛季只有 CAPITAL_WAR 阶段可宣战。 */
    public Long currentEndAt(long now) {
        Long end = endAtOrClosed(now);
        if (end != null && end < 0L) {
            throw new BizException(ErrorCode.WAR_CAPITAL_WINDOW_CLOSED,
                    "当前不在本季王城战开放窗口");
        }
        return end;
    }

    /** 面板只读判据；开发环境未启用赛季时与宣战入口同样放行。 */
    public boolean openAt(long now) {
        Long end = endAtOrClosed(now);
        return end == null || end > 0L;
    }

    /** null=赛季未启用，-1=窗口关闭，正数=排他的窗口终点。 */
    private Long endAtOrClosed(long now) {
        long seasonStart = ServerCalendar.seasonStartOrZero(configs);
        if (seasonStart == 0L) {
            return null;
        }
        if (now < seasonStart) {
            return -1L;
        }
        SeasonTimeline timeline = new SeasonTimeline(assembler.timelineRules(now));
        if (!timeline.allowsCapitalWar(now, seasonStart)) {
            return -1L;
        }
        return timeline.phaseEndAt(now, seasonStart);
    }
}
